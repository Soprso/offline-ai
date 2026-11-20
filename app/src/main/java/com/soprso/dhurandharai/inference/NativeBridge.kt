package com.soprso.dhurandharai.inference

/**
 * JNI bridge to libofflineai.so (llama.cpp b12558).
 *
 * Contract:
 *  - Only one model may be loaded at a time.
 *  - generate() runs on the calling thread and streams tokens through
 *    [TokenListener]; call [nativeCancel] from any other thread to stop.
 *  - Errors are returned as null + [nativeLastError], never fabricated.
 */
object NativeBridge {

    init {
        System.loadLibrary("offlineai")
    }

    /** Native build identity, shown in Settings. */
    external fun nativeVersion(): String

    /** Milestone D smoke test across the JNI boundary. */
    external fun nativeAdd(a: Int, b: Int): Int

    /** Loads a GGUF file. Returns "" on success, otherwise an error message. */
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): String

    /** Unloads the current model and frees all native resources. */
    external fun nativeUnload()

    external fun nativeIsLoaded(): Boolean

    /** Configured context length, 0 when no model is loaded. */
    external fun nativeContextLength(): Int

    /** Requests cancellation of an in-flight generation. Thread-safe. */
    external fun nativeCancel()

    /** Last error message from load/generate, or "" when none. */
    external fun nativeLastError(): String

    /** Changes inference thread counts on a loaded model. */
    external fun nativeSetThreads(nThreads: Int, nBatchThreads: Int): String

    /**
     * Generates a reply for [messages] (flat [role, content, ...] pairs).
     * Returns the full text, partial text if cancelled, or null on error.
     */
    external fun nativeGenerate(
        messages: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        seed: Long,
        listener: TokenListener,
        metricsListener: MetricsListener,
    ): String?

    /** True when the native library loaded and answered correctly. */
    fun selfTest(): Boolean =
        runCatching { nativeAdd(40, 2) == 42 }.getOrDefault(false)
}
