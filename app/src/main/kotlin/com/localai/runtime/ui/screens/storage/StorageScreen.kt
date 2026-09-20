package com.localai.runtime.ui.screens.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.localai.runtime.core.util.Formats
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.KeyValueRow
import com.localai.runtime.ui.components.SectionCard
import com.localai.runtime.ui.components.StatCard
import java.io.File

/** Storage screen: usage breakdown, cache/temp cleanup, settings export/import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(container: AppContainer, navController: NavHostController) {
    val vm: StorageViewModel = viewModel(factory = viewModelFactory { initializer { StorageViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var confirmClearCache by remember { mutableStateOf(false) }
    var confirmDeleteTemp by remember { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importSettings(context, it) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.onMessageShown()
        }
    }
    LaunchedEffect(state.exportFile) {
        val file = state.exportFile ?: return@LaunchedEffect
        shareFile(context, file, "Share settings")
        vm.onExportHandled()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Storage", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = vm::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val usage = state.usage
                SectionCard(title = "Usage") {
                    if (usage == null) {
                        Text(
                            if (state.busy) "Scanning storage…" else "Storage information is not available yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StatCard(label = "Models", value = Formats.bytes(usage.modelsBytes))
                            StatCard(label = "Downloads", value = Formats.bytes(usage.downloadsBytes))
                            StatCard(label = "Cache", value = Formats.bytes(usage.cacheBytes))
                            StatCard(label = "Logs", value = Formats.bytes(usage.logsBytes))
                            StatCard(
                                label = "Free",
                                value = Formats.bytes(usage.freeBytes),
                                supporting = "of ${Formats.bytes(usage.totalBytes)}",
                            )
                        }

                        val appUsed = usage.modelsBytes + usage.downloadsBytes + usage.cacheBytes +
                            usage.logsBytes + usage.tempBytes
                        val available = appUsed + usage.freeBytes
                        val fraction = if (available > 0) {
                            appUsed.toFloat() / available.toFloat()
                        } else {
                            0f
                        }
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "App data ${Formats.bytes(appUsed)} of ${Formats.bytes(available)} available on this volume",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        KeyValueRow("Temporary files (.part)", Formats.bytes(usage.tempBytes))
                    }
                }

                SectionCard(title = "Cleanup") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { confirmClearCache = true },
                            enabled = !state.busy && (state.usage?.cacheBytes ?: 0L) > 0L,
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Clear cache")
                        }
                        OutlinedButton(
                            onClick = { confirmDeleteTemp = true },
                            enabled = !state.busy && (state.usage?.tempBytes ?: 0L) > 0L,
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete temp files")
                        }
                    }
                    Text(
                        "Moving models between storage locations is not available in this version.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionCard(title = "Settings backup") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column {
                            Text("Export settings", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Share a JSON file with your configuration. Never contains secrets or tokens.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = vm::exportSettings) {
                            Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Export")
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column {
                            Text("Import settings", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Apply a previously exported settings file. Non-secret settings only.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                        ) {
                            Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Import")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("Clear cache?") },
            text = { Text("Cached data will be deleted. Models and downloads are not affected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearCache()
                        confirmClearCache = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDeleteTemp) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTemp = false },
            title = { Text("Delete temp files?") },
            text = { Text("Partial download and import files (.part) will be removed. Completed models are not affected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteTempFiles()
                        confirmDeleteTemp = false
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteTemp = false }) { Text("Cancel") }
            },
        )
    }
}

private fun shareFile(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, title)) }
        .onFailure {
            Toast.makeText(context, "Could not share the file", Toast.LENGTH_SHORT).show()
        }
}
