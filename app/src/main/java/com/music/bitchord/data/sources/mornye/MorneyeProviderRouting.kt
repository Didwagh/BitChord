package com.music.bitchord.data.sources.mornye

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Provider-specific URL and availability contracts shared by the Mornye
 * download addons.
 *
 * These helpers are deliberately pure (apart from the tiny retry driver at the
 * bottom) so the exact method names and return shapes can be tested without a
 * JavaScript engine. Method names are matched exactly: QuickJS calls
 * `ext['name']`, so "normalising" `handleURL` to `handleUrl` would silently
 * miss an addon that exported only one of them.
 */
internal enum class MorneyeProviderHint(val directIdField: String) {
    DEEZER("deezer_id"),
    TIDAL("tidal_id"),
    QOBUZ("qobuz_id"),
}

/** Recognises only URLs this layer can honestly route to an addon. */
internal fun morneyeUrlProvider(query: String): MorneyeProviderHint? {
    val url = query.trim()
    if (url.isBlank() || url.any { it.isWhitespace() }) return null
    val lower = url.lowercase(Locale.ROOT)
    if (lower.startsWith("deezer:")) return MorneyeProviderHint.DEEZER
    if (lower.startsWith("tidal:")) return MorneyeProviderHint.TIDAL
    if (lower.startsWith("qobuzapp://") || lower.startsWith("qobuz:track:") ||
        lower.startsWith("qobuz:album:") || lower.startsWith("qobuz:artist:") ||
        lower.startsWith("qobuz:playlist:")
    ) return MorneyeProviderHint.QOBUZ
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null

    val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
    return when {
        host == "deezer.com" || host == "www.deezer.com" ||
            host == "deezer.page.link" || host == "link.deezer.com" ||
            host.endsWith(".deezer.com") -> MorneyeProviderHint.DEEZER

        host == "tidal.com" || host == "www.tidal.com" ||
            host == "listen.tidal.com" || host.endsWith(".tidal.com") -> MorneyeProviderHint.TIDAL

        // Mirrors the addon's parseURL: no wildcard subdomains here.
        host == "qobuz.com" || host == "www.qobuz.com" ||
            host == "play.qobuz.com" || host == "open.qobuz.com" -> MorneyeProviderHint.QOBUZ

        else -> null
    }
}

/**
 * Exact exports to try, in order. Deezer has shipped both spellings; Tidal and
 * Qobuz ship `handleUrl`. Membership is checked with `in methods`, so the
 * runtime's case-sensitive lookup is never second-guessed.
 */
internal fun morneyeUrlHandlers(provider: MorneyeProviderHint): List<String> =
    when (provider) {
        MorneyeProviderHint.DEEZER -> listOf("handleURL", "handleUrl")
        MorneyeProviderHint.TIDAL -> listOf("handleUrl")
        MorneyeProviderHint.QOBUZ -> listOf("handleUrl")
    }

internal fun selectMorneyeUrlHandler(
    methods: List<String>,
    provider: MorneyeProviderHint,
): String? = morneyeUrlHandlers(provider).firstOrNull { it in methods }

/** Identifies the addons whose availability contract we actually implement. */
internal fun morneyeProviderForAddon(
    addonKey: String,
    displayName: String = "",
): MorneyeProviderHint? {
    val key = addonKey.lowercase(Locale.ROOT)
    val label = displayName.lowercase(Locale.ROOT)
    return when {
        key == "deezer" || key.contains("deezer") || label == "deezer" ->
            MorneyeProviderHint.DEEZER
        key == "tidal-web" || key.contains("tidal") || label == "tidal" ->
            MorneyeProviderHint.TIDAL
        key == "qobuz-web" || key.contains("qobuz") || label == "qobuz" ->
            MorneyeProviderHint.QOBUZ
        else -> null
    }
}

/** Converts addon-shaped JSON into plain Kotlin containers for safe reuse. */
internal fun normaliseJsonLike(value: Any?): Any? = when (value) {
    is JSONObject -> value.keys().asSequence()
        .associateWith { normaliseJsonLike(value.opt(it)) }
    is JSONArray -> (0 until value.length()).map { normaliseJsonLike(value.opt(it)) }
    is Map<*, *> -> value.mapKeys { it.key.toString() }.mapValues { normaliseJsonLike(it.value) }
    is List<*> -> value.map { normaliseJsonLike(it) }
    else -> value
}

internal fun morneyeResultMap(value: Any?): Map<*, *>? =
    normaliseJsonLike(value) as? Map<*, *>

