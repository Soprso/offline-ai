package com.soprso.dhurandharai.document.context

/** Builds a structured context string from processed documents for the AI model. */
object ContextBuilder {

    /** Total token budget for document context (rough estimate). */
    const val MAX_CONTEXT_TOKENS = 2048

    /**
     * Builds a document context string from processed attachments.
     * Respects [MAX_CONTEXT_TOKENS] by selecting the most relevant chunks first.
     */
    fun buildContext(
        chunksByDoc: Map<String, List<DocumentChunk>>,
        maxTokens: Int = MAX_CONTEXT_TOKENS,
    ): String {
        val sb = StringBuilder()
        var usedTokens = 0

        val sortedDocs = chunksByDoc.entries.sortedBy { it.value.firstOrNull()?.fileName ?: "" }

        for ((docId, chunks) in sortedDocs) {
            if (chunks.isEmpty()) continue
            val fileName = chunks.first().fileName
            sb.append("\nDOCUMENT: $fileName\n")

            var docTokens = 0
            for (chunk in chunks) {
                if (docTokens + chunk.tokenEstimate + 50 > maxTokens - usedTokens) break
                val header = buildChunkHeader(chunk)
                sb.append(header)
                sb.append(chunk.text)
                sb.append("\n")
                docTokens += chunk.tokenEstimate + header.length / 4
            }

            usedTokens += docTokens
            if (usedTokens >= maxTokens) break
        }

        if (sb.isEmpty()) return ""

        sb.insert(0, "\n=== DOCUMENT CONTEXT ===\nThe following content was extracted from attached documents. " +
            "Use it to answer the user's questions. Page references are provided where available.\n")
        sb.append("\n=== END DOCUMENT CONTEXT ===\n")

        return sb.toString()
    }

    private fun buildChunkHeader(chunk: DocumentChunk): String {
        val parts = mutableListOf<String>()
        if (chunk.pageNumber != null) parts.add("PAGE ${chunk.pageNumber}")
        if (chunk.sectionHeading != null) parts.add("SECTION: ${chunk.sectionHeading}")
        if (chunk.isOcr) parts.add("[OCR]")
        return if (parts.isNotEmpty()) "[${parts.joinToString(" / ")}] " else ""
    }
}
