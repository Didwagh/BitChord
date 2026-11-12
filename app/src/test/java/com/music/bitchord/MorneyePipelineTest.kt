package com.music.bitchord

import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.mornye.MorneyeStream
import com.music.bitchord.data.sources.mornye.MorneyeTrack
import com.music.bitchord.data.sources.mornye.parseMorneyeStream
import com.music.bitchord.data.sources.mornye.parseMorneyeTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end pipeline validation for the Mornye extension source layer.
 *
 * Tests the full data path that a song travels from an addon JS response
 * through to a playable SourceStream:
 *
 *   JS search result map  →  parseMorneyeTrack  →  MorneyeTrack
 *   MorneyeTrack  →  Song.videoId = SourceRegistry.trackKey(configId, track.id)
 *   Song.videoId  →  SourceTrackKeys.parse()  →  (configId, trackId)
 *   trackId passed to JS: download(rawTrackId, quality, opts)
 *   JS download result map  →  parseMorneyeStream  →  MorneyeStream
 *   MorneyeStream  →  SourceStream(url, headers, format)
 *
 * Five songs are exercised to satisfy the test-5-songs requirement. The
 * JS/HTTP layer is not mocked here — that runs on-device. What is tested here
 * is every Kotlin-side parsing and ID-routing decision so a bug in those
 * cannot hide behind "it works at runtime".
 */
class MorneyePipelineTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private val configId = "test-mornye-config-123"

    /** Simulates what an addon JS searchTracks() call returns for one track. */
    private fun fakeSearchResult(
        id: String,
        name: String,
        artist: String,
        album: String = "",
        durationMs: Int = 240_000,
        artworkUrl: String? = "https://cdn.example.com/$id.jpg",
        quality: String? = null,
    ): Map<String, Any?> = buildMap {
        put("id", id)
        put("name", name)
        put("artists", artist)
        put("album", album)
        put("duration_ms", durationMs)
        put("artwork", artworkUrl)
        if (quality != null) put("audioQuality", quality)
    }

    /** Simulates what an addon JS download() call returns for a stream. */
    private fun fakeDownloadResult(
        url: String,
        fileType: String = "mp3",
        headers: Map<String, String> = emptyMap(),
        quality: String? = null,
    ): Map<String, Any?> = buildMap {
        put("url", url)
        put("fileType", fileType)
        put("headers", headers)
        if (quality != null) put("quality", quality)
    }

    // ── Test data: 5 songs ────────────────────────────────────────────────────

    private val song1 = fakeSearchResult(
        id = "6rqhFgbbKwnb9MLmUQDhG6",
        name = "Shape of You",
        artist = "Ed Sheeran",
        album = "÷ (Divide)",
        durationMs = 233_713,
    )
    private val song2 = fakeSearchResult(
        id = "0tgVpDi06FyKpA1z0VMD4v",
        name = "Blinding Lights",
        artist = "The Weeknd",
        album = "After Hours",
        durationMs = 200_040,
        quality = "FLAC",
    )
    private val song3 = fakeSearchResult(
        id = "2hRDHpYMF8ZRSoWDi3c3L0",
        name = "Bad Guy",
        artist = "Billie Eilish",
        album = "WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?",
        durationMs = 194_088,
    )
    private val song4 = fakeSearchResult(
        id = "2nLtzopw4rPReszdYBJU6h",
        name = "Bohemian Rhapsody",
        artist = "Queen",
        album = "A Night at the Opera",
        durationMs = 354_320,
        quality = "lossless",
    )
    private val song5 = fakeSearchResult(
        id = "5HCyWlXZPP0y6Gqq8TgA20",
        name = "Kesariya",
        artist = "Arijit Singh",
        album = "Brahmastra",
        durationMs = 268_560,
    )

    // ── parseMorneyeTrack tests ───────────────────────────────────────────────

    @Test
    fun `parseMorneyeTrack parses all five songs correctly`() {
        listOf(song1, song2, song3, song4, song5).forEach { raw ->
            val track = parseMorneyeTrack(raw)
            assertNotNull("parseMorneyeTrack returned null for id=${raw["id"]}", track)
            assertTrue(
                "Track id must not be blank for ${raw["id"]}",
                track!!.id.isNotBlank(),
            )
            assertTrue(
                "Track title must not be blank for ${raw["id"]}",
                track.title.isNotBlank(),
            )
            assertTrue(
                "Track artist must not be blank for ${raw["id"]}",
                track.artist.isNotBlank(),
            )
            // Duration should come through in milliseconds > 10,000
            assertTrue(
                "Duration must be > 10000 ms for ${raw["id"]}",
                (track.durationMs ?: 0) > 10_000,
            )
        }
    }

    @Test
    fun `parseMorneyeTrack preserves exact id from addon response`() {
        val track = parseMorneyeTrack(song1)!!
        assertEquals("6rqhFgbbKwnb9MLmUQDhG6", track.id)
        assertEquals("Shape of You", track.title)
        assertEquals("Ed Sheeran", track.artist)
    }

    @Test
    fun `parseMorneyeTrack detects lossless quality hint`() {
        val losslessTrack = parseMorneyeTrack(song2)!!   // quality = "FLAC"
        val losslessTrack2 = parseMorneyeTrack(song4)!! // quality = "lossless"
        val normalTrack = parseMorneyeTrack(song1)!!

        assertTrue("FLAC quality should be detected as lossless", losslessTrack.isLossless)
        assertTrue("'lossless' quality should be detected as lossless", losslessTrack2.isLossless)
        assertTrue("No quality tag → not lossless", !normalTrack.isLossless)
    }

    @Test
    fun `parseMorneyeTrack returns null for missing id`() {
        val bad = mapOf("name" to "Test Song") // no id
        assertNull(parseMorneyeTrack(bad))
    }

    @Test
    fun `parseMorneyeTrack returns null for empty id`() {
        val bad = mapOf("id" to "", "name" to "Test Song")
        assertNull(parseMorneyeTrack(bad))
    }

    // ── SourceTrackKeys round-trip tests ──────────────────────────────────────

    @Test
    fun `SourceTrackKeys round-trips all five track IDs correctly`() {
        val tracks = listOf(song1, song2, song3, song4, song5)
            .mapNotNull { parseMorneyeTrack(it) }

        assertEquals("All 5 songs should parse", 5, tracks.size)

        for (track in tracks) {
            // Simulate what MorneyeSource.search() does to build Song.videoId
            val compositeKey = SourceRegistry.trackKey(configId, track.id)

            // The key must start with the prefix
            assertTrue(
                "Key for ${track.id} must start with 'src:'",
                compositeKey.startsWith("src:"),
            )

            // SourceTrackKeys.parse() must recover the original ids
            val parsed = SourceTrackKeys.parse(compositeKey)
            assertNotNull("SourceTrackKeys.parse() returned null for key=$compositeKey", parsed)
            assertEquals(
                "configId round-trip failed for ${track.id}",
                configId,
                parsed!!.first,
            )
            assertEquals(
                "trackId round-trip failed for ${track.id}",
                track.id,
                parsed.second,
            )
        }
    }

    @Test
    fun `SourceTrackKeys parse returns null for plain YouTube videoId`() {
        assertNull(SourceTrackKeys.parse("dQw4w9WgXcQ"))  // plain YouTube id
        assertNull(SourceTrackKeys.parse(""))
        assertNull(SourceTrackKeys.parse("src:config-only-no-separator"))
    }

    @Test
    fun `SourceTrackKeys parse handles separator in trackId`() {
        // Track IDs with special chars — the separator is ::, so a single : in
        // the track ID is fine.
        val weirdId = "track:with:colons"
        val key = SourceRegistry.trackKey(configId, weirdId)
        val parsed = SourceTrackKeys.parse(key)
        assertNotNull(parsed)
        assertEquals(configId, parsed!!.first)
        assertEquals(weirdId, parsed.second)
    }

    @Test
    fun `stream() receives plain trackId not composite key`() {
        // This replicates what SourceResolver.streamBest() does before calling stream():
        //   val trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
        // And what MorneyeSource.stream() does with that trackId:
        //   val rawTrackId = SourceTrackKeys.parse(trackId)?.second ?: trackId
        //
        // After streamBest() strips the prefix, trackId is already the plain addon id.
        // MorneyeSource.stream() then also strips — but SourceTrackKeys.parse() on a
        // plain id returns null, so the fallback `?: trackId` is used. Net result:
        // the plain id is preserved correctly through the double-strip.

        val originalTrackId = "6rqhFgbbKwnb9MLmUQDhG6"
        val compositeKey = SourceRegistry.trackKey(configId, originalTrackId)

        // Step 1: streamBest() strips the prefix
        val strippedByStreamBest = SourceRegistry.parseTrackKey(compositeKey)?.second
            ?: compositeKey
        assertEquals("streamBest stripping", originalTrackId, strippedByStreamBest)

        // Step 2: MorneyeSource.stream() strips again (harmless double-strip)
        val strippedByMorneyeSource = SourceTrackKeys.parse(strippedByStreamBest)?.second
            ?: strippedByStreamBest
        assertEquals("MorneyeSource double-strip is safe", originalTrackId, strippedByMorneyeSource)
    }

    // ── parseMorneyeStream tests ──────────────────────────────────────────────

    @Test
    fun `parseMorneyeStream parses valid stream results for all five songs`() {
        val streams = listOf(
            fakeDownloadResult("https://cdn1.example.com/song1.mp3", "mp3"),
            fakeDownloadResult("https://cdn2.example.com/song2.flac", "flac",
                headers = mapOf("Authorization" to "Bearer tok123")),
            fakeDownloadResult("https://cdn3.example.com/song3.m4a", "m4a"),
            fakeDownloadResult("https://cdn4.example.com/song4.flac", "flac",
                quality = "lossless"),
            fakeDownloadResult("https://cdn5.example.com/song5.mp3", "mp3",
                headers = mapOf("X-Custom" to "val")),
        )

        for ((i, raw) in streams.withIndex()) {
            val stream = parseMorneyeStream(raw)
            assertNotNull("parseMorneyeStream returned null for song ${i + 1}", stream)
            val url = stream!!.directUrl
            assertNotNull("directUrl must not be null for song ${i + 1}", url)
            assertTrue(
                "URL must start with https:// for song ${i + 1}",
                url!!.startsWith("https://"),
            )
            assertTrue(
                "URL must not be blank for song ${i + 1}",
                url.isNotBlank(),
            )
        }
    }

    @Test
    fun `parseMorneyeStream detects lossless by fileType`() {
        val flacStream = parseMorneyeStream(
            fakeDownloadResult("https://cdn.example.com/song.flac", "flac"),
        )!!
        assertTrue("FLAC fileType → isLossless", flacStream.isLossless)
    }

    @Test
    fun `parseMorneyeStream carries headers through correctly`() {
        val headers = mapOf(
            "Authorization" to "Bearer secrettoken",
            "X-Session-ID" to "abc123",
        )
        val stream = parseMorneyeStream(
            fakeDownloadResult("https://cdn.example.com/s.mp3", headers = headers),
        )!!
        assertEquals("Authorization", "Bearer secrettoken", stream.headers["Authorization"])
        assertEquals("X-Session-ID", "abc123", stream.headers["X-Session-ID"])
    }

    @Test
    fun `parseMorneyeStream returns null when both url and segments are absent`() {
        val empty = mapOf<String, Any?>()
        assertNull(parseMorneyeStream(empty))

        val noUrl = mapOf<String, Any?>("fileType" to "mp3")
        assertNull(parseMorneyeStream(noUrl))
    }

    @Test
    fun `parseMorneyeStream detects segmented streams correctly`() {
        val segmented = mapOf(
            "segments" to listOf("seg1.ts", "seg2.ts"),
            "fileType" to "hls",
        )
        val stream = parseMorneyeStream(segmented)
        assertNotNull(stream)
        assertTrue("Segmented stream without direct URL", stream!!.isSegmented)
        assertNull("No direct URL", stream.directUrl)
    }

    // ── Metadata / duration conversion tests ─────────────────────────────────

    @Test
    fun `parseMorneyeTrack converts duration_ms to milliseconds correctly`() {
        // duration_ms > 10000 is kept as-is
        val trackMs = parseMorneyeTrack(mapOf("id" to "x", "name" to "X",
            "duration_ms" to 234_000))!!
        assertEquals(234_000, trackMs.durationMs)

        // duration_ms <= 10000 is treated as seconds and converted to ms
        val trackSec = parseMorneyeTrack(mapOf("id" to "y", "name" to "Y",
            "duration_ms" to 234))!!
        assertEquals(234_000, trackSec.durationMs)
    }

    @Test
    fun `parseMorneyeTrack handles artists as a list of maps`() {
        val raw = mapOf(
            "id" to "z",
            "name" to "Collab Song",
            "artists" to listOf(
                mapOf("name" to "Artist A"),
                mapOf("name" to "Artist B"),
            ),
        )
        val track = parseMorneyeTrack(raw)!!
        assertEquals("Artist A, Artist B", track.artist)
    }
}

