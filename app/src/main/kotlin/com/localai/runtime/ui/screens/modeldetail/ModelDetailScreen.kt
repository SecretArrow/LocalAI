package com.localai.runtime.ui.screens.modeldetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.localai.runtime.core.model.Availability
import com.localai.runtime.core.model.BackendInfo
import com.localai.runtime.core.model.BackendType
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.ModelState
import com.localai.runtime.core.model.RuntimeConfig
import com.localai.runtime.core.util.Formats
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.EmptyState
import com.localai.runtime.ui.components.ErrorPanel
import com.localai.runtime.ui.components.KeyValueRow
import com.localai.runtime.ui.components.ProgressRow
import com.localai.runtime.ui.components.SectionCard
import com.localai.runtime.ui.components.StatusChip
import com.localai.runtime.ui.screens.home.MessageBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun ModelDetailScreen(container: AppContainer, navController: NavHostController, modelId: String) {
    val vm: ModelDetailViewModel = viewModel(
        factory = viewModelFactory { initializer { ModelDetailViewModel(container, modelId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.deleted) {
        if (state.deleted) navController.popBackStack()
    }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            delay(6_000)
            vm.clearError()
        }
    }

    val model = state.model
    if (model == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            EmptyState(title = "Model not found", subtitle = "It may have been deleted.")
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- Header ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    model.id,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        state.message?.let { message -> MessageBar(message = message, onDismiss = vm::clearMessage) }
        state.error?.let { error -> ErrorPanel(message = error) }

        state.download?.let { download ->
            SectionCard(title = "Downloading") {
                ProgressRow(
                    progress = download.percent,
                    downloaded = Formats.bytes(download.downloadedBytes),
                    total = if (download.totalBytes > 0) Formats.bytes(download.totalBytes) else "unknown",
                    speed = if (download.speedBytesPerSec > 0) Formats.speed(download.speedBytesPerSec) else null,
                    eta = if (download.etaSeconds > 0) Formats.eta(download.etaSeconds) else null,
                )
            }
        }

        // ---------- Metadata ----------
        SectionCard(title = "Model") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Status", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusChip(state = model.state)
            }
            KeyValueRow("Format", model.format.name)
            KeyValueRow("Quantization", model.quantization.ifBlank { "—" })
            KeyValueRow("Size", if (model.sizeBytes > 0) Formats.bytes(model.sizeBytes) else "—")
            KeyValueRow("Architecture", model.architecture ?: "—")
            KeyValueRow("Context length", if (model.contextLength > 0) "${model.contextLength} tokens" else "—")
            KeyValueRow("Parameters", formatParameterCount(model.parameterCount))
            KeyValueRow("Vocabulary", if (model.vocabSize > 0) model.vocabSize.toString() else "—")
            KeyValueRow("License", model.license ?: "—")
            KeyValueRow("Source", model.sourceUrl ?: if (model.imported) "Imported" else "—")
            model.sha256?.let { ShaRow(sha256 = it) }
            KeyValueRow("Installed", formatDate(model.installedAt))
            KeyValueRow("RAM required", if (model.minRamMb > 0) "${model.minRamMb} MB" else "—")
            KeyValueRow("RAM recommended", if (model.recommendedRamMb > 0) "${model.recommendedRamMb} MB" else "—")
            KeyValueRow("Backends", model.backends.joinToString(" / ") { it.displayName })
            model.lastError?.let { lastError ->
                Text(
                    "Last error: $lastError",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ---------- Actions ----------
        val canStart = model.installed &&
            model.state !in setOf(ModelState.RUNNING, ModelState.STARTING, ModelState.STOPPING)
        val canStop = model.state in setOf(ModelState.RUNNING, ModelState.STARTING)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::start, enabled = canStart, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start")
            }
            OutlinedButton(onClick = vm::stop, enabled = canStop, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stop")
            }
            OutlinedButton(onClick = vm::restart, enabled = model.installed, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Restart")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::exportConfig, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export config")
            }
            OutlinedButton(onClick = { showDeleteDialog = true }, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(6.dp))
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }

        // ---------- Runtime configuration ----------
        val config = state.config
        if (config != null) {
            ConfigEditor(
                initial = config,
                backends = state.backends,
                model = model,
                onSave = vm::saveConfig,
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete model?") },
            text = { Text("\"${model.name}\" and its local file will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        vm.delete()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ------------------------------------------------------------- config editor

@Composable
private fun ConfigEditor(
    initial: RuntimeConfig,
    backends: List<BackendInfo>,
    model: ModelInfo,
    onSave: (RuntimeConfig) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val cores = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }

    SectionCard(title = "Runtime configuration") {
        // Backend selection (Auto + detected backends).
        var backendMenuOpen by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Backend", style = MaterialTheme.typography.bodyMedium)
                Text(
                    draft.backend?.displayName ?: "Auto (best available)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                OutlinedButton(onClick = { backendMenuOpen = true }) { Text("Change") }
                DropdownMenu(expanded = backendMenuOpen, onDismissRequest = { backendMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Auto") },
                        onClick = {
                            draft = draft.copy(backend = null)
                            backendMenuOpen = false
                        },
                    )
                    backends.forEach { info ->
                        val available = info.availability == Availability.AVAILABLE
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (available) info.type.displayName else "${info.type.displayName} — unavailable",
                                    color = if (available) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            enabled = available,
                            onClick = {
                                draft = draft.copy(backend = info.type)
                                backendMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        // CPU threads: 0 on the slider means Auto (-1).
        val threadsUpper = maxOf(cores, draft.cpuThreads, 1)
        LabeledSlider(
            label = "CPU threads",
            valueText = if (draft.cpuThreads <= 0) "Auto" else draft.cpuThreads.toString(),
            value = if (draft.cpuThreads <= 0) 0f else draft.cpuThreads.coerceIn(1, threadsUpper).toFloat(),
            valueRange = 0f..threadsUpper.toFloat(),
            steps = (threadsUpper - 1).coerceAtLeast(0),
            supporting = "Auto picks a sensible count for this device ($cores cores detected)",
            onValue = { raw ->
                draft = draft.copy(
                    cpuThreads = if (raw < 1f) -1 else raw.roundToInt().coerceIn(1, threadsUpper),
                )
            },
        )

        // Context length: 0 on the slider means Auto (model default).
        LabeledSlider(
            label = "Context length",
            valueText = if (draft.contextLength <= 0) "Auto" else draft.contextLength.toString(),
            value = draft.contextLength.coerceIn(0, 32768).toFloat(),
            valueRange = 0f..32768f,
            steps = 63,
            supporting = model.contextLength.takeIf { it > 0 }?.let { "Model default: $it tokens" },
            onValue = { raw -> draft = draft.copy(contextLength = (raw / 512f).roundToInt() * 512) },
        )

        // GPU layers — honest availability (spec §65).
        val gpuAvailable = backends.any { it.type != BackendType.CPU && it.availability == Availability.AVAILABLE }
        if (gpuAvailable) {
            LabeledSlider(
                label = "GPU layers",
                valueText = if (draft.gpuLayers < 0) "Auto" else draft.gpuLayers.toString(),
                value = if (draft.gpuLayers < 0) 0f else draft.gpuLayers.coerceIn(0, 64).toFloat(),
                valueRange = 0f..64f,
                steps = 63,
                onValue = { raw -> draft = draft.copy(gpuLayers = if (raw < 1f) -1 else raw.roundToInt()) },
            )
        } else {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("GPU layers", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Auto",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "Auto — GPU not available in this build",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(value = 0f, onValueChange = {}, valueRange = 0f..64f, enabled = false)
            }
        }

        // Sampling parameters.
        LabeledSlider(
            label = "Temperature",
            valueText = "%.2f".format(Locale.US, draft.temperature),
            value = draft.temperature.coerceIn(0f, 2f),
            valueRange = 0f..2f,
            steps = 39,
            onValue = { raw -> draft = draft.copy(temperature = raw) },
        )
        LabeledSlider(
            label = "Top P",
            valueText = "%.2f".format(Locale.US, draft.topP),
            value = draft.topP.coerceIn(0f, 1f),
            valueRange = 0f..1f,
            steps = 99,
            onValue = { raw -> draft = draft.copy(topP = raw) },
        )
        LabeledSlider(
            label = "Top K",
            valueText = draft.topK.coerceAtLeast(1).toString(),
            value = draft.topK.coerceIn(1, 100).toFloat(),
            valueRange = 1f..100f,
            steps = 98,
            onValue = { raw -> draft = draft.copy(topK = raw.roundToInt().coerceIn(1, 100)) },
        )
        LabeledSlider(
            label = "Min P",
            valueText = "%.2f".format(Locale.US, draft.minP),
            value = draft.minP.coerceIn(0f, 1f),
            valueRange = 0f..1f,
            steps = 99,
            onValue = { raw -> draft = draft.copy(minP = raw) },
        )
        LabeledSlider(
            label = "Repeat penalty",
            valueText = "%.2f".format(Locale.US, draft.repeatPenalty),
            value = draft.repeatPenalty.coerceIn(0.5f, 2f),
            valueRange = 0.5f..2f,
            steps = 29,
            onValue = { raw -> draft = draft.copy(repeatPenalty = raw) },
        )

        // Seed (empty = random).
        var seedText by remember(draft.seed) {
            mutableStateOf(if (draft.seed < 0) "" else draft.seed.toString())
        }
        OutlinedTextField(
            value = seedText,
            onValueChange = { text ->
                val filtered = text.filter { it.isDigit() }.take(19)
                seedText = filtered
                draft = draft.copy(seed = filtered.toLongOrNull() ?: -1L)
            },
            label = { Text("Seed") },
            supportingText = { Text("Empty = random") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow(
            label = "Streaming responses",
            checked = draft.streaming,
            onCheckedChange = { draft = draft.copy(streaming = it) },
        )
        SwitchRow(
            label = "Memory mapping",
            checked = draft.memoryMapping,
            onCheckedChange = { draft = draft.copy(memoryMapping = it) },
            supporting = "Map model files instead of loading them into Java heap",
        )
        SwitchRow(
            label = "Flash attention",
            checked = draft.flashAttention,
            onCheckedChange = { draft = draft.copy(flashAttention = it) },
            supporting = "Requires backend support",
        )

        Button(
            onClick = { onSave(draft) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save configuration") }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        supporting?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ShaRow(sha256: String) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SHA-256",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (expanded) sha256 else "${sha256.take(16)}… (tap)",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
                .clickable { expanded = !expanded },
        )
    }
}

// ----------------------------------------------------------------- helpers

private fun formatParameterCount(count: Long): String = when {
    count <= 0L -> "—"
    count >= 1_000_000_000L -> "%.1fB".format(Locale.US, count / 1_000_000_000.0)
    count >= 1_000_000L -> "%.1fM".format(Locale.US, count / 1_000_000.0)
    else -> count.toString()
}

private fun formatDate(ts: Long): String =
    if (ts <= 0L) "—" else Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm"))
