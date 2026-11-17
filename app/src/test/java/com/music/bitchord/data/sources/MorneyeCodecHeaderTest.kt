package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bytes decide the codec, not the addon's claim and not the file extension.
 *
 * A lossless stream wrapped in MP4 hides its sample entry past the first few
 * dozen bytes (`dfLa` for FLAC-in-ISO, `alac` for ALAC), so a 64-byte header
 * sniff calls those files AAC. That mislabel is what made a 44 MB Amazon file
 * report `codec=aac` while the player found no selected audio track and stayed
 * silent. These cases pin the scan range and the order of the checks.
 */
class MorneyeCodecHeaderTest {

    private val source = MorneyeSource(SourceConfig(id = "cfg", kind = SourceKind.MORNYE))

    private fun head(vararg marker: Pair<Int, String>): ByteArray {
        val bytes = ByteArray(HEAD_SCAN)
        marker.forEach { (offset, text) ->
            text.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, offset)
        }
        return bytes
    }

    private fun check(bytes: ByteArray, expected: String?) =
        assertEquals(expected, source.codecFromHeader(bytes, bytes.size))

    @Test
    fun `flac in mp4 is not called aac`() {
        // ftyp brand says M4A, but the audio sample entry is FLAC.
        check(head(4 to "ftypM4A ", 40_000 to "dfLa"), "flac")
    }

    @Test
    fun `alac in mp4 is not called aac`() {
        check(head(4 to "ftypM4A ", 40_000 to "alac"), "alac")
    }

    @Test
    fun `plain mp4 aac stays aac`() {
        check(head(4 to "ftypM4A ", 40_000 to "mp4a"), "aac")
    }

    @Test
    fun `raw flac is flac`() {
        check(head(0 to "fLaC"), "flac")
    }

    @Test
    fun `ogg opus is opus`() {
        check(head(0 to "OggS", 30 to "OpusHead"), "opus")
    }

    @Test
    fun `ogg vorbis is vorbis`() {
        check(head(0 to "OggS", 30 to "vorbis"), "vorbis")
    }

    @Test
    fun `short read is unknown`() {
        assertEquals(null, source.codecFromHeader(ByteArray(HEAD_SCAN), 2))
    }

    private companion object {
        const val HEAD_SCAN = 65_536
    }
}
