package com.music.bitchord.data.sources.mornye

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA1 in the argument order addons see: `utils.hmacSHA1(key, data)`.
 *
 * The order is not cosmetic. Spotify's web token is a TOTP whose key is the
 * shared secret and whose message is the 8-byte time counter; swapping them
 * still produces a well-formed six-digit code, so the only visible symptom is
 * the endpoint answering `HTTP 400 Unauthorized request` — exactly the failure
 * this host used to hit. Keeping the function named and tested here makes the
 * order impossible to get wrong again.
 *
 * Returns the raw digest bytes, which is what the JS prelude hands back to the
 * addon as a `Uint8Array`.
 */
internal fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key, "HmacSHA1"))
    return mac.doFinal(data)
}
