package com.music.bitchord

import com.music.bitchord.data.sources.mornye.MorneyeSignedSession
import com.music.bitchord.data.sources.mornye.MorneyeSignedSessionConfig
import com.music.bitchord.data.sources.mornye.MorneyeSignedSessionRecord
import com.music.bitchord.data.sources.mornye.MorneyeSignedSessionStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wire-level tests for the signed-session client.
 *
 * The gateway is a real HTTP server (MockWebServer), so these exercise the
 * request headers, the bootstrap/challenge/exchange flow and the retry paths
 * the addons depend on. The signature is recomputed in the test from the
 * recorded request rather than calling the production helper, so a wrong
 * canonical string cannot pass by comparing the implementation to itself.
 */
class MorneyeSignedSessionTest {

    private lateinit var server: MockWebServer
    private lateinit var store: MapStore
    private var now: Long = 1_794_830_400_000L // 2026-11-16T12:00:00Z

    private val client = OkHttpClient()

    private val config get() = MorneyeSignedSessionConfig(
        namespace = "zarz-v2",
        baseUrl = server.url("/v2").toString().trimEnd('/'),
        appVersion = "tidal-web@1.2.14",
        platform = "extension",
        schemeLabel = "ZARZ-HMAC-V1",
        headerPrefix = "X-Zarz-",
        timeWindowSeconds = 300,
        bootstrapPath = "/bootstrap",
        challengePath = "/challenge",
        exchangePath = "/session/exchange",
        refreshPath = "/session/refresh",
        callbackUrl = "mornye://session-grant",
    )

    private fun session() = MorneyeSignedSession(config, store, client) { now }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = MapStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Signature ─────────────────────────────────────────────────────────

    @Test
    fun `signature matches the reference vector`() {
        val record = MorneyeSignedSessionRecord(
            installId = "install",
            sessionId = "sess1",
            sessionSecret = "s3cr3t",
            expiresAt = iso(now + 3_600_000),
        )
        val url = "https://api.zarz.moe/v2/tickets".toHttpUrl()
        val headers = session().signedHeaders(
            record = record,
            method = "POST",
            url = url,
            body = """{"a":1}""",
            timestampMs = 1_794_830_400_000L,
            nonce = "abc123",
        )
        assertEquals("sess1", headers["X-Zarz-Session"])
        assertEquals("2026-11-16T12:00:00.000Z", headers["X-Zarz-Timestamp"])
        assertEquals(
            "015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862",
            headers["X-Zarz-Body-SHA256"],
        )
        assertEquals("7syqxcS4JIwml4d1HHdsdZGzjvDLRGfVtlEb5d5jjyI", headers["X-Zarz-Signature"])
    }

