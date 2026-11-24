package com.soprso.dhurandharai.document.extractors

import android.content.Context
import android.net.Uri
import com.soprso.dhurandharai.document.DocumentMetadata
import com.soprso.dhurandharai.document.AttachmentError
import com.soprso.dhurandharai.document.AttachmentStatus
import com.soprso.dhurandharai.document.DocumentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node
import org.w3c.dom.NodeList

/** Extracts text and embedded images from DOCX (WordprocessingML) files. */
class DocxExtractor(private val context: Context) {

    companion object {
        const val MAX_PARAGRAPHS = 2000
        const val MAX_TABLES = 100
    }

    suspend fun extract(
        uri: Uri?,
        file: File?,
        attachment: DocumentMetadata,
    ): DocumentMetadata = withContext(Dispatchers.Default) {
        val docxFile = file ?: File(localPathFor(uri, context) ?: return@withContext attachment.copy(
            status = AttachmentStatus.Failed,
            error = AttachmentError("NO_LOCAL_FILE", "DOCX file is not available locally", true),
        ))

        if (!docxFile.exists()) {
            return@withContext attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("FILE_NOT_FOUND", "DOCX file does not exist", true),
            )
        }

        val textBuilder = StringBuilder()
        var imageCount = 0
        var paragraphCount = 0

        ZipFile(docxFile).use { zip ->
            // Extract text from document.xml
            val documentEntry = zip.getEntry("word/document.xml")
            if (documentEntry != null) {
                zip.getInputStream(documentEntry).use { input ->
                    val doc = DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(input)
                    val body = doc.getElementsByTagName("w:body").item(0)
                    if (body != null) {
                        processBody(body, textBuilder, MAX_PARAGRAPHS, MAX_TABLES)
                        paragraphCount = countParagraphs(body)
                    }
                }
            }

            // Count embedded images in word/media/
            imageCount = countMediaEntries(zip, "word/media/")
        }

        val extracted = textBuilder.toString().trim()

        attachment.copy(
            status = AttachmentStatus.Ready,
            extractedText = if (extracted.isNotBlank()) extracted else null,
            pageCount = 1,
            imageCount = imageCount,
            processedAt = System.currentTimeMillis(),
        )
    }

    private fun processBody(
        node: Node,
        sb: StringBuilder,
        maxParagraphs: Int,
        maxTables: Int,
    ) {
        var child = node.firstChild
        var paras = 0
        var tables = 0
        while (child != null && paras < maxParagraphs && tables < maxTables) {
            val name = child.nodeName
            when {
                name == "w:p" -> {
                    val paraText = extractParagraphText(child)
                    if (paraText.isNotBlank()) {
                        sb.append(paraText).append("\n")
                    }
                    paras++
                }
                name == "w:tbl" -> {
                    sb.append("[TABLE]\n")
                    val tableText = extractTableText(child)
                    sb.append(tableText).append("\n[/TABLE]\n")
                    tables++
                }
                name == "w:sectPr" -> Unit // skip section properties
            }
            child = child.nextSibling
        }
        if (paras >= maxParagraphs) {
            sb.append("\n… [document truncated at $maxParagraphs paragraphs]")
        }
        if (tables >= maxTables) {
            sb.append("\n… [table limit reached]")
        }
    }

    private fun extractParagraphText(paragraph: Node): String {
        val sb = StringBuilder()
        var child = paragraph.firstChild
        while (child != null) {
            child?.let { current ->
                if (current.nodeName == "w:r") { // run
                    var tc = current.firstChild
                    while (tc != null) {
                        if (tc.nodeName == "w:t") {
                            val text = tc.textContent
                            if (text.isNotBlank()) sb.append(text)
                        }
                        tc = tc.nextSibling
                    }
                }
            }
            child = child.nextSibling
        }
        return sb.toString()
    }

    private fun extractTableText(table: Node): String {
        val sb = StringBuilder()
        var child = table.firstChild
        while (child != null) {
            child?.let { currentRow ->
                if (currentRow.nodeName == "w:tr") { // table row
                    var cell = currentRow.firstChild
                    while (cell != null) {
                        cell?.let { currentCell ->
                            if (currentCell.nodeName == "w:tc") {
                                var tc = currentCell.firstChild
                                while (tc != null) {
                                    if (tc.nodeName == "w:t") {
                                        sb.append(tc.textContent)
                                        sb.append(" | ")
                                    }
                                    tc = tc.nextSibling
                                }
                                sb.append("\n")
                            }
                        }
                        cell = cell.nextSibling
                    }
                }
            }
            child = child.nextSibling
        }
        return sb.toString()
    }

    private fun countParagraphs(body: Node): Int {
        var count = 0
        var child = body.firstChild
        while (child != null) {
            if (child.nodeName == "w:p") count++
            child = child.nextSibling
        }
        return count
    }

    private fun countMediaEntries(zip: ZipFile, prefix: String): Int {
        var count = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name.startsWith(prefix) && !entry.isDirectory) {
                val lower = entry.name.lowercase()
                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")) {
                    count++
                }
            }
        }
        return count
    }

    private fun localPathFor(uri: Uri?, context: Context): String? {
        return null
    }
}
