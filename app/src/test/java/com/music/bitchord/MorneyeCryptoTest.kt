package com.music.bitchord

import com.music.bitchord.data.sources.mornye.hmacSha1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `utils.hmacSHA1(key, data)` feeds Spotify's web token TOTP. These vectors are
 * RFC 2202 and the last test pins the argument order that the token endpoint
 * depends on: reversing key and message still yields a digest, but the six-digit
 * code it produces is rejected with HTTP 400.
 */
class MorneyeCryptoTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `hmac sha1 matches RFC 2202 case 1`() {
        val key = ByteArray(20) { 0x0b }
        assertEquals(
            "b617318655057264e28bc0b6fb378c8ef146be00",
            hex(hmacSha1(key, "Hi There".toByteArray())),
        )
    }

    @Test
    fun `hmac sha1 matches RFC 2202 case 2`() {
        assertEquals(
            "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79",
            hex(hmacSha1("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray())),
        )
    }

    @Test
    fun `key is the signing key and data is the message`() {
        val key = "secret".toByteArray()
        val counter = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)
        // Correct order, verified independently with `openssl dgst -sha1 -hmac`.
        assertEquals("1b2998dda576aa7905ca20325b1275d00a084e94", hex(hmacSha1(key, counter)))
        assertNotEquals(hmacSha1(key, counter).toList(), hmacSha1(counter, key).toList())
    }
}
