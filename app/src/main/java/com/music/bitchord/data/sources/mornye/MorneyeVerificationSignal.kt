package com.music.bitchord.data.sources.mornye

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * One user-actionable verification prompt for Mornye extensions.
 *
 * A single failed lookup can be reported dozens of times — ExoPlayer retries,
 * the resolver asks every candidate, and each track in a queue makes its own
 * request. Pushing all of that into the UI produced 92 identical prompts for
 * Qobuz in one short session. This keeps one event at a time and shows it at
 * most once per addon in [COOLDOWN_MS].
 *
 * Deliberately carries no reason, auth URL, callback state or session id: the
 * dialog only needs to know which extension to send the user to, and those
 * other values are either secret or useless on screen.
 */
internal object MorneyeVerificationSignal {

    data class Event(
        val addonKey: String,
        val displayName: String,
    )

    private const val COOLDOWN_MS = 10 * 60_000L

    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event.asStateFlow()

    private val lastShown = ConcurrentHashMap<String, Long>()

    /** Reports a verification-shaped failure. Safe to call once per failed lookup. */
    fun report(addonKey: String, displayName: String) {
        if (_event.value != null) return
        val now = System.currentTimeMillis()
        val previous = lastShown[addonKey] ?: 0L
        if (now - previous < COOLDOWN_MS) return
        lastShown[addonKey] = now
        _event.value = Event(addonKey, displayName)
    }

    /** Dismisses the current prompt without resetting its per-addon cooldown. */
    fun dismiss() {
        _event.value = null
    }

    /** Called after a confirmed reconnect, so a fresh failure can prompt again. */
    fun clear(addonKey: String) {
        lastShown.remove(addonKey)
        if (_event.value?.addonKey == addonKey) _event.value = null
    }

    /** Test hook. */
    fun clearAll() {
        lastShown.clear()
        _event.value = null
    }
}
