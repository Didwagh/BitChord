package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Pure helpers behind ClearKey provisioning: no CDM is needed to test these. */
class ClearKeyDrmSessionManagerTest {

    @Test
    fun `hexToBytes accepts the 32 hex characters the Amazon addon returns`() {
        val bytes = hexToBytes("00112233445566778899aabbccddeeff")
        assertEquals(16, bytes?.size)
        assertEquals(0x00.toByte(), bytes!![0])
        assertEquals(0xff.toByte(), bytes[15])
        assertEquals(16, hexToBytes("0x00112233445566778899AABBCCDDEEFF")?.size)
        assertNull(hexToBytes("not-hex"))
        assertNull(hexToBytes("0011"))
    }

    @Test
    fun `clearKeyResponse binds the key to every KID`() {
        val key = ByteArray(16) { it.toByte() }
        val kid = ByteArray(16) { (0x80 + it).toByte() }
        val json = clearKeyResponse(key, listOf(kid))
        val expectKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        val expectKid = Base64.getUrlEncoder().withoutPadding().encodeToString(kid)
        assertTrue(json.contains("\"kid\":\"$expectKid\""))
        assertTrue(json.contains("\"k\":\"$expectKey\""))
        assertTrue(json.contains("\"kty\":\"oct\""))
        assertTrue(json.contains("\"type\":\"temporary\""))
    }

    @Test
    fun `keyIdsFromDrmInitData reads a version 0 PSSH`() {
        val kid = ByteArray(16) { it.toByte() }
        val box = psshV0(kid)
        val data = DrmInitData(DrmInitData.SchemeData(C.COMMON_PSSH_UUID, "cenc", box))
        assertEquals(listOf(kid.toList()), keyIdsFromDrmInitData(data).map { it.toList() })
    }

    @Test
    fun `missing or malformed DRM init data yields no KIDs`() {
        assertTrue(keyIdsFromDrmInitData(null).isEmpty())
        val junk = DrmInitData(DrmInitData.SchemeData(C.COMMON_PSSH_UUID, "cenc", ByteArray(8)))
        assertTrue(keyIdsFromDrmInitData(junk).isEmpty())
    }

    private fun psshV0(kid: ByteArray): ByteArray {
        val size = 12 + 16 + 4 + kid.size
        val out = ByteArray(size)
        fun be32(at: Int, value: Int) {
            out[at] = (value ushr 24).toByte()
            out[at + 1] = (value ushr 16).toByte()
            out[at + 2] = (value ushr 8).toByte()
            out[at + 3] = value.toByte()
        }
        be32(0, size)
        "pssh".forEachIndexed { i, c -> out[4 + i] = c.code.toByte() }
        be32(8, 0)
        repeat(16) { out[12 + it] = it.toByte() } // systemID
        be32(28, 1)
        kid.copyInto(out, 32)
        return out
    }
}
