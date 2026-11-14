package com.music.bitchord.data.sources.mornye

import android.util.Base64
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Kotlin implementation of the full Mornye host bridge.
 *
 * Every `bridge(method, argsJson)` call from JS routes here.
 * HTTP is blocking OkHttp (fine on IO dispatcher).
 * File ops are allowed inside [cacheDir] (temp downloads) and [packageDir] (addon assets).
 */
internal class MorneyeHostBridge(
    private val storage: MorneyeAddonStorage,
    private val packageDir: File,
    /** Directory for addon-written temp/download files — typically <cacheDir>/mornye/<addonKey>/ */
    private val cacheDir: File,
    private val invokeCallback: (id: Int, args: JSONArray) -> String,
) {
    companion object {
        private const val TAG = "BitChord/Mornye"
        private const val MAX_BODY = 32 * 1024 * 1024L
    }

    private val cookieJar = MorneyeCookieJar()
    private val httpClient by lazy {
        Http.client.newBuilder()
            .cookieJar(cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun handle(method: String, argsJson: String): String {
        return try {
            val args = runCatching { JSONArray(argsJson) }.getOrDefault(JSONArray())
            val result = dispatch(method, args)
            JSONObject().apply { put("value", result) }.toString()
        } catch (e: Exception) {
            if (e.message?.contains("cancel", ignoreCase = true) == true) throw e
            TrackLog.d(TAG, "bridge $method failed: ${e.message}")
            JSONObject().apply { put("failure", e.message ?: "Error") }.toString()
        }
    }

    private fun dispatch(method: String, args: JSONArray): Any? = when (method) {
        "http.request"                  -> handleHttpRequest(args)
        "http.clearCookies"             -> { cookieJar.clear(); JSONObject().apply { put("success", true) } }
        "storage.get"                   -> storageGet(args)
        "storage.set"                   -> { storage.set(args.optString(0), args.optString(1)); JSONObject().apply { put("success", true) } }
        "storage.remove"                -> { storage.remove(args.optString(0)); JSONObject().apply { put("success", true) } }
        "storage.clear"                 -> { storage.clear(); JSONObject().apply { put("success", true) } }
        "credentials.get"               -> credGet(args)
        "credentials.set"               -> { storage.setCredential(args.optString(0), args.optString(1)); JSONObject().apply { put("success", true) } }
        "credentials.remove"            -> { storage.removeCredential(args.optString(0)); JSONObject().apply { put("success", true) } }
        "file"                          -> handlePackageFileRead(args)
        "file.download"                 -> handleFileDownload(args)
        "file.delete"                   -> handleFileDelete(args)
        "file.exists"                   -> handleFileExists(args)
        "file.read"                     -> handleFileRead(args)
        "file.write"                    -> handleFileWrite(args)
        "file.transformPatternedBlocks" -> handleTransformPatternedBlocks(args)
        "base64Bytes"                   -> handleBase64Encode(args)
        "decodeBase64Bytes"             -> handleBase64Decode(args)
        "callback"                      -> handleCallback(args)
        "crypto.cipher"                 -> handleCipher(args)
        "crypto.hmac"                   -> handleHmac(args)
        "crypto.hash"                   -> handleHash(args)
        "crypto.random"                 -> handleRandom(args)
        "crypto.sign"                   -> JSONObject().apply { put("value", "") }
        "log"                           -> { TrackLog.d(TAG, "[addon] ${args.optString(0)}"); null }
        else                            -> { TrackLog.d(TAG, "bridge: unhandled $method"); null }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private fun handleHttpRequest(args: JSONArray): JSONObject {
        val url     = args.optString(0).trim()
        val opts    = args.optJSONObject(1) ?: JSONObject()
        val method  = opts.optString("method", "GET").uppercase()
        val bodyStr = opts.optString("body", "")
        val hdrs    = opts.optJSONObject("headers") ?: JSONObject()

        val builder = Request.Builder().url(url)
        for (k in hdrs.keys()) runCatching { builder.header(k, hdrs.getString(k)) }
        if (!hdrs.has("User-Agent"))
            builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")

        val body = when (method) {
            "POST", "PUT", "PATCH" -> {
                val ct = hdrs.optString("Content-Type", "application/json; charset=utf-8")
                bodyStr.toRequestBody(ct.toMediaType())
            }
            else -> null
        }
        when (method) {
            "POST"   -> builder.post(body!!)
            "PUT"    -> builder.put(body!!)
            "PATCH"  -> builder.patch(body!!)
            "DELETE" -> builder.delete()
            "HEAD"   -> builder.head()
            else     -> builder.get()
        }

        httpClient.newCall(builder.build()).execute().use { resp ->
            val bytes = resp.body?.byteStream()
                ?.readBytes()?.take(MAX_BODY.toInt())?.toByteArray() ?: ByteArray(0)
            return JSONObject().apply {
                put("ok",     resp.isSuccessful)
                put("status", resp.code)
                put("body",   bytes.toString(Charsets.UTF_8))
            }
        }
    }

    // ── Storage / credentials ─────────────────────────────────────────────

    private fun storageGet(args: JSONArray) = JSONObject().apply {
        val v = storage.get(args.optString(0))
        put("found", v != null)
        if (v != null) put("value", v)
    }

    private fun credGet(args: JSONArray) = JSONObject().apply {
        val v = storage.getCredential(args.optString(0))
        put("found", v != null)
        if (v != null) put("value", v)
    }

    // ── Package file read (original assets bundled in the .sflx archive) ──

    private fun handlePackageFileRead(args: JSONArray): JSONObject {
        val path     = args.optString(0).trimStart('/')
        val resolved = File(packageDir, path).canonicalFile
        require(resolved.startsWith(packageDir.canonicalFile)) { "Path outside package" }
        require(resolved.isFile) { "File not found in package: $path" }
        val bytes = resolved.readBytes()
        return JSONObject().apply {
            put("__bytes", JSONArray(bytes.map { it.toInt() and 0xFF }))
        }
    }

    // ── Arbitrary file ops (cacheDir or packageDir paths) ─────────────────

    private fun handleFileDownload(args: JSONArray): JSONObject {
        val url  = args.optString(0).trim()
        val path = args.optString(1).trim()
        val opts = args.optJSONObject(2) ?: JSONObject()
        val file = resolveWritablePath(path)

        val builder = Request.Builder().url(url)
        val hdrs = opts.optJSONObject("headers") ?: JSONObject()
        for (k in hdrs.keys()) runCatching { builder.header(k, hdrs.getString(k)) }
        if (!hdrs.has("User-Agent"))
            builder.header("User-Agent", "Mozilla/5.0")

        TrackLog.d(TAG, "file.download $url → $path")
        httpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return JSONObject().apply {
                put("success", false)
                put("error", "HTTP ${resp.code}")
                put("error_type", "http_error")
            }
            resp.body?.byteStream()?.use { input ->
                file.outputStream().buffered(65536).use { out -> input.copyTo(out, 65536) }
            }
        }
        return JSONObject().apply { put("success", true); put("path", file.absolutePath) }
    }

    private fun handleFileDelete(args: JSONArray): JSONObject {
        val path = args.optString(0).trim()
        runCatching { resolveWritablePath(path).delete() }
        return JSONObject().apply { put("success", true) }
    }

    private fun handleFileExists(args: JSONArray): JSONObject {
        val path = args.optString(0).trim()
        val exists = runCatching { resolveWritablePath(path).exists() }.getOrDefault(false)
        return JSONObject().apply { put("success", exists) }
    }

    private fun handleFileRead(args: JSONArray): JSONObject {
        val path  = args.optString(0).trim()
        val file  = resolveReadablePath(path)
        require(file.isFile && file.length() <= MAX_BODY) { "Cannot read $path" }
        val bytes = file.readBytes()
        return JSONObject().apply {
            put("__bytes", JSONArray(bytes.map { it.toInt() and 0xFF }))
            put("__buffer", true)
        }
    }

    private fun handleFileWrite(args: JSONArray): JSONObject {
        val path  = args.optString(0).trim()
        val data  = args.opt(1)
        val file  = resolveWritablePath(path)
        file.writeBytes(unpackBytes(data))
        return JSONObject().apply { put("success", true); put("path", file.absolutePath) }
    }

    /**
     * Used by Deezer (and potentially others) to decrypt files in-place by
     * transforming every Nth block while passing the rest through unchanged.
     *
     * options keys: algorithm, mode, key (hex), keyEncoding, iv (hex),
     *   ivEncoding, padding, segmentSize, transformEvery, transformOffset,
     *   bufferSize, transformPartial
     */
    private fun handleTransformPatternedBlocks(args: JSONArray): JSONObject {
        val inputPath  = args.optString(0).trim()
        val outputPath = args.optString(1).trim()
        val opts       = args.optJSONObject(2) ?: JSONObject()

        val inputFile  = resolveReadablePath(inputPath)
        val outputFile = resolveWritablePath(outputPath)
        require(inputFile.isFile) { "Input not found: $inputPath" }

        val algorithm     = opts.optString("algorithm", "blowfish").uppercase().let { if (it == "BLOWFISH") "Blowfish" else it }
        val mode          = opts.optString("mode", "cbc").uppercase()
        val segmentSize   = opts.optInt("segmentSize", 2048).coerceIn(16, 65536)
        val transformEvery= opts.optInt("transformEvery", 3).coerceAtLeast(1)
        val transformOffset = opts.optInt("transformOffset", 0)
        val keyEnc        = opts.optString("keyEncoding", "hex")
        val ivEnc         = opts.optString("ivEncoding", "hex")
        val keyRaw        = opts.optString("key", "")
        val ivRaw         = opts.optString("iv", "")

        val keyBytes = if (keyEnc == "hex") hexToBytes(keyRaw) else keyRaw.toByteArray()
        val ivBytes  = if (ivEnc  == "hex") hexToBytes(ivRaw)  else ivRaw.toByteArray()
        val secretKey = SecretKeySpec(keyBytes, algorithm)

        TrackLog.d(TAG, "transformPatternedBlocks $algorithm/$mode seg=$segmentSize every=$transformEvery")

        inputFile.inputStream().buffered(1 shl 20).use { input ->
            outputFile.outputStream().buffered(1 shl 20).use { output ->
                val buf = ByteArray(segmentSize)
                var blockIdx = 0
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    val shouldDecrypt = (blockIdx % transformEvery) == transformOffset && n == segmentSize
                    val outBytes = if (shouldDecrypt) {
                        val cipher = Cipher.getInstance("$algorithm/$mode/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ivBytes))
                        cipher.doFinal(buf, 0, n)
                    } else {
                        buf.copyOf(n)
                    }
                    output.write(outBytes)
                    blockIdx++
                }
            }
        }
        return JSONObject().apply { put("success", true); put("path", outputFile.absolutePath) }
    }

    // ── Base64 ────────────────────────────────────────────────────────────

    private fun handleBase64Encode(args: JSONArray): String {
        val arr   = args.optJSONArray(0) ?: return ""
        val bytes = ByteArray(arr.length()) { arr.getInt(it).toByte() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun handleBase64Decode(args: JSONArray): JSONObject {
        val b64   = args.optString(0).replace("\\s".toRegex(), "")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return JSONObject().apply {
            put("__bytes", JSONArray(bytes.map { it.toInt() and 0xFF }))
            put("__buffer", true)
        }
    }

    // ── Callbacks ─────────────────────────────────────────────────────────

    private fun handleCallback(args: JSONArray): Any? {
        val id    = args.optInt(0)
        val cbArgs= args.optJSONArray(1) ?: JSONArray()
        return runCatching {
            val r = invokeCallback(id, cbArgs)
            JSONObject(r).opt("value")
        }.getOrNull()
    }

    // ── Crypto ────────────────────────────────────────────────────────────

    private fun handleCipher(args: JSONArray): JSONObject {
        val dataArg = args.opt(0)
        val alg     = args.optString(1, "AES").uppercase().let {
            when (it) { "BLOWFISH" -> "Blowfish"; else -> it }
        }
        val mode    = args.optString(2, "CBC").uppercase()
        val keyArg  = args.opt(3)
        val ivArg   = args.opt(4)
        val pkcs7   = args.optBoolean(5, true)
        val decrypt = args.optBoolean(6, false)

        val data  = unpackBytes(dataArg)
        val key   = unpackBytes(keyArg)
        val iv    = unpackBytes(ivArg)

        val padding   = if (pkcs7) "PKCS5Padding" else "NoPadding"
        val transform = when (alg) {
            "RC4"      -> "RC4"
            "Blowfish" -> "Blowfish/$mode/$padding"
            else       -> "$alg/$mode/$padding"
        }
        val cipher    = Cipher.getInstance(transform)
        val secretKey = SecretKeySpec(key, alg.substringBefore('/'))
        if (alg == "RC4" || mode == "ECB") {
            cipher.init(if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE, secretKey)
        } else {
            cipher.init(
                if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
                secretKey, IvParameterSpec(iv),
            )
        }
        val result = cipher.doFinal(data)
        return JSONObject().apply {
            put("__bytes", JSONArray(result.map { it.toInt() and 0xFF }))
        }
    }

    private fun handleHmac(args: JSONArray): JSONObject {
        val data   = unpackBytes(args.opt(0))
        val key    = unpackBytes(args.opt(1))
        val alg    = args.optString(2, "SHA-256")
        val macAlg = "Hmac${alg.replace("-", "")}"
        val mac    = Mac.getInstance(macAlg)
        mac.init(SecretKeySpec(key, macAlg))
        val result = mac.doFinal(data)
        return JSONObject().apply { put("__bytes", JSONArray(result.map { it.toInt() and 0xFF })) }
    }

    private fun handleHash(args: JSONArray): JSONObject {
        val data = unpackBytes(args.opt(0))
        val alg  = args.optString(1, "SHA-256")
            .let { if (it == "SHA256") "SHA-256" else if (it == "SHA1") "SHA-1" else if (it == "MD5") "MD5" else it }
        val result = MessageDigest.getInstance(alg).digest(data)
        return JSONObject().apply { put("__bytes", JSONArray(result.map { it.toInt() and 0xFF })) }
    }

    private fun handleRandom(args: JSONArray): JSONObject {
        val n     = args.optInt(0, 16).coerceIn(1, 1024)
        val bytes = ByteArray(n).also { SecureRandom().nextBytes(it) }
        return JSONObject().apply { put("__bytes", JSONArray(bytes.map { it.toInt() and 0xFF })) }
    }

    // ── Path helpers ──────────────────────────────────────────────────────

    /**
     * Resolves a path that the addon is allowed to WRITE to.
     * Permitted: anywhere under [cacheDir].
     */
    private fun resolveWritablePath(path: String): File {
        val f = File(path).let { if (it.isAbsolute) it else File(cacheDir, path) }
        val canonical = f.canonicalFile
        require(canonical.path.startsWith(cacheDir.canonicalPath)) {
            "Write path outside cacheDir: $path"
        }
        canonical.parentFile?.mkdirs()
        return canonical
    }

    /**
     * Resolves a path the addon may READ from.
     * Permitted: [cacheDir] (addon files) or [packageDir] (bundled assets).
     */
    private fun resolveReadablePath(path: String): File {
        val f         = File(path).let { if (it.isAbsolute) it else File(cacheDir, path) }
        val canonical = f.canonicalFile
        val pkgCanon  = packageDir.canonicalPath
        val cacheCanon= cacheDir.canonicalPath
        require(canonical.path.startsWith(pkgCanon) || canonical.path.startsWith(cacheCanon)) {
            "Read path outside allowed directories: $path"
        }
        return canonical
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private fun unpackBytes(value: Any?): ByteArray {
        if (value is JSONObject && value.has("__bytes")) {
            val arr = value.getJSONArray("__bytes")
            return ByteArray(arr.length()) { arr.getInt(it).toByte() }
        }
        if (value is JSONArray) return ByteArray(value.length()) { value.getInt(it).toByte() }
        if (value is String) return try {
            Base64.decode(value.replace("\\s".toRegex(), ""), Base64.DEFAULT)
        } catch (e: Exception) { ByteArray(0) }
        return ByteArray(0)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.trim().lowercase()
        return ByteArray(h.length / 2) { i ->
            ((h[i * 2].digitToInt(16) shl 4) or h[i * 2 + 1].digitToInt(16)).toByte()
        }
    }
}

private class MorneyeCookieJar : okhttp3.CookieJar {
    private val jar = mutableMapOf<String, MutableList<okhttp3.Cookie>>()
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        jar.getOrPut(url.host) { mutableListOf() }.apply {
            removeAll { e -> cookies.any { it.name == e.name } }
            addAll(cookies)
        }
    }
    override fun loadForRequest(url: okhttp3.HttpUrl) =
        jar[url.host]?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()
    fun clear() = jar.clear()
}
