package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.playback.audio.LosslessOutput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * AAC earbuds must not shut Mornye out. The FLAC bytes are still real even
 * when Android re-encodes them for the output, and the source layer labels
 * them from the bytes it actually received.
 */
class MorneyeLosslessGateTest {

    @Test
    fun `mornye stays eligible on a non lossless output while legacy addons do not`() {
        // JUnit runs with the default no-output state. If a device-backed test
        // host has already published a capable output, the gate is not under
        // test here.
        assumeFalse("Lossless output is already capable", LosslessOutput.capable)

        val mornye = SourceConfig(
            kind = SourceKind.MORNYE,
            baseUrl = "qobuz-web",
            checkValidLossless = true,
        )
        val legacyAddon = SourceConfig(
            kind = SourceKind.ADDON,
            baseUrl = "https://example.invalid/addon",
            checkValidLossless = true,
        )

        assertTrue(mornye.awaitsLosslessOutput)
        assertFalse(mornye.blocksPlayback)
        assertTrue(legacyAddon.awaitsLosslessOutput)
        assertTrue(legacyAddon.blocksPlayback)
    }
}
