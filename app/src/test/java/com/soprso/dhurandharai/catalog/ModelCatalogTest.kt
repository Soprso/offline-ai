package com.soprso.dhurandharai.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun catalogueIsSmallUniqueAndDeliberatelyVersioned() {
        assertEquals(3, ModelCatalog.VERSION)
        assertEquals(22, ModelCatalog.models.size)
        assertEquals(
            ModelCatalog.models.size,
            ModelCatalog.models.map { it.id }.toSet().size,
        )
        assertEquals(
            setOf("qwen2", "llama", "gemma3", "qwen3", "phi3", "granitemoe"),
            ModelCatalog.models.map { it.architecture }.toSet(),
        )
    }

    @Test
    fun downloadableEntriesArePinnedToARealHttpsFileAndChecksum() {
        ModelCatalog.models.filterNot { it.gated }.forEach { model ->
            assertTrue(model.fileSizeBytes > 0)
            assertNotNull(model.revision)
            assertTrue(model.revision!!.length >= 7)
            val url = model.downloadUrl
            assertNotNull(url)
            assertTrue(url!!.startsWith("https://huggingface.co/"))
            assertTrue(url.contains("/resolve/${model.revision}/"))
            assertTrue(url.contains(model.fileName))
            assertTrue(url.endsWith("?download=true"))
            val sha = model.sha256
            assertNotNull(sha)
            assertEquals(64, sha!!.length)
            assertTrue(sha.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun gatedEntriesExplainWhyTheyCannotBeDownloadedDirectly() {
        val gated = ModelCatalog.models.filter { it.gated }
        assertEquals(2, gated.size)
        gated.forEach { model ->
            assertNull(model.downloadUrl)
            assertNull(model.sha256)
            assertTrue(model.sourceUrl.startsWith("https://huggingface.co/google/"))
            assertTrue(model.description.contains("license"))
        }
    }

    @Test
    fun everyCatalogueEntryHasARevisionPinnedArchitectureProfile() {
        ModelCatalog.models.forEach { model ->
            val profile = model.profile
            assertNotNull(profile)
            assertEquals(model.architecture, profile!!.architecture)
            assertTrue(profile.blockCount > 0)
            assertTrue(profile.contextLength >= 1024)
            assertTrue(profile.keyValueHeadCount > 0)
            assertTrue(profile.keyLength > 0)
            assertTrue(profile.valueLength > 0)
            assertTrue(profile.source.isNotBlank())
            assertTrue(profile.source.contains("GGUF header") || profile.source.contains("HF API"))
        }
    }

    @Test
    fun importedModelsGetTheirOwnCatalogIdNamespace() {
        val imported = ModelCatalog.fromImported(
            id = "local-123",
            displayName = "my-model.gguf",
            fileName = "my-model.gguf",
            sizeBytes = 1024L,
            architecture = "llama",
            parameterCount = null,
            quantization = null,
            sha256 = null,
        )
        assertEquals("manual:local-123", imported.id)
        assertEquals("Manual import", imported.publisher)
        assertNull(imported.downloadUrl)
        assertEquals(1024L, imported.fileSizeBytes)
    }
}
