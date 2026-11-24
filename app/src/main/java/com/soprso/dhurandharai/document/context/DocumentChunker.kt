package com.soprso.dhurandharai.document.context

/** A single chunk of document context with page/section metadata. */
data class DocumentChunk(
    val id: String,
    val documentId: String,
    val fileName: String,
    val pageNumber: Int?,
    val sectionHeading: String?,
    val text: String,
    val isOcr: Boolean = false,
    val tokenEstimate: Int = 0,
)

/** Splits extracted document text into bounded chunks for the AI context window. */
object DocumentChunker {

    const val MAX_CHUNK_TOKENS = 512
    const val OVERLAP_TOKENS = 50

    /** Very rough token estimate: ~4 chars per token for typical text. */
    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    /**
     * Splits text into overlapping chunks bounded by [MAX_CHUNK_TOKENS].
     * Page breaks and section headings are preserved where possible.
     */
    fun chunk(
        documentId: String,
        fileName: String,
        text: String,
        pageNumber: Int? = null,
        sectionHeading: String? = null,
        isOcr: Boolean = false,
    ): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        val paragraphs = text.split("\n").filter { it.isNotBlank() }

        var current = StringBuilder()
        var currentTokens = 0
        var chunkIndex = 0

        for (para in paragraphs) {
            val paraTokens = estimateTokens(para)
            if (paraTokens > MAX_CHUNK_TOKENS) {
                val sentences = para.split(".".toRegex()).filter { it.isNotBlank() }
                for (sentence in sentences) {
                    val sentTokens = estimateTokens(sentence)
                    if (currentTokens + sentTokens > MAX_CHUNK_TOKENS && current.isNotEmpty()) {
                        chunks += DocumentChunk(
                            id = "$documentId-chunk-$chunkIndex",
                            documentId = documentId,
                            fileName = fileName,
                            pageNumber = pageNumber,
                            sectionHeading = sectionHeading,
                            text = current.toString().trim(),
                            isOcr = isOcr,
                            tokenEstimate = currentTokens,
                        )
                        chunkIndex++
                        current = StringBuilder()
                        currentTokens = 0
                    }
                    current.append(sentence).append(". ")
                    currentTokens += sentTokens
                }
            } else if (currentTokens + paraTokens > MAX_CHUNK_TOKENS && current.isNotEmpty()) {
                chunks += DocumentChunk(
                    id = "$documentId-chunk-$chunkIndex",
                    documentId = documentId,
                    fileName = fileName,
                    pageNumber = pageNumber,
                    sectionHeading = sectionHeading,
                    text = current.toString().trim(),
                    isOcr = isOcr,
                    tokenEstimate = currentTokens,
                )
                chunkIndex++
                current = StringBuilder(current.toString().takeLast(200))
                currentTokens = estimateTokens(current.toString())
                current.append("\n").append(para)
                currentTokens += paraTokens
            } else {
                if (current.isNotEmpty()) current.append("\n")
                current.append(para)
                currentTokens += paraTokens
            }
        }

        if (current.isNotEmpty()) {
            chunks += DocumentChunk(
                id = "$documentId-chunk-$chunkIndex",
                documentId = documentId,
                fileName = fileName,
                pageNumber = pageNumber,
                sectionHeading = sectionHeading,
                text = current.toString().trim(),
                isOcr = isOcr,
                tokenEstimate = currentTokens,
            )
        }

        return chunks
    }
}
