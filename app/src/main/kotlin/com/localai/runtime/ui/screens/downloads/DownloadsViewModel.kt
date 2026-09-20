package com.localai.runtime.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.DownloadProgress
import com.localai.runtime.core.model.DownloadState
import com.localai.runtime.runtime.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadsUiState(
    /** All non-terminal downloads plus recently completed ones, active first. */
    val downloads: List<DownloadProgress> = emptyList(),
    val error: String? = null,
)

class DownloadsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.downloadManager.progress
                .onStart { emit(emptyList()) }
                .collect { list ->
                    _state.update {
                        it.copy(
                            downloads = list.sortedWith(
                                compareByDescending<DownloadProgress> { d -> d.state.isActive() }
                                    .thenBy { d -> d.id },
                            ),
                        )
                    }
                }
        }
    }

    // ---------------------------------------------------------------- actions

    fun pause(id: Long) = runAction { container.downloadManager.pause(id) }

    fun resume(id: Long) = runAction { container.downloadManager.resume(id) }

    fun cancel(id: Long) = runAction { container.downloadManager.cancel(id) }

    fun retry(id: Long) = runAction { container.downloadManager.retry(id) }

    fun pauseAll() = runAction { container.downloadManager.pauseAll() }

    fun resumeAll() = runAction { container.downloadManager.resumeAll() }

    fun cancelAll() = runAction { container.downloadManager.cancelAll() }

    fun clearError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------- internals

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}

/** True for states that count towards the "active" total. */
private fun DownloadState.isActive(): Boolean =
    this == DownloadState.QUEUED ||
        this == DownloadState.CONNECTING ||
        this == DownloadState.DOWNLOADING ||
        this == DownloadState.VERIFYING
