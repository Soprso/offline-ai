package com.soprso.dhurandharai.models

import android.content.Context
import com.soprso.dhurandharai.catalog.CatalogModel
import com.soprso.dhurandharai.database.AppDatabase
import com.soprso.dhurandharai.database.ModelMetadata
import java.io.File

/** Local registry of installed GGUF files. Never stores the binary in SQLite. */
class ModelRegistryRepository(context: Context) {

    private val dao = AppDatabase.get(context).modelDao()

    suspend fun upsert(model: ModelMetadata) = dao.upsert(model)

    suspend fun findByCatalogId(catalogId: String): ModelMetadata? = dao.findByCatalogId(catalogId)

    suspend fun findBySha256(sha256: String): ModelMetadata? =
        if (sha256.isBlank()) null else dao.findBySha256(sha256)

    suspend fun registerDownloaded(model: CatalogModel, file: File, sha256: String?): ModelMetadata {
        val metadata = ModelMetadata(
            id = model.id,
            catalogId = model.id,
            displayName = model.displayName,
            fileName = file.name,
            filePath = file.absolutePath,
            sizeBytes = file.length(),
            quantization = model.quantization,
            contextLength = model.recommendedContextLength,
            sha256 = sha256,
            sourceUrl = model.downloadUrl,
            source = model.sourceUrl,
            license = model.license,
            architecture = model.architecture,
            parameterCount = model.parameterCount,
            downloadedAt = System.currentTimeMillis(),
            lastUsedAt = null,
            status = "INSTALLED",
        )
        dao.upsert(metadata)
        return metadata
    }

    suspend fun touch(id: String) = dao.touch(id, System.currentTimeMillis())

    suspend fun setStatus(id: String, status: String) = dao.setStatus(id, status)

    suspend fun delete(id: String) = dao.delete(id)
}
