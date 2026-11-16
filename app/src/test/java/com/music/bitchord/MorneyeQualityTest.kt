package com.music.bitchord

import com.music.bitchord.data.sources.mornye.bitDepthFromLabel
import com.music.bitchord.data.sources.mornye.bitDepthOf
import com.music.bitchord.data.sources.mornye.isAtmosClaim
import com.music.bitchord.data.sources.mornye.isLosslessClaim
import com.music.bitchord.data.sources.mornye.isLosslessCodec
import com.music.bitchord.data.sources.mornye.kbpsFromLabel
import com.music.bitchord.data.sources.mornye.kbpsOf
import com.music.bitchord.data.sources.mornye.normaliseCodec
import com.music.bitchord.data.sources.mornye.sampleRateFromLabel
import com.music.bitchord.data.sources.mornye.sampleRateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Mornye addon says a tier is, and what this app is allowed to repeat.
 *
 * The failure this guards against is the one that makes a lossy file play under
 * a "FLAC" label: reading a number out of a human label and calling it a
 * bitrate, or treating an option's position in the manifest as a quality rank.
 * Every assertion here is about not inventing a fact.
 */
class MorneyeQualityTest {

    @Test
    fun `codec names fold onto the ones the player knows`() {
        assertEquals("flac", normaliseCodec(".FLAC"))
        assertEquals("alac", normaliseCodec("Apple Lossless"))
        assertEquals("aac", normaliseCodec("m4a"))
        assertEquals("wav", normaliseCodec("pcm"))
        assertEquals("eac3-joc", normaliseCodec("Dolby Atmos"))
        assertEquals("opus", normaliseCodec("opus"))
    }

    @Test
    fun `lossless is decided on the codec, not the tier's name`() {
        assertTrue(isLosslessCodec("flac"))
        assertTrue(isLosslessCodec("alac"))
        assertFalse(isLosslessCodec("opus"))
        assertFalse(isLosslessCodec(null))
        // A label may claim it; a codec may not.
        assertTrue(isLosslessClaim("flac", null))
        assertTrue(isLosslessClaim(null, "Hi-Res Lossless"))
        assertFalse(isLosslessClaim("opus", "320 kbps"))
        assertFalse(isLosslessClaim(null, null))
    }

    @Test
    fun `atmos is a claim about the codec and the label`() {
        assertTrue(isAtmosClaim("eac3-joc", null))
        assertTrue(isAtmosClaim(null, "Dolby Atmos"))
        assertFalse(isAtmosClaim("flac", "Lossless"))
    }

    @Test
    fun `a bitrate is only read when the label carries its unit`() {
        assertEquals(320, kbpsFromLabel("320 kbps"))
        assertEquals(320, kbpsFromLabel("MP3 320 kbps"))
        // `24-bit / 96 kHz FLAC` has two numbers, neither of them a bitrate.
        assertNull(kbpsFromLabel("24-bit / 96 kHz FLAC"))
        assertNull(kbpsFromLabel("MP3 320"))
        assertNull(kbpsFromLabel("FLAC"))
    }

    @Test
    fun `sample rate understands kilohertz and the bits-slash-rate shorthand`() {
        assertEquals(44_100, sampleRateFromLabel("44.1 kHz"))
        assertEquals(96_000, sampleRateFromLabel("96kHz"))
        assertEquals(96_000, sampleRateFromLabel("24-bit / 96 kHz FLAC"))
        assertEquals(96_000, sampleRateFromLabel("24/96"))
        assertEquals(44_100, sampleRateFromLabel("16/44.1"))
        assertNull(sampleRateFromLabel("FLAC"))
    }

    @Test
    fun `bit depth is only read when the label says bit`() {
        assertEquals(24, bitDepthFromLabel("24-bit"))
        assertEquals(16, bitDepthFromLabel("CD 16 bit"))
        assertNull(bitDepthFromLabel("320 kbps"))
        assertNull(bitDepthFromLabel("44.1 kHz"))
    }

    @Test
    fun `numbers from a manifest are read as the units they name`() {
        assertEquals(320, kbpsOf(320))
        assertEquals(320, kbpsOf("320kbps"))
        assertEquals(256, kbpsOf("AAC 256 kb/s"))
        assertNull(kbpsOf("lossless"))

        assertEquals(44_100, sampleRateOf(44_100))
        assertEquals(44_100, sampleRateOf("44.1"))
        assertEquals(96_000, sampleRateOf("96 kHz"))
        assertNull(sampleRateOf("CD"))

        assertEquals(24, bitDepthOf(24))
        assertEquals(24, bitDepthOf("24-bit"))
        assertNull(bitDepthOf("x"))
    }
}
