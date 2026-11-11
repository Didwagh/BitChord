package com.music.bitchord.data.sources.mornye

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
