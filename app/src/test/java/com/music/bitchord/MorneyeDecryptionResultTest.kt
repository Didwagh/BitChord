package com.music.bitchord

import com.music.bitchord.data.sources.mornye.parseMorneyeDownloadResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

/** The Amazon addon sends the CENC key in its download result; keep it. */
class MorneyeDecryptionResultTest {

    @Test
    fun `reads descriptor from Amazon addon`() {
        val json = JSONObject("""
            {"success":true,"file_path":"/tmp/a.m4a",
             "decryption":{"strategy":"ffmpeg.mov_key","key":"00112233445566778899aabbccddeeff",
                           "input_format":"mov","output_extension":".flac"}}
        """.trimIndent())
        val parsed = parseMorneyeDownloadResult(json)
        assertEquals("00112233445566778899aabbccddeeff", parsed.decryptionKey)
        assertEquals("ffmpeg.mov_key", parsed.decryptionStrategy)
    }

    @Test
    fun `falls back to flat decryption_key from older addons`() {
        val json = JSONObject("""{"success":true,"file_path":"/tmp/a.m4a","decryption_key":"abcdef"}""")
        val parsed = parseMorneyeDownloadResult(json)
        assertEquals("abcdef", parsed.decryptionKey)
        assertEquals(null, parsed.decryptionStrategy)
    }

    @Test
    fun `clear files have no key`() {
        val json = JSONObject("""{"success":true,"file_path":"/tmp/a.flac"}""")
        val parsed = parseMorneyeDownloadResult(json)
        assertEquals(null, parsed.decryptionKey)
    }
}
