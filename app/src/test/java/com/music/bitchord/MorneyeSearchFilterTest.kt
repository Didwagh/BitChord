package com.music.bitchord

import com.music.bitchord.data.sources.mornye.isMorneyeTrackItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotify's searchTracks and Amazon's customSearch return one mixed feed tagged
 * `item_type`; Tidal/Deezer/JioSaavn return a bare track array. Without this
 * distinction the mixed feed's albums, artists and playlists appeared as songs
 * in BitChord's results and failed when played.
 */
class MorneyeSearchFilterTest {

    private fun row(type: String?) = buildMap<String, Any?> {
        put("id", "x")
        put("name", "x")
        if (type != null) put("item_type", type)
    }

    @Test
    fun `rows without item_type are tracks`() {
        assertTrue(isMorneyeTrackItem(row(null)))
        assertTrue(isMorneyeTrackItem(emptyMap<Any?, Any?>()))
    }

    @Test
    fun `track rows are kept`() {
        assertTrue(isMorneyeTrackItem(row("track")))
        assertTrue(isMorneyeTrackItem(row(" TRACK ")))
    }

    @Test
    fun `mixed-feed album artist and playlist rows are dropped`() {
        assertFalse(isMorneyeTrackItem(row("album")))
        assertFalse(isMorneyeTrackItem(row("artist")))
        assertFalse(isMorneyeTrackItem(row("playlist")))
    }
}
