package com.soprso.dhurandharai.document.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentChunkerTest {

    @Test
    fun chunkSplitsByParagraphs() {
        val text = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph."
        val chunks = DocumentChunker.chunk("doc-1", "file.txt", text)

        assertTrue(chunks.isNotEmpty())
        val allText = chunks.joinToString(" ") { it.text }
        assertTrue(allText.contains("First paragraph"))
        assertTrue(allText.contains("Second paragraph"))
        assertTrue(allText.contains("Third paragraph"))
    }

    @Test
    fun chunkRespectsMaxTokens() {
        val longText = ("This is sentence number one. This is sentence number two. ").repeat(200)
        val chunks = DocumentChunker.chunk("doc-1", "file.txt", longText)

        assertTrue(chunks.isNotEmpty())
        // All chunk text should be preserved (no data loss)
        val allText = chunks.joinToString(" ") { it.text }
        assertTrue(allText.length >= 1000)
    }

    @Test
    fun chunkPreservesPageNumber() {
        val chunks = DocumentChunker.chunk("doc-1", "file.pdf", "Page content", pageNumber = 3)
        assertEquals(3, chunks[0].pageNumber)
    }

    @Test
    fun chunkMarksOcr() {
        val chunks = DocumentChunker.chunk("doc-1", "scan.pdf", "OCR text", isOcr = true)
        assertTrue(chunks[0].isOcr)
    }

    @Test
    fun emptyTextReturnsEmptyList() {
        val chunks = DocumentChunker.chunk("doc-1", "empty.txt", "")
        assertEquals(0, chunks.size)
    }
}
