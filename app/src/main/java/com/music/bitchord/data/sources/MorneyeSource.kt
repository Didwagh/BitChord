package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.mornye.MorneyeAddonException
import com.music.bitchord.data.sources.mornye.MorneyePackageManager
import com.music.bitchord.data.sources.mornye.MorneyeRuntime
import com.music.bitchord.data.sources.mornye.MorneyeStream
import com.music.bitchord.data.sources.mornye.parseMorneyeStream
import com.music.bitchord.data.sources.mornye.parseMorneyeTrack
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * A [MusicSource] backed by one installed Mornye addon.
 *
 * Mornye addons are JavaScript plugins (.sflx / .morneye-ext packages) that
 * expose `searchTracks(query, limit)` and `download(id, quality)` methods.
 * This source bridges those methods into BitChord's existing source pipeline so
 * the addon's streams flow through AudioCache exactly like any other source —
 * prefetched before the previous song ends and cached on disk in the same
 * ExoPlayer SimpleCache that the Download sheet already exports from.
 *
 * That is the key behavioural difference from Lokal's Mornye integration:
 *  - **Lokal** fetches the stream after the first song ends  → audible gap.
 *  - **BitChord** pre-fetches while the current song is playing → gapless,
 *    and the bytes land in the standard cache the Download sheet can export.
 *
 * One [MorneyeSource] instance per installed addon, held by [SourceRegistry].
 * [configId] is the SourceConfig id; the addon's storage key lives in
 * [SourceConfig.baseUrl].
 */
class MorneyeSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String    get() = config.id
    override val kind: SourceKind    get() = SourceKind.MORNYE
    override val displayName: String get() = config.displayName

    /** The Mornye addon storage key (stashed in baseUrl by [SourceRegistry.syncMorneyePackages]). */
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
            return@withContext SourceHealth.Rejected(
                "Addon type not supported (needs download_provider)"
            )

        val rt = runCatching { runtime() }.getOrNull()
            ?: return@withContext SourceHealth.Unreachable("Addon could not start")

        return@withContext if (rt.methods.isNotEmpty()) {
            SourceHealth.Ok("v${pkg.version} · ${rt.methods.size} methods")
        } else {
            SourceHealth.Unreachable("Addon loaded but exported no methods")
        }
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

        val results = runCatching {
            when {
                "searchTracks" in rt.methods ->
                    rt.invoke("searchTracks", listOf(query.trim(), limit))
                "customSearch" in rt.methods ->
                    rt.invoke("customSearch",
                        listOf(query.trim(), mapOf("limit" to limit, "filter" to "tracks")))
                else -> {
                    TrackLog.d(TAG, "[$addonKey] no search method available")
                    return@withContext emptyList()
                }
            }
        }.onFailure { TrackLog.w(TAG, "[$addonKey] search failed: ${it.message}") }
            .getOrNull() ?: return@withContext emptyList()

        val tracks: List<Any?> = when (results) {
            is List<*>              -> results
            is org.json.JSONArray   ->
                (0 until results.length()).map { results.opt(it) }
            is JSONObject           -> {
                val arr = results.optJSONArray("tracks") ?: results.optJSONArray("results")
                if (arr != null) (0 until arr.length()).map { arr.opt(it) } else emptyList()
            }
            else -> emptyList()
        }

        tracks.mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            val map: Map<*, *> = when (raw) {
                is JSONObject -> raw.toKotlinMap()
                is Map<*, *>  -> raw
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

            // Optionally initialise / refresh session.
            runCatching {
                if ("initialize" in rt.methods) {
                    val settings = runCatching {
                        JSONObject(MorneyePackageManager.currentSettings(addonKey)).toKotlinMap()
                    }.getOrDefault(emptyMap<String, Any?>())
                    rt.invoke("initialize", listOf(settings))
                }
            }.onFailure { TrackLog.d(TAG, "[$addonKey] initialize: ${it.message}") }

            // The trackId we store in Song.videoId is the full composite key
            // "src:<configId>::<addonTrackId>". Strip the prefix back to the
            // raw addon-side ID before passing it to the addon's download().
            val rawTrackId = SourceTrackKeys.parse(trackId)?.second ?: trackId

            val qualityId = resolveQuality(request)
            val downloadArgs = buildList {
                add(rawTrackId)
                add(qualityId)
                add(mapOf("forPlayback" to true))
            }

            val result = runCatching {
                rt.invoke("download", downloadArgs)
            }.onFailure {
                if (it is MorneyeAddonException) {
                    TrackLog.d(TAG, "[$addonKey] addon said: ${it.message}")
                } else {
                    TrackLog.w(TAG, "[$addonKey] download failed: ${it.message}")
                }
            }.getOrNull() ?: return@withContext null

            @Suppress("UNCHECKED_CAST")
            val streamMap: Map<*, *> = when (result) {
                is JSONObject -> result.toKotlinMap()
                is Map<*, *>  -> result
                else          -> {
                    TrackLog.w(TAG, "[$addonKey] unexpected result type: ${result::class.simpleName}")
                    return@withContext null
                }
            }

            val stream = parseMorneyeStream(streamMap) ?: run {
                TrackLog.d(TAG, "[$addonKey] no stream from download()")
                return@withContext null
            }

            if (stream.isSegmented) {
                // Segment-based downloads (DASH / Tidal segments) need a full
                // offline download pass. Skip and let the next source serve.
                TrackLog.d(TAG, "[$addonKey] segment result for $trackId — skipping to next source")
                return@withContext null
            }

            val url = stream.directUrl ?: return@withContext null

            if (ModuleSource.malformed(url)) {
                TrackLog.w(TAG, "[$addonKey] malformed URL for $trackId")
                return@withContext null
            }

            // Tell ExoPlayer the container type so it doesn't have to sniff.
            detectManifestType(url, stream.fileType)
                ?.let { StreamContainer.declare(url, it) }

            SourceStream(
                url         = url,
                format      = buildFormat(stream, request),
                headers     = stream.headers,
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

    private fun buildFormat(stream: MorneyeStream, request: StreamRequest): StreamFormat {
        val codec = when {
            stream.isAtmos  -> "eac3-joc"
            stream.isLossless -> "flac"
            stream.fileType?.contains("flac", ignoreCase = true) == true -> "flac"
            stream.fileType?.contains("alac", ignoreCase = true) == true -> "alac"
            stream.fileType?.contains("mp3",  ignoreCase = true) == true -> "mp3"
            stream.fileType?.contains("aac",  ignoreCase = true) == true -> "aac"
            stream.fileType?.contains("m4a",  ignoreCase = true) == true -> "m4a"
            else -> null
        }
        val tierKbps = (request as? StreamRequest.Capped)?.maxKbps
        return StreamFormat(codec = codec, kbps = tierKbps, kbpsInferred = tierKbps != null)
    }

    private fun detectManifestType(url: String, fileType: String?): String? {
        val ft = fileType?.lowercase(Locale.ROOT) ?: ""
        return when {
            "hls"  in ft || "m3u" in ft                        -> "hls"
            "dash" in ft || "mpd" in ft                        -> "dash"
            url.contains(".m3u8", ignoreCase = true)           -> "hls"
            url.contains(".mpd",  ignoreCase = true)           -> "dash"
            else                                               -> null
        }
    }

    companion object {
        private const val TAG = "BitChord/Mornye"
    }

    private fun JSONObject.toKotlinMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in keys()) map[key] = opt(key)
        return map
    }
}
