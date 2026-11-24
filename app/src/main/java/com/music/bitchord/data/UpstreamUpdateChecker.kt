package com.music.bitchord.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * Metadata-only notice for new upstream BitChord releases.
 *
 * This fork must never download or install upstream APKs: both apps share the
 * same package id, so an upstream install would replace the fork and drop the
 * Qobuz/Amazon additions. The checker reads only release metadata and produces
 * a prompt the user can hand to OpenCode to sync the fork safely.
 *
 * Upstream and fork version numbers have diverged, so the notice keys off the
 * upstream release id rather than comparing versions. Every new upstream tag
 * is shown once; dismissing it remembers that id.
 */
object UpstreamUpdateChecker {

    data class UpstreamRelease(
        val id: String,
        val tag: String,
        val releaseUrl: String,
        val notes: String?,
    )

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/kushagrasinghx/BitChord/releases/latest"
    private const val PREFS_NAME = "upstream_update_notice"
    private const val KEY_SEEN_ID = "seen_release_id"

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpstreamRelease?>(null)
    val available = _available.asStateFlow()

    suspend fun check(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(LATEST_RELEASE_URL).build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            } ?: return@runCatching
            val release = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
            val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
            val id = release["id"]?.jsonPrimitive?.contentOrNull ?: tag
            if (seenId(context) == id) return@runCatching
            _available.value = UpstreamRelease(id, tag, url, release["body"]?.jsonPrimitive?.contentOrNull)
        }
    }

    /** Called when the user acts on or dismisses the notice. */
    fun dismiss(context: Context) {
        _available.value?.let { release ->
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SEEN_ID, release.id)
                .apply()
        }
        _available.value = null
    }

    fun openCodePrompt(release: UpstreamRelease): String = buildString {
        appendLine("Upstream BitChord ${release.tag} is available at ${release.releaseUrl}.")
        appendLine()
        appendLine("In this fork repo (/data/data/com.termux/files/home/BitChord):")
        appendLine("1. Fetch upstream kushagrasinghx/BitChord main and attempt a merge into main.")
        appendLine("2. If there are conflicts, resolve them while preserving this fork's Qobuz/Amazon/Mornye support and keeping AppUpdateChecker pointed at Didwagh/BitChord only. Never install upstream APKs.")
        appendLine("3. Bump versionName/versionCode in build.gradle.kts.")
        appendLine("4. Run git diff --check and available tests, commit, push origin main.")
        appendLine("5. Wait for Android Release APK CI to succeed, then publish a new GitHub release under Didwagh/BitChord with all four APK assets.")
        appendLine("6. Verify the latest release points at Didwagh/BitChord and report the new version/tag.")
        val summary = release.notes
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
            ?.take(180)
        if (!summary.isNullOrBlank()) {
            appendLine()
            append("Upstream note: ")
            append(summary)
        }
    }

    private fun seenId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SEEN_ID, null)
}
