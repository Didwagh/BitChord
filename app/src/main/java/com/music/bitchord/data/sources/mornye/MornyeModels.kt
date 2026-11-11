package com.music.bitchord.data.sources.mornye

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val MornyeJson = Json { isLenient = true; ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

/** registry.json — see Mornye repository/model.rs. snake_case and camelCase both accepted. */
@Serializable
data class MornyeRegistry(
    val version: Int = 0,
    val extensions: List<MornyeRepoEntry> = emptyList(),
)

@Serializable
data class MornyeRepoEntry(
    val id: String = "",
    val name: String = "",
    @SerialName("display_name") val displayNameSnake: String = "",
    val displayName: String = "",
    val version: String = "",
    val description: String = "",
    @SerialName("download_url") val downloadUrlSnake: String = "",
    val downloadUrl: String = "",
    val category: String = "",
    val runtime: String = "",
    val sha256: String = "",
    @SerialName("checksum_sha256") val checksumSnake: String = "",
    val checksumSha256: String = "",
) {
    val url: String get() = downloadUrlSnake.ifBlank { downloadUrl }
    val label: String get() = displayNameSnake.ifBlank { displayName.ifBlank { name.ifBlank { id } } }
    val checksum: String get() = sha256.ifBlank { checksumSnake.ifBlank { checksumSha256 } }
    /** Only JavaScript extensions run here; python/lua entries are skipped. */
    val isJs: Boolean get() = runtime.isBlank() || runtime.equals("javascript", ignoreCase = true)
}

/** manifest.json inside a .sflx package. */
@Serializable
data class MornyeManifest(
    val name: String = "",
    val displayName: String = "",
    val version: String = "",
    val description: String = "",
    val type: List<String> = emptyList(),
    val qualityOptions: List<MornyeQuality> = emptyList(),
) {
    val isDownloadProvider: Boolean get() = type.contains("download_provider")
    val label: String get() = displayName.ifBlank { name }
}

@Serializable
data class MornyeQuality(val id: String = "", val label: String = "", val description: String = "")

/** One searchTracks() row. Mornye returns an array of these (or {tracks:[...]}). */
@Serializable
data class MornyeTrack(
    val id: String = "",
    val name: String = "",
    val artists: String = "",
    @SerialName("album_name") val albumName: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("cover_url") val coverUrl: String = "",
    val images: String = "",
    val isrc: String = "",
    @SerialName("audio_quality") val audioQuality: String = "",
) {
    val cover: String get() = coverUrl.ifBlank { images }
    val durationSec: Int get() = (durationMs / 1000).toInt()
}

/** getDownloadUrl() result. */
@Serializable
data class MornyeStream(
    val success: Boolean = false,
    val url: String = "",
    val format: String = "",
    @SerialName("bit_depth") val bitDepth: Int = 0,
    @SerialName("sample_rate") val sampleRate: Int = 0,
    val error: String = "",
    @SerialName("error_type") val errorType: String = "",
)
