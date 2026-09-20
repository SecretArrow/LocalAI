package com.localai.runtime.export

import android.content.Context
import com.localai.runtime.core.model.AuthMode
import com.localai.runtime.core.model.BackendType
import com.localai.runtime.core.model.LocalAiException
import com.localai.runtime.core.model.RuntimeProfileName
import com.localai.runtime.core.repo.ModelRepository
import com.localai.runtime.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Settings/model-config export and import (spec §8/§40).
 *
 * Exports NEVER contain secrets: no API token, no device tokens, no certificate keys —
 * only operational settings and per-model runtime parameters. Imports are lenient:
 * unknown keys and sections are ignored, recognised values are coerced into sane ranges,
 * and the returned count reports how many fields were applied.
 */
class ConfigPorter(
    private val context: Context,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun exportSettingsJson(): String {
        val settings = settingsRepository.flow.first()
        val installed = modelRepository.listAll().filter { it.installed }
        return buildJsonObject {
            put("version", 1)
            put("runtime", buildJsonObject {
                put("default_backend", settings.defaultBackendId ?: "auto")
                put("default_threads", settings.defaultThreads)
                put("default_context", settings.defaultContext)
                put("default_gpu_layers", settings.defaultGpuLayers)
                put("default_model", settings.defaultModelId ?: "")
            })
            put("api", buildJsonObject {
                put("host", settings.apiHost)
                put("port", settings.apiPort)
                put("tls_enabled", settings.apiTlsEnabled)
                put("tls_port", settings.apiTlsPort)
                put("auth_mode", settings.apiAuthMode.name)
                put("max_concurrent", settings.apiMaxConcurrent)
                put("queue_size", settings.apiQueueSize)
                put("timeout_seconds", settings.apiTimeoutSeconds)
            })
            put("downloads", buildJsonObject {
                put("wifi_only", settings.wifiOnly)
                put("max_parallel", settings.maxParallelDownloads)
                put("auto_retry", settings.autoRetry)
                put("auto_resume", settings.autoResume)
            })
            put("models", buildJsonArray {
                for (model in installed) {
                    val config = modelRepository.config(model.id)
                    add(
                        buildJsonObject {
                            put("id", model.id)
                            put("name", model.name)
                            put("backend", config.backend?.id ?: "auto")
                            put("threads", config.cpuThreads)
                            put("context_length", config.contextLength)
                            put("temperature", config.temperature.toDouble())
                            put("top_p", config.topP.toDouble())
                        },
                    )
                }
            })
        }.toString()
    }

    suspend fun exportModelConfig(modelId: String): String {
        val model = modelRepository.get(modelId)
            ?: throw LocalAiException.NotFound("Model not found: $modelId")
        val config = modelRepository.config(modelId)
        return buildJsonObject {
            put("version", 1)
            put("model", buildJsonObject {
                put("id", model.id)
                put("name", model.name)
                put("backend", config.backend?.id ?: "auto")
                put("threads", config.cpuThreads)
                put("gpu_layers", config.gpuLayers)
                put("context_length", config.contextLength)
                put("batch_size", config.batchSize)
                put("temperature", config.temperature.toDouble())
                put("top_p", config.topP.toDouble())
                put("top_k", config.topK)
                put("min_p", config.minP.toDouble())
                put("repeat_penalty", config.repeatPenalty.toDouble())
                put("seed", config.seed)
                put("streaming", config.streaming)
                put("memory_mapping", config.memoryMapping)
                put("flash_attention", config.flashAttention)
                put("parallel", config.parallel)
                put("profile", config.profile.name)
            })
        }.toString()
    }

    suspend fun importSettingsJson(json: String): Result<Int> = try {
        val root = lenientJson.parseToJsonElement(json) as? JsonObject
            ?: throw LocalAiException.Storage("Not a valid settings export: expected a JSON object")
        var applied = 0
        settingsRepository.update { current ->
            var s = current
            root["runtime"].objOrNull()?.let { section ->
                section["default_backend"].strOrNull()?.let { v ->
                    s = s.copy(defaultBackendId = v.trim().takeUnless { it.isEmpty() || it.equals("auto", true) })
                    applied++
                }
                section["default_threads"].intOrNullValue()?.let { s = s.copy(defaultThreads = it.coerceIn(-1, 64)); applied++ }
                section["default_context"].intOrNullValue()?.let { s = s.copy(defaultContext = it.coerceIn(256, 131072)); applied++ }
                section["default_gpu_layers"].intOrNullValue()?.let { s = s.copy(defaultGpuLayers = it.coerceIn(-1, 512)); applied++ }
                section["default_model"].strOrNull()?.let {
                    s = s.copy(defaultModelId = it.trim().takeUnless { name -> name.isEmpty() })
                    applied++
                }
            }
            root["api"].objOrNull()?.let { section ->
                section["host"].strOrNull()?.let { s = s.copy(apiHost = it.trim()); applied++ }
                section["port"].intOrNullValue()?.let { s = s.copy(apiPort = it.coerceIn(1, 65535)); applied++ }
                section["tls_enabled"].boolOrNullValue()?.let { s = s.copy(apiTlsEnabled = it); applied++ }
                section["tls_port"].intOrNullValue()?.let { s = s.copy(apiTlsPort = it.coerceIn(1, 65535)); applied++ }
                section["auth_mode"].strOrNull()?.let { v ->
                    val mode = runCatching { AuthMode.valueOf(v.trim().uppercase()) }.getOrNull()
                    if (mode != null) {
                        s = s.copy(apiAuthMode = mode)
                        applied++
                    }
                }
                section["max_concurrent"].intOrNullValue()?.let { s = s.copy(apiMaxConcurrent = it.coerceIn(1, 64)); applied++ }
                section["queue_size"].intOrNullValue()?.let { s = s.copy(apiQueueSize = it.coerceIn(1, 256)); applied++ }
                section["timeout_seconds"].longOrNullValue()?.let { s = s.copy(apiTimeoutSeconds = it.coerceIn(5L, 3600L)); applied++ }
            }
            root["downloads"].objOrNull()?.let { section ->
                section["wifi_only"].boolOrNullValue()?.let { s = s.copy(wifiOnly = it); applied++ }
                section["max_parallel"].intOrNullValue()?.let { s = s.copy(maxParallelDownloads = it.coerceIn(1, 8)); applied++ }
                section["auto_retry"].boolOrNullValue()?.let { s = s.copy(autoRetry = it); applied++ }
                section["auto_resume"].boolOrNullValue()?.let { s = s.copy(autoResume = it); applied++ }
            }
            s
        }
        Result.success(applied)
    } catch (e: CancellationException) {
        throw e
    } catch (e: LocalAiException) {
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(LocalAiException.Storage("Could not import settings", e.message ?: e.javaClass.simpleName))
    }

    suspend fun importModelConfig(modelId: String, json: String): Result<Unit> = try {
        val model = modelRepository.get(modelId)
            ?: return Result.failure(LocalAiException.NotFound("Model not found: $modelId"))
        val root = lenientJson.parseToJsonElement(json) as? JsonObject
            ?: throw LocalAiException.Storage("Not a valid model config: expected a JSON object")
        val section = root["model"].objOrNull() ?: root
        var config = modelRepository.configOrDefault(model, settingsRepository.flow.first())
        section["backend"].strOrNull()?.let { v ->
            config = config.copy(
                backend = v.trim().takeIf { it.isNotEmpty() && !it.equals("auto", true) }?.let { BackendType.fromId(it) },
            )
        }
        section["threads"].intOrNullValue()?.let { config = config.copy(cpuThreads = it.coerceIn(-1, 64)) }
        section["gpu_layers"].intOrNullValue()?.let { config = config.copy(gpuLayers = it.coerceIn(-1, 512)) }
        section["context_length"].intOrNullValue()?.let { config = config.copy(contextLength = it.coerceIn(256, 131072)) }
        section["batch_size"].intOrNullValue()?.let { config = config.copy(batchSize = it.coerceIn(64, 4096)) }
        section["temperature"].floatOrNullValue()?.let { config = config.copy(temperature = it.coerceIn(0f, 2f)) }
        section["top_p"].floatOrNullValue()?.let { config = config.copy(topP = it.coerceIn(0.01f, 1f)) }
        section["top_k"].intOrNullValue()?.let { config = config.copy(topK = it.coerceIn(0, 200)) }
        section["min_p"].floatOrNullValue()?.let { config = config.copy(minP = it.coerceIn(0f, 0.5f)) }
        section["repeat_penalty"].floatOrNullValue()?.let { config = config.copy(repeatPenalty = it.coerceIn(0.5f, 2.5f)) }
        section["seed"].longOrNullValue()?.let { config = config.copy(seed = it) }
        section["streaming"].boolOrNullValue()?.let { config = config.copy(streaming = it) }
        section["memory_mapping"].boolOrNullValue()?.let { config = config.copy(memoryMapping = it) }
        section["flash_attention"].boolOrNullValue()?.let { config = config.copy(flashAttention = it) }
        section["parallel"].intOrNullValue()?.let { config = config.copy(parallel = it.coerceIn(1, 8)) }
        section["profile"].strOrNull()?.let { v ->
            runCatching { RuntimeProfileName.valueOf(v.trim().uppercase()) }.getOrNull()?.let { profile ->
                config = config.copy(profile = profile)
            }
        }
        modelRepository.updateConfig(config.copy(modelId = modelId))
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: LocalAiException) {
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(LocalAiException.Storage("Could not import model configuration", e.message ?: e.javaClass.simpleName))
    }

    private companion object {
        val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

// ---------------------------------------------------------------- lenient JSON helpers

private fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.primitiveOrNull(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

private fun JsonElement?.strOrNull(): String? = primitiveOrNull()?.contentOrNull

private fun JsonElement?.intOrNullValue(): Int? = primitiveOrNull()?.intOrNull

private fun JsonElement?.longOrNullValue(): Long? = primitiveOrNull()?.longOrNull

private fun JsonElement?.boolOrNullValue(): Boolean? = primitiveOrNull()?.booleanOrNull

private fun JsonElement?.floatOrNullValue(): Float? = primitiveOrNull()?.doubleOrNull?.toFloat()
