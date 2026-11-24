package com.soprso.dhurandharai.document

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Manages persistent storage of attachments in app-private directories. */
object DocumentStorage {

    private const val DOCUMENTS_DIR = "documents"
    private const val IMAGES_ROOT_DIR = "images"
    private const val ORIGINALS_DIR = "originals"
    private const val THUMBNAILS_DIR = "thumbnails"
    private const val EXTRACTED_DIR = "extracted"
    /** Embedded images extracted from documents live under documents/. */
    private const val IMAGES_DIR = "images"
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val STALE_PART_MILLIS = 24L * 60L * 60L * 1000L

    fun documentsDir(context: Context): File {
        return dir(context, DOCUMENTS_DIR)
    }

    fun originalsDir(context: Context): File {
        return dir(context, DOCUMENTS_DIR, ORIGINALS_DIR)
    }

    fun thumbnailsDir(context: Context): File {
        return dir(context, DOCUMENTS_DIR, THUMBNAILS_DIR)
    }

    fun extractedDir(context: Context): File {
        return dir(context, DOCUMENTS_DIR, EXTRACTED_DIR)
    }

    fun imagesDir(context: Context): File {
        return dir(context, DOCUMENTS_DIR, IMAGES_DIR)
    }

    /** Persistent originals for standalone chat images (not document pages). */
    fun imageOriginalsDir(context: Context): File {
        return dir(context, IMAGES_ROOT_DIR, ORIGINALS_DIR)
    }

    /** 256 px WebP thumbnails for standalone chat images. */
    fun imageThumbnailsDir(context: Context): File {
        return dir(context, IMAGES_ROOT_DIR, THUMBNAILS_DIR)
    }

    fun imageRootDir(context: Context): File {
        return dir(context, IMAGES_ROOT_DIR)
    }

    private fun dir(context: Context, vararg segments: String): File {
        var parent = context.applicationContext.filesDir
        for (segment in segments) {
            parent = File(parent, segment)
            if (!parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("Could not create directory: ${parent.absolutePath}")
            }
        }
        return parent
    }

    /** Copies a content URI into app-private storage and returns the local file. */
    suspend fun copyUriToLocal(
        context: Context,
        uri: Uri,
        fileName: String,
    ): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open content URI: $uri")
        val target = File(originalsDir(context), sanitizeFileName(fileName))
        val temp = File(originalsDir(context), "${target.name}.part")
        input.use { source ->
            FileOutputStream(temp).use { output ->
                copyStream(source, output)
            }
        }
        if (!temp.renameTo(target)) {
            throw IllegalStateException("Could not finalize copied file")
        }
        target
    }

    /**
     * Copies a picked image into files/images/originals/image_<id>.<ext>.
     * The original bytes are preserved (no re-encode), and the location is
     * stored relative to filesDir so it survives restarts.
     */
    suspend fun copyImageUriToLocal(
        context: Context,
        uri: Uri,
        imageId: String,
        extension: String,
    ): File = withContext(Dispatchers.IO) {
        val dir = imageOriginalsDir(context)
        val target = File(dir, "image_${imageId}.${safeExtension(extension)}")
        val temp = File(dir, "${target.name}.part")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open content URI: $uri")
        input.use { source ->
            FileOutputStream(temp).use { output ->
                copyStream(source, output)
            }
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("Could not finalize copied image")
        }
        target
    }

    /**
     * Copies a locally generated image (for example a diffusion model output)
     * into persistent image storage. The source is left untouched so the
     * engine can keep its own cache semantics.
     */
    suspend fun copyFileToImageStorage(
        context: Context,
        source: File,
        imageId: String,
        extension: String,
    ): File = withContext(Dispatchers.IO) {
        if (!source.isFile) {
            throw IllegalStateException("Generated image does not exist: ${source.absolutePath}")
        }
        val dir = imageOriginalsDir(context)
        val target = File(dir, "image_${imageId}.${safeExtension(extension)}")
        val temp = File(dir, "${target.name}.part")
        source.inputStream().use { input ->
            FileOutputStream(temp).use { output ->
                copyStream(input, output)
            }
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("Could not finalize generated image")
        }
        target
    }

    /** Relative path stored in the database for images. */
    fun relativePath(context: Context, file: File): String =
        file.relativeTo(context.applicationContext.filesDir).invariantSeparatorsPath

    /**
     * Resolves an absolute legacy path or a relative image path against
     * filesDir. Returns null for paths that escape app-private storage.
     */
    fun resolvePath(context: Context, path: String?): File? {
        if (path.isNullOrBlank()) return null
        val root = context.applicationContext.filesDir
        val candidate = if (File(path).isAbsolute) File(path) else File(root, path)
        return try {
            val rootPath = root.canonicalPath
            if (candidate.canonicalPath.startsWith(rootPath)) candidate else null
        } catch (_: Exception) {
            null
        }
    }

    private fun safeExtension(extension: String): String =
        extension.lowercase()
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifBlank { "img" }

    private fun copyStream(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        return cleaned.ifEmpty { "attachment" }
    }

    fun uniqueFileName(dir: File, preferred: String): String {
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

    /** Requests persistent read permission for a content URI when supported. */
    fun persistUriPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            // Non-fatal: permission may not be persistable on all providers.
        }
    }

    /** Deletes a file safely, ignoring missing files. */
    fun deleteFile(path: String?) {
        if (path.isNullOrBlank()) return
        File(path).delete()
    }

    /** Deletes a stored path (absolute legacy path or relative image path). */
    fun deletePath(context: Context, path: String?) {
        resolvePath(context, path)?.delete()
    }

    /**
     * Cleans up orphaned attachment/image files not referenced by any database
     * row. Referenced paths may be absolute (documents) or relative (images).
     * Stale partial copies older than a day are removed too.
     */
    fun cleanupOrphans(context: Context, referencedPaths: Set<String?>) {
        val absoluteReferences = referencedPaths
            .filterNotNull()
            .mapNotNull { resolvePath(context, it)?.absolutePath }
            .toSet()

        val dirs = listOf(
            originalsDir(context),
            thumbnailsDir(context),
            extractedDir(context),
            imagesDir(context),
            imageOriginalsDir(context),
            imageThumbnailsDir(context),
        )
        val now = System.currentTimeMillis()
        for (dir in dirs) {
            if (!dir.exists()) continue
            for (file in dir.listFiles() ?: emptyArray()) {
                val path = file.absolutePath
                if (path in absoluteReferences) continue
                if (path.endsWith(".part")) {
                    if (now - file.lastModified() > STALE_PART_MILLIS) file.delete()
                    continue
                }
                file.delete()
            }
        }
    }
}