private fun firstText(vararg values: Any?): String? = values.firstNotNullOfOrNull { value ->
    value?.toString()?.trim()?.takeIf {
        it.isNotBlank() && it.lowercase(Locale.ROOT) != "null"
    }
}

private fun flagOf(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is Number -> value.toDouble() != 0.0
    is String -> when (value.trim().lowercase(Locale.ROOT)) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
    else -> null
}

/** A verification failure needs a user action, not a retry or a false empty result. */
internal fun morneyeVerificationRequired(error: Throwable?): Boolean {
    val message = error?.message.orEmpty()
    return message.contains("VERIFY_REQUIRED", ignoreCase = true) ||
        message.contains("verification_required", ignoreCase = true)
}

/**
 * Surfaces the `{success:false,error}` shape that `MorneyeRuntime.invoke()`
 * returns as a *value*. A negative availability answer is not a call error: it
 * carries `available:false` and a reason, and is parsed separately.
 */
internal fun morneyeCallError(value: Any?): String? {
    val map = morneyeResultMap(value) ?: return null
    val success = flagOf(map["success"])
    val error = firstText(map["error_message"], map["error"], map["reason"], map["message"])
    if (success == false) return error ?: "Addon call failed"
    if (success == null && error != null &&
        map["type"] == null && map["track"] == null && map["available"] == null
    ) return error
    return null
}

/** Only a `{type:"track",track:{…}}` answer is a playable URL result. */
internal fun morneyeUrlTrack(value: Any?): Map<*, *>? {
    val map = morneyeResultMap(value) ?: return null
    if (morneyeCallError(map) != null || flagOf(map["success"]) == false) return null
    if (map["type"]?.toString()?.trim()?.lowercase(Locale.ROOT) != "track") return null
    return morneyeResultMap(map["track"])
}

internal data class MorneyeAvailability(
    val available: Boolean = false,
    val trackId: String? = null,
    val preparedContext: Map<*, *>? = null,
    val reason: String? = null,
)

internal fun parseMorneyeAvailability(value: Any?): MorneyeAvailability {
    val map = morneyeResultMap(value)
        ?: return MorneyeAvailability(reason = "Addon gave no availability answer")
    val callError = morneyeCallError(map)
    return MorneyeAvailability(
        available = callError == null && flagOf(map["available"]) == true,
        trackId = firstText(map["track_id"], map["trackId"]),
        preparedContext = morneyeResultMap(map["prepared_context"] ?: map["preparedContext"]),
        reason = firstText(callError, map["reason"], map["error"], map["error_message"]),
    )
}

/** Matches Lokal's rule: any negative answer may be retried once after enrichment. */
internal fun shouldRetryMorneyeAvailability(
    availability: MorneyeAvailability,
    hasEnrichment: Boolean,
): Boolean = !availability.available && hasEnrichment

/**
 * Cheap driver for the ordering: check, optionally enrich, check again once.
 * The caller owns the merge so the second check sees the enriched track.
 */
internal suspend fun resolveMorneyeAvailability(
    check: suspend () -> MorneyeAvailability,
    enrich: (suspend () -> Unit)? = null,
): MorneyeAvailability {
    val first = check()
    if (!shouldRetryMorneyeAvailability(first, enrich != null)) return first
    enrich?.invoke()
    return check()
}

private fun meaningfulMorneyeValue(value: Any?): Boolean = when (value) {
    null -> false
    is String -> value.isNotBlank() && value.lowercase(Locale.ROOT) != "null"
    is Collection<*> -> value.isNotEmpty()
    is Map<*, *> -> value.isNotEmpty()
    is Number -> value.toDouble() != 0.0
    else -> true
}

/** Enrichment replaces with meaningful values, as Lokal does; blanks are ignored. */
internal fun mergeMorneyeTrack(base: Map<*, *>, enriched: Map<*, *>): Map<*, *> {
    val merged = LinkedHashMap<Any?, Any?>(base)
    enriched.forEach { (key, value) ->
        if (meaningfulMorneyeValue(value)) merged[key] = value
    }
    return merged
}

/**
 * The fourth argument to `checkAvailability(isrc, name, artists, options)`.
 * The provider id stays exactly as the addon issued it; no prefix stripping or
 * reformatting happens here.
 */
internal fun morneyeAvailabilityOptions(
    provider: MorneyeProviderHint,
    track: Map<*, *>,
    trackId: String,
    durationMs: Int?,
): Map<String, Any?> = linkedMapOf(
    "track" to track,
    "duration_ms" to (durationMs ?: 0),
    provider.directIdField to trackId,
    "track_id" to trackId,
)
