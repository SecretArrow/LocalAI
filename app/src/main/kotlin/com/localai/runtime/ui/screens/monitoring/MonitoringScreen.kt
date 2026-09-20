package com.localai.runtime.ui.screens.monitoring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.localai.runtime.core.util.Formats
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.server.api.EngineStats
import com.localai.runtime.ui.components.EmptyState
import com.localai.runtime.ui.components.KeyValueRow
import com.localai.runtime.ui.components.SectionCard

/** Live system + runtime monitoring: CPU/RAM charts, storage, per-model stats. */
@Composable
fun MonitoringScreen(container: AppContainer, navController: NavHostController) {
    val vm: MonitoringViewModel = viewModel(factory = viewModelFactory { initializer { MonitoringViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()

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
            Text("Monitoring", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "System") {
                val latest = state.latest
                if (latest == null) {
                    Text(
                        "Waiting for the first system sample…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val cpuValues = state.history.map { it.cpuPercent }
                    Text(
                        "CPU · ${latest.cpuPercent.toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Sparkline(
                        values = cpuValues,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "min ${cpuValues.minOrNull()?.toInt() ?: 0}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "max ${cpuValues.maxOrNull()?.toInt() ?: 0}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val ramFraction = if (latest.ramTotalBytes > 0) {
                        latest.ramUsedBytes.toFloat() / latest.ramTotalBytes.toFloat()
                    } else {
                        0f
                    }
                    Text(
                        "RAM · ${Formats.bytes(latest.ramUsedBytes)} / ${Formats.bytes(latest.ramTotalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { ramFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        latest.temperatureC?.let { InfoChip("🌡 ${it.toInt()} °C") }
                        latest.batteryPercent?.let { InfoChip("🔋 $it%") }
                        InfoChip(
                            "Storage ${Formats.bytes(latest.storageUsedBytes)} / ${Formats.bytes(latest.storageTotalBytes)}",
                        )
                        InfoChip(if (latest.networkOnline) "Network online" else "Offline")
                    }
                }
            }

            SectionCard(title = "Runtime") {
                if (state.engineStats.isEmpty()) {
                    EmptyState(
                        title = "Nothing running",
                        subtitle = "Start a model to see runtime statistics",
                    )
                } else {
                    state.engineStats.forEach { stat ->
                        ModelStatCard(
                            stat = stat,
                            modelName = state.modelNames[stat.modelId] ?: stat.modelId,
                            tpsHistory = state.tpsHistory[stat.modelId] ?: emptyList(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ModelStatCard(
    stat: EngineStats,
    modelName: String,
    tpsHistory: List<Float>,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text(modelName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Generation · ${"%.1f".format(stat.tokensPerSecond)} tokens/s",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tpsHistory.size > 1) {
            Sparkline(
                values = tpsHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        KeyValueRow("Requests", stat.totalRequests.toString())
        KeyValueRow("Active / queued", "${stat.activeRequests} / ${stat.queuedRequests}")
        KeyValueRow("Memory", Formats.bytes(stat.memoryUsageBytes))
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Simple normalized polyline chart for small rolling series. */
@Composable
private fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val maxValue = values.max()
        val minValue = values.min()
        val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val normalized = (value - minValue) / range
            val y = size.height - (normalized * size.height * 0.9f) - (size.height * 0.05f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
