package com.soprso.dhurandharai.document.context

/** Builds a structured, budget-bounded context string from processed documents for the AI model. */
object ContextBuilder {

    /** Total token budget for document context (rough estimate: ~4 chars per token). */
    const val MAX_CONTEXT_TOKENS = 2048

    /**
     * Builds a document context string from processed attachments.
     * Respects [maxTokens] by selecting chunks within budget. Marks sections clearly
     * and never fabricates visual analysis.
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
                val chunkCost = chunk.tokenEstimate + buildChunkHeader(chunk).length / 4 + 5
                if (usedTokens + chunkCost > maxTokens) break
                val header = buildChunkHeader(chunk)
                sb.append(header)
                sb.append(chunk.text)
                sb.append("\n")
                docTokens += chunkCost
                usedTokens += chunkCost
            }

            if (usedTokens >= maxTokens) break
        }

        if (sb.isEmpty()) return ""

        sb.insert(0, "\n=== DOCUMENT CONTEXT ===\nThe following content was extracted from attached documents. " +
            "Use it to answer the user's questions. Page references are provided where available. " +
            "If information was not fully extracted, say so explicitly.\n")
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
