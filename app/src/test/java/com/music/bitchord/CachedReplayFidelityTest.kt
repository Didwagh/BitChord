package com.music.bitchord

import com.music.bitchord.data.sources.ModuleSource
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A cached row must rebuild the same playback URI as the row that filled the
 * entry. Otherwise the `mix` marker changes, [AudioCache] looks under a
 * different key, and the resolver is free to answer with another rendition.
 */
class CachedReplayFidelityTest {

    @Test
    fun `stored quality is kept as-is`() {
        assertEquals(
            "FLAC · 24-bit · 96 kHz",
            AudioCache.replaySourceQuality("FLAC · 24-bit · 96 kHz", SourceResolver.MIX_STEREO),
        )
    }

    @Test
    fun `older stereo entries still ask for stereo`() {
        val quality = AudioCache.replaySourceQuality(null, SourceResolver.MIX_STEREO)
        assertEquals(SourceResolver.MIX_STEREO, SourceResolver.mixOf(quality))
        // A mix description is not a lossless claim: bytes and decoder decide.
        assertNull(quality?.let { if (it == ModuleSource.LOSSLESS) it else null })
    }

    @Test
    fun `older atmos entries stay on the immersive mix`() {
        val quality = AudioCache.replaySourceQuality(null, SourceResolver.MIX_ATMOS)
        assertEquals(ModuleSource.DOLBY, quality)
        assertEquals(SourceResolver.MIX_ATMOS, SourceResolver.mixOf(quality))
    }

    @Test
    fun `unmarked entries stay unmarked`() {
        assertNull(AudioCache.replaySourceQuality(null, SourceResolver.MIX_ANY))
        assertNull(AudioCache.replaySourceQuality(null, null))
        assertEquals(SourceResolver.MIX_ANY, SourceResolver.mixOf(null))
    }
}
