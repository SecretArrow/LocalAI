package com.localai.runtime.ui.screens.benchmark

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.util.Formats
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.server.api.BenchmarkResult
import com.localai.runtime.ui.components.ErrorPanel
import com.localai.runtime.ui.components.KeyValueRow
import com.localai.runtime.ui.components.SectionCard

/** Benchmark screen: pick a model, run the internal benchmark, share the results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(container: AppContainer, navController: NavHostController) {
    val vm: BenchmarkViewModel = viewModel(factory = viewModelFactory { initializer { BenchmarkViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            Text("Benchmark", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Model") {
                ModelDropdown(
                    models = state.models,
                    selectedModelId = state.selectedModelId,
                    onSelect = vm::select,
                )
                Text(
                    "The model must be running — it is started automatically if needed.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = vm::run,
                    enabled = !state.running && state.selectedModelId != null,
                ) {
                    Icon(Icons.Filled.Speed, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Run benchmark")
                }
                if (state.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.statusText?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.error?.let { error ->
                ErrorPanel(
                    message = error,
                    onRetry = {
                        vm.dismissError()
                        vm.run()
                    },
                )
            }

            state.result?.let { result ->
                SectionCard(title = "Results") {
                    KeyValueRow("Prompt processing", "${"%.1f".format(result.promptTokensPerSecond)} tokens/s")
                    KeyValueRow("Generation", "${"%.1f".format(result.generationTokensPerSecond)} tokens/s")
                    KeyValueRow("Peak memory", Formats.bytes(result.peakMemoryBytes))
                    KeyValueRow("Startup time", "${result.startupTimeMs} ms")
                    KeyValueRow("Model", result.modelId)
                    KeyValueRow("Backend", result.backend)
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, buildShareText(result))
                            }
                            context.startActivity(Intent.createChooser(intent, "Share benchmark"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Share results")
                    }
                }
            }

            SectionCard(title = "About") {
                Text(
                    "Benchmarks run llama.cpp's internal prompt-processing and generation tests " +
                        "(512 prompt / 256 generation tokens) against the running model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    models: List<ModelInfo>,
    selectedModelId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == selectedModelId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (models.isNotEmpty()) expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.name ?: (if (models.isEmpty()) "No installed models" else "Select a model"),
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.name) },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun buildShareText(result: BenchmarkResult): String = buildString {
    appendLine("LocalAI Runtime benchmark")
    appendLine("Model: ${result.modelId}")
    appendLine("Backend: ${result.backend}")
    appendLine("Prompt processing: ${"%.1f".format(result.promptTokensPerSecond)} tokens/s")
    appendLine("Generation: ${"%.1f".format(result.generationTokensPerSecond)} tokens/s")
    appendLine("Peak memory: ${Formats.bytes(result.peakMemoryBytes)}")
    appendLine("Startup time: ${result.startupTimeMs} ms")
}
