package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import android.os.Looper
import com.music.bitchord.data.sources.mornye.MorneyePackageManager

/**
 * A DRM session manager for items that were never configured for DRM.
 *
 * Some providers package clear audio in a fragmented MP4 that still carries a
 * `pssh` box. Media3 copies that box into `Format.drmInitData`, and its stock
 * [DrmSessionManager.DRM_UNSUPPORTED] turns it into an immediate
 * `ERROR_CODE_DRM_SCHEME_UNSUPPORTED` from `SampleQueue.onFormatResult` — before
 * a single sample reaches the decoder. The addon downloads these files over a
 * plain signed URL with no license exchange, so the samples themselves are
 * clear and no session is needed. Returning `null` from [acquireSession] makes
 * the sample queue treat the format as clear, which is what the bytes already
 * are.
 *
 * Genuinely DRM-configured items (`mediaItem.drmConfiguration != null`) keep
 * the stock provider — see [clearAwareDrmSessionManagerProvider].
 */
@UnstableApi
object ClearDrmSessionManager : DrmSessionManager {
    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) = Unit

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSession? = null

    override fun getCryptoType(format: Format): Int = C.CRYPTO_TYPE_NONE
}

/**
 * Provider for [DefaultMediaSourceFactory.setDrmSessionManagerProvider]: use
 * real DRM only when the item asked for it, and ignore stray PSSH boxes on
 * everything else.
 */
@UnstableApi
fun clearAwareDrmSessionManagerProvider(): DrmSessionManagerProvider {
    val configured = DefaultDrmSessionManagerProvider()
    return DrmSessionManagerProvider { item: MediaItem ->
        if (item.localConfiguration?.drmConfiguration != null) {
            configured.get(item)
        } else {
            // An addon may have handed back CENC-encrypted ISO-BMFF with the
            // AES key in its download result. Provision that key into a
            // ClearKey session before the samples reach MediaCodec; without
            // it ExoPlayer queues encrypted buffers with no crypto object and
            // dies with `queuing secure buffer without mCrypto or mDescrambler`.
            val uri = item.localConfiguration?.uri
            val configId = uri?.getQueryParameter("s")
            val trackId = uri?.getQueryParameter("t")
            val key = MorneyePackageManager.decryptionKeyFor(configId, trackId)
            if (key != null && clearKeySupported()) {
                ClearKeyDrmSessionManager(key)
            } else {
                ClearDrmSessionManager
            }
        }
    }
}
