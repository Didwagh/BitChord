package com.music.bitchord.data.sources.mornye

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire and in-memory shapes for Mornye extension packages.
 *
 * A package is a ZIP archive (.sflx / .morneye-ext / .spotiflac-ext) with a
 * `manifest.json` and a JavaScript entry point. The same package format runs
 * on Lokal desktop and phone; this brings it to BitChord on Android.
 *
 * The manifest naming follows the Mornye spec published at
 * mornyeapp/Mornye-Extension so packages from the official registry install
 * without modification.
 */

@Serializable
data class InstalledMorneyePackage(
    /** SHA-1 of "spotiflac:<name>" — stable across updates, used as storage namespace. */
    val key: String,
    val name: String,
    val displayName: String,
    val version: String,
    val description: String,
    val enabled: Boolean = true,
    /** SHA-256 of the installed archive bytes; used to detect re-installs. */
    val digest: String,
    val installedAt: Long,
    /** Which registry entry this came from, if any. */
    val repositoryId: String? = null,
    /** Raw manifest JSON for fields we don't deserialise here. */
    val manifestJson: String = "{}",
)

/** What the package's manifest declares it can do. */
data class MorneyeCapabilities(
    val searches: Boolean,
    val downloads: Boolean,
    val qualityOptions: List<MorneyeQualityOption>,
    /** Whether the addon requires a login or verification step before use. */
    val accessKind: String?,   // "login" | "verification" | null
)

data class MorneyeQualityOption(
    val id: String,
    val label: String,
)

/** One search result row as the addon's JS returned it. */
data class MorneyeTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Int?,
    val artworkUrl: String?,
    val qualityLabel: String?,
    val isLossless: Boolean,
    val isAtmos: Boolean,
)

/**
 * The resolved stream after calling the addon's download() method.
 *
 * Simple addons return a [directUrl] the player opens immediately.
 * Complex addons (DASH, segment-based) indicate [isSegmented] and BitChord
 * will fall through to the next source.
 */
data class MorneyeStream(
    val directUrl: String?,
    val headers: Map<String, String>,
    val fileType: String?,
    val isLossless: Boolean,
    val isAtmos: Boolean,
    val isSegmented: Boolean,
)

/** A repository entry in the Mornye registry. */
data class MorneyeRegistryEntry(
    val id: String,
    val name: String,
    val displayName: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val compatible: Boolean,
    val installed: InstalledMorneyePackage?,
    val repositoryId: String,
    val category: String,
)

/** Codec / file-type tokens this integration recognises as lossless. */
private val LOSSLESS_TOKENS = setOf(
    "flac", "alac", "wav", "aiff", "aif", "pcm",
    "lossless", "hi-res", "hires", "hi_res",
    "flac-24", "flac-16", "96khz", "192khz",
)

private val ATMOS_TOKENS = setOf(
    "atmos", "dolby", "eac3", "ec-3", "ec3",
)

// ── JSON parsing helpers ──────────────────────────────────────────────────

internal val packageJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

internal fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotBlank() }?.content?.ifBlank { null }

internal fun JsonElement?.intOrNull(): Int? =
    (this as? JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonElement?.boolOrFalse(): Boolean =
    (this as? JsonPrimitive)?.content?.lowercase() in setOf("true", "1", "yes")

/** Parse a track map from addon JS output. */
internal fun parseMorneyeTrack(map: Map<*, *>): MorneyeTrack? {
    val id = map["id"]?.toString()?.trim() ?: return null
    val title = (map["name"] ?: map["title"])?.toString()?.trim() ?: return null
    if (id.isEmpty() || title.isEmpty()) return null

    val artist = when (val a = map["artists"] ?: map["artist"]) {
        is List<*> -> a.filterIsInstance<Map<*, *>>()
            .mapNotNull { it["name"]?.toString() }
            .joinToString(", ")
            .ifBlank { a.firstOrNull()?.toString() ?: "" }
        else -> a?.toString() ?: ""
    }

    val durationMs = when (val d = map["duration_ms"] ?: map["durationMs"] ?: map["duration"]) {
        is Number -> d.toInt().let { if (it > 10_000) it else it * 1000 }
        is String -> d.toIntOrNull()
        else -> null
    }

    val qualityLabel = (map["quality"] ?: map["audioQuality"] ?: map["fileType"])?.toString()
    val losslessHint = qualityLabel?.lowercase()?.let { q -> LOSSLESS_TOKENS.any { it in q } } == true
    val atmosHint = qualityLabel?.lowercase()?.let { q -> ATMOS_TOKENS.any { it in q } } == true

    return MorneyeTrack(
        id = id,
        title = title,
        artist = artist.trim(),
        album = map["album"]?.toString()?.trim() ?: "",
        durationMs = durationMs,
        artworkUrl = (map["artwork"] ?: map["artworkUrl"] ?: map["thumbnail"])?.toString(),
        qualityLabel = qualityLabel,
        isLossless = losslessHint,
        isAtmos = atmosHint,
    )
}

/** Parse a download result map from addon JS output. */
internal fun parseMorneyeStream(result: Any?): MorneyeStream? {
    if (result !is Map<*, *>) return null

    val url = (result["url"] ?: result["streamUrl"])?.toString()?.trim()
    val hasSegments = result["segments"] is List<*> || result["segment_urls"] is List<*>

    @Suppress("UNCHECKED_CAST")
    val rawHeaders = result["headers"] as? Map<String, String>
        ?: (result["headers"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            if (k is String && v != null) k to v.toString() else null
        }?.toMap()
        ?: emptyMap()

    val fileType = (result["fileType"] ?: result["file_type"] ?: result["format"])?.toString()
    val qualityLabel = (result["quality"] ?: result["audioQuality"] ?: fileType)?.toString()
    val lossless = qualityLabel?.lowercase()?.let { q -> LOSSLESS_TOKENS.any { it in q } } == true
    val atmos = qualityLabel?.lowercase()?.let { q -> ATMOS_TOKENS.any { it in q } } == true

    // If there is no direct URL and there are no segments we have nothing.
    if (url.isNullOrBlank() && !hasSegments) return null

    return MorneyeStream(
        directUrl = url?.ifBlank { null },
        headers = rawHeaders,
        fileType = fileType,
        isLossless = lossless,
        isAtmos = atmos,
        isSegmented = hasSegments && url.isNullOrBlank(),
    )
}

/** Capabilities extracted from the manifest JSON map. */
internal fun capabilitiesOf(manifest: Map<String, Any?>): MorneyeCapabilities {
    @Suppress("UNCHECKED_CAST")
    val type = manifest["type"] as? List<*> ?: emptyList<Any>()
    val searches = type.any { it.toString() in setOf("download_provider", "metadata_provider") }

    @Suppress("UNCHECKED_CAST")
    val qualityRaw = manifest["qualityOptions"] as? List<*> ?: emptyList<Any>()
    val qualities = qualityRaw.filterIsInstance<Map<*, *>>().mapNotNull { q ->
        val id = q["id"]?.toString() ?: return@mapNotNull null
        val label = q["label"]?.toString() ?: id
        MorneyeQualityOption(id, label)
    }

    val access = (manifest["access"] as? Map<*, *>)?.get("kind")?.toString()

    return MorneyeCapabilities(
        searches = searches,
        downloads = searches, // download_providers also search
        qualityOptions = qualities,
        accessKind = access,
    )
}
