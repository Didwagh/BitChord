#!/usr/bin/env python3
# Mornye extension support for BitChord (mobile APK).
#   python3 mornye_patch.py --check   # verify anchors only, change nothing
#   python3 mornye_patch.py           # apply (backs up originals as *.bak)
#   python3 mornye_patch.py --revert  # restore from *.bak
import os, sys, shutil

ROOT = os.getcwd()
APP = "app/src/main/java/com/music/bitchord"
TEST = "app/src/test/java/com/music/bitchord"
NEW_FILES = {}
EDITS = []
def edit(p, old, new): EDITS.append((p, old, new))
def new(p, content): NEW_FILES[p] = content

# ----------------------------- EDITS ---------------------------------------
edit(APP+"/data/sources/SourceKind.kt", "    JIOSAAVN(\n", '''    /**
     * A Mornye extension repository or a single .sflx / .spotiflac-ext package.
     * JavaScript download providers run in the same sandboxed QuickJS VM the
     * legacy module sources use, behind a prelude implementing the Mornye host
     * API. BitChord asks for a stream URL, then streams/caches/exports itself.
     * worthPrefetching=true so the next track is resolved and cached ahead of
     * time through the ordinary read-ahead, not only when the current one ends.
     */
    MORNYE(
        label = "Mornye",
        detail = "A Mornye extension repository or package. JavaScript download " +
            "providers run in a sandbox; BitChord streams and caches the audio.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Extensions"),
        needsServer = true,
        canServeLossless = true,
        worthPrefetching = true,
        rank = 0,
    ),

    JIOSAAVN(
''')

edit(APP+"/data/sources/SourceRegistry.kt", "    val pickedQuality: String? = null,\n",
'''    val pickedQuality: String? = null,
    /** Mornye per-extension settings JSON, keyed by extension id. Supplied via
     *  the source URL fragment (#settings=<base64url JSON>); read by MornyeSource. */
    val mornyeSettings: String = "",
''')

edit(APP+"/data/sources/SourceRegistry.kt", "    fun init(context: Context) {\n",
'''    fun init(context: Context) {
        com.music.bitchord.data.sources.mornye.MornyeEngine.init(context.applicationContext)
''')

edit(APP+"/data/sources/SourceRegistry.kt", "        SourceKind.ADDON -> AddonSource(config)\n",
'''        SourceKind.ADDON -> AddonSource(config)
        SourceKind.MORNYE -> MornyeSource(config)
''')

edit(APP+"/data/sources/SourceRegistry.kt",
"        is DetectedFormat.Unsupported -> Result.failure(AddonException(detected.reason))\n",
'''        is DetectedFormat.MornyeRegistry -> Result.success(
            (existing ?: SourceConfig(kind = SourceKind.MORNYE)).copy(
                kind = SourceKind.MORNYE, baseUrl = detected.url,
                label = existing?.label.orEmpty().ifBlank { detected.name },
                qualityOptions = emptyList(), pickedQuality = null,
            ),
        )
        is DetectedFormat.MornyeExtension -> Result.success(
            (existing ?: SourceConfig(kind = SourceKind.MORNYE)).copy(
                kind = SourceKind.MORNYE, baseUrl = detected.url,
                label = existing?.label.orEmpty().ifBlank { detected.name },
                qualityOptions = emptyList(), pickedQuality = null,
            ),
        )
        is DetectedFormat.Unsupported -> Result.failure(AddonException(detected.reason))
''')

edit(APP+"/data/sources/addon/SourceFormats.kt",
"    data class Unsupported(val reason: String) : DetectedFormat\n",
'''    /** A Mornye extension registry (registry.json listing .sflx packages). */
    data class MornyeRegistry(val url: String, val name: String, val extensionCount: Int) : DetectedFormat
    /** A single Mornye extension package (.sflx / .spotiflac-ext / .zip). */
    data class MornyeExtension(val url: String, val name: String) : DetectedFormat

    data class Unsupported(val reason: String) : DetectedFormat
''')

edit(APP+"/data/sources/addon/SourceFormats.kt", "        val base = AddonClient.normalizeBase(url)\n",
'''        // Mornye packages/registries are recognised before the addon/module
        // passes, which would otherwise refuse a registry.json or a ZIP.
        com.music.bitchord.data.sources.mornye.MornyePackage.detectUrl(url)
            ?.let { return@withContext Result.success(it) }

        val base = AddonClient.normalizeBase(url)
''')