    @Test
    fun `signed fetch sends a verifiable signature`() {
        val record = MorneyeSignedSessionRecord(
            installId = "install",
            sessionId = "sess1",
            sessionSecret = "s3cr3t",
            expiresAt = iso(now + 3_600_000),
        )
        store.put("signed_session:" + namespace(), record.toJson().toString())
        var seen: RecordedRequest? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen = request
                return json(200, """{"ticket_id":"t1"}""")
            }
        }

        val response = session().signedFetch("POST", "/tickets", """{"a":1}""", emptyMap())

        assertEquals(200, response.getInt("statusCode"))
        assertEquals("t1", JSONObject(response.getString("body")).getString("ticket_id"))
        val request = requireNotNull(seen)
        assertEquals("/v2/tickets", request.path)
        verifySignature(request)
    }

    // ── Challenge / exchange ──────────────────────────────────────────────

    @Test
    fun `challenge is exchanged for a session and then reused`() {
        val mutable = MutableNow(now)
        var exchangeBody: JSONObject? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.startsWith("/v2/bootstrap") == true ->
                        json(200, """{"challenge_id":"chl_1","server_nonce":"n","expires_in":300}""")
                    request.path == "/v2/session/exchange" -> {
                        exchangeBody = JSONObject(request.body.readUtf8())
                        json(200, sessionJson("sess2", "secret2", now + 7_200_000))
                    }
                    request.path == "/v2/tickets" ->
                        json(200, """{"ticket_id":"t2"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = MorneyeSignedSession(config, store, client) { mutable.value }
        // The install id is the app's stable identity with the gateway; the
        // exchange must echo the one already stored, not mint a new one.
        store.put(
            "signed_session:" + namespace(),
            MorneyeSignedSessionRecord(installId = "install").toJson().toString(),
        )
        val first = client.signedFetch("POST", "/tickets", """{"a":1}""", emptyMap())
        assertTrue(first.optBoolean("needsVerification"))
        assertEquals("VERIFY_REQUIRED", first.getString("error"))
        val authUrl = first.getString("auth_url")
        assertTrue("authUrl=$authUrl", authUrl.contains("/v2/challenge"))
        assertTrue("authUrl=$authUrl", authUrl.contains("id=chl_1"))
        // The callback template lives inside the `cb` parameter; its
        // cb_version/state are URL-encoded, so decode before asserting.
        val callbackTemplate = query(authUrl, "cb")
        assertNotNull("authUrl=$authUrl", callbackTemplate)
        assertTrue("cb=$callbackTemplate", callbackTemplate!!.contains("cb_version=v2grant"))
        assertTrue("cb=$callbackTemplate", callbackTemplate.contains("state="))
        val state = query(authUrl, "state")
        assertNotNull(state)

        val callback = "mornye://session-grant?cb_version=v2grant&state=$state&grant=grant-123"
        val result = client.completeGrant(callback)
        assertTrue(result.optBoolean("success"))
        assertEquals("grant-123", exchangeBody?.getString("grant"))
        assertEquals("install", exchangeBody?.getString("install_id"))
        assertEquals("tidal-web@1.2.14", exchangeBody?.getString("app_version"))

        assertTrue(client.status().optBoolean("authenticated"))
        val second = client.signedFetch("POST", "/tickets", """{"a":1}""", emptyMap())
        assertEquals(200, second.getInt("statusCode"))
        // No second bootstrap: the stored session is enough.
        assertFalse(second.optBoolean("needsVerification"))
    }

    @Test
    fun `callback with a different state is rejected without an exchange`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when {
                    request.path?.startsWith("/v2/bootstrap") == true ->
                        json(200, """{"challenge_id":"chl_1","expires_in":300}""")
                    request.path == "/v2/session/exchange" ->
                        json(200, sessionJson("sess2", "secret2", now + 7_200_000))
                    else -> MockResponse().setResponseCode(404)
                }
        }
        val client = session()
        client.signedFetch("GET", "/tickets", "", emptyMap())
        val result = client.completeGrant(
            "mornye://session-grant?cb_version=v2grant&state=wrong&grant=grant-123",
        )
        assertFalse(result.optBoolean("success"))
        assertTrue(result.getString("error").contains("state"))
        // Only bootstrap was requested.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `bare grant stored by a deep link completes through addon call`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when {
                    request.path?.startsWith("/v2/bootstrap") == true ->
                        json(200, """{"challenge_id":"chl_1","expires_in":300}""")
                    request.path == "/v2/session/exchange" ->
                        json(200, sessionJson("sess3", "secret3", now + 7_200_000))
                    else -> MockResponse().setResponseCode(404)
                }
        }
        val client = session()
        client.signedFetch("GET", "/tickets", "", emptyMap())
        client.storePendingGrant("grant-from-callback")
        val result = client.completeGrant()
        assertTrue(result.optBoolean("success"))
        assertTrue(client.status().optBoolean("authenticated"))
    }

    // ── Existing sessions / retries ───────────────────────────────────────

    @Test
    fun `session invalid response bootstraps a new session and retries`() {
        var bootstraps = 0
        var tickets = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.startsWith("/v2/bootstrap") == true -> {
                        bootstraps++
                        json(
                            200,
                            sessionJson("sess$bootstraps", "secret$bootstraps", now + 7_200_000),
                        )
                    }
                    request.path == "/v2/tickets" -> {
                        tickets++
                        if (tickets == 1) {
                            json(
                                401,
                                """{"error":"invalid session","code":"SESSION_INVALID","origin":"gateway","action":"bootstrap_session"}""",
                            )
                        } else {
                            json(200, """{"ticket_id":"ok"}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val record = MorneyeSignedSessionRecord(
            installId = "install",
            sessionId = "old",
            sessionSecret = "oldsecret",
            expiresAt = iso(now + 7_200_000),
        )
        store.put("signed_session:" + namespace(), record.toJson().toString())

        val response = session().signedFetch("POST", "/tickets", "{}", emptyMap())

        assertEquals(200, response.getInt("statusCode"))
        assertEquals(1, bootstraps)
        assertEquals(2, tickets)
    }

    @Test
    fun `an expired gateway session bootstraps a new session and retries`() {
        var bootstraps = 0
        var tickets = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.startsWith("/v2/bootstrap") == true -> {
                        bootstraps++
                        json(200, sessionJson("sess$bootstraps", "secret$bootstraps", now + 7_200_000))
                    }
                    request.path == "/v2/tickets" -> {
                        tickets++
                        if (tickets == 1) {
                            json(
                                401,
                                """{"error":"session expired","code":"SESSION_EXPIRED","origin":"gateway","action":"bootstrap_session"}""",
                            )
                        } else {
                            json(200, """{"ticket_id":"ok"}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val record = MorneyeSignedSessionRecord(
            installId = "install",
            sessionId = "old",
            sessionSecret = "oldsecret",
            expiresAt = iso(now + 7_200_000),
        )
        store.put("signed_session:" + namespace(), record.toJson().toString())

        val response = session().signedFetch("POST", "/tickets", "{}", emptyMap())

        assertEquals(200, response.getInt("statusCode"))
        assertEquals(1, bootstraps)
        assertEquals(2, tickets)
    }

    @Test
    fun `a gateway verification challenge is forwarded to the addon`() {
        var bootstraps = 0
        var tickets = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.startsWith("/v2/bootstrap") == true -> {
                        bootstraps++
                        json(200, """{"challenge_id":"chl_$bootstraps","server_nonce":"n","expires_in":300}""")
                    }
                    request.path == "/v2/tickets" -> {
                        tickets++
                        json(
                            403,
                            """{"error":"verification required","code":"VERIFY_REQUIRED","origin":"gateway"}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val record = MorneyeSignedSessionRecord(
            installId = "install",
            sessionId = "old",
            sessionSecret = "oldsecret",
            expiresAt = iso(now + 7_200_000),
        )
        store.put("signed_session:" + namespace(), record.toJson().toString())

        val response = session().signedFetch("POST", "/tickets", "{}", emptyMap())

        assertTrue(response.optBoolean("needsVerification"))
        assertEquals("VERIFY_REQUIRED", response.optString("error"))
        assertEquals(1, bootstraps)
        assertEquals(1, tickets)
    }

    @Test
    fun `a provider auth failure does not reset the Mornye session`() {
        var bootstraps = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/v2/bootstrap") == true -> {
                    bootstraps++
                    json(200, sessionJson("sess", "secret", now + 7_200_000))
                }
                request.path == "/v2/tickets" -> json(
                    401,
                    """{"error":"login expired","code":"SESSION_EXPIRED","origin":"provider"}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val record = MorneyeSignedSessionRecord(
            installId = "install",
            sessionId = "old",
            sessionSecret = "oldsecret",
            expiresAt = iso(now + 7_200_000),
        )
        store.put("signed_session:" + namespace(), record.toJson().toString())

        val response = session().signedFetch("POST", "/tickets", "{}", emptyMap())

        assertEquals(401, response.getInt("statusCode"))
        assertEquals("provider", response.optString("error_origin"))
        assertEquals(0, bootstraps)
    }

    @Test
    fun `provider unavailable retries the same operation`() {
        var tickets = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                tickets++
                return if (tickets == 1) {
                    json(
                        503,
                        """{"error":"provider down","code":"PROVIDER_UNAVAILABLE","origin":"provider","retryable":true,"retry_mode":"same_operation","retry_after_seconds":1}""",
                    )
                } else {
                    json(200, """{"ticket_id":"ok"}""")
                }
            }
        }
        val record = MorneyeSignedSessionRecord(
            installId = "install",
            sessionId = "sess1",
            sessionSecret = "secret",
            expiresAt = iso(now + 7_200_000),
        )
        store.put("signed_session:" + namespace(), record.toJson().toString())

        // One second of retry delay is part of the contract; keep the test
        // responsive by allowing it (MockWebServer is local, the delay is the
        // only waiting).
        val response = session().signedFetch("POST", "/tickets", "{}", emptyMap())
        assertEquals(200, response.getInt("statusCode"))
        assertEquals(2, tickets)
    }

    @Test
    fun `session close to expiry refreshes before signing`() {
        var refreshBody: JSONObject? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/v2/session/refresh" -> {
                        refreshBody = JSONObject(request.body.readUtf8())
                        json(200, sessionJson("fresh", "freshsecret", now + 7_200_000))
                    }
                    "/v2/tickets" -> {
                        // The refreshed session id must be the one used here.
                        assertEquals("fresh", request.getHeader("X-Zarz-Session"))
                        json(200, """{"ticket_id":"ok"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val record = MorneyeSignedSessionRecord(
            installId = "install",
            sessionId = "old",
            sessionSecret = "oldsecret",
            expiresAt = iso(now + 600_000), // 10 minutes: inside the refresh window
        )
        store.put("signed_session:" + namespace(), record.toJson().toString())

        val response = session().signedFetch("GET", "/tickets", "", emptyMap())
        assertEquals(200, response.getInt("statusCode"))
        assertEquals("install", refreshBody?.getString("install_id"))
    }

    @Test
    fun `status reports not connected and pending verification`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                json(200, """{"challenge_id":"chl_1","expires_in":300}""")
        }
        val client = session()
        val before = client.status()
        assertFalse(before.optBoolean("authenticated"))
        assertFalse(before.optBoolean("verification_required"))

        val url = client.begin()
        assertNotNull(url)
        val after = client.status()
        assertTrue(after.optBoolean("verification_required"))
        assertTrue(after.getString("auth_url").contains("id=chl_1"))
        assertEquals(url, client.begin()) // reuses the live challenge
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `clear keeps the install id`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                json(200, sessionJson("sess1", "secret1", now + 7_200_000))
        }
        val client = session()
        client.signedFetch("GET", "/tickets", "", emptyMap())
        val installId = client.status().getString("install_id")
        client.clear()
        val status = client.status()
        assertFalse(status.optBoolean("authenticated"))
        assertEquals(installId, status.getString("install_id"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun verifySignature(request: RecordedRequest) {
        val sessionId = request.getHeader("X-Zarz-Session")!!
        val timestamp = request.getHeader("X-Zarz-Timestamp")!!
        val nonce = request.getHeader("X-Zarz-Nonce")!!
        val bodyHash = request.getHeader("X-Zarz-Body-SHA256")!!
        val signature = request.getHeader("X-Zarz-Signature")!!
        val body = request.body.readUtf8()
        assertEquals(sha256Hex(body), bodyHash)

        val instant = Instant.parse(timestamp)
        val window = instant.toEpochMilli() / 1000 / 300
        val rolling = hmacB64("s3cr3t".toByteArray(), "$window:$sessionId".toByteArray())
        val canonical = listOf(
            "ZARZ-HMAC-V1",
            request.method!!,
            "/v2/tickets",
            "",
            bodyHash,
            timestamp,
            nonce,
            sessionId,
            "tidal-web@1.2.14",
            "extension",
        ).joinToString("\n")
        assertEquals(hmacB64(rolling.toByteArray(), canonical.toByteArray()), signature)
    }

    private fun sessionJson(id: String, secret: String, expiresMs: Long): String =
        JSONObject().apply {
            put("session_id", id)
            put("session_secret", secret)
            put("expires_at", iso(expiresMs))
        }.toString()

    private fun json(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun namespace(): String {
        val data = listOf(
            "zarz-v2",
            server.url("/v2").toString().trimEnd('/'),
            "tidal-web@1.2.14",
            "extension",
        ).joinToString("\n")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun query(url: String, key: String): String? =
        url.substringAfter('?', "")
            .split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == key }
            ?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun hmacB64(key: ByteArray, value: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value))
    }

    private class MapStore : MorneyeSignedSessionStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }
        fun put(key: String, value: String) {
            values[key] = value
        }
    }

    private class MutableNow(var value: Long)
}
