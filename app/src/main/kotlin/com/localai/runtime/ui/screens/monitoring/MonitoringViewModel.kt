package com.localai.runtime.ui.screens.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.SystemSnapshot
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.server.api.EngineStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

data class MonitoringUiState(
    /** Ring buffer of the last [MAX_HISTORY] system snapshots. */
    val history: List<SystemSnapshot> = emptyList(),
    val latest: SystemSnapshot? = null,
    val engineStats: List<EngineStats> = emptyList(),
    /** Rolling tokens/sec history per running model (last [MAX_HISTORY] samples). */
    val tpsHistory: Map<String, List<Float>> = emptyMap(),
    val modelNames: Map<String, String> = emptyMap(),
)

/** Feeds the monitoring screen from SystemMonitor snapshots and engine stats. */
class MonitoringViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(MonitoringUiState())
    val state: StateFlow<MonitoringUiState> = _state.asStateFlow()

    private val snapshotBuffer = mutableListOf<SystemSnapshot>()

    init {
        viewModelScope.launch {
            container.systemMonitor.snapshots.collect { snapshot ->
                synchronized(snapshotBuffer) {
                    snapshotBuffer.add(snapshot)
                    while (snapshotBuffer.size > MAX_HISTORY) snapshotBuffer.removeAt(0)
                    _state.value = _state.value.copy(
                        history = snapshotBuffer.toList(),
                        latest = snapshot,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.runtimeCoordinator.stats()
                .onStart { emit(emptyList()) }
                .collect { stats -> pushStats(stats) }
        }
        viewModelScope.launch {
            container.modelRepository.models.collect { models ->
                _state.value = _state.value.copy(modelNames = models.associate { it.id to it.name })
            }
        }
    }

    private fun pushStats(stats: List<EngineStats>) {
        val tps = _state.value.tpsHistory.toMutableMap()
        val runningIds = stats.map { it.modelId }.toSet()
        tps.keys.retainAll(runningIds)
        stats.forEach { s ->
            val list = (tps[s.modelId] ?: emptyList()).toMutableList()
            list.add(s.tokensPerSecond)
            while (list.size > MAX_HISTORY) list.removeAt(0)
            tps[s.modelId] = list
        }
        _state.value = _state.value.copy(engineStats = stats, tpsHistory = tps)
    }

    private companion object {
        const val MAX_HISTORY = 60
    }
}
