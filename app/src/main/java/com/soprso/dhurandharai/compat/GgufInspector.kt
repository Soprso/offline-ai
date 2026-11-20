package com.soprso.dhurandharai.compat

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** How the K/V cache is laid out across layers. */
enum class KvLayout {
    STANDARD,
    SLIDING_WINDOW_2,
    SLIDING_WINDOW_6,
    UNKNOWN,
}

/**
 * Facts read from a GGUF header. No weights are decoded.
 * Tensor descriptors are used to size weights from actual tensor types and
 * dimensions instead of treating the download size as the weight size.
 */
data class GgufInfo(
    val version: Int,
    val architecture: String?,
    val quantization: String?,
    val fileType: Int?,
    val parameterCount: Long?,
    val trainContextLength: Int?,
    val layerCount: Int?,
    val embeddingLength: Int?,
    val headCount: Int?,
    val keyValueHeadCount: Int?,
    val keyLength: Int?,
    val valueLength: Int?,
    val feedForwardLength: Int?,
    val vocabSize: Int?,
    val slidingWindow: Int?,
    val tensorCount: Int?,
    val tensorBytes: Long?,
    val tensorElementCount: Long?,
    val tensorTypes: Set<Int>,
    val kvBytesPerToken: Long?,
    val kvLayout: KvLayout,
    val metadataSource: String,
    val notes: List<String> = emptyList(),
    val reason: String? = null,
) {
    val hasKvEstimate: Boolean get() = kvBytesPerToken != null && kvBytesPerToken > 0

    /** True when weights come from tensor descriptors, not the file size. */
    val hasTensorWeights: Boolean get() = tensorBytes != null && tensorBytes > 0
}

/**
 * Reads GGUF metadata and tensor descriptors only. Tokenizer arrays are
 * skipped by seeking, so a multi-gigabyte model needs no large allocation.
 */
object GgufInspector {

    private const val MAGIC = "GGUF"
    private const val MAX_KEY_BYTES = 4096
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_METADATA_ENTRIES = 4096
    private const val MAX_TENSORS = 8192

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    // ggml_type -> (elements per block, bytes per block), from ggml-common.h in
    // pinned llama.cpp b12558. This is the tensor type table, not the GGUF
    // metadata type table and not the llama_ftype file label.
    private val TENSOR_TYPE_SIZES = mapOf(
        0 to (1 to 4), 1 to (1 to 2), 2 to (32 to 18), 3 to (32 to 20),
        6 to (32 to 22), 7 to (32 to 24), 8 to (32 to 34), 9 to (32 to 36),
        10 to (256 to 84), 11 to (256 to 110), 12 to (256 to 144),
        13 to (256 to 176), 14 to (256 to 210), 15 to (256 to 292),
        16 to (256 to 66), 17 to (256 to 74), 18 to (256 to 98),
        19 to (256 to 50), 20 to (32 to 18), 21 to (256 to 110),
        22 to (256 to 82), 23 to (256 to 136), 24 to (1 to 1),
        25 to (1 to 2), 26 to (1 to 4), 27 to (1 to 8), 28 to (1 to 8),
        29 to (256 to 56), 30 to (1 to 2),
        34 to (256 to 54), 35 to (256 to 66), 39 to (32 to 17),
        40 to (64 to 36), 41 to (128 to 18), 42 to (64 to 18), 43 to (1 to 1),
    )

    private val KV_ELEMENT_BYTES = 2 // native context params use F16 for K and V

    fun inspect(file: File): GgufInfo = try {
        if (!file.exists()) {
            unknown("GGUF file is missing")
        } else {
            RandomAccessFile(file, "r").use { raf ->
                read(raf.channel, raf.length())
            }
        }
    } catch (t: Throwable) {
        unknown(t.message ?: "GGUF metadata could not be read")
    }

