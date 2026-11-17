package com.music.bitchord

import com.music.bitchord.data.sources.mornye.redactAddonLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorneyeLogRedactionTest {

    @Test
    fun `keeps the diagnostic detail`() {
        val line = "[Amazon] Search returned 0 results for believer"
        assertEquals(line, redactAddonLog(line))
    }

    @Test
    fun `redacts token assignments`() {
        val out = redactAddonLog("""access_token="BQDlgkp7OqP5cvpXt55lA6eqm" ok""")
        assertFalse(out.contains("BQDlgkp7"))
        assertTrue(out.contains("access_token="))
        assertTrue(out.contains("<redacted>"))
        assertTrue(out.endsWith(" ok"))
    }

    @Test
    fun `redacts bearer headers and signed headers`() {
        assertFalse(redactAddonLog("Authorization: Bearer abcdefghijklmnop").contains("abcdefghijklmnop"))
        assertFalse(redactAddonLog("X-Zarz-Signature: 7syqxcS4JIwml4d1HHdsdZGzjvDLRGfVtlEb5d5jjyI").contains("7syqxcS4"))
    }

    @Test
    fun `does not swallow ordinary URLs or status codes`() {
        val line = "GET https://api.deezer.com/search?q=believer -> HTTP 200"
        assertEquals(line, redactAddonLog(line))
    }
}
