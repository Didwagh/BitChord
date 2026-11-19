package com.music.bitchord.playback

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A skipped track's quality search must outlive the skip.
 *
 * Bones was replayed from cache as Opus, and a Qobuz 24-bit FLAC was found two
 * minutes later — while the queue had already moved on. The old job bookkeeping
 * cancelled the search on the transition, so the result never reached the
 * shelf in `swapIn` and the FLAC was simply thrown away. These tests pin the
 * replacement: retiring keeps the answer, promotion avoids a duplicate hunt,
 * and only a ceiling change ends everything.
 */
class UpgradeJobSlotsTest {

    @Test
    fun `retiring a job keeps it alive as stale`() {
        val slots = UpgradeJobSlots()
        val bones = Job()

        slots.start("bones", bones)
        slots.retire()

        assertTrue(bones.isActive)
        assertNull(slots.current)
        assertNull(slots.currentFor)
        assertTrue(slots.hasStaleFor("bones"))
    }

    @Test
    fun `starting another track cancels the oldest stale job but keeps the newer one`() {
        val slots = UpgradeJobSlots(maxStale = 1)
        val first = Job()
        val second = Job()
        val third = Job()

        slots.start("first", first)
        slots.start("second", second)
        assertTrue("first is stale but still alive", first.isActive)
        assertTrue(slots.hasStaleFor("first"))

        slots.start("third", third)
        assertFalse("the bounded set dropped the oldest", first.isActive)
        assertTrue(second.isActive)
        assertTrue(third.isActive)
        assertTrue(slots.hasStaleFor("second"))
        assertEquals("third", slots.currentFor)
    }

    @Test
    fun `a returned track promotes its still-running search instead of starting a duplicate`() {
        val slots = UpgradeJobSlots()
        val bones = Job()
        val enemy = Job()

        slots.start("bones", bones)
        slots.start("enemy", enemy)
        assertFalse(slots.isCurrentFor("bones"))

        slots.retire()
        assertTrue("bones is still searching while Enemy plays", slots.hasStaleFor("bones"))
        assertTrue(slots.promoteStale("bones"))

        assertTrue(slots.isCurrentFor("bones"))
        assertEquals(bones, slots.current)
        assertFalse(slots.hasStaleFor("bones"))
        assertTrue(enemy.isActive)
    }

    @Test
    fun `a stale job that is proving an upgrade is not cancelled to make room`() {
        val slots = UpgradeJobSlots(maxStale = 0, mayCancel = { it != "auditioning" })
        val auditioning = Job()
        val other = Job()

        slots.start("auditioning", auditioning)
        slots.start("other", other)

        assertTrue(auditioning.isActive)
        assertTrue(other.isActive)
        assertTrue(slots.hasStaleFor("auditioning"))
        assertTrue(slots.isCurrentFor("other"))
    }

    @Test
    fun `cancel all ends both the current job and every stale job`() {
        val slots = UpgradeJobSlots()
        val stale = Job()
        val current = Job()

        slots.start("stale", stale)
        slots.start("current", current)
        slots.cancelAll()

        assertFalse(stale.isActive)
        assertFalse(current.isActive)
        assertNull(slots.current)
        assertNull(slots.currentFor)
        assertFalse(slots.hasStaleFor("stale"))
        assertFalse(slots.hasStaleFor("current"))
    }
}
