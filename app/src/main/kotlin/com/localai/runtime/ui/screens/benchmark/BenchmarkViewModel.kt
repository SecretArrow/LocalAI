package com.localai.runtime.ui.screens.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.LocalAiException
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.server.api.BenchmarkResult
import com.localai.runtime.server.api.EngineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BenchmarkUiState(
    val models: List<ModelInfo> = emptyList(),
    val selectedModelId: String? = null,
    val running: Boolean = false,
    val statusText: String? = null,
    val result: BenchmarkResult? = null,
    val error: String? = null,
)

/**
 * Benchmark flow: requires the model to be RUNNING. When it is not, the run
 * starts it through the coordinator first and waits until it reports as running.
 */
class BenchmarkViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(BenchmarkUiState())
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val defaults = container.settingsRepository.flow.first()
            container.modelRepository.models.collect { models ->
                val installed = models.filter { it.installed }
                val current = _state.value.selectedModelId
                _state.value = _state.value.copy(
                    models = installed,
                    selectedModelId = when {
                        current != null && installed.any { it.id == current } -> current
                        defaults.defaultModelId != null && installed.any { it.id == defaults.defaultModelId } ->
                            defaults.defaultModelId
                        else -> installed.firstOrNull()?.id
                    },
                )
            }
        }
    }

    fun select(modelId: String) {
        if (!_state.value.running) {
            _state.value = _state.value.copy(selectedModelId = modelId, result = null, error = null)
        }
    }

    fun run() {
        val modelId = _state.value.selectedModelId ?: return
        if (_state.value.running) return
        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, error = null, result = null, statusText = null)
            try {
                if (modelId !in container.runtimeCoordinator.listRunning()) {
                    _state.value = _state.value.copy(statusText = "Starting model…")
                    container.runtimeCoordinator.start(modelId)
                    // Wait until the coordinator actually reports the model as running (max 60 s).
                    val deadline = System.currentTimeMillis() + 60_000
                    while (System.currentTimeMillis() < deadline &&
                        modelId !in container.runtimeCoordinator.listRunning()
                    ) {
                        delay(500)
                    }
                    if (modelId !in container.runtimeCoordinator.listRunning()) {
                        _state.value = _state.value.copy(error = "The model did not start within 60 seconds.")
                        return@launch
                    }
                }
                _state.value = _state.value.copy(statusText = "Benchmarking…")
                val result = withContext(Dispatchers.Default) {
                    container.runtimeCoordinator.benchmark(modelId)
                }
                _state.value = _state.value.copy(result = result)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = friendly(e))
            } finally {
                _state.value = _state.value.copy(running = false, statusText = null)
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun friendly(e: Exception): String = when (e) {
        is EngineException -> e.message ?: "Benchmark failed."
        is LocalAiException -> e.message ?: "Benchmark failed."
        else -> "Benchmark failed. ${e.message ?: "Please try again."}"
    }
}