    private fun read(channel: FileChannel, fileLength: Long): GgufInfo {
        val magic = readBytes(channel, 4).toString(Charsets.US_ASCII)
        if (magic != MAGIC) return unknown("Not a GGUF file")
        val version = readU32(channel)
        val tensorCount = readU64(channel)
        val metadataCount = readU64(channel)
        if (metadataCount <= 0 || metadataCount > MAX_METADATA_ENTRIES) {
            return unknown("Unsupported GGUF metadata count")
        }

        val values = HashMap<String, Any>()
        repeat(metadataCount.toInt()) {
            val keyLength = readU64(channel)
            if (keyLength <= 0 || keyLength > MAX_KEY_BYTES) {
                throw EOFException("Invalid GGUF metadata key length")
            }
            val key = readBytes(channel, keyLength.toInt()).toString(Charsets.UTF_8)
            val type = readU32(channel)
            if (isRelevant(key)) {
                readScalar(channel, type)?.let { values[key] = it }
            } else {
                skipValue(channel, type)
            }
        }
        if (channel.position() > fileLength) throw EOFException("GGUF metadata is truncated")

        val tensorStats = if (tensorCount in 1..MAX_TENSORS) {
            readTensorDescriptors(channel, tensorCount.toInt())
        } else {
            TensorStats(null, null, null, emptySet())
        }

        return buildInfo(version, values, tensorStats)
    }

    private fun readTensorDescriptors(channel: FileChannel, count: Int): TensorStats {
        var bytes = 0L
        var elements = 0L
        val types = HashSet<Int>()
        repeat(count) {
            val nameLength = readU64(channel)
            if (nameLength <= 0 || nameLength > MAX_KEY_BYTES) {
                throw EOFException("Invalid GGUF tensor name length")
            }
            skip(channel, nameLength)
            val dimensions = readU32(channel)
            if (dimensions <= 0 || dimensions > 4) {
                throw EOFException("Unsupported tensor rank")
            }
            var count = 1L
            repeat(dimensions) { count = Math.multiplyExact(count, readU64(channel)) }
            val type = readU32(channel)
            skip(channel, 8) // tensor offset in file
            val block = TENSOR_TYPE_SIZES[type]
                ?: throw EOFException("Unsupported tensor type $type")
            val (blockElements, blockBytes) = block
            bytes = Math.addExact(
                bytes,
                Math.multiplyExact((count + blockElements - 1) / blockElements, blockBytes.toLong()),
            )
            elements = Math.addExact(elements, count)
            types += type
        }
        return TensorStats(count, bytes, elements, types)
    }

    private data class TensorStats(
        val count: Int?,
        val bytes: Long?,
        val elements: Long?,
        val types: Set<Int>,
    )

    private fun buildInfo(version: Int, values: Map<String, Any>, tensors: TensorStats): GgufInfo {
        val architecture = values["general.architecture"] as? String
        val fileType = (values["general.file_type"] as? Number)?.toInt()
        val quantization = quantName(fileType)
        val parameterCount = (values["general.parameter_count"] as? Number)?.toLong()

        fun archNumber(suffix: String): Number? = architecture?.let { values["$it.$suffix"] as? Number }

        val layers = archNumber("block_count")?.toInt()?.takeIf { it > 0 }
        val embedding = archNumber("embedding_length")?.toInt()?.takeIf { it > 0 }
        val heads = archNumber("attention.head_count")?.toInt()?.takeIf { it > 0 }
        val kvHeads = archNumber("attention.head_count_kv")?.toInt()?.takeIf { it > 0 }
        val keyLength = archNumber("attention.key_length")?.toInt()?.takeIf { it > 0 }
        val valueLength = archNumber("attention.value_length")?.toInt()?.takeIf { it > 0 }
        val context = archNumber("context_length")?.toInt()?.takeIf { it > 0 }
        val feedForward = archNumber("feed_forward_length")?.toInt()?.takeIf { it > 0 }
        val vocab = (values["${architecture}.vocab_size"] as? Number)?.toInt()
            ?: (values["tokenizer.ggml.tokens"] as? Number)?.toInt()
        val slidingWindow = archNumber("attention.sliding_window")?.toInt()?.takeIf { it > 0 }

        val notes = ArrayList<String>()
        val derivedHeadDim = if (embedding != null && heads != null && embedding % heads == 0) {
            embedding / heads
        } else {
            null
        }
        if (keyLength == null && derivedHeadDim != null) {
            notes += "Key/value head size derived from embedding_length / head_count."
        }
        if (kvHeads == null && heads != null) {
            notes += "head_count_kv is missing; KV heads assumed equal to attention heads."
        }

        val kvBytes = if (
            layers != null && kvHeads != null && derivedHeadDim != null
        ) {
            val kDim = keyLength ?: derivedHeadDim
            val vDim = valueLength ?: kDim
            Math.multiplyExact(
                layers.toLong(),
                Math.multiplyExact(kvHeads.toLong(), (kDim + vDim) * KV_ELEMENT_BYTES.toLong()),
            )
        } else {
            null
        }

        val layout = when {
            slidingWindow == null -> KvLayout.STANDARD
            architecture == "gemma3" -> KvLayout.SLIDING_WINDOW_6
            architecture == "gemma2" -> KvLayout.SLIDING_WINDOW_2
            else -> KvLayout.UNKNOWN
        }
        if (layout == KvLayout.UNKNOWN) {
            notes += "Sliding-window layout is not known for $architecture; KV uses a full-context upper bound."
        }
        if (tensors.bytes == null) {
            notes += "Tensor descriptors were not readable; weight memory falls back to file size."
        }

        val reason = when {
            architecture.isNullOrBlank() -> "UNKNOWN: GGUF has no general.architecture"
            kvBytes == null -> "UNKNOWN KV cache: GGUF is missing layer or attention dimensions"
            else -> null
        }

        return GgufInfo(
            version = version,
            architecture = architecture,
            quantization = quantization,
            fileType = fileType,
            parameterCount = parameterCount,
            trainContextLength = context,
            layerCount = layers,
            embeddingLength = embedding,
            headCount = heads,
            keyValueHeadCount = kvHeads,
            keyLength = keyLength,
            valueLength = valueLength,
            feedForwardLength = feedForward,
            vocabSize = vocab,
            slidingWindow = slidingWindow,
            tensorCount = tensors.count,
            tensorBytes = tensors.bytes,
            tensorElementCount = tensors.elements,
            tensorTypes = tensors.types,
            kvBytesPerToken = kvBytes,
            kvLayout = layout,
            metadataSource = "this GGUF file header",
            notes = notes,
            reason = reason,
        )
    }

