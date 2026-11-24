package com.soprso.dhurandharai.document.extractors

import android.content.Context
import android.net.Uri
import com.soprso.dhurandharai.document.DocumentMetadata
import com.soprso.dhurandharai.document.AttachmentError
import com.soprso.dhurandharai.document.AttachmentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Extracts text from plain text and Markdown files. */
class TextExtractor(private val context: Context) {

    companion object {
        const val MAX_READ_BYTES = 2_000_000 // 2 MB cap
    }

    suspend fun extract(
        uri: Uri?,
        file: File?,
        attachment: DocumentMetadata,
    ): DocumentMetadata = withContext(Dispatchers.IO) {
        val bytes = try {
            when {
                uri != null -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                file != null && file.exists() -> file.readBytes()
                else -> null
            }
        } catch (e: Exception) {
            return@withContext attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("FILE_NOT_READABLE", "Could not open the file: ${e.message ?: "unknown"}", true),
            )
        } ?: return@withContext attachment.copy(
            status = AttachmentStatus.Failed,
            error = AttachmentError("FILE_NOT_READABLE", "Could not open the file", true),
        )

        val bounded = if (bytes.size > MAX_READ_BYTES) bytes.copyOfRange(0, MAX_READ_BYTES) else bytes
        val text = decodeText(bounded)
        val suffix = if (bytes.size > MAX_READ_BYTES) "\n\n… [file truncated at 2 MB]" else ""

        attachment.copy(
            status = AttachmentStatus.Ready,
            extractedText = (text + suffix).trim().ifBlank { null },
            pageCount = 1,
            processedAt = System.currentTimeMillis(),
        )
    }

    private fun decodeText(bytes: ByteArray): String {
        val charsets = listOf(StandardCharsets.UTF_8, StandardCharsets.UTF_16, Charset.forName("windows-1252"), StandardCharsets.ISO_8859_1)
        for (cs in charsets) {
            try {
                val decoded = String(bytes, cs)
                val replacementCount = decoded.count { it == '\uFFFD' }
                if (replacementCount <= decoded.length / 20) return decoded
            } catch (_: Exception) {
            }
        }
        return String(bytes, StandardCharsets.UTF_8)
    }
}
