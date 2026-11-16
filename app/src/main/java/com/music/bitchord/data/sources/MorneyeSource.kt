package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.mornye.MorneyeAddonException
import com.music.bitchord.data.sources.mornye.MorneyeDownloadResult
import com.music.bitchord.data.sources.mornye.MorneyePackageManager
import com.music.bitchord.data.sources.mornye.MorneyeQualityOption
import com.music.bitchord.data.sources.mornye.MorneyeRuntime
import com.music.bitchord.data.sources.mornye.isAtmosClaim
import com.music.bitchord.data.sources.mornye.isLosslessCodec
import com.music.bitchord.data.sources.mornye.normaliseCodec
import com.music.bitchord.data.sources.mornye.parseMorneyeDownloadResult
import com.music.bitchord.data.sources.mornye.parseMorneyeTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
 *  5. Playback's CacheDataSource also copies those bytes into BitChord's
 *     SimpleCache under the source key, so the track appears in Cached songs
 *     and exports through the same DownloadStore path as any other cached
 *     stream.
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

        val songs = items.mapNotNull { item ->
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
        TrackLog.d(TAG, "[$addonKey] search '$query' → ${songs.size} rows")
        songs
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

            val quality = resolveQuality(request)
            // The tier is part of the file name. A prefetch made at 320kbps and
            // a later play that asks for lossless must not reuse that file, and
            // two tiers sharing one name is exactly how a "FLAC" row ends up
            // playing a 128kbps transcode.
            val fileBase = "${sanitise(rawTrackId)}_${sanitise(quality?.id ?: "default")}"

            val addonCacheDir = File(MorneyePackageManager.downloadCacheDir, addonKey)
                .also { it.mkdirs() }

            val lock = downloadLocks.computeIfAbsent("$addonKey:$fileBase") { Mutex() }
            lock.withLock {
                // Read-ahead may have fetched this track already. Reusing that
                // file is what makes prefetch worth doing: playback opens it
                // instead of starting a second download at the moment the
                // previous track ends.
                existingFile(addonCacheDir, fileBase)?.let { file ->
                    TrackLog.d(
                        TAG,
                        "[$addonKey] reusing ${file.name} (${file.length()} bytes) for $rawTrackId",
                    )
                    return@withContext buildStream(file, quality, request, parsed = null)
                }

                // The addon may rename the extension (e.g. .flac → .m4a for
                // JioSaavn), so .flac is only a default; the result's file_path
                // is what is trusted.
                val outputPath = File(addonCacheDir, "$fileBase.flac").absolutePath
                TrackLog.d(
                    TAG,
                    "[$addonKey] download $rawTrackId quality=${quality?.id ?: "default"}" +
                        " (${quality?.label ?: "addon default"}) → $outputPath",
                )

                val downloadResult = runCatching {
                    rt.invoke(
                        "download",
                        listOf(rawTrackId, quality?.id, outputPath, null,
                            mapOf("forPlayback" to true)),
                    )
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
                    TrackLog.w(TAG, "[$addonKey] download() returned success=false: ${parsed.errorMessage}")
                    return@withContext null
                }

                val filePath = parsed.filePath
                if (filePath.isNullOrBlank()) {
                    TrackLog.w(TAG, "[$addonKey] download() success but no file_path")
                    return@withContext null
                }

                val file = File(filePath)
                if (!file.exists() || file.length() == 0L) {
                    TrackLog.w(TAG, "[$addonKey] file_path does not exist or is empty: $filePath")
                    return@withContext null
                }
                // Check the addon's codec claim against the bytes it wrote. The
                // extension is a promise; the magic number is the answer.
                val codecHint = parsed.codec ?: parsed.format?.let(::normaliseCodec)
                val detected = detectCodec(file)
                if (detected != null && codecHint != null && detected != codecHint) {
                    TrackLog.w(
                        TAG,
                        "[$addonKey] addon said $codecHint for $rawTrackId; bytes are $detected",
                    )
                }
                TrackLog.d(
                    TAG,
                    "[$addonKey] cached $rawTrackId at $filePath — ${file.length()} bytes, " +
                        "codec=${detected ?: codecHint ?: guessCodecFromPath(filePath) ?: "unknown"}",
                )
                return@withContext buildStream(file, quality, request, parsed)
            }
        }

    // ── Quality ───────────────────────────────────────────────────────────

    /**
     * The tier to ask the addon for.
     *
     * Ranked by what the manifest actually declares, not by the order it
     * happened to write its array in: lossless before lossy, then bit depth,
     * sample rate and bitrate. A catalogue whose options carry no numbers at
     * all still gets asked for its best — [MorneyeQualityOption.isLossless] and
     * the label order are all there is to go on — but nothing here invents a
     * lossless claim for a tier that did not make one.
     */
    private fun resolveQuality(request: StreamRequest): MorneyeQualityOption? {
        val opts = rankedQualities()
        if (opts.isEmpty()) return null
        return when (request) {
            is StreamRequest.Lossless -> opts.firstOrNull { it.isLossless } ?: opts.first()
            is StreamRequest.Best -> opts.first()
            is StreamRequest.Capped -> {
                val known = opts.filter { it.kbps != null }
                when {
                    // Highest tier that states a bitrate at or under the cap.
                    known.any { it.kbps!! <= request.maxKbps } ->
                        known.first { it.kbps!! <= request.maxKbps }
                    // Nothing states a bitrate: ask high only when the cap is
                    // high, as before; below that a low tier is the honest ask.
                    known.isEmpty() ->
                        if (request.maxKbps >= 320) opts.first() else opts.last()
                    // Everything known is over the cap: the cheapest stated tier.
                    else -> known.last()
                }
            }
        }
    }

    private fun rankedQualities(): List<MorneyeQualityOption> =
        MorneyePackageManager.capabilities(addonKey)?.qualityOptions.orEmpty()
            .sortedWith(
                compareByDescending<MorneyeQualityOption> { it.isLossless }
                    .thenByDescending { it.bitDepth ?: 0 }
                    .thenByDescending { it.sampleRateHz ?: 0 }
                    .thenByDescending { it.kbps ?: 0 },
            )

    private fun buildStream(
        file: File,
        quality: MorneyeQualityOption?,
        request: StreamRequest,
        parsed: MorneyeDownloadResult?,
    ): SourceStream {
        val codec = detectCodec(file)
            ?: parsed?.codec
            ?: parsed?.format?.let(::normaliseCodec)
            ?: quality?.codec
            ?: guessCodecFromPath(file.name)

        // Measurements come from the bytes first: a FLAC header states its own
        // sample rate and bit depth, and that is a fact rather than a promise.
        val flac = if (codec == "flac") flacStreamInfo(file) else null
        val sampleRate = flac?.sampleRateHz ?: parsed?.sampleRateHz ?: quality?.sampleRateHz
        val bitDepth = flac?.bitDepth ?: parsed?.bitDepth ?: quality?.bitDepth
        val statedKbps = parsed?.kbps
        val inferredKbps = quality?.kbps?.takeIf { statedKbps == null }
        val lossless = if (codec != null) isLosslessCodec(codec) else quality?.isLossless ?: false
        val atmosClaim = parsed?.quality?.let { isAtmosClaim(codec, it) }
            ?: quality?.let { isAtmosClaim(codec, it.label) }
            ?: false

        val format = StreamFormat(
            codec        = codec,
            kbps         = statedKbps ?: inferredKbps,
            sampleRateHz = sampleRate,
            bitDepth     = bitDepth,
            kbpsInferred = statedKbps == null && inferredKbps != null,
        )

        // "Below request" is a statement about what arrived, not about what was
        // asked for. Lossless was requested and the bytes are not lossless;
        // that is a fallback worth reporting rather than a file to keep quiet
        // about.
        val belowRequest = request is StreamRequest.Lossless && !lossless

        TrackLog.d(
            TAG,
            "[$addonKey] selected ${format.summary}" +
                (quality?.let { " (${it.id}/${it.label})" } ?: " (addon default)") +
                ", requested ${request.describe()}" +
                if (belowRequest) " — FALLBACK: nothing lossless available" else "",
        )
        if (belowRequest) {
            TrackLog.w(
                TAG,
                "[$addonKey] '${file.name}' is ${codec ?: "an unknown codec"}; " +
                    "lossless was requested but this addon offered no lossless tier for the track",
            )
        }
        if (atmosClaim && !format.isDolbyAtmos) {
            TrackLog.d(TAG, "[$addonKey] '${file.name}' is Atmos-branded but codec is ${codec ?: "unknown"}")
        }

        return SourceStream(
            url          = "file://${file.absolutePath}",
            format       = format,
            headers      = emptyMap(),
            durationSec  = null,
            belowRequest = belowRequest,
        )
    }

    private fun StreamRequest.describe(): String = when (this) {
        is StreamRequest.Lossless -> "lossless"
        is StreamRequest.Best -> "best available"
        is StreamRequest.Capped -> "≤${maxKbps}kbps"
    }

    /** Anything at all already on disk for this track and tier. */
    private fun existingFile(dir: File, base: String): File? =
        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.length() > 0 && it.name.startsWith("$base.") }
            ?.maxByOrNull { it.lastModified() }

    /**
     * The container the bytes actually are, read from the first few bytes.
     *
     * The extension is the addon's claim; the magic number is the decoder's
     * answer. A tier labelled "FLAC" that wrote an M4A is a fact worth finding
     * before the player fails to decode it, and the one place it can be found
     * is here.
     */
    private fun detectCodec(file: File): String? = runCatching {
        val head = ByteArray(64)
        val read = file.inputStream().use { it.read(head) }
        if (read < 4) return@runCatching null
        fun ascii(offset: Int, length: Int): String =
            String(head, offset, length, Charsets.ISO_8859_1)
        fun at(offset: Int): Int = head[offset].toInt() and 0xFF
        when {
            ascii(0, 4) == "fLaC" -> "flac"
            ascii(0, 4) == "RIFF" && read >= 12 && ascii(8, 4) == "WAVE" -> "wav"
            ascii(0, 4) == "OggS" -> {
                val body = String(head, 0, read, Charsets.ISO_8859_1)
                when {
                    "OpusHead" in body -> "opus"
                    "vorbis" in body   -> "vorbis"
                    else               -> "ogg"
                }
            }
            ascii(0, 3) == "ID3" -> "mp3"
            read >= 12 && ascii(4, 4) == "ftyp" -> {
                val body = String(head, 0, read, Charsets.ISO_8859_1)
                if ("alac" in body) "alac" else "aac"
            }
            at(0) == 0xFF && (at(1) and 0xF6) == 0xF0 -> "aac"
            at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> "mp3"
            ascii(0, 4) == "caff" -> "alac"
            else -> null
        }
    }.getOrNull()

    /** A FLAC file's own STREAMINFO: sample rate and bit depth, no guessing. */
    private fun flacStreamInfo(file: File): FlacInfo? = runCatching {
        val head = ByteArray(42)
        val read = file.inputStream().use { it.read(head) }
        if (read < 42 || String(head, 0, 4, Charsets.ISO_8859_1) != "fLaC") return@runCatching null
        // STREAMINFO starts after the 4-byte marker and its 4-byte block header.
        // The sample rate is 20 bits starting at byte 18, then 3 bits of
        // channels and 5 bits of (bits per sample - 1): the low bit of byte 20
        // is the bps field's top bit, and the rest is the high nibble of byte
        // 21. Reading the channel bits as if they were bps is how a 24-bit
        // master gets reported as 16.
        val sampleRate = ((head[18].toInt() and 0xFF) shl 12) or
            ((head[19].toInt() and 0xFF) shl 4) or
            ((head[20].toInt() and 0xFF) ushr 4)
        val bitDepth = ((((head[20].toInt() and 0x01) shl 4) or
            ((head[21].toInt() and 0xF0) ushr 4)) + 1)
        if (sampleRate !in 8_000..768_000) null
        else FlacInfo(sampleRate, bitDepth)
    }.getOrNull()

    private data class FlacInfo(val sampleRateHz: Int, val bitDepth: Int)

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun guessCodecFromPath(path: String): String? = when {
        path.endsWith(".flac", ignoreCase = true) -> "flac"
        path.endsWith(".m4a",  ignoreCase = true) -> "aac"
        path.endsWith(".mp3",  ignoreCase = true) -> "mp3"
        path.endsWith(".alac", ignoreCase = true) -> "alac"
        path.endsWith(".ogg",  ignoreCase = true) -> "vorbis"
        path.endsWith(".opus", ignoreCase = true) -> "opus"
        path.endsWith(".wav",  ignoreCase = true) -> "wav"
        path.endsWith(".aiff", ignoreCase = true) -> "aiff"
        else -> null
    }

    private fun sanitise(id: String) =
        id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(80)

    companion object {
        private const val TAG = "BitChord/Mornye"

        /**
         * One lock per addon and tier. Two prefetches for the same track — the
         * queue's read-ahead and the player's own open racing it, say — must
         * not both write the same file.
         */
        private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    }

    private fun JSONObject.toKotlinMap(): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        for (k in keys()) m[k] = opt(k)
        return m
    }
}
