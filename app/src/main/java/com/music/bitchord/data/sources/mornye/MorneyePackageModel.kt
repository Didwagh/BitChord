package com.music.bitchord.data.sources.mornye

import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

@Serializable
data class InstalledMorneyePackage(
    val key: String,
    val name: String,
    val displayName: String,
    val version: String,
    val description: String,
    val enabled: Boolean = true,
    val digest: String,
    val installedAt: Long,
    val repositoryId: String? = null,
    val manifestJson: String = "{}",
)

data class MorneyeCapabilities(
    val searches: Boolean,
    val downloads: Boolean,
    val qualityOptions: List<MorneyeQualityOption>,
    val accessKind: String?,
)

data class MorneyeQualityOption(val id: String, val label: String)

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

data class MorneyeStream(
    val directUrl: String?,
    val headers: Map<String, String>,
    val fileType: String?,
    val isLossless: Boolean,
    val isAtmos: Boolean,
    val isSegmented: Boolean,
)

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

/** Download result from an addon's download() function. */
data class MorneyeDownloadResult(
    val success: Boolean,
    val filePath: String?,
    val format: String?,
    val errorMessage: String?,
)

private val LOSSLESS_TOKENS = setOf(
    "flac", "alac", "wav", "aiff", "aif", "pcm",
    "lossless", "hi-res", "hires", "hi_res",
    "flac-24", "flac-16", "96khz", "192khz", "24-bit", "16-bit flac",
)
private val ATMOS_TOKENS = setOf("atmos", "dolby", "eac3", "ec-3", "ec3", "joc")

/**
 * Parse a track map from addon JS output into a [MorneyeTrack].
 *
 * Handles field names from all major Mornye addons:
 * - JioSaavn:  name, artists (string), album_name, duration_ms, cover_url
 * - Deezer:    name, artists (string), album_name, duration_ms, cover_url
 * - TIDAL:     name, artists (string/array), album_name, duration_ms, cover_url / artwork
 * - Generic:   title, artist, album, thumbnail, artworkUrl, artwork
 */
internal fun parseMorneyeTrack(map: Map<*, *>): MorneyeTrack? {
    val id = map["id"]?.toString()?.trim() ?: return null
    val title = (map["name"] ?: map["title"])?.toString()?.trim() ?: return null
    if (id.isEmpty() || title.isEmpty()) return null

    // artists: may be a string "Artist A, Artist B" or a list/array
    val artist = when (val a = map["artists"] ?: map["artist"]) {
        is String -> a.trim()
        is List<*> -> a.filterIsInstance<Map<*, *>>()
            .mapNotNull { it["name"]?.toString() }
            .joinToString(", ")
            .ifBlank { a.firstOrNull()?.toString() ?: "" }
        is JSONArray -> (0 until a.length())
            .mapNotNull { i ->
                val item = a.opt(i)
                when (item) {
                    is JSONObject -> item.optString("name")
                    is String    -> item
                    else         -> item?.toString()
                }
            }.joinToString(", ")
        else -> a?.toString() ?: ""
    }

    // album: addon may use album_name, album, or nothing
    val album = (map["album_name"] ?: map["album"])?.toString()?.trim() ?: ""

    val durationMs = when (val d = map["duration_ms"] ?: map["durationMs"] ?: map["duration"]) {
        is Number -> d.toInt().let { if (it in 1..7200) it * 1000 else it }
        is String -> d.toIntOrNull()
        else      -> null
    }

    // artwork: cover_url (Deezer/JioSaavn), artworkUrl, artwork, thumbnail
    val artworkUrl = (map["cover_url"] ?: map["artworkUrl"] ?: map["artwork"]
        ?: map["thumbnail"] ?: map["thumbnail_url"] ?: map["image"])?.toString()

    val qualityLabel = (map["audio_quality"] ?: map["quality"] ?: map["audioQuality"]
        ?: map["fileType"])?.toString()
    val lossless = qualityLabel?.lowercase()?.let { q -> LOSSLESS_TOKENS.any { it in q } } == true
    val atmos    = qualityLabel?.lowercase()?.let { q -> ATMOS_TOKENS.any { it in q } } == true

    return MorneyeTrack(
        id           = id,
        title        = title,
        artist       = artist.trim(),
        album        = album,
        durationMs   = durationMs,
        artworkUrl   = artworkUrl,
        qualityLabel = qualityLabel,
        isLossless   = lossless,
        isAtmos      = atmos,
    )
}

/** Parse the return value from an addon's stream() / getDownloadUrl() call. */
internal fun parseMorneyeStream(result: Any?): MorneyeStream? {
    if (result !is Map<*, *>) return null
    val url = (result["url"] ?: result["streamUrl"])?.toString()?.trim()
    val hasSegments = result["segments"] is List<*> || result["segment_urls"] is List<*>
    @Suppress("UNCHECKED_CAST")
    val rawHeaders = (result["headers"] as? Map<*, *>)
        ?.mapNotNull { (k, v) -> if (k is String && v != null) k to v.toString() else null }
        ?.toMap() ?: emptyMap()
    val fileType     = (result["fileType"] ?: result["file_type"] ?: result["format"])?.toString()
    val qualityLabel = (result["quality"] ?: result["audioQuality"] ?: fileType)?.toString()
    val lossless     = qualityLabel?.lowercase()?.let { q -> LOSSLESS_TOKENS.any { it in q } } == true
    val atmos        = qualityLabel?.lowercase()?.let { q -> ATMOS_TOKENS.any { it in q } } == true
    if (url.isNullOrBlank() && !hasSegments) return null
    return MorneyeStream(
        directUrl  = url?.ifBlank { null },
        headers    = rawHeaders,
        fileType   = fileType,
        isLossless = lossless,
        isAtmos    = atmos,
        isSegmented = hasSegments && url.isNullOrBlank(),
    )
}

/** Parse the return value of an addon's download() call. */
internal fun parseMorneyeDownloadResult(result: Any?): MorneyeDownloadResult {
    if (result !is JSONObject) return MorneyeDownloadResult(false, null, null, "No result")
    val success = result.optBoolean("success", false)
    val filePath = result.optString("file_path", "").ifBlank { null }
        ?: result.optString("path", "").ifBlank { null }
    val format   = (result.optString("format", "").ifBlank { null }
        ?: result.optString("actual_extension", "").ifBlank { null })
    val error    = result.optString("error_message", "").ifBlank { null }
        ?: result.optString("error", "").ifBlank { null }
    return MorneyeDownloadResult(success, filePath, format, error)
}

internal fun capabilitiesOf(manifest: Map<String, Any?>): MorneyeCapabilities {
    @Suppress("UNCHECKED_CAST")
    val type = manifest["type"] as? List<*> ?: emptyList<Any>()
    val searches = type.any { it.toString() in setOf("download_provider", "metadata_provider") }
    @Suppress("UNCHECKED_CAST")
    val qualityRaw = manifest["qualityOptions"] as? List<*> ?: emptyList<Any>()
    val qualities = qualityRaw.filterIsInstance<Map<*, *>>().mapNotNull { q ->
        val id    = q["id"]?.toString() ?: return@mapNotNull null
        val label = q["label"]?.toString() ?: id
        MorneyeQualityOption(id, label)
    }
    val access = (manifest["access"] as? Map<*, *>)?.get("kind")?.toString()
    return MorneyeCapabilities(searches, searches, qualities, access)
}
