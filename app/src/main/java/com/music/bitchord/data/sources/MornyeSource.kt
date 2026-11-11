package com.music.bitchord.data.sources

import android.util.Base64
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.mornye.MornyeEngine
import com.music.bitchord.data.sources.mornye.MornyeJson
import com.music.bitchord.data.sources.mornye.MornyePackage
import com.music.bitchord.data.sources.mornye.MornyeStream
import com.music.bitchord.data.sources.mornye.MornyeTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import java.util.Locale

/**
 * A [MusicSource] backed by Mornye JavaScript download extensions. search()
 * fans out across the registry's download providers; stream() asks the matching
 * extension for getDownloadUrl() and hands the URL back for BitChord to stream,
 * cache (Origin.MORNYE) and export. Track ids are "<extId>::<upstreamId>".
 */
class MornyeSource(override val config: SourceConfig) : MusicSource, SourceRegistry.ConfigBacked {
    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.MORNYE
    override val displayName: String get() = config.displayName

    private var exts: List<MornyePackage.Ext>? = null
    private val settingsByExt: Map<String, JSONObject> by lazy { parseSettings() }

    private fun parseSettings(): Map<String, JSONObject> {
        // Fragment #settings=<base64url JSON> on the stored URL, else the stored field.
        val frag = config.baseUrl.substringAfter('#', "")
        val b64 = frag.split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 && p[0] == "settings" }?.get(1) }.firstOrNull()
        val raw = when {
            !b64.isNullOrBlank() -> runCatching { String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP)) }.getOrDefault("")
            config.mornyeSettings.isNotBlank() -> config.mornyeSettings
            else -> ""
        }
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw); o.keys().asSequence().associateWith { o.getJSONObject(it) }
        }.getOrElse { emptyMap() }
    }

    private suspend fun extensions(): List<MornyePackage.Ext> {
        exts?.let { return it }
        val loaded = runCatching { MornyePackage.loadExtensions(config.baseUrl) }.getOrElse {
            TrackLog.w(TAG, "${config.displayName}: load failed — ${it.message}"); emptyList()
        }
        exts = loaded
        return loaded
    }

    private suspend fun ready(ext: MornyePackage.Ext): Boolean {
        val s = settingsByExt[ext.id]?.toString() ?: "{}"
        MornyeEngine.load(ext, s).getOrElse { TrackLog.w(TAG, "mornye load ${ext.id}: ${it.message}"); return false }
        // initialize() once; ignore failure (some need creds the user hasn't set).
        withTimeoutOrNull(INIT_MS) { MornyeEngine.call(ext.id, "initialize", "[$s]") }
        return true
    }

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) return@withContext SourceHealth.Rejected("A Mornye registry or package URL is required")
        val e = extensions()
        when {
            e.isEmpty() -> SourceHealth.Rejected("No JavaScript download_provider extensions found at that URL")
            else -> SourceHealth.Ok("${e.size} extension${if (e.size == 1) "" else "s"}: ${e.joinToString { it.id }}")
        }
    }

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val out = mutableListOf<Song>()
            for (ext in extensions()) {
                if (out.size >= limit) break
                if (!ready(ext)) continue
                val raw = withTimeoutOrNull(if (waitForAll) SEARCH_PATIENT_MS else SEARCH_MS) {
                    MornyeEngine.call(ext.id, "searchTracks", "[${JSONObject.quote(query)},$limit]")
                }?.getOrNull() ?: continue
                val tracks = parseTracks(raw)
                for (t in tracks) {
                    if (t.id.isBlank()) continue
                    out += Song(
                        videoId = SourceRegistry.trackKey(config.id, "${ext.id}$SEP${t.id}"),
                        title = t.name, artist = t.artists,
                        albumName = t.albumName.ifBlank { null },
                        thumbnailUrl = t.cover.ifBlank { null },
                        durationText = t.durationSec.takeIf { it > 0 }?.let { "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}" },
                        sourceQuality = ModuleSource.qualityTier(t.audioQuality),
                    )
                }
            }
            out.take(limit)
        }

    private fun parseTracks(raw: String): List<MornyeTrack> = runCatching {
        val el = MornyeJson.parseToJsonElement(raw)
        val arr: JsonArray = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["tracks"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        arr.mapNotNull { runCatching { MornyeJson.decodeFromJsonElement(MornyeTrack.serializer(), it) }.getOrNull() }
    }.getOrElse { emptyList() }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = withContext(Dispatchers.IO) {
        val cut = trackId.indexOf(SEP); if (cut < 0) return@withContext null
        val extId = trackId.substring(0, cut); val up = trackId.substring(cut + SEP.length)
        val ext = extensions().firstOrNull { it.id == extId } ?: return@withContext null
        if (!ready(ext)) return@withContext null
        val quality = pickQuality(ext, request)
        val raw = withTimeoutOrNull(STREAM_MS) {
            MornyeEngine.call(ext.id, "getDownloadUrl", "[${JSONObject.quote(up)},${JSONObject.quote(quality)}]")
        }?.getOrNull() ?: return@withContext null
        val s = runCatching { MornyeJson.decodeFromString(MornyeStream.serializer(), raw) }.getOrNull()
            ?: return@withContext null
        if (!s.success || s.url.isBlank()) { TrackLog.d(TAG, "mornye ${ext.id}: no stream (${s.error})"); return@withContext null }
        if (ModuleSource.malformed(s.url)) return@withContext null
        val fmt = StreamFormat(
            codec = s.format.ifBlank { null } ?: ModuleSource.qualityTier(quality)?.let { if (it == ModuleSource.LOSSLESS) "flac" else null },
            kbps = null,
            sampleRateHz = s.sampleRate.takeIf { it > 0 },
            bitDepth = s.bitDepth.takeIf { it > 0 },
            kbpsInferred = true,
        )
        if (ModuleSource.unplayable(fmt, DeviceCodecs.atmosWanted)) return@withContext null
        SourceStream(url = s.url, format = fmt)
    }

    /** Map the request tier onto the extension's own quality option ids. */
    private fun pickQuality(ext: MornyePackage.Ext, request: StreamRequest): String {
        val want = when (request) {
            is StreamRequest.Lossless -> ModuleSource.LOSSLESS
            is StreamRequest.Best -> ModuleSource.HIGH
            is StreamRequest.Capped -> if (request.maxKbps <= 128) ModuleSource.LOW else ModuleSource.HIGH
        }
        val opts = ext.manifest.qualityOptions
        if (opts.isEmpty()) return want
        val ranked = opts.map { it.id to (ModuleSource.qualityTier(it.id + " " + it.label + " " + it.description) ?: ModuleSource.LOW) }
        val pick = when (want) {
            ModuleSource.LOSSLESS -> ranked.firstOrNull { it.second == ModuleSource.LOSSLESS }
            ModuleSource.HIGH -> ranked.firstOrNull { it.second == ModuleSource.HIGH } ?: ranked.firstOrNull { it.second == ModuleSource.LOSSLESS }
            else -> ranked.firstOrNull { it.second == ModuleSource.LOW } ?: ranked.lastOrNull()
        }
        return pick?.first ?: opts.first().id
    }

    companion object {
        private const val TAG = "BitChord"
        private const val SEP = "::"
        private const val INIT_MS = 15_000L
        private const val SEARCH_MS = 9_000L
        private const val SEARCH_PATIENT_MS = 25_000L
        private const val STREAM_MS = 20_000L
    }
}
