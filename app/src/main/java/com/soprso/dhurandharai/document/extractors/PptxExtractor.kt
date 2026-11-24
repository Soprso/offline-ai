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

/** Extracts slide text and embedded image counts from PPTX (PresentationML) files. */
class PptxExtractor(private val context: Context) {

    companion object {
        const val MAX_SLIDES = 200
        const val MAX_ENTRY_BYTES = 4_000_000L
    }

    suspend fun extract(
        uri: Uri?,
        file: File?,
        attachment: DocumentMetadata,
    ): DocumentMetadata = withContext(Dispatchers.Default) {
        val pptxFile = file ?: return@withContext attachment.copy(
            status = AttachmentStatus.Failed,
            error = AttachmentError("NO_LOCAL_FILE", "PPTX file is not available locally", true),
        )
        if (!pptxFile.exists()) {
            return@withContext attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("FILE_NOT_FOUND", "PPTX file does not exist", true),
            )
        }

        val textBuilder = StringBuilder()
        var imageCount = 0
        var slideCount = 0
        try {
            ZipFile(pptxFile).use { zip ->
                val slideEntries = mutableListOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                        slideEntries += entry.name
                    }
                }
                slideEntries.sort()

                imageCount = countMediaEntries(zip, "ppt/media/")

                for (slideName in slideEntries.take(MAX_SLIDES)) {
                    val entry = zip.getEntry(slideName) ?: continue
                    slideCount++
                    if (entry.size > MAX_ENTRY_BYTES) continue
                    zip.getInputStream(entry).use { input ->
                        val doc = secureParse(input)
                        val slideText = extractSlideText(doc)
                        if (slideText.isNotBlank()) {
                            textBuilder.append("\n--- SLIDE ${slideEntries.indexOf(slideName) + 1} ---\n")
                            textBuilder.append(slideText).append("\n")
                        }
                    }
                }

                if (slideEntries.size > MAX_SLIDES) {
                    textBuilder.append("\n… [presentation truncated at $MAX_SLIDES slides]\n")
                }
            }
        } catch (e: Exception) {
            return@withContext attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("PPTX_PARSE_FAILED", "Unable to read PPTX: ${e.message ?: "unknown"}", true),
            )
        }

        val extracted = textBuilder.toString().trim()
        attachment.copy(
            status = AttachmentStatus.Ready,
            extractedText = extracted.ifBlank { null },
            pageCount = slideCount.coerceAtLeast(1),
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

    private fun extractSlideText(doc: org.w3c.dom.Document): String {
        val sb = StringBuilder()
        val textNodes = doc.getElementsByTagName("a:t")
        for (i in 0 until textNodes.length) {
            val text = textNodes.item(i).textContent
            if (text.isNotBlank()) sb.append(text).append(" ")
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
