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
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.mornye.MorneyePackageManager

private const val TAG = "BitChord/DRM"

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
 * A session manager whose key is looked up only when the sample format is
 * ready.
 *
 * The [DrmSessionManagerProvider] runs when the media source is built, which on
 * a source-backed row is during prefetch — before `stream()` has asked the
 * addon for the file and before the addon has returned its CENC key. Reading
 * the key there permanently captures "no key", and the file later reaches
 * MediaCodec with encryption metadata but no crypto object. Resolving on the
 * first [getCryptoType]/[acquireSession] call, which happens after the file is
 * open, gives the download a chance to save the key first.
 */
@UnstableApi
private class DeferredAddonDrmSessionManager(
    private val configId: String?,
    private val trackId: String?,
) : DrmSessionManager {

    private var playbackLooper: Looper? = null
    private var playerId: PlayerId = PlayerId.UNSET
    private var delegate: DrmSessionManager? = null

    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) {
        this.playbackLooper = playbackLooper
        this.playerId = playerId
        delegate?.setPlayer(playbackLooper, playerId)
    }

    private fun resolve(): DrmSessionManager = synchronized(this) {
        delegate?.let { return@synchronized it }
        val key = MorneyePackageManager.decryptionKeyFor(configId, trackId)
        val chosen = if (key != null && clearKeySupported()) {
            TrackLog.d(TAG, "deferred ClearKey ready for ${trackId ?: "unknown track"}")
            ClearKeyDrmSessionManager(key)
        } else {
            TrackLog.d(
                TAG,
                "deferred key lookup for ${trackId ?: "unknown track"}: " +
                    "keyFound=${key != null}, clearKeySupported=${clearKeySupported()}",
            )
            ClearDrmSessionManager
        }
        playbackLooper?.let { chosen.setPlayer(it, playerId) }
        delegate = chosen
        chosen
    }

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSession? = resolve().acquireSession(eventDispatcher, format)

    override fun getCryptoType(format: Format): Int = resolve().getCryptoType(format)

    override fun release() {
        synchronized(this) {
            delegate?.release()
            delegate = null
        }
    }
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
            // A source-backed row may be prefetched before its addon has
            // returned the file and the CENC key. Keep its identity and defer
            // the decision; everything else has no addon key to wait for.
            val uri = item.localConfiguration?.uri
            val uriConfigId = uri?.getQueryParameter("s")
            val uriTrackId = uri?.getQueryParameter("t")
            // The queue's media id is the same (configId, trackId) pair, and it
            // survives playback URI rewrites that the query parameters do not.
            // Read it as a fallback rather than trusting one encoding of the
            // identity: a key stored under the source id must be findable when
            // the item arrives as a local file.
            val parsed = SourceRegistry.parseTrackKey(item.mediaId)
            val configId = uriConfigId ?: parsed?.first
            val trackId = uriTrackId ?: parsed?.second
            val sourceTrack = configId != null && trackId != null
            TrackLog.d(
                TAG,
                "select: mediaId=${item.mediaId}, uriScheme=${uri?.scheme}, " +
                    "uriAuthority=${uri?.authority}, sourceTrack=$sourceTrack, " +
                    "clearKeySupported=${clearKeySupported()}",
            )
            if (sourceTrack) {
                DeferredAddonDrmSessionManager(configId, trackId)
            } else {
                ClearDrmSessionManager
            }
        }
    }
}
