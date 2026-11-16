package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.TrackLog
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The signed-session client the Tidal, Qobuz, Amazon and Deezer addons require.
 *
 * Those addons talk to a gateway (`api.zarz.moe`) rather than to the provider
 * directly. The gateway issues a short-lived `session_id` + `session_secret`
 * pair and expects every request to be signed with a rolling HMAC. The
 * protocol is documented by the reference hosts (Mornye's Rust
 * `signed_session` module and Lokal's Electron `session.js`); this is a Kotlin
 * port of the same wire format, not a reinterpretation of it:
 *
 *  - `X-Zarz-Session`, `X-Zarz-Timestamp`, `X-Zarz-Nonce`,
 *    `X-Zarz-Body-SHA256`, `X-Zarz-Signature`, `X-Zarz-App-Version`,
 *    `X-Zarz-Platform` (the prefix comes from the manifest).
 *  - The rolling key is `base64url(HMAC-SHA256(session_secret,
 *    "<window>:<session_id>"))`, where the window is the Unix time divided by
 *    `timeWindowSeconds`.
 *  - The signed string is the scheme label, method, path, an empty query
 *    segment, body SHA-256, timestamp, nonce, session id, app version and
 *    platform, joined with `\n`.
 *
 * When the gateway wants a human verification it answers the bootstrap with a
 * `challenge_id` instead of a session. The challenge URL is opened by the app;
 * the resulting `mornye://session-grant?...&grant=...` callback is exchanged
 * for a session. Until that happens [signedFetch] returns
 * `{needsVerification: true, auth_url: …}` exactly like the reference hosts,
 * which the addons already understand.
 */
internal class MorneyeSignedSession(
    private val config: MorneyeSignedSessionConfig,
    private val store: MorneyeSignedSessionStore,
    private val client: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "BitChord/Mornye"
        private const val DEFAULT_CALLBACK = "mornye://session-grant"
        private const val VERIFICATION_TIMEOUT_MS = 180_000L
        private const val REFRESH_WINDOW_SECONDS = 3_600L
        private val EMPTY_JSON = "application/json".toMediaType()

        private val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
    }

    private val namespace: String = sha256Hex(
        listOf(config.namespace, config.baseUrl, config.appVersion, config.platform)
            .joinToString("\n")
            .toByteArray(Charsets.UTF_8),
    )
    private val recordKey = "signed_session:$namespace"
    private val pendingKey = "signed_session_pending:$namespace"
    private val grantKey = "signed_session_grant:$namespace"

    @Volatile private var blocked = false

    // ── Public surface ─────────────────────────────────────────────────────

    /** What the settings screen and the addon's `session.status()` see. */
    @Synchronized
    fun status(): JSONObject {
        val record = record()
        val pending = pending()
        return JSONObject().apply {
            put("authenticated", usable(record) && !blocked)
            put("verification_required", pending != null)
            put("expires_at", record.expiresAt)
            put("session_id", record.sessionId)
            put("install_id", record.installId)
            put("app_version", config.appVersion)
            put("platform", config.platform)
            put("auth_url", pending?.url ?: "")
        }
    }

    @Synchronized
    fun clear(): JSONObject {
        val record = record()
        saveRecord(MorneyeSignedSessionRecord(installId = record.installId))
        store.set(pendingKey, "")
        store.set(grantKey, "")
        blocked = false
        return JSONObject().apply { put("success", true) }
    }

    /**
     * Starts (or resumes) verification. Returns the URL the user must open, or
     * null when the stored session is already usable.
     */
    @Synchronized
    fun begin(): String? {
        val record = record()
        if (usable(record) && !blocked) return null
        return runCatching { bootstrap(record) }
            .onFailure { TrackLog.w(TAG, "signed-session bootstrap failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * The URL the user must open to approve this install, from the last
     * bootstrap. Used when the app has to show a button without making a
     * network call.
     */
    @Synchronized
    fun pendingAuthUrl(): String? = pending()?.url?.takeIf { it.isNotBlank() }

    /**
     * Completes a verification callback. [raw] may be the full
     * `mornye://session-grant?...&grant=...` link, a bare grant, or a
     * `grant=...` fragment. The `state` is checked against the pending
     * bootstrap when it is present.
     */
    @Synchronized
    fun completeGrant(raw: String? = null): JSONObject {
        return try {
            val grant = extractGrant(raw)
            if (grant.isBlank()) {
                JSONObject().apply {
                    put("success", false)
                    put(
                        "error",
                        "No verification grant. Open the verification page and paste its callback link.",
                    )
                }
            } else {
                exchange(grant)
                JSONObject().apply { put("success", true) }
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "signed-session exchange failed: ${e.message}")
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Session exchange failed")
            }
        }
    }

    /**
     * Stores a grant captured by the Android deep link so the addon's own
     * `completeGrant()` (which is called with no arguments) can finish the
     * exchange synchronously.
     */
    @Synchronized
    fun storePendingGrant(raw: String) {
        extractGrant(raw).takeIf { it.isNotBlank() }?.let { store.set(grantKey, it) }
    }

    /**
     * The addon-facing signed request. Returns the same JSON shape the Rust and
     * Electron hosts return: `ok`, `status`/`statusCode`, `body`, `headers`,
     * plus the gateway's error contract when the response carries one.
     */
    @Synchronized
    fun signedFetch(
        method: String,
        path: String,
        body: String,
        extraHeaders: Map<String, String>,
    ): JSONObject {
        return try {
            var record = record()
            if (!usable(record) || blocked) {
                // bootstrap() returns null when it minted a session and the
                // auth URL when a human has to approve this install.
                val authUrl = bootstrap(record)
                if (!authUrl.isNullOrBlank()) return verification(record, authUrl)
                record = record()
            }
            if (refreshDue(record)) {
                runCatching { refresh(record) }
                    .onFailure { TrackLog.d(TAG, "signed-session refresh failed: ${it.message}") }
                record = record()
                if (!usable(record) || blocked) {
                    val authUrl = bootstrap(record)
                    if (!authUrl.isNullOrBlank()) return verification(record, authUrl)
                    record = record()
                }
            }
            fetchWithSession(record, method, path, body, extraHeaders)
        } catch (e: Exception) {
            TrackLog.w(TAG, "signed request failed: ${e.message}")
            JSONObject().apply {
                put("ok", false)
                put("status", 0)
                put("statusCode", 0)
                put("error", e.message ?: "Signed request failed")
            }
        }
    }

    // ── Request loop ───────────────────────────────────────────────────────

    private fun fetchWithSession(
        initial: MorneyeSignedSessionRecord,
        method: String,
        path: String,
        body: String,
        extraHeaders: Map<String, String>,
    ): JSONObject {
        var record = initial
        var sessionRetries = 0
        var providerRetries = 0

        while (true) {
            val response = request(record, method, path, body, extraHeaders)
            val contract = runCatching { JSONObject(response.body) }.getOrDefault(JSONObject())
            val code = contract.optString("code").uppercase()
            val origin = contract.optString("origin")
            val action = contract.optString("action")
            val retryable = contract.optBoolean("retryable", false)
            val retryMode = contract.optString("retry_mode")
            val retryAfter = retryAfterSeconds(response, contract)

            if (
                response.status == 503 && origin == "provider" &&
                code == "PROVIDER_UNAVAILABLE" && retryable &&
                (retryMode.isEmpty() || retryMode == "same_operation") &&
                providerRetries < 2
            ) {
                val delayMs = retryAfter.coerceIn(1L, 120L) * 1_000L
                runCatching { Thread.sleep(delayMs) }
                providerRetries++
                continue
            }

            val reset = response.status == 401 && origin == "gateway" && code == "SESSION_INVALID"
            val verify = response.status == 428 && origin == "gateway" && code == "VERIFY_REQUIRED"
            if ((reset || verify) && sessionRetries < 1) {
                sessionRetries++
                if (reset) {
                    saveRecord(MorneyeSignedSessionRecord(installId = record.installId))
                    blocked = false
                } else {
                    blocked = true
                }
                val authUrl = bootstrap(record())
                if (!authUrl.isNullOrBlank()) return verification(record(), authUrl)
                record = record()
                continue
            }

            return response.toJson(contract)
        }
    }

    // ── Bootstrap / exchange / refresh ─────────────────────────────────────

    /**
     * Returns null when a session was minted, or the verification URL when a
     * human has to approve this install. A previous still-valid pending
     * challenge is reused until it expires, so pressing "Connect" twice does
     * not burn a challenge.
     */
    private fun bootstrap(record: MorneyeSignedSessionRecord): String? {
        pending()?.let { pending ->
            if (now() - pending.createdAt < VERIFICATION_TIMEOUT_MS) return pending.url
            store.set(pendingKey, "")
        }

        val url = endpoint(config.bootstrapPath).newBuilder()
            .addQueryParameter("app_version", config.appVersion)
            .addQueryParameter("install_id", record.installId)
            .build()
        val response = execute(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent())
                .get()
                .build(),
        )
        if (response.status !in 200..299) {
            throw IllegalStateException("Signed-session bootstrap returned HTTP ${response.status}")
        }
        val json = runCatching { JSONObject(response.body) }
            .getOrElse { throw IllegalStateException("Signed-session bootstrap returned invalid JSON") }

        val sessionId = json.optString("session_id")
        val secret = json.optString("session_secret")
        val expires = json.optString("expires_at")
        if (sessionId.isNotBlank() && secret.isNotBlank() && expires.isNotBlank()) {
            saveRecord(
                MorneyeSignedSessionRecord(
                    installId = record.installId,
                    sessionId = sessionId,
                    sessionSecret = secret,
                    expiresAt = expires,
                ),
            )
            store.set(pendingKey, "")
            blocked = false
            return null
        }

        var authUrl = json.optString("auth_url")
            .ifBlank { json.optString("challenge_url") }
        val state = randomBase64Url(24)
        val callback = callbackUrl(state)

        if (authUrl.isBlank() && json.optString("challenge_id").isNotBlank()) {
            authUrl = endpoint(config.challengePath).newBuilder()
                .addQueryParameter("id", json.optString("challenge_id"))
                .addQueryParameter("cb", callback)
                .build()
                .toString()
        }
        if (authUrl.isBlank()) {
            throw IllegalStateException(
                "Signed-session bootstrap returned neither a session nor a challenge",
            )
        }

        val validated = validateAuthUrl(authUrl)
        // Prefer the state the gateway put in the URL (or in a nested callback)
        // over ours: the callback has to echo what the challenge page holds.
        val returnedState = stateFrom(validated) ?: state
        val finalUrl = if (stateFrom(validated) == null) {
            validated.newBuilder().addQueryParameter("state", returnedState).build().toString()
        } else {
            validated.toString()
        }
        savePending(MorneyeSignedPending(finalUrl, returnedState, now()))
        return finalUrl
    }

    private fun exchange(grant: String) {
        val record = record()
        val body = JSONObject().apply {
            put("grant", grant)
            put("install_id", record.installId)
            put("app_version", config.appVersion)
            put("platform", config.platform)
        }.toString()
        val response = execute(
            Request.Builder()
                .url(endpoint(config.exchangePath))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent())
                .post(body.toRequestBody(EMPTY_JSON))
                .build(),
        )
        if (response.status !in 200..299) {
            throw IllegalStateException("Session exchange returned HTTP ${response.status}")
        }
        val json = JSONObject(response.body)
        val sessionId = json.optString("session_id")
        val secret = json.optString("session_secret")
        val expires = json.optString("expires_at")
        if (sessionId.isBlank() || secret.isBlank() || expires.isBlank()) {
            throw IllegalStateException("Session exchange omitted its session fields")
        }
        saveRecord(
            MorneyeSignedSessionRecord(
                installId = record.installId,
                sessionId = sessionId,
                sessionSecret = secret,
                expiresAt = expires,
            ),
        )
        store.set(pendingKey, "")
        store.set(grantKey, "")
        blocked = false
    }

    private fun refresh(record: MorneyeSignedSessionRecord) {
        val body = JSONObject().put("install_id", record.installId).toString()
        val response = request(record, "POST", config.refreshPath, body, emptyMap())
        if (response.status !in 200..299) return
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return
        val sessionId = json.optString("session_id").ifBlank { record.sessionId }
        val secret = json.optString("session_secret").ifBlank { record.sessionSecret }
        val expires = json.optString("expires_at").ifBlank { record.expiresAt }
        if (sessionId != record.sessionId || secret != record.sessionSecret || expires != record.expiresAt) {
            saveRecord(record.copy(sessionId = sessionId, sessionSecret = secret, expiresAt = expires))
        }
    }

    // ── Signing ────────────────────────────────────────────────────────────

    private fun request(
        record: MorneyeSignedSessionRecord,
        method: String,
        path: String,
        body: String,
        extraHeaders: Map<String, String>,
    ): MorneyeHttpResponse {
        val url = endpoint(path)
        val upper = method.trim().uppercase().ifEmpty { "GET" }
        val headers = signedHeaders(record, upper, url, body)
            .toMutableMap()
            .apply {
                put("Accept", "application/json")
                if (body.isNotEmpty() || upper in setOf("POST", "PUT", "PATCH")) {
                    put("Content-Type", "application/json")
                }
                put("User-Agent", userAgent())
                // Addon-supplied headers win, matching the reference hosts'
                // "extras after signature" ordering (tickets, etc.).
                putAll(extraHeaders.filterValues { it.isNotBlank() })
            }

        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val requestBody = if (body.isEmpty() && upper in setOf("GET", "HEAD", "DELETE")) {
            null
        } else {
            body.toRequestBody(EMPTY_JSON)
        }
        when (upper) {
            "POST" -> builder.post(requestBody ?: "".toRequestBody(EMPTY_JSON))
            "PUT" -> builder.put(requestBody ?: "".toRequestBody(EMPTY_JSON))
            "PATCH" -> builder.patch(requestBody ?: "".toRequestBody(EMPTY_JSON))
            "DELETE" -> builder.delete(requestBody)
            "HEAD" -> builder.head()
            else -> builder.get()
        }
        val response = execute(builder.build())
        return response
    }

    /**
     * The exact canonical string and headers from the gateway protocol. Kept
     * internal so the test can recompute a signature over a recorded request
     * rather than trusting this implementation against itself.
     */
    internal fun signedHeaders(
        record: MorneyeSignedSessionRecord,
        method: String,
        url: HttpUrl,
        body: String,
        timestampMs: Long = now(),
        nonce: String = randomHex(16),
    ): Map<String, String> {
        val timestamp = ISO_MILLIS.format(Instant.ofEpochMilli(timestampMs).truncatedTo(ChronoUnit.MILLIS))
        val bodyHash = sha256Hex(body.toByteArray(Charsets.UTF_8))
        val window = timestampMs / 1_000L / config.timeWindowSeconds.coerceAtLeast(1L)
        val rolling = hmacBase64Url(
            key = record.sessionSecret.toByteArray(Charsets.UTF_8),
            data = "$window:${record.sessionId}".toByteArray(Charsets.UTF_8),
        )
        val canonical = listOf(
            config.schemeLabel,
            method.uppercase(),
            url.encodedPath,
            "",
            bodyHash,
            timestamp,
            nonce,
            record.sessionId,
            config.appVersion,
            config.platform,
        ).joinToString("\n")
        val signature = hmacBase64Url(
            key = rolling.toByteArray(Charsets.UTF_8),
            data = canonical.toByteArray(Charsets.UTF_8),
        )
        val prefix = config.headerPrefix
        return linkedMapOf(
            "${prefix}Session" to record.sessionId,
            "${prefix}Timestamp" to timestamp,
            "${prefix}Nonce" to nonce,
            "${prefix}Body-SHA256" to bodyHash,
            "${prefix}Signature" to signature,
            "${prefix}App-Version" to config.appVersion,
            "${prefix}Platform" to config.platform,
        )
    }

    // ── Endpoint / callback validation ─────────────────────────────────────

    private fun endpoint(path: String): HttpUrl {
        val base = config.baseUrl.trimEnd('/')
        val url = "$base/${path.trimStart('/')}".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid signed-session endpoint: $path")
        val baseUrl = config.baseUrl.toHttpUrl()
        val basePath = baseUrl.encodedPath.trimEnd('/')
        require(url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port) {
            "Signed-session path escaped its base URL"
        }
        require(basePath.isEmpty() || url.encodedPath.startsWith("$basePath/")) {
            "Signed-session path escaped its base URL"
        }
        return url
    }

    private fun validateAuthUrl(raw: String): HttpUrl {
        val url = raw.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Verification URL is not HTTP(S)")
        require(url.scheme == "https" || url.scheme == "http") {
            "Verification URL is not HTTP(S)"
        }
        return url
    }

    private fun callbackUrl(state: String): String {
        val base = config.callbackUrl.ifBlank { DEFAULT_CALLBACK }
        val uri = runCatching { URI(base) }.getOrNull()
        val sep = if (base.contains('?')) '&' else '?'
        return "$base${sep}cb_version=v2grant&state=${java.net.URLEncoder.encode(state, "UTF-8")}"
            .also {
                if (uri == null) {
                    TrackLog.w(TAG, "Invalid signed-session callback URL: $base")
                }
            }
    }

    private fun stateFrom(url: HttpUrl): String? {
        url.queryParameter("state")?.takeIf { it.isNotBlank() }?.let { return it }
        val nested = url.queryParameter("cb")
            ?: url.queryParameter("callback_url")
            ?: url.queryParameter("redirect_uri")
            ?: return null
        return runCatching {
            nested.toHttpUrlOrNull()?.queryParameter("state")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun extractGrant(raw: String?): String {
        var value = raw?.trim().orEmpty()
        if (value.isEmpty()) return store.get(grantKey).orEmpty().trim()

        // A pasted `grant=...` fragment or a complete callback URL.
        if (!value.contains("://") && value.contains("grant=")) {
            value = "mornye://session-grant?$value"
        }
        if (value.startsWith("mornye://", true) || value.startsWith("spotiflac://", true)) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return ""
            val query = parseQuery(uri.rawQuery)
            val returnedState = query["state"]
            val pending = pending()
            if (
                !returnedState.isNullOrBlank() && pending != null &&
                pending.state.isNotBlank() && returnedState != pending.state
            ) {
                throw IllegalArgumentException("Verification callback state does not match the pending request")
            }
            return query["grant"] ?: query["code"] ?: ""
        }
        // A bare token is accepted only when it looks like a grant rather than
        // an arbitrary sentence a user pasted by mistake.
        return if (!value.contains(Regex("\\s")) && value.length >= 16) value else ""
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = java.net.URLDecoder.decode(part.substring(0, idx), "UTF-8")
            val value = java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            key to value
        }.toMap()
    }

    // ── Persistence ────────────────────────────────────────────────────────

    private fun record(): MorneyeSignedSessionRecord {
        val raw = store.get(recordKey)
        val record = if (raw.isNullOrBlank()) {
            MorneyeSignedSessionRecord(installId = "")
        } else {
            runCatching { MorneyeSignedSessionRecord.fromJson(JSONObject(raw)) }
                .getOrDefault(MorneyeSignedSessionRecord(installId = ""))
        }
        if (record.installId.isBlank()) {
            val created = record.copy(installId = randomHex(16))
            saveRecord(created)
            return created
        }
        return record
    }

    private fun saveRecord(record: MorneyeSignedSessionRecord) {
        store.set(recordKey, record.toJson().toString())
    }

    private fun pending(): MorneyeSignedPending? {
        val raw = store.get(pendingKey)
        if (raw.isNullOrBlank()) return null
        return runCatching { MorneyeSignedPending.fromJson(JSONObject(raw)) }.getOrNull()
    }

    private fun savePending(pending: MorneyeSignedPending) {
        store.set(pendingKey, pending.toJson().toString())
    }

    private fun usable(record: MorneyeSignedSessionRecord): Boolean {
        if (record.sessionId.isBlank() || record.sessionSecret.isBlank()) return false
        val expiry = parseTime(record.expiresAt) ?: return false
        return expiry > now()
    }

    private fun refreshDue(record: MorneyeSignedSessionRecord): Boolean {
        if (config.refreshPath.isBlank()) return false
        val expiry = parseTime(record.expiresAt) ?: return false
        return expiry - now() <= REFRESH_WINDOW_SECONDS * 1_000L
    }

    private fun verification(
        record: MorneyeSignedSessionRecord,
        authUrl: String? = pending()?.url,
    ): JSONObject = JSONObject().apply {
        put("ok", false)
        put("status", 0)
        put("statusCode", 0)
        put("needsVerification", true)
        put("error", "VERIFY_REQUIRED")
        put("auth_url", authUrl.orEmpty())
        put("open_auth_url", authUrl.orEmpty())
        put("install_id", record.installId)
    }

    // ── HTTP plumbing ──────────────────────────────────────────────────────

    private fun execute(request: Request): MorneyeHttpResponse {
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes()
                ?.take(MAX_BODY)
                ?.toByteArray()
                ?: ByteArray(0)
            val headers = response.headers.names().associateWith { name ->
                response.headers.values(name).joinToString(", ")
            }
            return MorneyeHttpResponse(
                status = response.code,
                url = response.request.url.toString(),
                headers = headers,
                body = bytes.toString(Charsets.UTF_8),
            )
        }
    }

    private fun retryAfterSeconds(
        response: MorneyeHttpResponse,
        contract: JSONObject,
    ): Long {
        contract.optLong("retry_after_seconds", 0L).takeIf { it > 0 }?.let { return it }
        val header = response.headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.trim()
            ?: return 0
        return header.toLongOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun MorneyeHttpResponse.toJson(contract: JSONObject): JSONObject = JSONObject().apply {
        put("ok", status in 200..299)
        put("status", status)
        put("statusCode", status)
        put("url", url)
        put("headers", JSONObject().apply {
            headers.forEach { (name, value) -> put(name, value) }
        })
        put("body", body)
        contract.optString("code").takeIf { it.isNotBlank() }?.let {
            put("code", it)
            put("error_code", it)
        }
        contract.optString("origin").takeIf { it.isNotBlank() }?.let { put("error_origin", it) }
        contract.optString("action").takeIf { it.isNotBlank() }?.let { put("error_action", it) }
        if (contract.has("retryable")) put("retryable", contract.optBoolean("retryable"))
        contract.optString("retry_mode").takeIf { it.isNotBlank() }?.let { put("retryMode", it) }
        val retry = contract.optLong("retry_after_seconds", 0L)
        if (retry > 0) {
            put("retry_after_seconds", retry)
            put("retryAfterSeconds", retry)
        }
    }

    private fun userAgent(): String = "Mornye/${config.appVersion}"

    // ── Encoding helpers ───────────────────────────────────────────────────

    private fun randomHex(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return data.joinToString("") { "%02x".format(it) }
    }

    private fun randomBase64Url(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacBase64Url(key: ByteArray, data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data))
    }

    private fun parseTime(value: String): Long? {
        val text = value.trim()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1_000L else it }
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching {
            java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return null
    }
}

