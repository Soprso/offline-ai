package com.soprso.dhurandharai.models

import android.content.Context
import com.soprso.dhurandharai.catalog.CatalogModel
import com.soprso.dhurandharai.catalog.ModelCatalog
import com.soprso.dhurandharai.database.AppDatabase
import com.soprso.dhurandharai.database.DownloadRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

enum class DownloadPhase {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    ERROR,
    DONE,
}

data class DownloadProgress(
    val catalogId: String,
    val phase: DownloadPhase,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val error: String? = null,
)

private class IntegrityException(message: String) : IOException(message)

/**
 * App-scoped, resumable HTTPS downloader.
 *
 * Downloads run on IO coroutines (never on the UI thread). Partial files use a
 * `.part` suffix and are only installed after size and SHA-256 verification.
 * State is persisted in Room; interrupted downloads become PAUSED on restart.
 */
object ModelDownloadManager {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_BYTES = 256 * 1024
    private const val EXTRA_STORAGE_BYTES = 128L * 1024 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var registry: ModelRegistryRepository
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            registry = ModelRegistryRepository(appContext)
            initialized = true
        }
        scope.launch { reconcileOnStart() }
    }

    fun phaseOf(catalogId: String): DownloadPhase? = _progress.value[catalogId]?.phase

    fun isActive(catalogId: String): Boolean = jobs[catalogId]?.isActive == true

    fun start(model: CatalogModel) {
        ensureInitialized()
        if (isActive(model.id)) return
        if (jobs.values.any { it.isActive }) {
            emit(model.id, DownloadPhase.ERROR, error = "Another download is already running")
            return
        }
        cancelledIds.remove(model.id)
        if (model.downloadUrl == null || !model.downloadUrl.startsWith("https://")) {
            emit(model.id, DownloadPhase.ERROR, error = "This model is not available for direct download")
            return
        }
        if (model.gated) {
            emit(model.id, DownloadPhase.ERROR, error = "Accept this model's license on Hugging Face first")
            return
        }

        jobs[model.id] = scope.launch {
            try {
                runDownload(model)
            } finally {
                jobs.remove(model.id)
                cancelledIds.remove(model.id)
            }
        }
    }

    fun pause(catalogId: String) {
        ensureInitialized()
        jobs[catalogId]?.cancel()
    }

    fun cancel(catalogId: String) {
        ensureInitialized()
        cancelledIds.add(catalogId)
        jobs[catalogId]?.cancel()
        scope.launch {
            val record = dao().get(catalogId)
            val temp = tempFile(record?.fileName ?: catalogId)
            if (temp.exists()) temp.delete()
            dao().delete(catalogId)
            clear(catalogId)
        }
    }

    fun retry(model: CatalogModel) {
        ensureInitialized()
        if (isActive(model.id)) return
        cancelledIds.add(model.id)
        scope.launch {
            dao().delete(model.id)
            tempFile(model.fileName).delete()
            clear(model.id)
            cancelledIds.remove(model.id)
            start(model)
        }
    }

    private suspend fun runDownload(model: CatalogModel) {
        val currentJob = coroutineContext[Job]
        val url = model.downloadUrl ?: throw IOException("Missing download URL")
        val final = finalFile(model.fileName)
        val temp = tempFile(model.fileName)

        val duplicate = registry.findByCatalogId(model.id)
            ?: model.sha256?.let { registry.findBySha256(it) }
        if (duplicate != null) {
            emit(model.id, DownloadPhase.ERROR, error = "Already installed: ${duplicate.displayName}")
            return
        }
        if (final.exists()) {
            // File exists without a registry row: adopt it only when size matches.
            if (final.length() == model.fileSizeBytes && verifyOrSkip(model, final)) {
                registry.registerDownloaded(model, final, model.sha256)
                clear(model.id)
                return
            }
            final.delete()
        }

        val usableSpace = ModelRepository.modelsDir(appContext).usableSpace
        if (usableSpace < model.fileSizeBytes + EXTRA_STORAGE_BYTES) {
            emit(model.id, DownloadPhase.ERROR, error = "Not enough free storage for this model")
            return
        }

        var offset = if (temp.exists()) temp.length() else 0L
        if (offset >= model.fileSizeBytes) {
            temp.delete()
            offset = 0L
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")

        val startedAt = System.currentTimeMillis()
        var downloaded = offset
        var total = model.fileSizeBytes
        var speed = 0L
        var lastProgressAt = startedAt
        var lastProgressBytes = offset

        upsert(
            DownloadRecord(
                catalogId = model.id,
                url = url,
                fileName = model.fileName,
                totalBytes = total,
                downloadedBytes = offset,
                state = "DOWNLOADING",
                expectedSha256 = model.sha256,
                startedAt = startedAt,
                updatedAt = startedAt,
            ),
        )
        emit(model.id, DownloadPhase.DOWNLOADING, offset, total)

        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Download failed (HTTP $code)")
            if (code == 200 && offset > 0) {
                temp.delete()
                offset = 0
                downloaded = 0
            }
            val contentLength = connection.contentLengthLong
            if (code == 206 && contentLength > 0) {
                total = offset + contentLength
            } else if (contentLength > 0) {
                total = contentLength
            }

            val buffer = ByteArray(BUFFER_BYTES)
            connection.inputStream.use { input ->
                FileOutputStream(temp, true).use { output ->
                    while (true) {
                        if (currentJob?.isActive == false) throw CancellationException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MS || downloaded >= total) {
                            val elapsed = (now - lastProgressAt).coerceAtLeast(1L)
                            speed = (downloaded - lastProgressBytes) * 1000L / elapsed
                            updateProgress(model.id, downloaded, total, speed)
                            lastProgressAt = now
                            lastProgressBytes = downloaded
                        }
                    }
                    output.flush()
                }
            }

            if (downloaded < total) throw IOException("Download ended early")
            if (temp.length() != model.fileSizeBytes) {
                throw IOException("Downloaded size does not match the catalogue")
            }
            emit(model.id, DownloadPhase.VERIFYING, downloaded, total)
            if (!verifyOrSkip(model, temp)) throw IntegrityException("Checksum mismatch")

            if (!ModelRepository.finalizeDownload(temp, final)) {
                throw IOException("Could not install the downloaded file")
            }
            registry.registerDownloaded(model, final, model.sha256)
            dao().delete(model.id)
            emit(model.id, DownloadPhase.DONE, downloaded, total)
            clear(model.id)
        } catch (cancellation: CancellationException) {
            updateState(
                catalogId = model.id,
                phase = DownloadPhase.PAUSED,
                downloadedBytes = temp.length().coerceAtMost(total),
                totalBytes = total,
                error = null,
            )
            throw cancellation
        } catch (t: Throwable) {
            if (t is IntegrityException && temp.exists()) temp.delete()
            updateState(
                catalogId = model.id,
                phase = DownloadPhase.ERROR,
                downloadedBytes = temp.length().coerceAtMost(total),
                totalBytes = total,
                error = readableError(t),
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun verifyOrSkip(model: CatalogModel, file: File): Boolean {
        val expected = model.sha256 ?: return true
        return ModelRepository.sha256Of(file).equals(expected, ignoreCase = true)
    }

    private suspend fun reconcileOnStart() = withContext(Dispatchers.IO) {
        for (record in dao().all()) {
            val model = ModelCatalog.byId(record.catalogId)
            val final = finalFile(record.fileName)
            val temp = tempFile(record.fileName)

            // Crash window: file was finalized/verified but the registry row was
            // not written. Adopt it instead of leaving a stale download record.
            if (model != null && final.exists() && final.length() == model.fileSizeBytes &&
                verifyOrSkip(model, final)
            ) {
                registry.registerDownloaded(model, final, model.sha256)
                dao().delete(record.catalogId)
                clear(record.catalogId)
                continue
            }
            if (model != null && temp.exists() && temp.length() == model.fileSizeBytes &&
                verifyOrSkip(model, temp) && ModelRepository.finalizeDownload(temp, final)
            ) {
                registry.registerDownloaded(model, final, model.sha256)
                dao().delete(record.catalogId)
                clear(record.catalogId)
                continue
            }
            if (final.exists()) final.delete()

            if (record.state == "DOWNLOADING" || record.state == "VERIFYING") {
                val actual = temp.length().coerceAtMost(record.totalBytes)
                updateState(
                    catalogId = record.catalogId,
                    phase = DownloadPhase.PAUSED,
                    downloadedBytes = actual,
                    totalBytes = record.totalBytes,
                    error = null,
                )
            } else {
                emit(
                    record.catalogId,
                    when (record.state) {
                        "PAUSED" -> DownloadPhase.PAUSED
                        "ERROR" -> DownloadPhase.ERROR
                        "QUEUED" -> DownloadPhase.QUEUED
                        else -> DownloadPhase.PAUSED
                    },
                    record.downloadedBytes,
                    record.totalBytes,
                    record.bytesPerSecond,
                    record.error,
                )
            }
        }
    }

    private suspend fun updateProgress(catalogId: String, downloaded: Long, total: Long, speed: Long) {
        if (catalogId in cancelledIds) return
        val record = dao().get(catalogId) ?: return
        dao().upsert(
            record.copy(
                downloadedBytes = downloaded,
                totalBytes = total,
                bytesPerSecond = speed,
                state = "DOWNLOADING",
                error = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        emit(catalogId, DownloadPhase.DOWNLOADING, downloaded, total, speed)
    }

    private suspend fun updateState(
        catalogId: String,
        phase: DownloadPhase,
        downloadedBytes: Long,
        totalBytes: Long,
        error: String?,
    ) {
        if (catalogId in cancelledIds) return
        val record = dao().get(catalogId)
        if (record != null) {
            dao().upsert(
                record.copy(
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    state = phase.name,
                    error = error,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        emit(catalogId, phase, downloadedBytes, totalBytes, error = error)
    }

    private suspend fun upsert(record: DownloadRecord) = dao().upsert(record)

    private fun emit(
        catalogId: String,
        phase: DownloadPhase,
        downloaded: Long = 0L,
        total: Long = 0L,
        speed: Long = 0L,
        error: String? = null,
    ) {
        _progress.value = _progress.value + (
            catalogId to DownloadProgress(catalogId, phase, downloaded, total, speed, error)
            )
    }

    private fun clear(catalogId: String) {
        _progress.value = _progress.value - catalogId
    }

    private fun readableError(t: Throwable): String = when (t) {
        is IOException -> t.message?.take(160) ?: "Network error"
        else -> t.message?.take(160) ?: "Download failed"
    }

    private fun finalFile(fileName: String): File = File(ModelRepository.modelsDir(appContext), fileName)

    private fun tempFile(fileName: String): File = File(ModelRepository.modelsDir(appContext), "$fileName.part")

    private fun dao() = AppDatabase.get(appContext).downloadDao()

    private fun ensureInitialized() {
        if (!initialized) throw IllegalStateException("ModelDownloadManager.initialize(context) was not called")
    }
}

