package com.localai.runtime.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.DownloadState
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.ModelState
import com.localai.runtime.core.model.SystemSnapshot
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.service.RuntimeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Dashboard state rendered by [HomeScreen]. */
data class HomeUiState(
    val modelsInstalled: Int = 0,
    val modelsRunning: Int = 0,
    val apiRunning: Boolean = false,
    val apiEndpoint: String? = null,
    val activeDownloads: Int = 0,
    val systemSnapshot: SystemSnapshot? = null,
    val quickStartModel: ModelInfo? = null,
    val message: String? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    // Last known API settings, used by the periodic API status refresh below.
    private var apiEnabled: Boolean = false
    private var apiHost: String = "127.0.0.1"
    private var apiPort: Int = 8080

    init {
        viewModelScope.launch {
            val models: Flow<List<ModelInfo>> =
                container.modelRepository.models.onStart { emit(emptyList()) }
            val stats =
                container.runtimeCoordinator.stats().onStart { emit(emptyList()) }
            val settings =
                container.settingsRepository.flow.onStart { emit(AppSettings()) }
            val downloads =
                container.downloadManager.progress.onStart { emit(emptyList()) }
            val snapshots: Flow<SystemSnapshot?> =
                container.systemMonitor.snapshots
                    .map { it as SystemSnapshot? }
                    .onStart { emit(null) }

            combine(models, stats, settings, downloads, snapshots) { ms, runningStats, cfg, dl, snap ->
                apiEnabled = cfg.apiEnabled
                apiHost = cfg.apiHost
                apiPort = cfg.apiPort
                val runningIds = runningStats.map { it.modelId }.toSet()
                HomeUiState(
                    modelsInstalled = ms.count { it.installed },
                    modelsRunning = if (runningIds.isNotEmpty()) runningIds.size
                    else ms.count { it.state == ModelState.RUNNING },
                    apiRunning = cfg.apiEnabled && container.apiServer.isRunning(),
                    apiEndpoint = if (cfg.apiEnabled) "http://${cfg.apiHost}:${cfg.apiPort}" else null,
                    activeDownloads = dl.count { it.state !in TERMINAL_DOWNLOAD_STATES },
                    systemSnapshot = snap,
                    quickStartModel = pickQuickStart(ms, cfg),
                )
            }.collect { next ->
                // Preserve one-shot messages emitted by actions.
                _state.update { current -> next.copy(message = current.message) }
            }
        }

        // Keep the API running indicator fresh even while no flow emits
        // (the server is started/stopped from other screens).
        viewModelScope.launch {
            while (isActive) {
                refreshApiStatus()
                delay(5_000)
            }
        }
    }

    /** Starts the preferred model (default model, else first installed) via the runtime service. */
    fun startDefaultModel() {
        val model = _state.value.quickStartModel ?: return
        try {
            RuntimeService.startModel(container.context, model.id)
            _state.update { it.copy(message = "Starting ${model.name}…") }
        } catch (e: Exception) {
            _state.update { it.copy(message = "Could not start model: ${e.message}") }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun refreshApiStatus() {
        val running = apiEnabled && container.apiServer.isRunning()
        _state.update { s ->
            s.copy(
                apiRunning = running,
                apiEndpoint = if (apiEnabled) "http://$apiHost:$apiPort" else null,
            )
        }
    }

    private fun pickQuickStart(models: List<ModelInfo>, settings: AppSettings): ModelInfo? {
        models.firstOrNull { it.state == ModelState.RUNNING }?.let { return it }
        settings.defaultModelId?.let { id ->
            models.firstOrNull { it.id == id && it.installed }?.let { return it }
        }
        return models.firstOrNull { it.installed }
    }

    private companion object {
        val TERMINAL_DOWNLOAD_STATES = setOf(
            DownloadState.COMPLETED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        )
    }
}

