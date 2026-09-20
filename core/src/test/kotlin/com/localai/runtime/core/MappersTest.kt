package com.localai.runtime.core

import com.localai.runtime.core.db.DownloadEntity
import com.localai.runtime.core.db.joinCsv
import com.localai.runtime.core.db.parseBackendOrNull
import com.localai.runtime.core.db.parseCsv
import com.localai.runtime.core.db.parseModelFormat
import com.localai.runtime.core.db.toDownloadProgress
import com.localai.runtime.core.db.toModelEntity
import com.localai.runtime.core.db.toModelInfo
import com.localai.runtime.core.db.toRuntimeConfig
import com.localai.runtime.core.db.toRuntimeConfigEntity
import com.localai.runtime.core.model.BackendType
import com.localai.runtime.core.model.CatalogEntry
import com.localai.runtime.core.model.DownloadState
import com.localai.runtime.core.model.ModelFormat
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.model.ModelState
import com.localai.runtime.core.model.RuntimeConfig
import com.localai.runtime.core.model.RuntimeProfileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for entity <-> domain mappers (CSV joins, enum fallbacks, catalog mapping). */
class MappersTest {

    @Test
    fun modelInfoEntityRoundTripPreservesAllFields() {
        val info = ModelInfo(
            id = "m1",
            name = "Test Model",
            version = "2.1",
            format = ModelFormat.GGUF,
            quantization = "Q5_K_M",
            sizeBytes = 4_000_000_000L,
            sha256 = "deadbeef",
            architecture = "llama",
            contextLength = 8192,
            parameterCount = 3_000_000_000L,
            vocabSize = 32000,
            license = "MIT",
            sourceUrl = "https://example.com/m.gguf",
            minRamMb = 4096,
            recommendedRamMb = 6144,
            backends = listOf(BackendType.CPU, BackendType.GPU_VULKAN),
            state = ModelState.RUNNING,
            localPath = "/data/models/m.gguf",
            installedAt = 111L,
            updatedAt = 222L,
            favorite = true,
            collections = listOf("chat", "coding"),
            lastError = "boom",
            imported = false,
        )

        val roundTrip = info.toModelEntity().toModelInfo()

        assertEquals(info, roundTrip)
    }

    @Test
    fun modelEntityFallsBackOnUnknownEnumsAndBackendIds() {
        val entity = ModelInfo(id = "m", name = "M").toModelEntity()
            .copy(state = "BOGUS", backendsCsv = "cpu,tpu")

        val info = entity.toModelInfo()

        assertEquals(ModelState.NOT_INSTALLED, info.state)
        // Unknown backend ids fall back to CPU via BackendType.fromId.
        assertEquals(listOf(BackendType.CPU, BackendType.CPU), info.backends)
    }

    @Test
    fun csvHelpersTrimAndSkipBlanks() {
        assertEquals(listOf("a", "b", "c"), parseCsv("a, b ,,c"))
        assertEquals(emptyList<String>(), parseCsv("  "))
        assertEquals("a,b", joinCsv(listOf("a", "", "b")))
    }

    @Test
    fun runtimeConfigEntityRoundTrip() {
        val config = RuntimeConfig(
            modelId = "m1",
            backend = BackendType.GPU_OPENCL,
            cpuThreads = 4,
            gpuLayers = 10,
            contextLength = 2048,
            batchSize = 256,
            temperature = 0.1f,
            topP = 0.8f,
            topK = 5,
            minP = 0.01f,
            repeatPenalty = 1.05f,
            seed = 42L,
            streaming = false,
            memoryMapping = false,
            flashAttention = true,
            parallel = 2,
            profile = RuntimeProfileName.LOW_MEMORY,
        )
        assertEquals(config, config.toRuntimeConfigEntity().toRuntimeConfig())

        val autoBackend = RuntimeConfig(modelId = "m2")
        assertEquals(autoBackend, autoBackend.toRuntimeConfigEntity().toRuntimeConfig())
    }

    @Test
    fun runtimeConfigEntityUnknownEnumsFallBackSafely() {
        val entity = RuntimeConfig(modelId = "m", backend = BackendType.CPU).toRuntimeConfigEntity()
            .copy(profile = "BOGUS", backendId = "quantum")

        val config = entity.toRuntimeConfig()

        assertEquals(RuntimeProfileName.CUSTOM, config.profile)
        assertNull(config.backend)
        assertNull(parseBackendOrNull(null))
        assertNull(parseBackendOrNull(""))
        assertEquals(BackendType.CPU, parseBackendOrNull("cpu"))
        assertEquals(BackendType.GPU_VULKAN, parseBackendOrNull("vulkan"))
    }

