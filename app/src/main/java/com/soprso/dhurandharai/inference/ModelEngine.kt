package com.soprso.dhurandharai.inference

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** UI-facing model lifecycle state. */
sealed interface EngineState {
    data object Unloaded : EngineState
    data class Loading(val path: String) : EngineState
    data class Ready(val modelName: String, val contextLength: Int) : EngineState
    data class Generating(val modelName: String) : EngineState
    data class Error(val message: String) : EngineState
}

/** Measured timing for one native inference request. No values are estimated. */
data class InferenceMetrics(
    val loadTimeUs: Long,
    val promptChars: Int,
    val promptTokens: Int,
    val promptProcessedTokens: Int,
    val promptReusedTokens: Int,
    val contextSize: Int,
    val formatUs: Long,
    val tokenizeUs: Long,
    val promptProcessUs: Long,
    val firstTokenUs: Long,
    val generationUs: Long,
    val generatedTokens: Int,
    val totalUs: Long,
    val nativeCode: Int,
    val cancelled: Boolean,
    val threads: Int,
    val batchThreads: Int,
) {
    val promptTps: Double
        get() = if (promptProcessUs > 0) 1_000_000.0 * promptProcessedTokens / promptProcessUs else 0.0

    val generationTps: Double
        get() = if (generationUs > 0) 1_000_000.0 * generatedTokens / generationUs else 0.0

    fun compactSummary(): String = buildString {
        append("pp ")
        append(promptProcessedTokens)
        append('/')
        append(promptTokens)
        append("t/")
        append(fmtMs(promptProcessUs))
        append("ms (")
        append(fmtTps(promptTps))
        append("t/s reused ")
        append(promptReusedTokens)
        append(") tg ")
        append(generatedTokens)
        append("t/")
        append(fmtMs(generationUs))
        append("ms (")
        append(fmtTps(generationTps))
        append("t/s) ttft ")
        append(fmtMs(firstTokenUs))
        append("ms total ")
        append(fmtMs(totalUs))
        append("ms")
    }

    fun detailedLogLine(modelName: String): String = buildString {
        append("model=").append(modelName)
        append(" prompt_chars=").append(promptChars)
        append(" prompt_tokens=").append(promptTokens)
        append(" processed_tokens=").append(promptProcessedTokens)
        append(" reused_tokens=").append(promptReusedTokens)
        append(" n_ctx=").append(contextSize)
        append(" threads=").append(threads)
        append(" batch_threads=").append(batchThreads)
        append(" format_ms=").append(fmtMs(formatUs))
        append(" tokenize_ms=").append(fmtMs(tokenizeUs))
        append(" prompt_ms=").append(fmtMs(promptProcessUs))
        append(" prompt_tps=").append(fmtTps(promptTps))
        append(" first_token_ms=").append(fmtMs(firstTokenUs))
        append(" gen_tokens=").append(generatedTokens)
        append(" gen_ms=").append(fmtMs(generationUs))
        append(" gen_tps=").append(fmtTps(generationTps))
        append(" total_ms=").append(fmtMs(totalUs))
        append(" native_code=").append(nativeCode)
        append(" cancelled=").append(cancelled)
    }

    companion object {
        private fun fmtMs(us: Long): String = String.format(java.util.Locale.US, "%.1f", us / 1000.0)
        private fun fmtTps(tps: Double): String = String.format(java.util.Locale.US, "%.2f", tps)

        fun from(values: LongArray): InferenceMetrics? {
            if (values.size < 17) return null
            return InferenceMetrics(
                loadTimeUs = values[MetricsListener.LOAD_TIME_US],
                promptChars = values[MetricsListener.PROMPT_CHARS].toInt(),
                promptTokens = values[MetricsListener.PROMPT_TOKENS].toInt(),
                promptProcessedTokens = values[MetricsListener.PROMPT_PROCESSED_TOKENS].toInt(),
                promptReusedTokens = values[MetricsListener.PROMPT_REUSED_TOKENS].toInt(),
                contextSize = values[MetricsListener.CONTEXT_SIZE].toInt(),
                formatUs = values[MetricsListener.FORMAT_US],
                tokenizeUs = values[MetricsListener.TOKENIZE_US],
                promptProcessUs = values[MetricsListener.PROMPT_PROCESS_US],
                firstTokenUs = values[MetricsListener.FIRST_TOKEN_US],
                generationUs = values[MetricsListener.GENERATION_US],
                generatedTokens = values[MetricsListener.GENERATED_TOKENS].toInt(),
                totalUs = values[MetricsListener.TOTAL_US],
                nativeCode = values[MetricsListener.NATIVE_CODE].toInt(),
                cancelled = values[MetricsListener.CANCELLED] != 0L,
                threads = values[MetricsListener.THREADS].toInt(),
                batchThreads = values[MetricsListener.BATCH_THREADS].toInt(),
            )
        }
    }
}