edit(APP+"/data/sources/addon/SourceFormats.kt",
"        // 3. Recognisably an index of *something*, just not one of ours. Worth\n",
'''        // 2b. A Mornye registry: an "extensions" array whose entries carry a
        //     download_url to a .sflx package.
        val extArray = obj["extensions"] as? JsonArray
        if (extArray != null && extArray.isNotEmpty()) {
            val looksMornye = extArray.any { el ->
                val o = el as? JsonObject ?: return@any false
                o.containsKey("download_url") || o.containsKey("downloadUrl")
            }
            if (looksMornye) {
                val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull ?: "Mornye extensions"
                return DetectedFormat.MornyeRegistry(url, name, extArray.size)
            }
        }

        // 3. Recognisably an index of *something*, just not one of ours. Worth
''')

edit(APP+"/playback/AudioCache.kt",
"    enum class Origin { YOUTUBE, JIOSAAVN, OTHER }\n",
"    enum class Origin { YOUTUBE, JIOSAAVN, MORNYE, OTHER }\n")

edit(APP+"/playback/AudioCache.kt",
"        return if (kind == SourceKind.JIOSAAVN) Origin.JIOSAAVN else Origin.OTHER\n",
'''        return when (kind) {
            SourceKind.JIOSAAVN -> Origin.JIOSAAVN
            SourceKind.MORNYE -> Origin.MORNYE
            else -> Origin.OTHER
        }
''')

edit(APP+"/playback/AudioCache.kt",
"        origin == Origin.YOUTUBE.name || origin == Origin.JIOSAAVN.name\n",
"        origin == Origin.YOUTUBE.name || origin == Origin.JIOSAAVN.name ||\n            origin == Origin.MORNYE.name\n")

edit(APP+"/playback/AudioCache.kt",
"                val foreign = meta.get(META_ORIGIN, null as String?) == Origin.OTHER.name &&\n",
'''                val entryOrigin = meta.get(META_ORIGIN, null as String?)
                val foreign = (entryOrigin == Origin.OTHER.name || entryOrigin == Origin.MORNYE.name) &&
''')

edit(APP+"/playback/AudioCache.kt", '            Origin.OTHER -> "Addon"\n',
'            Origin.MORNYE -> "Mornye"\n            Origin.OTHER -> "Addon"\n')

edit(APP+"/data/sources/SourceResolver.kt",
"        return rankedAbove(youtube.configId, active).filter { it.kind == SourceKind.ADDON }\n",
"        return rankedAbove(youtube.configId, active)\n            .filter { it.kind == SourceKind.ADDON || it.kind == SourceKind.MORNYE }\n")

edit(APP+"/ui/screens/SourcesScreen.kt", "                SourceKind.MODULE -> SfIcons.Extension\n",
"                SourceKind.MODULE -> SfIcons.Extension\n                SourceKind.MORNYE -> SfIcons.Extension\n")

