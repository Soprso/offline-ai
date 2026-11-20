package com.soprso.dhurandharai.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceMetricsTest {
    private fun values(): LongArray = LongArray(17).apply {
        this[MetricsListener.LOAD_TIME_US] = 2_000_000L
        this[MetricsListener.PROMPT_CHARS] = 120L
        this[MetricsListener.PROMPT_TOKENS] = 100L
        this[MetricsListener.PROMPT_PROCESSED_TOKENS] = 20L
        this[MetricsListener.PROMPT_REUSED_TOKENS] = 80L
        this[MetricsListener.CONTEXT_SIZE] = 4096L
        this[MetricsListener.FORMAT_US] = 1_000L
        this[MetricsListener.TOKENIZE_US] = 2_000L
        this[MetricsListener.PROMPT_PROCESS_US] = 100_000L
        this[MetricsListener.FIRST_TOKEN_US] = 150_000L
        this[MetricsListener.GENERATION_US] = 500_000L
        this[MetricsListener.GENERATED_TOKENS] = 50L
        this[MetricsListener.TOTAL_US] = 650_000L
        this[MetricsListener.NATIVE_CODE] = 0L
        this[MetricsListener.CANCELLED] = 0L
        this[MetricsListener.THREADS] = 4L
        this[MetricsListener.BATCH_THREADS] = 4L
    }

    @Test
    fun mapsAllMeasuredNativeValues() {
        val metrics = InferenceMetrics.from(values())

        assertNotNull(metrics)
        assertEquals(120, metrics!!.promptChars)
        assertEquals(100, metrics.promptTokens)
        assertEquals(20, metrics.promptProcessedTokens)
        assertEquals(80, metrics.promptReusedTokens)
        assertEquals(4096, metrics.contextSize)
        assertEquals(150_000L, metrics.firstTokenUs)
        assertEquals(650_000L, metrics.totalUs)
        assertEquals(4, metrics.threads)
        assertFalse(metrics.cancelled)
    }

    @Test
    fun calculatesSpeedsFromMeasuredDurations() {
        val metrics = InferenceMetrics.from(values())!!

        assertEquals(200.0, metrics.promptTps, 0.001)
        assertEquals(100.0, metrics.generationTps, 0.001)
        assertTrue(metrics.compactSummary().contains("reused 80"))
        assertTrue(metrics.compactSummary().contains("ttft 150.0ms"))
    }

    @Test
    fun rejectsIncompleteMetricsPayload() {
        assertEquals(null, InferenceMetrics.from(LongArray(3)))
    }
}
