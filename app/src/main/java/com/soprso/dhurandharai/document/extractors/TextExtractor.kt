package com.soprso.dhurandharai.document.extractors

import android.content.Context
import android.net.Uri
import com.soprso.dhurandharai.document.DocumentMetadata
import com.soprso.dhurandharai.document.AttachmentError
import com.soprso.dhurandharai.document.AttachmentStatus
import com.soprso.dhurandharai.document.DocumentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Extracts text from plain text and Markdown files. */
class TextExtractor(private val context: Context) {

    companion object {
        const val MAX_READ_BYTES = 2_000_000 // 2 MB cap to avoid loading huge files into memory
    }

    suspend fun extract(
        uri: Uri?,
        file: File?,
        attachment: DocumentMetadata,
    ): DocumentMetadata = withContext(Dispatchers.IO) {
        val input: InputStream? = if (uri != null) {
            context.contentResolver.openInputStream(uri)
        } else if (file != null && file.exists()) {
            FileInputStream(file)
        } else {
            null
        }

        input?.use { stream ->
            val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
            val sb = StringBuilder()
            val buffer = CharArray(8192)
            var totalRead = 0L
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                totalRead += read
                if (totalRead > MAX_READ_BYTES) {
                    sb.append("\n… [file truncated at 2 MB]")
                    break
                }
                sb.append(buffer, 0, read)
            }
            return@withContext attachment.copy(
                status = AttachmentStatus.Ready,
                extractedText = sb.toString(),
                pageCount = 1,
                processedAt = System.currentTimeMillis(),
            )
        }

        return@withContext attachment.copy(
            status = AttachmentStatus.Failed,
            error = AttachmentError("FILE_NOT_READABLE", "Could not open the file", true),
        )
    }
}
