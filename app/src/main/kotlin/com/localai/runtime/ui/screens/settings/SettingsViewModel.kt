package com.localai.runtime.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.BackendInfo
import com.localai.runtime.core.model.DeviceCapabilities
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.security.TokenGenerator
import com.localai.runtime.runtime.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Supports the Settings screen: applies every change through
 * [com.localai.runtime.core.settings.SettingsRepository.update] and exposes the
 * auxiliary data the sections need (detected backends, installed models, API token mask).
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _backends = MutableStateFlow<List<BackendInfo>>(emptyList())
    val backends: StateFlow<List<BackendInfo>> = _backends.asStateFlow()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _tokenMask = MutableStateFlow<String?>(null)
    val tokenMask: StateFlow<String?> = _tokenMask.asStateFlow()

    private val _newToken = MutableStateFlow<String?>(null)
    val newToken: StateFlow<String?> = _newToken.asStateFlow()

    private val _capabilities = MutableStateFlow<DeviceCapabilities?>(null)
    val capabilities: StateFlow<DeviceCapabilities?> = _capabilities.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { container.backendRegistry.detectAll() }
                .onSuccess { _backends.value = it }
        }
        viewModelScope.launch {
            container.modelRepository.models.collect { _models.value = it }
        }
        viewModelScope.launch {
            _capabilities.value = withContext(Dispatchers.IO) {
                runCatching { container.deviceProbe.probe() }.getOrNull()
            }
        }
        refreshTokenMask()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            container.settingsRepository.update(transform)
        }
    }

    /** Generates a new API token, stores it encrypted and returns it for one-time display. */
    fun generateToken() {
        val token = TokenGenerator.randomToken()
        container.secretStore.saveToken(TOKEN_NAME, token)
        container.authStore.invalidateCache()
        _newToken.value = token
        refreshTokenMask()
    }

    fun revokeToken() {
        container.secretStore.clearToken(TOKEN_NAME)
        container.authStore.invalidateCache()
        refreshTokenMask()
    }

    fun onTokenShown() {
        _newToken.value = null
    }

    private fun refreshTokenMask() {
        val token = container.secretStore.readToken(TOKEN_NAME)
        _tokenMask.value = token?.let { it.take(8) + "•••" }
    }

    private companion object {
        const val TOKEN_NAME = "api_token"
    }
}
