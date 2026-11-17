package com.music.bitchord

import com.music.bitchord.data.sources.mornye.MorneyeFailureCooldown
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cooldown is what stops a dead TIDAL candidate from being asked again by
 * ExoPlayer's retries and the resolver's fallback. Time is passed in so the
 * test does not sleep for 90 seconds.
 */
class MorneyeFailureCooldownTest {

    private val key = MorneyeFailureCooldown.key("ff9e6812d4", "535662896", "HI_RES_LOSSLESS")

    @After
    fun tearDown() = MorneyeFailureCooldown.clearAll()

    @Test
    fun `a recorded failure is skipped inside the window`() {
        MorneyeFailureCooldown.record(key, now = 1_000L)
        assertTrue(MorneyeFailureCooldown.isCoolingDown(key, now = 1_001L))
        assertTrue(
            MorneyeFailureCooldown.isCoolingDown(
                key,
                now = 1_000L + MorneyeFailureCooldown.WINDOW_MS - 1,
            ),
        )
    }

    @Test
    fun `the candidate is allowed again after the window`() {
        MorneyeFailureCooldown.record(key, now = 1_000L)
        assertFalse(
            MorneyeFailureCooldown.isCoolingDown(
                key,
                now = 1_000L + MorneyeFailureCooldown.WINDOW_MS,
            ),
        )
        // The expired entry is dropped rather than lingering forever.
        assertFalse(MorneyeFailureCooldown.isCoolingDown(key, now = 1_000L))
    }

    @Test
    fun `a success clears the failure`() {
        MorneyeFailureCooldown.record(key)
        MorneyeFailureCooldown.clear(key)
        assertFalse(MorneyeFailureCooldown.isCoolingDown(key))
    }

    @Test
    fun `a different quality tier is a different candidate`() {
        val cd = MorneyeFailureCooldown.key("ff9e6812d4", "535662896", "LOSSLESS")
        MorneyeFailureCooldown.record(key)
        assertFalse(MorneyeFailureCooldown.isCoolingDown(cd))
    }
}