/**
 * Thin, synchronous wrapper over [NativeBridge]. All blocking calls are made
 * by the caller on a background dispatcher; this class only owns state.
 * One model at a time, as required.
 */
object ModelEngine {

    private const val LOG_TAG = "OfflineAI-Inference"

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _loadedPath = MutableStateFlow<String?>(null)
    val loadedPathFlow: StateFlow<String?> = _loadedPath.asStateFlow()

    private val _lastMetrics = MutableStateFlow<InferenceMetrics?>(null)
    val lastMetrics: StateFlow<InferenceMetrics?> = _lastMetrics.asStateFlow()

    @Volatile
    var loadedPath: String? = null
        private set

    val isLoaded: Boolean get() = loadedPath != null

    /**
     * Loads [path]. Returns null on success or an error message.
     * Unloads any previously loaded model first (one model at a time).
     */
    suspend fun load(path: String, displayName: String, nCtx: Int = 4096): String? =
        withContext(Dispatchers.Default) {
            _state.value = EngineState.Loading(path)
            unloadInternal()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val err = NativeBridge.nativeLoad(path, nCtx, threads)
            if (err.isNotEmpty()) {
                _state.value = EngineState.Error(err)
                return@withContext err
            }
            loadedPath = path
            _loadedPath.value = path
            _state.value = EngineState.Ready(displayName, NativeBridge.nativeContextLength())
            null
        }

    /** Frees the model. Safe to call when nothing is loaded. */
    suspend fun unload() = withContext(Dispatchers.Default) {
        NativeBridge.nativeCancel()
        unloadInternal()
        _state.value = EngineState.Unloaded
    }

    private fun unloadInternal() {
        if (loadedPath != null || NativeBridge.nativeIsLoaded()) {
            NativeBridge.nativeUnload()
        }
        loadedPath = null
        _loadedPath.value = null
    }

    /**
     * Streams a completion for [messages] (flat role/content pairs).
     * Returns the generated text (possibly partial if cancelled), or null
     * with [NativeBridge.nativeLastError] describing the failure.
     */
    suspend fun generate(
        modelName: String,
        messages: Array<String>,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.9f,
        minP: Float = 0.05f,
        seed: Long = System.currentTimeMillis(),
        onToken: (String) -> Unit,
        onMetrics: ((InferenceMetrics) -> Unit)? = null,
    ): String? = withContext(Dispatchers.Default) {
        if (!isLoaded) {
            return@withContext null
        }
        _state.value = EngineState.Generating(modelName)
        _lastMetrics.value = null
        try {
            val sb = StringBuilder()
            val result = NativeBridge.nativeGenerate(
                messages, maxTokens, temperature, topK, topP, minP, seed,
                { bytes ->
                    // Already on the generation thread; decode complete UTF-8 only.
                    val piece = String(bytes, Charsets.UTF_8)
                    sb.append(piece)
                    onToken(piece)
                },
                MetricsListener { values ->
                    InferenceMetrics.from(values)?.let { metrics ->
                        _lastMetrics.value = metrics
                        Log.i(LOG_TAG, metrics.detailedLogLine(modelName))
                        onMetrics?.invoke(metrics)
                    }
                },
            )
            if (result == null) {
                null
            } else {
                // Prefer the streamed accumulation: it is what the user saw.
                if (sb.isNotEmpty()) sb.toString() else result
            }
        } finally {
            _state.value = loadedPath
                ?.let { EngineState.Ready(modelName, NativeBridge.nativeContextLength()) }
                ?: EngineState.Unloaded
        }
    }

    /** Requests cancellation; thread-safe and lock-free. */
    fun cancel() {
        NativeBridge.nativeCancel()
    }

    /** Changes thread counts on the loaded model. Returns null on success. */
    fun setThreads(nThreads: Int, nBatchThreads: Int = nThreads): String? {
        val error = runCatching { NativeBridge.nativeSetThreads(nThreads, nBatchThreads) }
            .getOrElse { it.message ?: "failed to set threads" }
        return error.ifEmpty { null }
    }

    /** Error text from the last failed load/generate, or "". */
    fun lastError(): String =
        runCatching { NativeBridge.nativeLastError() }.getOrDefault("")

    /** Native build string for Settings. */
    fun version(): String =
        runCatching { NativeBridge.nativeVersion() }.getOrDefault("native unavailable")
}
