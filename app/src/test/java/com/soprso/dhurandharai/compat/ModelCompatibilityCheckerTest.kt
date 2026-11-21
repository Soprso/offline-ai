package com.soprso.dhurandharai.compat

import com.soprso.dhurandharai.catalog.ModelCatalog
import com.soprso.dhurandharai.device.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCompatibilityCheckerTest {

    private val gib = 1024L * 1024L * 1024L
    private val mib = 1024L * 1024L

    private fun device(
        totalGib: Long,
        availableGib: Long,
        abi: String = "arm64-v8a",
        lowMemory: Boolean = false,
        freeStorageBytes: Long = 30 * gib,
        appMaxMemoryBytes: Long = 256L * mib,
        nativeLibraryBytes: Long = 32L * mib,
        cpuCount: Int = 8,
        availableBytes: Long? = null,
    ) = DeviceProfile(
        manufacturer = "Nothing",
        model = "A015",
        androidVersion = "14",
        sdkVersion = 34,
        totalMemoryBytes = totalGib * gib,
        availableMemoryBytes = availableBytes ?: availableGib * gib,
        lowMemory = lowMemory,
        lowRamDevice = false,
        memoryThresholdBytes = 0,
        memoryClassMb = 192,
        largeMemoryClassMb = 384,
        appMaxMemoryBytes = appMaxMemoryBytes,
        nativeLibraryBytes = nativeLibraryBytes,
        cpuCount = cpuCount,
        primaryAbi = abi,
        supportedAbis = listOf(abi),
        internalStorageBytes = 64 * gib,
        availableStorageBytes = freeStorageBytes,
        availableModelStorageBytes = freeStorageBytes,
        summary = "test device",
    )

    private fun info(modelId: String): GgufInfo =
        ModelCompatibilityChecker.infoFromProfile(ModelCatalog.byId(modelId)!!)!!

    @Test
    fun estimatesProvideDownloadMinimumAndRecommendedSystemRam() {
        val model = ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!
        val result = ModelCompatibilityChecker.assess(model, device(8, 4), info(model.id))

        assertEquals(4096, result.contextLength)
        assertNotNull(result.minimumSystemRamBytes)
        assertNotNull(result.recommendedSystemRamBytes)
        assertNotNull(result.memory.inferencePeakBytes)
        assertNotNull(result.memory.systemOverheadBytes)
        assertTrue(result.minimumSystemRamBytes!! > model.fileSizeBytes)
        assertTrue(result.minimumSystemRamBytes!! > result.memory.inferencePeakBytes!!)
        assertTrue(result.recommendedSystemRamBytes!! > result.minimumSystemRamBytes!!)
        assertEquals(result.minimumSystemRamBytes, result.estimatedMemoryBytes)
        assertEquals(CheckStatus.OK, result.architectureStatus)
        assertEquals(CheckStatus.OK, result.storageStatus)
        assertEquals(CheckStatus.UNKNOWN, result.performanceStatus)
        assertTrue(result.performanceReason.contains("not yet measured"))
        assertTrue(result.reason.contains("Minimum system RAM"))
        assertTrue(result.reason.contains("Recommended system RAM"))
        assertTrue(result.reason.contains("safe budget"))
    }

    @Test
    fun safeBudgetNeverExceedsTotalOrCurrentlyAvailableMemory() {
        val normal = ModelCompatibilityChecker.safeBudgetBytes(device(8, 4))
        val scarce = ModelCompatibilityChecker.safeBudgetBytes(device(8, 1))
        val pressured = ModelCompatibilityChecker.safeBudgetBytes(device(8, 1, lowMemory = true))

        assertEquals(4L * gib * 80 / 100, normal)
        assertEquals(1L * gib * 80 / 100, scarce)
        assertEquals(1L * gib * 50 / 100, pressured)
        assertTrue(normal <= 8L * gib * 45 / 100)
    }

    @Test
    fun ramRequirementsVaryByQuantizationAndModelSize() {
        val q4 = ModelCatalog.byId("smollm2-360m-q4-k-m")!!
        val q3 = ModelCatalog.byId("smollm2-360m-q3-k-m")!!
        val tiny = ModelCatalog.byId("tinyllama-1.1b-q4-k-m")!!
        val q4Result = ModelCompatibilityChecker.assess(q4, device(8, 4), info(q4.id), contextLength = 4096)
        val q3Result = ModelCompatibilityChecker.assess(q3, device(8, 4), info(q3.id), contextLength = 4096)
        val tinyResult = ModelCompatibilityChecker.assess(tiny, device(8, 4), info(tiny.id), contextLength = 1024)

        assertTrue(q4Result.minimumSystemRamBytes!! > q3Result.minimumSystemRamBytes!!)
        assertTrue(tinyResult.minimumSystemRamBytes!! > q4Result.minimumSystemRamBytes!!)
        assertTrue(q4Result.minimumSystemRamBytes!! > q4.fileSizeBytes)
    }

    @Test
    fun kvCacheAndSystemRamScaleWithContextWindow() {
        val model = ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!
        val gInfo = info(model.id)
        val at2048 = ModelCompatibilityChecker.kvCacheBytes(gInfo, 2048)
        val at4096 = ModelCompatibilityChecker.kvCacheBytes(gInfo, 4096)

        assertEquals(12_288L * 2048L, at2048)
        assertEquals(12_288L * 4096L, at4096)
        assertEquals(at4096!! * 2, ModelCompatibilityChecker.kvBytesPerToken(gInfo, 4)!! * 4096L)

        val estimate2048 = ModelCompatibilityChecker.estimateMemoryBytes(model, gInfo, 2048)!!
        val estimate4096 = ModelCompatibilityChecker.estimateMemoryBytes(model, gInfo, 4096)!!
        assertTrue(estimate4096 > estimate2048)
    }

    @Test
    fun longerContextCanWorsenCompatibilityForTheSameModel() {
        val model = ModelCatalog.byId("qwen2.5-3b-q4-k-m")!!
        val phone = device(8, 4)
        val short = ModelCompatibilityChecker.assess(model, phone, info(model.id), contextLength = 1024)
        val long = ModelCompatibilityChecker.assess(model, phone, info(model.id), contextLength = 8192)

        assertTrue(long.minimumSystemRamBytes!! > short.minimumSystemRamBytes!!)
        assertTrue(long.category != CompatibilityCategory.RECOMMENDED)
        assertTrue(
            long.category == CompatibilityCategory.NOT_RECOMMENDED ||
                long.category == CompatibilityCategory.SUPPORTED
        )
    }

    @Test
    fun installedTensorBytesOverridePublishedFileSizeUpperBound() {
        val model = ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!
        val fromSource = info(model.id)
        val fromFile = fromSource.copy(
            tensorBytes = 64L * mib,
            tensorTypes = setOf(8, 15),
        )

        val upperBound = ModelCompatibilityChecker.memoryBreakdown(model, fromSource, 4096, device(8, 4))
        val measured = ModelCompatibilityChecker.memoryBreakdown(model, fromFile, 4096, device(8, 4))

        assertEquals(model.fileSizeBytes, upperBound.weightsBytes)
        assertTrue(upperBound.weightsSource.contains("upper bound"))
        assertTrue(upperBound.notes.any { it.contains("upper bound") })
        assertEquals(64L * mib, measured.weightsBytes)
        assertTrue(measured.weightsSource.contains("GGUF tensor descriptors"))
        assertTrue(measured.weightsSource.contains("2 tensor types"))
        assertTrue(measured.inferencePeakBytes!! < upperBound.inferencePeakBytes!!)
    }

    @Test
    fun largeModelIsNotRecommendedOnSmallDeviceButEstimateIsShown() {
        val model = ModelCatalog.byId("phi3-mini-4k-q4")!!
        val result = ModelCompatibilityChecker.assess(model, device(6, 3), info(model.id), contextLength = 4096)

        assertEquals(CompatibilityCategory.NOT_RECOMMENDED, result.category)
        assertNotNull(result.minimumSystemRamBytes)
        assertNotNull(result.recommendedSystemRamBytes)
        assertTrue(result.reason.contains("Not recommended"))
        assertTrue(
            result.memory.inferencePeakBytes!! > result.safeBudgetBytes ||
                result.minimumSystemRamBytes!! > 6L * gib
        )
    }

    @Test
    fun gemmaThreeArchitectureAndSlidingWindowAreRecognised() {
        val model = ModelCatalog.byId("gemma3-1b-q4-0")!!
        val info = info(model.id)
        val result = ModelCompatibilityChecker.assess(model, device(8, 4), info)

        assertEquals(KvLayout.SLIDING_WINDOW_6, info.kvLayout)
        assertEquals(CheckStatus.OK, result.architectureStatus)
        assertTrue(result.architectureReason.contains("gemma3"))
        assertTrue(result.performanceReason.contains("not yet measured"))
    }

    @Test
    fun unknownArchitectureIsSeparateFromMemoryAndStorage() {
        val model = ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!
        val unknown = model.copy(architecture = "madeup")
        val result = ModelCompatibilityChecker.assess(
            unknown,
            device(8, 4),
            info(model.id).copy(architecture = "madeup"),
        )

        assertEquals(CompatibilityCategory.UNKNOWN, result.category)
        assertEquals(CheckStatus.UNKNOWN, result.architectureStatus)
        assertTrue(result.architectureReason.contains("bundled llama.cpp"))
        assertNotNull(result.minimumSystemRamBytes)
        assertEquals(CheckStatus.OK, result.storageStatus)
    }

    @Test
    fun nonArm64ApisFailBeforeMemoryIsConsidered() {
        val model = ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!
        val result = ModelCompatibilityChecker.assess(
            model,
            device(8, 4, abi = "armeabi-v7a"),
            info(model.id),
        )

        assertEquals(CompatibilityCategory.NOT_RECOMMENDED, result.category)
        assertEquals(CheckStatus.FAIL, result.architectureStatus)
        assertTrue(result.architectureReason.contains("arm64-v8a"))
    }

    @Test
    fun missingGgufMetadataReportsUnknownInsteadOfFalsePrecision() {
        val model = ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!
        val result = ModelCompatibilityChecker.assess(model, device(8, 4), null)

        assertEquals(CompatibilityCategory.UNKNOWN, result.category)
        assertNull(result.minimumSystemRamBytes)
        assertNull(result.recommendedSystemRamBytes)
        assertNull(result.memory.inferencePeakBytes)
        assertNull(result.memory.kvCacheBytes)
        assertTrue(result.memory.notes.any { it.contains("RAM estimates are UNKNOWN") || it.contains("No GGUF metadata") })
        assertTrue(result.reason.contains("UNKNOWN"))
    }

    @Test
    fun unknownKvLayoutKeepsEstimateButPreventsARecommendation() {
        val model = ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!
        val unknownLayout = info(model.id).copy(
            architecture = "madeup", // sliding window pattern is not verified for this arch
            slidingWindow = 512,
            kvLayout = KvLayout.UNKNOWN,
        )
        val result = ModelCompatibilityChecker.assess(model, device(8, 4), unknownLayout)

        assertFalse(result.memory.kvComplete)
        assertNotNull(result.memory.inferencePeakBytes)
        assertEquals(CompatibilityCategory.UNKNOWN, result.category)
    }

    @Test
    fun insufficientStorageIsSeparateFromMemory() {
        val model = ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!
        val result = ModelCompatibilityChecker.assess(
            model,
            device(8, 4, freeStorageBytes = 512L * mib),
            info(model.id),
        )

        assertEquals(CompatibilityCategory.NOT_RECOMMENDED, result.category)
        assertEquals(CheckStatus.FAIL, result.storageStatus)
        assertTrue(result.storageReason.contains("free model storage"))
        assertNotNull(result.minimumSystemRamBytes)
    }

    @Test
    fun contextOptionsRespectTrainContextAndRecommendedDefault() {
        val info = info("qwen2.5-0.5b-q4-k-m").copy(trainContextLength = 2048)

        assertEquals(listOf(512, 1024, 2048), ModelCompatibilityChecker.contextOptions(info, 4096))
        assertEquals(2048, ModelCompatibilityChecker.defaultContext(info, 4096))
        assertEquals(512, ModelCompatibilityChecker.defaultContext(info, 128))
    }

    @Test
    fun sourceProfilesProvideEstimatesForUninstalledCatalogueEntries() {
        ModelCatalog.models.forEach { model ->
            val info = ModelCompatibilityChecker.infoFromProfile(model)
            assertNotNull(info)
            assertTrue(info!!.architecture!!.isNotBlank())
            assertTrue(info.trainContextLength!! > 0)
            assertTrue(info.metadataSource.contains("GGUF header"))

            val result = ModelCompatibilityChecker.assess(model, device(8, 4), info)
            assertNotNull(result.minimumSystemRamBytes)
            assertNotNull(result.recommendedSystemRamBytes)
            assertTrue(result.memory.weightsSource.contains("upper bound"))
        }
    }
}
