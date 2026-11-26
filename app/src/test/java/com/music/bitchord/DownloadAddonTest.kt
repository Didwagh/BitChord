package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.MorneyeSource
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.sources.mornye.DownloadAddonCatalog
import com.music.bitchord.data.sources.mornye.InstalledMorneyePackage
import com.music.bitchord.data.sources.mornye.MorneyeCapabilities
import com.music.bitchord.download.DownloadAddonChoice
import com.music.bitchord.download.DownloadAddonPreference
import com.music.bitchord.download.DownloadFailureSignal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind a long-pressed addon download, kept separate from the UI
 * that asks the question. The important parts fail quietly if they go wrong:
 * an addon with downloads forbidden would appear in the chooser and then refuse
 * the tap, a saved default would silently become a strict no-fallback pick, or
 * a forty-track album would raise forty identical popups.
 */
class DownloadAddonTest {

    @After
    fun clearSignals() {
        DownloadFailureSignal.clearAll()
    }

    @Test
    fun `only enabled addons that allow downloads are offered`() {
        val options = DownloadAddonCatalog.downloadableAddonOptions(
            listOf(
                addon("qobuz-web", "Qobuz"),
                addon("disabled", "Disabled", enabled = false),
                addon("no-download", "No downloads"),
                addon("forbidden", "Forbidden"),
            ),
        ) { key ->
            when (key) {
                "no-download" -> capabilities(downloads = false)
                "forbidden" -> capabilities(allowDownloads = false)
                else -> capabilities()
            }
        }

        assertEquals(listOf("qobuz-web"), options.map { it.key })
        assertEquals("Qobuz", options.single().detail)
    }

    @Test
    fun `an addon with no empty display name still has a usable label`() {
        val options = DownloadAddonCatalog.downloadableAddonOptions(
            listOf(addon("amazon-web", "Amazon Music")),
        ) { capabilities() }

        assertEquals("Amazon Music", options.single().displayName)
        // Amazon stays on the generic path; it must not inherit Qobuz's
        // availability hints just because it is a Mornye addon.
        assertEquals("Mornye extension", options.single().detail)
    }

    @Test
    fun `an unknown addon key gets a generic label rather than its raw key`() {
        assertEquals("Mornye extension", DownloadAddonCatalog.displayName("qobuz-web", emptyList()))
        assertEquals(
            "Qobuz",
            DownloadAddonCatalog.displayName("qobuz-web", listOf(addon("qobuz-web", "Qobuz"))),
        )
    }

    @Test
    fun `a saved addon that can no longer export downloads is treated as stale`() {
        val installed = listOf(
            addon("qobuz-web", "Qobuz"),
            addon("disabled", "Disabled", enabled = false),
            addon("no-download", "No downloads"),
            addon("forbidden", "Forbidden"),
        )
        fun usable(key: String) = DownloadAddonCatalog.usableAddon(key, installed) { addonKey ->
            when (addonKey) {
                "no-download" -> capabilities(downloads = false)
                "forbidden" -> capabilities(allowDownloads = false)
                else -> capabilities()
            }
        }

        assertTrue(usable("qobuz-web"))
        assertFalse(usable("disabled"))
        assertFalse(usable("no-download"))
        assertFalse(usable("forbidden"))
        assertFalse(usable("uninstalled"))
    }

    @Test
    fun `a saved default is a preference and a blank one is automatic`() {
        assertEquals(
            DownloadAddonChoice("qobuz-web", strict = false),
            DownloadAddonPreference.fromSaved(" qobuz-web "),
        )
        assertNull(DownloadAddonPreference.fromSaved(null))
        assertNull(DownloadAddonPreference.fromSaved("   "))
    }

    @Test
    fun `a plain tap prefers the first capable addon when none is saved`() {
        val choice = DownloadAddonPreference.automatic(null, listOf("qobuz-web", "amazon-web"))
        assertEquals(DownloadAddonChoice("qobuz-web", strict = false), choice)
        assertFalse(choice!!.strict)
    }

    @Test
    fun `a valid saved default wins over the automatic addon`() {
        assertEquals(
            DownloadAddonChoice("amazon-web", strict = false),
            DownloadAddonPreference.automatic(" amazon-web ", listOf("qobuz-web", "amazon-web")),
        )
    }

