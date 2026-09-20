package com.localai.runtime.ui.screens.storage

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.runtime.core.model.StorageUsage
import com.localai.runtime.core.util.Formats
import com.localai.runtime.runtime.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class StorageUiState(
    val usage: StorageUsage? = null,
    val busy: Boolean = false,
    val exportFile: File? = null,
    val message: String? = null,
)

/** Storage dashboard: directory sizes, free space, cache/temp cleanup, settings portability. */
class StorageViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val usage = withContext(Dispatchers.IO) { computeUsage() }
                _state.value = _state.value.copy(usage = usage)
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Could not scan storage: ${e.message ?: "unknown error"}")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val freed = withContext(Dispatchers.IO) { deleteContents(container.cacheDir) }
                _state.value = _state.value.copy(message = "Cache cleared (${Formats.bytes(freed)} freed)")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Could not clear cache: ${e.message ?: "unknown error"}")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun deleteTempFiles() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val freed = withContext(Dispatchers.IO) {
                    var bytes = 0L
                    listOf(container.downloadsDir, container.modelsDir, container.cacheDir).forEach { dir ->
                        bytes += deletePartFiles(dir)
                    }
                    bytes
                }
                _state.value = _state.value.copy(message = "Temporary files deleted (${Formats.bytes(freed)} freed)")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Could not delete temp files: ${e.message ?: "unknown error"}")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun exportSettings() {
        viewModelScope.launch {
            try {
                val json = container.configPorter.exportSettingsJson()
                val file = withContext(Dispatchers.IO) {
                    val dir = File(filesDir(), "exports").apply { mkdirs() }
                    File(dir, "localai-settings-${System.currentTimeMillis()}.json")
                        .apply { writeText(json) }
                }
                _state.value = _state.value.copy(exportFile = file)
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Export failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun importSettings(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Could not read the selected file")
                }
                container.configPorter.importSettingsJson(json)
                    .onSuccess { count ->
                        _state.value = _state.value.copy(message = "Imported $count settings")
                    }
                    .onFailure { e ->
                        _state.value = _state.value.copy(message = "Import failed: ${e.message ?: "invalid file"}")
                    }
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Import failed: ${e.message ?: "could not read file"}")
            }
        }
    }

    fun onExportHandled() {
        _state.value = _state.value.copy(exportFile = null)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    private fun computeUsage(): StorageUsage {
        val models = dirSize(container.modelsDir)
        val downloads = dirSize(container.downloadsDir)
        val cache = dirSize(container.cacheDir)
        val logs = dirSize(File(filesDir(), "logs"))
        val temp = partSize(container.downloadsDir) + partSize(container.modelsDir) + partSize(container.cacheDir)
        val stats = StatFs(container.cacheDir.absolutePath)
        return StorageUsage(
            modelsBytes = models,
            downloadsBytes = downloads,
            cacheBytes = cache,
            logsBytes = logs,
            tempBytes = temp,
            freeBytes = stats.availableBytes,
            totalBytes = stats.totalBytes,
        )
    }

    /** The app files dir derived from the exposed cache dir (same parent directory). */
    private fun filesDir(): File = File(container.cacheDir.parentFile, "files")

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.walkBottomUp().forEach { file ->
            if (file.isFile) size += file.length()
        }
        return size
    }

    private fun partSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkBottomUp()
            .filter { it.isFile && it.name.endsWith(".part") }
            .sumOf { it.length() }
    }

    private fun deletePartFiles(dir: File): Long {
        if (!dir.exists()) return 0L
        var freed = 0L
        dir.walkBottomUp()
            .filter { it.isFile && it.name.endsWith(".part") }
            .forEach { file ->
                freed += file.length()
                file.delete()
            }
        return freed
    }

    private fun deleteContents(dir: File): Long {
        if (!dir.exists()) return 0L
        var freed = 0L
        // Secrets live in noBackupFilesDir/secrets — never inside the cache dir.
        dir.listFiles()?.forEach { child ->
            freed += if (child.isDirectory) dirSize(child) else child.length()
            child.deleteRecursively()
        }
        return freed
    }
}
