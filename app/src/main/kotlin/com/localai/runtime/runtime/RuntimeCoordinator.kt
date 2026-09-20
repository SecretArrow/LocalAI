package com.localai.runtime.runtime

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import com.localai.runtime.core.log.AppLogger
import com.localai.runtime.core.model.AppSettings
import com.localai.runtime.core.model.BackendType
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.ModelState
import com.localai.runtime.core.repo.ModelRepository
import com.localai.runtime.core.runtime.BackendRegistry
import com.localai.runtime.core.runtime.InferenceBackend
import com.localai.runtime.core.runtime.LoadedModel
import com.localai.runtime.core.settings.SettingsRepository
import com.localai.runtime.core.util.Formats
import com.localai.runtime.server.api.BenchmarkResult
import com.localai.runtime.server.api.ChatMessage
import com.localai.runtime.server.api.CompletionParams
import com.localai.runtime.server.api.EngineException
import com.localai.runtime.server.api.EngineStats
import com.localai.runtime.server.api.InferenceEngine
import com.localai.runtime.server.api.ServerModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The app-side inference engine: owns loaded models, enforces memory and concurrency
 * limits, supervises crashes and publishes live stats for the UI and the API server.
 *
 * Concurrency model (spec §20/§34):
 *  - a per-model [Mutex] serialises start/stop/restart/benchmark and generation per model
 *    (a single llama.cpp context cannot serve parallel generations);
 *  - a global slot gate sized by [AppSettings.apiMaxConcurrent] caps concurrent generations
 *    across all models; requests that cannot get a slot within 30s fail with [EngineException.Reason.BUSY];
 *  - crash supervision counts consecutive generation failures per model; at 3 the model is
 *    unloaded and marked FAILED, and later start attempts are gated by a 2s/8s/32s backoff.
 */
