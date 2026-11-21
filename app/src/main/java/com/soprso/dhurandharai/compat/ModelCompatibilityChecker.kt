package com.soprso.dhurandharai.compat

import com.soprso.dhurandharai.catalog.CatalogModel
import com.soprso.dhurandharai.device.DeviceProfile
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class CompatibilityCategory {
    RECOMMENDED,
    SUPPORTED,
    NOT_RECOMMENDED,
    UNKNOWN,
}

enum class CheckStatus {
    OK,
    UNKNOWN,
    FAIL,
}

data class MemoryBreakdown(
    val weightsBytes: Long?,
    val weightsSource: String,
    val kvCacheBytes: Long?,
    val kvDtype: String,
    val kvComplete: Boolean,
    val runtimeBytes: Long?,
    val inferenceBuffersBytes: Long?,
    val marginBytes: Long?,
    val systemOverheadBytes: Long?,
    /** Estimated peak memory for the inference working set (weights + KV + runtime + buffers + margin). */
    val inferencePeakBytes: Long?,
    /** Conservative estimated minimum system RAM for a potentially usable inference session. */
    val minimumSystemRamBytes: Long?,
    /** Conservative estimated system RAM for comfortable operating headroom. */
    val recommendedSystemRamBytes: Long?,
    /** Backward-compatible alias for [inferencePeakBytes]. */
    val totalBytes: Long?,
    val sourceLabel: String,
    val detail: String,
    val notes: List<String>,
)

data class CompatibilityAssessment(
    val category: CompatibilityCategory,
    /** Backward-compatible alias for [memory.minimumSystemRamBytes]. */
    val estimatedMemoryBytes: Long?,
    val minimumSystemRamBytes: Long?,
    val recommendedSystemRamBytes: Long?,
    val safeBudgetBytes: Long,
    val contextLength: Int,
    val reason: String,
    val memory: MemoryBreakdown,
    val architectureStatus: CheckStatus,
    val architectureReason: String,
    val storageStatus: CheckStatus,
    val storageReason: String,
    val performanceStatus: CheckStatus,
    val performanceReason: String,
)

/**
 * Model-specific memory/RAM estimate (conservative where exact measurements are unavailable).
 *
 * Weights come from GGUF tensor descriptors when a file is available, otherwise
 * from the published file size as a documented upper bound (quantization is reflected
 * in file/tensor size). KV cache is derived from layer count, KV heads, head size,
 * context length and the configured F16 K/V cache; sliding-window layouts use their
 * real window where the architecture is known. Runtime and compute buffers use the
 * configured batch, micro-batch, thread count and model dimensions to approximate
 * llama.cpp inference overhead. A conservative OS/app overhead reserve is included in
 * minimum/recommended system RAM estimates. Anything that cannot be derived is reported
 * as UNKNOWN rather than given a confident number. Android memory pressure changes the
 * safe budget, never the model requirement estimates.
 */
object ModelCompatibilityChecker {

    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * 1024L * 1024L
    private const val N_BATCH = 512
    private const val N_UBATCH = 512
    private const val N_SEQ_MAX = 1
    private const val KV_ELEMENT_BYTES = 2 // native context uses F16 K and V
    private const val SAFETY_MARGIN_NUM = 25
    private const val SAFETY_MARGIN_DEN = 100
    /** Conservative baseline for Android system + app/background overhead (estimate). */
    private const val SYSTEM_OVERHEAD_BYTES = 1536L * MIB
    /** Comfortable headroom factor applied to minimum system RAM (estimate). */
    private const val RECOMMENDED_HEADROOM_NUM = 135
    private const val RECOMMENDED_HEADROOM_DEN = 100
    private const val MIN_CONTEXT = 512

    private val CONTEXT_CHOICES = listOf(512, 1024, 2048, 4096, 8192)

    // Verified against llama_model_mapping() in pinned llama.cpp b12558.
    private val KNOWN_ARCHITECTURES = setOf(
        "llama", "llama4", "qwen", "qwen2", "qwen2moe", "qwen3", "qwen3moe",
        "gemma", "gemma2", "gemma3", "gemma3n", "mistral", "mixtral",
        "phi2", "phi3", "phimoe", "falcon", "baichuan", "internlm2",
        "granite", "granitemoe", "granitehybrid", "graniteswitch", "granite_swa",
        "starcoder", "starcoder2", "yi", "deepseek2", "gpt2", "bert",
    )

