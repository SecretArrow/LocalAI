package com.localai.runtime.core

import com.localai.runtime.core.db.DownloadEntity
import com.localai.runtime.core.download.DownloadManager
import com.localai.runtime.core.download.DownloadStore
import com.localai.runtime.core.download.NetworkGate
import com.localai.runtime.core.model.DownloadState
import com.localai.runtime.core.util.Hashing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure JVM integration tests for [DownloadManager] against MockWebServer.
 *
 * Uses a real coroutine scope with real delays (no runTest virtual time) because
 * the manager's backoff/tick logic is wall-clock based; every wait is bounded
 * by withTimeout and polls with short delays so the suite stays CI-friendly.
 */
class DownloadManagerTest {

    private val body8 = "12345678"

    private lateinit var server: MockWebServer
    private lateinit var gate: FakeGate
    private lateinit var store: FakeDownloadStore
    private lateinit var scope: CoroutineScope
    private lateinit var manager: DownloadManager
    private val tempDirs = mutableListOf<File>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gate = FakeGate()
        store = FakeDownloadStore()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager = DownloadManager(client, store, gate, Hashing, scope, maxParallel = 2)
    }

    @After
    fun tearDown() {
        manager.shutdown()
        scope.cancel()
        runCatching { server.shutdown() }
        tempDirs.forEach { dir -> runCatching { dir.deleteRecursively() } }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun freshDownloadCompletes() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body8))
        val dest = newDest("fresh.bin")

        val id = manager.enqueue(
            url = server.url("/fresh.bin").toString(),
            destFile = dest,
            fileName = "fresh.bin",
            modelId = "model-x",
            expectedSha256 = null,
            expectedSize = 8,
            wifiOnly = false,
        )

        pollUntil { store.get(id)?.state == DownloadState.COMPLETED.name }

        assertTrue("destination file missing", dest.isFile)
        assertEquals(body8, dest.readText())
        assertEquals(8L, dest.length())
        assertFalse("part file left behind", partOf(dest).exists())
        val entity = store.get(id)
        assertNotNull(entity)
        assertEquals(DownloadState.COMPLETED.name, entity!!.state)
        assertEquals(8L, entity.downloadedBytes)
        assertEquals(8L, entity.totalBytes)
        assertEquals(1, server.requestCount)
        // The in-memory snapshot tracks the completed download (recently terminal).
        pollUntil { manager.snapshot().any { it.id == id && it.state == DownloadState.COMPLETED } }
        val snap = manager.snapshot().first { it.id == id }
        assertEquals("model-x", snap.modelId)
        assertEquals("fresh.bin", snap.fileName)
    }

    @Test
    fun resumeWithRangeHeaderAfterTruncatedResponse() = runBlocking<Unit> {
        val seenRanges = CopyOnWriteArrayList<String?>()
        val served = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seenRanges.add(request.getHeader("Range"))
                return if (served.getAndIncrement() == 0) {
                    // First attempt: a "complete" response that only carries half the
                    // expected bytes, then the socket is closed abruptly.
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(body8.substring(0, 4))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                } else {
                    // Subsequent attempt must resume from byte 4.
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 4-7/8")
                        .setBody(body8.substring(4))
                }
            }
        }
        val dest = newDest("resume.bin")

        val id = manager.enqueue(
            url = server.url("/resume.bin").toString(),
            destFile = dest,
            fileName = "resume.bin",
            modelId = null,
            expectedSha256 = null,
            expectedSize = 8,
            wifiOnly = false,
        )

        pollUntil { store.get(id)?.state == DownloadState.COMPLETED.name }

        assertTrue(dest.isFile)
        assertEquals(body8, dest.readText())
        assertEquals(8L, dest.length())
        assertFalse(partOf(dest).exists())
        assertTrue(
            "expected a Range: bytes=4- request, saw $seenRanges",
            seenRanges.contains("bytes=4-"),
        )
        assertTrue(seenRanges.size >= 2)
    }

    @Test
    fun serverIgnoringRangeRestartsFromScratch() = runBlocking<Unit> {
        // Pre-existing partial file forces a ranged request on the first attempt.
        val dest = newDest("ignored.bin")
        partOf(dest).writeText(body8.substring(0, 4))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body8)) // ignores Range

        val id = manager.enqueue(
            url = server.url("/ignored.bin").toString(),
            destFile = dest,
            fileName = "ignored.bin",
            modelId = null,
            expectedSha256 = null,
            expectedSize = 8,
            wifiOnly = false,
        )

        pollUntil { store.get(id)?.state == DownloadState.COMPLETED.name }

        assertTrue(dest.isFile)
        assertEquals("content must be the full body, not part+body", body8, dest.readText())
        assertEquals(8L, dest.length())
        assertFalse(partOf(dest).exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun checksumMismatchFailsAndDeletesPart() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body8))
        val dest = newDest("badhash.bin")

        val id = manager.enqueue(
            url = server.url("/badhash.bin").toString(),
            destFile = dest,
            fileName = "badhash.bin",
            modelId = null,
            expectedSha256 = "deadbeef",
            expectedSize = 8,
            wifiOnly = false,
        )

        pollUntil { store.get(id)?.state == DownloadState.FAILED.name }

        val entity = store.get(id)
        assertNotNull(entity)
        assertTrue(
            "error should mention the mismatch: ${entity!!.error}",
            entity.error?.contains("ismatch") == true,
        )
        assertFalse("part file must be deleted", partOf(dest).exists())
        assertFalse("destination must not exist on failure", dest.exists())
    }

    @Test
    fun wifiOnlyGatePausesWithoutTouchingTheServer() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body8))
        gate.wifi = false
        val dest = newDest("wifionly.bin")

        val id = manager.enqueue(
            url = server.url("/wifionly.bin").toString(),
            destFile = dest,
            fileName = "wifionly.bin",
            modelId = null,
            expectedSha256 = null,
            expectedSize = 8,
            wifiOnly = true,
        )

        pollUntil { store.get(id)?.state == DownloadState.PAUSED.name }
        assertEquals("Waiting for Wi-Fi", store.get(id)?.error)

        // Give any stray request a chance to slip through, then verify none did.
        delay(400)
        assertEquals(0, server.requestCount)
        assertFalse(dest.exists())
        assertFalse(partOf(dest).exists())

        // Connectivity returns + explicit resume drives the download to completion.
        gate.wifi = true
        manager.resume(id)
        pollUntil { store.get(id)?.state == DownloadState.COMPLETED.name }
        assertEquals(body8, dest.readText())
        assertEquals(1, server.requestCount)
    }

    // ---------------------------------------------------------------- helpers

    private fun newDest(name: String): File {
        val dir = createTempDirectory(prefix = "dlm-test-${System.nanoTime()}").toFile()
        tempDirs.add(dir)
        return File(dir, name)
    }

    private fun partOf(dest: File): File = File(dest.path + ".part")

    /** Polls [condition] every 25 ms inside a bounded timeout (never sleeps long). */
    private suspend fun pollUntil(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(25)
        }
    }

    private class FakeGate(
        @Volatile var online: Boolean = true,
        @Volatile var wifi: Boolean = true,
        @Volatile var metered: Boolean = false,
    ) : NetworkGate {
        override fun isOnline(): Boolean = online
        override fun isWifi(): Boolean = wifi
        override fun isMetered(): Boolean = metered
    }

    /** Thread-safe in-memory DownloadStore with a MutableStateFlow-backed observeAll. */
    private class FakeDownloadStore : DownloadStore {
        private val lock = Any()
        private val rows = mutableListOf<DownloadEntity>()
        private val flow = MutableStateFlow<List<DownloadEntity>>(emptyList())
        private var nextId = 1L

        override suspend fun insert(e: DownloadEntity): Long = synchronized(lock) {
            val id = nextId++
            rows.add(e.copy(id = id))
            publish()
            id
        }

        override suspend fun update(e: DownloadEntity) {
            synchronized(lock) {
                val index = rows.indexOfFirst { it.id == e.id }
                if (index >= 0) rows[index] = e else rows.add(e)
                publish()
            }
        }

        override suspend fun get(id: Long): DownloadEntity? = synchronized(lock) {
            rows.firstOrNull { it.id == id }
        }

        override suspend fun all(): List<DownloadEntity> = synchronized(lock) { rows.toList() }

        override suspend fun active(): List<DownloadEntity> = synchronized(lock) {
            rows.filter { it.state in ACTIVE_STATES }
        }

        override suspend fun delete(id: Long) {
            synchronized(lock) {
                rows.removeAll { it.id == id }
                publish()
            }
        }

        override fun observeAll(): Flow<List<DownloadEntity>> = flow

        private fun publish() {
            flow.value = synchronized(lock) { rows.toList() }
        }

        private companion object {
            val ACTIVE_STATES = setOf("QUEUED", "CONNECTING", "DOWNLOADING", "PAUSED", "VERIFYING")
        }
    }
}
