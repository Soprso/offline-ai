package com.soprso.dhurandharai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soprso.dhurandharai.compat.CompatibilityCategory
import com.soprso.dhurandharai.compat.CheckStatus
import com.soprso.dhurandharai.device.DeviceProfile
import com.soprso.dhurandharai.inference.EngineState
import com.soprso.dhurandharai.models.DownloadPhase
import com.soprso.dhurandharai.models.ModelActionState
import com.soprso.dhurandharai.models.ModelManagerViewModel
import com.soprso.dhurandharai.models.ModelUiItem
import com.soprso.dhurandharai.models.ModelUiStatus
import java.util.Locale

/**
 * Terminal-styled local model manager: curated catalogue, compatibility,
 * resumable downloads, registry and native load/unload lifecycle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLibraryScreen(
    viewModel: ModelManagerViewModel,
    onBack: () -> Unit,
) {
    val items by viewModel.items.collectAsState()
    val device by viewModel.device.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val loadedPath by viewModel.loadedPath.collectAsState()
    val action by viewModel.action.collectAsState()

    var selectedId by remember { mutableStateOf<String?>(null) }
    var pendingLoad by remember { mutableStateOf<ModelUiItem?>(null) }
    var pendingDelete by remember { mutableStateOf<ModelUiItem?>(null) }
    var pendingNotRecommendedDownload by remember { mutableStateOf<ModelUiItem?>(null) }

    val selected = items.firstOrNull { it.catalog.id == selectedId }
    val recommended = items.filter { it.recommended }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importModel(uri)
    }

    val canChange = !action.busy && engineState !is EngineState.Generating

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleMedium)
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::refreshDevice,
                        enabled = !action.busy,
                    ) {
                        Text("REFRESH", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !action.busy,
                    ) {
                        Text("+ ADD", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EngineStatus(
                state = engineState,
                canUnload = canChange && loadedPath != null,
                onUnload = viewModel::unloadModel,
            )

            ActionStatus(action = action, onClear = viewModel::clearAction)

            DeviceLine(device = device)

            Text(
                "Models are stored on your device. Internet is used only to download models; AI inference runs locally.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "recommended-header") { SectionLabel("RECOMMENDED") }
                if (recommended.isEmpty()) {
                    item(key = "recommended-empty") {
                        Text(
                            "No model fits the safe memory budget right now. Close other apps and refresh.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                } else {
                    items(recommended, key = { "rec-${it.catalog.id}" }) { item ->
                        ModelRow(item = item, onClick = { selectedId = item.catalog.id })
                    }
                }

                item(key = "catalog-header") { SectionLabel("AVAILABLE MODELS") }
                items(items, key = { "all-${it.catalog.id}" }) { item ->
                    ModelRow(item = item, onClick = { selectedId = item.catalog.id })
                }
            }
        }
    }

    if (selected != null) {
        ModelDetailSheet(
            item = selected,
            device = device,
            canChange = canChange,
            onDismiss = { selectedId = null },
            onDownload = {
                if (selected.compatibility.category == CompatibilityCategory.NOT_RECOMMENDED) {
                    pendingNotRecommendedDownload = selected
                } else {
                    viewModel.download(selected)
                }
            },
            onPause = { viewModel.pauseDownload(selected) },
            onResume = { viewModel.resumeDownload(selected) },
            onCancel = { viewModel.cancelDownload(selected); selectedId = null },
            onRetry = { viewModel.retryDownload(selected) },
            onLoad = {
                if (loadedPath != null && selected.status != ModelUiStatus.LOADED) {
                    pendingLoad = selected
                } else {
                    viewModel.loadModel(selected)
                }
            },
            onUnload = { viewModel.unloadModel() },
            onDelete = { pendingDelete = selected },
            onContextChange = { context -> viewModel.setContextLength(selected, context) },
        )
    }

    pendingNotRecommendedDownload?.let { item ->
        NotRecommendedDownloadDialog(
            item = item,
            onConfirm = {
                viewModel.download(item)
                pendingNotRecommendedDownload = null
            },
            onDismiss = { pendingNotRecommendedDownload = null },
        )
    }

    pendingLoad?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingLoad = null },
            title = { Text("Replace loaded model?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Only one model can be loaded at a time. ${item.catalog.displayName} will be unloaded before ${item.installed?.displayName ?: "the selected model"} is loaded. The file is kept on disk.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.loadModel(item)
                    pendingLoad = null
                    selectedId = null
                }) { Text("REPLACE", style = MaterialTheme.typography.labelMedium) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLoad = null }) {
                    Text("CANCEL", style = MaterialTheme.typography.labelMedium)
                }
            },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete model?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Delete ${item.installed?.displayName ?: item.catalog.displayName} and its GGUF file from this device? This cannot be undone. Unload it first if it is currently loaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteModel(item)
                    pendingDelete = null
                    selectedId = null
                }) { Text("DELETE", style = MaterialTheme.typography.labelMedium) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("CANCEL", style = MaterialTheme.typography.labelMedium)
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun DeviceLine(device: DeviceProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            "DEVICE  ${device.manufacturer} ${device.model}".trim(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "ANDROID ${device.androidVersion} (SDK ${device.sdkVersion}) · ABI ${device.primaryAbi}" +
                if (device.supportedAbis.size > 1) " [${device.supportedAbis.joinToString(",")}]" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "RAM ${formatSize(device.totalMemoryBytes)} total · ${formatSize(device.availableMemoryBytes)} available" +
                if (device.lowMemory || device.lowRamDevice) " · PRESSURE" else "",
            style = MaterialTheme.typography.labelSmall,
            color = if (device.lowMemory || device.lowRamDevice) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            "STORAGE ${formatSize(device.availableModelStorageBytes)} model dir free · " +
                "${formatSize(device.availableStorageBytes)} app data free",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EngineStatus(
    state: EngineState,
    canUnload: Boolean,
    onUnload: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ENGINE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    engineLabel(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state is EngineState.Error) {
                        MaterialTheme.colorScheme.error
                    } else if (state is EngineState.Ready || state is EngineState.Generating) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onUnload, enabled = canUnload) {
                Text("UNLOAD", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (state is EngineState.Ready) {
            Text(
                "context ${state.contextLength}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ActionStatus(
    action: ModelActionState,
    onClear: () -> Unit,
) {
    if (action.busy) {
        Text(
            action.label ?: "Working…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (action.progress != null) {
            LinearProgressIndicator(
                progress = { action.progress!! },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        }
    } else if (action.message != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${if (action.isError) "! " else "> "}${action.message}",
                style = MaterialTheme.typography.labelSmall,
                color = if (action.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            TextButton(onClick = onClear) {
                Text("✕", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ModelRow(
    item: ModelUiItem,
    onClick: () -> Unit,
) {
    val model = item.catalog
    val progress = item.download
    val showProgress = item.status == ModelUiStatus.DOWNLOADING ||
        item.status == ModelUiStatus.VERIFYING ||
        progress?.phase == DownloadPhase.PAUSED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.status == ModelUiStatus.LOADED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLabel(item),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(item),
            )
        }
        Text(
            "${model.family} · ${model.parameterCount} · ${model.quantization} · ${model.architecture}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Download ${formatSize(model.fileSizeBytes)} · " +
                "Min RAM ${ramLabel(item.compatibility.minimumSystemRamBytes)} · " +
                "Rec RAM ${ramLabel(item.compatibility.recommendedSystemRamBytes)} · " +
                compatibilityLabel(item.compatibility.category),
            style = MaterialTheme.typography.labelSmall,
            color = compatibilityColor(item.compatibility.category),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.recommended) {
            Text(
                "★ PICK",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showProgress && progress != null && progress.totalBytes > 0) {
            val fraction = (progress.downloadedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
            Text(
                "${(fraction * 100).toInt()}% · ${formatSize(progress.downloadedBytes)} / " +
                    "${formatSize(progress.totalBytes)} · ${formatSpeed(progress.bytesPerSecond)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (item.status == ModelUiStatus.ERROR && progress?.error != null) {
            Text(
                "! ${progress.error}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDetailSheet(
    item: ModelUiItem,
    device: DeviceProfile,
    canChange: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    onContextChange: (Int) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val model = item.catalog
        val progress = item.download
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(model.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${model.publisher} · ${model.family}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                model.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "STATUS  ${statusLabel(item)}",
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(item),
            )

            DetailRow("PARAMETERS", model.parameterCount)
            DetailRow("ARCHITECTURE", item.info?.architecture ?: model.architecture)
            DetailRow("QUANTIZATION", model.quantization)
            DetailRow("MODEL DOWNLOAD SIZE", formatSize(model.fileSizeBytes))
            DetailRow("MINIMUM SYSTEM RAM", ramLabel(item.compatibility.minimumSystemRamBytes))
            DetailRow("RECOMMENDED SYSTEM RAM", ramLabel(item.compatibility.recommendedSystemRamBytes))
            DetailRow("LICENSE", model.license)

            Text(
                "CONTEXT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item.contextOptions.forEach { context ->
                    TextButton(
                        onClick = { onContextChange(context) },
                        enabled = canChange,
                    ) {
                        Text(
                            if (context == item.contextLength) "[$context]" else "$context",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (context == item.contextLength) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            Text(
                "Changing context recalculates KV cache, estimated peak and compatibility. The same value is passed to the native loader.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "MEMORY BREAKDOWN (ESTIMATE)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val memory = item.compatibility.memory
            DetailRow("WEIGHTS", memory.weightsBytes?.let(::formatSize) ?: "UNKNOWN")
            DetailRow("WEIGHT SOURCE", memory.weightsSource)
            DetailRow(
                "KV CACHE",
                "${memory.kvCacheBytes?.let(::formatSize) ?: "UNKNOWN"} · ${memory.kvDtype} · ${item.contextLength} ctx",
            )
            DetailRow(
                "KV LAYOUT",
                if (memory.kvComplete) "KNOWN" else "UNKNOWN / full-context upper bound",
            )
            DetailRow("RUNTIME", memory.runtimeBytes?.let { "~${formatSize(it)}" } ?: "UNKNOWN")
            DetailRow(
                "COMPUTE BUFFERS",
                memory.inferenceBuffersBytes?.let { "~${formatSize(it)}" } ?: "UNKNOWN",
            )
            DetailRow("SAFETY MARGIN", memory.marginBytes?.let { "~${formatSize(it)}" } ?: "UNKNOWN")
            DetailRow(
                "EST. INFERENCE PEAK",
                memory.inferencePeakBytes?.let { "~${formatSize(it)}" } ?: "UNKNOWN",
                valueColor = compatibilityColor(item.compatibility.category),
            )
            DetailRow("OS/APP OVERHEAD (EST.)", memory.systemOverheadBytes?.let { "~${formatSize(it)}" } ?: "UNKNOWN")
            DetailRow("MINIMUM SYSTEM RAM", ramLabel(item.compatibility.minimumSystemRamBytes))
            DetailRow("RECOMMENDED SYSTEM RAM", ramLabel(item.compatibility.recommendedSystemRamBytes))
            DetailRow("TOTAL RAM", formatSize(device.totalMemoryBytes))
            DetailRow(
                "AVAILABLE RAM",
                formatSize(device.availableMemoryBytes) +
                    if (device.lowMemory || device.lowRamDevice) " · PRESSURE" else "",
                valueColor = if (device.lowMemory || device.lowRamDevice) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            DetailRow("SAFE BUDGET (INFERENCE)", formatSize(item.compatibility.safeBudgetBytes))
            Text(
                "Minimum RAM indicates the estimated amount for a potentially usable inference session (not model weights alone). Recommended RAM adds comfortable headroom.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${memory.detail} Source: ${memory.sourceLabel}.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            memory.notes.take(3).forEach { note ->
                Text(
                    "· $note",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DetailRow(
                "ARCHITECTURE CHECK",
                "${checkLabel(item.compatibility.architectureStatus)} · ${item.compatibility.architectureReason}",
                valueColor = checkColor(item.compatibility.architectureStatus),
            )
            DetailRow(
                "STORAGE CHECK",
                "${checkLabel(item.compatibility.storageStatus)} · ${item.compatibility.storageReason}",
                valueColor = checkColor(item.compatibility.storageStatus),
            )
            DetailRow(
                "SPEED (NOT MEASURED)",
                "${checkLabel(item.compatibility.performanceStatus)} · ${item.compatibility.performanceReason}",
                valueColor = checkColor(item.compatibility.performanceStatus),
            )
            DetailRow("METADATA", item.info?.metadataSource ?: "unavailable")
            if (model.sourceUrl.isNotBlank()) DetailRow("SOURCE", model.sourceUrl)
            if (model.revision != null) DetailRow("REVISION", model.revision!!.take(16))
            DetailRow(
                "COMPATIBILITY",
                compatibilityLabel(item.compatibility.category),
                valueColor = compatibilityColor(item.compatibility.category),
            )
            Text(
                item.compatibility.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            item.installed?.let { installed ->
                DetailRow("FILE", installed.fileName)
                DetailRow("CHECKSUM", installed.sha256?.take(16) ?: "not recorded")
                DetailRow("INSTALLED", formatTimestamp(installed.downloadedAt))
                DetailRow("LAST USED", formatTimestamp(installed.lastUsedAt))
            }

            if (model.gated) {
                Text(
                    "This repository is gated. Accept its license on Hugging Face, then use + ADD to import the GGUF manually. Direct download is disabled.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (progress?.error != null) {
                Text(
                    "! ${progress.error}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (progress?.phase == DownloadPhase.PAUSED) {
                Text(
                    "Paused at ${formatSize(progress.downloadedBytes)} / ${formatSize(progress.totalBytes)}. Resume continues with HTTP Range when supported.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.status == ModelUiStatus.LOADED) {
                Text(
                    "Unload before deleting. Unloading keeps the file on disk.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    item.status == ModelUiStatus.LOADED -> {
                        SheetButton("UNLOAD", enabled = canChange, onClick = onUnload)
                    }
                    item.status == ModelUiStatus.INSTALLED -> {
                        SheetButton("LOAD", enabled = canChange, onClick = onLoad)
                        SheetButton("DELETE", enabled = canChange, danger = true, onClick = onDelete)
                    }
                    progress?.phase == DownloadPhase.PAUSED -> {
                        SheetButton("RESUME", enabled = canChange, onClick = onResume)
                        SheetButton("CANCEL", enabled = canChange, onClick = onCancel)
                    }
                    item.status == ModelUiStatus.DOWNLOADING -> {
                        SheetButton("PAUSE", enabled = canChange, onClick = onPause)
                        SheetButton("CANCEL", enabled = canChange, onClick = onCancel)
                    }
                    item.status == ModelUiStatus.VERIFYING -> {
                        SheetButton("CANCEL", enabled = canChange, onClick = onCancel)
                    }
                    item.status == ModelUiStatus.ERROR -> {
                        SheetButton("RETRY", enabled = canChange, onClick = onRetry)
                        SheetButton("CANCEL", enabled = canChange, onClick = onCancel)
                    }
                    else -> {
                        SheetButton(
                            label = if (model.gated) "LICENSE REQUIRED" else "DOWNLOAD",
                            enabled = canChange && !model.gated,
                            onClick = onDownload,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotRecommendedDownloadDialog(
    item: ModelUiItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HARDWARE COMPATIBILITY WARNING", style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                "This model is not recommended for your device based on its estimated hardware requirements.\n\n" +
                    "Model download size: ${formatSize(item.catalog.fileSizeBytes)}\n" +
                    "Minimum system RAM (estimate): ${ramLabel(item.compatibility.minimumSystemRamBytes)}\n" +
                    "Recommended system RAM (estimate): ${ramLabel(item.compatibility.recommendedSystemRamBytes)}\n\n" +
                    "Running this model may cause slow inference, excessive memory consumption, application instability, out-of-memory errors or device overheating.\n\n" +
                    "Do you still want to download it?",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("DOWNLOAD ANYWAY", style = MaterialTheme.typography.labelMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", style = MaterialTheme.typography.labelMedium)
            }
        },
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun ramLabel(bytes: Long?): String = bytes?.let { "~${formatSize(it)} (est.)" } ?: "UNKNOWN"

@Composable
private fun SheetButton(
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

private fun statusLabel(item: ModelUiItem): String = when {
    item.status == ModelUiStatus.LOADED -> "LOADED"
    item.status == ModelUiStatus.INSTALLED -> "INSTALLED"
    item.status == ModelUiStatus.VERIFYING -> "VERIFYING"
    item.status == ModelUiStatus.DOWNLOADING -> "DOWNLOADING"
    item.status == ModelUiStatus.ERROR -> "ERROR"
    item.download?.phase == DownloadPhase.PAUSED -> "AVAILABLE · paused"
    item.catalog.gated -> "AVAILABLE · gated"
    else -> "AVAILABLE"
}

@Composable
private fun statusColor(item: ModelUiItem): androidx.compose.ui.graphics.Color = when {
    item.status == ModelUiStatus.LOADED || item.status == ModelUiStatus.INSTALLED ->
        MaterialTheme.colorScheme.primary
    item.status == ModelUiStatus.ERROR -> MaterialTheme.colorScheme.error
    item.status == ModelUiStatus.DOWNLOADING || item.status == ModelUiStatus.VERIFYING ->
        MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun compatibilityLabel(category: CompatibilityCategory): String = when (category) {
    CompatibilityCategory.RECOMMENDED -> "RECOMMENDED"
    CompatibilityCategory.SUPPORTED -> "SUPPORTED"
    CompatibilityCategory.NOT_RECOMMENDED -> "NOT RECOMMENDED"
    CompatibilityCategory.UNKNOWN -> "UNKNOWN"
}

@Composable
private fun compatibilityColor(category: CompatibilityCategory): androidx.compose.ui.graphics.Color = when (category) {
    CompatibilityCategory.RECOMMENDED -> MaterialTheme.colorScheme.primary
    CompatibilityCategory.SUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
    CompatibilityCategory.NOT_RECOMMENDED -> MaterialTheme.colorScheme.error
    CompatibilityCategory.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun checkLabel(status: CheckStatus): String = when (status) {
    CheckStatus.OK -> "OK"
    CheckStatus.UNKNOWN -> "UNKNOWN"
    CheckStatus.FAIL -> "FAIL"
}

@Composable
private fun checkColor(status: CheckStatus): androidx.compose.ui.graphics.Color = when (status) {
    CheckStatus.OK -> MaterialTheme.colorScheme.primary
    CheckStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    CheckStatus.FAIL -> MaterialTheme.colorScheme.error
}

private fun engineLabel(state: EngineState): String = when (state) {
    EngineState.Unloaded -> "unloaded"
    is EngineState.Loading -> "loading ${state.path.substringAfterLast('/')}"
    is EngineState.Ready -> "loaded · ${state.modelName}"
    is EngineState.Generating -> "generating · ${state.modelName}"
    is EngineState.Error -> "error · ${state.message}"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= (1L shl 30) -> String.format(Locale.ROOT, "%.2f GiB", bytes / (1L shl 30).toDouble())
    bytes >= (1L shl 20) -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1L shl 20).toDouble())
    bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0 -> "—"
    bytesPerSecond >= (1L shl 20) ->
        String.format(Locale.ROOT, "%.1f MiB/s", bytesPerSecond / (1L shl 20).toDouble())
    else -> String.format(Locale.ROOT, "%.0f KiB/s", bytesPerSecond / 1024.0)
}

private fun formatTimestamp(timestamp: Long?): String = if (timestamp == null) {
    "never"
} else {
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(java.util.Date(timestamp))
}
