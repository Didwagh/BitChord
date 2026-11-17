package com.music.bitchord

import com.music.bitchord.data.sources.mornye.MorneyePackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The install gate is `compareVersions(manifest.minAppVersion, COMPAT_VERSION) <= 0`.
 * Qobuz and Beatport both declare 5.3.0, so a regression to 5.1.0 silently
 * rejects them before a single request is made — exactly the "Qobuz won't
 * install" symptom. These pin both the comparison and the advertised version.
 */
class MorneyeCompatVersionTest {

    private val compat: String = run {
        val field = MorneyePackageManager::class.java
            .getDeclaredField("COMPAT_VERSION")
            .apply { isAccessible = true }
        field.get(MorneyePackageManager) as String
    }

    @Test
    fun `the runtime advertises at least 5_3_0`() {
        assertTrue(
            "COMPAT_VERSION=$compat must be >= 5.3.0 for qobuz-web and beatport",
            MorneyePackageManager.compareVersions(compat, "5.3.0") >= 0,
        )
    }

    @Test
    fun `an addon at the advertised version installs`() {
        assertTrue(MorneyePackageManager.compareVersions(compat, compat) <= 0)
    }

    @Test
    fun `an addon that needs a newer runtime is rejected`() {
        assertTrue(MorneyePackageManager.compareVersions("5.4.0", compat) > 0)
    }

    @Test
    fun `version comparison is numeric not lexicographic`() {
        assertEquals(1, MorneyePackageManager.compareVersions("5.10.0", "5.9.0"))
        assertEquals(-1, MorneyePackageManager.compareVersions("5.3.0", "5.10.0"))
        assertEquals(0, MorneyePackageManager.compareVersions("5.3", "5.3.0"))
    }
}
