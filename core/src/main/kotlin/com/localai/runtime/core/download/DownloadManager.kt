package com.localai.runtime.core.download

import com.localai.runtime.core.db.DownloadEntity
import com.localai.runtime.core.model.DownloadProgress
import com.localai.runtime.core.model.DownloadState
import com.localai.runtime.core.util.Hashing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Framework-free network status seam. The app side implements this with
 * `com.localai.runtime.core.net.NetworkMonitor` (ConnectivityManager backed).
 */
interface NetworkGate {
    fun isOnline(): Boolean
    fun isWifi(): Boolean
    fun isMetered(): Boolean
}

/**
 * Persistence seam for download rows, owned by this package. Room's
 * `com.localai.runtime.core.db.DownloadDao` implements it; tests use an
 * in-memory fake. Signatures are frozen by CONTRACTS.md.
 */
interface DownloadStore {
    suspend fun insert(e: DownloadEntity): Long
    suspend fun update(e: DownloadEntity)
    suspend fun get(id: Long): DownloadEntity?
    suspend fun all(): List<DownloadEntity>
    suspend fun active(): List<DownloadEntity>
    suspend fun delete(id: Long)
    fun observeAll(): Flow<List<DownloadEntity>>
}

/**
 * Robust HTTP(S) file download manager (pure JVM — no Android imports).
 *
 * Behaviour (CONTRACTS.md §download / spec §6, §23, §56):
 *  - persistent queue with [maxParallel] parallel workers (FIFO by createdAt);
 *  - `Range: bytes=<downloaded>-` resume with `If-Range` (ETag preferred, else Last-Modified),
 *    streaming into a `dest.part` file that survives process restarts;
 *  - incremental SHA-256 (existing part re-hashed before appending on resume);
 *  - final size + sha256 verification (`VERIFYING` -> `COMPLETED` / `FAILED`);
 *  - cooperative pause/cancel checked between stream chunks;
 *  - automatic retry with exponential backoff (2s/8s/32s, max 3 retries) for
 *    network-ish failures;
 *  - Wi-Fi-only gate: entries pause with "Waiting for Wi-Fi" and are re-driven
 *    by a periodic watchdog once connectivity returns;
 *  - every state transition is persisted through [DownloadStore].
 *
 * All coroutines are launched on the injected [scope] (structured concurrency,
 * no GlobalScope). Logging goes to stderr so the class stays JVM-testable.
 */
