package com.music.bitchord.data.sources.mornye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTP summaries are useful in an exported diagnostics log, but a URL must
 * lose its query and fragment before it is written: signed routes and auth
 * callbacks carry one-use material there.
 */
class MorneyeHostBridgeUrlRedactionTest {

    @Test
    fun `drops query and fragment`() {
        assertEquals(
            "https://api.zarz.moe/v2/dl/tid",
            MorneyeHostBridge.redactedUrl("https://api.zarz.moe/v2/dl/tid?ticket=secret#frag"),
        )
        assertEquals(
            "https://mornye.example/session-grant",
            MorneyeHostBridge.redactedUrl("https://mornye.example/session-grant?state=abc&grant=xyz"),
        )
    }

    @Test
    fun `keeps host and path`() {
        assertEquals(
            "https://na.mesk.skill.music.a2z.com/api/showSearch",
            MorneyeHostBridge.redactedUrl("https://na.mesk.skill.music.a2z.com/api/showSearch"),
        )
    }

    @Test
    fun `caps long paths`() {
        val out = MorneyeHostBridge.redactedUrl("https://x.example/" + "a".repeat(400))
        assertTrue(out.length <= 180)
        assertTrue(out.endsWith("..."))
        assertFalse(out.contains("?"))
    }
}