    /** Builds an estimate-only GgufInfo from verified source-repository metadata. */
    fun infoFromProfile(model: CatalogModel): GgufInfo? {
        val profile = model.profile ?: return null
        val perToken = safeMultiply(
            profile.blockCount.toLong(),
            Math.multiplyExact(profile.keyValueHeadCount.toLong(), (profile.keyLength + profile.valueLength) * 2L),
        ) ?: return null
        val layout = when {
            profile.slidingWindow == null -> KvLayout.STANDARD
            profile.architecture == "gemma3" -> KvLayout.SLIDING_WINDOW_6
            profile.architecture == "gemma2" -> KvLayout.SLIDING_WINDOW_2
            else -> KvLayout.UNKNOWN
        }
        return GgufInfo(
            version = 0,
            architecture = profile.architecture,
            quantization = model.quantization,
            fileType = null,
            parameterCount = null,
            trainContextLength = profile.contextLength,
            layerCount = profile.blockCount,
            embeddingLength = profile.embeddingLength,
            headCount = profile.headCount,
            keyValueHeadCount = profile.keyValueHeadCount,
            keyLength = profile.keyLength,
            valueLength = profile.valueLength,
            feedForwardLength = null,
            vocabSize = null,
            slidingWindow = profile.slidingWindow,
            tensorCount = null,
            tensorBytes = null,
            tensorElementCount = null,
            tensorTypes = emptySet(),
            kvBytesPerToken = perToken,
            kvLayout = layout,
            metadataSource = profile.source,
            notes = listOf(
                "Source GGUF header; installed file metadata takes precedence.",
                "Weights are a published file-size upper bound until the file is opened.",
            ),
        )
    }

    fun contextOptions(info: GgufInfo?, fallback: Int): List<Int> {
        val max = info?.trainContextLength
            ?.takeIf { it >= MIN_CONTEXT }
            ?: fallback.coerceAtLeast(MIN_CONTEXT)
        val options = CONTEXT_CHOICES.filter { it <= max }
        return options.ifEmpty { listOf(max) }
    }

    fun defaultContext(info: GgufInfo?, fallback: Int): Int {
        val options = contextOptions(info, fallback)
        return options.lastOrNull { it <= fallback } ?: options.first()
    }

    /** K/V bytes for one context position, using the configured F16 cache. */
    fun kvBytesPerToken(info: GgufInfo, kvElementBytes: Int = KV_ELEMENT_BYTES): Long? {
        val base = info.kvBytesPerToken?.takeIf { it > 0 } ?: return null
        return safeMultiply(base / KV_ELEMENT_BYTES, kvElementBytes.toLong())
    }

