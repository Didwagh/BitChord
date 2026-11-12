package com.music.bitchord.data.sources

/**
 * Helpers for the composite track key that [SourceRegistry.trackKey] builds and
 * [SourceRegistry.parseTrackKey] unpacks.
 *
 * The format is `src:<configId>::<trackId>`, where `::` is the separator.
 * This helper is kept here rather than inlined so both [SourceRegistry] and
 * [MorneyeSource] can call the same logic without duplicating it.
 */
internal object SourceTrackKeys {

    private const val PREFIX = "src:"
    private const val SEPARATOR = "::"

    /**
     * Returns the `(configId, trackId)` pair for a composite track key, or
     * null when [key] is a plain YouTube video id that has never been through
     * [SourceRegistry.trackKey].
     */
    fun parse(key: String): Pair<String, String>? {
        if (!key.startsWith(PREFIX)) return null
        val body = key.removePrefix(PREFIX)
        val sep = body.indexOf(SEPARATOR)
        if (sep < 0) return null
        val configId = body.substring(0, sep)
        val trackId = body.substring(sep + SEPARATOR.length)
        return if (configId.isNotEmpty() && trackId.isNotEmpty()) configId to trackId else null
    }
}

