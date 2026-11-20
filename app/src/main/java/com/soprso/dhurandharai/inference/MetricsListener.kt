package com.soprso.dhurandharai.inference

/**
 * End-of-request timing data from the native engine.
 *
 * Values are microseconds unless the companion object names a count.
 */
fun interface MetricsListener {
    fun onMetrics(values: LongArray)

    companion object {
        const val LOAD_TIME_US = 0
        const val PROMPT_CHARS = 1
        const val PROMPT_TOKENS = 2
        const val CONTEXT_SIZE = 3
        const val FORMAT_US = 4
        const val TOKENIZE_US = 5
        const val PROMPT_PROCESS_US = 6
        const val FIRST_TOKEN_US = 7
        const val GENERATION_US = 8
        const val GENERATED_TOKENS = 9
        const val TOTAL_US = 10
        const val NATIVE_CODE = 11
        const val CANCELLED = 12
        const val THREADS = 13
        const val BATCH_THREADS = 14
        const val PROMPT_PROCESSED_TOKENS = 15
        const val PROMPT_REUSED_TOKENS = 16
    }
}
