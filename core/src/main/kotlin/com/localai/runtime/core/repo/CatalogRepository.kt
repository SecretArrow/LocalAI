package com.localai.runtime.core.repo

import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.CatalogDiff
import com.localai.runtime.core.model.CatalogEntry
import com.localai.runtime.core.model.CatalogManifest
import com.localai.runtime.core.model.LocalAiException
import com.localai.runtime.core.model.ModelInfo
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Fetches the remote model catalog manifest, caches it on disk and diffs it against the
 * locally installed (non-imported) models. Never auto-downloads or auto-replaces anything.
 * Failures come back as [Result.failure] with a [LocalAiException.Network] cause; coroutine
 * cancellation still propagates.
 */
class CatalogRepository(
    private val client: OkHttpClient,
    private val cacheFile: File,
    private val modelRepository: ModelRepository,
) {

    /** Catalog entries from the on-disk cache, re-read at collect time; empty when no cache. */
    val entries: Flow<List<CatalogEntry>> = flow {
        val manifest = withContext(Dispatchers.IO) { readCachedManifest() }
        emit(manifest?.models ?: emptyList())
    }

    /** Version tag of the cached manifest (updatedAt, falling back to schema version), or null without cache. */
    suspend fun cachedVersion(): String? = withContext(Dispatchers.IO) {
        readCachedManifest()?.let { manifest -> manifestVersion(manifest) }
    }

    /**
     * Fetches the manifest over HTTP (30s timeouts), skips invalid entries (blank id or
     * downloadUrl, duplicates), writes the cache atomically (tmp + rename) and diffs against
     * the installed non-imported models. Returns [Result.failure] on any failure.
     */
    suspend fun refresh(url: String = AppSettings.DEFAULT_CATALOG_URL): Result<CatalogDiff> {
        return try {
            withContext(Dispatchers.IO) {
                val fetchClient = client.newBuilder()
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(url).build()
                val body = fetchClient.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        throw LocalAiException.Network(
                            "Catalog request failed (HTTP ${response.code})",
                            url,
                        )
                    }
                    response.body?.string()
                }
                if (body.isNullOrBlank()) {
                    throw LocalAiException.Network("Catalog response body is empty", url)
                }
                val parsed = parse(body)
                val valid = sanitize(parsed.models)
                if (valid.isEmpty()) {
                    throw LocalAiException.Network("Catalog manifest contains no valid model entries", url)
                }
                val manifest = CatalogManifest(
                    version = parsed.version,
                    updatedAt = parsed.updatedAt,
                    models = valid,
                )
                writeCacheAtomically(CACHE_JSON.encodeToString(CatalogManifest.serializer(), manifest))
                val installed = modelRepository.listAll().filter { !it.imported }
                Result.success(diffEntries(manifest, installed))
            }
        } catch (e: LocalAiException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                LocalAiException.Network(
                    "Failed to refresh catalog: ${e.message ?: e.javaClass.simpleName}",
                    e.toString(),
                ),
            )
        }
    }

    private fun readCachedManifest(): CatalogManifest? = try {
        if (cacheFile.isFile) parse(cacheFile.readText()) else null
    } catch (e: Exception) {
        LOGGER.warning("Unreadable catalog cache (${e.message}); treating as empty")
        null
    }

    private fun writeCacheAtomically(content: String) {
        val parent = cacheFile.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val tmp = File(parent, cacheFile.name + ".tmp")
        tmp.writeText(content)
        try {
            Files.move(
                tmp.toPath(),
                cacheFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private val LOGGER = Logger.getLogger(CatalogRepository::class.java.name)
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val CACHE_JSON = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

        /**
         * Tolerant manifest parsing: unknown keys ignored, entries that fail to decode
         * (missing id/downloadUrl) skipped, missing top-level fields defaulted.
         */
        fun parse(json: String): CatalogManifest {
            val root = JSON.parseToJsonElement(json).jsonObject
            val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
            val updatedAt = root["updatedAt"]?.jsonPrimitive?.contentOrNull ?: ""
            val modelsElement = root["models"]
            val models = (if (modelsElement is JsonArray) modelsElement else emptyList()).mapNotNull { element ->
                runCatching { JSON.decodeFromJsonElement(CatalogEntry.serializer(), element) }.getOrNull()
            }
            return CatalogManifest(version = version, updatedAt = updatedAt, models = models)
        }

        /** Keeps entries with non-blank id + downloadUrl, dropping duplicates by id. */
        internal fun sanitize(entries: List<CatalogEntry>): List<CatalogEntry> {
            val result = ArrayList<CatalogEntry>(entries.size)
            val seen = HashSet<String>(entries.size)
            for (entry in entries) {
                when {
                    entry.id.isBlank() ->
                        LOGGER.warning("Skipping catalog entry with blank id: ${entry.name}")
                    entry.downloadUrl.isBlank() ->
                        LOGGER.warning("Skipping catalog entry without downloadUrl: ${entry.id}")
                    !seen.add(entry.id) ->
                        LOGGER.warning("Skipping duplicate catalog entry id: ${entry.id}")
                    else -> result.add(entry)
                }
            }
            return result
        }

        /**
         * Pure diff: new = no installed model with that id, updated = installed version differs,
         * removed = installed id missing from the manifest. [installed] must already exclude
         * imported models.
         */
        internal fun diffEntries(catalog: CatalogManifest, installed: List<ModelInfo>): CatalogDiff {
            val installedById = HashMap<String, ModelInfo>(installed.size)
            for (model in installed) {
                installedById[model.id] = model
            }
            val entryIds = HashSet<String>(catalog.models.size)
            val newModels = ArrayList<CatalogEntry>()
            val updatedModels = ArrayList<CatalogEntry>()
            for (entry in catalog.models) {
                if (!entryIds.add(entry.id)) continue
                val existing = installedById[entry.id]
                when {
                    existing == null -> newModels.add(entry)
                    existing.version != entry.version -> updatedModels.add(entry)
                }
            }
            val removedIds = installed.filter { it.id !in entryIds }.map { it.id }
            return CatalogDiff(
                newModels = newModels,
                updatedModels = updatedModels,
                removedIds = removedIds,
                catalogVersion = manifestVersion(catalog),
            )
        }

        /** Human-facing manifest version: updatedAt when present, schema version otherwise. */
        internal fun manifestVersion(manifest: CatalogManifest): String =
            manifest.updatedAt.ifBlank { manifest.version.toString() }
    }
}

/** Awaits an OkHttp call honouring coroutine cancellation. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        },
    )
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
