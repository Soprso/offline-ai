package com.soprso.dhurandharai.models

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soprso.dhurandharai.catalog.CatalogModel
import com.soprso.dhurandharai.catalog.ModelCatalog
import com.soprso.dhurandharai.compat.CompatibilityAssessment
import com.soprso.dhurandharai.compat.CompatibilityCategory
import com.soprso.dhurandharai.compat.CheckStatus
import com.soprso.dhurandharai.compat.GgufInfo
import com.soprso.dhurandharai.compat.GgufInspector
import com.soprso.dhurandharai.compat.ModelCompatibilityChecker
import com.soprso.dhurandharai.database.AppDatabase
import com.soprso.dhurandharai.database.ModelMetadata
import com.soprso.dhurandharai.device.DeviceInfoProvider
import com.soprso.dhurandharai.device.DeviceProfile
import com.soprso.dhurandharai.inference.EngineState
import com.soprso.dhurandharai.inference.ModelEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ModelUiStatus {
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    LOADED,
    ERROR,
}

data class ModelUiItem(
    val catalog: CatalogModel,
    val installed: ModelMetadata?,
    val info: GgufInfo?,
    val compatibility: CompatibilityAssessment,
    val status: ModelUiStatus,
    val download: DownloadProgress?,
    val recommended: Boolean,
    val contextLength: Int,
    val contextOptions: List<Int>,
)

private data class ModelBaseState(
    val installed: List<ModelMetadata>,
    val downloads: Map<String, DownloadProgress>,
    val engine: EngineState,
    val activePath: String?,
    val profile: DeviceProfile,
)

