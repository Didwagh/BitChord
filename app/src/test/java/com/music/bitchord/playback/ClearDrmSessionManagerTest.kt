package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.UUID

class ClearDrmSessionManagerTest {
    /** The shape Media3 gives an fMP4 track whose moov carries a stray pssh. */
    private val formatWithPssh = Format.Builder()
        .setSampleMimeType("audio/flac")
        .setDrmInitData(
            DrmInitData(
                DrmInitData.SchemeData(
                    UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
                    "application/mp4",
                    byteArrayOf(1, 2, 3, 4),
                ),
            ),
        )
        .build()

    @Test
    fun `acquireSession returns null even when the format has pssh`() {
        assertNull(ClearDrmSessionManager.acquireSession(null, formatWithPssh))
    }

    @Test
    fun `crypto type stays none so the decoder is not blocked`() {
        assertEquals(C.CRYPTO_TYPE_NONE, ClearDrmSessionManager.getCryptoType(formatWithPssh))
    }

    @Test
    fun `provider uses the clear manager for ordinary items without a DRM configuration`() {
        val item = MediaItem.Builder().setMediaId("ordinary-youtube-id").build()

        assertSame(ClearDrmSessionManager, clearAwareDrmSessionManagerProvider().get(item))
    }

    @Test
    fun `provider defers source-backed items until their addon key is known`() {
        // A src: id is the queue's (configId, trackId) pair, so it deliberately
        // gets the deferred manager instead of the plain clear one.
        val item = MediaItem.Builder().setMediaId("src:mornye::B079TNV7WL").build()

        assertNotSame(ClearDrmSessionManager, clearAwareDrmSessionManagerProvider().get(item))
    }
}
