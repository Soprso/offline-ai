package com.soprso.dhurandharai.document.extractors

import android.content.Context
import android.net.Uri
import com.soprso.dhurandharai.document.DocumentMetadata
import com.soprso.dhurandharai.document.AttachmentError
import com.soprso.dhurandharai.document.AttachmentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Extracts text and embedded image counts from DOCX (WordprocessingML) files. */
class DocxExtractor(private val context: Context) {

    companion object {
        const val MAX_PARAGRAPHS = 2000
        const val MAX_TABLES = 100
        const val MAX_ENTRY_BYTES = 4_000_000L
    }

    suspend fun extract(
        uri: Uri?,
        file: File?,
        attachment: DocumentMetadata,
    ): DocumentMetadata = withContext(Dispatchers.Default) {
        val docxFile = file ?: return@withContext attachment.copy(
            status = AttachmentStatus.Failed,
            error = AttachmentError("NO_LOCAL_FILE", "DOCX file is not available locally", true),
        )
        if (!docxFile.exists()) {
            return@withContext attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("FILE_NOT_FOUND", "DOCX file does not exist", true),
            )
        }

        val textBuilder = StringBuilder()
        var imageCount = 0
        try {
            ZipFile(docxFile).use { zip ->
                val documentEntry = zip.getEntry("word/document.xml")
                if (documentEntry != null) {
                    zip.getInputStream(documentEntry).use { input ->
                        val doc = secureParse(input)
                        val body = doc.getElementsByTagName("w:body").item(0)
                        if (body != null) processBody(body, textBuilder, MAX_PARAGRAPHS, MAX_TABLES)
                    }
                }
                imageCount = countMediaEntries(zip, "word/media/")
            }
        } catch (e: Exception) {
            return@withContext attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("DOCX_PARSE_FAILED", "Unable to read DOCX: ${e.message ?: "unknown"}", true),
            )
        }

        val extracted = textBuilder.toString().trim()
        attachment.copy(
            status = AttachmentStatus.Ready,
            extractedText = extracted.ifBlank { null },
            pageCount = 1,
            imageCount = imageCount,
            processedAt = System.currentTimeMillis(),
        )
    }

    private fun secureParse(input: InputStream) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        setExpandEntityReferences(false)
    }.newDocumentBuilder().parse(input)

    private fun processBody(node: org.w3c.dom.Node, sb: StringBuilder, maxParagraphs: Int, maxTables: Int) {
        var child = node.firstChild
        var paras = 0
        var tables = 0
        while (child != null && paras < maxParagraphs) {
            when (child.nodeName) {
                "w:p" -> {
                    val paraText = extractParagraphText(child)
                    if (paraText.isNotBlank()) sb.append(paraText).append("\n")
                    paras++
                }
                "w:tbl" -> {
                    if (tables < maxTables) {
                        sb.append("[TABLE]\n").append(extractTableText(child)).append("\n[/TABLE]\n")
                    }
                    tables++
                }
            }
            child = child.nextSibling
        }
        if (paras >= maxParagraphs) sb.append("\n… [document truncated at $maxParagraphs paragraphs]\n")
        if (tables > maxTables) sb.append("\n… [tables truncated at $maxTables]\n")
    }

    private fun extractParagraphText(paragraph: org.w3c.dom.Node): String {
        val sb = StringBuilder()
        val runs = paragraph.childNodes
        for (i in 0 until runs.length) {
            val run = runs.item(i) ?: continue
            if (run.nodeName != "w:r") continue
            val children = run.childNodes
            for (j in 0 until children.length) {
                val n = children.item(j) ?: continue
                if (n.nodeName == "w:t") sb.append(n.textContent)
                if (n.nodeName == "w:tab") sb.append("\t")
                if (n.nodeName == "w:br") sb.append("\n")
            }
        }
        return sb.toString().trim()
    }

    private fun extractTableText(table: org.w3c.dom.Node): String {
        val sb = StringBuilder()
        val rows = table.childNodes
        for (i in 0 until rows.length) {
            val row = rows.item(i) ?: continue
            if (row.nodeName != "w:tr") continue
            val cells = row.childNodes
            val cellTexts = mutableListOf<String>()
            for (j in 0 until cells.length) {
                val cell = cells.item(j) ?: continue
                if (cell.nodeName != "w:tc") continue
                cellTexts.add(extractParagraphsFromNode(cell))
            }
            if (cellTexts.isNotEmpty()) sb.append(cellTexts.joinToString(" | ")).append("\n")
        }
        return sb.toString().trim()
    }

    private fun extractParagraphsFromNode(node: org.w3c.dom.Node): String {
        val sb = StringBuilder()
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) ?: continue
            if (child.nodeName == "w:p") {
                val t = extractParagraphText(child)
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t)
                }
            } else {
                val nested = extractParagraphsFromNode(child)
                if (nested.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(nested)
                }
            }
        }
        return sb.toString().trim()
    }

    private fun countMediaEntries(zip: ZipFile, prefix: String): Int {
        var count = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name.startsWith(prefix) && !entry.isDirectory) {
                if (entry.size > MAX_ENTRY_BYTES) continue
                val lower = entry.name.lowercase()
                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".gif") || lower.endsWith(".webp")
                ) count++
            }
        }
        return count
    }
}
