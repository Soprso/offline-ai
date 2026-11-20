package com.soprso.dhurandharai.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.soprso.dhurandharai.database.ModelMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Owns GGUF files in app-private storage.
 *
 * The Android document picker returns a content URI, not a filesystem path, so
 * a selected model is copied into `filesDir/models` before llama.cpp loads it.
 * SHA-256 is computed during that single copy pass; no network is involved.
 */
object ModelRepository {

    private const val MODELS_DIR = "models"
    private const val COPY_BUFFER_BYTES = 1024 * 1024
    private const val PROGRESS_INTERVAL_BYTES = 4L * 1024 * 1024

    fun modelsDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, MODELS_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not create model storage directory")
        }
        return dir
    }

    fun fileFor(context: Context, fileName: String): File = File(modelsDir(context), fileName)

    /**
     * Copies [uri] into app-private storage and returns metadata for Room.
     * Reports `(copiedBytes, totalBytes)`; `totalBytes` is <= 0 when unknown.
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        onProgress: (Long, Long) -> Unit,
    ): ModelMetadata = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rawName = queryName(resolver, uri)
            ?: uri.lastPathSegment
            ?: throw IOException("Could not determine the selected file name")

        val baseName = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (!baseName.endsWith(".gguf", ignoreCase = true)) {
            throw IllegalArgumentException("Only .gguf model files are supported")
        }

        val safeName = sanitize(baseName)
        val dir = modelsDir(context)
        val fileName = uniqueName(dir, safeName)
        val target = File(dir, fileName)
        val temporary = File(dir, "$fileName.part")

        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var lastReported = 0L
        val total = querySize(resolver, uri)

        try {
            val input = resolver.openInputStream(uri)
                ?: throw IOException("The selected file could not be opened")
            input.use { source ->
                FileOutputStream(temporary).use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        if (copied - lastReported >= PROGRESS_INTERVAL_BYTES) {
                            onProgress(copied, total)
                            lastReported = copied
                        }
                    }
                    sink.flush()
                }
            }

            if (copied == 0L) throw IOException("The selected model file is empty")
            if (!temporary.renameTo(target)) {
                throw IOException("Could not finalize the imported model file")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }

        onProgress(copied, if (total > 0) total else copied)
        ModelMetadata(
            id = UUID.randomUUID().toString(),
            displayName = safeName,
            fileName = fileName,
            filePath = target.absolutePath,
            sizeBytes = copied,
            quantization = null,
            contextLength = null,
            sha256 = hex(digest.digest()),
            sourceUrl = null,
            source = "manual import",
            license = "user-provided",
            architecture = null,
            parameterCount = null,
            catalogId = null,
            downloadedAt = System.currentTimeMillis(),
            status = "INSTALLED",
        )
    }

    suspend fun delete(context: Context, model: ModelMetadata): Boolean = withContext(Dispatchers.IO) {
        val file = fileFor(context, model.fileName)
        if (!file.exists()) true else file.delete()
    }

    suspend fun sha256Of(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        hex(digest.digest())
    }

    suspend fun finalizeDownload(temp: File, target: File): Boolean = withContext(Dispatchers.IO) {
        if (target.exists() && !target.delete()) return@withContext false
        temp.renameTo(target)
    }

    private fun queryName(resolver: android.content.ContentResolver, uri: Uri): String? =
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long =
        resolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
        } ?: -1L

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        return cleaned.ifEmpty { "model.gguf" }
    }

    private fun uniqueName(dir: File, preferred: String): String {
        if (!File(dir, preferred).exists()) return preferred
        val dot = preferred.lastIndexOf('.')
        val stem = if (dot > 0) preferred.substring(0, dot) else preferred
        val extension = if (dot > 0) preferred.substring(dot) else ""
        var suffix = 1
        while (true) {
            val candidate = "${stem}_$suffix$extension"
            if (!File(dir, candidate).exists()) return candidate
            suffix++
        }
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(Character.forDigit(value ushr 4, 16))
            append(Character.forDigit(value and 0x0f, 16))
        }
    }
}