    /** Approximate KV allocation for [contextLength] and the model's layer layout. */
    fun kvCacheBytes(
        info: GgufInfo,
        contextLength: Int,
        kvElementBytes: Int = KV_ELEMENT_BYTES,
    ): Long? {
        val layers = info.layerCount?.takeIf { it > 0 } ?: return null
        val scaledPerToken = kvBytesPerToken(info, kvElementBytes) ?: return null
        // kvBytesPerToken already includes every layer. Sliding-window layouts
        // must scale from one layer, otherwise the cache is overcounted by the
        // number of layers.
        if (scaledPerToken % layers != 0L) return null
        val perLayerToken = scaledPerToken / layers
        val context = contextLength.coerceAtLeast(MIN_CONTEXT).toLong()
        return try {
            val layerTokens = when (info.kvLayout) {
                KvLayout.STANDARD -> layers.toLong() * context
                KvLayout.SLIDING_WINDOW_2 -> {
                    val dense = (0 until layers).count { it % 2 == 1 }.toLong()
                    val swa = layers - dense
                    val window = min(context, info.slidingWindow?.toLong() ?: context)
                    dense * context + swa * window
                }
                KvLayout.SLIDING_WINDOW_6 -> {
                    val dense = (0 until layers).count { it % 6 == 5 }.toLong()
                    val swa = layers - dense
                    val window = min(context, info.slidingWindow?.toLong() ?: context)
                    dense * context + swa * window
                }
                // No verified pattern: bound memory as if every layer were full attention.
                KvLayout.UNKNOWN -> layers.toLong() * context
            }
            Math.multiplyExact(perLayerToken, Math.multiplyExact(layerTokens, N_SEQ_MAX.toLong()))
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun estimateMemoryBytes(model: CatalogModel, info: GgufInfo?, contextLength: Int): Long? =
        memoryBreakdown(model, info, contextLength, DeviceProfile.estimateFallback()).minimumSystemRamBytes

    fun memoryBreakdown(
        model: CatalogModel,
        info: GgufInfo?,
        contextLength: Int,
        device: DeviceProfile,
    ): MemoryBreakdown {
        val context = contextLength.coerceAtLeast(MIN_CONTEXT)
        val notes = ArrayList<String>()
        val weights = if (info?.hasTensorWeights == true) {
            info.tensorBytes
        } else {
            notes += "No tensor metadata; published file size is used as an upper bound."
            model.fileSizeBytes.takeIf { it > 0 }
        }
        val weightsSource = if (info?.hasTensorWeights == true) {
            "GGUF tensor descriptors (${info.tensorTypes.size} tensor types)"
        } else {
            "published GGUF file size (upper bound, reflects quantization)"
        }

        val kv = info?.let { kvCacheBytes(it, context) }
        if (kv == null && info != null) {
            notes += "KV cache dimensions are incomplete; RAM estimates are UNKNOWN."
        }
        if (info == null) {
            notes += "No GGUF metadata is available; RAM estimates are UNKNOWN."
        }
        notes.addAll(info?.notes.orEmpty())

        val runtime = runtimeBytes(device)
        val buffers = computeBufferBytes(info, context, device)
        val subtotal = safeSum(listOf(weights, kv, runtime, buffers))
        val margin = subtotal?.let { it * SAFETY_MARGIN_NUM / SAFETY_MARGIN_DEN }
        val inferencePeak = subtotal?.let { safeSum(listOf(it, margin)) }
        val systemOverhead = if (inferencePeak != null) SYSTEM_OVERHEAD_BYTES else null
        val minimumSystemRam = if (inferencePeak != null && systemOverhead != null) {
            safeSum(listOf(inferencePeak, systemOverhead))
        } else null
        val recommendedSystemRam = minimumSystemRam?.let {
            it * RECOMMENDED_HEADROOM_NUM / RECOMMENDED_HEADROOM_DEN
        }

        if (minimumSystemRam != null) {
            notes += "System RAM figures are conservative estimates (OS/app overhead and headroom included)."
        }

        val source = if (info?.hasTensorWeights == true) {
            "GGUF tensor descriptors + model-specific KV/context + configured runtime + conservative system overhead (estimate)"
        } else {
            "published file size (upper bound) + model-specific KV/context + configured runtime + conservative system overhead (estimate)"
        }
        val detail = if (kv != null && minimumSystemRam != null) {
            "F16 KV cache at $context context; no GPU layers; ${device.primaryAbi} CPU backend. " +
                "Includes inference margin ($SAFETY_MARGIN_NUM%), OS/app overhead estimate, and recommended headroom."
        } else {
            info?.reason ?: "UNKNOWN: insufficient metadata for a reliable RAM estimate."
        }

        return MemoryBreakdown(
            weightsBytes = weights,
            weightsSource = weightsSource,
            kvCacheBytes = kv,
            kvDtype = "F16",
            kvComplete = kv != null && info?.kvLayout != KvLayout.UNKNOWN,
            runtimeBytes = runtime,
            inferenceBuffersBytes = buffers,
            marginBytes = margin,
            systemOverheadBytes = systemOverhead,
            inferencePeakBytes = inferencePeak,
            minimumSystemRamBytes = minimumSystemRam,
            recommendedSystemRamBytes = recommendedSystemRam,
            totalBytes = inferencePeak,
            sourceLabel = source,
            detail = detail,
            notes = notes,
        )
    }

    fun assess(
        model: CatalogModel,
        device: DeviceProfile,
        info: GgufInfo? = null,
        contextLength: Int = defaultContext(info, model.recommendedContextLength),
        installed: Boolean = false,
    ): CompatibilityAssessment {
        val context = contextLength.coerceAtLeast(MIN_CONTEXT)
        val budget = safeBudgetBytes(device)
        val memory = memoryBreakdown(model, info, context, device)

        val arm64 = "arm64-v8a" in device.supportedAbis || device.primaryAbi == "arm64-v8a"
        val architecture = when {
            !arm64 -> CheckStatus.FAIL to "This APK is arm64-v8a only."
            info?.architecture.isNullOrBlank() &&
                model.architecture.equals("unknown", ignoreCase = true) ->
                CheckStatus.UNKNOWN to "Architecture UNKNOWN; llama.cpp support cannot be verified."
            modelArchitecture(model, info).lowercase(Locale.ROOT) !in KNOWN_ARCHITECTURES ->
                CheckStatus.UNKNOWN to
                    "${modelArchitecture(model, info)} is not in the bundled llama.cpp mapping."
            else -> CheckStatus.OK to
                "${modelArchitecture(model, info)} is mapped by the bundled llama.cpp on arm64-v8a."
        }

        val workingBytes = if (installed) 16L * MIB else 128L * MIB
        val requiredStorage = safeSum(listOf(model.fileSizeBytes, workingBytes)) ?: model.fileSizeBytes
        val freeStorage = device.availableModelStorageBytes
        val storage = when {
            freeStorage <= 0 -> CheckStatus.UNKNOWN to "Available model storage is UNKNOWN."
            freeStorage < requiredStorage -> CheckStatus.FAIL to
                "Needs ${formatSize(requiredStorage)} free model storage; ${formatSize(freeStorage)} is free."
            else -> CheckStatus.OK to
                "${formatSize(freeStorage)} free model storage; needs about ${formatSize(requiredStorage)}."
        }

        val pressure = if (device.lowMemory) {
            "device reports low-memory pressure"
        } else {
            "${formatSize(device.availableMemoryBytes)} currently available"
        }

        val inferencePeak = memory.inferencePeakBytes
        val minimumRam = memory.minimumSystemRamBytes
        val recommendedRam = memory.recommendedSystemRamBytes
        val memoryComplete = inferencePeak != null && minimumRam != null && memory.kvComplete && info != null
        val totalRam = device.totalMemoryBytes.takeIf { it > 0 }
        val fitsSafeBudget = inferencePeak != null && inferencePeak <= budget
        val fitsMinimumTotal = minimumRam != null && (totalRam == null || minimumRam <= totalRam)
        val fitsRecommendedTotal = recommendedRam != null && (totalRam == null || recommendedRam <= totalRam)
        val comfortableBudget = inferencePeak != null && inferencePeak <= budget * 80 / 100

        val category = when {
            architecture.first == CheckStatus.FAIL -> CompatibilityCategory.NOT_RECOMMENDED
            storage.first == CheckStatus.FAIL -> CompatibilityCategory.NOT_RECOMMENDED
            device.lowMemory && inferencePeak == null -> CompatibilityCategory.UNKNOWN
            !memoryComplete -> CompatibilityCategory.UNKNOWN
            !fitsSafeBudget || !fitsMinimumTotal -> CompatibilityCategory.NOT_RECOMMENDED
            architecture.first == CheckStatus.UNKNOWN -> CompatibilityCategory.UNKNOWN
            !fitsRecommendedTotal || !comfortableBudget -> CompatibilityCategory.SUPPORTED
            else -> CompatibilityCategory.RECOMMENDED
        }

        val memoryReason = if (minimumRam == null || recommendedRam == null || inferencePeak == null) {
            "Estimated RAM requirements UNKNOWN: ${memory.detail}"
        } else {
            "Minimum system RAM (estimate): ${formatSize(minimumRam)}. " +
                "Recommended system RAM (estimate): ${formatSize(recommendedRam)}. " +
                "Estimated inference peak: ${formatSize(inferencePeak)}. " +
                "Current safe budget: ${formatSize(budget)}; $pressure."
        }

        val reason = buildString {
            when (category) {
                CompatibilityCategory.RECOMMENDED -> append("Recommended. ")
                CompatibilityCategory.SUPPORTED -> append("Supported with limited headroom. ")
                CompatibilityCategory.NOT_RECOMMENDED -> append("Not recommended. ")
                CompatibilityCategory.UNKNOWN -> append("UNKNOWN compatibility. ")
            }
            append(memoryReason)
            if (totalRam != null && minimumRam != null && minimumRam > totalRam) {
                append(" Device total RAM is below the estimated minimum.")
            } else if (totalRam != null && recommendedRam != null && recommendedRam > totalRam) {
                append(" Device total RAM is below the recommended estimate.")
            }
            if (architecture.first != CheckStatus.OK) {
                append(' ').append(architecture.second)
            }
            if (storage.first != CheckStatus.OK) {
                append(' ').append(storage.second)
            }
        }

        return CompatibilityAssessment(
            category = category,
            estimatedMemoryBytes = minimumRam,
            minimumSystemRamBytes = minimumRam,
            recommendedSystemRamBytes = recommendedRam,
            safeBudgetBytes = budget,
            contextLength = context,
            reason = reason,
            memory = memory,
            architectureStatus = architecture.first,
            architectureReason = architecture.second,
            storageStatus = storage.first,
            storageReason = storage.second,
            performanceStatus = CheckStatus.UNKNOWN,
            performanceReason = "Performance not yet measured on this device.",
        )
    }

    /**
     * Conservative safe budget for the inference working set right now: never larger
     * than a fraction of total RAM or of memory currently available. Android can
     * reclaim cached pages at any time, so available RAM is not treated as permanently
     * usable. This is a runtime budget, not a model's minimum/recommended system RAM.
     */
    fun safeBudgetBytes(device: DeviceProfile): Long {
        val total = device.totalMemoryBytes.coerceAtLeast(1L)
        val available = device.availableMemoryBytes.coerceAtLeast(0L)
        if (total == 1L && available == 0L) return 0L
        val totalCap = total * 45 / 100
        val availableCap = available * 80 / 100
        var budget = min(totalCap, availableCap)
        if (device.lowMemory) budget = min(budget, available * 50 / 100)
        return budget.coerceAtLeast(0L)
    }

    private fun modelArchitecture(model: CatalogModel, info: GgufInfo?): String =
        info?.architecture?.takeIf { it.isNotBlank() } ?: model.architecture

    private fun runtimeBytes(device: DeviceProfile): Long? {
        val heapReserve = min(device.appMaxMemoryBytes.coerceAtLeast(0L), 128L * MIB)
            .coerceAtLeast(96L * MIB)
        val nativeReserve = safeMultiply(device.nativeLibraryBytes.coerceAtLeast(0L), 2L)
        val baseRuntime = 128L * MIB
        return safeSum(listOf(baseRuntime, heapReserve, nativeReserve))
    }

    /** Compute/inference buffers scale with configured batch sizes and model dimensions (conservative estimate). */
    private fun computeBufferBytes(info: GgufInfo?, context: Int, device: DeviceProfile): Long? {
        if (info == null) return null
        val embedding = info.embeddingLength?.toLong()
        val feedForward = info.feedForwardLength?.toLong()
        val headCount = info.headCount?.toLong()
        val headDim = if (embedding != null && headCount != null && headCount > 0 &&
            embedding % headCount == 0L
        ) embedding / headCount else null
        if (embedding == null || headDim == null) return null

        val activation = safeMultiply(N_UBATCH.toLong(), (embedding + (feedForward ?: embedding * 4)) * 8)
        val attention = safeMultiply(
            min(context.toLong(), N_BATCH.toLong()),
            (headCount ?: 1) * headDim * 4,
        )
        val graph = safeMultiply((info.tensorCount ?: 0).toLong(), 512L)
        val threads = device.cpuCount.coerceIn(2, 8).toLong()
        val threadStacks = safeMultiply(threads, 2L * MIB)
        val batchFactor = max(1L, N_BATCH / N_UBATCH.toLong())
        val estimated = safeSum(listOf(activation, attention, graph, threadStacks))?.let {
            safeMultiply(it, batchFactor)
        }
        // Ensure a conservative baseline for llama.cpp/context/batching overhead when tensor counts are small/unknown.
        return max(estimated ?: 0L, 128L * MIB)
    }

    private fun safeSum(values: List<Long?>): Long? = try {
        var sum = 0L
        values.forEach { value -> if (value == null) return null else sum = Math.addExact(sum, value) }
        sum
    } catch (_: ArithmeticException) {
        null
    }

    private fun safeMultiply(a: Long, b: Long): Long? = try {
        Math.multiplyExact(a, b)
    } catch (_: ArithmeticException) {
        null
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= (1L shl 30) -> String.format(Locale.ROOT, "%.2f GiB", bytes / (1L shl 30).toDouble())
        bytes >= (1L shl 20) -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1L shl 20).toDouble())
        bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
