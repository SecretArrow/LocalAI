package com.localai.runtime.ui.screens.modeldetail

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.BackendInfo
import com.localai.runtime.core.model.DownloadProgress
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.RuntimeConfig
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.service.RuntimeService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelDetailUiState(
    val model: ModelInfo? = null,
    /** Editable runtime configuration draft (defaults when nothing stored yet). */
    val config: RuntimeConfig? = null,
    val download: DownloadProgress? = null,
    val backends: List<BackendInfo> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val deleted: Boolean = false,
)

class ModelDetailViewModel(
    private val container: AppContainer,
    private val modelId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ModelDetailUiState())
    val state: StateFlow<ModelDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                container.modelRepository.observe(modelId).onStart { emit(null) },
                container.modelRepository.observeConfig(modelId).onStart { emit(null) },
            ) { model, savedConfig -> model to savedConfig }
                .collect { (model, savedConfig) ->
                    val config = savedConfig ?: model?.let { m ->
                        runCatching {
                            val defaults = container.settingsRepository.flow.first()
                            container.modelRepository.configOrDefault(m, defaults)
                        }.getOrNull()
                    }
                    _state.update { current ->
                        current.copy(
                            model = model,
                            config = config ?: current.config ?: RuntimeConfig(modelId = modelId),
                        )
                    }
                }
        }
        // Cache backend availability for the backend dropdown (spec §9).
        viewModelScope.launch {
            val infos = runCatching { container.backendRegistry.detectAll() }.getOrDefault(emptyList())
            _state.update { it.copy(backends = infos) }
        }
        viewModelScope.launch {
            container.downloadManager.progress.collect { list ->
                _state.update { it.copy(download = list.firstOrNull { p -> p.modelId == modelId }) }
            }
        }
    }

    // ---------------------------------------------------------------- actions

    fun start() {
        try {
            RuntimeService.startModel(container.context, modelId)
            _state.update { it.copy(message = "Starting model…") }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Could not start model: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun stop() {
        container.applicationScope.launch {
            runCatching { container.runtimeCoordinator.stop(modelId) }
                .onFailure { e ->
                    container.appLogger.e("ModelDetailVM", "Failed to stop $modelId", e)
                    _state.update { it.copy(error = "Could not stop model: ${e.message}") }
                }
        }
    }

    fun restart() {
        viewModelScope.launch {
            try {
                val running = container.runtimeCoordinator.listRunning()
                if (modelId in running) {
                    container.runtimeCoordinator.restart(modelId)
                    _state.update { it.copy(message = "Restarting model…") }
                } else {
                    RuntimeService.startModel(container.context, modelId)
                    _state.update { it.copy(message = "Starting model…") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not restart model: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun saveConfig(config: RuntimeConfig) {
        viewModelScope.launch {
            try {
                container.modelRepository.updateConfig(config)
                _state.update { it.copy(message = "Configuration saved") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not save configuration: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Exports the model's runtime config as JSON and shares it via the system sheet. */
    fun exportConfig() {
        viewModelScope.launch {
            try {
                val json = container.configPorter.exportModelConfig(modelId)
                val safeName = _state.value.model?.name
                    ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    ?.takeIf { it.isNotBlank() } ?: modelId
                val fileName = "$safeName-config.json"
                withContext(Dispatchers.IO) {
                    val sharedDir = File(container.cacheDir, "shared")
                    sharedDir.mkdirs()
                    File(sharedDir, fileName).writeText(json)
                }
                shareConfigFile(fileName)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not export config: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Deletes the model (call sites must confirm with the user first — never silent). */
    fun delete() {
        viewModelScope.launch {
            try {
                _state.value.download?.let { active ->
                    runCatching { container.downloadManager.cancel(active.id) }
                }
                val model = container.modelRepository.get(modelId)
                model?.localPath?.takeIf { it.startsWith("/") }?.let { path ->
                    withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
                }
                container.modelRepository.delete(modelId)
                _state.update { it.copy(deleted = true) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not delete model: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------- internals

    private fun shareConfigFile(fileName: String) {
        val context = container.context
        val file = File(File(container.cacheDir, "shared"), fileName)
        if (!file.exists()) {
            _state.update { it.copy(error = "Export file is missing") }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share model config").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { e ->
                _state.update { it.copy(error = "Could not share config: ${e.message}") }
            }
    }
}