    private fun isRelevant(key: String): Boolean =
        key == "general.architecture" ||
            key == "general.file_type" ||
            key == "general.parameter_count" ||
            key.endsWith(".block_count") ||
            key.endsWith(".context_length") ||
            key.endsWith(".embedding_length") ||
            key.endsWith(".feed_forward_length") ||
            key.endsWith(".attention.head_count") ||
            key.endsWith(".attention.head_count_kv") ||
            key.endsWith(".attention.key_length") ||
            key.endsWith(".attention.value_length") ||
            key.endsWith(".attention.sliding_window") ||
            key.endsWith(".vocab_size") ||
            key == "tokenizer.ggml.tokens"

    private fun readScalar(channel: FileChannel, type: Int): Any? = when (type) {
        TYPE_UINT8 -> readU8(channel).toLong()
        TYPE_UINT16 -> readU16(channel).toLong()
        TYPE_UINT32 -> readU32(channel).toLong() and 0xffff_ffffL
        TYPE_UINT64 -> readU64(channel)
        TYPE_BOOL -> readU8(channel) != 0
        TYPE_INT8 -> readU8(channel).toByte().toLong()
        TYPE_INT16 -> readU16(channel).toShort().toLong()
        TYPE_INT32 -> (readU32(channel).toLong() shl 32) shr 32
        TYPE_INT64 -> readU64(channel)
        TYPE_STRING -> {
            val value = readString(channel)
            value.takeIf { it.all { c -> c in '0'..'9' } && it.length <= 18 }?.toLongOrNull() ?: value
        }
        TYPE_FLOAT32 -> {
            skip(channel, 4)
            null
        }
        TYPE_FLOAT64 -> {
            skip(channel, 8)
            null
        }
        TYPE_ARRAY -> {
            val elementType = readU32(channel)
            val count = readU64(channel)
            if (elementType == TYPE_UINT32 || elementType == TYPE_UINT64 ||
                elementType == TYPE_STRING
            ) {
                skipArray(channel, elementType, count)
                count
            } else {
                skipArray(channel, elementType, count)
                null
            }
        }
        else -> throw EOFException("Unsupported GGUF metadata type $type")
    }

    private fun skipValue(channel: FileChannel, type: Int) {
        when (type) {
            TYPE_ARRAY -> {
                val elementType = readU32(channel)
                val count = readU64(channel)
                skipArray(channel, elementType, count)
            }
            TYPE_STRING -> skipString(channel)
            else -> skip(
                channel,
                fixedSize(type)?.toLong() ?: throw EOFException("Unsupported GGUF type $type"),
            )
        }
    }