# ----------------------------- NEW FILES -----------------------------------
new(APP+"/data/sources/mornye/MornyeModels.kt", r'''package com.music.bitchord.data.sources.mornye

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val MornyeJson = Json { isLenient = true; ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

/** registry.json — see Mornye repository/model.rs. snake_case and camelCase both accepted. */
@Serializable
data class MornyeRegistry(
    val version: Int = 0,
    val extensions: List<MornyeRepoEntry> = emptyList(),
)

@Serializable
data class MornyeRepoEntry(
    val id: String = "",
    val name: String = "",
    @SerialName("display_name") val displayNameSnake: String = "",
    val displayName: String = "",
    val version: String = "",
    val description: String = "",
    @SerialName("download_url") val downloadUrlSnake: String = "",
    val downloadUrl: String = "",
    val category: String = "",
    val runtime: String = "",
    val sha256: String = "",
    @SerialName("checksum_sha256") val checksumSnake: String = "",
    val checksumSha256: String = "",
) {
    val url: String get() = downloadUrlSnake.ifBlank { downloadUrl }
    val label: String get() = displayNameSnake.ifBlank { displayName.ifBlank { name.ifBlank { id } } }
    val checksum: String get() = sha256.ifBlank { checksumSnake.ifBlank { checksumSha256 } }
    /** Only JavaScript extensions run here; python/lua entries are skipped. */
    val isJs: Boolean get() = runtime.isBlank() || runtime.equals("javascript", ignoreCase = true)
}

/** manifest.json inside a .sflx package. */
@Serializable
data class MornyeManifest(
    val name: String = "",
    val displayName: String = "",
    val version: String = "",
    val description: String = "",
    val type: List<String> = emptyList(),
    val qualityOptions: List<MornyeQuality> = emptyList(),
) {
    val isDownloadProvider: Boolean get() = type.contains("download_provider")
    val label: String get() = displayName.ifBlank { name }
}

@Serializable
data class MornyeQuality(val id: String = "", val label: String = "", val description: String = "")

/** One searchTracks() row. Mornye returns an array of these (or {tracks:[...]}). */
@Serializable
data class MornyeTrack(
    val id: String = "",
    val name: String = "",
    val artists: String = "",
    @SerialName("album_name") val albumName: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("cover_url") val coverUrl: String = "",
    val images: String = "",
    val isrc: String = "",
    @SerialName("audio_quality") val audioQuality: String = "",
) {
    val cover: String get() = coverUrl.ifBlank { images }
    val durationSec: Int get() = (durationMs / 1000).toInt()
}

/** getDownloadUrl() result. */
@Serializable
data class MornyeStream(
    val success: Boolean = false,
    val url: String = "",
    val format: String = "",
    @SerialName("bit_depth") val bitDepth: Int = 0,
    @SerialName("sample_rate") val sampleRate: Int = 0,
    val error: String = "",
    @SerialName("error_type") val errorType: String = "",
)
''')

