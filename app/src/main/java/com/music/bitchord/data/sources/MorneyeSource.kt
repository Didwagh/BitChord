package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.mornye.MorneyeAddonException
import com.music.bitchord.data.sources.mornye.MorneyeAvailability
import com.music.bitchord.data.sources.mornye.MorneyeDownloadResult
import com.music.bitchord.data.sources.mornye.MorneyeFailureCooldown
import com.music.bitchord.data.sources.mornye.MorneyePackageManager
import com.music.bitchord.data.sources.mornye.MorneyeProviderHint
import com.music.bitchord.data.sources.mornye.MorneyeQualityOption
import com.music.bitchord.data.sources.mornye.MorneyeRuntime
import com.music.bitchord.data.sources.mornye.isAtmosClaim
import com.music.bitchord.data.sources.mornye.isLosslessCodec
import com.music.bitchord.data.sources.mornye.isMorneyeTrackItem
import com.music.bitchord.data.sources.mornye.mergeMorneyeTrack
import com.music.bitchord.data.sources.mornye.morneyeAvailabilityOptions
import com.music.bitchord.data.sources.mornye.morneyeCallError
import com.music.bitchord.data.sources.mornye.morneyeProviderForAddon
import com.music.bitchord.data.sources.mornye.morneyeResultMap
import com.music.bitchord.data.sources.mornye.morneyeUrlProvider
import com.music.bitchord.data.sources.mornye.morneyeUrlTrack
import com.music.bitchord.data.sources.mornye.morneyeVerificationRequired
import com.music.bitchord.data.sources.mornye.normaliseCodec
import com.music.bitchord.data.sources.mornye.normaliseJsonLike
import com.music.bitchord.data.sources.mornye.parseMorneyeAvailability
import com.music.bitchord.data.sources.mornye.parseMorneyeDownloadResult
import com.music.bitchord.data.sources.mornye.parseMorneyeTrack
import com.music.bitchord.data.sources.mornye.resolveMorneyeAvailability
import com.music.bitchord.data.sources.mornye.selectMorneyeUrlHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
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

    /**
     * The runtime whose `initialize()` has already run.
     *
     * Addons reset their in-memory state in `initialize` (Apple Music clears
     * its metadata cache, JioSaavn its song cache), so it is a once-per-engine
     * call, not a per-search one. Comparing identity rather than a Boolean
     * means a runtime that was torn down on a settings change is initialised
     * again when it is rebuilt.
     */
    @Volatile private var initialisedRuntime: MorneyeRuntime? = null
    private val initLock = Mutex()

    /**
     * Search rows carry the metadata availability needs (ISRC, title, artists,
     * provider ids), while `stream()` receives only the addon's track id. Keep
     * a bounded, access-ordered copy here so the optional availability pass can
     * use the same row the listener actually picked.
     */
    private val rememberedTracks = Collections.synchronizedMap(
        object : LinkedHashMap<String, Map<*, *>>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Map<*, *>>?,
            ) = size > 512
        },
    )

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
        val methods = runCatching { rt.exportedMethods() }
            .onFailure { TrackLog.w(TAG, "[$addonKey] engine failed: ${it.message}") }
            .getOrDefault(emptyList())
        return@withContext if (methods.isNotEmpty())
            SourceHealth.Ok("v${pkg.version} · ${methods.size} methods")
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

        // The addon contract starts with initialize(); it used to be called
        // only from stream(), and only after the engine had been built by some
        // other call, so a search could run against an addon that had never
        // seen its settings.
        initializeIfSupported(rt)

        // The engine is built lazily, so ask it what it exports before reading
        // that list. Checking a runtime that has not been started yet used to
        // report "no search method" for every addon on the first lookup.
        val methods = runCatching { rt.exportedMethods() }
            .onFailure { TrackLog.w(TAG, "[$addonKey] engine failed: ${it.message}") }
            .getOrDefault(emptyList())

        // A pasted provider URL is a resolution request, not a text query.
        // Only the addon whose export actually exists is asked, and only a
        // `{type:"track",track:{…}}` answer becomes a playable row.
        morneyeUrlProvider(query)?.let { provider ->
            val handler = selectMorneyeUrlHandler(methods, provider)
            if (handler == null) {
                TrackLog.d(
                    TAG,
                    "[$addonKey] no URL handler for $provider (methods=${methods.joinToString()})",
                )
                return@withContext emptyList()
            }
            val resolved = runCatching { rt.invoke(handler, listOf(query.trim())) }
                .onFailure {
                    if (it is MorneyeAddonException) TrackLog.d(TAG, "[$addonKey] $handler: ${it.message}")
                    else TrackLog.w(TAG, "[$addonKey] $handler failed: ${it.message}")
                }.getOrNull()
            val callError = morneyeCallError(resolved)
            if (callError != null) {
                TrackLog.w(TAG, "[$addonKey] $handler: $callError")
                return@withContext emptyList()
            }
            val trackMap = morneyeUrlTrack(resolved)
            if (trackMap == null) {
                TrackLog.d(TAG, "[$addonKey] $handler did not return a track row")
                return@withContext emptyList()
            }
            val song = songFromTrackMap(trackMap)
            if (song == null) {
                TrackLog.d(TAG, "[$addonKey] $handler returned an unusable track row")
                return@withContext emptyList()
            }
            TrackLog.d(TAG, "[$addonKey] $handler resolved a track URL via $provider")
            return@withContext listOf(song)
        }

        // Try searchTracks first (the module-style addons), fall back to
        // customSearch (the metadata/download providers, Amazon among them).
        val raw = runCatching {
            when {
                "searchTracks" in methods ->
                    rt.invoke("searchTracks", listOf(query.trim(), limit))
                "customSearch" in methods ->
                    rt.invoke("customSearch",
                        listOf(query.trim(), mapOf("limit" to limit, "filter" to "songs")))
                else -> {
                    TrackLog.d(
                        TAG,
                        "[$addonKey] no search method (methods=${methods.joinToString()})",
                    )
                    null
                }
            }
        }.onFailure {
            if (morneyeVerificationRequired(it)) {
                TrackLog.w(
                    TAG,
                    "[$addonKey] search needs provider verification; " +
                        "open Mornye Extensions → $displayName → Connect",
                )
            } else {
                TrackLog.w(TAG, "[$addonKey] search error: ${it.message}")
            }
        }.getOrNull() ?: return@withContext emptyList()

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
            songFromTrackMap(map)
        }
            // Amazon's feed repeats a track in several shelves; one row per
            // track is what the list and the queue expect.
            .distinctBy { it.videoId }
        TrackLog.d(TAG, "[$addonKey] search '$query' → ${songs.size} rows")
        songs
    }

    /** Builds the app row from an addon track map and keeps its metadata for availability. */
    private fun songFromTrackMap(item: Map<*, *>): Song? {
        val map = morneyeResultMap(item) ?: return null
        val track = parseMorneyeTrack(map) ?: return null
        if (!isMorneyeTrackItem(map)) return null
        rememberTrack(track.id, map)
        return Song(
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
            sourceHiRes = track.isHiRes && !track.isAtmos,
        )
    }

    private fun rememberTrack(trackId: String, map: Map<*, *>) {
        val normalised = normaliseJsonLike(map) as? Map<*, *> ?: return
        synchronized(rememberedTracks) {
            rememberedTracks[trackId] = normalised
            rememberedTracks[SourceRegistry.trackKey(config.id, trackId)] = normalised
        }
    }

    private fun rememberedTrack(trackId: String): Map<*, *> =
        synchronized(rememberedTracks) {
            rememberedTracks[trackId] ?: rememberedTracks[SourceRegistry.trackKey(config.id, trackId)]
        } ?: normaliseJsonLike(mapOf("id" to trackId)) as Map<*, *>

    private fun textField(map: Map<*, *>, vararg keys: String): String = keys
        .firstNotNullOfOrNull { key ->
            map[key]?.toString()?.trim()?.takeIf {
                it.isNotBlank() && it.lowercase(Locale.ROOT) != "null"
            }
        }.orEmpty()

    /**
     * Optional availability → enrich → availability pass. A failed optional
     * call is *not* a failed download: the addon may still resolve the row by
     * its own id, so the caller falls back to that. A genuine `available:false`
     * is returned and handled as a source miss.
     */
    private suspend fun resolveAvailability(
        rt: MorneyeRuntime,
        methods: List<String>,
        provider: MorneyeProviderHint,
        track: Map<*, *>,
        rawTrackId: String,
    ): MorneyeAvailability? {
        if ("checkAvailability" !in methods) return null
        var effectiveTrack = track
        val meta = parseMorneyeTrack(track)
        val isrc = textField(track, "isrc", "ISRC")
        val name = meta?.title?.takeIf { it.isNotBlank() }
            ?: textField(track, "name", "title").ifBlank { rawTrackId }
        val artists = meta?.artist.orEmpty()
        val duration = meta?.durationMs
        val hasEnrichment = "enrichTrack" in methods

        return runCatching {
            resolveMorneyeAvailability(
                check = {
                    val options = morneyeAvailabilityOptions(
                        provider, effectiveTrack, rawTrackId, duration,
                    )
                    parseMorneyeAvailability(
                        rt.invoke("checkAvailability", listOf(isrc, name, artists, options)),
                    )
                },
                enrich = if (hasEnrichment) suspend {
                    val enriched = rt.invoke("enrichTrack", listOf(effectiveTrack))
                    val error = morneyeCallError(enriched)
                    val map = morneyeResultMap(enriched)
                    when {
                        error != null -> TrackLog.d(TAG, "[$addonKey] enrichTrack: $error")
                        map != null -> effectiveTrack = mergeMorneyeTrack(effectiveTrack, map)
                        else -> TrackLog.d(TAG, "[$addonKey] enrichTrack gave no usable metadata")
                    }
                } else null,
            )
        }.onFailure {
            when {
                morneyeVerificationRequired(it) -> TrackLog.w(
                    TAG,
                    "[$addonKey] availability needs provider verification; " +
                        "open Mornye Extensions → $displayName → Connect",
                )
                it is MorneyeAddonException ->
                    TrackLog.d(TAG, "[$addonKey] availability: ${it.message}")
                else -> TrackLog.w(TAG, "[$addonKey] availability failed: ${it.message}")
            }
        }.getOrNull()
    }

    // ── Stream ────────────────────────────────────────────────────────────

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            val rt = runtime() ?: return@withContext null

            // Strip the composite key to get the addon's own track ID.
            // Song.videoId = "src:<configId>::<addonTrackId>"
            val rawTrackId = SourceTrackKeys.parse(trackId)?.second ?: trackId

            // Optional initialize call to refresh session and apply settings.
            initializeIfSupported(rt)

            // A metadata-only addon (Apple Music, Spotify) searches but has no
            // download() to produce a file. Asking anyway returned `no_method`
            // with no explanation, once per prefetch, and made it look as if
            // the source had failed rather than not being a stream source.
            val methods = runCatching { rt.exportedMethods() }.getOrDefault(emptyList())
            if ("download" !in methods) {
                TrackLog.d(
                    TAG,
                    "[$addonKey] $rawTrackId is metadata-only (no download method); " +
                        "playback will fall through to a source that can stream it",
                )
                return@withContext null
            }

            val quality = resolveQuality(request)
            // The tier is part of the file name. A prefetch made at 320kbps and
            // a later play that asks for lossless must not reuse that file, and
            // two tiers sharing one name is exactly how a "FLAC" row ends up
            // playing a 128kbps transcode.
            val fileBase = "${sanitise(rawTrackId)}_${sanitise(quality?.id ?: "default")}"
            val failKey = MorneyeFailureCooldown.key(addonKey, rawTrackId, quality?.id)

            val addonCacheDir = File(MorneyePackageManager.downloadCacheDir, addonKey)
                .also { it.mkdirs() }

            val lock = downloadLocks.computeIfAbsent("$addonKey:$fileBase") { Mutex() }
            lock.withLock {
                // Read-ahead may have fetched this track already. Reusing that
                // file is what makes prefetch worth doing: playback opens it
                // instead of starting a second download at the moment the
                // previous track ends.
                existingFile(addonCacheDir, fileBase)?.let { file ->
                    // A file cached by an older build may still be CENC-
                    // encrypted, without the key ever having been saved. It
                    // cannot be played; fetch it again so the addon hands us
                    // the key alongside the audio.
                    when {
                        isManifestFile(file) -> {
                            TrackLog.w(
                                TAG,
                                "[$addonKey] cached ${file.name} is a DASH/XML manifest, not audio; " +
                                    "discarding and re-downloading",
                            )
                            discardPartialDownload(addonCacheDir, fileBase)
                        }
                        isCencEncrypted(file) &&
                            MorneyePackageManager.decryptionKeyFor(configId, rawTrackId) == null -> {
                            TrackLog.d(
                                TAG,
                                "[$addonKey] cached ${file.name} is encrypted but has no saved key; " +
                                    "discarding and re-downloading",
                            )
                            discardPartialDownload(addonCacheDir, fileBase)
                        }
                        else -> {
                            TrackLog.d(
                                TAG,
                                "[$addonKey] reusing ${file.name} (${file.length()} bytes) for $rawTrackId",
                            )
                            MorneyePackageManager.rememberCachedFile(configId, rawTrackId, file.absolutePath)
                            MorneyeFailureCooldown.clear(failKey)
                            return@withContext buildStream(file, quality, request, parsed = null)
                        }
                    }
                }

                if (MorneyeFailureCooldown.isCoolingDown(failKey)) {
                    TrackLog.d(
                        TAG,
                        "[$addonKey] skipping $rawTrackId (${quality?.id ?: "default"}): " +
                            "it failed less than ${MorneyeFailureCooldown.WINDOW_MS / 1000}s ago; " +
                            "letting another source answer",
                    )
                    return@withContext null
                }

                // Authoritative ticket first, as Lokal does. Only the two
                // addons whose availability contract we know are asked, and
                // only now that a download is actually needed — a cached file
                // or an active cooldown never pays for this call.
                val provider = morneyeProviderForAddon(addonKey, displayName)
                val availability = if (provider != null) {
                    resolveAvailability(rt, methods, provider, rememberedTrack(rawTrackId), rawTrackId)
                } else null
                if (availability != null && !availability.available) {
                    discardPartialDownload(addonCacheDir, fileBase)
                    MorneyeFailureCooldown.record(failKey)
                    TrackLog.d(
                        TAG,
                        "[$addonKey] $provider availability refused $rawTrackId: " +
                            "${availability.reason ?: "no reason given"}; letting another source answer",
                    )
                    return@withContext null
                }
                val downloadTrackId = availability?.trackId?.takeIf { it.isNotBlank() } ?: rawTrackId
                val options = mutableMapOf<String, Any?>("forPlayback" to true)
                availability?.preparedContext?.let { options["preparedContext"] = it }
                if (availability?.preparedContext != null) {
                    TrackLog.d(TAG, "[$addonKey] passing preparedContext to download in fifth argument")
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

                // The addon's own retries and the signed-session contract's
                // provider retries both happen inside this call, so its elapsed
                // time is the honest answer to "why did one candidate take
                // 40s?".
                val startedAt = System.currentTimeMillis()
                val downloadResult = runCatching {
                    rt.invoke(
                        "download",
                        listOf(downloadTrackId, quality?.id, outputPath, null, options),
                    )
                }.onFailure {
                    if (it is MorneyeAddonException) TrackLog.d(TAG, "[$addonKey] addon: ${it.message}")
                    else TrackLog.w(TAG, "[$addonKey] download failed: ${it.message}")
                }.getOrNull()
                val elapsedMs = System.currentTimeMillis() - startedAt
                if (downloadResult == null) {
                    // The addon writes straight to outputPath. A connection abort
                    // leaves a half-written file there, and existingFile() below
                    // cannot tell it from a complete one — the next play would
                    // reuse the stub and fail at the first sample. Delete it.
                    discardPartialDownload(addonCacheDir, fileBase)
                    MorneyeFailureCooldown.record(failKey)
                    TrackLog.w(TAG, "[$addonKey] download for $rawTrackId returned nothing after ${elapsedMs}ms")
                    return@withContext null
                }

                val parsed = parseMorneyeDownloadResult(downloadResult as? JSONObject
                    ?: run {
                        // If invoke returned a Map, re-encode it
                        @Suppress("UNCHECKED_CAST")
                        val map = downloadResult as? Map<*, *>
                        if (map != null) JSONObject(map.mapKeys { it.key.toString() }) else null
                    } ?: return@withContext null)

                if (!parsed.success) {
                    discardPartialDownload(addonCacheDir, fileBase)
                    MorneyeFailureCooldown.record(failKey)
                    TrackLog.w(
                        TAG,
                        "[$addonKey] download() returned success=false after ${elapsedMs}ms: " +
                            "${parsed.errorMessage}",
                    )
                    return@withContext null
                }

                val filePath = parsed.filePath
                if (filePath.isNullOrBlank()) {
                    discardPartialDownload(addonCacheDir, fileBase)
                    MorneyeFailureCooldown.record(failKey)
                    TrackLog.w(TAG, "[$addonKey] download() success but no file_path")
                    return@withContext null
                }

                val file = File(filePath)
                if (!file.exists() || file.length() == 0L) {
                    discardPartialDownload(addonCacheDir, fileBase)
                    MorneyeFailureCooldown.record(failKey)
                    TrackLog.w(TAG, "[$addonKey] file_path does not exist or is empty: $filePath")
                    return@withContext null
                }
                // Tidal's DASH path can hand back the MPD itself when the
                // segment info is stale. It is not audio and must never be
                // cached under a `.flac` name, even briefly.
                if (isManifestFile(file)) {
                    discardPartialDownload(addonCacheDir, fileBase)
                    MorneyeFailureCooldown.record(failKey)
                    TrackLog.w(TAG, "[$addonKey] download() wrote a DASH/XML manifest, not audio: ${file.name}")
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
                // Amazon's addon hands back CENC-encrypted ISO-BMFF plus the
                // AES key in `decryption`. Lokal runs ffmpeg -decryption_key
                // here; we keep the key and provision a ClearKey session when
                // the player opens this file. Never log the key itself.
                parsed.decryptionKey?.let { key ->
                    if (parsed.decryptionStrategy.isNullOrBlank() ||
                        parsed.decryptionStrategy.equals("ffmpeg.mov_key", ignoreCase = true)
                    ) {
                        MorneyePackageManager.rememberDecryptionKey(configId, rawTrackId, key)
                        TrackLog.d(
                            TAG,
                            "[$addonKey] $rawTrackId is CENC-encrypted; registered ${key.length}-char " +
                                "ClearKey material for playback",
                        )
                    } else {
                        TrackLog.w(
                            TAG,
                            "[$addonKey] $rawTrackId uses unsupported decryption strategy " +
                                "${parsed.decryptionStrategy}; playback may fail",
                        )
                    }
                }
                MorneyePackageManager.rememberCachedFile(configId, rawTrackId, file.absolutePath)
                MorneyeFailureCooldown.clear(failKey)
                TrackLog.d(
                    TAG,
                    "[$addonKey] cached $rawTrackId at $filePath in ${elapsedMs}ms — ${file.length()} bytes, " +
                        "codec=${detected ?: codecHint ?: "unknown"}",
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

    internal fun buildStream(
        file: File,
        quality: MorneyeQualityOption?,
        request: StreamRequest,
        parsed: MorneyeDownloadResult?,
    ): SourceStream {
        // The bytes decide the codec. The addon's own observation of what it
        // wrote is the next best thing; the manifest tier and the filename we
        // chose ourselves are requests, not evidence, and must never make an
        // unknown container look like FLAC.
        val codec = detectCodec(file)
            ?: parsed?.codec
            ?: parsed?.format?.let(::normaliseCodec)

        // Measurements come from the bytes first: a FLAC header states its own
        // sample rate and bit depth, and that is a fact rather than a promise.
        // The addon may state them too; the requested tier may not.
        val flac = if (codec == "flac") flacStreamInfo(file) else null
        val sampleRate = flac?.sampleRateHz ?: parsed?.sampleRateHz
        val bitDepth = flac?.bitDepth ?: parsed?.bitDepth
        val statedKbps = parsed?.kbps
        val inferredKbps = quality?.kbps?.takeIf { statedKbps == null }
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
        // A statement about what arrived. A lossless tier that produced
        // unidentifiable bytes is a fallback, not a successful lossless result.
        val lossless = format.isLossless == true

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

    /**
     * Whether a cached MP4 still carries CENC encryption boxes. Used only to
     * decide whether an old cache entry needs the key-bearing download again;
     * a clear file never pays for the scan unless it is being reused.
     */
    private fun isCencEncrypted(file: File): Boolean {
        if (!file.name.contains('.')) return false
        val ext = file.name.substringAfterLast('.').lowercase(Locale.ROOT)
        if (ext !in setOf("m4a", "mp4", "m4b")) return false
        val needles = arrayOf("pssh".toByteArray(), "senc".toByteArray())
        val buffer = ByteArray(64 * 1024)
        var tail = ByteArray(0)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val data = if (tail.isEmpty()) buffer.copyOf(read) else tail + buffer.copyOf(read)
                if (needles.any { containsAscii(data, it) }) return true
                tail = data.copyOfRange(maxOf(0, data.size - 8), data.size)
            }
        }
        return false
    }

    /** Reject DASH/MPD text before it can be cached as an audio file. */
    private fun isManifestFile(file: File): Boolean = runCatching {
        val head = ByteArray(MANIFEST_SCAN_BYTES)
        val read = file.inputStream().use { it.read(head) }
        looksLikeManifestBytes(head, read)
    }.getOrDefault(false)

    internal fun looksLikeManifestBytes(head: ByteArray, read: Int): Boolean {
        var at = 0
        if (read >= 3 && head[0] == 0xEF.toByte() &&
            head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        ) at = 3
        while (at < read && when (head[at].toInt() and 0xFF) {
                0x20, 0x09, 0x0A, 0x0D -> true
                else -> false
            }
        ) at++
        if (at >= read) return false
        val text = String(head, at, read - at, Charsets.ISO_8859_1)
        return text.startsWith("<?xml", ignoreCase = true) ||
            text.startsWith("<MPD", ignoreCase = true)
    }

    private fun containsAscii(data: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || data.size < needle.size) return false
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    /** Anything at all already on disk for this track and tier. */
    private fun existingFile(dir: File, base: String): File? =
        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.length() > 0 && it.name.startsWith("$base.") }
            ?.maxByOrNull { it.lastModified() }

    /**
     * Remove every file this track/tier wrote, so a failed transfer cannot be
     * mistaken for a cached track by [existingFile] on the next play. Called on
     * every failure path after the addon has been given a path to write to.
     */
    private fun discardPartialDownload(dir: File, base: String) {
        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith("$base.") }
            ?.forEach { file ->
                if (file.delete()) {
                    TrackLog.d(TAG, "removed partial download ${file.name} (${file.length()} bytes)")
                } else {
                    TrackLog.w(TAG, "could not remove partial download ${file.name}")
                }
            }
    }

    /**
     * The container the bytes actually are, read from the first few bytes.
     *
     * The extension is the addon's claim; the magic number is the decoder's
     * answer. A tier labelled "FLAC" that wrote an M4A is a fact worth finding
     * before the player fails to decode it, and the one place it can be found
     * is here.
     */
    private fun detectCodec(file: File): String? = runCatching {
        val head = ByteArray(HEAD_SCAN_BYTES)
        val read = file.inputStream().use { it.read(head) }
        return@runCatching codecFromHeader(head, read)
    }.getOrNull()

    /**
     * Pure header reader, kept separate from file I/O so MP4-wrapped lossless
     * cases can be tested without a fixture file. The `dfLa`/`alac` sample
     * entries sit well past the first 64 bytes, so a header-only check used to
     * call these files AAC.
     */
    internal fun codecFromHeader(head: ByteArray, read: Int): String? {
        if (read < 4) return null
        fun ascii(offset: Int, length: Int): String =
            String(head, offset, minOf(read, offset + length), Charsets.ISO_8859_1).take(length)
        fun at(offset: Int): Int = head[offset].toInt() and 0xFF
        return when {
            ascii(0, 4) == "fLaC" -> "flac"
            ascii(0, 4) == "RIFF" && read >= 12 && ascii(8, 4) == "WAVE" -> "wav"
            ascii(0, 4) == "OggS" -> {
                val body = String(head, 0, read, Charsets.ISO_8859_1)
                when {
                    "OpusHead" in body -> "opus"
                    "vorbis" in body -> "vorbis"
                    else -> "ogg"
                }
            }
            ascii(0, 3) == "ID3" -> "mp3"
            read >= 12 && ascii(4, 4) == "ftyp" -> {
                val body = String(head, 0, read, Charsets.ISO_8859_1)
                when {
                    "dfLa" in body -> "flac"
                    "alac" in body -> "alac"
                    else -> "aac"
                }
            }
            at(0) == 0xFF && (at(1) and 0xF6) == 0xF0 -> "aac"
            at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> "mp3"
            ascii(0, 4) == "caff" -> "alac"
            else -> null
        }
    }

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

    /**
     * Runs the addon's `initialize(settings)` once the engine is up.
     *
     * The contract starts here: settings, credentials and session state are
     * what every later search and download reads. It is skipped for addons
     * that do not export it, and a failure is logged rather than fatal — an
     * addon whose initialize is unhappy can still answer a plain search.
     */
    private suspend fun initializeIfSupported(rt: MorneyeRuntime) {
        if (initialisedRuntime === rt) return
        initLock.withLock {
            if (initialisedRuntime === rt) return
            val methods = runCatching { rt.exportedMethods() }
                .onFailure { TrackLog.w(TAG, "[$addonKey] engine failed: ${it.message}") }
                .getOrDefault(emptyList())
            if ("initialize" in methods) {
                runCatching {
                    val settings = runCatching {
                        JSONObject(MorneyePackageManager.currentSettings(addonKey)).toKotlinMap()
                    }.getOrDefault(emptyMap<String, Any?>())
                    rt.invoke("initialize", listOf(settings))
                }.onFailure { TrackLog.d(TAG, "[$addonKey] initialize: ${it.message}") }
                    .onSuccess { initialisedRuntime = rt }
            } else {
                // Nothing to call; remember that so the method scan is not
                // repeated on every search.
                initialisedRuntime = rt
            }
        }
    }

    private fun sanitise(id: String) =
        id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(80)

    companion object {
        private const val TAG = "BitChord/Mornye"

        /** Far enough into an MP4 to reach the audio sample entry. */
        private const val HEAD_SCAN_BYTES = 65_536

        /** A BOM and indentation still leave an MPD recognisable. */
        private const val MANIFEST_SCAN_BYTES = 256

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
