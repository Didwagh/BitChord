package com.music.bitchord

import com.music.bitchord.data.sources.mornye.InstalledMorneyePackage
import com.music.bitchord.data.sources.mornye.MorneyePackageManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Installed Mornye addons share one rank, so the persisted list order is the
 * priority [SourceRegistry.active][com.music.bitchord.data.sources.SourceRegistry.active]
 * sees. Reordering must be total for known keys and safe when the list has
 * changed since the screen was opened.
 */
class MorneyeOrderTest {

    private fun addon(key: String) = InstalledMorneyePackage(
        key = key,
        name = key,
        displayName = key,
        version = "1.0.0",
        description = "",
        digest = key,
        installedAt = 0L,
    )

    private val installed = listOf(addon("a"), addon("b"), addon("c"))

    @Test
    fun `known keys are reordered`() {
        val result = MorneyePackageManager.orderInstalledPackages(
            installed,
            listOf("c", "a", "b"),
        )
        assertEquals(listOf("c", "a", "b"), result.map { it.key })
    }

    @Test
    fun `missing keys are ignored and forgotten keys are appended`() {
        val result = MorneyePackageManager.orderInstalledPackages(
            installed,
            listOf("gone", "c"),
        )
        assertEquals(listOf("c", "a", "b"), result.map { it.key })
    }

    @Test
    fun `an unchanged order keeps the installed list intact`() {
        val result = MorneyePackageManager.orderInstalledPackages(
            installed,
            installed.map { it.key },
        )
        assertEquals(installed, result)
    }
}
