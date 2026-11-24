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

/** Extracts text from CSV files with row/column limits to bound context size. */
class CsvExtractor(private val context: Context) {

    companion object {
        const val MAX_ROWS = 500
        const val MAX_COLUMNS = 50
        const val MAX_READ_BYTES = 2_000_000
    }

    suspend fun extract(
        uri: Uri?,
        file: File?,
        attachment: DocumentMetadata,
    ): DocumentMetadata = withContext(Dispatchers.IO) {
        val input = if (uri != null) {
            context.contentResolver.openInputStream(uri)
        } else if (file != null && file.exists()) {
            java.io.FileInputStream(file)
        } else {
            return@withContext attachment.copy(
                status = AttachmentStatus.Failed,
                error = AttachmentError("FILE_NOT_READABLE", "Could not open the CSV file", true),
            )
        }

        val text = input.use { stream ->
            val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
            val sb = StringBuilder()
            var row = 0
            var totalRead = 0L
            val buffer = CharArray(8192)

            // Read header
            var line: String? = reader.readLine()
            if (line != null) {
                val columns = line.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                    .take(MAX_COLUMNS)
                sb.append("HEADERS: ")
                sb.append(columns.joinToString(", "))
                sb.append("\n")
            }

            // Read rows
            while (row < MAX_ROWS) {
                line = reader.readLine()
                if (line == null) break
                val values = line.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                    .take(MAX_COLUMNS)
                sb.append("ROW ${row + 1}: ")
                sb.append(values.joinToString(", "))
                sb.append("\n")
                row++
                totalRead += line.length
                if (totalRead > MAX_READ_BYTES) {
                    sb.append("\n… [CSV truncated at 2 MB / $MAX_ROWS rows]")
                    break
                }
            }

            if (row >= MAX_ROWS) {
                sb.append("\n… [CSV truncated at $MAX_ROWS rows]")
            }

            sb.toString()
        }

        attachment.copy(
            status = AttachmentStatus.Ready,
            extractedText = text,
            pageCount = 1,
            processedAt = System.currentTimeMillis(),
        )
    }
}
