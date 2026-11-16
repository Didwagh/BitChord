package com.music.bitchord.data.sources.mornye

import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

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

data class MorneyeQualityOption(
    val id: String,
    val label: String,
    /** What the manifest says this tier actually is; null when it says nothing. */
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    /** True only when the manifest says so, or names a lossless codec. */
    val isLossless: Boolean = false,
)

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
    /** What the addon says it actually wrote, when it says more than an extension. */
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    /** The tier the addon reports having delivered, e.g. `lossless` or `high`. */
    val quality: String? = null,
)

private val LOSSLESS_TOKENS = setOf(
    "flac", "alac", "wav", "aiff", "aif", "pcm",
    "lossless", "hi-res", "hires", "hi_res",
    // A bare `24-bit` or `96 kHz` is a *resolution*, not a statement that the
    // codec is bit-exact: a 96 kHz Opus stream is still Opus. Those labels are
    // left to the codec, which the bytes settle. See [buildStream].
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
    val quality = (result.optString("quality", "").ifBlank { null }
        ?: result.optString("actual_quality", "").ifBlank { null }
        ?: result.optString("audio_quality", "").ifBlank { null })
    // Addons report what they wrote under several names. Take the first that
    // parses rather than preferring one spelling: a catalogue that says
    // `bit_depth` and one that says `bitDepth` mean the same thing.
    val kbps = sequenceOf("kbps", "bitrate", "bit_rate", "bitrate_kbps")
        .mapNotNull { key -> result.opt(key)?.let(::kbpsOf) }
        .firstOrNull()
    val sampleRate = sequenceOf("sample_rate", "sampleRate", "sample_rate_hz", "sampling_rate")
        .mapNotNull { key -> result.opt(key)?.let(::sampleRateOf) }
        .firstOrNull()
    val bitDepth = sequenceOf("bit_depth", "bitDepth", "bits_per_sample", "bitsPerSample")
        .mapNotNull { key -> result.opt(key)?.let(::bitDepthOf) }
        .firstOrNull()
    val codec = (result.optString("codec", "").ifBlank { null }
        ?: result.optString("audio_codec", "").ifBlank { null })
        ?.lowercase()?.let(::normaliseCodec)
    return MorneyeDownloadResult(
        success = success,
        filePath = filePath,
        format = format,
        errorMessage = error,
        codec = codec,
        kbps = kbps,
        sampleRateHz = sampleRate,
        bitDepth = bitDepth,
        quality = quality,
    )
}

/**
 * Reads an array from a manifest value.
 *
 * A manifest reaches this function two ways: straight from `JSONObject` while
 * a package is installed (`JSONArray`, `JSONObject`) and from a test or a
 * Kotlin-built map (`List`, `Map`). Accepting only the latter made every real
 * addon report an empty `type` and no quality tiers, so playback always asked
 * for the addon's `default` and never saw the lossless tier the manifest
 * declared.
 */
private fun manifestArray(value: Any?): List<Any?> = when (value) {
    is JSONArray -> (0 until value.length()).map { value.opt(it) }
    is List<*>   -> value
    else         -> emptyList()
}

private fun manifestObject(value: Any?): Map<*, *>? = when (value) {
    is JSONObject -> value.keys().asSequence().associateWith { value.opt(it) }
    is Map<*, *>  -> value
    else          -> null
}

internal fun capabilitiesOf(manifest: Map<String, Any?>): MorneyeCapabilities {
    val type = manifestArray(manifest["type"])
    val searches = type.any { it.toString() in setOf("download_provider", "metadata_provider") }
    val qualityRaw = manifestArray(manifest["qualityOptions"])
    val qualities = qualityRaw.mapNotNull { raw ->
        val q = manifestObject(raw) ?: return@mapNotNull null
        val id    = q["id"]?.toString() ?: return@mapNotNull null
        val label = q["label"]?.toString() ?: id
        val codec = (q["codec"] ?: q["format"] ?: q["fileType"])?.toString()
            ?.lowercase()?.let(::normaliseCodec)
        // Mornye manifests state the tier's family as `kind` (`lossless`,
        // `lossy`, `spatial`) far more often than they state `lossless: true`.
        // A family is a claim like a label is; the bytes still decide the
        // format that is reported.
        val kind = q["kind"]?.toString()?.lowercase()
        val explicitLossless = q["lossless"] as? Boolean
            ?: (q["isLossless"] as? Boolean)
            ?: (q["is_lossless"] as? Boolean)
        val isLossless = explicitLossless ?: when (kind) {
            "lossless"         -> true
            "lossy", "spatial" -> false
            else               -> isLosslessClaim(codec, label)
        }
        val kbps = sequenceOf("kbps", "bitrate", "bit_rate", "bitrate_kbps")
            .mapNotNull { key -> q[key]?.let(::kbpsOf) }
            .firstOrNull() ?: kbpsFromLabel(label)
        val sampleRate = sequenceOf("sample_rate", "sampleRate", "sample_rate_hz", "sampling_rate")
            .mapNotNull { key -> q[key]?.let(::sampleRateOf) }
            .firstOrNull() ?: sampleRateFromLabel(label)
        val bitDepth = sequenceOf("bit_depth", "bitDepth", "bits_per_sample", "bitsPerSample")
            .mapNotNull { key -> q[key]?.let(::bitDepthOf) }
            .firstOrNull() ?: bitDepthFromLabel(label)
        MorneyeQualityOption(
            id = id,
            label = label,
            codec = codec,
            kbps = kbps,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            isLossless = isLossless,
        )
    }
    val access = manifestObject(manifest["access"])?.get("kind")?.toString()
    // A metadata-only addon (Apple Music, Spotify) can search but has no
    // download() to hand playback a file. Reporting searches as downloads made
    // every search row look playable and sent prefetch into a `download` call
    // that could only answer "no_method".
    val downloads = type.any { it.toString() == "download_provider" }
    return MorneyeCapabilities(searches, downloads, qualities, access)
}

