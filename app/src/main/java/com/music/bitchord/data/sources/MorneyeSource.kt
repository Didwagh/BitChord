package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.mornye.MorneyeAddonException
import com.music.bitchord.data.sources.mornye.MorneyePackageManager
import com.music.bitchord.data.sources.mornye.MorneyeRuntime
import com.music.bitchord.data.sources.mornye.parseMorneyeDownloadResult
import com.music.bitchord.data.sources.mornye.parseMorneyeTrack
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * [MusicSource] backed by one installed Mornye JS addon.
 *
 * HOW PLAYBACK WORKS (different from Lokal):
 *
 * Lokal calls download() with no path → gets back raw bytes → plays from memory.
 * That means audio isn't fetched until after the previous track ends → audible gap.
 *
 * BitChord instead:
 *  1. search() returns Song objects with composite videoIds.
 *  2. stream() is called on the NEXT song while the current one is still playing
 *     (BitChord's existing AudioCache prefetch drives this).
 *  3. stream() calls download(rawId, quality, cacheFilePath) → addon fetches +
 *     optionally decrypts the file into cacheFilePath.
 *  4. Returns SourceStream(url = "file:///…") → ExoPlayer reads the local file
 *     with zero network latency → gapless.
 *  5. Files land in <cacheDir>/mornye/<addonKey>/ — the same directory
 *     BitChord's Downloads sheet can already browse and export.
 */
class MorneyeSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String    get() = config.id
    override val kind: SourceKind    get() = SourceKind.MORNYE
    override val displayName: String get() = config.displayName

    val addonKey: String get() = config.baseUrl

    private fun runtime(): MorneyeRuntime? = MorneyePackageManager.runtime(addonKey)

    // ── Health ────────────────────────────────────────────────────────────

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        val pkg = MorneyePackageManager.packages.value.firstOrNull { it.key == addonKey }
            ?: return@withContext SourceHealth.Rejected("Addon not installed")
        if (!pkg.enabled)
            return@withContext SourceHealth.Rejected("Addon is disabled")
        val caps = MorneyePackageManager.capabilities(addonKey)
            ?: return@withContext SourceHealth.Rejected("Cannot read addon capabilities")
        if (!caps.searches)
            return@withContext SourceHealth.Rejected("Addon type not supported (needs download_provider)")
        val rt = runCatching { runtime() }.getOrNull()
            ?: return@withContext SourceHealth.Unreachable("Addon could not start")
        return@withContext if (rt.methods.isNotEmpty())
            SourceHealth.Ok("v${pkg.version} · ${rt.methods.size} methods")
        else
            SourceHealth.Unreachable("Addon loaded but exported no methods")
    }

    // ── Search ────────────────────────────────────────────────────────────

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val rt = runtime() ?: return@withContext emptyList()

        // Try searchTracks first (most addons), fall back to customSearch
        val raw = runCatching {
            when {
                "searchTracks" in rt.methods ->
                    rt.invoke("searchTracks", listOf(query.trim(), limit))
                "customSearch" in rt.methods ->
                    rt.invoke("customSearch",
                        listOf(query.trim(), mapOf("limit" to limit, "filter" to "tracks")))
                else -> {
                    TrackLog.d(TAG, "[$addonKey] no search method"); null
                }
            }
        }.onFailure { TrackLog.w(TAG, "[$addonKey] search error: ${it.message}") }
            .getOrNull() ?: return@withContext emptyList()

        val items: List<Any?> = when (raw) {
            is List<*>      -> raw
            is JSONArray    -> (0 until raw.length()).map { raw.opt(it) }
            is JSONObject   -> {
                val arr = raw.optJSONArray("tracks") ?: raw.optJSONArray("results")
                    ?: raw.optJSONArray("items")
                if (arr != null) (0 until arr.length()).map { arr.opt(it) } else emptyList()
            }
            else -> emptyList()
        }

        items.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val map: Map<*, *> = when (item) {
                is JSONObject -> item.toKotlinMap()
                is Map<*, *>  -> item
                else          -> return@mapNotNull null
            }
            val track = parseMorneyeTrack(map) ?: return@mapNotNull null
            Song(
                videoId       = SourceRegistry.trackKey(config.id, track.id),
                title         = track.title,
                artist        = track.artist,
                albumName     = track.album.ifBlank { null },
                thumbnailUrl  = track.artworkUrl,
                durationText  = track.durationMs?.div(1000)?.let { sec ->
                    String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
                },
                sourceQuality = when {
                    track.isAtmos    -> ModuleSource.DOLBY
                    track.isLossless -> ModuleSource.LOSSLESS
                    else             -> null
                },
                sourceHiRes = track.isLossless && !track.isAtmos,
            )
        }
    }

    // ── Stream ────────────────────────────────────────────────────────────

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            val rt = runtime() ?: return@withContext null

            // Strip the composite key to get the addon's own track ID.
            // Song.videoId = "src:<configId>::<addonTrackId>"
            val rawTrackId = SourceTrackKeys.parse(trackId)?.second ?: trackId

            // Optional initialize call to refresh session
            runCatching {
                if ("initialize" in rt.methods) {
                    val settings = runCatching {
                        JSONObject(MorneyePackageManager.currentSettings(addonKey)).toKotlinMap()
                    }.getOrDefault(emptyMap<String, Any?>())
                    rt.invoke("initialize", listOf(settings))
                }
            }.onFailure { TrackLog.d(TAG, "[$addonKey] initialize: ${it.message}") }

            val qualityId = resolveQuality(request)

            // Build the output file path in the addon's own cache dir.
            // The addon may rename the extension (e.g. .flac → .m4a for JioSaavn),
            // so we use .flac as a default and trust file_path in the result.
            val addonCacheDir = File(MorneyePackageManager.downloadCacheDir, addonKey)
                .also { it.mkdirs() }
            val outputPath = File(addonCacheDir, "${sanitise(rawTrackId)}.flac").absolutePath

            TrackLog.d(TAG, "[$addonKey] download $rawTrackId quality=$qualityId → $outputPath")

            val downloadResult = runCatching {
                rt.invoke("download",
                    listOf(rawTrackId, qualityId, outputPath, null,
                        mapOf("forPlayback" to true)))
            }.onFailure {
                if (it is MorneyeAddonException) TrackLog.d(TAG, "[$addonKey] addon: ${it.message}")
                else TrackLog.w(TAG, "[$addonKey] download failed: ${it.message}")
            }.getOrNull() ?: return@withContext null

            val parsed = parseMorneyeDownloadResult(downloadResult as? JSONObject
                ?: run {
                    // If invoke returned a Map, re-encode it
                    @Suppress("UNCHECKED_CAST")
                    val map = downloadResult as? Map<*, *>
                    if (map != null) JSONObject(map.mapKeys { it.key.toString() }) else null
                } ?: return@withContext null)

            if (!parsed.success) {
                TrackLog.d(TAG, "[$addonKey] download() returned success=false: ${parsed.errorMessage}")
                return@withContext null
            }

            val filePath = parsed.filePath
            if (filePath.isNullOrBlank()) {
                TrackLog.w(TAG, "[$addonKey] download() success but no file_path")
                return@withContext null
            }

            if (!File(filePath).exists()) {
                TrackLog.w(TAG, "[$addonKey] file_path does not exist: $filePath")
                return@withContext null
            }

            // Determine the codec from the downloaded file format
            val codec = when (parsed.format?.lowercase()) {
                "flac"       -> "flac"
                "alac", "m4a" -> "alac"
                "mp3"        -> "mp3"
                "aac"        -> "aac"
                else         -> guessCodecFromPath(filePath)
            }
            val isLossless = codec in setOf("flac", "alac", "wav")
            val isAtmos    = codec in setOf("eac3", "eac3-joc")

            val format = StreamFormat(
                codec        = codec,
                kbps         = null,
                kbpsInferred = false,
            )

            TrackLog.d(TAG, "[$addonKey] ready: file=$filePath codec=$codec")
            SourceStream(
                url         = "file://$filePath",
                format      = format,
                headers     = emptyMap(),
                durationSec = null,
            )
        }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun resolveQuality(request: StreamRequest): String? {
        val caps = MorneyePackageManager.capabilities(addonKey) ?: return null
        val opts = caps.qualityOptions
        if (opts.isEmpty()) return null
        return when (request) {
            is StreamRequest.Lossless -> bestLosslessQuality()
            is StreamRequest.Best     -> opts.lastOrNull()?.id
            is StreamRequest.Capped   ->
                if (request.maxKbps >= 320) opts.lastOrNull()?.id else opts.firstOrNull()?.id
        }
    }

    private fun bestLosslessQuality(): String? {
        val opts = MorneyePackageManager.capabilities(addonKey)?.qualityOptions ?: return null
        return opts.firstOrNull { q ->
            q.label.lowercase(Locale.ROOT).let { l ->
                "lossless" in l || "flac" in l || "hifi" in l ||
                    "hi-res" in l || "max" in l || "best" in l || "24" in l
            }
        }?.id ?: opts.lastOrNull()?.id
    }

    private fun guessCodecFromPath(path: String): String? = when {
        path.endsWith(".flac", ignoreCase = true) -> "flac"
        path.endsWith(".m4a",  ignoreCase = true) -> "aac"
        path.endsWith(".mp3",  ignoreCase = true) -> "mp3"
        path.endsWith(".alac", ignoreCase = true) -> "alac"
        path.endsWith(".ogg",  ignoreCase = true) -> "vorbis"
        path.endsWith(".opus", ignoreCase = true) -> "opus"
        else -> null
    }

    private fun sanitise(id: String) =
        id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(80)

    companion object {
        private const val TAG = "BitChord/Mornye"
    }

    private fun JSONObject.toKotlinMap(): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        for (k in keys()) m[k] = opt(k)
        return m
    }
}
