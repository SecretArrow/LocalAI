package com.localai.runtime.ui.screens.models

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.localai.runtime.core.model.CatalogEntry
import com.localai.runtime.core.model.DownloadProgress
import com.localai.runtime.core.model.DownloadState
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.ModelState
import com.localai.runtime.core.runtime.BackendRegistry
import com.localai.runtime.core.util.Formats
import com.localai.runtime.navigation.Routes
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.EmptyState
import com.localai.runtime.ui.components.ErrorPanel
import com.localai.runtime.ui.components.KeyValueRow
import com.localai.runtime.ui.components.ProgressRow
import com.localai.runtime.ui.components.StatusChip
import com.localai.runtime.ui.screens.home.MessageBar
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(container: AppContainer, navController: NavHostController) {
    val vm: ModelsViewModel = viewModel(factory = viewModelFactory { initializer { ModelsViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var deleteTarget by remember { mutableStateOf<ModelInfo?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.importModel(uri)
        }
    }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            delay(6_000)
            vm.clearError()
        }
    }

    val visibleModels = remember(
        state.models, state.searchQuery, state.filter, state.sort, state.backendDetectionDone,
    ) {
        filterModels(state.models, state.searchQuery, state.filter, state.sort, container.backendRegistry)
    }
    val visibleCatalog = remember(state.catalogEntries, state.models, state.searchQuery) {
        val installed = state.models.associateBy { it.id }
        val query = state.searchQuery.trim()
        state.catalogEntries.filter { entry ->
            val model = installed[entry.id]
            val matchesQuery = query.isBlank() ||
                entry.name.contains(query, ignoreCase = true) ||
                entry.id.contains(query, ignoreCase = true)
            // Hide installed entries unless the catalog offers a different version.
            val relevant = model == null || !model.installed || model.version != entry.version
            relevant && model?.state != ModelState.DOWNLOADING && matchesQuery
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- Search + sort ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = vm::setSearchQuery,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search models") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
            Box {
                var sortMenuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort")
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    ModelSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                vm.setSort(option)
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        // ---------- Filter chips ----------
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { vm.setFilter(filter) },
                    label = { Text(filter.label) },
                )
            }
        }

        // ---------- Import + custom URL ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import model", maxLines = 1)
            }
            OutlinedButton(
                onClick = { showUrlDialog = true },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add URL", maxLines = 1)
            }
        }

        state.error?.let { error ->
            ErrorPanel(message = error)
        }
        state.message?.let { message ->
            MessageBar(message = message, onDismiss = vm::clearMessage)
        }

        // ---------- List ----------
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (visibleModels.isEmpty() && visibleCatalog.isEmpty() && !state.refreshingCatalog) {
                item {
                    EmptyState(
                        title = "No models",
                        subtitle = "Import a local GGUF file, paste a download URL or pick one from the catalog below.",
                    )
                }
            }
            items(visibleModels, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    download = state.downloads[model.id],
                    onRun = { vm.runModel(model.id) },
                    onStop = { vm.stopModel(model.id) },
                    onDelete = { deleteTarget = model },
                    onPauseDownload = { state.downloads[model.id]?.let { vm.pauseDownload(it.id) } },
                    onResumeDownload = { state.downloads[model.id]?.let { vm.resumeDownload(it.id) } },
                    onCancelDownload = { state.downloads[model.id]?.let { vm.cancelDownload(it.id) } },
                    onRetryDownload = { state.downloads[model.id]?.let { vm.retryDownload(it.id) } },
                    onClick = { navController.navigate(Routes.modelDetail(model.id)) },
                )
            }

            item { CatalogHeader(state = state, onRefresh = vm::refreshCatalog) }

            if (visibleCatalog.isEmpty()) {
                item {
                    EmptyState(
                        title = if (state.catalogEntries.isEmpty()) "Catalog empty" else "Nothing new",
                        subtitle = if (state.catalogEntries.isEmpty()) {
                            "Tap the refresh icon to load the model catalog once you are online."
                        } else {
                            "All catalog models are already installed."
                        },
                    )
                }
            }
            items(visibleCatalog, key = { "catalog-${it.id}" }) { entry ->
                CatalogCard(
                    entry = entry,
                    isInstalled = state.models.any { it.id == entry.id && it.installed },
                    isNew = state.catalogDiff?.newModels?.any { it.id == entry.id } == true,
                    isUpdate = state.catalogDiff?.updatedModels?.any { it.id == entry.id } == true,
                    onDownload = { vm.downloadEntry(entry) },
                )
            }
        }
    }

    // ---------- Dialogs ----------
    deleteTarget?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete model?") },
            text = {
                Text("\"${model.name}\" and its local file will be removed. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteModel(model.id)
                        deleteTarget = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showUrlDialog) {
        UrlDialog(
            probing = state.probingUrl,
            onDismiss = { showUrlDialog = false },
            onCheck = { url ->
                vm.probeCustomUrl(url)
            },
        )
    }

    state.urlProbe?.let { probe ->
        AlertDialog(
            onDismissRequest = vm::dismissUrlProbe,
            title = { Text("Confirm download") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyValueRow("File", probe.fileName)
                    KeyValueRow("Size", if (probe.sizeBytes > 0) Formats.bytes(probe.sizeBytes) else "Unknown")
                    KeyValueRow("Resume", if (probe.rangesSupported) "Supported" else "Not supported")
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmCustomUrlDownload) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissUrlProbe) { Text("Cancel") }
            },
        )
    }

    state.importing?.let { progress ->
        AlertDialog(
            onDismissRequest = { /* explicit cancel only */ },
            title = { Text("Importing model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(progress.fileName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (progress.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (progress.processedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${Formats.bytes(progress.processedBytes)} / ${Formats.bytes(progress.totalBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "Copying into app storage and computing the checksum. Large files may take a while.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = vm::cancelImport) { Text("Cancel import") }
            },
        )
    }
}

