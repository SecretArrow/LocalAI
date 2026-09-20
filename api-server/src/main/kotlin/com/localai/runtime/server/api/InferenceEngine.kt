package com.localai.runtime.server.api

import kotlinx.coroutines.flow.Flow

/**
 * The inference engine contract. Implemented by the app-side runtime coordinator
 * and consumed by the embedded API server and the chat UI.
 */
interface InferenceEngine {

    /** IDs of currently loaded/running models. */
    suspend fun listRunning(): List<String>

    /** Loads a model and starts it. Returns the updated model view. Throws [EngineException] on failure. */
    suspend fun start(modelId: String): ServerModel

    /** Unloads a running model. Returns the updated model view. */
    suspend fun stop(modelId: String): ServerModel

    /** Restarts a running model. */
    suspend fun restart(modelId: String): ServerModel

    /**
     * Streaming generation. The cold flow emits text chunks as they are produced,
     * completes when generation finishes and throws [EngineException] on failure.
     * Backpressure: the engine drops nothing; collect on a dispatcher suited to the consumer.
     */
    fun generate(modelId: String, messages: List<ChatMessage>, params: CompletionParams): Flow<String>

    /** Runs a benchmark against a running model. */
    suspend fun benchmark(modelId: String): BenchmarkResult

    /** Live stats stream for all running models. */
    fun stats(): Flow<List<EngineStats>>
}

/** Thrown for every user-facing inference failure with a readable, non-technical message. */
class EngineException(
    message: String,
    val reason: Reason,
    val detail: String? = null,
) : Exception(message) {
    enum class Reason { MODEL_NOT_FOUND, MODEL_NOT_RUNNING, BACKEND_UNAVAILABLE, INSUFFICIENT_MEMORY, INCOMPATIBLE_MODEL, LOAD_FAILED, GENERATION_FAILED, BUSY }
}
