package com.localai.runtime.core.repo

import com.localai.runtime.core.db.LocalAiDatabase
import com.localai.runtime.core.db.joinCsv
import com.localai.runtime.core.db.parseBackendOrNull
import com.localai.runtime.core.db.toModelEntity
import com.localai.runtime.core.db.toModelInfo
import com.localai.runtime.core.db.toRuntimeConfig
import com.localai.runtime.core.db.toRuntimeConfigEntity
import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.BackendType
import com.localai.runtime.core.model.CatalogEntry
import com.localai.runtime.core.model.LocalAiException
import com.localai.runtime.core.model.ModelFormat
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.ModelState
import com.localai.runtime.core.model.RuntimeConfig
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistence facade over the model + runtime config tables. List APIs expose [Flow];
 * actions are one-shot suspends. User-facing failures throw [LocalAiException] subtypes.
 */
class ModelRepository(
    private val db: LocalAiDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** All models, sorted: RUNNING first, then favorites, then name (case-insensitive). */
    val models: Flow<List<ModelInfo>> = db.modelDao().observeAll()
        .map { entities ->
            entities.map { it.toModelInfo() }
                .sortedWith(
                    compareBy(
                        { it.state != ModelState.RUNNING },
                        { !it.favorite },
                        { it.name.lowercase(Locale.ROOT) },
                    ),
                )
        }

    fun observe(id: String): Flow<ModelInfo?> = db.modelDao().observe(id).map { it?.toModelInfo() }

    suspend fun get(id: String): ModelInfo? = db.modelDao().get(id)?.toModelInfo()

    /** Inserts or replaces the model row (backends/collections are csv-joined into the entity). */
    suspend fun upsert(model: ModelInfo) {
        db.modelDao().upsert(model.toModelEntity())
    }

    /** Overwrites the state and the last error; a null error clears it. */
    suspend fun updateState(id: String, state: ModelState, error: String? = null) {
        val entity = db.modelDao().get(id)
            ?: throw LocalAiException.NotFound("Model not found: $id")
        db.modelDao().upsert(entity.copy(state = state.name, lastError = error, updatedAt = clock()))
    }

    suspend fun updateConfig(config: RuntimeConfig) {
        db.runtimeConfigDao().upsert(config.toRuntimeConfigEntity())
    }

    fun observeConfig(id: String): Flow<RuntimeConfig?> =
        db.runtimeConfigDao().observe(id).map { it?.toRuntimeConfig() }

    /** Stored config, or defaults derived from the model and stock [AppSettings]. */
    suspend fun config(id: String): RuntimeConfig {
        val model = get(id) ?: throw LocalAiException.NotFound("Model not found: $id")
        return db.runtimeConfigDao().get(id)?.toRuntimeConfig() ?: configOrDefault(model, AppSettings())
    }

    /**
     * Builds a fresh config from app defaults + model constraints without touching storage:
     * backend/threads/gpuLayers from settings (null backend = Auto), context from the model
     * when it declares one, otherwise the settings default. Remaining values keep the
     * [RuntimeConfig] data class defaults.
     */
    suspend fun configOrDefault(model: ModelInfo, defaults: AppSettings): RuntimeConfig = RuntimeConfig(
        modelId = model.id,
        backend = parseBackendOrNull(defaults.defaultBackendId),
        cpuThreads = defaults.defaultThreads,
        gpuLayers = defaults.defaultGpuLayers,
        contextLength = if (model.contextLength > 0) model.contextLength else defaults.defaultContext,
    )

    suspend fun delete(id: String) {
        db.modelDao().delete(id)
        db.runtimeConfigDao().delete(id)
    }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        val entity = db.modelDao().get(id)
            ?: throw LocalAiException.NotFound("Model not found: $id")
        db.modelDao().upsert(entity.copy(favorite = favorite, updatedAt = clock()))
    }

    suspend fun setCollections(id: String, collections: List<String>) {
        val entity = db.modelDao().get(id)
            ?: throw LocalAiException.NotFound("Model not found: $id")
        db.modelDao().upsert(entity.copy(collectionsCsv = joinCsv(collections), updatedAt = clock()))
    }

    /** Catalog entry as a NOT_INSTALLED model shell (no local path, nothing persisted). */
    suspend fun fromCatalog(entry: CatalogEntry): ModelInfo =
        entry.toModelInfo().copy(state = ModelState.NOT_INSTALLED, localPath = null)

    /**
     * Registers a user-imported model file. [format] wins when known; otherwise it is detected
     * from the file name (then the path). Generates id "import-<millis>-<rand4>", marks the
     * model INSTALLED + imported and persists it.
     */
    suspend fun registerImported(
        name: String,
        path: String,
        sizeBytes: Long,
        sha256: String?,
        format: ModelFormat,
    ): ModelInfo {
        val now = clock()
        val suffix = Random.nextInt(10000).toString().padStart(4, '0')
        val resolvedFormat = when {
            format != ModelFormat.UNKNOWN -> format
            ModelFormat.fromFileName(name) != ModelFormat.UNKNOWN -> ModelFormat.fromFileName(name)
            else -> ModelFormat.fromFileName(path)
        }
        val model = ModelInfo(
            id = "import-$now-$suffix",
            name = name,
            format = resolvedFormat,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            backends = listOf(BackendType.CPU),
            state = ModelState.INSTALLED,
            localPath = path,
            installedAt = now,
            updatedAt = now,
            imported = true,
        )
        upsert(model)
        return model
    }

    suspend fun listAll(): List<ModelInfo> = db.modelDao().all().map { it.toModelInfo() }
}
