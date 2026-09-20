package com.localai.runtime.ui.screens.settings

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.AuthMode
import com.localai.runtime.core.model.Availability
import com.localai.runtime.core.model.DarkMode
import com.localai.runtime.core.util.Formats
import com.localai.runtime.navigation.Routes
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.KeyValueRow
import com.localai.runtime.ui.components.SectionCard

/** Settings screen — mirrors [AppSettings] 1:1, grouped into sections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, navController: NavHostController) {
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })
    val settings by container.settingsRepository.flow
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val backends by vm.backends.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val tokenMask by vm.tokenMask.collectAsStateWithLifecycle()
    val newToken by vm.newToken.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val installedModels = remember(models) { models.filter { it.installed } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            SectionCard(title = "Runtime") {
                val backendOptions = buildList {
                    add("Auto (recommended)" to null)
                    backends.forEach { info ->
                        add("${info.type.displayName} (${availabilityLabel(info.availability)})" to info.type.id)
                    }
                }
                DropdownRow(
                    label = "Default backend",
                    options = backendOptions,
                    selected = settings.defaultBackendId,
                ) { id -> vm.update { it.copy(defaultBackendId = id) } }

                SliderRow(
                    title = "Default CPU threads",
                    valueLabel = if (settings.defaultThreads <= 0) "Auto" else settings.defaultThreads.toString(),
                    value = if (settings.defaultThreads <= 0) 0f else settings.defaultThreads.toFloat(),
                    onValueChange = { v ->
                        vm.update { it.copy(defaultThreads = if (v < 1f) -1 else v.toInt()) }
                    },
                    valueRange = 0f..16f,
                    steps = 15,
                )

                DropdownRow(
                    label = "Default context length",
                    options = listOf(2048, 4096, 8192, 16384).map { "$it tokens" to it },
                    selected = settings.defaultContext,
                ) { ctx -> vm.update { it.copy(defaultContext = ctx ?: 4096) } }

                val modelOptions = buildList {
                    add("None" to null)
                    installedModels.forEach { add(it.name to it.id) }
                }
                DropdownRow(
                    label = "Default model",
                    options = modelOptions,
                    selected = settings.defaultModelId,
                ) { id -> vm.update { it.copy(defaultModelId = id) } }
            }

            SectionCard(title = "API server") {
                SwitchRow(
                    title = "Enable API server",
                    subtitle = "Start the OpenAI-compatible server",
                    checked = settings.apiEnabled,
                    onCheckedChange = { v -> vm.update { it.copy(apiEnabled = v) } },
                )
                FieldRow(
                    label = "Host",
                    value = settings.apiHost,
                ) { v -> vm.update { it.copy(apiHost = v) } }
                PortField(
                    label = "Port",
                    value = settings.apiPort,
                ) { p -> vm.update { it.copy(apiPort = p) } }
                SwitchRow(
                    title = "HTTPS (TLS)",
                    subtitle = "Also listen on a TLS port",
                    checked = settings.apiTlsEnabled,
                    onCheckedChange = { v -> vm.update { it.copy(apiTlsEnabled = v) } },
                )
                if (settings.apiTlsEnabled) {
                    PortField(
                        label = "TLS port",
                        value = settings.apiTlsPort,
                    ) { p -> vm.update { it.copy(apiTlsPort = p) } }
                }

                Text("Authentication", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("None", settings.apiAuthMode == AuthMode.NONE) {
                        vm.update { it.copy(apiAuthMode = AuthMode.NONE) }
                    }
                    ChoiceChip("API key", settings.apiAuthMode == AuthMode.API_KEY) {
                        vm.update { it.copy(apiAuthMode = AuthMode.API_KEY) }
                    }
                    ChoiceChip("Bearer", settings.apiAuthMode == AuthMode.BEARER) {
                        vm.update { it.copy(apiAuthMode = AuthMode.BEARER) }
                    }
                }
                if (settings.apiAuthMode != AuthMode.NONE) {
                    val mask = tokenMask
                    if (mask == null) {
                        OutlinedButton(onClick = vm::generateToken) {
                            Text("Generate API token")
                        }
                    } else {
                        KeyValueRow("API token", mask)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = vm::generateToken) { Text("Regenerate") }
                            TextButton(
                                onClick = vm::revokeToken,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Revoke") }
                        }
                        Text(
                            "The full token is shown only once when generated or regenerated.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SliderRow(
                    title = "Max concurrent requests",
                    valueLabel = settings.apiMaxConcurrent.toString(),
                    value = settings.apiMaxConcurrent.toFloat(),
                    onValueChange = { v -> vm.update { it.copy(apiMaxConcurrent = v.toInt()) } },
                    valueRange = 1f..16f,
                    steps = 14,
                )
                SliderRow(
                    title = "Queue size",
                    valueLabel = settings.apiQueueSize.toString(),
                    value = settings.apiQueueSize.toFloat(),
                    onValueChange = { v -> vm.update { it.copy(apiQueueSize = v.toInt()) } },
                    valueRange = 1f..64f,
                    steps = 62,
                )
                SliderRow(
                    title = "Request timeout",
                    valueLabel = "${settings.apiTimeoutSeconds}s",
                    value = settings.apiTimeoutSeconds.toFloat(),
                    onValueChange = { v -> vm.update { it.copy(apiTimeoutSeconds = v.toLong()) } },
                    valueRange = 30f..600f,
                    steps = 18,
                )
                SwitchRow(
                    title = "LAN access",
                    subtitle = "Expose the server to devices on your local network",
                    checked = settings.apiLanAccess,
                    onCheckedChange = { v -> vm.update { it.copy(apiLanAccess = v) } },
                )
                if (settings.apiLanAccess && settings.apiAuthMode == AuthMode.NONE) {
                    Text(
                        "Warning: with LAN access and no authentication, anyone on your network can use the API.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SectionCard(title = "Downloads") {
                SwitchRow(
                    title = "Wi-Fi only",
                    subtitle = "Pause downloads on metered networks",
                    checked = settings.wifiOnly,
                    onCheckedChange = { v -> vm.update { it.copy(wifiOnly = v) } },
                )
                SliderRow(
                    title = "Max parallel downloads",
                    valueLabel = settings.maxParallelDownloads.toString(),
                    value = settings.maxParallelDownloads.toFloat(),
                    onValueChange = { v -> vm.update { it.copy(maxParallelDownloads = v.toInt()) } },
                    valueRange = 1f..4f,
                    steps = 2,
                )
                SwitchRow(
                    title = "Auto retry",
                    subtitle = "Retry failed downloads with backoff",
                    checked = settings.autoRetry,
                    onCheckedChange = { v -> vm.update { it.copy(autoRetry = v) } },
                )
                SwitchRow(
                    title = "Auto resume",
                    subtitle = "Resume interrupted downloads on startup",
                    checked = settings.autoResume,
                    onCheckedChange = { v -> vm.update { it.copy(autoResume = v) } },
                )
            }

            SectionCard(title = "Background") {
                SwitchRow(
                    title = "Keep runtime alive",
                    subtitle = "Foreground service while models run",
                    checked = settings.keepRuntimeAlive,
                    onCheckedChange = { v -> vm.update { it.copy(keepRuntimeAlive = v) } },
                )
                SwitchRow(
                    title = "Start on boot",
                    subtitle = "Start the runtime after device boot",
                    checked = settings.startOnBoot,
                    onCheckedChange = { v -> vm.update { it.copy(startOnBoot = v) } },
                )
                val autoStartOptions = buildList {
                    add("None" to null)
                    installedModels.forEach { add(it.name to it.id) }
                }
                DropdownRow(
                    label = "Auto-start model",
                    options = autoStartOptions,
                    selected = settings.autoStartModelId,
                ) { id -> vm.update { it.copy(autoStartModelId = id) } }
            }

            SectionCard(title = "Catalog") {
                FieldRow(
                    label = "Catalog URL",
                    value = settings.catalogUrl,
                ) { v -> vm.update { it.copy(catalogUrl = v) } }
                SwitchRow(
                    title = "Auto refresh",
                    subtitle = "Refresh the model catalog in the background",
                    checked = settings.catalogAutoRefresh,
                    onCheckedChange = { v -> vm.update { it.copy(catalogAutoRefresh = v) } },
                )
                SliderRow(
                    title = "Refresh interval",
                    valueLabel = "${settings.catalogRefreshHours}h",
                    value = settings.catalogRefreshHours.toFloat(),
                    onValueChange = { v -> vm.update { it.copy(catalogRefreshHours = v.toInt()) } },
                    valueRange = 1f..72f,
                )
            }

            SectionCard(title = "Appearance") {
                Text("Theme", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Light", settings.darkMode == DarkMode.LIGHT) {
                        vm.update { it.copy(darkMode = DarkMode.LIGHT) }
                    }
                    ChoiceChip("Dark", settings.darkMode == DarkMode.DARK) {
                        vm.update { it.copy(darkMode = DarkMode.DARK) }
                    }
                    ChoiceChip("System", settings.darkMode == DarkMode.SYSTEM) {
                        vm.update { it.copy(darkMode = DarkMode.SYSTEM) }
                    }
                }
                SwitchRow(
                    title = "Dynamic color",
                    subtitle = "Use system colors (Android 12+)",
                    checked = settings.dynamicColor,
                    onCheckedChange = { v -> vm.update { it.copy(dynamicColor = v) } },
                )
            }

            SectionCard(title = "Security") {
                Text(
                    "Tokens and secrets are stored encrypted with the Android Keystore and never " +
                        "included in backups or exports.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinkRow(
                    title = "Paired devices",
                    subtitle = "Manage device pairing and tokens",
                    icon = Icons.Filled.Devices,
                ) { navController.navigate(Routes.DEVICES) }
            }

            SectionCard(title = "Developer") {
                SwitchRow(
                    title = "Developer mode",
                    checked = settings.developerMode,
                    onCheckedChange = { v -> vm.update { it.copy(developerMode = v) } },
                )
                SwitchRow(
                    title = "Verbose logs",
                    subtitle = "Log debug-level details",
                    checked = settings.verboseLogs,
                    onCheckedChange = { v -> vm.update { it.copy(verboseLogs = v) } },
                )
                SwitchRow(
                    title = "Privacy mode",
                    subtitle = "Never log request bodies",
                    checked = settings.privacyMode,
                    onCheckedChange = { v -> vm.update { it.copy(privacyMode = v) } },
                )
            }

            if (settings.developerMode) {
                SectionCard(title = "Diagnostics") {
                    val caps = capabilities
                    if (caps == null) {
                        Text(
                            "Probing device…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        KeyValueRow("ABI", caps.abi.joinToString(", ").ifEmpty { "unknown" })
                        KeyValueRow("CPU cores", caps.cpuCores.toString())
                        KeyValueRow("Total RAM", Formats.bytes(caps.totalRamBytes))
                        KeyValueRow("GPU", "${caps.gpuVendor} ${caps.gpuModel}".trim())
                        KeyValueRow("Android", caps.androidVersion)
                        KeyValueRow("Device", caps.deviceModel)
                        KeyValueRow("CPU backend", if (caps.cpuBackendAvailable) "available" else "unavailable")
                    }
                    KeyValueRow("Build fingerprint", Build.FINGERPRINT)
                    KeyValueRow("Version", com.localai.runtime.BuildConfig.VERSION_NAME)
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        newToken?.let { token ->
            AlertDialog(
                onDismissRequest = vm::onTokenShown,
                title = { Text("API token") },
                text = {
                    Column {
                        Text(
                            "Copy this token now — it is shown only once.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            token,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(token))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            vm.onTokenShown()
                        },
                    ) { Text("Copy") }
                },
                dismissButton = {
                    TextButton(onClick = vm::onTokenShown) { Text("Done") }
                },
            )
        }
    }

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PortField(
    label: String,
    value: Int,
    onValid: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let { n -> if (n in 1024..65535) onValid(n) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownRow(
    label: String,
    options: List<Pair<String, T?>>,
    selected: T?,
    onSelect: (T?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.second == selected }?.first ?: "—"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (options.isNotEmpty()) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LinkRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

private fun availabilityLabel(availability: Availability): String = when (availability) {
    Availability.AVAILABLE -> "available"
    Availability.SUPPORTED -> "supported"
    Availability.UNAVAILABLE -> "unavailable"
    Availability.UNKNOWN -> "unknown"
}
