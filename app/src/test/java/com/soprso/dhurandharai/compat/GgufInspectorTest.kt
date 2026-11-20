package com.soprso.dhurandharai.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class GgufInspectorTest {

    private data class TensorSpec(
        val name: String,
        val dimensions: LongArray,
        val ggmlType: Int,
        val offset: Long,
    )

    @Test
    fun readsDimensionsAndTensorWeightBytesInsteadOfFileSize() {
        val file = syntheticGguf(
            tensors = listOf(
                TensorSpec("blk.0.attn_q.weight", longArrayOf(2, 4), GGML_Q4_K, 0L),
            ),
        )

        val info = GgufInspector.inspect(file)

        assertEquals(3, info.version)
        assertEquals("qwen2", info.architecture)
        assertEquals("Q4_K_M", info.quantization)
        assertEquals(1024, info.trainContextLength)
        assertEquals(2, info.layerCount)
        assertEquals(128, info.embeddingLength)
        assertEquals(4, info.headCount)
        assertEquals(2, info.keyValueHeadCount)
        assertEquals(1, info.tensorCount)
        // Q4_K_M stores 256 elements in 144 bytes. 8 elements use one block.
        assertEquals(144L, info.tensorBytes)
        assertEquals(setOf(GGML_Q4_K), info.tensorTypes)
        assertTrue(info.hasTensorWeights)
        // 2 layers x 2 KV heads x 32 derived head dim x (K + V) x F16.
        assertEquals(512L, info.kvBytesPerToken)
        assertTrue(info.notes.any { it.contains("derived") })
        assertNull(info.reason)
    }

    @Test
    fun mixedTensorQuantizationsAreReportedInsteadOfTrustingTheFileLabel() {
        val file = syntheticGguf(
            tensors = listOf(
                TensorSpec("a", longArrayOf(32), GGML_Q8_0, 0L),
                TensorSpec("b", longArrayOf(32), GGML_Q4_K, 34L),
            ),
        )

        val info = GgufInspector.inspect(file)

        assertEquals("Q4_K_M", info.quantization) // general.file_type
        assertEquals(setOf(GGML_Q8_0, GGML_Q4_K), info.tensorTypes)
        // Q8_0: 32 elements in 34 bytes; Q4_K_M: 256 elements in 144 bytes.
        assertEquals(178L, info.tensorBytes)
        assertEquals(2, info.tensorCount)
    }

    @Test
    fun gemmaThreeUsesKnownSlidingWindowLayoutAndSmallerCache() {
        val file = syntheticGguf(
            architecture = "gemma3",
            layers = 6,
            embedding = 256,
            headCount = 4,
            kvHeads = 1,
            keyLength = 256,
            valueLength = 256,
            slidingWindow = 512,
            context = 32768,
        )

        val info = GgufInspector.inspect(file)

        assertEquals(KvLayout.SLIDING_WINDOW_6, info.kvLayout)
        assertEquals(6L * 512L * 2L, info.kvBytesPerToken)
        assertEquals(1_024L, info.kvBytesPerToken!! / info.layerCount!!)

        val cached = ModelCompatibilityChecker.kvCacheBytes(info, 4096)
        // One dense layer at full 4096 positions, five SWA layers at 512.
        assertEquals(1_024L * (4_096L + 5L * 512L), cached)
        assertTrue(cached!! < info.kvBytesPerToken!! * 4096L)
    }

    @Test
    fun missingKvMetadataIsUnknownInsteadOfGuessed() {
        val file = syntheticGguf(architecture = "madeup", includeKvHeads = false)

        val info = GgufInspector.inspect(file)

        assertEquals("madeup", info.architecture)
        assertNull(info.kvBytesPerToken)
        assertFalse(info.hasKvEstimate)
        assertTrue(info.reason!!.contains("UNKNOWN KV cache"))
    }

    @Test
    fun malformedFileReturnsUnknownInsteadOfThrowing() {
        val file = Files.createTempFile("bad", ".gguf").toFile()
        file.writeBytes("not gguf".toByteArray(StandardCharsets.US_ASCII))

        val info = GgufInspector.inspect(file)

        assertNull(info.architecture)
        assertNull(info.kvBytesPerToken)
        assertNull(info.tensorBytes)
        assertNotNull(info.reason)
    }

    private fun syntheticGguf(
        architecture: String = "qwen2",
        layers: Int = 2,
        embedding: Int = 128,
        headCount: Int = 4,
        kvHeads: Int = 2,
        includeKvHeads: Boolean = true,
        keyLength: Int? = null,
        valueLength: Int? = null,
        slidingWindow: Int? = null,
        context: Int = 1024,
        fileType: Int = LLAMA_FTYPE_Q4_K_M,
        includeTokens: Boolean = true,
        tensors: List<TensorSpec> = emptyList(),
    ): File {
        val entries = ArrayList<Pair<String, (LittleEndianWriter) -> Unit>>()
        entries += "general.architecture" to { it.stringValue(architecture) }
        entries += "general.file_type" to { it.u32Value(fileType) }
        entries += "$architecture.block_count" to { it.u32Value(layers) }
        entries += "$architecture.context_length" to { it.u32Value(context) }
        entries += "$architecture.embedding_length" to { it.u32Value(embedding) }
        entries += "$architecture.attention.head_count" to { it.u32Value(headCount) }
        if (includeKvHeads) {
            entries += "$architecture.attention.head_count_kv" to { it.u32Value(kvHeads) }
        }
        if (keyLength != null) {
            entries += "$architecture.attention.key_length" to { it.u32Value(keyLength) }
        }
        if (valueLength != null) {
            entries += "$architecture.attention.value_length" to { it.u32Value(valueLength) }
        }
        if (slidingWindow != null) {
            entries += "$architecture.attention.sliding_window" to { it.u32Value(slidingWindow) }
        }
        if (includeTokens) {
            entries += "tokenizer.ggml.tokens" to { w ->
                w.u32(TYPE_ARRAY)
                w.u32(TYPE_STRING)
                w.u64(2L)
                w.string("alpha")
                w.string("beta")
            }
        }
        entries += "$architecture.rope.freq_base" to {
            it.u32(TYPE_FLOAT32)
            it.f32(10_000f)
        }

        val w = LittleEndianWriter()
        w.bytes("GGUF".toByteArray(StandardCharsets.US_ASCII))
        w.u32(3)
        w.u64(tensors.size.toLong())
        w.u64(entries.size.toLong())
        entries.forEach { (key, writeValue) ->
            w.string(key)
            writeValue(w)
        }
        tensors.forEach { spec ->
            w.string(spec.name)
            w.u32(spec.dimensions.size)
            spec.dimensions.forEach { w.u64(it) }
            w.u32(spec.ggmlType)
            w.u64(spec.offset)
        }

        val file = Files.createTempFile("test", ".gguf").toFile()
        file.deleteOnExit()
        file.writeBytes(w.toByteArray())
        return file
    }

    private fun LittleEndianWriter.stringValue(value: String) {
        u32(TYPE_STRING)
        string(value)
    }

    private fun LittleEndianWriter.u32Value(value: Int) {
        u32(TYPE_UINT32)
        u32(value)
    }

    private class LittleEndianWriter {
        private val out = ByteArrayOutputStream()

        fun bytes(bytes: ByteArray) {
            out.write(bytes, 0, bytes.size)
        }

        fun u32(value: Int) {
            bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }

        fun u64(value: Long) {
            bytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
        }

        fun f32(value: Float) {
            bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        }

        fun string(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            u64(bytes.size.toLong())
            bytes(bytes)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private companion object {
        const val TYPE_UINT32 = 4
        const val TYPE_FLOAT32 = 6
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9

        const val GGML_Q8_0 = 8
        const val GGML_Q4_K = 12
        const val LLAMA_FTYPE_Q4_K_M = 15
    }
}
