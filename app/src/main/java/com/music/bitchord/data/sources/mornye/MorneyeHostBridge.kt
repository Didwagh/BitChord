package com.music.bitchord.data.sources.mornye

import android.util.Base64
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * Kotlin implementation of every host method the Mornye bootstrap can call.
 *
 * The JS side calls `bridge(method, argsJson)` synchronously; this class
 * decodes the args, executes the operation on whatever thread QuickJS is
 * running on (always IO), and returns a JSON string shaped as
 * `{"value": <result>}` or `{"failure": "<message>"}`.
 *
 * Methods implemented here are the ones actually used by search/download addons.
 * The same host contract is documented at mornyeapp/Mornye-Extension.
 */
internal class MorneyeHostBridge(
    /** Encrypted per-addon key-value storage. */
    private val storage: MorneyeAddonStorage,
    /** Directory holding the files bundled inside the addon's archive. */
    private val packageDir: File,
    /** All JS callback functions registered by the addon (id → JS-side handle). */
    private val invokeCallback: (id: Int, args: JSONArray) -> String,
) {
    companion object {
        private const val TAG = "BitChord/Mornye"
        private const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024L  // 32 MB

        // Cookie jar shared per bridge instance so logins persist within a session.
        // We don't share it globally so addons can't see each other's cookies.
    }

    private val cookieJar = MorneyeCookieJar()

    private val httpClient by lazy {
        Http.client.newBuilder()
            .cookieJar(cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    // ── Entry point ───────────────────────────────────────────────────────

    /**
     * Called by the QuickJS binding for every `bridge(method, argsJson)` call
     * from JS. Must return quickly or block only on IO — never on the main
     * thread.
     */
    fun handle(method: String, argsJson: String): String {
        return try {
            val args = runCatching { JSONArray(argsJson) }.getOrDefault(JSONArray())
            val result = dispatch(method, args)
            JSONObject().apply { put("value", result) }.toString()
        } catch (e: Exception) {
            if (e.message?.contains("cancel", ignoreCase = true) == true) throw e
            TrackLog.d(TAG, "bridge $method failed: ${e.message}")
            JSONObject().apply { put("failure", e.message ?: "Unknown error") }.toString()
        }
    }

    // ── Dispatch ──────────────────────────────────────────────────────────

    private fun dispatch(method: String, args: JSONArray): Any? = when (method) {
        "http.request"        -> handleHttpRequest(args)
        "http.clearCookies"   -> { cookieJar.clear(); JSONObject().apply { put("success", true) } }
        "storage.get"         -> handleStorageGet(args)
        "storage.set"         -> handleStorageSet(args)
        "storage.remove"      -> handleStorageRemove(args)
        "storage.clear"       -> { storage.clear(); JSONObject().apply { put("success", true) } }
        "credentials.get"     -> handleCredentialsGet(args)
        "credentials.set"     -> handleCredentialsSet(args)
        "credentials.remove"  -> handleCredentialsRemove(args)
        "file"                -> handleFileRead(args)
        "base64Bytes"         -> handleBase64Encode(args)
        "decodeBase64Bytes"   -> handleBase64Decode(args)
        "callback"            -> handleCallback(args)
        "crypto.cipher"       -> handleCipher(args)
        "crypto.hmac"         -> handleHmac(args)
        "crypto.hash"         -> handleHash(args)
        "crypto.random"       -> handleRandom(args)
        "crypto.sign"         -> JSONObject().apply { put("value", "") }  // stub
        "log"                 -> { TrackLog.d(TAG, "[addon] ${args.optString(0)}"); null }
        else                  -> {
            TrackLog.d(TAG, "bridge: unhandled method $method")
            null
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private fun handleHttpRequest(args: JSONArray): Any {
        val url = args.optString(0).trim()
        val opts = args.optJSONObject(1) ?: JSONObject()
        val method = opts.optString("method", "GET").uppercase()
        val bodyStr = opts.optString("body", "")
        val headersObj = opts.optJSONObject("headers") ?: JSONObject()
        val asFetch = opts.optBoolean("asFetch", false)
        val binary = opts.optBoolean("binary", false)

        val builder = Request.Builder().url(url)
        for (key in headersObj.keys()) {
            runCatching { builder.header(key, headersObj.getString(key)) }
        }
        if (!headersObj.has("User-Agent")) {
            builder.header("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
        }

        val body = when (method) {
            "POST", "PUT", "PATCH" -> {
                val ct = headersObj.optString("Content-Type", "application/json; charset=utf-8")
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

        httpClient.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.byteStream()?.readBytes()
                ?.take(MAX_RESPONSE_BYTES.toInt())?.toByteArray()
                ?: ByteArray(0)
            val ok = response.isSuccessful

            return if (asFetch || binary) {
                JSONObject().apply {
                    put("ok", ok)
                    put("status", response.code)
                    put("body", if (binary) "" else bytes.toString(Charsets.UTF_8))
                    put("binary", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            } else {
                JSONObject().apply {
                    put("ok", ok)
                    put("status", response.code)
                    put("body", bytes.toString(Charsets.UTF_8))
                }
            }
        }
    }

    // ── Storage ───────────────────────────────────────────────────────────

    private fun handleStorageGet(args: JSONArray): JSONObject {
        val key = args.optString(0)
        val value = storage.get(key)
        return JSONObject().apply {
            put("found", value != null)
            if (value != null) put("value", value)
        }
    }

    private fun handleStorageSet(args: JSONArray): JSONObject {
        val key = args.optString(0)
        val value = args.opt(1)
        storage.set(key, value?.toString() ?: "")
        return JSONObject().apply { put("success", true) }
    }

    private fun handleStorageRemove(args: JSONArray): JSONObject {
        storage.remove(args.optString(0))
        return JSONObject().apply { put("success", true) }
    }

    private fun handleCredentialsGet(args: JSONArray): JSONObject {
        val key = args.optString(0)
        val value = storage.getCredential(key)
        return JSONObject().apply {
            put("found", value != null)
            if (value != null) put("value", value)
        }
    }

    private fun handleCredentialsSet(args: JSONArray): JSONObject {
        storage.setCredential(args.optString(0), args.optString(1))
        return JSONObject().apply { put("success", true) }
    }

    private fun handleCredentialsRemove(args: JSONArray): JSONObject {
        storage.removeCredential(args.optString(0))
        return JSONObject().apply { put("success", true) }
    }

    // ── File ──────────────────────────────────────────────────────────────

    private fun handleFileRead(args: JSONArray): Any {
        val path = args.optString(0).trimStart('/')
        // Sanitise: no path traversal outside the package directory.
        val resolved = File(packageDir, path).canonicalFile
        require(resolved.startsWith(packageDir.canonicalFile)) { "Path outside package" }
        require(resolved.isFile) { "File not found: $path" }
        require(resolved.length() <= MAX_RESPONSE_BYTES) { "File too large" }
        val bytes = resolved.readBytes()
        // Return as base64-encoded bytes object so the bootstrap can unpack it.
        return JSONObject().apply {
            put("__bytes", JSONArray(bytes.map { it.toInt() and 0xFF }))
        }
    }

    // ── Base64 ────────────────────────────────────────────────────────────

    private fun handleBase64Encode(args: JSONArray): String {
        val arr = args.optJSONArray(0) ?: return ""
        val bytes = ByteArray(arr.length()) { arr.getInt(it).toByte() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun handleBase64Decode(args: JSONArray): JSONObject {
        val b64 = args.optString(0).replace("\\s".toRegex(), "")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return JSONObject().apply {
            put("__bytes", JSONArray(bytes.map { it.toInt() and 0xFF }))
            put("__buffer", true)
        }
    }

    // ── Callbacks ─────────────────────────────────────────────────────────

    private fun handleCallback(args: JSONArray): Any? {
        val id = args.optInt(0)
        val cbArgs = args.optJSONArray(1) ?: JSONArray()
        return runCatching {
            val resultJson = invokeCallback(id, cbArgs)
            JSONObject(resultJson).opt("value")
        }.getOrNull()
    }

    // ── Crypto ────────────────────────────────────────────────────────────

    private fun handleCipher(args: JSONArray): JSONObject {
        val dataArg  = args.opt(0)
        val alg      = args.optString(1, "AES").uppercase()
        val mode     = args.optString(2, "CBC").uppercase()
        val keyArg   = args.opt(3)
        val ivArg    = args.opt(4)
        val pkcs7    = args.optBoolean(5, true)
        val decrypt  = args.optBoolean(6, false)

        val data  = unpackBytes(dataArg)
        val key   = unpackBytes(keyArg)
        val iv    = unpackBytes(ivArg)

        val transformation = when (alg) {
            "AES" -> "AES/${mode}/${if (pkcs7) "PKCS5Padding" else "NoPadding"}"
            "RC4" -> "RC4"
            else  -> throw IllegalArgumentException("Unsupported cipher: $alg")
        }

        val cipher = Cipher.getInstance(transformation)
        val secretKey = SecretKeySpec(key, alg.substringBefore('/'))
        if (alg == "RC4" || mode == "ECB") {
            cipher.init(if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE, secretKey)
        } else {
            cipher.init(
                if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
                secretKey,
                IvParameterSpec(iv),
            )
        }
        val result = cipher.doFinal(data)
        return JSONObject().apply {
            put("__bytes", JSONArray(result.map { it.toInt() and 0xFF }))
        }
    }

    private fun handleHmac(args: JSONArray): JSONObject {
        val data  = unpackBytes(args.opt(0))
        val key   = unpackBytes(args.opt(1))
        val alg   = args.optString(2, "SHA-256")
        val macAlg = "Hmac${alg.replace("-", "")}"
        val mac = Mac.getInstance(macAlg)
        mac.init(SecretKeySpec(key, macAlg))
        val result = mac.doFinal(data)
        return JSONObject().apply {
            put("__bytes", JSONArray(result.map { it.toInt() and 0xFF }))
        }
    }

    private fun handleHash(args: JSONArray): JSONObject {
        val data = unpackBytes(args.opt(0))
        val alg  = args.optString(1, "SHA-256").replace("-", "").let {
            if (it == "SHA256") "SHA-256" else if (it == "SHA1") "SHA-1" else it
        }
        val result = MessageDigest.getInstance(alg).digest(data)
        return JSONObject().apply {
            put("__bytes", JSONArray(result.map { it.toInt() and 0xFF }))
        }
    }

    private fun handleRandom(args: JSONArray): JSONObject {
        val n = args.optInt(0, 16).coerceIn(1, 1024)
        val bytes = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }
        return JSONObject().apply {
            put("__bytes", JSONArray(bytes.map { it.toInt() and 0xFF }))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Decode a value that the JS `pack()` function may have sent as either
     * a `{__bytes: [...]}` object, a plain byte array, or a Base64 string.
     */
    private fun unpackBytes(value: Any?): ByteArray {
        if (value is JSONObject && value.has("__bytes")) {
            val arr = value.getJSONArray("__bytes")
            return ByteArray(arr.length()) { arr.getInt(it).toByte() }
        }
        if (value is JSONArray) {
            return ByteArray(value.length()) { value.getInt(it).toByte() }
        }
        if (value is String) {
            return Base64.decode(value.replace("\\s".toRegex(), ""), Base64.DEFAULT)
        }
        return ByteArray(0)
    }
}

/** Simple in-memory cookie jar so addon HTTP sessions persist within one runtime. */
private class MorneyeCookieJar : okhttp3.CookieJar {
    private val jar = mutableMapOf<String, MutableList<okhttp3.Cookie>>()

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        jar.getOrPut(url.host) { mutableListOf() }.apply {
            removeAll { existing -> cookies.any { it.name == existing.name } }
            addAll(cookies)
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
        jar[url.host]?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()

    fun clear() = jar.clear()
}
