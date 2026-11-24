package com.soprso.dhurandharai.document.extractors

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExtractorTest {

    @Test
    fun parseCsvLineHandlesSimpleFields() {
        val result = CsvExtractor.Companion.parseCsvLine("a,b,c")
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun parseCsvLineHandlesQuotedFields() {
        val result = CsvExtractor.Companion.parseCsvLine("\"a,b\",\"c\"")
        assertEquals(listOf("a,b", "c"), result)
    }

    @Test
    fun parseCsvLineHandlesEscapedQuotes() {
        val result = CsvExtractor.Companion.parseCsvLine("\"a\"\"b\",c")
        assertEquals(listOf("a\"b", "c"), result)
    }

    @Test
    fun parseCsvLineHandlesMixedQuotedAndUnquoted() {
        val result = CsvExtractor.Companion.parseCsvLine("simple,\"quoted, field\",another")
        assertEquals(listOf("simple", "quoted, field", "another"), result)
    }

    @Test
    fun parseCsvLineHandlesEmptyFields() {
        val result = CsvExtractor.Companion.parseCsvLine("a,,c")
        assertEquals(listOf("a", "", "c"), result)
    }
}