private const val MAX_BODY = 32 * 1024 * 1024

internal data class MorneyeHttpResponse(
    val status: Int,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

internal data class MorneyeSignedSessionRecord(
    val installId: String,
    val sessionId: String = "",
    val sessionSecret: String = "",
    val expiresAt: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("install_id", installId)
        put("session_id", sessionId)
        put("session_secret", sessionSecret)
        put("expires_at", expiresAt)
    }

    companion object {
        fun fromJson(json: JSONObject) = MorneyeSignedSessionRecord(
            installId = json.optString("install_id"),
            sessionId = json.optString("session_id"),
            sessionSecret = json.optString("session_secret"),
            expiresAt = json.optString("expires_at"),
        )
    }
}

internal data class MorneyeSignedPending(
    val url: String,
    val state: String,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("state", state)
        put("created_at", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject) = MorneyeSignedPending(
            url = json.optString("url"),
            state = json.optString("state"),
            createdAt = json.optLong("created_at"),
        )
    }
}

/** Key/value storage used by [MorneyeSignedSession]; the app uses encrypted prefs. */
internal interface MorneyeSignedSessionStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/** Manifest `signedSession` block, with the same defaults as the reference hosts. */
internal data class MorneyeSignedSessionConfig(
    val namespace: String,
    val baseUrl: String,
    val appVersion: String,
    val platform: String,
    val schemeLabel: String,
    val headerPrefix: String,
    val timeWindowSeconds: Long,
    val bootstrapPath: String,
    val challengePath: String,
    val exchangePath: String,
    val refreshPath: String,
    val callbackUrl: String,
) {
    companion object {
        fun fromManifest(manifest: JSONObject): MorneyeSignedSessionConfig? {
            val raw = manifest.optJSONObject("signedSession") ?: return null
            val baseUrl = raw.optString("baseUrl").trim().trimEnd('/')
            if (baseUrl.isBlank()) return null
            val endpoints = raw.optJSONObject("endpoints") ?: JSONObject()
            return MorneyeSignedSessionConfig(
                namespace = raw.optString("namespace", "zarz-v2"),
                baseUrl = baseUrl,
                appVersion = raw.optString("appVersion", "ext-1.0"),
                platform = raw.optString("platform", "extension"),
                schemeLabel = raw.optString("schemeLabel", "SPOTIFLAC-HMAC-V1"),
                headerPrefix = raw.optString("headerPrefix", "X-Sig-"),
                timeWindowSeconds = raw.optLong("timeWindowSeconds", 300L).coerceAtLeast(1L),
                bootstrapPath = endpoints.optString("bootstrap", "/bootstrap"),
                challengePath = endpoints.optString("challenge", "/challenge"),
                exchangePath = endpoints.optString("exchange", "/session/exchange"),
                refreshPath = endpoints.optString("refresh", "/session/refresh"),
                callbackUrl = raw.optString("callbackUrl", "mornye://session-grant"),
            )
        }
    }
}
