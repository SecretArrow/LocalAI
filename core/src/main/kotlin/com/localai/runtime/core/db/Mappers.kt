package com.localai.runtime.core.db

import com.localai.runtime.core.model.BackendType
import com.localai.runtime.core.model.CatalogEntry
import com.localai.runtime.core.model.DownloadProgress
import com.localai.runtime.core.model.DownloadState
import com.localai.runtime.core.model.ModelFormat
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.ModelState
import com.localai.runtime.core.model.RuntimeConfig
import com.localai.runtime.core.model.RuntimeProfileName

/** Splits a comma-separated column, trimming and dropping blank segments. */
fun parseCsv(value: String): List<String> =
    if (value.isBlank()) {
        emptyList()
    } else {
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

/** Joins non-blank values with commas. */
fun joinCsv(values: List<String>): String = values.filter { it.isNotBlank() }.joinToString(",")

/** Case-insensitive format lookup ("gguf", "ONNX", "TFLite", "SafeTensors"); anything else -> UNKNOWN. */
fun parseModelFormat(value: String): ModelFormat = when (value.trim().lowercase()) {
    "gguf" -> ModelFormat.GGUF
    "onnx" -> ModelFormat.ONNX
    "tflite" -> ModelFormat.TFLITE
    "safetensors" -> ModelFormat.SAFETENSORS
    else -> ModelFormat.UNKNOWN
}

/** Safe [ModelState] lookup; unknown values degrade to NOT_INSTALLED. */
fun parseModelState(value: String): ModelState =
    runCatching { ModelState.valueOf(value.trim()) }.getOrDefault(ModelState.NOT_INSTALLED)

/** Safe [DownloadState] lookup; unknown values degrade to QUEUED so the row can be re-driven. */
fun parseDownloadState(value: String): DownloadState =
    runCatching { DownloadState.valueOf(value.trim()) }.getOrDefault(DownloadState.QUEUED)

/** Safe [RuntimeProfileName] lookup; unknown values degrade to CUSTOM. */
fun parseRuntimeProfile(value: String): RuntimeProfileName =
    runCatching { RuntimeProfileName.valueOf(value.trim()) }.getOrDefault(RuntimeProfileName.CUSTOM)

/** Resolves a backend id ("cpu", "vulkan", ...) or null when unknown/absent (= Auto). */
fun parseBackendOrNull(id: String?): BackendType? =
    id?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
        BackendType.entries.firstOrNull { it.id == value }
    }

/** Entity -> domain. */
fun ModelEntity.toModelInfo(): ModelInfo = ModelInfo(
    id = id,
    name = name,
    version = version,
    format = parseModelFormat(format),
    quantization = quantization,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    architecture = architecture,
    contextLength = contextLength,
    parameterCount = parameterCount,
    vocabSize = vocabSize,
    license = license,
    sourceUrl = sourceUrl,
    minRamMb = minRamMb,
    recommendedRamMb = recommendedRamMb,
    backends = parseCsv(backendsCsv).map { BackendType.fromId(it) },
    state = parseModelState(state),
    localPath = localPath,
    installedAt = installedAt,
    updatedAt = updatedAt,
    favorite = favorite,
    collections = parseCsv(collectionsCsv),
    lastError = lastError,
    imported = imported,
)

/** Domain -> entity. */
fun ModelInfo.toModelEntity(): ModelEntity = ModelEntity(
    id = id,
    name = name,
    version = version,
    format = format.name,
    quantization = quantization,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    architecture = architecture,
    contextLength = contextLength,
    parameterCount = parameterCount,
    vocabSize = vocabSize,
    license = license,
    sourceUrl = sourceUrl,
    minRamMb = minRamMb,
    recommendedRamMb = recommendedRamMb,
    backendsCsv = joinCsv(backends.map { it.id }),
    state = state.name,
    localPath = localPath,
    installedAt = installedAt,
    updatedAt = updatedAt,
    favorite = favorite,
    collectionsCsv = joinCsv(collections),
    lastError = lastError,
    imported = imported,
)

/** Entity -> domain. */
fun RuntimeConfigEntity.toRuntimeConfig(): RuntimeConfig = RuntimeConfig(
    modelId = modelId,
    backend = parseBackendOrNull(backendId),
    cpuThreads = cpuThreads,
    gpuLayers = gpuLayers,
    contextLength = contextLength,
    batchSize = batchSize,
    temperature = temperature,
    topP = topP,
    topK = topK,
    minP = minP,
    repeatPenalty = repeatPenalty,
    seed = seed,
    streaming = streaming,
    memoryMapping = memoryMapping,
    flashAttention = flashAttention,
    parallel = parallel,
    profile = parseRuntimeProfile(profile),
)

/** Domain -> entity. */
fun RuntimeConfig.toRuntimeConfigEntity(): RuntimeConfigEntity = RuntimeConfigEntity(
    modelId = modelId,
    backendId = backend?.id,
    cpuThreads = cpuThreads,
    gpuLayers = gpuLayers,
    contextLength = contextLength,
    batchSize = batchSize,
    temperature = temperature,
    topP = topP,
    topK = topK,
    minP = minP,
    repeatPenalty = repeatPenalty,
    seed = seed,
    streaming = streaming,
    memoryMapping = memoryMapping,
    flashAttention = flashAttention,
    parallel = parallel,
    profile = profile.name,
)

/** Download row -> progress snapshot; live speed/ETA come from the download manager. */
fun DownloadEntity.toDownloadProgress(
    speedBytesPerSec: Long = 0,
    etaSeconds: Long = -1,
): DownloadProgress = DownloadProgress(
    id = id,
    modelId = modelId,
    fileName = fileName,
    url = url,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    speedBytesPerSec = speedBytesPerSec,
    etaSeconds = etaSeconds,
    state = parseDownloadState(state),
    error = error,
)

/** Catalog entry -> NOT_INSTALLED model shell (sourceUrl carries the downloadUrl). */
fun CatalogEntry.toModelInfo(): ModelInfo = ModelInfo(
    id = id,
    name = name,
    version = version,
    format = parseModelFormat(format),
    quantization = quantization,
    architecture = architecture,
    sizeBytes = size,
    sha256 = sha256,
    sourceUrl = downloadUrl,
    license = license,
    minRamMb = minRamMb,
    recommendedRamMb = recommendedRamMb,
    backends = backends.map { BackendType.fromId(it) }.ifEmpty { listOf(BackendType.CPU) },
    contextLength = contextLength,
    parameterCount = parameterCount,
)
