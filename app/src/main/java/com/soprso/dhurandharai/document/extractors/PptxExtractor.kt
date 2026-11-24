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

/** Extracts text and embedded images from PPTX (PresentationML) files. */
class PptxExtractor(private val context: Context) {

    companion object {
        const val MAX_SLIDES = 200
    }

    suspend fun extract(
        uri: Uri?,
        file: File?,
        attachment: DocumentMetadata,
    ): DocumentMetadata = withContext(Dispatchers.Default) {
        val pptxFile = file ?: File(localPathFor(uri, context) ?: return@withContext attachment.copy(
            status = AttachmentStatus.Failed,
            error = AttachmentError("NO_LOCAL_FILE", "PPTX file is not available locally", true),
        ))

        if (!pptxFile.exists()) {
            return@withContext attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("FILE_NOT_FOUND", "PPTX file does not exist", true),
            )
        }

        val textBuilder = StringBuilder()
        var imageCount = 0
        var slideCount = 0

        ZipFile(pptxFile).use { zip ->
            // Collect slide entries in order
            val slideEntries = mutableListOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                    slideEntries += entry.name
                }
            }
            slideEntries.sort()

            // Count images in ppt/media/
            imageCount = countMediaEntries(zip, "ppt/media/")

            // Process slides
            for (slideName in slideEntries.take(MAX_SLIDES)) {
                val entry = zip.getEntry(slideName) ?: continue
                val slideNumber = slideEntries.indexOf(slideName) + 1
                zip.getInputStream(entry).use { input ->
                    val doc = DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(input)
                    val slideText = extractSlideText(doc)
                    if (slideText.isNotBlank()) {
                        textBuilder.append("\n--- SLIDE $slideNumber ---\n")
                        textBuilder.append(slideText).append("\n")
                    }
                }
                slideCount++
            }

            if (slideEntries.size > MAX_SLIDES) {
                textBuilder.append("\n… [presentation truncated at $MAX_SLIDES slides]")
            }
        }

        val extracted = textBuilder.toString().trim()

        attachment.copy(
            status = AttachmentStatus.Ready,
            extractedText = if (extracted.isNotBlank()) extracted else null,
            pageCount = slideCount.coerceAtLeast(1),
            imageCount = imageCount,
            processedAt = System.currentTimeMillis(),
        )
    }

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