new(APP+"/data/sources/mornye/MornyePackage.kt", r'''package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.addon.DetectedFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp.Request
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Fetches and unpacks Mornye packages and registries. A package is a ZIP
 * (.sflx / .spotiflac-ext / .zip) holding manifest.json + index.js. Contents
 * are cached under filesDir/mornye/<id>/<version>/ so a search and the later
 * stream call reuse the same unpacked script.
 */
internal object MornyePackage {
    private const val TAG = "BitChord"

    data class Ext(val id: String, val manifest: MornyeManifest, val js: String, val dir: File)

    /** registry.json URL for a GitHub repo URL, else the input unchanged. */
    fun normalizeRegistryUrl(raw: String): String {
        val u = raw.trim().trimEnd('/')
        if (u.contains("raw.githubusercontent.com")) return u
        val m = Regex("^https?://github.com/([^/]+)/([^/]+)").find(u) ?: return u
        val (owner, repo) = m.destructured
        return "https://raw.githubusercontent.com/$owner/${repo.removeSuffix(".git")}/HEAD/registry.json"
    }

    private fun isPackageUrl(u: String): Boolean {
        val p = u.substringBefore('?').substringBefore('#').lowercase()
        return p.endsWith(".sflx") || p.endsWith(".spotiflac-ext") || p.endsWith(".zip")
    }

    /** Recognise a Mornye URL before the addon/module passes. Null = not Mornye. */
    suspend fun detectUrl(raw: String): DetectedFormat? = withContext(Dispatchers.IO) {
        val url = raw.trim().substringBefore('#')
        try {
            if (isPackageUrl(url)) {
                val ext = loadPackage(url, url.substringAfterLast('/').substringBeforeLast('.'), "")
                return@withContext DetectedFormat.MornyeExtension(raw.trim(), ext.manifest.label.ifBlank { ext.id })
            }
            val regUrl = normalizeRegistryUrl(url)
            if (regUrl != url || regUrl.endsWith("registry.json")) {
                val body = fetchText(regUrl)
                val reg = runCatching { MornyeJson.decodeFromString(MornyeRegistry.serializer(), body) }.getOrNull()
                if (reg != null && reg.extensions.any { it.url.isNotBlank() }) {
                    return@withContext DetectedFormat.MornyeRegistry(regUrl, "Mornye extensions", reg.extensions.size)
                }
            }
        } catch (e: Exception) {
            TrackLog.d(TAG, "Mornye detectUrl($url): ${e.message}")
        }
        null
    }

    /** All JS download-provider extensions a source URL names (registry fan-out or single package). */
    suspend fun loadExtensions(baseUrl: String): List<Ext> = withContext(Dispatchers.IO) {
        val url = baseUrl.trim().substringBefore('#')
        if (isPackageUrl(url)) {
            return@withContext listOfNotNull(runCatching {
                loadPackage(url, url.substringAfterLast('/').substringBeforeLast('.'), "")
            }.getOrNull())
        }
        val regUrl = normalizeRegistryUrl(url)
        val reg = MornyeJson.decodeFromString(MornyeRegistry.serializer(), fetchText(regUrl))
        reg.extensions.filter { it.isJs && it.url.isNotBlank() }.mapNotNull { entry ->
            runCatching {
                val abs = if (entry.url.startsWith("http")) entry.url
                    else regUrl.substringBeforeLast('/') + "/" + entry.url.trimStart('/')
                loadPackage(abs, entry.id, entry.checksum)
            }.getOrNull()
        }.filter { it.manifest.isDownloadProvider }
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "BitChord-Mornye").build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.string() ?: throw IllegalStateException("Empty response")
        }
    }

    private fun loadPackage(url: String, id: String, sha256: String): Ext {
        val req = Request.Builder().url(url).header("User-Agent", "BitChord-Mornye").build()
        val bytes = Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            r.body?.bytes() ?: throw IllegalStateException("Empty package")
        }
        if (sha256.isNotBlank()) {
            val want = sha256.trim().lowercase().removePrefix("sha256:")
            val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (want.length == 64 && got != want) throw IllegalStateException("Checksum mismatch for $id")
        }
        var manifestJson: String? = null; var indexJs: String? = null
        ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name.substringAfterLast('/')
                when {
                    n.equals("manifest.json", true) -> manifestJson = z.readBytes().toString(Charsets.UTF_8)
                    n.equals("index.js", true) || n.equals("main.js", true) -> indexJs = z.readBytes().toString(Charsets.UTF_8)
                }
                e = z.nextEntry
            }
        }
        val manifest = MornyeJson.decodeFromString(MornyeManifest.serializer(),
            manifestJson ?: throw IllegalStateException("No manifest.json in $id"))
        val js = indexJs ?: throw IllegalStateException("No index.js in $id")
        val extId = manifest.name.ifBlank { id }
        val dir = File(MornyeEngine.appContext.filesDir, "mornye/$extId/${manifest.version}")
        dir.mkdirs()
        return Ext(extId, manifest, js, dir)
    }
}
''')

