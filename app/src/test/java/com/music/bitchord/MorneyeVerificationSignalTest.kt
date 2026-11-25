package com.music.bitchord

import com.music.bitchord.data.sources.mornye.MorneyeVerificationSignal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The prompt is one alert per addon, not one per failed lookup. */
class MorneyeVerificationSignalTest {

    @After
    fun tearDown() = MorneyeVerificationSignal.clearAll()

    @Test
    fun `one event at a time and a separate cooldown per addon`() {
        MorneyeVerificationSignal.report("qobuz-web", "Qobuz")
        MorneyeVerificationSignal.report("amazon", "Amazon Music")

        val first = MorneyeVerificationSignal.event.value
        assertNotNull(first)
        assertEquals("qobuz-web", first!!.addonKey)
        assertEquals("Qobuz", first.displayName)

        MorneyeVerificationSignal.dismiss()
        MorneyeVerificationSignal.report("amazon", "Amazon Music")
        assertEquals("amazon", MorneyeVerificationSignal.event.value?.addonKey)

        MorneyeVerificationSignal.dismiss()
        MorneyeVerificationSignal.report("qobuz-web", "Qobuz")
        assertNull(MorneyeVerificationSignal.event.value)
    }

    @Test
    fun `a confirmed reconnect lets that addon prompt again`() {
        MorneyeVerificationSignal.report("qobuz-web", "Qobuz")
        MorneyeVerificationSignal.dismiss()
        MorneyeVerificationSignal.report("qobuz-web", "Qobuz")
        assertNull(MorneyeVerificationSignal.event.value)

        MorneyeVerificationSignal.clear("qobuz-web")
        MorneyeVerificationSignal.report("qobuz-web", "Qobuz")
        assertEquals("qobuz-web", MorneyeVerificationSignal.event.value?.addonKey)
    }
}
