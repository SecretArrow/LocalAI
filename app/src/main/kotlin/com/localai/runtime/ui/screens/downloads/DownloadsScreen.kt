package com.localai.runtime.ui.screens.downloads

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.localai.runtime.core.model.DownloadProgress
import com.localai.runtime.core.model.DownloadState
import com.localai.runtime.core.util.Formats
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.EmptyState
import com.localai.runtime.ui.components.ErrorPanel
import com.localai.runtime.ui.components.ProgressRow
import kotlinx.coroutines.delay

@Composable
fun DownloadsScreen(container: AppContainer, navController: NavHostController) {
    val vm: DownloadsViewModel = viewModel(factory = viewModelFactory { initializer { DownloadsViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.error) {
        if (state.error != null) {
            delay(6_000)
            vm.clearError()
        }
    }

    val downloads = state.downloads
    val activeCount = downloads.count { it.state.isActiveState() }
    val pausedCount = downloads.count { it.state == DownloadState.PAUSED }
    val anyPausable = downloads.any {
        it.state == DownloadState.QUEUED || it.state == DownloadState.CONNECTING || it.state == DownloadState.DOWNLOADING
    }
    val anyResumable = pausedCount > 0
    val anyCancellable = downloads.any { it.state !in TERMINAL_STATES }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- Header stats ----------
        Column {
            Text("Downloads", style = MaterialTheme.typography.headlineSmall)
            Text(
                buildString {
                    append("$activeCount active")
                    if (pausedCount > 0) append("  ·  $pausedCount paused")
                    append("  ·  ${downloads.size} total")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------- Control row ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = vm::pauseAll,
                enabled = anyPausable,
                modifier = Modifier.weight(1f),
            ) { Text("Pause all", maxLines = 1) }
            OutlinedButton(
                onClick = vm::resumeAll,
                enabled = anyResumable,
                modifier = Modifier.weight(1f),
            ) { Text("Resume all", maxLines = 1) }
            OutlinedButton(
                onClick = vm::cancelAll,
                enabled = anyCancellable,
                modifier = Modifier.weight(1f),
            ) { Text("Cancel all", maxLines = 1) }
        }

        state.error?.let { error -> ErrorPanel(message = error) }

        // ---------- List ----------
        if (downloads.isEmpty()) {
            EmptyState(
                title = "No downloads",
                subtitle = "Models you download appear here.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(downloads, key = { it.id }) { download ->
                    DownloadCard(
                        download = download,
                        onPause = { vm.pause(download.id) },
                        onResume = { vm.resume(download.id) },
                        onCancel = { vm.cancel(download.id) },
                        onRetry = { vm.retry(download.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    download: DownloadProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        download.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val host = runCatching { Uri.parse(download.url).host }.getOrNull()
                    if (!host.isNullOrBlank()) {
                        Text(
                            host,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DownloadStateLabel(state = download.state)
            }

            ProgressRow(
                progress = download.percent,
                downloaded = Formats.bytes(download.downloadedBytes),
                total = if (download.totalBytes > 0) Formats.bytes(download.totalBytes) else "unknown",
                speed = if (download.speedBytesPerSec > 0) Formats.speed(download.speedBytesPerSec) else null,
                eta = when {
                    download.etaSeconds > 0 -> Formats.eta(download.etaSeconds)
                    download.speedBytesPerSec > 0 -> "—"
                    else -> null
                },
            )

            download.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (download.state) {
                    DownloadState.QUEUED, DownloadState.CONNECTING, DownloadState.DOWNLOADING -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = "Pause")
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    }
                    DownloadState.PAUSED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    }
                    DownloadState.FAILED, DownloadState.CANCELLED -> {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Filled.Replay, contentDescription = "Retry")
                        }
                    }
                    else -> Unit // VERIFYING and COMPLETED have no actions
                }
            }
        }
    }
}

@Composable
private fun DownloadStateLabel(state: DownloadState) {
    val (label, color) = when (state) {
        DownloadState.QUEUED -> "Queued" to MaterialTheme.colorScheme.onSurfaceVariant
        DownloadState.CONNECTING -> "Connecting" to MaterialTheme.colorScheme.tertiary
        DownloadState.DOWNLOADING -> "Downloading" to MaterialTheme.colorScheme.primary
        DownloadState.PAUSED -> "Paused" to MaterialTheme.colorScheme.onSurfaceVariant
        DownloadState.VERIFYING -> "Verifying" to MaterialTheme.colorScheme.tertiary
        DownloadState.COMPLETED -> "Completed" to Color(0xFF2E7D32)
        DownloadState.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        DownloadState.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private fun DownloadState.isActiveState(): Boolean =
    this == DownloadState.QUEUED ||
        this == DownloadState.CONNECTING ||
        this == DownloadState.DOWNLOADING ||
        this == DownloadState.VERIFYING

private val TERMINAL_STATES = setOf(
    DownloadState.COMPLETED,
    DownloadState.FAILED,
    DownloadState.CANCELLED,
)
