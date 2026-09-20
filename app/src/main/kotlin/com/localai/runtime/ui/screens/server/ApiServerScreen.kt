package com.localai.runtime.ui.screens.server

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.localai.runtime.core.model.AuthMode
import com.localai.runtime.core.util.Formats
import com.localai.runtime.navigation.Routes
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.ErrorPanel
import com.localai.runtime.ui.components.KeyValueRow
import com.localai.runtime.ui.components.SectionCard
import com.localai.runtime.ui.components.StatCard

/** API server dashboard: status, endpoints, stats, QR pairing, TLS management. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiServerScreen(container: AppContainer, navController: NavHostController) {
    val vm: ApiServerViewModel = viewModel(factory = viewModelFactory { initializer { ApiServerViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.attach(context) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    var certPem by remember { mutableStateOf<String?>(null) }
    var keyPem by remember { mutableStateOf<String?>(null) }
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { certPem = readPem(context, it) }
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { keyPem = readPem(context, it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(
                                if (state.running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                                CircleShape,
                            ),
                    )
                    Text(
                        if (state.running) "Running" else "Stopped",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.running) {
                        Text(
                            "· up ${Formats.eta(state.uptimeSeconds)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.running) {
                    state.endpoints.forEach { url -> EndpointChip(url) }
                    state.webConsoleUrl?.let { EndpointChip(it, label = "Web console") }
                } else {
                    Text(
                        if (state.apiEnabled) {
                            "The server is enabled but not running. Tap Start below."
                        } else {
                            "Run an OpenAI-compatible API server on this device. Configure it in Settings."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(label = "Requests", value = state.requestsTotal.toString())
                StatCard(label = "Tokens generated", value = state.tokensGenerated.toString())
                StatCard(label = "Uptime", value = if (state.running) Formats.eta(state.uptimeSeconds) else "—")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.running) {
                    OutlinedButton(onClick = { vm.restartServer(context) }, enabled = !state.busy) {
                        Text("Restart")
                    }
                    Button(
                        onClick = vm::stopServer,
                        enabled = !state.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Stop")
                    }
                } else {
                    Button(onClick = { vm.startServer(context) }, enabled = !state.busy) {
                        Text("Start server")
                    }
                }
            }

            if (state.error != null) {
                ErrorPanel(message = state.error ?: "", onRetry = vm::dismissError)
            }

            if (state.running && state.lanAccess && state.authMode == AuthMode.NONE) {
                WarningCard(
                    "Your API server is exposed to the local network WITHOUT authentication. " +
                        "Enable API key authentication in Settings.",
                )
            }

            SectionCard(title = "Connect") {
                val qr = state.qrBitmap
                when {
                    state.running && qr != null -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = "QR code",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(220.dp),
                            )
                            Text(
                                "Scan to connect (LAN)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!state.lanAccess) {
                                Text(
                                    "LAN access is off — this payload points at this device only.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    state.running -> Text(
                        "QR code unavailable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        "Start the server to show a QR connect payload.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(title = "HTTPS") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enable TLS", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Adds an HTTPS listener next to plain HTTP",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.tlsEnabled, onCheckedChange = vm::setTlsEnabled)
                }
                Button(onClick = vm::generateSelfSigned, enabled = state.running && !state.busy) {
                    Text("Generate self-signed certificate")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { certPicker.launch(arrayOf("application/x-pem-file", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (certPem != null) "cert.pem ✓" else "Select cert.pem")
                    }
                    OutlinedButton(
                        onClick = { keyPicker.launch(arrayOf("application/x-pem-file", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (keyPem != null) "key.pem ✓" else "Select key.pem")
                    }
                }
                OutlinedButton(
                    onClick = {
                        val cert = certPem
                        val key = keyPem
                        if (cert != null && key != null) vm.importTls(cert, key)
                    },
                    enabled = certPem != null && keyPem != null && state.running && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Apply imported certificate")
                }
                Text(
                    "Self-signed and imported certificates are not trusted by browsers by default — " +
                        "accept the warning or install the certificate on the client.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.running && state.tlsEnabled) {
                    KeyValueRow("HTTPS endpoint", "https://127.0.0.1:${state.tlsPort}")
                }
            }

            SectionCard(title = "Related") {
                LinkRow(
                    title = "API documentation",
                    subtitle = "Endpoints, curl, Python and JavaScript examples",
                    icon = Icons.Filled.Description,
                ) { navController.navigate(Routes.API_DOCS) }
                LinkRow(
                    title = "Devices",
                    subtitle = "Pair a phone or computer via a one-time token",
                    icon = Icons.Filled.Devices,
                ) { navController.navigate(Routes.DEVICES) }
            }

            Spacer(Modifier.height(8.dp))
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }
}

@Composable
private fun EndpointChip(url: String, label: String? = null) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (label != null) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    url,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(url))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy endpoint",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
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

private fun readPem(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()
