package com.music.bitchord.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps a copy of [TrackLog] in a fixed, user-visible text file.
 *
 * Diagnostics used to exist only in memory until someone opened the page and
 * tapped Share. That is fine for a paste but hopeless for the case that matters
 * most — a track that sounds wrong *now*, where by the time a file has been
 * found and shared the interesting lines have scrolled out of the buffer. This
 * mirrors whatever [TrackLog] currently holds into one stable path that can be
 * opened from a file manager or read straight off the device:
 *
 *     Download/BitChord/Diagnostics/bitchord-diagnostics.txt
 *
 * One file, overwritten in place. It is a live view of the ring buffer, not an
 * archive: the text is produced by [TrackLog.export], so it has the same header,
 * redaction and severity filtering as Copy Log and the Share sheet. Nothing is
 * added here that the in-memory log did not already contain, and an I/O failure
 * is logged and ignored — diagnostics must never be able to break playback.
 */
object DiagnosticsFileSink {
    private const val TAG = "BitChord"
    private const val NAME = "bitchord-diagnostics.txt"
    private const val PREFS = "bitchord_diagnostics_file"
    private const val KEY_URI = "uri"
    private const val FOLDER = "BitChord/Diagnostics"
    private const val MIRROR_INTERVAL_MS = 2_000L

    /** Where the file lives, for the one line printed at install and for tests. */
    internal val RELATIVE_PATH: String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installed = false

    /** Idempotent: called from Application.onCreate, which can run more than once in tests. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        scope.launch {
            var lastRevision = -1L
            while (true) {
                val revision = TrackLog.revision
                if (revision != lastRevision) {
                    runCatching { writeOnce(app) }
                        .onFailure { Log.w(TAG, "could not mirror diagnostics to a file: ${it.message}") }
                    lastRevision = revision
                }
                delay(MIRROR_INTERVAL_MS)
            }
        }
    }

    private suspend fun writeOnce(context: Context) {
        val text = TrackLog.export()
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var uri = prefs.getString(KEY_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (uri != null && !writeTo(resolver, uri, text)) {
                // The row survived but its file did not (a file manager deleted
                // it, or a restore left a stale row). Forget it and reserve a
                // fresh one rather than losing the mirror for this session.
                runCatching { resolver.delete(uri, null, null) }
                uri = null
            }
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                }
                uri = runCatching {
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                }.getOrNull()
                if (uri != null) {
                    prefs.edit().putString(KEY_URI, uri.toString()).apply()
                    Log.i(TAG, "diagnostics mirrored to $RELATIVE_PATH/$NAME")
                }
            }
            if (uri != null && writeTo(resolver, uri, text)) return
        }

        // Below API 29 MediaStore.Downloads does not exist. A direct file in
        // the public Downloads folder is the only option there; on newer
        // versions this is reached only when the media store refused the row.
        @Suppress("DEPRECATION")
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER,
        )
        if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
        File(folder, NAME).writeText(text)
    }

    private fun writeTo(resolver: android.content.ContentResolver, uri: Uri, text: String): Boolean =
        runCatching {
            resolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open diagnostics file for writing")
            true
        }.getOrDefault(false)
}