// --------------------------------------------------------------------- cards

@Composable
private fun ModelCard(
    model: ModelInfo,
    download: DownloadProgress?,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            model.version.takeIf { it.isNotBlank() },
                            model.format.name,
                            model.quantization.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(state = model.state)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(Formats.bytes(model.sizeBytes))
                        append("  ·  ")
                        append(if (model.minRamMb > 0) "min RAM ${model.minRamMb} MB" else "RAM requirement unknown")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BackendBadge(model)
            }

            model.lastError?.let { lastError ->
                Text(
                    lastError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            download?.let { progress ->
                DownloadProgressBlock(
                    progress = progress,
                    onPause = onPauseDownload,
                    onResume = onResumeDownload,
                    onCancel = onCancelDownload,
                    onRetry = onRetryDownload,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                when (model.state) {
                    ModelState.RUNNING, ModelState.STARTING, ModelState.STOPPING ->
                        IconButton(onClick = onStop) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop model")
                        }
                    else -> if (model.installed) {
                        IconButton(onClick = onRun) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Run model")
                        }
                    }
                }
                IconButton(onClick = onClick) {
                    Icon(Icons.Filled.Info, contentDescription = "Model details")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete model", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun BackendBadge(model: ModelInfo) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
    ) {
        Text(
            model.backends.joinToString(" / ") { it.displayName },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun DownloadProgressBlock(
    progress: DownloadProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ProgressRow(
            progress = progress.percent,
            downloaded = Formats.bytes(progress.downloadedBytes),
            total = if (progress.totalBytes > 0) Formats.bytes(progress.totalBytes) else "unknown",
            speed = if (progress.speedBytesPerSec > 0) Formats.speed(progress.speedBytesPerSec) else null,
            eta = if (progress.etaSeconds > 0) Formats.eta(progress.etaSeconds) else null,
        )
        progress.error?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (progress.state) {
                DownloadState.QUEUED, DownloadState.CONNECTING, DownloadState.DOWNLOADING -> {
                    IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, contentDescription = "Pause download") }
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel download") }
                }
                DownloadState.PAUSED -> {
                    IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, contentDescription = "Resume download") }
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel download") }
                }
                DownloadState.FAILED, DownloadState.CANCELLED -> {
                    IconButton(onClick = onRetry) { Icon(Icons.Filled.Replay, contentDescription = "Retry download") }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun CatalogHeader(state: ModelsUiState, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Available to download", style = MaterialTheme.typography.titleMedium)
            val diff = state.catalogDiff
            val summary = buildString {
                state.catalogVersion?.let { append("catalog $it") }
                if (diff != null) {
                    if (isNotEmpty()) append("  ·  ")
                    append("${diff.newModels.size} new, ${diff.updatedModels.size} updated")
                }
            }
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.refreshingCatalog) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh catalog")
            }
        }
    }
}

@Composable
private fun CatalogCard(
    entry: CatalogEntry,
    isInstalled: Boolean,
    isNew: Boolean,
    isUpdate: Boolean,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            entry.version.takeIf { it.isNotBlank() },
                            entry.format.takeIf { it.isNotBlank() },
                            entry.quantization.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isNew) {
                    Badge("New")
                } else if (isInstalled || isUpdate) {
                    Badge("Update")
                }
            }
            Text(
                buildString {
                    append(if (entry.size > 0) Formats.bytes(entry.size) else "Unknown size")
                    entry.license?.takeIf { it.isNotBlank() }?.let { append("  ·  $it") }
                    if (entry.minRamMb > 0) append("  ·  min RAM ${entry.minRamMb} MB")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.description.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row {
                FilledTonalButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isInstalled) "Update" else "Download")
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun UrlDialog(
    probing: Boolean,
    onDismiss: () -> Unit,
    onCheck: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download from URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Direct HTTP(S) link to a model file (e.g. a .gguf file).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("https://example.com/model.gguf") },
                )
                if (probing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Checking URL…", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCheck(url) },
                enabled = url.isNotBlank() && !probing,
            ) { Text("Check") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ----------------------------------------------------------------- filtering

private fun filterModels(
    models: List<ModelInfo>,
    query: String,
    filter: ModelFilter,
    sort: ModelSort,
    registry: BackendRegistry,
): List<ModelInfo> {
    val trimmed = query.trim()
    var result = if (trimmed.isBlank()) models else models.filter { model ->
        model.name.contains(trimmed, ignoreCase = true) ||
            model.id.contains(trimmed, ignoreCase = true) ||
            model.quantization.contains(trimmed, ignoreCase = true) ||
            model.architecture?.contains(trimmed, ignoreCase = true) == true
    }
    result = when (filter) {
        ModelFilter.ALL -> result
        ModelFilter.INSTALLED -> result.filter { it.installed }
        ModelFilter.RUNNING -> result.filter { it.state == ModelState.RUNNING || it.state == ModelState.STARTING }
        ModelFilter.COMPATIBLE -> result.filter { model ->
            runCatching { registry.available(model).isNotEmpty() }.getOrDefault(false)
        }
    }
    return when (sort) {
        ModelSort.NAME -> result.sortedBy { it.name.lowercase() }
        ModelSort.SIZE -> result.sortedByDescending { it.sizeBytes }
        ModelSort.RECENT -> result.sortedByDescending { maxOf(it.installedAt, it.updatedAt) }
    }
}