new(APP+"/data/sources/mornye/MornyeEngine.kt", r'''package com.music.bitchord.data.sources.mornye

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sandboxed QuickJS host for Mornye extensions. Mirrors the proven engine pool
 * and two-evaluate call pattern of the legacy QuickJsExecutor, but loads the
 * Mornye prelude (registerExtension + host API) instead of the Spine wrapper.
 * One engine per extension, reused across search/stream so initialize() state
 * (tokens) survives. Storage is a per-extension JSON file under filesDir.
 */
internal object MornyeEngine {
    private const val TAG = "BitChord"
    lateinit var appContext: Context
        private set
    fun init(ctx: Context) { appContext = ctx }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val engines = LinkedHashMap<String, QuickJs>(16, 0.75f, true)
    private const val MAX = 12

    suspend fun load(ext: MornyePackage.Ext, settingsJson: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            synchronized(lock) {
                if (engines.containsKey(ext.id)) return@runCatching
                while (engines.size >= MAX) {
                    val k = engines.keys.first(); engines.remove(k)?.let { runCatching { it.close() } }
                }
            }
            val qjs = QuickJs.create(Dispatchers.Default)
            qjs.maxStackSize = 512 * 1024L
            try {
                bind(qjs, ext)
                qjs.evaluate<Unit>(PRELUDE)
                qjs.evaluate<Unit>("var __mornye_settings = $settingsJson; var settings = __mornye_settings;")
                qjs.evaluate<Unit>(ext.js)
                val registered = qjs.evaluate<String>("(__mornye_ext ? 'yes' : 'no')")
                if (registered != "yes") throw IllegalStateException("extension did not call registerExtension()")
                synchronized(lock) { engines[ext.id] = qjs }
            } catch (e: Throwable) { runCatching { qjs.close() }; throw e }
        }
    }

    suspend fun call(extId: String, fn: String, argsJson: String): Result<String> = withContext(Dispatchers.Default) {
        val qjs = synchronized(lock) { engines[extId] }
            ?: return@withContext Result.failure(IllegalStateException("extension $extId not loaded"))
        runCatching {
            val fnLit = JSONObject.quote(fn)
            val argsLit = JSONObject.quote(argsJson)
            qjs.evaluate<String>(
                """
                var __mornye_resolved_json = undefined;
                (async function() {
                    try {
                        var f = __mornye_ext && __mornye_ext[$fnLit];
                        if (typeof f !== 'function') { __mornye_resolved_json = JSON.stringify({success:false,error:'no '+$fnLit}); return; }
                        var r = await f.apply(__mornye_ext, JSON.parse($argsLit));
                        __mornye_resolved_json = (typeof r === 'string') ? r : JSON.stringify(r);
                    } catch(e) { __mornye_resolved_json = JSON.stringify({success:false,error:(e&&e.message)||String(e)}); }
                })();
                """.trimIndent()
            )
            qjs.evaluate<String>("__mornye_resolved_json")
        }
    }

    fun unload(extId: String) { synchronized(lock) { engines.remove(extId) }?.let { runCatching { it.close() } } }

    // ---- storage ----
    private val storeLock = Mutex()
    private fun storeFile(ext: MornyePackage.Ext) = File(ext.dir, "storage.json")
    private suspend fun readStore(ext: MornyePackage.Ext): JSONObject = storeLock.withLock {
        val f = storeFile(ext); if (!f.exists()) return JSONObject()
        runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
    }
    private suspend fun writeStore(ext: MornyePackage.Ext, o: JSONObject) = storeLock.withLock {
        storeFile(ext).writeText(o.toString())
    }

    private suspend fun bind(qjs: QuickJs, ext: MornyePackage.Ext) {
        qjs.define("__mornye") {
            function("log", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) { TrackLog.d(TAG, "[mornye:${ext.id}] ${args.joinToString(" ")}") }
            })
            function("http", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val method = args.getOrNull(0)?.toString() ?: "GET"
                    val url = args.getOrNull(1)?.toString() ?: ""
                    val body = args.getOrNull(2)?.toString()?.takeIf { it != "null" }
                    val headers = args.getOrNull(3)?.toString() ?: "{}"
                    return try {
                        val (code, text, hdr) = fetchSync(url, method, headers, body)
                        JSONObject().put("ok", code in 200..299).put("statusCode", code)
                            .put("body", text).put("url", url).put("headers", hdr).toString()
                    } catch (e: Exception) {
                        JSONObject().put("ok", false).put("statusCode", 0).put("body", "").put("error", e.message ?: "error").toString()
                    }
                }
            })
            asyncFunction("fetch", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val url = args.getOrNull(0)?.toString() ?: ""
                    val method = args.getOrNull(1)?.toString() ?: "GET"
                    val headers = args.getOrNull(2)?.toString() ?: "{}"
                    val body = args.getOrNull(3)?.toString()?.takeIf { it != "null" }
                    val (code, text, _) = fetchSync(url, method, headers, body)
                    return JSONObject().put("status", code).put("ok", code in 200..299).put("body", text).toString()
                }
            })
            asyncFunction("setTimeout", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    kotlinx.coroutines.delay(args.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L); return "0"
                }
            })
            function("storageGet", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val k = args.getOrNull(0)?.toString() ?: ""
                    val o = kotlinx.coroutines.runBlocking { readStore(ext) }
                    return if (o.has(k)) o.get(k).toString() else ""
                }
            })
            function("storageSet", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    val k = args.getOrNull(0)?.toString() ?: return
                    val v = args.getOrNull(1)?.toString() ?: "null"
                    kotlinx.coroutines.runBlocking { val o = readStore(ext); o.put(k, JSONObject.wrap(org.json.JSONTokener(v).nextValue())); writeStore(ext, o) }
                }
            })
            function("storageRemove", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    val k = args.getOrNull(0)?.toString() ?: return
                    kotlinx.coroutines.runBlocking { val o = readStore(ext); o.remove(k); writeStore(ext, o) }
                }
            })
            function("storageClear", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) { kotlinx.coroutines.runBlocking { writeStore(ext, JSONObject()) } }
            })
            function("readFile", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val rel = args.getOrNull(0)?.toString() ?: return ""
                    val f = File(ext.dir, rel); return if (f.exists()) f.readText() else ""
                }
            })
            function("writeFile", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    val rel = args.getOrNull(0)?.toString() ?: return
                    val data = args.getOrNull(1)?.toString() ?: ""
                    val f = File(ext.dir, rel); f.parentFile?.mkdirs(); f.writeText(data)
                }
            })
            function("exists", object : FunctionBinding<Boolean> {
                override fun invoke(args: Array<Any?>): Boolean = File(ext.dir, args.getOrNull(0)?.toString() ?: "").exists()
            })
            function("localTime", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val c = Calendar.getInstance()
                    return JSONObject().put("hour", c.get(Calendar.HOUR_OF_DAY))
                        .put("timezone", c.timeZone.id ?: "UTC").toString()
                }
            })
        }
    }

    private fun fetchSync(url: String, method: String, headersJson: String, body: String?): Triple<Int, String, JSONObject> {
        val b = Request.Builder().url(url)
        var ua = false
        runCatching {
            val h = JSONObject(headersJson)
            for (k in h.keys()) { val v = h.optString(k); b.header(k, v); if (k.equals("user-agent", true)) ua = true }
        }
        if (!ua) b.header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
        val mt = "application/json; charset=utf-8".toMediaType()
        when (method.uppercase(Locale.ROOT)) {
            "POST" -> b.post((body ?: "").toRequestBody(mt))
            "PUT" -> b.put((body ?: "").toRequestBody(mt))
            "DELETE" -> b.delete()
            "HEAD" -> b.head()
            else -> b.get()
        }
        http.newCall(b.build()).execute().use { r ->
            val hdr = JSONObject(); for (n in r.headers.names()) hdr.put(n, r.headers[n])
            return Triple(r.code, r.body?.string() ?: "", hdr)
        }
    }

    // ---- Mornye host prelude (no '$' so Kotlin raw-string interpolation is inert) ----
    private const val PRELUDE = """
var __mornye_ext = null;
function registerExtension(o){ __mornye_ext = o; }
var console = { log:function(){__mornye.log.apply(null,[].slice.call(arguments));},
  info:function(){__mornye.log.apply(null,[].slice.call(arguments));},
  debug:function(){__mornye.log.apply(null,[].slice.call(arguments));},
  warn:function(){__mornye.log.apply(null,[].slice.call(arguments));},
  error:function(){__mornye.log.apply(null,[].slice.call(arguments));} };
var log = console;
function __http(method,url,body,headers){
  var b = (body===undefined||body===null)?null:(typeof body==='string'?body:JSON.stringify(body));
  var h = headers||{};
  return JSON.parse(__mornye.http(method,url,b,JSON.stringify(h)));
}
var http = {
  get:function(u,h){return __http('GET',u,null,h);},
  post:function(u,b,h){return __http('POST',u,b,h);},
  put:function(u,b,h){return __http('PUT',u,b,h);},
  delete:function(u,h){return __http('DELETE',u,null,h);},
  request:function(o){return __http(o.method||'GET',o.url,o.body,o.headers);}
};
var fetch = async function(url,opts){
  opts=opts||{}; var method=opts.method||'GET';
  var headers=opts.headers?(typeof opts.headers==='string'?opts.headers:JSON.stringify(opts.headers)):'{}';
  var body=(opts.body===undefined||opts.body===null)?null:(typeof opts.body==='string'?opts.body:JSON.stringify(opts.body));
  var raw=JSON.parse(await __mornye.fetch(url,method,headers,body));
  var text=raw.body;
  return { ok:raw.ok, status:raw.status, statusText:raw.ok?'OK':'Error',
    json:function(){return JSON.parse(text);}, text:function(){return text;},
    headers:{get:function(){return null;}} };
};
var storage = {
  get:function(k){var s=__mornye.storageGet(k);return s===''?undefined:JSON.parse(s);},
  set:function(k,v){__mornye.storageSet(k,JSON.stringify(v));},
  remove:function(k){__mornye.storageRemove(k);},
  clear:function(){__mornye.storageClear();}
};
var credentials = { store:storage.set, get:storage.get, remove:storage.remove,
  has:function(k){return storage.get(k)!==undefined;} };
var file = {
  read:function(p){return __mornye.readFile(p);},
  write:function(p,d){__mornye.writeFile(p,typeof d==='string'?d:JSON.stringify(d));},
  exists:function(p){return __mornye.exists(p);},
  download:function(u,p,o){return {success:false,error:'file.download not used by BitChord; getDownloadUrl is streamed by the host'};}
};
var auth = {
  isAuthenticated:function(){return !!(settings&&(settings.access_token||settings.token||settings.arl));},
  getTokens:function(){var t=settings&&(settings.access_token||settings.token)||'';return {access_token:t,refresh_token:(settings&&settings.refresh_token)||'',is_expired:false,is_authenticated:!!t};},
  getAuthCode:function(){return (settings&&settings.auth_code)||'';},
  setAuthCode:function(t){if(t&&t.access_token)storage.set('access_token',t.access_token);},
  clearAuth:function(){storage.remove('access_token');},
  openAuthUrl:function(){}
};
var utils = {
  encrypt:function(){return {success:false,error:'crypto not supported in BitChord host'};},
  decrypt:function(){return {success:false,error:'crypto not supported in BitChord host'};},
  generateKey:function(){return {success:false,error:'crypto not supported in BitChord host'};}
};
var gobackend = { getLocalTime:function(){return JSON.parse(__mornye.localTime());} };
var setTimeout = async function(fn,ms){ await __mornye.setTimeout(null,ms||0); if(typeof fn==='function')fn(); return 0; };
var clearTimeout = function(){};
function btoa(s){var c='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';var o='';var i=0;s=String(s);while(i<s.length){var a=s.charCodeAt(i++),b=s.charCodeAt(i++),d=s.charCodeAt(i++);var n=(a<<16)|((isNaN(b)?0:b)<<8)|(isNaN(d)?0:d);o+=c[(n>>18)&63]+c[(n>>12)&63]+(isNaN(b)?'=':c[(n>>6)&63])+(isNaN(d)?'=':c[n&63]);}return o;}
function atob(s){var c='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';s=String(s).replace(/=+$/,'');var o='';var i=0;while(i<s.length){var n=(c.indexOf(s[i++])<<18)|(c.indexOf(s[i++])<<12)|((c.indexOf(s[i++])&63)<<6)|(c.indexOf(s[i++])&63);o+=String.fromCharCode((n>>16)&255);if(i-2<s.length+2)o+=String.fromCharCode((n>>8)&255);o+=String.fromCharCode(n&255);}return o;}
"""
}
''')

