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
import java.nio.charset.StandardCharsets

/** Extracts text from CSV files with bounded row parsing and quoted-field support. */
class CsvExtractor(private val context: Context) {

    companion object {
        const val MAX_ROWS = 200
        const val MAX_COLUMNS = 40
        const val MAX_READ_BYTES = 2_000_000
        const val MAX_CELL_CHARS = 200

        fun parseCsvLine(line: String): List<String> {
            val out = mutableListOf<String>()
            val cell = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' -> {
                        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                            cell.append('"')
                            i++
                        } else {
                            inQuotes = !inQuotes
                        }
                    }
                    c == ',' && !inQuotes -> {
                        out.add(cell.toString())
                        cell.clear()
                    }
                    else -> cell.append(c)
                }
                i++
            }
            out.add(cell.toString())
            return out
        }
    }

    fun parseCsvLine(line: String): List<String> = Companion.parseCsvLine(line)

    suspend fun extract(
        uri: Uri?,
        file: File?,
        attachment: DocumentMetadata,
    ): DocumentMetadata = withContext(Dispatchers.IO) {
        val input = when {
            uri != null -> context.contentResolver.openInputStream(uri)
            file != null && file.exists() -> FileInputStream(file)
            else -> null
        } ?: return@withContext attachment.copy(
            status = AttachmentStatus.Failed,
            error = AttachmentError("FILE_NOT_READABLE", "Could not open the CSV file", true),
        )

        val sb = StringBuilder()
        var rowCount = 0
        var truncated = false
        var bytesRead = 0L

        input.use { stream ->
            val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
            var line = reader.readLine()
            var isHeader = true
            while (line != null && rowCount < MAX_ROWS) {
                bytesRead += line.toByteArray(StandardCharsets.UTF_8).size
                if (bytesRead > MAX_READ_BYTES) {
                    truncated = true
                    break
                }
                val cells = parseCsvLine(line).take(MAX_COLUMNS).map { it.trim().take(MAX_CELL_CHARS) }
                if (isHeader) {
                    sb.append("HEADERS: ").append(cells.joinToString(" | ")).append("\n")
                    isHeader = false
                } else {
                    rowCount++
                    sb.append("ROW ").append(rowCount).append(": ").append(cells.joinToString(" | ")).append("\n")
                }
                line = reader.readLine()
            }
            if (!truncated && line != null) truncated = true
        }

        if (truncated) sb.append("\n… [CSV truncated: max $MAX_ROWS rows or 2 MB]\n")

        attachment.copy(
            status = AttachmentStatus.Ready,
            extractedText = sb.toString().trim().ifBlank { null },
            pageCount = 1,
            processedAt = System.currentTimeMillis(),
        )
    }
}
