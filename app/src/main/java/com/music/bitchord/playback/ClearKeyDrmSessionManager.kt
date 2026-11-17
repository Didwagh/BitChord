package com.music.bitchord.playback

import android.media.MediaDrm
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkCryptoConfig
import com.music.bitchord.data.TrackLog
import java.util.Base64

private const val TAG = "BitChord/ClearKey"

/**
 * Decrypts an addon's CENC-encrypted ISO-BMFF with the AES key the addon
 * returned, using the platform's ClearKey CDM.
 *
 * Lokal and the reference Mornye app run `ffmpeg -decryption_key <key>` on the
 * downloaded file before handing it to their player. BitChord has no ffmpeg
 * CLI, but the samples are ordinary AES-128-CTR CENC: Media3 already marks
 * them encrypted and only needs a [FrameworkCryptoConfig] backed by a CDM
 * that holds the key. ClearKey is that CDM — no license server, no network.
 *
 * The key itself is never logged; only its presence and length are.
 */
@UnstableApi
internal class ClearKeyDrmSessionManager(
    private val keyHex: String,
) : DrmSessionManager {

    private var session: DrmSession? = null
    private var attempted = false

    override fun setPlayer(playbackLooper: android.os.Looper, playerId: androidx.media3.exoplayer.analytics.PlayerId) = Unit

    /** Provision once, on the first call — SampleQueue asks for the crypto type before the session. */
    private fun ensureSession(drmInitData: DrmInitData?): DrmSession? {
        if (!attempted) {
            attempted = true
            session = createClearKeySession(keyHex, drmInitData)
            if (session == null) {
                TrackLog.w(TAG, "ClearKey provisioning failed; falling back to a clear session")
            }
        }
        return session
    }

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSession = ensureSession(format.drmInitData)
        ?: DrmSessionManager.DRM_UNSUPPORTED.acquireSession(eventDispatcher, format)!!

    override fun getCryptoType(format: Format): Int =
        if (ensureSession(format.drmInitData) != null) C.CRYPTO_TYPE_FRAMEWORK else C.CRYPTO_TYPE_NONE

    override fun release() {
        session?.release(null)
        session = null
    }
}

/** True when this device can provision a ClearKey CDM at all. */
internal fun clearKeySupported(): Boolean = runCatching {
    MediaDrm.isCryptoSchemeSupported(C.CLEARKEY_UUID)
}.getOrDefault(false)

private fun createClearKeySession(keyHex: String, drmInitData: DrmInitData?): DrmSession? {
    val key = hexToBytes(keyHex) ?: run {
        TrackLog.w(TAG, "Mornye returned a decryption key that is not 16-byte hex")
        return null
    }
    if (!clearKeySupported()) {
        TrackLog.w(TAG, "Device has no ClearKey CDM; encrypted addon audio cannot be decoded")
        return null
    }
    val drm = runCatching { MediaDrm(C.CLEARKEY_UUID) }.getOrElse {
        TrackLog.w(TAG, "Could not open ClearKey CDM: ${it.message}")
        return null
    }
    return try {
        val sessionId = drm.openSession()
        val kids = keyIdsFromDrmInitData(drmInitData).ifEmpty { listOf(ByteArray(16)) }
        // getKeyRequest is what tells the plugin which KIDs the session needs.
        // The init data is the PSSH Media3 parsed from the addon's file; some
        // addons omit it, in which case the zero KID above is enough.
        runCatching {
            drm.getKeyRequest(sessionId, firstPssh(drmInitData), "cenc", MediaDrm.KEY_TYPE_STREAMING, null)
        }.onFailure { TrackLog.d(TAG, "ClearKey key request: ${it.message}") }
        drm.provideKeyResponse(sessionId, clearKeyResponse(key, kids).toByteArray(Charsets.UTF_8))
        val cryptoConfig = FrameworkCryptoConfig(C.CLEARKEY_UUID, sessionId)
        TrackLog.d(TAG, "ClearKey session ready for ${kids.size} key id(s)")
        object : DrmSession {
            private var refs = 0
            private var closed = false
            override fun getState(): Int = DrmSession.STATE_OPENED_WITH_KEYS
            override fun getError(): DrmSession.DrmSessionException? = null
            override fun getSchemeUuid() = C.CLEARKEY_UUID
            override fun getCryptoConfig() = cryptoConfig
            override fun queryKeyStatus(): Map<String, String> = emptyMap()
            override fun getOfflineLicenseKeySetId(): ByteArray? = null
            override fun requiresSecureDecoder(mime: String) = false
            override fun acquire(eventDispatcher: DrmSessionEventListener.EventDispatcher?) { refs++ }
            override fun release(eventDispatcher: DrmSessionEventListener.EventDispatcher?) {
                if (--refs > 0 || closed) return
                closed = true
                runCatching { drm.closeSession(sessionId) }
                runCatching { drm.close() }
            }
        }
    } catch (e: Exception) {
        runCatching { drm.close() }
        TrackLog.w(TAG, "ClearKey provisioning failed: ${e.message}")
        null
    }
}

/** Hex, optionally `0x`-prefixed, to the 16 AES bytes ffmpeg's `-decryption_key` uses. */
internal fun hexToBytes(value: String): ByteArray? {
    val hex = value.trim().removePrefix("0x").replace(Regex("[^0-9a-fA-F]"), "")
    if (hex.length != 32) return null
    return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** EME ClearKey license response: one JWK entry per KID, all using the same AES key. */
internal fun clearKeyResponse(key: ByteArray, kids: List<ByteArray>): String {
    val encodedKey = base64Url(key)
    val entries = kids.joinToString(",") { kid ->
        "{\"kty\":\"oct\",\"kid\":\"${base64Url(kid)}\",\"k\":\"$encodedKey\"}"
    }
    return "{\"keys\":[$entries],\"type\":\"temporary\"}"
}

private fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** KIDs from a v0 PSSH box, which is what CENC files in MP4 carry. */
internal fun keyIdsFromDrmInitData(data: DrmInitData?): List<ByteArray> {
    if (data == null) return emptyList()
    val kids = mutableListOf<ByteArray>()
    for (i in 0 until data.schemeDataCount) {
        val box = data.get(i).data ?: continue
        if (box.size < 28 || !box.copyOfRange(4, 8).contentEquals(PSH_TAG)) continue
        if (box[8].toInt() != 0) continue // v1 PSSH has no KID list
        var offset = 12 + 16 // version+flags, systemID
        if (offset + 4 > box.size) continue
        val count = be32(box, offset)
        offset += 4
        repeat(count.coerceAtMost(64)) {
            if (offset + 16 <= box.size) {
                kids += box.copyOfRange(offset, offset + 16)
                offset += 16
            }
        }
    }
    return kids
}

private fun firstPssh(data: DrmInitData?): ByteArray? {
    if (data == null) return null
    for (i in 0 until data.schemeDataCount) {
        val box = data.get(i).data ?: continue
        if (box.size >= 8 && box.copyOfRange(4, 8).contentEquals(PSH_TAG)) return box
    }
    return null
}

private val PSH_TAG = byteArrayOf(0x70, 0x73, 0x73, 0x68) // 'pssh'
private fun be32(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xff) shl 24) or
        ((bytes[at + 1].toInt() and 0xff) shl 16) or
        ((bytes[at + 2].toInt() and 0xff) shl 8) or
        (bytes[at + 3].toInt() and 0xff)
