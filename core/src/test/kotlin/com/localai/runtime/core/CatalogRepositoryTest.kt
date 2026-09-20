package com.localai.runtime.core

import com.localai.runtime.core.model.CatalogEntry
import com.localai.runtime.core.model.CatalogManifest
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.core.repo.CatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for manifest parsing and catalog diffing (no network, no Android).
 * Covers [CatalogRepository.parse], [CatalogRepository.sanitize] and [CatalogRepository.diffEntries].
 */
class CatalogRepositoryTest {

    private fun entry(
        id: String,
        version: String = "1.0",
        url: String = "https://example.com/$id.gguf",
    ) = CatalogEntry(id = id, name = "Model $id", version = version, downloadUrl = url)

    @Test
    fun parseValidManifest() {
        val json = """
            {
              "version": 2,
              "updatedAt": "2025-06-01T00:00:00Z",
              "models": [
                {
                  "id": "llama3",
                  "name": "Llama 3 8B",
                  "version": "1.2",
                  "format": "GGUF",
                  "quantization": "Q4_K_M",
                  "size": 4294967296,
                  "sha256": "abc123",
                  "downloadUrl": "https://example.com/llama3.gguf",
                  "license": "MIT",
                  "backends": ["cpu", "vulkan"],
                  "minRamMb": 4096,
                  "recommendedRamMb": 6144,
                  "contextLength": 8192,
                  "parameterCount": 8000000000
                }
              ]
            }
        """.trimIndent()

        val manifest = CatalogRepository.parse(json)

        assertEquals(2, manifest.version)
        assertEquals("2025-06-01T00:00:00Z", manifest.updatedAt)
        assertEquals(1, manifest.models.size)
        val model = manifest.models.first()
        assertEquals("llama3", model.id)
        assertEquals("Llama 3 8B", model.name)
        assertEquals("1.2", model.version)
        assertEquals("Q4_K_M", model.quantization)
        assertEquals(4294967296L, model.size)
        assertEquals("abc123", model.sha256)
        assertEquals("https://example.com/llama3.gguf", model.downloadUrl)
        assertEquals(listOf("cpu", "vulkan"), model.backends)
        assertEquals(8192, model.contextLength)
    }

    @Test
    fun parseIgnoresUnknownKeys() {
        val json = """
            {
              "version": 1,
              "updatedAt": "u1",
              "futureTopLevel": {"x": 1},
              "models": [
                {"id": "m", "name": "M", "downloadUrl": "https://e/m.gguf", "unknownField": true, "another": [1, 2, 3]}
              ]
            }
        """.trimIndent()

        val manifest = CatalogRepository.parse(json)

        assertEquals(1, manifest.models.size)
        assertEquals("m", manifest.models.first().id)
        assertEquals("M", manifest.models.first().name)
    }

    @Test
    fun parseSkipsEntriesMissingRequiredFields() {
        val json = """
            {
              "models": [
                {"name": "NoId"},
                {"id": "NoUrl", "name": "NoUrl"},
                {"id": "ok", "name": "Ok", "downloadUrl": "https://e/ok.gguf"}
              ]
            }
        """.trimIndent()

        val manifest = CatalogRepository.parse(json)

        assertEquals(listOf("ok"), manifest.models.map { it.id })
    }

    @Test
    fun parseEmptyManifestObject() {
        val manifest = CatalogRepository.parse("{}")

        assertEquals(1, manifest.version)
        assertTrue(manifest.models.isEmpty())
    }

    @Test
    fun sanitizeDropsInvalidAndDuplicateEntries() {
        val entries = listOf(
            entry("a"),
            entry("b", url = "   "),
            entry(""),
            entry("a"),
        )

        val clean = CatalogRepository.sanitize(entries)

        assertEquals(listOf("a"), clean.map { it.id })
    }

    @Test
    fun diffEntriesReportsNewUpdatedAndRemoved() {
        val installed = listOf(
            ModelInfo(id = "same", name = "Same", version = "1.0"),
            ModelInfo(id = "changed", name = "Changed", version = "1.0"),
            ModelInfo(id = "gone", name = "Gone", version = "1.0"),
        )
        val catalog = CatalogManifest(
            version = 3,
            updatedAt = "2025-07-01",
            models = listOf(
                entry("same", version = "1.0"),
                entry("changed", version = "2.0"),
                entry("fresh", version = "0.9"),
            ),
        )

        val diff = CatalogRepository.diffEntries(catalog, installed)

        assertEquals(listOf("fresh"), diff.newModels.map { it.id })
        assertEquals(listOf("changed"), diff.updatedModels.map { it.id })
        assertEquals(listOf("gone"), diff.removedIds)
        assertEquals("2025-07-01", diff.catalogVersion)
    }

    @Test
    fun diffEntriesWithEmptyCatalogRemovesEverything() {
        val installed = listOf(
            ModelInfo(id = "a", name = "A"),
            ModelInfo(id = "b", name = "B"),
        )

        val diff = CatalogRepository.diffEntries(CatalogManifest(models = emptyList()), installed)

        assertTrue(diff.newModels.isEmpty())
        assertTrue(diff.updatedModels.isEmpty())
        assertEquals(listOf("a", "b"), diff.removedIds)
    }

    @Test
    fun diffEntriesCatalogVersionFallsBackToSchemaVersion() {
        val diff = CatalogRepository.diffEntries(
            CatalogManifest(version = 5, updatedAt = ""),
            emptyList(),
        )

        assertEquals("5", diff.catalogVersion)
    }

    @Test
    fun diffEntriesIgnoresDuplicateCatalogIds() {
        val installed = listOf(ModelInfo(id = "a", name = "A", version = "1.0"))
        val catalog = CatalogManifest(
            models = listOf(entry("a", version = "1.0"), entry("a", version = "9.9")),
        )

        val diff = CatalogRepository.diffEntries(catalog, installed)

        assertTrue(diff.newModels.isEmpty())
        assertTrue(diff.updatedModels.isEmpty())
        assertTrue(diff.removedIds.isEmpty())
    }
}
