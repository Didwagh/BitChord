package com.music.bitchord

import com.music.bitchord.data.TrackLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file mirror polls [TrackLog.revision] instead of rewriting on a timer.
 * These guard the one contract it relies on: the number moves whenever the
 * buffer changes, including when it is cleared.
 */
class TrackLogRevisionTest {

    @Test
    fun `recording a line advances the revision`() {
        val before = TrackLog.revision
        TrackLog.d("TEST", "revision probe ${System.nanoTime()}")
        assertTrue(TrackLog.revision > before)
    }

    @Test
    fun `clearing the log advances the revision so the mirror rewrites`() {
        TrackLog.d("TEST", "seed ${System.nanoTime()}")
        val before = TrackLog.revision
        TrackLog.clear()
        assertEquals(before + 1, TrackLog.revision)
    }
}
