package com.music.bitchord

import com.music.bitchord.data.sources.MorneyeSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.mornye.MorneyeDownloadResult
import com.music.bitchord.data.sources.mornye.MorneyeQualityOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Quality claims are allowed to be wrong; the bytes are not.
 *
 * Both Deezer and Tidal can describe an MP3/AAC/DASH result as FLAC. This
 * fixture pins the rule that a manifest tier and our own `.flac` cache name
 * never become codec evidence, and that an unknown container is reported as a
 * fallback rather than a successful lossless stream.
 */
class MorneyeQualityTruthTest {

    private val source = MorneyeSource(SourceConfig(id = "cfg", kind = SourceKind.MORNYE))

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("bitchord-quality", ".m4a").also { it.writeBytes(bytes) }

    @Test
    fun `unknown bytes do not become FLAC from the tier or filename`() {
        val file = tempFile(ByteArray(128))
        val tier = MorneyeQualityOption(
            id = "flac",
            label = "FLAC / Hi-Res",
            codec = "flac",
            sampleRateHz = 96_000,
            bitDepth = 24,
            isLossless = true,
        )
        try {
            val stream = source.buildStream(file, tier, StreamRequest.Lossless, parsed = null)
            assertNull(stream.format.codec)
            assertNull(stream.format.isLossless)
            assertNull(stream.format.sampleRateHz)
            assertNull(stream.format.bitDepth)
            assertTrue(stream.belowRequest)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `byte-detected MP3 beats an addon FLAC claim`() {
        val bytes = ByteArray(128)
        "ID3".toByteArray(Charsets.ISO_8859_1).copyInto(bytes)
        val file = tempFile(bytes)
        val claim = MorneyeDownloadResult(
            success = true,
            filePath = file.absolutePath,
            format = "flac",
            errorMessage = null,
            codec = "flac",
            sampleRateHz = 96_000,
            bitDepth = 24,
            quality = "lossless",
        )
        try {
            val stream = source.buildStream(
                file,
                MorneyeQualityOption(id = "flac", label = "FLAC", codec = "flac", isLossless = true),
                StreamRequest.Lossless,
                claim,
            )
            assertEquals("mp3", stream.format.codec)
            assertEquals(false, stream.format.isLossless)
            assertTrue(stream.belowRequest)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `FLAC STREAMINFO wins over an addon codec claim`() {
        // 48 kHz / 24-bit encoded in the same layout MorneyeSource reads.
        val bytes = ByteArray(42)
        "fLaC".toByteArray(Charsets.ISO_8859_1).copyInto(bytes)
        bytes[18] = 0x0B
        bytes[19] = 0xB8.toByte()
        bytes[20] = 0x01
        bytes[21] = 0x70
        val file = tempFile(bytes)
        val claim = MorneyeDownloadResult(
            success = true,
            filePath = file.absolutePath,
            format = "mp3",
            errorMessage = null,
            codec = "aac",
            sampleRateHz = 44_100,
            bitDepth = 16,
        )
        try {
            val stream = source.buildStream(file, null, StreamRequest.Lossless, claim)
            assertEquals("flac", stream.format.codec)
            assertEquals(true, stream.format.isLossless)
            assertEquals(48_000, stream.format.sampleRateHz)
            assertEquals(24, stream.format.bitDepth)
            assertFalse(stream.belowRequest)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `DASH XML is recognised before it can be cached as audio`() {
        val xml = "  <?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>"
            .toByteArray(Charsets.UTF_8)
        assertTrue(source.looksLikeManifestBytes(xml, xml.size))

        val audio = "fLaC".toByteArray(Charsets.ISO_8859_1)
        assertFalse(source.looksLikeManifestBytes(audio, audio.size))
    }
}