new(APP+"/data/sources/MornyeSource.kt", r'''package com.music.bitchord.data.sources

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
''')

new(TEST+"/MornyeIntegrationTest.kt", r'''package com.music.bitchord

import com.music.bitchord.data.sources.mornye.MornyeJson
import com.music.bitchord.data.sources.mornye.MornyeManifest
import com.music.bitchord.data.sources.mornye.MornyePackage
import com.music.bitchord.data.sources.mornye.MornyeRegistry
import com.music.bitchord.data.sources.mornye.MornyeStream
import com.music.bitchord.data.sources.mornye.MornyeTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks for the Mornye wire formats and URL handling (no QuickJS needed). */
class MornyeIntegrationTest {
    @Test fun registry_snake_and_camel() {
        val body = """{"version":1,"extensions":[
            {"id":"deezer","download_url":"https://x/deezer.sflx","sha256":"abc"},
            {"id":"qobuz","downloadUrl":"https://x/qobuz.sflx","runtime":"javascript"},
            {"id":"py","downloadUrl":"https://x/py.sflx","runtime":"python"}]}"""
        val r = MornyeJson.decodeFromString(MornyeRegistry.serializer(), body)
        assertEquals(3, r.extensions.size)
        assertEquals("https://x/deezer.sflx", r.extensions[0].url)
        assertEquals("https://x/qobuz.sflx", r.extensions[1].url)
        assertTrue(r.extensions[0].isJs)
        assertFalse(r.extensions[2].isJs)
    }
    @Test fun github_repo_to_raw_registry() {
        assertEquals("https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/HEAD/registry.json",
            MornyePackage.normalizeRegistryUrl("https://github.com/mornyeapp/Mornye-Extension"))
        val raw = "https://raw.githubusercontent.com/a/b/main/registry.json"
        assertEquals(raw, MornyePackage.normalizeRegistryUrl(raw))
    }
    @Test fun manifest_download_provider() {
        val m = MornyeJson.decodeFromString(MornyeManifest.serializer(),
            """{"name":"deezer","displayName":"Deezer","type":["metadata_provider","download_provider"],
                "qualityOptions":[{"id":"FLAC","label":"FLAC Lossless"},{"id":"MP3_320","label":"320"}]}""")
        assertTrue(m.isDownloadProvider)
        assertEquals(2, m.qualityOptions.size)
    }
    @Test fun track_and_stream_shapes() {
        val t = MornyeJson.decodeFromString(MornyeTrack.serializer(),
            """{"id":"1","name":"Song","artists":"Artist","album_name":"Album","duration_ms":240000,"images":"http://art"}""")
        assertEquals(240, t.durationSec); assertEquals("http://art", t.cover)
        val s = MornyeJson.decodeFromString(MornyeStream.serializer(),
            """{"success":true,"url":"https://cdn/x.flac","format":"flac","bit_depth":24,"sample_rate":96000}""")
        assertTrue(s.success); assertEquals(24, s.bitDepth); assertEquals(96000, s.sampleRate)
    }
}
''')

