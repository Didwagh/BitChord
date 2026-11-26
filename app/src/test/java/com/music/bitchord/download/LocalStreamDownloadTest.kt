package com.music.bitchord.download

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * An addon's finished copy arrives as a `file://` stream. Downloads used to
 * give that URL to OkHttp, which rejects the scheme, so even a successfully
 * cached FLAC was thrown out before it could be copied to Music/BitChord.
 * Playback needed the same lesson already; these pin the download half of it.
 */
class LocalStreamDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun resetRoots() = Downloader.installRoots(emptyList())

    @Test
    fun `a file inside the app cache is probed and copied`() = runBlocking {
        val cache = tmp.newFolder("mornye")
        val file = File(cache, "qobuz_123.flac")
        val bytes = ByteArray(4096) { if (it < 4) "fLaC"[it].code.toByte() else it.toByte() }
        file.writeBytes(bytes)
        Downloader.installRoots(listOf(cache))

        val probe = Downloader.probe(file.toURI().toString(), emptyMap())
        assertTrue(probe is Downloader.Probe.File)
        val fileProbe = probe as Downloader.Probe.File
        assertEquals(Downloader.Container.FLAC, fileProbe.container)
        assertEquals(bytes.size.toLong(), fileProbe.totalBytes)

        val output = ByteArrayOutputStream()
        val written = Downloader.fetchDirect(file.toURI().toString(), emptyMap(), output) { _, _ -> }
        assertEquals(bytes.size.toLong(), written)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun `a file outside the allow-list is never read`() = runBlocking {
        val cache = tmp.newFolder("mornye")
        val outside = File(tmp.root, "not-a-stream.flac")
        val bytes = ByteArray(512) { if (it < 4) "fLaC"[it].code.toByte() else it.toByte() }
        outside.writeBytes(bytes)
        Downloader.installRoots(listOf(cache))

        assertNull(Downloader.probe(outside.toURI().toString(), emptyMap()))
        try {
            Downloader.fetchDirect(outside.toURI().toString(), emptyMap(), ByteArrayOutputStream()) { _, _ -> }
            fail("an out-of-scope file was copied")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("outside"))
        }
    }
}
