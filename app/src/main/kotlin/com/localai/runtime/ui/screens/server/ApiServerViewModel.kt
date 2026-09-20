package com.localai.runtime.ui.screens.server

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.AuthMode
import com.localai.runtime.core.net.LanAddresses
import com.localai.runtime.qr.QrCodes
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.service.RuntimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ServerUiState(
    val apiEnabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val tlsEnabled: Boolean = false,
    val tlsPort: Int = 8443,
    val authMode: AuthMode = AuthMode.NONE,
    val lanAccess: Boolean = false,
    val mdnsEnabled: Boolean = true,
    val running: Boolean = false,
    val endpoints: List<String> = emptyList(),
    val requestsTotal: Long = 0,
    val tokensGenerated: Long = 0,
    val uptimeSeconds: Long = 0,
    val qrBitmap: Bitmap? = null,
    val webConsoleUrl: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/**
 * API server dashboard state: reflects the transport settings, polls the live
 * server, aggregates engine counters and builds the LAN QR connect payload.
 */
class ApiServerViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var startedAtMs = 0L
    private var lastQrKey: String? = null

    init {
        viewModelScope.launch {
            container.settingsRepository.flow.collect { s ->
                _state.value = _state.value.copy(
                    apiEnabled = s.apiEnabled,
                    host = s.apiHost,
                    port = s.apiPort,
                    tlsEnabled = s.apiTlsEnabled,
                    tlsPort = s.apiTlsPort,
                    authMode = s.apiAuthMode,
                    lanAccess = s.apiLanAccess,
                    mdnsEnabled = s.mdnsEnabled,
                )
            }
        }
        viewModelScope.launch {
            container.runtimeCoordinator.stats()
                .onStart { emit(emptyList()) }
                .collect { stats ->
                    _state.value = _state.value.copy(
                        requestsTotal = stats.sumOf { it.totalRequests },
                        tokensGenerated = stats.sumOf { it.generatedTokens },
                    )
                }
        }
        viewModelScope.launch {
            while (isActive) {
                tick()
                delay(1000)
            }
        }
    }

    /** Supplies an application context for LAN discovery and service starts. */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        viewModelScope.launch { tick() }
    }

    fun startServer(context: Context) {
        if (_state.value.busy || _state.value.running) return
        val app = context.applicationContext
        if (appContext == null) appContext = app
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val ok = withContext(Dispatchers.IO) { container.apiServer.start() }
                if (ok) {
                    startedAtMs = System.currentTimeMillis()
                    container.settingsRepository.update { it.copy(apiEnabled = true) }
                    // Keep the process in the foreground while the server runs.
                    runCatching {
                        ContextCompat.startForegroundService(app, Intent(app, RuntimeService::class.java))
                    }
                    if (_state.value.mdnsEnabled) {
                        withContext(Dispatchers.IO) { container.lanAdvertiser.start(_state.value.port) }
                    }
                    rebuild(running = true)
                    _state.value = _state.value.copy(running = true, message = "API server started")
                } else {
                    _state.value = _state.value.copy(
                        error = "Could not start the API server — the port may already be in use. Try a different port in Settings.",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Failed to start server: ${e.message ?: "unknown error"}")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun stopServer() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                withContext(Dispatchers.IO) {
                    container.apiServer.stop()
                    container.lanAdvertiser.stop()
                }
                container.settingsRepository.update { it.copy(apiEnabled = false) }
                startedAtMs = 0L
                rebuild(running = false)
                _state.value = _state.value.copy(running = false, message = "API server stopped")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Failed to stop server: ${e.message ?: "unknown error"}")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun restartServer(context: Context) {
        if (_state.value.busy) return
        val app = context.applicationContext
        if (appContext == null) appContext = app
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                withContext(Dispatchers.IO) { container.apiServer.stop() }
                val ok = withContext(Dispatchers.IO) { container.apiServer.start() }
                if (ok) {
                    startedAtMs = System.currentTimeMillis()
                    runCatching {
                        ContextCompat.startForegroundService(app, Intent(app, RuntimeService::class.java))
                    }
                    if (_state.value.mdnsEnabled) {
                        withContext(Dispatchers.IO) { container.lanAdvertiser.start(_state.value.port) }
                    }
                    rebuild(running = true)
                    _state.value = _state.value.copy(running = true, message = "API server restarted")
                } else {
                    rebuild(running = false)
                    _state.value = _state.value.copy(
                        running = false,
                        error = "Could not restart the API server — the port may already be in use.",
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Restart failed: ${e.message ?: "unknown error"}")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun setTlsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.update { it.copy(apiTlsEnabled = enabled) }
        }
    }

    fun generateSelfSigned() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val pair = withContext(Dispatchers.Default) { container.apiServer.generateSelfSignedCert() }
                val ok = withContext(Dispatchers.IO) { container.apiServer.startWithTls(pair.first, pair.second) }
                if (ok) {
                    container.settingsRepository.update { it.copy(apiTlsEnabled = true) }
                    rebuild(running = true)
                    _state.value = _state.value.copy(message = "HTTPS enabled with a self-signed certificate")
                } else {
                    _state.value = _state.value.copy(error = "Could not start the HTTPS listener.")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Certificate generation failed: ${e.message ?: "unknown error"}")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun importTls(certPem: String, keyPem: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val ok = withContext(Dispatchers.IO) { container.apiServer.startWithTls(certPem, keyPem) }
                if (ok) {
                    container.settingsRepository.update { it.copy(apiTlsEnabled = true) }
                    rebuild(running = true)
                    _state.value = _state.value.copy(message = "Imported certificate applied — HTTPS is active")
                } else {
                    _state.value = _state.value.copy(error = "The certificate was rejected. Make sure you selected valid PEM files.")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Could not apply certificate: ${e.message ?: "invalid PEM files"}")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private suspend fun tick() {
        val running = withContext(Dispatchers.IO) { container.apiServer.isRunning() }
        if (running) {
            if (startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
        } else {
            startedAtMs = 0L
        }
        rebuild(running)
        _state.value = _state.value.copy(
            running = running,
            uptimeSeconds = if (running && startedAtMs > 0) (System.currentTimeMillis() - startedAtMs) / 1000 else 0L,
        )
    }

    private suspend fun rebuild(running: Boolean) {
        if (!running) {
            if (_state.value.endpoints.isNotEmpty() || _state.value.qrBitmap != null || _state.value.webConsoleUrl != null) {
                _state.value = _state.value.copy(endpoints = emptyList(), qrBitmap = null, webConsoleUrl = null)
            }
            lastQrKey = null
            return
        }
        val endpoint = withContext(Dispatchers.IO) { container.apiServer.endpoint() }
        val context = appContext
        val lanIps = if (context != null && _state.value.lanAccess) {
            withContext(Dispatchers.IO) { LanAddresses.ipv4Addresses(context) }
        } else {
            emptyList()
        }
        val endpoints = buildList {
            add("http://127.0.0.1:${endpoint.port}")
            lanIps.forEach { add("http://$it:${endpoint.port}") }
            if (endpoint.tlsEnabled && endpoint.tlsPort != null) {
                add("https://127.0.0.1:${endpoint.tlsPort}")
                lanIps.forEach { add("https://$it:${endpoint.tlsPort}") }
            }
        }
        val qrHost = lanIps.firstOrNull() ?: "127.0.0.1"
        val qrKey = "$qrHost:${endpoint.port}:${endpoint.tlsEnabled}"
        val qr = if (qrKey != lastQrKey) {
            lastQrKey = qrKey
            withContext(Dispatchers.Default) {
                QrCodes.generate(QrCodes.apiConnectPayload(qrHost, endpoint.port, endpoint.tlsEnabled))
            }
        } else {
            _state.value.qrBitmap
        }
        _state.value = _state.value.copy(
            endpoints = endpoints,
            qrBitmap = qr,
            webConsoleUrl = "http://127.0.0.1:${endpoint.port}/",
        )
    }
}
