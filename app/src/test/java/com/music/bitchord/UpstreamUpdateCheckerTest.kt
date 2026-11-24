package com.music.bitchord

import com.music.bitchord.data.UpstreamUpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The upstream notice must stay a prompt, never a source of upstream APKs. */
class UpstreamUpdateCheckerTest {

    @Test
    fun `prompt asks OpenCode to sync the fork without upstream APKs`() {
        val release = UpstreamUpdateChecker.UpstreamRelease(
            id = "123",
            tag = "v1.10",
            releaseUrl = "https://github.com/kushagrasinghx/BitChord/releases/tag/v1.10",
            notes = "Upstream notes\n# Heading",
        )

        val prompt = UpstreamUpdateChecker.openCodePrompt(release)

        assertTrue(prompt.contains("kushagrasinghx/BitChord"))
        assertTrue(prompt.contains("Didwagh/BitChord"))
        assertTrue(prompt.contains("Bump versionName/versionCode"))
        assertTrue(prompt.contains("Upstream note: Upstream notes"))
        assertFalse(prompt.contains("browser_download_url"))
        assertFalse(prompt.contains(".apk"))
    }
}
