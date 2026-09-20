package com.localai.runtime.ui.screens.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.db.ApiLogEntity
import com.localai.runtime.core.model.ApiLogRecord
import com.localai.runtime.core.model.LogLine
import com.localai.runtime.core.util.Formats
import com.localai.runtime.runtime.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LogsUiState(
    val appLogs: List<LogLine> = emptyList(),
    val apiLogs: List<ApiLogRecord> = emptyList(),
    val levels: Set<String> = setOf("DEBUG", "INFO", "WARN", "ERROR"),
    val paused: Boolean = false,
    val privacyMode: Boolean = true,
    val exportFile: File? = null,
    val message: String? = null,
)

/**
 * Combined log viewer: in-app logger lines and API request records from Room.
 * While paused the latest lists are kept but not published to the UI.
 */
class LogsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    private var latestAppLogs: List<LogLine> = emptyList()
    private var latestApiLogs: List<ApiLogRecord> = emptyList()

    init {
        viewModelScope.launch {
            container.settingsRepository.flow.collect { s ->
                _state.value = _state.value.copy(privacyMode = s.privacyMode)
            }
        }
        viewModelScope.launch {
            container.appLogger.lines.collect { lines ->
                latestAppLogs = lines
                if (!_state.value.paused) _state.value = _state.value.copy(appLogs = lines)
            }
        }
        viewModelScope.launch {
            container.database.apiLogDao().observeRecent(200).collect { entities ->
                latestApiLogs = entities.map { it.toRecord() }
                if (!_state.value.paused) _state.value = _state.value.copy(apiLogs = latestApiLogs)
            }
        }
    }

    fun setPaused(paused: Boolean) {
        _state.value = _state.value.copy(paused = paused)
        if (!paused) {
            _state.value = _state.value.copy(appLogs = latestAppLogs, apiLogs = latestApiLogs)
        }
    }

    fun toggleLevel(level: String) {
        val levels = _state.value.levels.toMutableSet()
        if (!levels.remove(level)) levels.add(level)
        if (levels.isEmpty()) return // keep at least one level selected
        _state.value = _state.value.copy(levels = levels)
    }

    fun clear() {
        viewModelScope.launch {
            try {
                container.appLogger.clear()
                container.database.apiLogDao().clear()
                latestAppLogs = emptyList()
                latestApiLogs = emptyList()
                _state.value = _state.value.copy(appLogs = emptyList(), apiLogs = emptyList(), message = "Logs cleared")
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Could not clear logs: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun export() {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(container.cacheDir, "shared").apply { mkdirs() }
                    File(dir, "localai-logs-${System.currentTimeMillis()}.txt")
                        .apply { writeText(buildExportText()) }
                }
                _state.value = _state.value.copy(exportFile = file)
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Export failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun onExportHandled() {
        _state.value = _state.value.copy(exportFile = null)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    private fun buildExportText(): String = buildString {
        appendLine("LocalAI Runtime — log export")
        appendLine()
        appendLine("=== App logs ===")
        latestAppLogs.forEach {
            appendLine("[${Formats.time(it.ts)}] ${it.level}/${it.tag}: ${it.message}")
        }
        appendLine()
        appendLine("=== API requests ===")
        latestApiLogs.forEach {
            val model = it.model?.let { m -> " model=$m" } ?: ""
            appendLine("[${Formats.time(it.ts)}] ${it.method} ${it.path} -> ${it.status} (${it.latencyMs} ms)$model")
        }
    }
}

private fun ApiLogEntity.toRecord(): ApiLogRecord = ApiLogRecord(
    id = id,
    ts = ts,
    method = method,
    path = path,
    status = status,
    latencyMs = latencyMs,
    model = model,
    client = client,
    error = error,
    tokensIn = tokensIn,
    tokensOut = tokensOut,
)
