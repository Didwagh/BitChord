package com.music.bitchord.data.sources.mornye

/**
 * The addons a listener can sensibly be offered as a download source.
 *
 * Static eligibility only: enabled, installed, manifest says it exports a
 * `download` function, and its own policy allows downloads. Whether a given
 * song is actually in that addon's catalogue is the same expensive search the
 * download itself does, so the chooser does not pretend to know it in advance.
 */
internal data class DownloadAddonOption(
    val key: String,
    val displayName: String,
    val detail: String?,
)

internal object DownloadAddonCatalog {

    private const val GENERIC_LABEL = "Mornye extension"

    /** In the same order the Mornye screen lists them, which is source priority. */
    fun options(
        packages: List<InstalledMorneyePackage> = MorneyePackageManager.packages.value,
    ): List<DownloadAddonOption> = downloadableAddonOptions(
        packages,
        { key -> MorneyePackageManager.capabilities(key) },
    )

    /** Whether a saved addon key still names an addon that can export downloads. */
    fun hasUsableAddon(
        key: String,
        packages: List<InstalledMorneyePackage> = MorneyePackageManager.packages.value,
    ): Boolean = usableAddon(key, packages) { MorneyePackageManager.capabilities(it) }

    internal fun usableAddon(
        key: String,
        packages: List<InstalledMorneyePackage>,
        capabilities: (String) -> MorneyeCapabilities?,
    ): Boolean = packages.any { pkg ->
        pkg.key == key && pkg.enabled && capabilities(key)?.let { it.downloads && it.allowDownloads } == true
    }

    internal fun downloadableAddonOptions(
        packages: List<InstalledMorneyePackage>,
        capabilities: (String) -> MorneyeCapabilities?,
    ): List<DownloadAddonOption> = packages
        .filter { pkg ->
            pkg.enabled && capabilities(pkg.key)?.let { it.downloads && it.allowDownloads } == true
        }
        .map { pkg ->
            DownloadAddonOption(
                key = pkg.key,
                displayName = pkg.displayName.ifBlank { pkg.name }.ifBlank { GENERIC_LABEL },
                detail = when (morneyeProviderForAddon(pkg.key, pkg.displayName)) {
                    MorneyeProviderHint.QOBUZ -> "Qobuz"
                    else -> GENERIC_LABEL
                },
            )
        }

    /**
     * A name safe to show in a popup, with no identity or secret data.
     *
     * Reads the installed packages rather than the filtered chooser list so a
     * saved default whose addon was since disabled still shows its name.
     */
    fun displayName(
        key: String,
        packages: List<InstalledMorneyePackage> = MorneyePackageManager.packages.value,
    ): String = packages.firstOrNull { it.key == key }
        ?.let { pkg -> pkg.displayName.ifBlank { pkg.name }.ifBlank { GENERIC_LABEL } }
        ?: GENERIC_LABEL
}
