package com.localai.runtime.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.localai.runtime.BuildConfig
import com.localai.runtime.core.util.Formats
import com.localai.runtime.navigation.Routes
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.StatCard
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(container: AppContainer, navController: NavHostController) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- Header ----------
        Column {
            Text("LocalAI Runtime", style = MaterialTheme.typography.headlineSmall)
            Text(
                "v${BuildConfig.VERSION_NAME} · on-device inference",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------- One-shot message ----------
        state.message?.let { message ->
            MessageBar(message = message, onDismiss = vm::clearMessage)
        }

        // ---------- Stat tiles (2 x 2) ----------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(label = "Models installed", value = state.modelsInstalled.toString(), modifier = Modifier.weight(1f))
            StatCard(label = "Running", value = state.modelsRunning.toString(), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "API server",
                value = if (state.apiRunning) "● Running" else "● Stopped",
                supporting = state.apiEndpoint ?: "Enable it in Settings or the Server tab",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = "Downloads",
                value = state.activeDownloads.toString(),
                supporting = if (state.activeDownloads > 0) "in progress" else null,
                modifier = Modifier.weight(1f),
            )
        }

        // ---------- Quick start ----------
        val quickStart = state.quickStartModel
        if (quickStart != null && state.modelsRunning == 0) {
            Button(
                onClick = { vm.startDefaultModel() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start ${quickStart.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // ---------- Quick actions ----------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = { navController.navigate(Routes.MODELS) { launchSingleTop = true } },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Run model", maxLines = 1)
            }
            FilledTonalButton(
                onClick = { navController.navigate(Routes.MODELS) { launchSingleTop = true } },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download model", maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = { navController.navigate(Routes.CHAT) { launchSingleTop = true } },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open chat", maxLines = 1)
            }
            FilledTonalButton(
                onClick = { navController.navigate(Routes.SERVER) { launchSingleTop = true } },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("API server", maxLines = 1)
            }
        }

        // ---------- System monitor tiles ----------
        val snap = state.systemSnapshot
        Text("System", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "CPU",
                value = snap?.cpuPercent?.let { "${it.toInt()}%" } ?: "—",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = "RAM",
                value = if (snap != null && snap.ramTotalBytes > 0) {
                    "${Formats.bytes(snap.ramUsedBytes)} / ${Formats.bytes(snap.ramTotalBytes)}"
                } else "—",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "Battery",
                value = snap?.batteryPercent?.let { "$it%" } ?: "—",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = "Storage",
                value = if (snap != null && snap.storageTotalBytes > 0) {
                    "${Formats.bytes(snap.storageUsedBytes)} / ${Formats.bytes(snap.storageTotalBytes)}"
                } else "—",
                modifier = Modifier.weight(1f),
            )
        }

        // ---------- More ----------
        Text("More", style = MaterialTheme.typography.titleMedium)
        val moreItems: List<Triple<ImageVector, String, String>> = listOf(
            Triple(Icons.Filled.Insights, "Monitoring", Routes.MONITORING),
            Triple(Icons.Filled.Description, "Logs", Routes.LOGS),
            Triple(Icons.Filled.Storage, "Storage", Routes.STORAGE),
            Triple(Icons.Filled.Devices, "Devices", Routes.DEVICES),
            Triple(Icons.Filled.Download, "Downloads", Routes.DOWNLOADS),
            Triple(Icons.Filled.Speed, "Benchmark", Routes.BENCHMARK),
            Triple(Icons.Filled.MenuBook, "API docs", Routes.API_DOCS),
            Triple(Icons.Filled.Info, "About", Routes.ABOUT),
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ) {
            Column {
                moreItems.forEachIndexed { index, (icon, label, route) ->
                    MoreRow(
                        icon = icon,
                        label = label,
                        onClick = { navController.navigate(route) { launchSingleTop = true } },
                    )
                    if (index != moreItems.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 52.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small dismissible info bar used for one-shot view model messages. */
@Composable
internal fun MessageBar(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        delay(3_500)
        onDismiss()
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
