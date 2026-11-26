package com.music.bitchord.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * One in-app notice for a failed, explicitly chosen addon download.
 *
 * A forty-track album picked from one addon must not produce forty popups, and
 * the record deliberately holds no URL, grant, session id, callback state or
 * raw provider error. The title and addon name are what the listener needs to
 * recognise the song and go fix the extension.
 */
internal object DownloadFailureSignal {

    data class Event(
        val addonKey: String,
        val addonName: String,
        val title: String,
        val wasCached: Boolean,
    )

    private const val COOLDOWN_MS = 60_000L

    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event.asStateFlow()

    private val lastShown = ConcurrentHashMap<String, Long>()

    /** Safe to call once per failed track; only the first one per addon is shown. */
    fun report(addonKey: String, addonName: String, title: String, wasCached: Boolean) {
        if (_event.value != null) return
        val now = System.currentTimeMillis()
        val previous = lastShown[addonKey] ?: 0L
        if (now - previous < COOLDOWN_MS) return
        lastShown[addonKey] = now
        _event.value = Event(addonKey, addonName, title, wasCached)
    }

    /** Dismisses the prompt without resetting its per-addon cooldown. */
    fun dismiss() {
        _event.value = null
    }

    /** Test hook. */
    fun clearAll() {
        lastShown.clear()
        _event.value = null
    }
}
