package com.music.bitchord

import com.music.bitchord.data.sources.mornye.MornyeJson
import com.music.bitchord.data.sources.mornye.MornyeManifest
import com.music.bitchord.data.sources.mornye.MornyePackage
import com.music.bitchord.data.sources.mornye.MornyeRegistry
import com.music.bitchord.data.sources.mornye.MornyeStream
import com.music.bitchord.data.sources.mornye.MornyeTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks for the Mornye wire formats and URL handling (no QuickJS needed). */
class MornyeIntegrationTest {
    @Test fun registry_snake_and_camel() {
        val body = """{"version":1,"extensions":[
            {"id":"deezer","download_url":"https://x/deezer.sflx","sha256":"abc"},
            {"id":"qobuz","downloadUrl":"https://x/qobuz.sflx","runtime":"javascript"},
            {"id":"py","downloadUrl":"https://x/py.sflx","runtime":"python"}]}"""
        val r = MornyeJson.decodeFromString(MornyeRegistry.serializer(), body)
        assertEquals(3, r.extensions.size)
        assertEquals("https://x/deezer.sflx", r.extensions[0].url)
        assertEquals("https://x/qobuz.sflx", r.extensions[1].url)
        assertTrue(r.extensions[0].isJs)
        assertFalse(r.extensions[2].isJs)
    }
    @Test fun github_repo_to_raw_registry() {
        assertEquals("https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/HEAD/registry.json",
            MornyePackage.normalizeRegistryUrl("https://github.com/mornyeapp/Mornye-Extension"))
        val raw = "https://raw.githubusercontent.com/a/b/main/registry.json"
        assertEquals(raw, MornyePackage.normalizeRegistryUrl(raw))
    }
    @Test fun manifest_download_provider() {
        val m = MornyeJson.decodeFromString(MornyeManifest.serializer(),
            """{"name":"deezer","displayName":"Deezer","type":["metadata_provider","download_provider"],
                "qualityOptions":[{"id":"FLAC","label":"FLAC Lossless"},{"id":"MP3_320","label":"320"}]}""")
        assertTrue(m.isDownloadProvider)
        assertEquals(2, m.qualityOptions.size)
    }
    @Test fun track_and_stream_shapes() {
        val t = MornyeJson.decodeFromString(MornyeTrack.serializer(),
            """{"id":"1","name":"Song","artists":"Artist","album_name":"Album","duration_ms":240000,"images":"http://art"}""")
        assertEquals(240, t.durationSec); assertEquals("http://art", t.cover)
        val s = MornyeJson.decodeFromString(MornyeStream.serializer(),
            """{"success":true,"url":"https://cdn/x.flac","format":"flac","bit_depth":24,"sample_rate":96000}""")
        assertTrue(s.success); assertEquals(24, s.bitDepth); assertEquals(96000, s.sampleRate)
    }
}
