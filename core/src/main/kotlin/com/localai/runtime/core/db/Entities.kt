package com.localai.runtime.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A known model: catalog entry, imported file or installed runtime model. */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val format: String,
    val quantization: String,
    val sizeBytes: Long,
    val sha256: String?,
    val architecture: String?,
    val contextLength: Int,
    val parameterCount: Long,
    val vocabSize: Int,
    val license: String?,
    val sourceUrl: String?,
    val minRamMb: Int,
    val recommendedRamMb: Int,
    val backendsCsv: String,
    val state: String,
    val localPath: String?,
    val installedAt: Long,
    val updatedAt: Long,
    val favorite: Boolean,
    val collectionsCsv: String,
    val lastError: String?,
    val imported: Boolean,
)

/** Per-model runtime configuration (llama.cpp style parameters). */
@Entity(tableName = "runtime_configs")
data class RuntimeConfigEntity(
    @PrimaryKey val modelId: String,
    val backendId: String?,
    val cpuThreads: Int,
    val gpuLayers: Int,
    val contextLength: Int,
    val batchSize: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val repeatPenalty: Float,
    val seed: Long,
    val streaming: Boolean,
    val memoryMapping: Boolean,
    val flashAttention: Boolean,
    val parallel: Int,
    val profile: String,
)

/** Persisted download state so interrupted downloads survive process restarts. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: String?,
    val url: String,
    val fileName: String,
    val destPath: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val etag: String?,
    val lastModified: String?,
    val sha256: String?,
    val expectedSize: Long,
    val state: String,
    val error: String?,
    val wifiOnly: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One API request log line (method, path, status, latency, token estimates). */
@Entity(tableName = "api_logs")
data class ApiLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val method: String,
    val path: String,
    val status: Int,
    val latencyMs: Long,
    val model: String?,
    val client: String?,
    val error: String?,
    val tokensIn: Int,
    val tokensOut: Int,
)

/** A paired external device allowed to call the local API (token hashes only, never raw tokens). */
@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val tokenHash: String,
    val tokenPrefix: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val revoked: Boolean,
)
