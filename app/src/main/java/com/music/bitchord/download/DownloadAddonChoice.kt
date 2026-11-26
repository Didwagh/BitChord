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

    /**
     * The addon an ordinary tap tries first.
     *
     * An explicit setting still wins. With nothing saved, the first addon the
     * chooser would offer is used instead: leaving it unset must not mean
     * "download YouTube while a connected addon has the song". This remains a
     * preference, not a strict pick — if the addon cannot supply the track,
     * the ordinary resolver and its YouTube floor still answer.
     *
     * [available] is the already-filtered chooser list, so a stale saved key or
     * an addon that lost its download capability cannot win here.
     */
    fun automatic(savedAddonKey: String?, available: List<String>): DownloadAddonChoice? {
        val saved = fromSaved(savedAddonKey)?.takeIf { it.addonKey in available }
        if (saved != null) return saved
        return available.firstOrNull()?.let { DownloadAddonChoice(it, strict = false) }
    }

    /**
     * Whether a tap should actually enqueue [videoId].
     *
     * Duplicate protection normally stops a second copy being written, but a
     * strict long press is a new request for a particular addon: an earlier
     * YouTube save must not make that choice a silent no-op.
     */
    fun shouldAttempt(choice: DownloadAddonChoice?, videoId: String, saved: Set<String>): Boolean =
        choice?.strict == true || videoId !in saved
}
