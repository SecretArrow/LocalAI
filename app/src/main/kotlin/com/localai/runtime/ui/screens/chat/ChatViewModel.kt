package com.localai.runtime.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.LocalAiException
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.server.api.ChatMessage
import com.localai.runtime.server.api.CompletionParams
import com.localai.runtime.server.api.EngineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/** A single message bubble in the conversation. */
data class UiMessage(val role: String, val content: String, val streaming: Boolean = false)

/**
 * Single-conversation view model (v1). Keeps the message list, tracks which models
 * are currently running and streams assistant replies from the runtime coordinator.
 */
class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _runningModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val runningModels: StateFlow<List<ModelInfo>> = _runningModels.asStateFlow()

    private val _selectedModelId = MutableStateFlow<String?>(null)
    val selectedModelId: StateFlow<String?> = _selectedModelId.asStateFlow()

    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _temperature = MutableStateFlow(0.7f)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _maxTokens = MutableStateFlow(512)
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var generateJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = container.settingsRepository.flow.first()
            _selectedModelId.value = settings.defaultModelId
        }
        viewModelScope.launch {
            combine(
                container.runtimeCoordinator.stats().onStart { emit(emptyList()) },
                container.modelRepository.models.onStart { emit(emptyList()) },
            ) { stats, models ->
                val runningIds = stats.map { it.modelId }.toSet()
                models.filter { it.id in runningIds }
            }.collect { running ->
                _runningModels.value = running
                val current = _selectedModelId.value
                _selectedModelId.value = when {
                    current != null && running.any { it.id == current } -> current
                    else -> running.firstOrNull()?.id
                }
            }
        }
    }

    /** Appends the user's message and streams an assistant reply. */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _generating.value) return
        val modelId = _selectedModelId.value
        if (modelId == null) {
            _error.value = "No model is running. Start a model from the Models tab first."
            return
        }
        _error.value = null
        val history = _messages.value.map { ChatMessage(it.role, it.content) }
        _messages.value = _messages.value + listOf(
            UiMessage("user", trimmed),
            UiMessage("assistant", "", streaming = true),
        )
        _generating.value = true
        generateJob = viewModelScope.launch {
            try {
                val prompt = buildList {
                    val system = _systemPrompt.value.trim()
                    if (system.isNotEmpty()) add(ChatMessage("system", system))
                    addAll(history)
                    add(ChatMessage("user", trimmed))
                }
                val params = CompletionParams(
                    temperature = _temperature.value,
                    maxTokens = _maxTokens.value,
                    stream = true,
                )
                container.runtimeCoordinator.generate(modelId, prompt, params)
                    .collect { chunk ->
                        if (chunk.isNotEmpty()) appendChunk(chunk)
                    }
            } catch (ce: CancellationException) {
                // User pressed Stop — keep the partial reply.
                throw ce
            } catch (e: Exception) {
                _messages.value = _messages.value.filterNot { it.streaming && it.content.isEmpty() }
                _error.value = friendlyError(e)
            } finally {
                finishStreaming()
                _generating.value = false
            }
        }
    }

    fun stopGeneration() {
        generateJob?.cancel()
    }

    /** Drops the last assistant reply and re-sends the previous user message. */
    fun regenerate() {
        if (_generating.value) return
        val msgs = _messages.value
        val lastUser = msgs.indexOfLast { it.role == "user" }
        if (lastUser < 0) return
        val text = msgs[lastUser].content
        _messages.value = msgs.subList(0, lastUser).toList()
        send(text)
    }

    fun clearConversation() {
        generateJob?.cancel()
        generateJob = null
        _messages.value = emptyList()
        _error.value = null
    }

    fun selectModel(id: String) {
        _selectedModelId.value = id
    }

    fun setSystemPrompt(value: String) {
        _systemPrompt.value = value
    }

    fun setTemperature(value: Float) {
        _temperature.value = value
    }

    fun setMaxTokens(value: Int) {
        _maxTokens.value = value
    }

    fun dismissError() {
        _error.value = null
    }

    private fun appendChunk(chunk: String) {
        val list = _messages.value.toMutableList()
        if (list.isNotEmpty()) {
            val last = list.last()
            list[list.lastIndex] = last.copy(content = last.content + chunk)
            _messages.value = list
        }
    }

    private fun finishStreaming() {
        _messages.value = _messages.value
            .filterNot { it.streaming && it.content.isEmpty() }
            .map { it.copy(streaming = false) }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is EngineException -> e.message ?: "Generation failed."
        is LocalAiException -> e.message ?: "Generation failed."
        else -> "Generation failed. ${e.message ?: "Please try again."}"
    }
}
