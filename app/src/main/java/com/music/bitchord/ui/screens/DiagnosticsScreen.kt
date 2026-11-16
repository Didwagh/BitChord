package com.music.bitchord.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings → Diagnostics: the log [TrackLog] keeps, as a page rather than a
 * clipboard paste.
 *
 * Copy Log on a playing song answers "what happened to this track". This
 * answers "what happened at all" — the whole buffer, with a severity and a tag
 * on every line, filterable to Mornye's own lines when an addon is the thing
 * being diagnosed. It reads the same in-memory buffer, so opening it can't
 * disturb playback and nothing here asks the system for logcat access.
 *
 * Copying one line is a click on that line. Export writes the whole (filtered)
 * log to a text file and hands it to the share sheet, because a half-megabyte
 * paste is over the binder limit and gets silently truncated.
 */
@Composable
fun DiagnosticsScreen(
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf(DiagFilter.ALL) }
    var entries by remember { mutableStateOf<List<TrackLog.Entry>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    // Re-read on every filter change, and keep reading while the page is open
    // so a track started in the background shows up without a manual refresh.
    LaunchedEffect(filter) {
        while (true) {
            entries = TrackLog.snapshot(minLevel = filter.minLevel, tag = filter.tag)
            delay(1_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    "Diagnostics",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "How this session resolved and played each track — the same record " +
                        "Copy Log takes for one song. Held in memory only, and never sent " +
                        "anywhere unless you share it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                SegmentedControl(
                    options = DiagFilter.entries.map { it.label },
                    selectedIndex = filter.ordinal,
                    onSelect = { filter = DiagFilter.entries[it] },
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val all = TrackLog.snapshot(filter.minLevel, tag = filter.tag)
                                if (all.isEmpty()) {
                                    message = "Nothing to copy."
                                    return@launch
                                }
                                // The whole buffer can be over the binder limit for a
                                // clipboard set, and Android fails that silently. Keep
                                // the newest lines, which are the ones worth pasting.
                                val text = all.joinToString("\n") { it.toText() }
                                val capped = if (text.length > CLIPBOARD_MAX) {
                                    text.takeLast(CLIPBOARD_MAX)
                                } else {
                                    text
                                }
                                clipboard.setText(AnnotatedString(capped))
                                message = if (capped.length < text.length) {
                                    "Copied the newest ${capped.length} characters of " +
                                        "${text.length}; use Share for the whole log."
                                } else {
                                    "Copied ${all.size} lines."
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Copy all")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val text = TrackLog.export(filter.minLevel, tag = filter.tag)
                                val intent = writeForSharing(context, text)
                                if (intent == null) {
                                    message = "Could not write the log file."
                                } else {
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { message = "No app accepted the log." }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Share")
                    }
                    TextButton(
                        onClick = {
                            TrackLog.clear()
                            entries = emptyList()
                            message = "Log cleared."
                        },
                    ) {
                        Text("Clear")
                    }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    "No lines yet. Play something — resolution, source choice and cache " +
                        "decisions are recorded as they happen.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Newest first: the line that explains why the track just started
            // is at the bottom of the buffer, which is the far end of a scroll
            // on a phone.
            items(entries.asReversed()) { entry ->
                DiagnosticLine(
                    entry = entry,
                    onCopy = {
                        clipboard.setText(AnnotatedString(entry.toText()))
                        message = "Copied one line."
                    },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticLine(
    entry: TrackLog.Entry,
    onCopy: () -> Unit,
) {
    val severity = when (entry.level) {
        'E' -> MaterialTheme.colorScheme.error
        'W' -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                entry.stamp(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = severity,
                fontWeight = FontWeight.Bold,
            )
            Text(
                entry.tag,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            entry.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        entry.track?.let { track ->
            Text(
                track,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** What the page can be narrowed to. Severity and tag are independent knobs. */
private enum class DiagFilter(
    val label: String,
    val minLevel: Char,
    val tag: String?,
) {
    ALL("All", 'D', null),
    MORNYE("Mornye", 'D', "Mornye"),
    WARNINGS("Warnings+", 'W', null),
}

/**
 * Writes the log to the app's share folder and builds the hand-off intent.
 *
 * A `content://` URI through the same [FileProvider] the Replay poster uses, so
 * the receiving app gets this one file and a grant that expires with it. The
 * text rides as the stream rather than as an extra: the held log can exceed the
 * binder limit for intent data, and a truncated paste is worse than a file.
 */
private suspend fun writeForSharing(context: Context, text: String): Intent? =
    withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(context.cacheDir, "shared").apply { mkdirs() }
            // One name, overwritten: a hand-off point, not an album.
            val file = File(folder, "bitchord-diagnostics.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "BitChord diagnostics")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrNull()
    }

/** Roughly the clipboard's safe size, well under the binder transaction limit. */
private const val CLIPBOARD_MAX = 200_000
