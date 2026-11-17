package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.sources.mornye.isMorneyeTrackItem
import com.music.bitchord.data.sources.mornye.parseMorneyeTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The Amazon extension states `duration_ms: 0` on every search row, because
 * its `showSearch` response carries no runtime. This is the shape the host
 * matcher has to cope with: a genuine row under the right title and artist
 * whose only missing field is the one the matcher uses to break ties.
 *
 * It must still be matchable. A zero-length runtime is "this catalogue did not
 * say", not "this recording is zero seconds long" — treating it as the latter
 * refuses every Amazon row and silently turns substitution and prefetch off,
 * which is exactly what the device log showed: searches ran, `download()` was
 * never invoked.
 */
class AmazonCatalogMatchTest {

    private val configId = "amzn-config"

    /** The raw row the Amazon extension returns for Believer. */
    private val believerRow: Map<String, Any?> = mapOf(
        "item_type" to "track",
        "id" to "B079VDQRPX",
        "name" to "Believer",
        "artists" to "Imagine Dragons",
        "duration_ms" to 0,
        "cover_url" to "https://m.media-amazon.com/images/I/617AJXTR5HL._AA256.jpg",
        "album_id" to "B079VSDTZP",
        "explicit" to false,
        "audio_modes" to "",
    )

    /** The same pipeline step [com.music.bitchord.data.sources.MorneyeSource.search] performs. */
    private fun songFrom(raw: Map<String, Any?>): Song? {
        val track = parseMorneyeTrack(raw) ?: return null
        if (!isMorneyeTrackItem(raw)) return null
        return Song(
            videoId = SourceRegistry.trackKey(configId, track.id),
            title = track.title,
            artist = track.artist,
            albumName = track.album.ifBlank { null },
            thumbnailUrl = track.artworkUrl,
            durationText = track.durationMs?.div(1000)?.let { sec ->
                String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
            },
            sourceQuality = null,
            sourceHiRes = false,
        )
    }

    private val target = TrackMatcher.Target(
        title = "Believer",
        artist = "Imagine Dragons",
        durationSec = 204,
        album = "Evolve",
        isExplicit = false,
        isVideo = false,
    )

    @Test
    fun `a zero duration is unknown, not zero seconds`() {
        val track = parseMorneyeTrack(believerRow)
        assertEquals("Amazon's duration_ms=0 means unknown", null, track?.durationMs)
    }

    @Test
    fun `an unstated runtime reaches the row as no duration, not 0 colon 00`() {
        val song = songFrom(believerRow)
        assertTrue("row survived the search pipeline", song != null)
        assertEquals(
            "a row must not claim a runtime its catalogue never stated",
            null,
            song!!.durationText,
        )
    }

    @Test
    fun `the Believer row is still recognised as the requested recording`() {
        val song = songFrom(believerRow)
        assertTrue("row survived the search pipeline", song != null)
        val ranked = TrackMatcher.ranked(listOf(song!!), target)
        assertEquals(
            "Amazon rows with no runtime must remain matchable",
            1,
            ranked.size,
        )
    }

    @Test
    fun `a title-and-artist row with a stated runtime still matches`() {
        val song = songFrom(believerRow + ("duration_ms" to 204_000))
        assertTrue(song != null)
        assertEquals(listOf(song), TrackMatcher.ranked(listOf(song!!), target))
    }

    @Test
    fun `a composite key round trips to the raw Amazon id`() {
        val song = songFrom(believerRow)!!
        assertEquals("B079VDQRPX", SourceTrackKeys.parse(song.videoId)?.second)
        assertEquals(configId, SourceTrackKeys.parse(song.videoId)?.first)
    }
}
