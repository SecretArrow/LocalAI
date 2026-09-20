package com.localai.runtime.core.runtime

import com.localai.runtime.core.model.BackendInfo
import com.localai.runtime.core.model.BackendType
import com.localai.runtime.core.model.Availability
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.RuntimeConfig
import com.localai.runtime.server.api.BenchmarkResult
import com.localai.runtime.server.api.ChatMessage
import com.localai.runtime.server.api.CompletionParams
import kotlinx.coroutines.flow.Flow

/**
 * A pluggable inference backend (CPU today; GPU/NPU stubs stay honest).
 * All signatures are frozen in docs/CONTRACTS.md.
 */
interface InferenceBackend {
    val type: BackendType

    /** Probes the backend against the device; cheap, safe to call repeatedly. */
    suspend fun detect(capabilities: com.localai.runtime.core.model.DeviceCapabilities): BackendInfo

    /** Whether this backend can load [model] at all (format/backend-list check). */
    fun supportsModel(model: ModelInfo): Boolean

    /** Loads the model, or null when this backend cannot load it (never crashes). */
    suspend fun load(model: ModelInfo, config: RuntimeConfig): LoadedModel?
}

/** A loaded, ready-to-generate model instance owned by a backend. */
interface LoadedModel {
    val modelId: String
    val backend: BackendType
    val contextLength: Int

    /** Cold flow of generated text pieces; completes when generation finishes. */
    fun generateStream(
        messages: List<ChatMessage>,
        params: CompletionParams,
    ): Flow<String>

    suspend fun benchmark(): BenchmarkResult

    /** Idempotent release of native resources. */
    suspend fun unload()
}

/**
 * Ordered registry of backends. The constructor list defines preference order —
 * callers pass CPU first so [auto] prefers the CPU runtime.
 *
 * [detectAll] caches results; [available] filters cached AVAILABLE backends
 * that also support the model.
 */
class BackendRegistry(
    private val probe: DeviceProbe,
    private val backends: List<InferenceBackend>,
) {

    @Volatile
    private var detected: Map<BackendType, BackendInfo>? = null

    /** Runs every backend's detect() with a single probe of the device. */
    suspend fun detectAll(): List<BackendInfo> {
        val capabilities = probe.probe()
        val infos = backends.map { backend ->
            try {
                backend.detect(capabilities)
            } catch (t: Throwable) {
                BackendInfo(
                    type = backend.type,
                    availability = Availability.UNAVAILABLE,
                    reason = "Detection failed: ${t.message ?: t.javaClass.simpleName}",
                )
            }
        }
        val byType = LinkedHashMap<BackendType, BackendInfo>()
        for (info in infos) {
            byType[info.type] = info
        }
        detected = byType
        return infos
    }

    /** Backends detected AVAILABLE that can load [model]. Empty until detection has run. */
    fun available(model: ModelInfo): List<InferenceBackend> {
        val cache = detected ?: return emptyList()
        return backends.filter {
            cache[it.type]?.availability == Availability.AVAILABLE && it.supportsModel(model)
        }
    }

    /** First available backend for [model] (list order = preference, CPU first). */
    fun auto(model: ModelInfo): InferenceBackend? = available(model).firstOrNull()
}

/**
 * Honest placeholder for backends that are not bundled in this build.
 * Canonical reasons (spec §2/§36):
 * - Vulkan:  "Vulkan compute integration not bundled in this build; CPU runtime is active"
 * - OpenCL:  "OpenCL compute integration not bundled in this build; CPU runtime is active"
 * - NNAPI:   "NNAPI delegate requires an ONNX/TFLite runtime, not bundled in this build"
 * - NPU:     "No public NPU API exposed by this device"
 */
class StubBackend(
    override val type: BackendType,
    private val reason: String,
) : InferenceBackend {

    override suspend fun detect(capabilities: com.localai.runtime.core.model.DeviceCapabilities): BackendInfo =
        BackendInfo(type = type, availability = Availability.UNAVAILABLE, reason = reason)

    override fun supportsModel(model: ModelInfo): Boolean = false

    override suspend fun load(model: ModelInfo, config: RuntimeConfig): LoadedModel? = null
}
