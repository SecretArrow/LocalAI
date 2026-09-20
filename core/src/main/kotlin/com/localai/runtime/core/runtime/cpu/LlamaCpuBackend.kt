package com.localai.runtime.core.runtime.cpu

import android.os.Debug
import com.localai.runtime.core.model.Availability
import com.localai.runtime.core.model.BackendInfo
import com.localai.runtime.core.model.BackendType
import com.localai.runtime.core.model.LocalAiException
import com.localai.runtime.core.model.ModelFormat
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.RuntimeConfig
import com.localai.runtime.core.runtime.InferenceBackend
import com.localai.runtime.core.runtime.LoadedModel
import com.localai.runtime.server.api.BenchmarkResult
import com.localai.runtime.server.api.ChatMessage
import com.localai.runtime.server.api.CompletionParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * llama.cpp-backed CPU backend for GGUF models. All native calls go through
 * [LlamaBridge]; when the native library is unavailable the backend reports
 * UNAVAILABLE and [load] returns null instead of crashing.
 *
 * Note: [load] expects [ModelInfo.localPath] to be a real filesystem path.
 * content:// URIs must be materialised to a file by the caller before load
 * (see docs/CONTRACTS.md import flow; the backend has no Context by contract).
 */
class CpuBackend : InferenceBackend {

    override val type: BackendType = BackendType.CPU

    override suspend fun detect(capabilities: com.localai.runtime.core.model.DeviceCapabilities): BackendInfo {
        return if (LlamaBridge.available) {
            BackendInfo(
                type = BackendType.CPU,
                availability = Availability.AVAILABLE,
                details = "llama.cpp CPU runtime (GGUF)",
            )
        } else {
            BackendInfo(
                type = BackendType.CPU,
                availability = Availability.UNAVAILABLE,
                reason = "Native runtime library could not be loaded: ${LlamaBridge.loadError ?: "unknown error"}",
            )
        }
    }

    override fun supportsModel(model: ModelInfo): Boolean =
        model.format == ModelFormat.GGUF &&
            (model.backends.isEmpty() || model.backends.contains(BackendType.CPU))

    override suspend fun load(model: ModelInfo, config: RuntimeConfig): LoadedModel? {
        if (!LlamaBridge.available) return null
        val path = model.localPath ?: return null
        val threads = if (config.cpuThreads > 0) {
            config.cpuThreads
        } else {
            min(Runtime.getRuntime().availableProcessors(), 8)
        }
        val loadStartedAt = System.currentTimeMillis()
        val pointer = withContext(Dispatchers.IO) {
            try {
                LlamaBridge.nativeLoadModel(path, config.contextLength, threads, config.memoryMapping)
            } catch (_: Throwable) {
                0L
            }
        }
        if (pointer == 0L) return null
        val startupTimeMs = System.currentTimeMillis() - loadStartedAt
        val actualContextLength = try {
            LlamaBridge.nativeContextLength(pointer)
        } catch (_: Throwable) {
            0
        }

        return object : LoadedModel {
            override val modelId: String = model.id
            override val backend: BackendType = BackendType.CPU
            override val contextLength: Int = actualContextLength

            /** Native handle holder: unloaded swaps it to 0 exactly once. */
            private val handle = AtomicLong(pointer)
            private val unloadGuard = AtomicBoolean(false)

            override fun generateStream(
                messages: List<ChatMessage>,
                params: CompletionParams,
            ): Flow<String> = channelFlow {
                val current = handle.get()
                if (current == 0L) {
                    close(LocalAiException.RuntimeCrashed("Model '$modelId' is no longer loaded"))
                    return@channelFlow
                }
                withContext(Dispatchers.Default) {
                    val roles = messages.map { it.role }.toTypedArray()
                    val contents = messages.map { it.content }.toTypedArray()
                    try {
                        LlamaBridge.nativeGenerate(
                            current,
                            roles,
                            contents,
                            params.temperature,
                            params.topP,
                            params.topK,
                            params.minP,
                            params.repeatPenalty,
                            params.seed,
                            params.maxTokens,
                        ) { piece ->
                            // Returning false stops native generation (cancellation).
                            trySend(piece).isSuccess && !isClosedForSend
                        }
                    } catch (t: Throwable) {
                        close(
                            LocalAiException.RuntimeCrashed(
                                "Generation failed on the CPU runtime",
                                t.message ?: t.javaClass.simpleName,
                            )
                        )
                        return@withContext
                    }
                    close()
                }
            }

            override suspend fun benchmark(): BenchmarkResult {
                val current = handle.get()
                if (current == 0L) {
                    throw LocalAiException.RuntimeCrashed("Model '$modelId' is already unloaded")
                }
                return withContext(Dispatchers.Default) {
                    val before = try {
                        Debug.getNativeHeapAllocatedSize()
                    } catch (_: Throwable) {
                        0L
                    }
                    val stats = try {
                        LlamaBridge.nativeBenchmark(current, 512, 256)
                    } catch (_: Throwable) {
                        null
                    }
                    val after = try {
                        Debug.getNativeHeapAllocatedSize()
                    } catch (_: Throwable) {
                        0L
                    }
                    if (stats == null || stats.size < 2) {
                        throw LocalAiException.RuntimeCrashed("Benchmark failed on the CPU runtime")
                    }
                    BenchmarkResult(
                        modelId = modelId,
                        backend = BackendType.CPU.id,
                        promptTokensPerSecond = stats.getOrElse(0) { 0.0 },
                        generationTokensPerSecond = stats.getOrElse(1) { 0.0 },
                        peakMemoryBytes = maxOf(before, after),
                        startupTimeMs = startupTimeMs,
                    )
                }
            }

            override suspend fun unload() {
                if (unloadGuard.compareAndSet(false, true)) {
                    val toFree = handle.getAndSet(0L)
                    if (toFree != 0L) {
                        withContext(Dispatchers.Default) {
                            try {
                                LlamaBridge.nativeFreeModel(toFree)
                            } catch (_: Throwable) {
                                // never crash on unload
                            }
                        }
                    }
                }
            }
        }
    }
}
