package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.playback.pickedMixQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A row tapped in the addons search has to stay the row the listener picked.
 *
 * The `&mix=` marker is what tells [SourceResolver.resolve] that: without it a
 * failed Mornye download may be replaced by YouTube's Opus, which is a
 * different, lesser recording under the addon's name. Mornye extensions were
 * left out of the marker because the check only knew about legacy addons.
 *
 * The marker builder is tested directly rather than through
 * [com.music.bitchord.playback.toMediaItem]: the unit-test android.jar stubs
 * [android.net.Uri.Builder] methods to return null, so no URI can be built
 * here. That is a limitation of the test host, not of the player.
 */
class MornyePickedRowTest {

    private val before = SourceRegistry.configs.value

    @After
    fun tearDown() {
        SourceRegistry.configs.value = before
    }

    private fun markerFor(kind: SourceKind): String {
        SourceRegistry.configs.value = listOf(SourceConfig(id = "cfg", kind = kind))
        return Song(
            videoId = SourceRegistry.trackKey("cfg", "B079VDQRPX"),
            title = "Believer",
            artist = "Imagine Dragons",
            thumbnailUrl = null,
            durationText = "3:24",
        ).pickedMixQuery("cfg")
    }

    @Test
    fun `mornye row is marked as picked`() {
        assertEquals(
            "&${SourceResolver.MIX_PARAM}=${SourceResolver.MIX_ANY}",
            markerFor(SourceKind.MORNYE),
        )
    }

    @Test
    fun `legacy addon row keeps its picked marker`() {
        assertEquals(
            "&${SourceResolver.MIX_PARAM}=${SourceResolver.mixOf(null)}",
            markerFor(SourceKind.ADDON),
        )
    }

    @Test
    fun `other sources are not marked as picked`() {
        assertEquals("", markerFor(SourceKind.JIOSAAVN))
    }
}
