package com.music.bitchord.download

/**
 * A Mornye addon the listener asked downloads to come from.
 *
 * [strict] separates the two ways an addon can be chosen. A long press is a
 * one-shot, strict choice: if that addon cannot supply the track, the download
 * fails and says so instead of quietly turning into YouTube. The saved default
 * from Settings is a preference: it is tried first, but the ordinary resolver
 * is still allowed to answer when it cannot.
 *
 * Only the stable Mornye addon key is stored here. No session id, grant, auth
 * URL, callback state or key material ever rides along with a download.
 */
data class DownloadAddonChoice(
    val addonKey: String,
    val strict: Boolean,
)

/** Turns the saved setting into the softer, auto-fallback form of a choice. */
internal object DownloadAddonPreference {
    fun fromSaved(addonKey: String?): DownloadAddonChoice? =
        addonKey?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { DownloadAddonChoice(it, strict = false) }
}