    @Test
    fun catalogEntryToModelInfoMapsAllFields() {
        val entry = CatalogEntry(
            id = "llama3",
            name = "Llama 3",
            version = "1.2",
            format = "GGUF",
            quantization = "Q4_K_M",
            architecture = "llama",
            size = 2_147_483_648L,
            sha256 = "abc",
            downloadUrl = "https://example.com/llama3.gguf",
            license = "MIT",
            backends = listOf("cpu", "vulkan"),
            minRamMb = 2048,
            recommendedRamMb = 4096,
            contextLength = 8192,
            parameterCount = 3_000_000_000L,
        )

        val info = entry.toModelInfo()

        assertEquals("llama3", info.id)
        assertEquals("Llama 3", info.name)
        assertEquals("1.2", info.version)
        assertEquals(ModelFormat.GGUF, info.format)
        assertEquals("Q4_K_M", info.quantization)
        assertEquals("llama", info.architecture)
        assertEquals(2_147_483_648L, info.sizeBytes)
        assertEquals("abc", info.sha256)
        assertEquals("https://example.com/llama3.gguf", info.sourceUrl)
        assertEquals("MIT", info.license)
        assertEquals(listOf(BackendType.CPU, BackendType.GPU_VULKAN), info.backends)
        assertEquals(2048, info.minRamMb)
        assertEquals(4096, info.recommendedRamMb)
        assertEquals(8192, info.contextLength)
        assertEquals(3_000_000_000L, info.parameterCount)
        assertEquals(ModelState.NOT_INSTALLED, info.state)
        assertNull(info.localPath)
        assertEquals(false, info.imported)
    }

    @Test
    fun catalogEntryFormatMappingIsCaseInsensitiveWithFallback() {
        assertEquals(ModelFormat.GGUF, parseModelFormat("gguf"))
        assertEquals(ModelFormat.GGUF, parseModelFormat(" GGUF "))
        assertEquals(ModelFormat.ONNX, parseModelFormat("Onnx"))
        assertEquals(ModelFormat.TFLITE, parseModelFormat("tflite"))
        assertEquals(ModelFormat.SAFETENSORS, parseModelFormat("SafeTensors"))
        assertEquals(ModelFormat.UNKNOWN, parseModelFormat("mxnet"))
        assertEquals(ModelFormat.UNKNOWN, parseModelFormat(""))

        val unknown = CatalogEntry(
            id = "x",
            name = "X",
            format = "mxnet",
            downloadUrl = "https://e/x.bin",
            backends = listOf("tpu"),
        )
        assertEquals(ModelFormat.UNKNOWN, unknown.toModelInfo().format)
        assertEquals(listOf(BackendType.CPU), unknown.toModelInfo().backends)
    }

    @Test
    fun downloadEntityToProgressMapsStateAndPercent() {
        val entity = DownloadEntity(
            modelId = "m1",
            url = "https://example.com/m.gguf",
            fileName = "m.gguf",
            destPath = "/data/downloads/m.gguf",
            downloadedBytes = 25L,
            totalBytes = 100L,
            etag = "e",
            lastModified = null,
            sha256 = "aa",
            expectedSize = 100L,
            state = "DOWNLOADING",
            error = null,
            wifiOnly = true,
            createdAt = 1L,
            updatedAt = 2L,
        )

        val progress = entity.toDownloadProgress(speedBytesPerSec = 10L, etaSeconds = 8L)

        assertEquals(DownloadState.DOWNLOADING, progress.state)
        assertEquals("m1", progress.modelId)
        assertEquals("m.gguf", progress.fileName)
        assertEquals("https://example.com/m.gguf", progress.url)
        assertEquals(25L, progress.downloadedBytes)
        assertEquals(100L, progress.totalBytes)
        assertEquals(10L, progress.speedBytesPerSec)
        assertEquals(8L, progress.etaSeconds)
        assertEquals(25, progress.percent)

        val corrupted = entity.copy(state = "WAT")
        assertEquals(DownloadState.QUEUED, corrupted.toDownloadProgress().state)
    }
}