class DownloadManager(
    private val client: OkHttpClient,
    private val store: DownloadStore,
    private val network: NetworkGate,
    private val hasher: Hashing,
    private val scope: CoroutineScope,
    private val maxParallel: Int = 2,
    autoResumeOnStart: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private companion object {
        const val PART_SUFFIX = ".part"
        const val WIFI_WAIT = "Waiting for Wi-Fi"
        const val NO_NETWORK = "No network connection"
        const val TICK_MS = 500L
        const val PROGRESS_PERSIST_MS = 500L
        const val SPEED_SAMPLE_MS = 200L
        const val TERMINAL_VISIBILITY_MS = 60_000L
        const val WATCHDOG_INTERVAL_MS = 15_000L
        const val MAX_RETRIES = 3
        const val REHASH_BUFFER_BYTES = 1024 * 1024
        const val STREAM_BUFFER_BYTES = 64 * 1024
        val BACKOFF_MS = longArrayOf(2_000L, 8_000L, 32_000L)
        val TERMINAL_STATES = setOf(DownloadState.COMPLETED, DownloadState.FAILED, DownloadState.CANCELLED)
        val CONTENT_RANGE_REGEX = Regex("""bytes (\d+)-(\d+)/(\d+)""")
    }

    /** Per-id live stats feeding speed/ETA; [Tracker.completedAt] drives the 60s terminal window. */
    private data class Tracker(
        @Volatile var speed: Long = 0,
        @Volatile var eta: Long = -1,
        @Volatile var lastBytes: Long = -1,
        @Volatile var lastTs: Long = 0,
        @Volatile var completedAt: Long = -1,
    )

    /** Cooperative control flags checked between stream chunks. */
    private class Control {
        @Volatile var paused: Boolean = false
        @Volatile var cancelled: Boolean = false
    }

    private class CancelledSignal : IOException("Cancelled")
    private class PausedSignal : IOException("Paused")

    private val trackers = ConcurrentHashMap<Long, Tracker>()
    private val controls = ConcurrentHashMap<Long, Control>()
    private val calls = ConcurrentHashMap<Long, Call>()
    private val workers = ConcurrentHashMap<Long, Job>()
    private val attempts = ConcurrentHashMap<Long, AtomicInteger>()
    private val resumeRequested = ConcurrentHashMap.newKeySet<Long>()

    @Volatile private var closed = false

    /** Mirror of the store's rows so [snapshot] works without suspending. */
    private val lastEntities = MutableStateFlow<List<DownloadEntity>>(emptyList())

    /** Internal 500ms heartbeat that re-derives [progress] (speed/ETA freshness). */
    private val tick = MutableStateFlow(Unit)

    private val schedulerMutex = Mutex()
    private var schedulerJob: Job? = null
    private var tickerJob: Job? = null
    private var watchdogJob: Job? = null

    /**
     * Distinct snapshots of all non-terminal downloads (plus terminal ones from
     * the last 60s), refreshed from the store and a 500ms tick; de-duplicated
     * by (id, percent, state).
     */
    val progress: Flow<List<DownloadProgress>> =
        combine(store.observeAll(), tick) { entities, _ -> entities }
            .map { entities ->
                entities.map { toProgress(it) }.filter { it.state !in TERMINAL_STATES || isRecentlyTerminal(it.id) }
            }
            .distinctUntilChanged { old, new ->
                old.map { Triple(it.id, it.percent, it.state) } == new.map { Triple(it.id, it.percent, it.state) }
            }

    init {
        // Keep the in-memory mirror fresh for snapshot().
        scope.launch {
            try {
                store.observeAll().collect { entities -> lastEntities.value = entities }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("progress mirror collector failed: ${e.message}")
            }
        }
        if (autoResumeOnStart) {
            scope.launch {
                try {
                    val restartable = setOf(
                        DownloadState.DOWNLOADING,
                        DownloadState.CONNECTING,
                        DownloadState.QUEUED,
                        DownloadState.VERIFYING,
                    )
                    store.all().forEach { e ->
                        if (parseState(e.state) in restartable) {
                            store.update(e.copy(state = DownloadState.QUEUED.name, updatedAt = clock()))
                        }
                    }
                    kick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("auto-resume on start failed: ${e.message}")
                }
            }
        }
        // Watchdog: re-drive entries paused for network reasons once the gate is satisfied again.
        watchdogJob = scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (closed) break
                try {
                    var changed = false
                    for (e in store.active()) {
                        if (parseState(e.state) != DownloadState.PAUSED) continue
                        val wifiWait = e.error == WIFI_WAIT
                        val noNetwork = e.error == NO_NETWORK
                        if (!wifiWait && !noNetwork) continue
                        val satisfied = network.isOnline() && (!wifiWait || network.isWifi())
                        if (satisfied) {
                            resumeRequested.add(e.id)
                            store.update(e.copy(state = DownloadState.QUEUED.name, error = null, updatedAt = clock()))
                            changed = true
                        }
                    }
                    if (changed) kick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("watchdog failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Registers a new download and schedules it.
     *
     * @param expectedSize total size in bytes when known; <= 0 means unknown (resolved from headers later)
     * @return the row id assigned by the store
     */
    suspend fun enqueue(
        url: String,
        destFile: File,
        fileName: String,
        modelId: String?,
        expectedSha256: String?,
        expectedSize: Long,
        wifiOnly: Boolean,
    ): Long {
        val destPath = destFile.absolutePath
        val part = File(destPath + PART_SUFFIX)
        val already = if (part.isFile) part.length() else 0L
        val total = if (expectedSize > 0) expectedSize else 0L
        val now = clock()
        val entity = DownloadEntity(
            modelId = modelId,
            url = url,
            fileName = fileName,
            destPath = destPath,
            downloadedBytes = already,
            totalBytes = total,
            etag = null,
            lastModified = null,
            sha256 = expectedSha256,
            expectedSize = total,
            state = DownloadState.QUEUED.name,
            error = null,
            wifiOnly = wifiOnly,
            createdAt = now,
            updatedAt = now,
        )
        val id = store.insert(entity)
        trackers.computeIfAbsent(id) { Tracker() }.apply {
            speed = 0
            eta = -1
            lastBytes = already
            lastTs = now
            completedAt = -1
        }
        kick()
        return id
    }

    /**
     * Runs one scheduling pass: fills free slots (up to [maxParallel]) with
     * QUEUED entries (plus PAUSED ones flagged for resume), oldest first.
     */
    fun kick() {
        if (closed) return
        schedulerJob?.cancel()
        schedulerJob = scope.launch {
            schedulerMutex.withLock {
                try {
                    ensureTicker()
                    val candidates = store.active()
                        .filter { e ->
                            val st = parseState(e.state)
                            st == DownloadState.QUEUED ||
                                (st == DownloadState.PAUSED && resumeRequested.contains(e.id))
                        }
                        .sortedWith(compareBy({ it.createdAt }, { it.id }))
                    var slots = (maxParallel - workers.values.count { it.isActive }).coerceAtLeast(0)
                    for (c in candidates) {
                        if (slots <= 0) break
                        if (workers[c.id]?.isActive == true) continue
                        resumeRequested.remove(c.id)
                        startWorker(c.id)
                        slots -= 1
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("scheduler failed: ${e.message}")
                }
            }
        }
    }

    /** Keeps the 500ms progress tick alive while anything is live or recently terminal. */
    private fun ensureTicker() {
        if (closed) return
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                tick.value = Unit
                val anyLive = lastEntities.value.any { e ->
                    parseState(e.state) !in TERMINAL_STATES || isRecentlyTerminal(e.id)
                }
                if (!anyLive) break
            }
        }
    }

    private fun startWorker(id: Long) {
        workers.remove(id)?.cancel()
        val job = scope.launch { runWorker(id) }
        workers[id] = job
        job.invokeOnCompletion {
            workers.remove(id, job)
            kick() // a slot freed up — refill the queue
        }
    }

    /** Worker wrapper: classifies failures and drives the retry/backoff loop. */
    private suspend fun runWorker(id: Long) {
        val control = controls.computeIfAbsent(id) { Control() }
        while (true) {
            try {
                runWorkerOnce(id, control)
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val entity = try {
                    store.get(id)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (readFailure: Exception) {
                    log("id=$id: state re-read failed: ${readFailure.message}")
                    null
                } ?: return
                val part = File(entity.destPath + PART_SUFFIX)
                when {
                    control.cancelled -> {
                        runCatching { part.delete() }
                        runCatching { persist(entity, DownloadState.CANCELLED) }
                        markTerminal(id)
                        return
                    }

                    control.paused -> {
                        runCatching {
                            persist(
                                entity,
                                DownloadState.PAUSED,
                                downloadedBytes = if (part.isFile) part.length() else entity.downloadedBytes,
                            )
                        }
                        return
                    }

                    else -> {
                        val attempt = attempts.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
                        if (e is IOException && attempt <= MAX_RETRIES) {
                            val backoffMs = BACKOFF_MS[attempt - 1]
                            log("id=$id attempt $attempt failed (${e.message}); retrying in ${backoffMs}ms")
                            runCatching { persist(entity, DownloadState.QUEUED) }
                            delay(backoffMs)
                            continue
                        }
                        val message = e.message ?: e.javaClass.simpleName
                        runCatching { persist(entity, DownloadState.FAILED, error = "Download interrupted: $message") }
                        log("id=$id failed permanently: $message")
                        markTerminal(id)
                        return
                    }
                }
            }
        }
    }

    /** One full transfer attempt for [id]; throws to signal failure. */
    private suspend fun runWorkerOnce(id: Long, control: Control) {
        var entity = store.get(id) ?: return
        if (control.cancelled) return // cancel() already persisted the terminal state
        if (control.paused) {
            persist(entity, DownloadState.PAUSED)
            return
        }
        // Network gates.
        if (entity.wifiOnly && !network.isWifi()) {
            persist(entity, DownloadState.PAUSED, error = WIFI_WAIT)
            return
        }
        if (!network.isOnline()) {
            persist(entity, DownloadState.PAUSED, error = NO_NETWORK)
            return
        }

        val part = File(entity.destPath + PART_SUFFIX)
        val expectedSha = entity.sha256?.trim()?.takeIf { it.isNotEmpty() }
        val expectedTotal = entity.expectedSize.takeIf { it > 0 }
            ?: entity.totalBytes.takeIf { it > 0 }
            ?: 0L

        try {
            part.parentFile?.mkdirs()
            var existing = if (part.isFile) part.length() else 0L
            entity = persist(entity, DownloadState.CONNECTING, downloadedBytes = existing)

            val digest = MessageDigest.getInstance("SHA-256")
            if (existing > 0 && !rehashExisting(part, digest, control)) {
                if (control.cancelled) {
                    runCatching { part.delete() }
                    persist(entity, DownloadState.CANCELLED)
                    markTerminal(id)
                } else {
                    persist(
                        entity,
                        DownloadState.PAUSED,
                        downloadedBytes = if (part.isFile) part.length() else existing,
                    )
                }
                return
            }

            val tracker = trackers.computeIfAbsent(id) { Tracker() }
            tracker.speed = 0
            tracker.eta = -1
            tracker.lastBytes = existing
            tracker.lastTs = clock()

            var total = expectedTotal
            var alreadyComplete = false
            var response: Response? = null
            try {
                var omitRange = false
                while (true) {
                    val builder = Request.Builder().url(entity.url).get()
                    if (existing > 0 && !omitRange) {
                        builder.header("Range", "bytes=$existing-")
                        (entity.etag ?: entity.lastModified)?.let { builder.header("If-Range", it) }
                    }
                    val call = client.newCall(builder.build())
                    calls[id] = call
                    val resp = withContext(Dispatchers.IO) { call.execute() }
                    response = resp
                    when {
                        resp.code == 416 -> {
                            resp.close()
                            response = null
                            if (expectedTotal > 0 && existing == expectedTotal &&
                                part.isFile && part.length() == expectedTotal
                            ) {
                                alreadyComplete = true
                                break
                            }
                            if (omitRange) throw IOException("HTTP 416 Range Not Satisfiable")
                            log("id=$id: 416 with incomplete part — restarting from zero")
                            truncate(part)
                            digest.reset()
                            existing = 0
                            tracker.lastBytes = 0
                            omitRange = true
                        }

                        resp.code == 200 && existing > 0 -> {
                            // Server ignored the range: restart from scratch with this full response.
                            log("id=$id: server ignored Range — restarting from scratch")
                            truncate(part)
                            digest.reset()
                            existing = 0
                            tracker.lastBytes = 0
                            break
                        }

                        resp.code == 206 -> {
                            val range = parseContentRange(resp.header("Content-Range"))
                            if (range != null && range.first != existing) {
                                resp.close()
                                response = null
                                throw IOException("Server resumed from offset ${range.first}, expected $existing")
                            }
                            if (range != null && expectedTotal > 0 && range.third > 0 && range.third != expectedTotal) {
                                log("id=$id: server total ${range.third} differs from expected $expectedTotal")
                            }
                            break
                        }

                        resp.code >= 400 -> {
                            val suffix = if (resp.message.isBlank()) "" else " ${resp.message}"
                            resp.close()
                            response = null
                            throw IOException("HTTP ${resp.code}$suffix")
                        }

                        else -> break
                    }
                }
            } catch (e: CancellationException) {
                response?.close()
                throw e
            } catch (e: Exception) {
                response?.close()
                throw e
            }

            var done = existing
            if (!alreadyComplete) {
                val resp = response ?: throw IOException("No response to stream")
                resp.use { r ->
                    val body = r.body ?: throw IOException("Empty response body")
                    if (total <= 0) {
                        total = if (r.code == 206) {
                            val rangeTotal = parseContentRange(r.header("Content-Range"))?.third ?: 0L
                            if (rangeTotal > 0) rangeTotal else existing + body.contentLength()
                        } else {
                            body.contentLength()
                        }.takeIf { it > 0 } ?: 0L
                    }
                    // Capture resume validators for later runs.
                    entity = entity.copy(
                        etag = r.header("ETag") ?: entity.etag,
                        lastModified = r.header("Last-Modified") ?: entity.lastModified,
                    )

                    val source = body.byteStream()
                    val fos = withContext(Dispatchers.IO) { FileOutputStream(part, true) }
                    var lastPersist = 0L
                    var sampleBytes = existing
                    var sampleTs = clock()
                    try {
                        val buffer = ByteArray(STREAM_BUFFER_BYTES)
                        withContext(Dispatchers.IO) {
                            while (true) {
                                if (control.cancelled) throw CancelledSignal()
                                if (control.paused) throw PausedSignal()
                                val n = source.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                digest.update(buffer, 0, n)
                                fos.write(buffer, 0, n)
                                done += n
                                tracker.lastBytes = done
                                val now = clock()
                                if (now - sampleTs >= SPEED_SAMPLE_MS) {
                                    val dt = now - sampleTs
                                    val delta = done - sampleBytes
                                    if (delta > 0 && dt > 0) {
                                        val instant = delta * 1000L / dt
                                        tracker.speed = if (tracker.speed <= 0L) instant else (tracker.speed + instant) / 2L
                                        if (total > 0 && tracker.speed > 0L) {
                                            tracker.eta = (total - done).coerceAtLeast(0L) / tracker.speed
                                        }
                                    }
                                    sampleBytes = done
                                    sampleTs = now
                                }
                                if (now - lastPersist >= PROGRESS_PERSIST_MS) {
                                    lastPersist = now
                                    entity = persist(
                                        entity,
                                        DownloadState.DOWNLOADING,
                                        downloadedBytes = done,
                                        totalBytes = if (total > 0) total else entity.totalBytes,
                                    )
                                }
                            }
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            runCatching { fos.flush() }
                            runCatching { fos.close() }
                            runCatching { source.close() }
                        }
                    }
                }
            }

            val finalLen = part.length()
            if (total > 0 && finalLen != total) {
                throw IOException("Download incomplete: received $finalLen of $total bytes")
            }
            if (total <= 0) total = finalLen
            done = finalLen

            entity = persist(entity, DownloadState.VERIFYING, downloadedBytes = done, totalBytes = total)
            val actualSha = if (alreadyComplete) hasher.sha256(part) else hexLower(digest.digest())
            verifyAndFinish(id, entity, part, expectedSha, actualSha, total)
        } finally {
            calls.remove(id)
        }
    }

    /** Re-hashes the existing part into [digest]; false when paused/cancelled mid-way. */
    private suspend fun rehashExisting(part: File, digest: MessageDigest, control: Control): Boolean =
        withContext(Dispatchers.IO) {
            part.inputStream().use { input ->
                val buffer = ByteArray(REHASH_BUFFER_BYTES)
                while (true) {
                    if (control.cancelled || control.paused) return@use false
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) digest.update(buffer, 0, n)
                }
                true
            }
        }

    /** Size + checksum verification and the atomic part -> dest move. */
    private suspend fun verifyAndFinish(
        id: Long,
        entity: DownloadEntity,
        part: File,
        expectedSha: String?,
        actualSha: String,
        total: Long,
    ) {
        if (expectedSha != null && !actualSha.equals(expectedSha, ignoreCase = true)) {
            runCatching { part.delete() }
            val message = "Checksum mismatch: expected $expectedSha, got $actualSha"
            persist(entity, DownloadState.FAILED, downloadedBytes = 0, totalBytes = total, error = message)
            log("id=$id: $message")
            markTerminal(id)
            return
        }
        val dest = File(entity.destPath)
        val moved = withContext(Dispatchers.IO) {
            if (dest.exists()) runCatching { dest.delete() }
            part.renameTo(dest) || runCatching { part.copyTo(dest, overwrite = true); true }.getOrDefault(false)
        }
        if (!moved) {
            throw IOException("Could not move the downloaded file into place")
        }
        if (File(entity.destPath + PART_SUFFIX).exists()) {
            runCatching { File(entity.destPath + PART_SUFFIX).delete() }
        }
        persist(entity, DownloadState.COMPLETED, downloadedBytes = total, totalBytes = total, error = null)
        markTerminal(id)
    }

    /** Empties [part] in place (keeps the inode; no delete/recreate race). */
    private fun truncate(part: File) {
        if (!part.exists()) return
        RandomAccessFile(part, "rw").use { it.setLength(0) }
    }

    /** Persists a state transition and returns the updated entity. */
    private suspend fun persist(
        entity: DownloadEntity,
        state: DownloadState,
        downloadedBytes: Long = entity.downloadedBytes,
        totalBytes: Long = entity.totalBytes,
        error: String? = null,
    ): DownloadEntity {
        val updated = entity.copy(
            state = state.name,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            error = error,
            updatedAt = clock(),
        )
        store.update(updated)
        return updated
    }

    private fun markTerminal(id: Long) {
        attempts.remove(id)
        trackers.computeIfAbsent(id) { Tracker() }.completedAt = clock()
    }

    private fun parseState(value: String): DownloadState =
        runCatching { DownloadState.valueOf(value.trim()) }.getOrDefault(DownloadState.QUEUED)

    /** Parses `Content-Range: bytes 4-7/8` into (start, end, total). */
    private fun parseContentRange(header: String?): Triple<Long, Long, Long>? {
        if (header == null) return null
        val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
        return Triple(
            match.groupValues[1].toLong(),
            match.groupValues[2].toLong(),
            match.groupValues[3].toLong(),
        )
    }

    private fun hexLower(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(out)
    }

    private fun toProgress(e: DownloadEntity): DownloadProgress {
        val state = parseState(e.state)
        val tracker = trackers[e.id]
        val downloading = state == DownloadState.DOWNLOADING
        val bytes = if (downloading && tracker != null && tracker.lastBytes >= 0) {
            maxOf(e.downloadedBytes, tracker.lastBytes)
        } else {
            e.downloadedBytes
        }
        return DownloadProgress(
            id = e.id,
            modelId = e.modelId,
            fileName = e.fileName,
            url = e.url,
            downloadedBytes = bytes,
            totalBytes = e.totalBytes,
            speedBytesPerSec = if (downloading) tracker?.speed ?: 0L else 0L,
            etaSeconds = if (downloading) tracker?.eta ?: -1L else -1L,
            state = state,
            error = e.error,
        )
    }

    private fun isRecentlyTerminal(id: Long): Boolean {
        val completedAt = trackers[id]?.completedAt ?: return false
        return clock() - completedAt <= TERMINAL_VISIBILITY_MS
    }

    /** Asks the worker for [id] to stop at the next chunk; persisted as PAUSED. */
    suspend fun pause(id: Long) {
        val control = controls.computeIfAbsent(id) { Control() }
        control.paused = true
        calls[id]?.let { runCatching { it.cancel() } }
        workers[id]?.let { worker ->
            if (worker.isActive) {
                val finished = withTimeoutOrNull(1_000) { worker.join(); true }
                if (finished == null) {
                    worker.cancel()
                    runCatching { worker.join() }
                }
            }
        }
        try {
            val entity = store.get(id) ?: return
            if (parseState(entity.state) !in TERMINAL_STATES) {
                val part = File(entity.destPath + PART_SUFFIX)
                persist(
                    entity,
                    DownloadState.PAUSED,
                    downloadedBytes = if (part.isFile) part.length() else entity.downloadedBytes,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("pause($id) failed: ${e.message}")
        }
    }

    /** Clears the pause flag and re-queues a non-terminal download. */
    suspend fun resume(id: Long) {
        val control = controls.computeIfAbsent(id) { Control() }
        control.paused = false
        control.cancelled = false
        try {
            val entity = store.get(id) ?: return
            if (parseState(entity.state) in TERMINAL_STATES) return
            resumeRequested.add(id)
            store.update(entity.copy(state = DownloadState.QUEUED.name, error = null, updatedAt = clock()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("resume($id) failed: ${e.message}")
        }
        kick()
    }

    /** Stops the download, deletes the partial file and persists CANCELLED. */
    suspend fun cancel(id: Long) {
        val control = controls.computeIfAbsent(id) { Control() }
        control.cancelled = true
        control.paused = false
        calls[id]?.let { runCatching { it.cancel() } }
        workers[id]?.let { worker ->
            if (worker.isActive) {
                val finished = withTimeoutOrNull(1_000) { worker.join(); true }
                if (finished == null) {
                    worker.cancel()
                    runCatching { worker.join() }
                }
            }
        }
        try {
            val entity = store.get(id) ?: return
            if (parseState(entity.state) !in TERMINAL_STATES) {
                runCatching { File(entity.destPath + PART_SUFFIX).delete() }
                persist(entity, DownloadState.CANCELLED)
            }
            markTerminal(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("cancel($id) failed: ${e.message}")
        }
        kick()
    }

    /** Re-queues a FAILED/CANCELLED download with a fresh retry budget. */
    suspend fun retry(id: Long) {
        attempts.remove(id)
        val control = controls.computeIfAbsent(id) { Control() }
        control.paused = false
        control.cancelled = false
        try {
            val entity = store.get(id) ?: return
            store.update(entity.copy(state = DownloadState.QUEUED.name, error = null, updatedAt = clock()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("retry($id) failed: ${e.message}")
        }
        kick()
    }

    suspend fun pauseAll() {
        activeIds().forEach { pause(it) }
    }

    suspend fun resumeAll() {
        activeIds().forEach { resume(it) }
    }

    suspend fun cancelAll() {
        activeIds().forEach { cancel(it) }
    }

    private suspend fun activeIds(): List<Long> = try {
        store.active().map { it.id }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("activeIds failed: ${e.message}")
        emptyList()
    }

    /** Current progress for everything non-terminal (plus recently terminal), no store round-trip. */
    fun snapshot(): List<DownloadProgress> =
        lastEntities.value
            .map { toProgress(it) }
            .filter { it.state !in TERMINAL_STATES || isRecentlyTerminal(it.id) }

    /** Stops background jobs (watchdog/ticker/scheduler) and aborts in-flight calls. */
    fun shutdown() {
        closed = true
        watchdogJob?.cancel()
        tickerJob?.cancel()
        schedulerJob?.cancel()
        calls.values.forEach { runCatching { it.cancel() } }
    }

    private fun log(message: String) {
        System.err.println("DownloadManager: $message")
    }
}
