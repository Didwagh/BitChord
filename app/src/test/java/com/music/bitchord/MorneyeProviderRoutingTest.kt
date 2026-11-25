package com.music.bitchord

import com.music.bitchord.data.sources.mornye.MorneyeProviderHint
import com.music.bitchord.data.sources.mornye.mergeMorneyeTrack
import com.music.bitchord.data.sources.mornye.morneyeAvailabilityOptions
import com.music.bitchord.data.sources.mornye.morneyeCallError
import com.music.bitchord.data.sources.mornye.morneyeProviderForAddon
import com.music.bitchord.data.sources.mornye.morneyeUrlProvider
import com.music.bitchord.data.sources.mornye.morneyeUrlTrack
import com.music.bitchord.data.sources.mornye.morneyeVerificationRequired
import com.music.bitchord.data.sources.mornye.morneyeVerificationResult
import com.music.bitchord.data.sources.mornye.morneyeVerificationText
import com.music.bitchord.data.sources.mornye.parseMorneyeAvailability
import com.music.bitchord.data.sources.mornye.resolveMorneyeAvailability
import com.music.bitchord.data.sources.mornye.selectMorneyeUrlHandler
import com.music.bitchord.data.sources.mornye.shouldRetryMorneyeAvailability
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL routing and availability contracts are exact, case-sensitive and cheap to pin. */
class MorneyeProviderRoutingTest {

    @Test
    fun `recognises only Qobuz URL shapes`() {
        assertEquals(MorneyeProviderHint.QOBUZ, morneyeUrlProvider("https://open.qobuz.com/track/789"))
        assertEquals(MorneyeProviderHint.QOBUZ, morneyeUrlProvider("https://www.qobuz.com/track/789"))
        assertEquals(MorneyeProviderHint.QOBUZ, morneyeUrlProvider("qobuzapp://track/789"))
        assertEquals(MorneyeProviderHint.QOBUZ, morneyeUrlProvider("qobuz:track:789"))
        assertNull(morneyeUrlProvider("Believer Imagine Dragons"))
        assertNull(morneyeUrlProvider("https://example.com/track/qobuz/1"))
        assertNull(morneyeUrlProvider("https://cdn.qobuz.com/track/789"))
        // Tidal and Deezer are no longer routed by this fork.
        assertNull(morneyeUrlProvider("https://listen.tidal.com/track/456"))
        assertNull(morneyeUrlProvider("tidal://track/456"))
        assertNull(morneyeUrlProvider("https://www.deezer.com/track/123"))
        assertNull(morneyeUrlProvider("deezer:track:123"))
    }

    @Test
    fun `maps only Qobuz addon keys and labels to provider hints`() {
        assertEquals(MorneyeProviderHint.QOBUZ, morneyeProviderForAddon("qobuz-web", "Qobuz"))
        assertEquals(MorneyeProviderHint.QOBUZ, morneyeProviderForAddon("qobuz-app-like", ""))
        assertNull(morneyeProviderForAddon("amazon", "Amazon Music"))
        assertNull(morneyeProviderForAddon("tidal-web", "Tidal"))
        assertNull(morneyeProviderForAddon("deezer", "Deezer"))
    }

    @Test
    fun `selects exact exported handler names without normalising case`() {
        assertEquals(
            "handleUrl",
            selectMorneyeUrlHandler(listOf("handleUrl", "handleURL"), MorneyeProviderHint.QOBUZ),
        )
        assertNull(selectMorneyeUrlHandler(listOf("handleURL"), MorneyeProviderHint.QOBUZ))
        assertNull(selectMorneyeUrlHandler(listOf("handleurl"), MorneyeProviderHint.QOBUZ))
    }

    @Test
    fun `nested success false errors are parsed as values`() {
        assertEquals("boom", morneyeCallError(mapOf("success" to false, "error" to "boom")))
        assertEquals(
            "nested boom",
            morneyeCallError(JSONObject("""{"success":false,"error":"nested boom"}""")),
        )
        // A negative availability ticket is an answer, not a thrown call error.
        assertNull(morneyeCallError(mapOf("available" to false, "reason" to "no match")))
    }