    private fun skipArray(channel: FileChannel, elementType: Int, count: Long) {
        if (count < 0) throw EOFException("Invalid GGUF array length")
        val fixed = fixedSize(elementType)
        if (fixed != null) {
            skip(channel, Math.multiplyExact(count, fixed.toLong()))
            return
        }
        when (elementType) {
            TYPE_STRING -> repeat(count.toInt()) { skipString(channel) }
            TYPE_ARRAY -> repeat(count.toInt()) {
                val nestedType = readU32(channel)
                val nestedCount = readU64(channel)
                skipArray(channel, nestedType, nestedCount)
            }
            else -> throw EOFException("Unsupported GGUF array element type $elementType")
        }
    }

    private fun fixedSize(type: Int): Int? = when (type) {
        TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1
        TYPE_UINT16, TYPE_INT16 -> 2
        TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4
        TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8
        else -> null
    }

    private fun readString(channel: FileChannel): String {
        val length = readU64(channel)
        if (length > MAX_STRING_BYTES) throw EOFException("GGUF string is too large")
        return readBytes(channel, length.toInt()).toString(Charsets.UTF_8)
    }

    private fun skipString(channel: FileChannel) {
        val length = readU64(channel)
        skip(channel, length)
    }

    private fun skip(channel: FileChannel, bytes: Long) {
        if (bytes < 0) throw EOFException("Negative GGUF skip")
        channel.position(Math.addExact(channel.position(), bytes))
    }

    private fun readBytes(channel: FileChannel, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        readFully(channel, buffer)
        return buffer.array()
    }

    private fun readU8(channel: FileChannel): Int {
        val buffer = ByteBuffer.allocate(1).order(ByteOrder.LITTLE_ENDIAN)
        readFully(channel, buffer)
        return buffer.get(0).toInt() and 0xff
    }

    private fun readU16(channel: FileChannel): Int {
        val buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        readFully(channel, buffer)
        return buffer.getShort(0).toInt() and 0xffff
    }

    private fun readU32(channel: FileChannel): Int {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        readFully(channel, buffer)
        return buffer.getInt(0)
    }

    private fun readU64(channel: FileChannel): Long {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        readFully(channel, buffer)
        return buffer.getLong(0)
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException("Truncated GGUF file")
        }
    }

    // general.file_type uses llama_ftype from llama.h, not ggml_type.
    private fun quantName(type: Int?): String? = when (type) {
        0 -> "F32"
        1 -> "F16"
        2 -> "Q4_0"
        3 -> "Q4_1"
        7 -> "Q8_0"
        8 -> "Q5_0"
        9 -> "Q5_1"
        10 -> "Q2_K"
        11 -> "Q3_K_S"
        12 -> "Q3_K_M"
        13 -> "Q3_K_L"
        14 -> "Q4_K_S"
        15 -> "Q4_K_M"
        16 -> "Q5_K_S"
        17 -> "Q5_K_M"
        18 -> "Q6_K"
        19 -> "IQ2_XXS"
        20 -> "IQ2_XS"
        21 -> "Q2_K_S"
        22 -> "IQ3_XS"
        23 -> "IQ3_XXS"
        24 -> "IQ1_S"
        25 -> "IQ4_NL"
        26 -> "IQ3_S"
        27 -> "IQ3_M"
        28 -> "IQ2_S"
        29 -> "IQ2_M"
        30 -> "IQ4_XS"
        31 -> "IQ1_M"
        32 -> "BF16"
        36 -> "TQ1_0"
        37 -> "TQ2_0"
        38 -> "MXFP4_MOE"
        39 -> "NVFP4"
        40 -> "Q1_0"
        41 -> "Q2_0"
        42 -> "F8_E4M3"
        else -> null
    }

    private fun unknown(reason: String) = GgufInfo(
        version = 0,
        architecture = null,
        quantization = null,
        fileType = null,
        parameterCount = null,
        trainContextLength = null,
        layerCount = null,
        embeddingLength = null,
        headCount = null,
        keyValueHeadCount = null,
        keyLength = null,
        valueLength = null,
        feedForwardLength = null,
        vocabSize = null,
        slidingWindow = null,
        tensorCount = null,
        tensorBytes = null,
        tensorElementCount = null,
        tensorTypes = emptySet(),
        kvBytesPerToken = null,
        kvLayout = KvLayout.UNKNOWN,
        metadataSource = "unavailable",
        notes = emptyList(),
        reason = reason,
    )
}