/** Single entry point for catalogue, downloads, registry and native loading. */
class ModelManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val registry = ModelRegistryRepository(application)

    private val _device = MutableStateFlow(DeviceInfoProvider.read(application))
    val device: StateFlow<DeviceProfile> = _device.asStateFlow()

    private val installedModels = db.modelDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engineState: StateFlow<EngineState> = ModelEngine.state
    val loadedPath: StateFlow<String?> = ModelEngine.loadedPathFlow

    private val _action = MutableStateFlow(ModelActionState())
    val action: StateFlow<ModelActionState> = _action.asStateFlow()

    private val _ggufInfo = MutableStateFlow<Map<String, GgufInfo>>(emptyMap())
    private val inspectedFiles = HashMap<String, GgufInfo>()
    private val _contextSelections = MutableStateFlow<Map<String, Int>>(emptyMap())

    private val baseState: StateFlow<ModelBaseState> = combine<
        List<ModelMetadata>,
        Map<String, DownloadProgress>,
        EngineState,
        String?,
        DeviceProfile,
        ModelBaseState,
        >(
        installedModels,
        ModelDownloadManager.progress,
        engineState,
        loadedPath,
        device,
    ) { installed, downloads, engine, activePath, profile ->
        ModelBaseState(installed, downloads, engine, activePath, profile)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ModelBaseState(emptyList(), emptyMap(), engineState.value, loadedPath.value, device.value),
    )

    val items: StateFlow<List<ModelUiItem>> = combine<
        ModelBaseState,
        Map<String, GgufInfo>,
        Map<String, Int>,
        List<ModelUiItem>,
        >(
        baseState,
        _ggufInfo,
        _contextSelections,
    ) { base, ggufInfo, contextSelections ->
        val installed = base.installed
        val downloads = base.downloads
        val activePath = base.activePath
        val profile = base.profile
        val entries = ModelCatalog.models + installed
            .filter { it.catalogId == null || ModelCatalog.byId(it.catalogId) == null }
            .map {
                ModelCatalog.fromImported(
                    id = it.id,
                    displayName = it.displayName,
                    fileName = it.fileName,
                    sizeBytes = it.sizeBytes,
                    architecture = it.architecture,
                    parameterCount = it.parameterCount,
                    quantization = it.quantization,
                    sha256 = it.sha256,
                )
            }

        entries.map { entry ->
            val model = installedFor(entry, installed)
            val info = resolvedInfo(entry, model, ggufInfo)
            val options = ModelCompatibilityChecker.contextOptions(info, entry.recommendedContextLength)
            val context = contextSelections[entry.id]
                ?.let { selected -> options.firstOrNull { it == selected } }
                ?: ModelCompatibilityChecker.defaultContext(info, entry.recommendedContextLength)
            val compatibility = ModelCompatibilityChecker.assess(
                model = entry,
                device = profile,
                info = info,
                contextLength = context,
                installed = model != null,
            )
            val download = downloads[entry.id]
            val status = when {
                model != null && activePath == model.filePath -> ModelUiStatus.LOADED
                model != null -> ModelUiStatus.INSTALLED
                download?.phase == DownloadPhase.DOWNLOADING ||
                    download?.phase == DownloadPhase.QUEUED -> ModelUiStatus.DOWNLOADING
                download?.phase == DownloadPhase.VERIFYING -> ModelUiStatus.VERIFYING
                download?.phase == DownloadPhase.ERROR -> ModelUiStatus.ERROR
                else -> ModelUiStatus.AVAILABLE
            }
            ModelUiItem(
                catalog = entry,
                installed = model,
                info = info,
                compatibility = compatibility,
                status = status,
                download = download,
                recommended = compatibility.category == CompatibilityCategory.RECOMMENDED &&
                    !entry.gated && entry.downloadUrl != null && model == null,
                contextLength = context,
                contextOptions = options,
            )
        }.sortedWith(
            compareByDescending<ModelUiItem> { it.recommended }
                .thenBy { it.catalog.displayName },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        ModelDownloadManager.initialize(application)
        viewModelScope.launch {
            installedModels.collect { installed ->
                val inspected = withContext(Dispatchers.IO) {
                    installed.mapNotNull { metadata ->
                        val file = File(
                            metadata.filePath.ifBlank {
                                ModelRepository.fileFor(getApplication(), metadata.fileName).absolutePath
                            },
                        )
                        if (!file.exists()) return@mapNotNull null
                        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
                        val cached = inspectedFiles[key]
                        val info = cached ?: GgufInspector.inspect(file).also { inspectedFiles[key] = it }
                        metadata.id to info
                    }.toMap()
                }
                _ggufInfo.value = inspected
            }
        }
    }

    fun clearAction() {
        _action.value = ModelActionState()
    }

    fun refreshDevice() {
        _device.value = DeviceInfoProvider.read(getApplication())
    }

    fun setContextLength(item: ModelUiItem, contextLength: Int) {
        if (contextLength !in item.contextOptions) return
        _contextSelections.value = _contextSelections.value + (item.catalog.id to contextLength)
    }

    fun download(item: ModelUiItem) {
        if (item.installed != null) {
            _action.value = ModelActionState(message = "Already installed", isError = true)
            return
        }
        if (item.catalog.gated) {
            _action.value = ModelActionState(
                message = "Accept this model's license on Hugging Face, then import the GGUF manually",
                isError = true,
            )
            return
        }
        _action.value = ModelActionState(message = "Downloading ${item.catalog.displayName}")
        ModelDownloadManager.start(item.catalog)
    }

    fun pauseDownload(item: ModelUiItem) = ModelDownloadManager.pause(item.catalog.id)

    fun resumeDownload(item: ModelUiItem) = ModelDownloadManager.start(item.catalog)

    fun cancelDownload(item: ModelUiItem) {
        ModelDownloadManager.cancel(item.catalog.id)
        _action.value = ModelActionState(message = "Download cancelled")
    }

    fun retryDownload(item: ModelUiItem) {
        _action.value = ModelActionState(message = "Retrying ${item.catalog.displayName}")
        ModelDownloadManager.retry(item.catalog)
    }

    fun loadModel(item: ModelUiItem) {
        val installed = item.installed ?: run {
            _action.value = ModelActionState(message = "Model is not installed", isError = true)
            return
        }
        if (engineState.value is EngineState.Generating) {
            _action.value = ModelActionState(message = "Stop generation before changing models", isError = true)
            return
        }

        viewModelScope.launch {
            // Memory can change between opening the sheet and tapping LOAD.
            val freshDevice = withContext(Dispatchers.IO) { DeviceInfoProvider.read(getApplication()) }
            _device.value = freshDevice
            val info = item.info ?: ModelCompatibilityChecker.infoFromProfile(item.catalog)
            val fresh = ModelCompatibilityChecker.assess(
                model = item.catalog,
                device = freshDevice,
                info = info,
                contextLength = item.contextLength,
                installed = true,
            )
            if (
                fresh.architectureStatus == CheckStatus.FAIL ||
                fresh.storageStatus == CheckStatus.FAIL
            ) {
                _action.value = ModelActionState(message = fresh.reason, isError = true)
                return@launch
            }

            val warnings = ArrayList<String>()
            if (fresh.category == CompatibilityCategory.NOT_RECOMMENDED) warnings += "not recommended for this device"
            if (fresh.minimumSystemRamBytes == null || fresh.recommendedSystemRamBytes == null) {
                warnings += "RAM estimate UNKNOWN"
            }
            if (!fresh.memory.kvComplete) warnings += "KV layout UNKNOWN"
            if (freshDevice.lowMemory) warnings += "device memory pressure"
            if (fresh.architectureStatus == CheckStatus.UNKNOWN) warnings += "architecture unverified"
            val warning = if (warnings.isEmpty()) "" else " · WARNING: ${warnings.joinToString("; ")}"

            _action.value = ModelActionState(true, "Loading ${installed.displayName}$warning")
            val path = installed.filePath.ifBlank {
                ModelRepository.fileFor(getApplication(), installed.fileName).absolutePath
            }
            if (!withContext(Dispatchers.IO) { java.io.File(path).exists() }) {
                _action.value = ModelActionState(message = "Model file is missing", isError = true)
                return@launch
            }
            if (ModelEngine.isLoaded) ModelEngine.unload()
            val context = item.contextLength
            val error = ModelEngine.load(path, installed.displayName, context)
            if (error == null) {
                registry.touch(installed.id)
                registry.setStatus(installed.id, "INSTALLED")
                _action.value = ModelActionState(message = "Loaded ${installed.displayName} · context $context$warning")
            } else {
                _action.value = ModelActionState(message = error, isError = true)
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            _action.value = ModelActionState(true, "Unloading model")
            ModelEngine.unload()
            _action.value = ModelActionState(message = "Model unloaded; file kept on device")
        }
    }

    fun deleteModel(item: ModelUiItem) {
        val installed = item.installed ?: return
        if (loadedPath.value == installed.filePath) {
            _action.value = ModelActionState(message = "Unload the model before deleting it", isError = true)
            return
        }
        viewModelScope.launch {
            _action.value = ModelActionState(true, "Deleting ${installed.displayName}")
            val deleted = ModelRepository.delete(getApplication(), installed)
            registry.delete(installed.id)
            _action.value = if (deleted) {
                ModelActionState(message = "Deleted ${installed.displayName}")
            } else {
                ModelActionState(message = "Registry entry removed; file was already gone")
            }
        }
    }

    fun importModel(uri: Uri) {
        if (_action.value.busy) return
        viewModelScope.launch {
            _action.value = ModelActionState(true, "Importing GGUF file")
            try {
                val model = ModelRepository.import(getApplication(), uri) { copied, total ->
                    _action.value = _action.value.copy(
                        progress = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else null,
                    )
                }
                model.sha256?.let { sha ->
                    if (registry.findBySha256(sha) != null) {
                        withContext(Dispatchers.IO) { ModelRepository.delete(getApplication(), model) }
                        _action.value = ModelActionState(message = "This exact model is already installed", isError = true)
                        return@launch
                    }
                }
                registry.upsert(model)
                _action.value = ModelActionState(message = "Imported ${model.displayName}")
            } catch (t: Throwable) {
                _action.value = ModelActionState(
                    message = t.message ?: "Import failed",
                    isError = true,
                )
            }
        }
    }

    private fun installedFor(entry: CatalogModel, installed: List<ModelMetadata>): ModelMetadata? =
        if (entry.id.startsWith("manual:")) {
            val id = entry.id.removePrefix("manual:")
            installed.firstOrNull { it.id == id }
        } else {
            installed.firstOrNull { it.catalogId == entry.id }
        }

    /**
     * Installed GGUF headers win when readable. Otherwise the catalogue's
     * revision-pinned source headers provide estimates; genuinely unknown
     * imports stay UNKNOWN instead of getting invented dimensions.
     */
    private fun resolvedInfo(
        entry: CatalogModel,
        model: ModelMetadata?,
        inspected: Map<String, GgufInfo>,
    ): GgufInfo? {
        val fromFile = model?.let { inspected[it.id] }
            ?.takeIf { !it.architecture.isNullOrBlank() }
        return fromFile ?: ModelCompatibilityChecker.infoFromProfile(entry)
    }
}
