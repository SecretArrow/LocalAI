package com.localai.runtime.ui.screens.models

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.CatalogDiff
import com.localai.runtime.core.model.CatalogEntry
import com.localai.runtime.core.model.DownloadProgress
import com.localai.runtime.core.model.DownloadState
import com.localai.runtime.core.model.ModelFormat
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.ModelState
import com.localai.runtime.core.util.Hashing
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.service.RuntimeService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

enum class ModelFilter(val label: String) {
    ALL("All"),
    INSTALLED("Installed"),
    RUNNING("Running"),
    COMPATIBLE("Compatible"),
}

enum class ModelSort(val label: String) {
    NAME("Name"),
    SIZE("Size"),
    RECENT("Recent"),
}

/** Result of the HEAD/Range probe for a user-supplied download URL (spec §55). */
data class CustomUrlProbe(
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val rangesSupported: Boolean,
)

/** Progress of an in-flight local import (copy + checksum). */
data class ImportProgress(
    val fileName: String,
    val processedBytes: Long,
    val totalBytes: Long,
)

data class ModelsUiState(
    val models: List<ModelInfo> = emptyList(),
    val catalogEntries: List<CatalogEntry> = emptyList(),
    val catalogDiff: CatalogDiff? = null,
    val catalogVersion: String? = null,
    val searchQuery: String = "",
    val filter: ModelFilter = ModelFilter.ALL,
    val sort: ModelSort = ModelSort.NAME,
    /** Live download progress by model id (for inline progress on model cards). */
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val refreshingCatalog: Boolean = false,
    val backendDetectionDone: Boolean = false,
    val probingUrl: Boolean = false,
    val urlProbe: CustomUrlProbe? = null,
    val importing: ImportProgress? = null,
    val error: String? = null,
    val message: String? = null,
)

class ModelsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    private var settings: AppSettings = AppSettings()
    private val handledTerminalDownloads = mutableSetOf<Long>()
    private var importJob: Job? = null

    init {
        viewModelScope.launch {
            container.settingsRepository.flow.collect { settings = it }
        }
        viewModelScope.launch {
            combine(
                container.modelRepository.models.onStart { emit(emptyList()) },
                container.catalogRepository.entries.onStart { emit(emptyList()) },
            ) { models, entries -> models to entries }
                .collect { (models, entries) ->
                    _state.update { it.copy(models = models, catalogEntries = entries) }
                }
        }
        viewModelScope.launch {
            val version = container.catalogRepository.cachedVersion()
            if (version != null) _state.update { it.copy(catalogVersion = version) }
        }
        viewModelScope.launch {
            container.downloadManager.progress.collect { list ->
                _state.update {
                    it.copy(downloads = list.filter { p -> p.modelId != null }.associate { p -> p.modelId!! to p })
                }
                list.filter { it.state in TERMINAL_STATES }.forEach { syncTerminalDownload(it) }
            }
        }
        // Warm the backend registry cache so the "Compatible" filter works immediately.
        viewModelScope.launch {
            runCatching { container.backendRegistry.detectAll() }
            _state.update { it.copy(backendDetectionDone = true) }
        }
    }

    // ---------------------------------------------------------------- actions

    fun setSearchQuery(query: String) = _state.update { it.copy(searchQuery = query) }

    fun setFilter(filter: ModelFilter) = _state.update { it.copy(filter = filter) }

    fun setSort(sort: ModelSort) = _state.update { it.copy(sort = sort) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun refreshCatalog() {
        if (_state.value.refreshingCatalog) return
        _state.update { it.copy(refreshingCatalog = true, error = null) }
        viewModelScope.launch {
            val url = container.settingsRepository.flow.first().catalogUrl
            val result = container.catalogRepository.refresh(url)
            _state.update { current ->
                val diff = result.getOrNull()
                current.copy(
                    refreshingCatalog = false,
                    catalogDiff = diff ?: current.catalogDiff,
                    catalogVersion = diff?.catalogVersion?.takeIf { it.isNotBlank() } ?: current.catalogVersion,
                    error = result.exceptionOrNull()?.let { "Catalog refresh failed: ${it.message ?: it.javaClass.simpleName}" },
                )
            }
        }
    }

    /** Registers the catalog entry as a known model and enqueues its download. */
    fun downloadEntry(entry: CatalogEntry) {
        viewModelScope.launch {
            try {
                val fileName = fileNameFromUrl(entry.downloadUrl) ?: "${entry.id}.gguf"
                val dest = File(container.downloadsDir, fileName)
                val info = container.modelRepository.fromCatalog(entry)
                container.modelRepository.upsert(info)
                container.modelRepository.updateState(info.id, ModelState.DOWNLOADING)
                container.downloadManager.enqueue(
                    url = entry.downloadUrl,
                    destFile = dest,
                    fileName = fileName,
                    modelId = entry.id,
                    expectedSha256 = entry.sha256,
                    expectedSize = entry.size,
                    wifiOnly = settings.wifiOnly,
                )
                _state.update { it.copy(message = "Downloading ${entry.name}") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not start download: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun runModel(id: String) {
        try {
            RuntimeService.startModel(container.context, id)
            _state.update { it.copy(message = "Starting model…") }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Could not start model: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun stopModel(id: String) {
        container.applicationScope.launch {
            runCatching { container.runtimeCoordinator.stop(id) }
                .onFailure { e ->
                    container.appLogger.e("ModelsVM", "Failed to stop $id", e)
                    _state.update { it.copy(error = "Could not stop model: ${e.message}") }
                }
        }
    }

    /** Deletes a model (call sites must confirm with the user first — never silent). */
    fun deleteModel(id: String) {
        viewModelScope.launch {
            try {
                _state.value.downloads[id]?.let { active ->
                    runCatching { container.downloadManager.cancel(active.id) }
                }
                val model = container.modelRepository.get(id)
                // Remove the local file when it lives in our private storage.
                // Content URIs (SAF imports handled elsewhere) are never touched here.
                model?.localPath?.takeIf { it.startsWith("/") }?.let { path ->
                    withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
                }
                container.modelRepository.delete(id)
                _state.update { it.copy(message = "Deleted ${model?.name ?: id}") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not delete model: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun pauseDownload(id: Long) {
        viewModelScope.launch { runCatching { container.downloadManager.pause(id) } }
    }

    fun resumeDownload(id: Long) {
        viewModelScope.launch { runCatching { container.downloadManager.resume(id) } }
    }

    fun cancelDownload(id: Long) {
        viewModelScope.launch { runCatching { container.downloadManager.cancel(id) } }
    }

    fun retryDownload(id: Long) {
        viewModelScope.launch { runCatching { container.downloadManager.retry(id) } }
    }

    // ------------------------------------------------------- custom URL (§55)

    fun probeCustomUrl(rawUrl: String) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update { it.copy(error = "Enter a valid http(s) URL") }
            return
        }
        _state.update { it.copy(probingUrl = true, urlProbe = null, error = null) }
        viewModelScope.launch {
            val probe = withContext(Dispatchers.IO) {
                runCatching { probeUrl(url) }.getOrNull()
            }
            _state.update { current ->
                if (probe == null) {
                    current.copy(probingUrl = false, error = "Could not reach $url")
                } else {
                    current.copy(probingUrl = false, urlProbe = probe)
                }
            }
        }
    }

    fun confirmCustomUrlDownload() {
        val probe = _state.value.urlProbe ?: return
        _state.update { it.copy(urlProbe = null) }
        viewModelScope.launch {
            try {
                val dest = File(container.downloadsDir, probe.fileName)
                container.downloadManager.enqueue(
                    url = probe.url,
                    destFile = dest,
                    fileName = probe.fileName,
                    modelId = null,
                    expectedSha256 = null,
                    expectedSize = probe.sizeBytes,
                    wifiOnly = settings.wifiOnly,
                )
                _state.update { it.copy(message = "Downloading ${probe.fileName}") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not start download: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun dismissUrlProbe() = _state.update { it.copy(urlProbe = null, probingUrl = false) }

    // ------------------------------------------------------ import local file

    /**
     * Imports a model picked via SAF. The file is copied once into the private models
     * directory (the pragmatic single-copy approach sanctioned by the contracts) while
     * SHA-256 is computed; a 60s budget guards the whole operation — on timeout the
     * model is still registered, just without a checksum.
     */
    fun importModel(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            val resolver = container.context.contentResolver
            val meta = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                                cursor.getString(nameIdx) ?: "imported-model"
                            } else {
                                "imported-model"
                            }
                            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                            name to size
                        } else {
                            "imported-model" to 0L
                        }
                    } ?: ("imported-model" to 0L)
                }.getOrDefault("imported-model" to 0L)
            }
            val displayName = meta.first
            val declaredSize = meta.second
            val fileName = displayName.substringAfterLast('/').ifBlank { "imported-model" }
            _state.update { it.copy(importing = ImportProgress(fileName, 0L, declaredSize)) }

            var dest: File? = null
            try {
                val target = withContext(Dispatchers.IO) {
                    container.modelsDir.mkdirs()
                    uniqueDest(container.modelsDir, fileName)
                }
                dest = target
                var copyCompleted = false
                val sha256 = withTimeoutOrNull(60_000) {
                    withContext(Dispatchers.IO) {
                        val input = resolver.openInputStream(uri)
                            ?: throw IOException("Cannot open the selected file")
                        input.use { ins ->
                            FileOutputStream(target).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                var copied = 0L
                                while (isActive) {
                                    val read = ins.read(buffer)
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                    copied += read
                                    if (copied shr 22 != (copied - read) shr 22) {
                                        _state.update { s ->
                                            s.copy(importing = ImportProgress(fileName, copied, declaredSize))
                                        }
                                    }
                                }
                                out.flush()
                            }
                        }
                        if (!isActive) throw CancellationException("Import cancelled")
                        copyCompleted = true
                        val total = target.length()
                        Hashing.sha256(target) { bytes ->
                            _state.update { s ->
                                s.copy(importing = ImportProgress(fileName, bytes, total))
                            }
                        }
                    }
                }
                if (!copyCompleted) {
                    // Timed out mid-copy — never register a partial model file.
                    throw IOException("Import timed out before the file was fully copied")
                }
                val model = container.modelRepository.registerImported(
                    name = fileName.substringBeforeLast('.').ifBlank { fileName },
                    path = target.absolutePath,
                    sizeBytes = target.length(),
                    sha256 = sha256,
                    format = ModelFormat.fromFileName(fileName),
                )
                _state.update { it.copy(importing = null, message = "Imported ${model.name}") }
            } catch (e: CancellationException) {
                dest?.let { partial ->
                    runCatching {
                        withContext(NonCancellable + Dispatchers.IO) { partial.delete() }
                    }
                }
                _state.update { it.copy(importing = null) }
                throw e
            } catch (e: Exception) {
                dest?.let { partial ->
                    runCatching {
                        withContext(Dispatchers.IO) { partial.delete() }
                    }
                }
                _state.update {
                    it.copy(
                        importing = null,
                        error = "Import failed: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    // ------------------------------------------------------------- internals

    /**
     * Keeps the model table in sync with terminal download states.
     *
     * The frozen DownloadManager contract has no ModelRepository access, so this
     * view model performs the bookkeeping: mark models INSTALLED with their local
     * path when a download completes, FAILED/CANCELLED otherwise. Custom-URL
     * downloads (no model id) are registered as imported models on completion.
     */
    private fun syncTerminalDownload(progress: DownloadProgress) {
        if (!handledTerminalDownloads.add(progress.id)) return
        viewModelScope.launch {
            try {
                val repo = container.modelRepository
                val existing = progress.modelId?.let { repo.get(it) }
                when (progress.state) {
                    DownloadState.COMPLETED -> {
                        val localPath = File(container.downloadsDir, progress.fileName).absolutePath
                        when {
                            existing != null && !existing.installed -> repo.upsert(
                                existing.copy(
                                    state = ModelState.INSTALLED,
                                    localPath = localPath,
                                    installedAt = System.currentTimeMillis(),
                                    sizeBytes = if (existing.sizeBytes > 0) existing.sizeBytes else progress.totalBytes,
                                ),
                            )
                            existing == null && progress.modelId != null -> {
                                val entry = _state.value.catalogEntries.firstOrNull { it.id == progress.modelId }
                                if (entry != null) {
                                    val info = repo.fromCatalog(entry)
                                    repo.upsert(
                                        info.copy(
                                            state = ModelState.INSTALLED,
                                            localPath = localPath,
                                            installedAt = System.currentTimeMillis(),
                                            sizeBytes = if (entry.size > 0) entry.size else progress.totalBytes,
                                        ),
                                    )
                                }
                            }
                            else -> Unit // already installed (was an update download)
                        }
                        if (progress.modelId == null) registerCustomUrlDownload(progress)
                    }
                    DownloadState.FAILED -> {
                        if (existing != null && existing.state == ModelState.DOWNLOADING) {
                            repo.updateState(progress.modelId!!, ModelState.FAILED, progress.error)
                        }
                    }
                    DownloadState.CANCELLED -> {
                        if (existing != null && existing.state == ModelState.DOWNLOADING) {
                            repo.updateState(progress.modelId!!, ModelState.NOT_INSTALLED)
                        }
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                container.appLogger.w("ModelsVM", "Download bookkeeping failed: ${e.message}")
            }
        }
    }

    private suspend fun registerCustomUrlDownload(progress: DownloadProgress) {
        val dest = File(container.downloadsDir, progress.fileName)
        if (!dest.exists() || !dest.isFile) return
        val repo = container.modelRepository
        if (repo.listAll().any { it.localPath == dest.absolutePath }) return
        val model = repo.registerImported(
            name = dest.name.substringBeforeLast('.').ifBlank { dest.name },
            path = dest.absolutePath,
            sizeBytes = dest.length(),
            sha256 = null,
            format = ModelFormat.fromFileName(dest.name),
        )
        _state.update { it.copy(message = "Downloaded ${model.name} added to your models") }
    }

    /** HEAD probe with a ranged-GET fallback: content length + Accept-Ranges (spec §55). */
    private fun probeUrl(url: String): CustomUrlProbe {
        val client = container.okHttpClient.newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val fileName = Uri.decode(Uri.parse(url).lastPathSegment).orEmpty().ifBlank { "download.bin" }

        val head = runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
                if (response.isSuccessful) {
                    Triple(
                        response.header("Content-Length")?.toLongOrNull() ?: -1L,
                        response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true,
                        response.code,
                    )
                } else {
                    null
                }
            }
        }.getOrNull()
        if (head != null) return CustomUrlProbe(url, fileName, head.first, head.second)

        // Some servers reject HEAD — retry with a 1-byte ranged GET.
        val ranged = runCatching {
            client.newCall(
                Request.Builder().url(url).header("Range", "bytes=0-0").get().build(),
            ).execute().use { response ->
                if (response.isSuccessful) {
                    val size = if (response.code == 206) {
                        response.header("Content-Range")
                            ?.substringAfterLast('/')
                            ?.toLongOrNull() ?: -1L
                    } else {
                        response.header("Content-Length")?.toLongOrNull() ?: -1L
                    }
                    val ranges = response.code == 206 ||
                        response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                    Triple(size, ranges, response.code)
                } else {
                    null
                }
            }
        }.getOrNull() ?: throw IOException("Server did not respond with a usable result")

        return CustomUrlProbe(url, fileName, ranged.first, ranged.second)
    }

    private fun uniqueDest(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        var n = 1
        while (candidate.exists() && n < 1000) {
            candidate = if (ext.isBlank()) File(dir, "$base (${++n})") else File(dir, "$base (${++n}).$ext")
        }
        return candidate
    }

    private fun fileNameFromUrl(url: String): String? =
        Uri.decode(Uri.parse(url).lastPathSegment)
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }

    private companion object {
        val TERMINAL_STATES = setOf(
            DownloadState.COMPLETED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        )
    }
}
