package com.localai.runtime.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.PairedDevice
import com.localai.runtime.pairing.PairingSession
import com.localai.runtime.runtime.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DevicesUiState(
    val devices: List<PairedDevice> = emptyList(),
    val session: PairingSession? = null,
    val secondsLeft: Int = 0,
    val generatedToken: String? = null,
    val mdnsEnabled: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Device pairing view model: runs the 60-second pairing code flow, shows the
 * one-time device token after approval and manages the paired device list.
 */
class DevicesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            container.pairingManager.devices.collect { devices ->
                _state.value = _state.value.copy(devices = devices.filter { !it.revoked })
            }
        }
        viewModelScope.launch {
            container.settingsRepository.flow.collect { s ->
                _state.value = _state.value.copy(mdnsEnabled = s.mdnsEnabled)
            }
        }
    }

    fun beginPairing() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val session = container.pairingManager.beginPairing()
                _state.value = _state.value.copy(
                    session = session,
                    generatedToken = null,
                    secondsLeft = ((session.expiresAtMs - System.currentTimeMillis()) / 1000)
                        .toInt().coerceAtLeast(0),
                )
                countdownJob?.cancel()
                countdownJob = viewModelScope.launch {
                    while (isActive) {
                        delay(1000)
                        val left = ((session.expiresAtMs - System.currentTimeMillis()) / 1000).toInt()
                        if (left <= 0) {
                            _state.value = _state.value.copy(session = null, secondsLeft = 0)
                            break
                        }
                        _state.value = _state.value.copy(secondsLeft = left)
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Could not start pairing.")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun approveDevice(deviceName: String) {
        val session = _state.value.session ?: return
        val name = deviceName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val token = container.pairingManager.approve(session, name)
                countdownJob?.cancel()
                _state.value = _state.value.copy(session = null, secondsLeft = 0, generatedToken = token)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Pairing failed.")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun revoke(id: String) {
        viewModelScope.launch {
            runCatching { container.pairingManager.revoke(id) }
                .onFailure { e -> _state.value = _state.value.copy(error = e.message ?: "Could not revoke the device.") }
        }
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { container.pairingManager.rename(id, trimmed) }
                .onFailure { e -> _state.value = _state.value.copy(error = e.message ?: "Could not rename the device.") }
        }
    }

    fun setMdns(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.update { it.copy(mdnsEnabled = enabled) }
        }
    }

    fun dismissToken() {
        _state.value = _state.value.copy(generatedToken = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}
