package com.localai.runtime.ui.screens.devices

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.navigation.NavHostController
import com.localai.runtime.core.model.PairedDevice
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.EmptyState
import com.localai.runtime.ui.components.SectionCard
import java.text.DateFormat
import java.util.Date

/** Device pairing screen: pairing code flow, one-time token, paired device list, mDNS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(container: AppContainer, navController: NavHostController) {
    val vm: DevicesViewModel = viewModel(factory = viewModelFactory { initializer { DevicesViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var deviceName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<PairedDevice?>(null) }
    var renameText by remember { mutableStateOf("") }
    var revokeTarget by remember { mutableStateOf<PairedDevice?>(null) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            vm.dismissError()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "Devices", onBack = { navController.popBackStack() })
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard(title = "Pair a device") {
                    val session = state.session
                    if (session == null) {
                        Text(
                            "Generate a one-time pairing code to approve a connecting device. " +
                                "The code is valid for 60 seconds.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = vm::beginPairing, enabled = !state.busy) {
                            Text("Start pairing")
                        }
                    } else {
                        Text(
                            text = session.code,
                            style = MaterialTheme.typography.headlineMedium,
                            letterSpacing = 8.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Expires in ${state.secondsLeft}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it },
                            label = { Text("Device name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { vm.approveDevice(deviceName) },
                            enabled = deviceName.isNotBlank() && !state.busy,
                        ) {
                            Text("Approve connection")
                        }
                    }
                }

                SectionCard(title = "Discovery") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("mDNS advertisement", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Advertise _localai._tcp on the local network",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.mdnsEnabled, onCheckedChange = vm::setMdns)
                    }
                }

                SectionCard(title = "Paired devices") {
                    if (state.devices.isEmpty()) {
                        EmptyState(
                            title = "No paired devices",
                            subtitle = "Devices you approve appear here",
                        )
                    } else {
                        state.devices.forEachIndexed { index, device ->
                            Column(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${device.id.take(8)}•••  ·  Paired ${formatDate(device.createdAt)}  ·  " +
                                                "Last seen ${formatDate(device.lastSeenAt)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            renameTarget = device
                                            renameText = device.name
                                        },
                                    ) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Rename")
                                    }
                                    IconButton(onClick = { revokeTarget = device }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Revoke",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                if (index != state.devices.lastIndex) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }

    state.generatedToken?.let { token ->
        AlertDialog(
            onDismissRequest = vm::dismissToken,
            title = { Text("Device token") },
            text = {
                Column {
                    Text(
                        "Show or scan this token to the connecting device once. It is not displayed again.",
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
                    },
                ) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissToken) { Text("Done") }
            },
        )
    }

    renameTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Device name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rename(device.id, renameText)
                        renameTarget = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    revokeTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke device?") },
            text = {
                Text("\u201C${device.name}\u201D will no longer be able to connect with its token.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.revoke(device.id)
                        revokeTarget = null
                    },
                ) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

private fun formatDate(ts: Long): String =
    if (ts <= 0L) {
        "never"
    } else {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))
    }
