package com.localai.runtime.server.api

import kotlinx.serialization.Serializable

/**
 * Shared API types used by the embedded HTTP server, the inference engine and the UI layer.
 * This module is pure Kotlin/JVM so it can be consumed by Android modules and tested on the JVM.
 */

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class CompletionParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val seed: Int = -1,
    val maxTokens: Int = 512,
    val stream: Boolean = true,
    val stop: List<String> = emptyList(),
)

@Serializable
data class ServerModel(
    val id: String,
    val name: String,
    val version: String = "",
    val format: String,
    val quantization: String = "",
    val sizeBytes: Long = 0,
    val state: String,
    val backend: String? = null,
    val contextLength: Int = 0,
    val minRamMb: Int = 0,
    val backends: List<String> = emptyList(),
)

@Serializable
data class EngineStats(
    val modelId: String,
    val state: String,
    val backend: String,
    val tokensPerSecond: Float = 0f,
    val promptTokensPerSecond: Float = 0f,
    val memoryUsageBytes: Long = 0,
    val activeRequests: Int = 0,
    val queuedRequests: Int = 0,
    val totalRequests: Long = 0,
    val generatedTokens: Long = 0,
)

@Serializable
data class BenchmarkResult(
    val modelId: String,
    val backend: String,
    val promptTokensPerSecond: Double,
    val generationTokensPerSecond: Double,
    val peakMemoryBytes: Long,
    val startupTimeMs: Long,
)

@Serializable
data class ServerLogEntry(
    val ts: Long,
    val method: String,
    val path: String,
    val status: Int,
    val latencyMs: Long,
    val model: String? = null,
    val client: String? = null,
    val error: String? = null,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
)

@Serializable
data class ApiLimits(
    val maxRequestBytes: Long = 10L * 1024 * 1024,
    val maxConcurrent: Int = 4,
    val queueSize: Int = 16,
    val requestTimeoutSeconds: Long = 300,
    val maxPromptChars: Int = 200_000,
)

/** Transport configuration snapshot for the API server. */
@Serializable
data class ServerEndpoint(
    val host: String,
    val port: Int,
    val tlsPort: Int? = null,
    val tlsEnabled: Boolean = false,
)
