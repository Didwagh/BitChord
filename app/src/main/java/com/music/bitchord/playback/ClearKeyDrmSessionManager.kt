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
import java.util.UUID

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
        // Android's ClearKey plugin rejects the file's Widevine/Amazon PSSH
        // (cdmError 4). It expects its own system ID and a version-1 box whose
        // payload is the KID list, so build that from the KIDs Media3 parsed.
        val request = runCatching {
            drm.getKeyRequest(
                sessionId,
                clearKeyInitData(kids),
                "cenc",
                MediaDrm.KEY_TYPE_STREAMING,
                null,
            )
        }
        if (request.isSuccess) {
            TrackLog.d(TAG, "ClearKey key request accepted for ${kids.size} key id(s)")
        } else {
            TrackLog.d(TAG, "ClearKey key request rejected: ${request.exceptionOrNull()?.message}")
        }
        val response = runCatching {
            drm.provideKeyResponse(sessionId, clearKeyResponse(key, kids).toByteArray(Charsets.UTF_8))
        }
        if (response.isFailure) {
            TrackLog.w(TAG, "ClearKey license response rejected: ${response.exceptionOrNull()?.message}")
        }
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

/**
 * Android's ClearKey InitDataParser expects a PSSH with the ClearKey system
 * ID, version 1, the KID count where a v0 box stores it, and a trailing
 * four-byte DataSize of zero. Mirroring that layout avoids `cdmError:4`
 * when the media contains only an Amazon/Widevine PSSH.
 */
internal fun clearKeyInitData(kids: List<ByteArray>): ByteArray {
    val ids = kids.ifEmpty { listOf(ByteArray(16)) }
    val size = 12 + 16 + 4 + ids.size * 16 + 4
    val out = ByteArray(size)
    putBe32(out, 0, size)
    PSH_TAG.copyInto(out, 4)
    out[8] = 1 // version; Android ClearKey requires this even though KIDs follow
    uuidBytes(C.CLEARKEY_UUID).copyInto(out, 12)
    putBe32(out, 28, ids.size)
    ids.forEachIndexed { index, kid ->
        require(kid.size == 16) { "ClearKey KID must be 16 bytes" }
        kid.copyInto(out, 32 + index * 16)
    }
    return out // last four bytes are DataSize(0)
}

private fun uuidBytes(uuid: UUID): ByteArray {
    val high = uuid.mostSignificantBits
    val low = uuid.leastSignificantBits
    return ByteArray(16) { i ->
        val bits = if (i < 8) high shr (56 - i * 8) else low shr (120 - i * 8)
        (bits.toInt() and 0xff).toByte()
    }
}

private fun putBe32(bytes: ByteArray, at: Int, value: Int) {
    bytes[at] = (value ushr 24).toByte()
    bytes[at + 1] = (value ushr 16).toByte()
    bytes[at + 2] = (value ushr 8).toByte()
    bytes[at + 3] = value.toByte()
}

private val PSH_TAG = byteArrayOf(0x70, 0x73, 0x73, 0x68) // 'pssh'
private fun be32(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xff) shl 24) or
        ((bytes[at + 1].toInt() and 0xff) shl 16) or
        ((bytes[at + 2].toInt() and 0xff) shl 8) or
        (bytes[at + 3].toInt() and 0xff)
