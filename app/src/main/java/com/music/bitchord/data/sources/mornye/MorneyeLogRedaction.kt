package com.music.bitchord.data.sources.mornye

/**
 * Secrets an addon might put in a log line, removed before the line reaches the
 * diagnostics buffer.
 *
 * The host does not log credentials itself, but addons are third-party code and
 * a few of them log request context at info level. Diagnostics are meant to be
 * shared (the export is a plain text file), so the host redacts the common
 * shapes rather than trusting every bundle. Messages, URLs and status codes are
 * left intact — those are the parts worth debugging.
 */
private val SECRET_ASSIGNMENT = Regex(
    """(?i)\b(access[_-]?token|refresh[_-]?token|id[_-]?token|session[_-]?secret|""" +
        """client[_-]?secret|authorization|password|passwd|device[_-]?id|cookie|""" +
        """x-zarz-(?:session|signature))\b(\s*[:=]\s*["']?)([^\s"',;}&]{4,})""",
)

private val BEARER = Regex("""(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]{8,}""")

internal fun redactAddonLog(text: String): String =
    // Bearer first: otherwise the assignment rule consumes the word "Bearer"
    // as the value and leaves the token behind it untouched.
    text.replace(BEARER) { m -> "${m.groupValues[1]} <redacted>" }
        .replace(SECRET_ASSIGNMENT) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}<redacted>"
        }