class RuntimeCoordinator(
    private val context: Context,
    private val registry: BackendRegistry,
    private val modelRepository: ModelRepository,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) : InferenceEngine {

    /** Models currently loaded, keyed by id. */
    private val running = ConcurrentHashMap<String, LoadedModel>()

    private val modelMutexes = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    // Request/token counters.
    private val totalRequests = AtomicLong()
    private val requestsPerModel = ConcurrentHashMap<String, AtomicLong>()
    private val generatedTokens = ConcurrentHashMap<String, AtomicLong>()
    private val activePerModel = ConcurrentHashMap<String, AtomicInteger>()
    private val waitingPerModel = ConcurrentHashMap<String, AtomicInteger>()

    /** Rolling ~10s window of token-emission timestamps (monotonic clock) per model. */
    private val tokenTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Wall-clock timestamps of consecutive generation failures per model (crash supervision). */
    private val failureTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    private val startupMs = ConcurrentHashMap<String, Long>()

    /** Incremental native heap attributed to each loaded model (captured at load time). */
    private val heapDeltas = ConcurrentHashMap<String, Long>()

    private val statsState = MutableStateFlow<List<EngineStats>>(emptyList())
    private val statsFlow = statsState.asStateFlow()

    // Global generate-slot gate (resizable via settings because it is a plain counter).
    private val slotLock = Any()
    private var activeGenerates = 0

    init {
        scope.launch {
            settings.flow.collect { latestSettings = it }
        }
        // Early background capability detection so the CPU backend is
        // selectable as soon as a model is started (available() returns empty
        // until detection has completed).
        scope.launch {
            runCatching { registry.detectAll() }
                .onFailure { logger.w("Coordinator", "backend detection failed: ${it.message}") }
        }
        scope.launch {
            while (isActive) {
                delay(STATS_TICK_MS)
                refreshStats()
            }
        }
    }

    override suspend fun listRunning(): List<String> = running.keys.toList()

    override suspend fun start(modelId: String): ServerModel {
        var model = modelRepository.get(modelId)
            ?: throw EngineException("Model not found: $modelId", EngineException.Reason.MODEL_NOT_FOUND)

        running[modelId]?.let { already ->
            logger.w(TAG, "Model ${model.name} is already running")
            return toServerModel(model.copy(state = ModelState.RUNNING), already.backend)
        }

        mutexFor(modelId).withLock {
            running[modelId]?.let { already ->
                return toServerModel(model.copy(state = ModelState.RUNNING), already.backend)
            }

            awaitCrashBackoff(modelId)

            // Memory pre-check (spec §10/§35) before touching any heavy runtime state.
            val effectiveContext = model.contextLength.takeIf { it > 0 } ?: latestSettings.defaultContext
            val estimate = (model.sizeBytes * MODEL_OVERHEAD_FACTOR).toLong() + effectiveContext * CONTEXT_BYTES_PER_TOKEN
            val available = availableMemoryBytes()
            if (estimate > available * MEMORY_USAGE_RATIO) {
                val message = "Not enough memory. Required ~${Formats.bytes(estimate)}, available ${Formats.bytes(available)}"
                logger.w(TAG, "Refusing to start ${model.name}: $message")
                throw EngineException(message, EngineException.Reason.INSUFFICIENT_MEMORY)
            }

            modelRepository.updateState(modelId, ModelState.STARTING)

            try {
                // Resolve content:// URIs into a real file once (spec §7: single pragmatic copy).
                val storedPath = model.localPath
                if (storedPath != null && storedPath.startsWith("content://")) {
                    val target = copyContentUriToLocal(storedPath, model)
                    model = model.copy(localPath = target.absolutePath, state = ModelState.STARTING)
                    modelRepository.upsert(model)
                }
                if (model.localPath == null) {
                    throw EngineException(
                        "Model files are missing. Download or import the model first.",
                        EngineException.Reason.LOAD_FAILED,
                    )
                }

                val config = modelRepository.configOrDefault(model, latestSettings)
                val backend: InferenceBackend? = config.backend
                    ?.let { wanted -> registry.available(model).firstOrNull { it.type == wanted } }
                    ?: registry.auto(model)

                val loadStart = SystemClock.elapsedRealtime()
                val heapBefore = Debug.getNativeHeapAllocatedSize()
                val loaded: LoadedModel = try {
                    val candidate = backend
                        ?: throw EngineException(
                            "No available backend can run ${model.name}",
                            EngineException.Reason.BACKEND_UNAVAILABLE,
                        )
                    candidate.load(model, config)
                        ?: throw EngineException(
                            "No available backend can run ${model.name}",
                            EngineException.Reason.BACKEND_UNAVAILABLE,
                        )
                } catch (e: EngineException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw EngineException(
                        "Failed to load ${model.name}: ${e.message ?: e.javaClass.simpleName}",
                        EngineException.Reason.LOAD_FAILED,
                        e.message ?: e.javaClass.simpleName,
                    )
                }
                startupMs[modelId] = SystemClock.elapsedRealtime() - loadStart
                heapDeltas[modelId] = (Debug.getNativeHeapAllocatedSize() - heapBefore).coerceAtLeast(0L)

                running[modelId] = loaded
                modelRepository.updateState(modelId, ModelState.RUNNING)
                logger.i(
                    TAG,
                    "Model ${model.name} running on ${loaded.backend.displayName} " +
                        "(loaded in ${startupMs[modelId]}ms, ~${Formats.bytes(heapDeltas[modelId] ?: 0L)} native)",
                )
                return toServerModel(model.copy(state = ModelState.RUNNING), loaded.backend)
            } catch (e: EngineException) {
                modelRepository.updateState(modelId, ModelState.FAILED, e.message)
                logger.e(TAG, "Failed to start ${model.name}: ${e.message}")
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val message = e.message ?: e.javaClass.simpleName
                modelRepository.updateState(modelId, ModelState.FAILED, message)
                logger.e(TAG, "Failed to start ${model.name}", e)
                throw EngineException("Failed to start ${model.name}: $message", EngineException.Reason.LOAD_FAILED, message)
            }
        }
    }

    override suspend fun stop(modelId: String): ServerModel {
        val model = modelRepository.get(modelId)
        if (model == null && !running.containsKey(modelId)) {
            throw EngineException("Model not found: $modelId", EngineException.Reason.MODEL_NOT_FOUND)
        }
        mutexFor(modelId).withLock {
            stopInternalLocked(modelId, ModelState.STOPPED, null)
        }
        clearFailures(modelId)
        val view = model?.copy(state = ModelState.STOPPED)
        return if (view != null) {
            toServerModel(view, null)
        } else {
            ServerModel(id = modelId, name = modelId, format = "UNKNOWN", state = ModelState.STOPPED.name)
        }
    }

    override suspend fun restart(modelId: String): ServerModel {
        if (running.containsKey(modelId)) {
            stop(modelId)
        }
        return start(modelId)
    }

    override fun generate(modelId: String, messages: List<ChatMessage>, params: CompletionParams): Flow<String> {
        running[modelId]
            ?: throw EngineException("Model is not running. Start it first.", EngineException.Reason.MODEL_NOT_RUNNING)
        return flow {
            val loaded = running[modelId]
                ?: throw EngineException("Model is not running. Start it first.", EngineException.Reason.MODEL_NOT_RUNNING)
            totalRequests.incrementAndGet()
            requestsPerModel.getOrPut(modelId) { AtomicLong() }.incrementAndGet()
            if (!acquireGenerateSlot(modelId)) {
                throw EngineException("Too many concurrent requests; try again later.", EngineException.Reason.BUSY)
            }
            activePerModel.getOrPut(modelId) { AtomicInteger() }.incrementAndGet()
            try {
                mutexFor(modelId).withLock {
                    // Re-check under the lock: the model may have been stopped or restarted meanwhile.
                    val current = running[modelId]
                    if (current == null || current !== loaded) {
                        throw EngineException("Model is not running. Start it first.", EngineException.Reason.MODEL_NOT_RUNNING)
                    }
                    emitAll(
                        current.generateStream(messages, params)
                            .onEach { recordToken(modelId) }
                            .catch { e ->
                                if (e is CancellationException) throw e
                                handleGenerateFailure(modelId, e)
                                throw e.toEngineException()
                            },
                    )
                    clearFailures(modelId)
                }
            } finally {
                activePerModel[modelId]?.decrementAndGet()
                releaseSlot()
            }
        }
    }

    override suspend fun benchmark(modelId: String): BenchmarkResult {
        val loaded = running[modelId]
            ?: throw EngineException("Model is not running. Start it first.", EngineException.Reason.MODEL_NOT_RUNNING)
        return mutexFor(modelId).withLock {
            try {
                val result = loaded.benchmark()
                val startup = startupMs[modelId]
                if (startup != null && startup > 0L && result.startupTimeMs <= 0L) result.copy(startupTimeMs = startup) else result
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                throw e
            } catch (e: Throwable) {
                throw EngineException(
                    "Benchmark failed: ${e.message ?: e.javaClass.simpleName}",
                    EngineException.Reason.GENERATION_FAILED,
                    e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    override fun stats(): Flow<List<EngineStats>> = statsFlow

    /** Unloads every running model. Called by the app container on shutdown. */
    fun shutdown() {
        scope.launch {
            val ids = running.keys.toList()
            if (ids.isNotEmpty()) logger.i(TAG, "Runtime shutdown: unloading ${ids.size} model(s)")
            for (id in ids) {
                try {
                    stop(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.e(TAG, "Failed to stop $id during shutdown", t)
                }
            }
        }
    }

    // ---------------------------------------------------------------- internals

    /** Caller must hold the per-model mutex. */
    private suspend fun stopInternalLocked(modelId: String, finalState: ModelState, error: String?) {
        val loaded = running.remove(modelId)
        if (loaded != null) {
            try {
                loaded.unload()
            } catch (t: Throwable) {
                logger.e(TAG, "Error while unloading model $modelId", t)
            }
        }
        activePerModel.remove(modelId)
        waitingPerModel.remove(modelId)
        tokenTimestamps.remove(modelId)
        startupMs.remove(modelId)
        heapDeltas.remove(modelId)
        modelRepository.updateState(modelId, finalState, error)
        logger.i(TAG, "Model $modelId is now ${finalState.name}${error?.let { " ($it)" } ?: ""}")
    }

    private fun mutexFor(modelId: String): Mutex = modelMutexes.getOrPut(modelId) { Mutex() }

    private fun currentMaxConcurrent(): Int = latestSettings.apiMaxConcurrent.coerceAtLeast(1)

    private fun tryTakeSlot(): Boolean = synchronized(slotLock) {
        if (activeGenerates < currentMaxConcurrent()) {
            activeGenerates++
            true
        } else {
            false
        }
    }

    private fun releaseSlot() {
        synchronized(slotLock) {
            if (activeGenerates > 0) activeGenerates--
        }
    }

    /**
     * Acquires a global generation slot, waiting at most [GENERATE_SLOT_TIMEOUT_MS].
     * Polling is used instead of a fixed semaphore so that settings changes to
     * apiMaxConcurrent take effect immediately and slots can never leak.
     */
    private suspend fun acquireGenerateSlot(modelId: String): Boolean {
        val waiters = waitingPerModel.getOrPut(modelId) { AtomicInteger() }
        waiters.incrementAndGet()
        var acquired = false
        try {
            withTimeoutOrNull(GENERATE_SLOT_TIMEOUT_MS) {
                while (!acquired) {
                    if (tryTakeSlot()) acquired = true else delay(GENERATE_SLOT_POLL_MS)
                }
            }
        } finally {
            waiters.decrementAndGet()
        }
        return acquired
    }

    private fun recordToken(modelId: String) {
        generatedTokens.getOrPut(modelId) { AtomicLong() }.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val window = tokenTimestamps.getOrPut(modelId) { ArrayDeque() }
        synchronized(window) {
            window.addLast(now)
            while (true) {
                val first = window.firstOrNull() ?: break
                if (now - first > ROLLING_WINDOW_MS) window.removeFirst() else break
            }
        }
    }

    private fun rollingTokensPerSecond(modelId: String): Float {
        val window = tokenTimestamps[modelId] ?: return 0f
        val now = SystemClock.elapsedRealtime()
        synchronized(window) {
            while (true) {
                val first = window.firstOrNull() ?: break
                if (now - first > ROLLING_WINDOW_MS) window.removeFirst() else break
            }
            if (window.isEmpty()) return 0f
            val spanMs = (now - window.first()).coerceAtLeast(1L)
            val effectiveMs = minOf(spanMs, ROLLING_WINDOW_MS)
            return window.size * 1000f / effectiveMs
        }
    }

    private fun refreshStats() {
        statsState.value = running.entries.map { (id, loaded) ->
            EngineStats(
                modelId = id,
                state = "running",
                backend = loaded.backend.id,
                tokensPerSecond = rollingTokensPerSecond(id),
                promptTokensPerSecond = 0f,
                memoryUsageBytes = heapDeltas[id] ?: 0L,
                activeRequests = activePerModel[id]?.get() ?: 0,
                queuedRequests = waitingPerModel[id]?.get() ?: 0,
                totalRequests = requestsPerModel[id]?.get() ?: 0L,
                generatedTokens = generatedTokens[id]?.get() ?: 0L,
            )
        }
    }

    private fun handleGenerateFailure(modelId: String, error: Throwable) {
        val failures = failureTimestamps.getOrPut(modelId) { ArrayList() }
        val count = synchronized(failures) {
            failures.add(System.currentTimeMillis())
            failures.size
        }
        logger.e(TAG, "Generation failed for model $modelId (consecutive failure $count)", error)
        if (count >= MAX_CONSECUTIVE_FAILURES) {
            scope.launch {
                try {
                    mutexFor(modelId).withLock {
                        if (!running.containsKey(modelId)) return@withLock
                        logger.w(TAG, "Crash supervision: unloading $modelId after $count consecutive failures")
                        stopInternalLocked(
                            modelId,
                            ModelState.FAILED,
                            "Repeated generation failures: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.e(TAG, "Crash supervision could not unload $modelId", t)
                }
            }
        }
    }

    private fun clearFailures(modelId: String) {
        failureTimestamps.remove(modelId)
    }

    /** Enforces the 2s/8s/32s backoff before a manual retry after generation failures (spec §34). */
    private suspend fun awaitCrashBackoff(modelId: String) {
        val failures = failureTimestamps[modelId] ?: return
        val (count, lastFailure) = synchronized(failures) {
            if (failures.isEmpty()) 0 to 0L else failures.size to failures.last()
        }
        if (count == 0) return
        val backoffMs = CRASH_BACKOFF_MS[(count - 1).coerceAtMost(CRASH_BACKOFF_MS.lastIndex)]
        val elapsed = System.currentTimeMillis() - lastFailure
        val remaining = backoffMs - elapsed
        if (remaining > 0) {
            logger.w(TAG, "Crash backoff: waiting ${remaining}ms before starting $modelId ($count recent failure(s))")
            delay(remaining)
        }
    }

    private fun availableMemoryBytes(): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Long.MAX_VALUE
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.availMem > 0) info.availMem else Long.MAX_VALUE
    } catch (t: Throwable) {
        Long.MAX_VALUE
    }

    /** Copies a SAF content URI into the private models dir once; returns the local file. */
    private suspend fun copyContentUriToLocal(uriString: String, model: ModelInfo): File =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(uriString)
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val rawName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "${model.id}.${model.format.extension.ifBlank { "bin" }}"
            val fileName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "${model.id}.bin" }
            val target = File(dir, fileName)
            if (target.exists() && target.length() > 0L) {
                return@withContext target
            }
            val partial = File(dir, "$fileName.part")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw EngineException("Could not open the imported model file.", EngineException.Reason.LOAD_FAILED)
            try {
                input.use { src ->
                    partial.outputStream().use { out -> src.copyTo(out) }
                }
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
            } catch (e: CancellationException) {
                partial.delete()
                throw e
            } catch (e: EngineException) {
                partial.delete()
                throw e
            } catch (e: Throwable) {
                partial.delete()
                throw EngineException(
                    "Failed to copy the model into app storage: ${e.message ?: e.javaClass.simpleName}",
                    EngineException.Reason.LOAD_FAILED,
                    e.message ?: e.javaClass.simpleName,
                )
            }
            target
        }

    private fun toServerModel(model: ModelInfo, backend: BackendType?): ServerModel = ServerModel(
        id = model.id,
        name = model.name,
        version = model.version,
        format = model.format.name,
        quantization = model.quantization,
        sizeBytes = model.sizeBytes,
        state = model.state.name,
        backend = backend?.id,
        contextLength = model.contextLength,
        minRamMb = model.minRamMb,
        backends = model.backends.map { it.id },
    )

    private fun Throwable.toEngineException(): EngineException =
        if (this is EngineException) {
            this
        } else {
            EngineException(
                "Generation failed: ${message ?: javaClass.simpleName}",
                EngineException.Reason.GENERATION_FAILED,
                javaClass.simpleName,
            )
        }

    private companion object {
        const val TAG = "RuntimeCoordinator"
        const val MODEL_OVERHEAD_FACTOR = 1.15
        const val CONTEXT_BYTES_PER_TOKEN = 2048L
        const val MEMORY_USAGE_RATIO = 0.85
        const val ROLLING_WINDOW_MS = 10_000L
        const val STATS_TICK_MS = 1_000L
        const val GENERATE_SLOT_TIMEOUT_MS = 30_000L
        const val GENERATE_SLOT_POLL_MS = 25L
        const val MAX_CONSECUTIVE_FAILURES = 3
        val CRASH_BACKOFF_MS = longArrayOf(2_000L, 8_000L, 32_000L)
    }
}