new(".github/workflows/mornye-apk.yml", r'''name: Build Mornye APK
on:
  workflow_dispatch:
  push:
    branches: [ main ]
    paths: [ 'app/**', '.github/workflows/mornye-apk.yml' ]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
      - name: Unit tests (Mornye wire formats)
        run: ./gradlew :app:testProdDebugUnitTest --tests "com.music.bitchord.MornyeIntegrationTest"
      - name: Build installable APK (debug-signed)
        run: ./gradlew :app:assembleProdDebug
      - uses: actions/upload-artifact@v4
        with:
          name: BitChord-Mornye-apk
          path: app/build/outputs/apk/prod/debug/*.apk
''')

# ----------------------------- ENGINE --------------------------------------
def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "--revert":
        n = 0
        for base, _, files in os.walk(ROOT):
            if ".git" in base: continue
            for f in files:
                if f.endswith(".bak"):
                    p = os.path.join(base, f); shutil.move(p, p[:-4]); n += 1
        print("Reverted %d files from .bak" % n); return
    problems = []
    for rel, old, new in EDITS:
        p = os.path.join(ROOT, rel)
        if not os.path.exists(p): problems.append("missing file: " + rel); continue
        c = open(p, encoding="utf-8").read().count(old)
        if c != 1: problems.append("anchor count %d (want 1) in %s: %r" % (c, rel, old[:60]))
    if problems:
        print("ANCHOR PROBLEMS (your checkout differs from what this patch expects):")
        for x in problems: print("  -", x)
        sys.exit(1)
    if mode == "--check":
        print("All %d anchors present and unique. Ready to apply." % len(EDITS)); return
    for rel, old, new in EDITS:
        p = os.path.join(ROOT, rel)
        if not os.path.exists(p + ".bak"): shutil.copy2(p, p + ".bak")
        open(p, "w", encoding="utf-8").write(open(p, encoding="utf-8").read().replace(old, new, 1))
    for rel, content in NEW_FILES.items():
        p = os.path.join(ROOT, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
        if os.path.exists(p) and not os.path.exists(p + ".bak"): shutil.copy2(p, p + ".bak")
        open(p, "w", encoding="utf-8").write(content)
    print("Applied %d edits and wrote %d new files." % (len(EDITS), len(NEW_FILES)))
    print("Next: commit, push, and run the 'Build Mornye APK' workflow (or ./gradlew :app:assembleProdDebug).")

if __name__ == "__main__":
    main()