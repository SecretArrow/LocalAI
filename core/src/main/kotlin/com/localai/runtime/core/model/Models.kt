package com.localai.runtime.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ModelFormat(val extension: String) {
    GGUF("gguf"),
    ONNX("onnx"),
    TFLITE("tflite"),
    SAFETENSORS("safetensors"),
    UNKNOWN("");

    companion object {
        fun fromFileName(name: String): ModelFormat {
            val lower = name.lowercase()
            return entries.firstOrNull { it.extension.isNotEmpty() && lower.endsWith(it.extension) } ?: UNKNOWN
        }
    }
}

@Serializable
enum class ModelState {
    NOT_INSTALLED,
    DOWNLOADING,
    IMPORTING,
    INSTALLED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
}

@Serializable
enum class BackendType(val id: String, val displayName: String) {
    CPU("cpu", "CPU"),
    GPU_VULKAN("vulkan", "GPU (Vulkan)"),
    GPU_OPENCL("opencl", "GPU (OpenCL)"),
    NNAPI("nnapi", "NNAPI"),
    NPU("npu", "NPU");

    companion object {
        fun fromId(id: String): BackendType = entries.firstOrNull { it.id == id } ?: CPU
    }
}

@Serializable
enum class Availability { AVAILABLE, SUPPORTED, UNAVAILABLE, UNKNOWN }

@Serializable
enum class AuthMode { NONE, API_KEY, BEARER }

@Serializable
enum class DarkMode { LIGHT, DARK, SYSTEM }

@Serializable
data class BackendInfo(
    val type: BackendType,
    val availability: Availability,
    val reason: String = "",
    val details: String = "",
)

@Serializable
data class DeviceCapabilities(
    val abi: List<String> = emptyList(),
    val arch: String = "",
    val cpuCores: Int = 0,
    val totalRamBytes: Long = 0,
    val availableRamBytes: Long = 0,
    val gpuVendor: String = "Unknown",
    val gpuModel: String = "Unknown",
    val vulkan: Availability = Availability.UNKNOWN,
    val opencl: Availability = Availability.UNKNOWN,
    val nnapi: Availability = Availability.UNKNOWN,
    val npu: Availability = Availability.UNKNOWN,
    val androidVersion: String = "",
    val deviceModel: String = "",
    val cpuBackendAvailable: Boolean = false,
) {
    val arm64: Boolean get() = abi.contains("arm64-v8a")
}

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val version: String = "",
    val format: ModelFormat = ModelFormat.UNKNOWN,
    val quantization: String = "",
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    val architecture: String? = null,
    val contextLength: Int = 0,
    val parameterCount: Long = 0,
    val vocabSize: Int = 0,
    val license: String? = null,
    val sourceUrl: String? = null,
    val minRamMb: Int = 0,
    val recommendedRamMb: Int = 0,
    val backends: List<BackendType> = listOf(BackendType.CPU),
    val state: ModelState = ModelState.NOT_INSTALLED,
    val localPath: String? = null,
    val installedAt: Long = 0,
    val updatedAt: Long = 0,
    val favorite: Boolean = false,
    val collections: List<String> = emptyList(),
    val lastError: String? = null,
    val imported: Boolean = false,
) {
    val installed: Boolean get() = localPath != null || state in listOf(ModelState.INSTALLED, ModelState.STARTING, ModelState.RUNNING, ModelState.STOPPING, ModelState.STOPPED, ModelState.FAILED)
}

@Serializable
data class CatalogEntry(
    val id: String,
    val name: String,
    val version: String = "1.0",
    val format: String = "GGUF",
    val quantization: String = "",
    val architecture: String? = null,
    val size: Long = 0,
    val sha256: String? = null,
    val downloadUrl: String,
    val license: String? = null,
    val architectures: List<String> = emptyList(),
    val backends: List<String> = listOf("cpu"),
    val minRamMb: Int = 0,
    val recommendedRamMb: Int = 0,
    val contextLength: Int = 0,
    val parameterCount: Long = 0,
    val description: String = "",
)

@Serializable
data class CatalogManifest(
    val version: Int = 1,
    val updatedAt: String = "",
    val models: List<CatalogEntry> = emptyList(),
)

@Serializable
data class CatalogDiff(
    val newModels: List<CatalogEntry> = emptyList(),
    val updatedModels: List<CatalogEntry> = emptyList(),
    val removedIds: List<String> = emptyList(),
    val catalogVersion: String = "",
)

@Serializable
data class RuntimeConfig(
    val modelId: String,
    val backend: BackendType? = null,
    val cpuThreads: Int = -1,
    val gpuLayers: Int = -1,
    val contextLength: Int = 4096,
    val batchSize: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val seed: Long = -1L,
    val streaming: Boolean = true,
    val memoryMapping: Boolean = true,
    val flashAttention: Boolean = false,
    val parallel: Int = 1,
    val profile: RuntimeProfileName = RuntimeProfileName.CUSTOM,
)

@Serializable
enum class RuntimeProfileName { FAST, BALANCED, QUALITY, CODING, LOW_MEMORY, CUSTOM }

@Serializable
enum class DownloadState { QUEUED, CONNECTING, DOWNLOADING, PAUSED, VERIFYING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class DownloadProgress(
    val id: Long,
    val modelId: String? = null,
    val fileName: String,
    val url: String = "",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSec: Long = 0,
    val etaSeconds: Long = -1,
    val state: DownloadState = DownloadState.QUEUED,
    val error: String? = null,
) {
    val percent: Int get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}

@Serializable
data class SystemSnapshot(
    val ts: Long,
    val cpuPercent: Float = 0f,
    val ramUsedBytes: Long = 0,
    val ramTotalBytes: Long = 0,
    val gpuPercent: Float? = null,
    val npuPercent: Float? = null,
    val temperatureC: Float? = null,
    val batteryPercent: Int? = null,
    val storageUsedBytes: Long = 0,
    val storageTotalBytes: Long = 0,
    val networkOnline: Boolean = false,
)

@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long = 0,
    val revoked: Boolean = false,
)

@Serializable
data class StorageUsage(
    val modelsBytes: Long,
    val downloadsBytes: Long,
    val cacheBytes: Long,
    val logsBytes: Long,
    val tempBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
)

@Serializable
data class ApiLogRecord(
    val id: Long,
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
data class LogLine(val ts: Long, val level: String, val tag: String, val message: String)