    @Test
    fun `verification failures are recognised for user-facing diagnostics`() {
        assertTrue(morneyeVerificationRequired(IllegalStateException("VERIFY_REQUIRED")))
        assertTrue(morneyeVerificationRequired(IllegalStateException("verification_required")))
        // Amazon's addon returns this exact text from download().
        assertTrue(morneyeVerificationRequired(IllegalStateException("Verification required")))
        assertTrue(morneyeVerificationText("Session expired — please re-add Mornye session"))
        assertTrue(morneyeVerificationText("needs verification"))
        assertFalse(morneyeVerificationRequired(IllegalStateException("HTTP 503")))
        assertFalse(morneyeVerificationRequired(null))
    }

    @Test
    fun `structured verification answers are recognised without reading secrets`() {
        assertTrue(morneyeVerificationResult(JSONObject("""{"needsVerification":true,"auth_url":"secret"}""")))
        assertTrue(morneyeVerificationResult(mapOf("success" to false, "error" to "VERIFY_REQUIRED")))
        assertTrue(
            morneyeVerificationResult(
                mapOf("success" to false, "error_code" to "SESSION_EXPIRED"),
            ),
        )
        assertFalse(morneyeVerificationResult(mapOf("success" to false, "error" to "HTTP 503")))
        assertFalse(morneyeVerificationResult(null))
    }

    @Test
    fun `only a track URL result becomes a playable row`() {
        assertNull(morneyeUrlTrack(mapOf("type" to "album", "album" to mapOf("id" to "a"))))
        assertNull(morneyeUrlTrack(mapOf("success" to false, "error" to "bad link")))
        val track = morneyeUrlTrack(
            mapOf("type" to "track", "track" to mapOf("id" to "qobuz:123", "name" to "One")),
        )
        assertNotNull(track)
        assertEquals("qobuz:123", track!!["id"])
    }

    @Test
    fun `availability parses positive ticket with prepared context`() {
        val answer = parseMorneyeAvailability(
            mapOf(
                "available" to true,
                "track_id" to "123",
                "prepared_context" to mapOf("track_id" to "123"),
            ),
        )
        assertTrue(answer.available)
        assertEquals("123", answer.trackId)
        assertNotNull(answer.preparedContext)
        assertFalse(shouldRetryMorneyeAvailability(answer, hasEnrichment = true))

        val negative = parseMorneyeAvailability(mapOf("available" to false, "reason" to "no match"))
        assertFalse(negative.available)
        assertEquals("no match", negative.reason)
        assertTrue(shouldRetryMorneyeAvailability(negative, hasEnrichment = true))
        assertFalse(shouldRetryMorneyeAvailability(negative, hasEnrichment = false))
    }

    @Test
    fun `availability retry order is check enrich check`() = runBlocking {
        val calls = mutableListOf<String>()
        var enriched = false
        val result = resolveMorneyeAvailability(
            check = {
                calls += if (enriched) "check2" else "check1"
                if (enriched) {
                    parseMorneyeAvailability(mapOf("available" to true, "track_id" to "resolved"))
                } else {
                    parseMorneyeAvailability(mapOf("available" to false, "reason" to "unmatched"))
                }
            },
            enrich = {
                calls += "enrich"
                enriched = true
            },
        )
        assertEquals(listOf("check1", "enrich", "check2"), calls)
        assertTrue(result.available)
        assertEquals("resolved", result.trackId)
    }

    @Test
    fun `availability options keep the raw provider id and merge only fills gaps`() {
        val options = morneyeAvailabilityOptions(
            MorneyeProviderHint.QOBUZ,
            mapOf("id" to "qobuz:789", "name" to "Two"),
            "qobuz:789",
            null,
        )
        assertEquals("qobuz:789", options["qobuz_id"])
        assertEquals(0, options["duration_ms"])
        assertEquals("qobuz:789", (options["track"] as Map<*, *>)["id"])
        assertFalse(options.containsKey("deezer_id"))
        assertFalse(options.containsKey("tidal_id"))

        val merged = mergeMorneyeTrack(
            mapOf("id" to "1", "name" to "One"),
            mapOf("name" to "Replacement", "isrc" to "US123", "blank" to "", "count" to 0),
        )
        assertEquals("Replacement", merged["name"])
        assertEquals("US123", merged["isrc"])
        assertFalse(merged.containsKey("blank"))
        assertFalse(merged.containsKey("count"))
    }
}
