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
        appMaxMemoryBytes: Long = 128L * mib,
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
    fun smallModelIsRecommendedForAnEightGbPhoneWithActualNumbers() {
        val model = ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!
        val result = ModelCompatibilityChecker.assess(model, device(8, 4), info(model.id))

        assertEquals(CompatibilityCategory.RECOMMENDED, result.category)
        assertEquals(4096, result.contextLength)
        assertTrue(result.reason.contains("Estimated runtime memory:"))
        assertTrue(result.reason.contains("safe budget"))
        assertTrue(result.reason.contains("currently available"))
        assertTrue(result.estimatedMemoryBytes!! > model.fileSizeBytes)
        assertEquals(CheckStatus.OK, result.architectureStatus)
        assertEquals(CheckStatus.OK, result.storageStatus)
        assertEquals(CheckStatus.UNKNOWN, result.performanceStatus)
        assertTrue(result.performanceReason.contains("not yet measured"))
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
    fun lowMemoryDeviceBlocksAnEstimateOutsideTheReducedBudget() {
        val model = ModelCatalog.byId("qwen2.5-3b-q4-k-m")!!
        val result = ModelCompatibilityChecker.assess(
            model,
            device(8, 1, lowMemory = true),
            info(model.id),
            contextLength = 2048,
        )

        assertEquals(CompatibilityCategory.NOT_RECOMMENDED, result.category)
        assertEquals(CheckStatus.OK, result.storageStatus)
        assertTrue(result.reason.contains("Not recommended"))
        assertTrue(result.memory.totalBytes!! > result.safeBudgetBytes)
        assertTrue(result.reason.contains("low-memory pressure"))
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
        assertTrue(measured.totalBytes!! < upperBound.totalBytes!!)
    }

    @Test
    fun kvCacheScalesWithContextAndRespectsConfiguredKvElementSize() {
        val info = info("qwen2.5-0.5b-q4-k-m")
        val at2048 = ModelCompatibilityChecker.kvCacheBytes(info, 2048)
        val at4096 = ModelCompatibilityChecker.kvCacheBytes(info, 4096)

        assertEquals(12_288L * 2048L, at2048)
        assertEquals(12_288L * 4096L, at4096)
        assertEquals(at4096!! * 2, ModelCompatibilityChecker.kvBytesPerToken(info, 4)!! * 4096L)

        val estimate2048 = ModelCompatibilityChecker.estimateMemoryBytes(
            ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!, info, 2048,
        )!!
        val estimate4096 = ModelCompatibilityChecker.estimateMemoryBytes(
            ModelCatalog.byId("qwen2.5-0.5b-q4-k-m")!!, info, 4096,
        )!!
        assertTrue(estimate4096 > estimate2048)
    }

    @Test
    fun longerContextCanChangeCompatibilityForTheSameModel() {
        val model = ModelCatalog.byId("qwen2.5-3b-q4-k-m")!!
        val phone = device(8, 0, availableBytes = (3.8 * gib).toLong())
        val short = ModelCompatibilityChecker.assess(model, phone, info(model.id), contextLength = 2048)
        val long = ModelCompatibilityChecker.assess(model, phone, info(model.id), contextLength = 8192)

        assertEquals(CompatibilityCategory.SUPPORTED, short.category)
        assertEquals(CompatibilityCategory.NOT_RECOMMENDED, long.category)
        assertTrue(long.estimatedMemoryBytes!! > short.estimatedMemoryBytes!!)
        assertTrue(long.reason.contains("safe budget"))
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
        assertNotNull(result.estimatedMemoryBytes)
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
        assertNull(result.estimatedMemoryBytes)
        assertNull(result.memory.kvCacheBytes)
        assertNull(result.memory.totalBytes)
        assertTrue(result.memory.notes.any { it.contains("No GGUF metadata") })
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
        assertNotNull(result.memory.totalBytes)
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
        assertNotNull(result.estimatedMemoryBytes)
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
            assertNotNull(result.estimatedMemoryBytes)
            assertTrue(result.memory.weightsSource.contains("file size (upper bound)"))
        }
    }
}
