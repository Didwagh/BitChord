package com.music.bitchord.data.sources.mornye

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers addon candidates that just failed so they are not asked again
 * immediately.
 *
 * ExoPlayer retries a failed load three times, and [SourceResolver] re-asks
 * every source around the pinned one; without this, a TIDAL candidate that
 * spent 22–43s failing was asked again by the next retry. A provider outage or
 * a rejected ticket does not heal between those attempts. After [WINDOW_MS] the
 * candidate is allowed again, so a transient failure is not permanent.
 */
internal object MorneyeFailureCooldown {

    const val WINDOW_MS = 90_000L

    private val failures = ConcurrentHashMap<String, Long>()

    fun key(addonKey: String, trackId: String, qualityId: String?): String =
        "$addonKey\u0000$trackId\u0000${qualityId ?: "default"}"

    /** True when [key] failed less than [WINDOW_MS] ago. */
    fun isCoolingDown(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val at = failures[key] ?: return false
        if (now - at < WINDOW_MS) return true
        failures.remove(key)
        return false
    }

    fun record(key: String, now: Long = System.currentTimeMillis()) {
        failures[key] = now
    }

    fun clear(key: String) {
        failures.remove(key)
    }

    /**
     * Forgets every failed track for one extension. Called only after a
     * confirmed reconnect: dismissing a prompt or merely opening the connect
     * page is not proof that the session was repaired.
     */
    fun clearAddon(addonKey: String) {
        val prefix = "$addonKey\u0000"
        failures.keys.removeIf { it.startsWith(prefix) }
    }

    /** Test hook. */
    fun clearAll() = failures.clear()
}
