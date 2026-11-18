package com.music.bitchord

import com.music.bitchord.data.sources.mornye.MorneyeProviderHint
import com.music.bitchord.data.sources.mornye.mergeMorneyeTrack
import com.music.bitchord.data.sources.mornye.morneyeAvailabilityOptions
import com.music.bitchord.data.sources.mornye.morneyeCallError
import com.music.bitchord.data.sources.mornye.morneyeUrlProvider
import com.music.bitchord.data.sources.mornye.morneyeUrlTrack
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
    fun `recognises only known Deezer and Tidal URL shapes`() {
        assertEquals(MorneyeProviderHint.DEEZER, morneyeUrlProvider("https://www.deezer.com/track/123"))
        assertEquals(MorneyeProviderHint.DEEZER, morneyeUrlProvider("https://deezer.page.link/abc?x=1"))
        assertEquals(MorneyeProviderHint.DEEZER, morneyeUrlProvider("deezer:track:123"))
        assertEquals(MorneyeProviderHint.TIDAL, morneyeUrlProvider("https://listen.tidal.com/track/456"))
        assertEquals(MorneyeProviderHint.TIDAL, morneyeUrlProvider("tidal://track/456"))
        assertNull(morneyeUrlProvider("Believer Imagine Dragons"))
        assertNull(morneyeUrlProvider("https://example.com/track/deezer/1"))
    }

    @Test
    fun `selects exact exported handler names without normalising case`() {
        assertEquals(
            "handleURL",
            selectMorneyeUrlHandler(listOf("handleUrl", "handleURL"), MorneyeProviderHint.DEEZER),
        )
        assertEquals(
            "handleUrl",
            selectMorneyeUrlHandler(listOf("handleUrl"), MorneyeProviderHint.DEEZER),
        )
        assertEquals(
            "handleUrl",
            selectMorneyeUrlHandler(listOf("handleUrl"), MorneyeProviderHint.TIDAL),
        )
        assertNull(selectMorneyeUrlHandler(listOf("handleurl"), MorneyeProviderHint.DEEZER))
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
    fun `only a track URL result becomes a playable row`() {
        assertNull(morneyeUrlTrack(mapOf("type" to "album", "album" to mapOf("id" to "a"))))
        assertNull(morneyeUrlTrack(mapOf("success" to false, "error" to "bad link")))
        val track = morneyeUrlTrack(
            mapOf("type" to "track", "track" to mapOf("id" to "deezer:123", "name" to "One")),
        )
        assertNotNull(track)
        assertEquals("deezer:123", track!!["id"])
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
            MorneyeProviderHint.DEEZER,
            mapOf("id" to "deezer:123", "name" to "One"),
            "deezer:123",
            210_000,
        )
        assertEquals("deezer:123", options["deezer_id"])
        assertEquals(210_000, options["duration_ms"])
        assertEquals("deezer:123", (options["track"] as Map<*, *>)["id"])

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
