package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.sources.mornye.InstalledMorneyePackage
import com.music.bitchord.data.sources.mornye.MorneyeInstallException
import com.music.bitchord.data.sources.mornye.MorneyePackageManager
import com.music.bitchord.data.sources.mornye.MorneyeRegistryEntry
import com.music.bitchord.data.sources.mornye.MorneyeSignedSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen for managing installed Mornye extensions.
 *
 * Shows installed addons with their on/off toggle, then the official registry
 * so the user can install new ones.  An "Install from file" button lets
 * sideloading a .sflx / .morneye-ext / .spotiflac-ext archive.
 *
 * Cached audio for Mornye streams lives in BitChord's standard AudioCache
 * (ExoPlayer SimpleCache), so it appears and is exportable in the same
 * Downloads sheet as any other stream.
 */
@Composable
fun MorneyeScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val packages by MorneyePackageManager.packages.collectAsState()
    var registryEntries by remember { mutableStateOf<List<MorneyeRegistryEntry>?>(null) }
    var registryError   by remember { mutableStateOf<String?>(null) }
    var busyKeys        = remember { mutableStateListOf<String>() }
    var message         by remember { mutableStateOf<String?>(null) }

    // Signed-session state for the providers that need it (Tidal, Qobuz,
    // Amazon, Deezer). Reloaded whenever the manager connects, clears or
    // exchanges a session, so the rows do not need to poll.
    val sessionRevision by MorneyePackageManager.sessionRevision.collectAsState()
    var signedKeys      by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sessionStatuses by remember {
        mutableStateOf<Map<String, MorneyeSignedSessionStatus>>(emptyMap())
    }
    var pasteTarget     by remember { mutableStateOf<InstalledMorneyePackage?>(null) }
    var pasteText       by remember { mutableStateOf("") }

    LaunchedEffect(packages, sessionRevision) {
        val (keys, statuses) = withContext(Dispatchers.IO) {
            val keys = packages
                .mapNotNull { pkg ->
                    pkg.key.takeIf { MorneyePackageManager.signedSessionConfig(it) != null }
                }
                .toSet()
            keys to keys.mapNotNull { key ->
                MorneyePackageManager.signedSessionStatus(key)?.let { key to it }
            }.toMap()
        }
        signedKeys = keys
        sessionStatuses = statuses
    }

    // Load registry on first composition.
    LaunchedEffect(Unit) {
        runCatching { MorneyePackageManager.fetchRegistry() }
            .onSuccess  { registryEntries = it }
            .onFailure  { registryError = "Registry load failed: ${it.message}" }
    }

    // File picker for sideloading.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.readBytes()
            } ?: run {
                message = "Could not read the selected file."
                return@launch
            }
            runCatching { MorneyePackageManager.installFromBytes(bytes) }
                .onSuccess { message = "Installed ${it.displayName} v${it.version}" }
                .onFailure {
                    message = when (it) {
                        is MorneyeInstallException -> it.message
                        else -> "Install failed: ${it.message}"
                    }
                }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        // ── Header ───────────────────────────────────────────────────────
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    "Mornye Extensions",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "JavaScript add-ons that run on-device and supply FLAC and lossless " +
                        "streams. Streams are pre-fetched before each song ends — no gaps — " +
                        "and cached in the same place as your other downloads.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ── Installed packages ────────────────────────────────────────────
        item {
            SectionHeader("Installed add-ons")
        }

        if (packages.isEmpty()) {
            item {
                Text(
                    "No add-ons installed yet. Install one from the registry below or from a file.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(packages, key = { it.key }) { pkg ->
                InstalledAddonRow(
                    pkg     = pkg,
                    busy    = pkg.key in busyKeys,
                    requiresSession = pkg.key in signedKeys,
                    sessionStatus = sessionStatuses[pkg.key],
                    onToggle = { MorneyePackageManager.setEnabled(pkg.key, it) },
                    onRemove = {
                        scope.launch {
                            busyKeys.add(pkg.key)
                            MorneyePackageManager.remove(pkg.key)
                            busyKeys.remove(pkg.key)
                            message = "${pkg.displayName} removed."
                        }
                    },
                    onConnect = {
                        scope.launch {
                            busyKeys.add(pkg.key)
                            runCatching {
                                val url = MorneyePackageManager.beginSignedSession(pkg.key)
                                if (url.isNullOrBlank()) {
                                    "Already connected."
                                } else {
                                    val opened = runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }.isSuccess
                                    if (opened) {
                                        "Complete verification in the browser, then return here. " +
                                            "If no redirect appears, copy the link the page shows and use Paste link."
                                    } else {
                                        "No browser available. Copy the verification link and open it manually."
                                    }
                                }
                            }.onSuccess { message = it }
                                .onFailure { message = "Connection failed: ${it.message}" }
                            busyKeys.remove(pkg.key)
                        }
                    },
                    onPasteGrant = {
                        pasteTarget = pkg
                        pasteText = ""
                    },
                    onDisconnect = {
                        scope.launch {
                            busyKeys.add(pkg.key)
                            MorneyePackageManager.clearSignedSession(pkg.key)
                            busyKeys.remove(pkg.key)
                            message = "${pkg.displayName} disconnected."
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        // Install from file button.
        item {
            OutlinedButton(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text("Install from file  (.sflx / .morneye-ext / .spotiflac-ext)")
            }
        }

        // Notice.
        item {
            Text(
                "⚠ Add-ons are third-party code that runs on your device. " +
                    "Only install add-ons you trust and are authorised to use.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ── Official registry ─────────────────────────────────────────────
        item { SectionHeader("Official registry") }

        when {
            registryError != null -> item {
                Text(
                    registryError!!,
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            registryEntries == null -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
            else -> items(registryEntries!!, key = { it.id }) { entry ->
                RegistryEntryRow(
                    entry  = entry,
                    busy   = entry.id in busyKeys,
                    onInstall = {
                        scope.launch {
                            busyKeys.add(entry.id)
                            runCatching {
                                MorneyePackageManager.installFromUrl(entry.downloadUrl, entry.repositoryId)
                            }.onSuccess {
                                message = "Installed ${it.displayName} v${it.version}"
                                // Refresh registry to reflect installed state.
                                runCatching { MorneyePackageManager.fetchRegistry() }
                                    .onSuccess { registryEntries = it }
                            }.onFailure {
                                message = "Install failed: ${it.message}"
                            }
                            busyKeys.remove(entry.id)
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }

    pasteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { pasteTarget = null },
            title = { Text("Paste verification link") },
            text = {
                Column {
                    Text(
                        "After approving access, the verification page shows a " +
                            "mornye://session-grant link. Paste the whole link (or just the grant) here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        label = { Text("mornye://session-grant?…") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pasteText.isNotBlank() && target.key !in busyKeys,
                    onClick = {
                        val raw = pasteText
                        pasteTarget = null
                        pasteText = ""
                        scope.launch {
                            busyKeys.add(target.key)
                            runCatching {
                                MorneyePackageManager.completeSignedSessionGrant(target.key, raw)
                            }.onSuccess { status ->
                                message = if (status?.authenticated == true) {
                                    "${target.displayName} connected."
                                } else {
                                    "The link was accepted but no session was issued. Try Connect again."
                                }
                            }.onFailure {
                                message = "Verification failed: ${it.message}"
                            }
                            busyKeys.remove(target.key)
                        }
                    },
                ) { Text("Verify") }
            },
            dismissButton = {
                TextButton(onClick = { pasteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun InstalledAddonRow(
    pkg: InstalledMorneyePackage,
    busy: Boolean,
    requiresSession: Boolean,
    sessionStatus: MorneyeSignedSessionStatus?,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onConnect: () -> Unit,
    onPasteGrant: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.clickable { expanded = !expanded }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pkg.displayName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "v${pkg.version}${if (pkg.description.isNotBlank()) " · ${pkg.description}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(start = 8.dp))
            } else {
                Switch(
                    checked  = pkg.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter   = expandVertically(),
            exit    = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
                if (pkg.description.isNotBlank()) {
                    Text(pkg.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    "Installed: ${java.text.SimpleDateFormat("d MMM yyyy",
                        java.util.Locale.getDefault()).format(java.util.Date(pkg.installedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (requiresSession) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Provider connection",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        signedSessionLabel(sessionStatus),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            sessionStatus?.authenticated == true -> MaterialTheme.colorScheme.primary
                            sessionStatus?.verificationRequired == true -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (sessionStatus?.authenticated != true) {
                            Button(
                                onClick = onConnect,
                                enabled = !busy,
                            ) {
                                Text(
                                    if (sessionStatus?.verificationRequired == true) {
                                        "Continue verification"
                                    } else {
                                        "Connect"
                                    },
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onPasteGrant,
                            enabled = !busy,
                        ) { Text("Paste link") }
                        if (sessionStatus?.authenticated == true ||
                            sessionStatus?.verificationRequired == true
                        ) {
                            TextButton(onClick = onDisconnect, enabled = !busy) {
                                Text("Disconnect")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick  = onRemove,
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor   = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove add-on")
                }
            }
        }
    }
}

private fun signedSessionLabel(status: MorneyeSignedSessionStatus?): String = when {
    status == null -> "Not connected."
    status.authenticated -> "Connected" + status.expiresAt
        .takeIf { it.isNotBlank() }
        ?.let { " · expires ${formatSessionExpiry(it)}" }
        .orEmpty()
    status.verificationRequired ->
        "Waiting for verification. Finish in the browser, or paste the link the page shows."
    else -> "Not connected."
}

private fun formatSessionExpiry(raw: String): String = runCatching {
    java.time.Instant.parse(raw)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
}.getOrDefault(raw)

@Composable
private fun RegistryEntryRow(
    entry: MorneyeRegistryEntry,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.displayName,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    "v${entry.version}",
                    entry.description.ifBlank { null },
                    if (!entry.compatible) "Needs newer Mornye runtime" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (!entry.compatible)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(start = 8.dp))
        } else {
            OutlinedButton(
                onClick  = onInstall,
                enabled  = entry.compatible && entry.installed == null,
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text(
                    when {
                        entry.installed != null -> "Installed"
                        !entry.compatible       -> "Incompatible"
                        else                    -> "Install"
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}