    @Test
    fun `a stale saved default falls back to the first capable addon`() {
        assertEquals(
            DownloadAddonChoice("qobuz-web", strict = false),
            DownloadAddonPreference.automatic("gone-web", listOf("qobuz-web", "amazon-web")),
        )
        assertNull(DownloadAddonPreference.automatic("gone-web", emptyList()))
    }

    @Test
    fun `a strict addon choice can replace an earlier saved copy`() {
        assertTrue(
            DownloadAddonPreference.shouldAttempt(
                DownloadAddonChoice("qobuz-web", strict = true),
                "39fdxGmYfvg",
                setOf("39fdxGmYfvg"),
            ),
        )
        assertFalse(DownloadAddonPreference.shouldAttempt(null, "39fdxGmYfvg", setOf("39fdxGmYfvg")))
        assertTrue(DownloadAddonPreference.shouldAttempt(null, "other", setOf("39fdxGmYfvg")))
    }

    @Test
    fun `a pinned lookup takes only the named Mornye addon`() {
        val youtube = FakeSource("yt", SourceKind.YOUTUBE)
        val otherAddon = morneye("amazon-web")
        val wanted = morneye("qobuz-web")

        val found = SourceResolver.pinnedDownloadSource(
            listOf(youtube, otherAddon, wanted),
            "qobuz-web",
        )

        assertSame(wanted, found)
        assertNull(SourceResolver.pinnedDownloadSource(listOf(youtube, otherAddon), "qobuz-web"))
    }

    @Test
    fun `only a strict one-shot ask widens a capped download`() {
        val capped = StreamRequest.Capped(128)
        assertEquals(StreamRequest.Best, SourceResolver.requestForChosenAddon(capped, widenCapped = true))
        assertEquals(capped, SourceResolver.requestForChosenAddon(capped, widenCapped = false))
        assertEquals(
            StreamRequest.Lossless,
            SourceResolver.requestForChosenAddon(StreamRequest.Lossless, widenCapped = true),
        )
    }

    @Test
    fun `one failed addon raises one in-app notice, even for a whole album`() {
        DownloadFailureSignal.report("qobuz-web", "Qobuz", "First song", false)
        val first = DownloadFailureSignal.event.value!!
        assertEquals("First song", first.title)
        assertFalse(first.wasCached)

        // Nothing else is on screen while the first notice is up, and the
        // cooldown keeps a dismiss-and-refresh from starting a fresh burst.
        DownloadFailureSignal.report("amazon-web", "Amazon Music", "Second song", true)
        assertSame(first, DownloadFailureSignal.event.value)

        DownloadFailureSignal.dismiss()
        assertNull(DownloadFailureSignal.event.value)
        DownloadFailureSignal.report("qobuz-web", "Qobuz", "First song again", false)
        assertNull(DownloadFailureSignal.event.value)
    }

    @Test
    fun `a cached-songs failure says where the ask came from`() {
        DownloadFailureSignal.report("qobuz-web", "Qobuz", "Cached song", true)
        assertTrue(DownloadFailureSignal.event.value!!.wasCached)
    }

    private fun addon(key: String, name: String, enabled: Boolean = true) = InstalledMorneyePackage(
        key = key,
        name = name,
        displayName = name,
        version = "1",
        description = "",
        enabled = enabled,
        digest = "",
        installedAt = 0L,
    )

    private fun capabilities(
        downloads: Boolean = true,
        allowDownloads: Boolean = true,
    ) = MorneyeCapabilities(
        searches = true,
        downloads = downloads,
        qualityOptions = emptyList(),
        accessKind = null,
        allowDownloads = allowDownloads,
    )

    private fun morneye(key: String) = MorneyeSource(
        SourceConfig(kind = SourceKind.MORNYE, baseUrl = key, label = key),
    )

    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind,
    ) : MusicSource {
        override val displayName: String get() = configId
        override suspend fun health(): SourceHealth = SourceHealth.Ok()
        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
            target: TrackMatcher.Target?,
        ): List<Song> = emptyList()
        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = null
    }
}
