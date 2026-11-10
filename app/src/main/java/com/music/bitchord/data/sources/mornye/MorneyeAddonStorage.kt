package com.music.bitchord.data.sources.mornye

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.auth.EncryptedPrefs

/**
 * Isolated persistent storage for one Mornye addon.
 *
 * Backed by [EncryptedPrefs] (EncryptedSharedPreferences) so tokens survive
 * process restarts and are encrypted at rest. Each addon gets its own prefs
 * file, keyed by [addonKey], so they cannot collide.
 *
 * The Mornye bootstrap exposes two namespaces to addon JS:
 *  - `storage`     — arbitrary JSON (settings, caches, session tokens).
 *  - `credentials` — sensitive tokens, same backing store but `c:` prefix.
 */
class MorneyeAddonStorage(
    private val context: Context,
    private val addonKey: String,
) {
    private val prefs: SharedPreferences by lazy {
        // EncryptedPrefs.open(context, encryptedName, plaintextFallbackName)
        EncryptedPrefs.open(
            context,
            "mornye_$addonKey",
            "mornye_${addonKey}_plain",
        )
    }

    // ── Plain storage (addon's globalThis.storage) ────────────────────────

    fun get(key: String): String? = prefs.getString("s:$key", null)

    fun set(key: String, value: String) =
        prefs.edit().putString("s:$key", value).apply()

    fun remove(key: String) =
        prefs.edit().remove("s:$key").apply()

    fun clear() {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("s:") }.forEach(::remove)
        }.apply()
    }

    // ── Credential storage (addon's globalThis.credentials) ───────────────

    fun getCredential(key: String): String? = prefs.getString("c:$key", null)

    fun setCredential(key: String, value: String) =
        prefs.edit().putString("c:$key", value).apply()

    fun removeCredential(key: String) =
        prefs.edit().remove("c:$key").apply()

    val hasCredentials: Boolean
        get() = prefs.all.keys.any { it.startsWith("c:") }

    /** Wipes everything — called when the addon is removed. */
    fun purge() = prefs.edit().clear().apply()
}
