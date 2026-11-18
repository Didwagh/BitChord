package com.music.bitchord

import com.music.bitchord.data.sources.mornye.bitDepthFromLabel
import com.music.bitchord.data.sources.mornye.bitDepthOf
import com.music.bitchord.data.sources.mornye.capabilitiesOf
import com.music.bitchord.data.sources.mornye.isAtmosClaim
import com.music.bitchord.data.sources.mornye.isLosslessClaim
import com.music.bitchord.data.sources.mornye.isLosslessCodec
import com.music.bitchord.data.sources.mornye.kbpsFromLabel
import com.music.bitchord.data.sources.mornye.kbpsOf
import com.music.bitchord.data.sources.mornye.normaliseCodec
import com.music.bitchord.data.sources.mornye.parseMorneyeTrack
import com.music.bitchord.data.sources.mornye.sampleRateFromLabel
import com.music.bitchord.data.sources.mornye.sampleRateOf
import org.json.JSONObject
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
    fun `a lossless family is read from the manifest kind`() {
        val caps = capabilitiesOf(
            mapOf(
                "type" to listOf("metadata_provider", "download_provider"),
                "qualityOptions" to listOf(
                    mapOf("id" to "best", "label" to "FLAC Best Available", "kind" to "lossless"),
                    mapOf("id" to "opus", "label" to "Opus 320kbps", "kind" to "lossy"),
                    mapOf("id" to "eac3", "label" to "Dolby Digital Plus", "kind" to "spatial"),
                ),
            ),
        )
        assertEquals(listOf(true, false, false), caps.qualityOptions.map { it.isLossless })
        assertEquals(320, caps.qualityOptions[1].kbps)
        // Searchable and streamable: both flags are true only because the
        // manifest names `download_provider`.
        assertTrue(caps.searches)
        assertTrue(caps.downloads)
    }

    @Test
    fun `a manifest read out of JSONObject still exposes its quality tiers`() {
        // This is the shape the package manager actually hands over: opt()
        // returns JSONArray/JSONObject, not List/Map. Asserting only the
        // Kotlin-built map above is what let production ask every addon for
        // its "default" tier while the tests said it ranked them.
        val manifest = JSONObject(
            """
            {
              "type": ["metadata_provider", "download_provider"],
              "qualityOptions": [
                {"id": "best", "label": "FLAC Best Available", "kind": "lossless"},
                {"id": "opus", "label": "Opus 320kbps", "kind": "lossy"}
              ]
            }
            """.trimIndent(),
        )
        val caps = capabilitiesOf(manifest.keys().asSequence().associateWith { manifest.opt(it) })

        assertTrue(caps.searches)
        assertTrue(caps.downloads)
        assertEquals(listOf("best", "opus"), caps.qualityOptions.map { it.id })
        assertTrue(caps.qualityOptions[0].isLossless)
        assertEquals(320, caps.qualityOptions[1].kbps)
    }

    @Test
    fun `a metadata-only addon is not reported as able to download`() {
        val caps = capabilitiesOf(
            mapOf(
                "type" to listOf("metadata_provider", "lyrics_provider"),
                "qualityOptions" to emptyList<Map<String, Any?>>(),
            ),
        )
        assertTrue(caps.searches)
        assertFalse(caps.downloads)
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

    @Test
    fun `manifest download policy and feature gaps are reported`() {
        val caps = capabilitiesOf(
            mapOf(
                "type" to listOf("metadata_provider", "download_provider"),
                "allowDownloads" to 0,
                "checkValidLossless" to "true",
                "requiredRuntimeFeatures" to listOf("signedSession@3", "deferredPreview@1"),
            ),
        )
        assertFalse(caps.allowDownloads)
        assertTrue(caps.checkValidLossless)
        assertEquals(listOf("deferredPreview@1"), caps.unsupportedFeatures)
    }

    @Test
    fun `a plain FLAC row is not hi-res and Tidal Atmos mode is explicit`() {
        val flac = parseMorneyeTrack(
            mapOf("id" to "1", "name" to "One", "audio_quality" to "FLAC"),
        )!!
        assertTrue(flac.isLossless)
        assertFalse(flac.isHiRes)
        assertFalse(flac.isAtmos)

        val atmos = parseMorneyeTrack(
            mapOf(
                "id" to "2",
                "name" to "Two",
                "audio_quality" to "HI_RES_LOSSLESS",
                "audio_modes" to listOf("DOLBY_ATMOS"),
            ),
        )!!
        assertTrue(atmos.isLossless)
        assertTrue(atmos.isAtmos)
        // An immersive mix must not inherit a hi-res PCM badge from its label.
        assertFalse(atmos.isHiRes)
    }
}