// ── Quality metadata ────────────────────────────────────────────────────────

private val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff", "pcm")
private val ATMOS_CODECS = setOf("eac3-joc", "ec3-joc", "dolby-atmos", "eac3")

/** The names catalogues use for a codec, folded onto the ones [StreamFormat] knows. */
internal fun normaliseCodec(raw: String): String = when (raw.trim().lowercase().removePrefix(".")) {
    "flac"                    -> "flac"
    "alac", "apple lossless"  -> "alac"
    "wav", "wave", "pcm"      -> "wav"
    "aiff", "aif"             -> "aiff"
    "mp3", "mpeg", "mpeg-1"   -> "mp3"
    "aac", "m4a", "mp4a"      -> "aac"
    "opus"                    -> "opus"
    "vorbis", "ogg"           -> "vorbis"
    "eac3", "ec-3", "ec3"     -> "eac3"
    "eac3-joc", "ec3-joc", "joc", "atmos", "dolby atmos" -> "eac3-joc"
    else                      -> raw.trim().lowercase().removePrefix(".")
}

/** Whether [codec] names a bit-exact format. */
internal fun isLosslessCodec(codec: String?): Boolean = codec != null && codec in LOSSLESS_CODECS

/** Whether a label or codec *claims* lossless. A claim is all this can be. */
internal fun isLosslessClaim(codec: String?, label: String?): Boolean {
    if (isLosslessCodec(codec)) return true
    val l = label?.lowercase().orEmpty()
    return LOSSLESS_TOKENS.any { it in l }
}

internal fun isAtmosClaim(codec: String?, label: String?): Boolean {
    if (codec != null && codec in ATMOS_CODECS) return true
    val l = label?.lowercase().orEmpty()
    return ATMOS_TOKENS.any { it in l }
}

/** `320kbps`, `320 kbps`, `320` → 320. Null when the text carries no number. */
internal fun kbpsOf(value: Any?): Int? = when (value) {
    is Number -> value.toInt().takeIf { it in 8..10_000 }
    is String -> {
        val m = Regex("(\\d{2,5})\\s*(?:kbps|kb/s|kbit|k)?", RegexOption.IGNORE_CASE)
            .find(value)
        m?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 8..10_000 }
    }
    else -> null
}

/**
 * `44100`, `44.1 kHz`, `44.1` → 44100.
 *
 * A bare number below 400 is read as kilohertz, because no real audio sample
 * rate is 44 Hz and every catalogue that writes `24/96` means thousands.
 */
internal fun sampleRateOf(value: Any?): Int? {
    val number = when (value) {
        is Number -> value.toDouble()
        is String -> Regex("(\\d+(?:\\.\\d+)?)").find(value)?.groupValues?.get(1)?.toDoubleOrNull()
        else -> null
    } ?: return null
    val hz = if (number in 1.0..400.0) number * 1000.0 else number
    return hz.toInt().takeIf { it in 8_000..768_000 }
}

/** `24`, `24-bit`, `bitDepth: 24` → 24. */
internal fun bitDepthOf(value: Any?): Int? = when (value) {
    is Number -> value.toInt().takeIf { it in 8..64 }
    is String -> Regex("(\\d{1,2})").find(value)?.groupValues?.get(1)?.toIntOrNull()
        ?.takeIf { it in 8..64 }
    else -> null
}

/**
 * Bitrate from a human label, but only when the label says so.
 *
 * A bare scan of every number in `24-bit / 96 kHz FLAC` finds 24 and 96 and
 * calls them a bitrate and a sample rate in kilohertz, which is the exact kind
 * of invention this integration must not do. Units decide.
 */
internal fun kbpsFromLabel(label: String): Int? {
    val lower = label.lowercase(Locale.ROOT)
    if (!Regex("\\d{2,5}\\s*(kbps|kb/s|kbit|kb)").containsMatchIn(lower)) return null
    return kbpsOf(label)?.takeIf { it >= 32 }
}

/** Sample rate from a label: `44.1 kHz`, `96khz`, `24/96`, `16/44.1`. */
internal fun sampleRateFromLabel(label: String): Int? {
    val lower = label.lowercase(Locale.ROOT)
    // A kilohertz number is the one immediately before the unit, not the first
    // number in the label: `24-bit / 96 kHz` has a bit depth in front of it.
    Regex("(\\d+(?:\\.\\d+)?)\\s*k?hz").find(lower)?.let { m ->
        val number = m.groupValues[1].toDoubleOrNull() ?: return@let
        val hz = (if (number in 1.0..400.0) number * 1000.0 else number).toInt()
        if (hz in 8_000..768_000) return hz
    }
    // A slash pair is the conventional "bits/rate" or "rate/bits" shorthand.
    Regex("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)").find(lower)?.let { m ->
        listOfNotNull(m.groupValues[1].toDoubleOrNull(), m.groupValues[2].toDoubleOrNull())
            .firstOrNull { it in 32.0..768.0 }
            ?.let { return (it * 1000).toInt() }
    }
    return null
}

/** Bit depth from a label, only when it says "bit". */
internal fun bitDepthFromLabel(label: String): Int? {
    if ("bit" !in label.lowercase(Locale.ROOT)) return null
    return bitDepthOf(label)
}
