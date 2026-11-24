package com.soprso.dhurandharai.document.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    @Test
    fun buildContextRespectsTokenBudget() {
        val chunks = mapOf(
            "doc-1" to listOf(
                DocumentChunk(
                    id = "c1", documentId = "doc-1", fileName = "report.pdf",
                    pageNumber = 1, sectionHeading = null,
                    text = "A ".repeat(5000), isOcr = false, tokenEstimate = 1250,
                ),
                DocumentChunk(
                    id = "c2", documentId = "doc-1", fileName = "report.pdf",
                    pageNumber = 2, sectionHeading = null,
                    text = "B ".repeat(5000), isOcr = false, tokenEstimate = 1250,
                ),
            ),
        )
        val context = ContextBuilder.buildContext(chunks, maxTokens = 100)

        assertTrue(context.contains("report.pdf"))
        // With maxTokens=100 and chunk tokenEstimate=1250, no chunk text should fit.
        // Context should only contain headers/wrapper.
        assertTrue(context.length < 500)
    }

    @Test
    fun buildContextEmptyWhenNoChunks() {
        val context = ContextBuilder.buildContext(emptyMap())
        assertEquals("", context)
    }

    @Test
    fun buildContextIncludesPageReferences() {
        val chunks = mapOf(
            "doc-1" to listOf(
                DocumentChunk(
                    id = "c1", documentId = "doc-1", fileName = "report.pdf",
                    pageNumber = 1, sectionHeading = "Introduction",
                    text = "Page one text", isOcr = false,
                ),
            ),
        )
        val context = ContextBuilder.buildContext(chunks)
        assertTrue(context.contains("PAGE 1"))
        assertTrue(context.contains("SECTION: Introduction"))
    }

    @Test
    fun buildContextMarksOcrChunks() {
        val chunks = mapOf(
            "doc-1" to listOf(
                DocumentChunk(
                    id = "c1", documentId = "doc-1", fileName = "scan.pdf",
                    pageNumber = null, sectionHeading = null,
                    text = "OCR text", isOcr = true,
                ),
            ),
        )
        val context = ContextBuilder.buildContext(chunks)
        assertTrue(context.contains("[OCR]"))
    }
}
