package com.music.bitchord.ui.screens

import android.content.Context
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.sources.mornye.InstalledMorneyePackage
import com.music.bitchord.data.sources.mornye.MorneyeInstallException
import com.music.bitchord.data.sources.mornye.MorneyePackageManager
import com.music.bitchord.data.sources.mornye.MorneyeRegistryEntry
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
                    onToggle = { MorneyePackageManager.setEnabled(pkg.key, it) },
                    onRemove = {
                        scope.launch {
                            busyKeys.add(pkg.key)
                            MorneyePackageManager.remove(pkg.key)
                            busyKeys.remove(pkg.key)
                            message = "${pkg.displayName} removed."
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
}

@Composable
private fun InstalledAddonRow(
    pkg: InstalledMorneyePackage,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
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
