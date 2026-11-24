package com.soprso.dhurandharai.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.soprso.dhurandharai.chat.ChatViewModel
import com.soprso.dhurandharai.chat.MessageCopyPayload
import com.soprso.dhurandharai.chat.ReportSubmissionUiState
import com.soprso.dhurandharai.database.Message
import com.soprso.dhurandharai.document.AttachmentMime
import com.soprso.dhurandharai.document.AttachmentStatus
import com.soprso.dhurandharai.document.DocumentAttachment
import com.soprso.dhurandharai.document.DocumentStorage
import com.soprso.dhurandharai.ui.screens.AttachmentComposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.io.File

private val ExamplePrompts = listOf(
    "Explain how a transformer model works",
    "Summarize the water cycle in three lines",
    "Write a short Python function to reverse a string",
)

/**
 * Main chat screen: top bar, conversation, empty state, composer.
 * Generation is streamed from the native engine; no response is fabricated.
 *
 * Controls use text glyphs instead of material-icons to keep the dependency
 * footprint at zero beyond the Compose BOM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenModels: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var elapsedSeconds by remember { mutableStateOf(0) }
    var reportTarget by remember { mutableStateOf<Message?>(null) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedUrisMutable by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showAttachmentChooser by remember { mutableStateOf(false) }

    val messages by viewModel.messages.collectAsState()
    val modelName by viewModel.currentModelName.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val error by viewModel.error.collectAsState()
    val performanceSummary by viewModel.performanceSummary.collectAsState()
    val reportSubmission by viewModel.reportSubmission.collectAsState()
    val messageAttachments by viewModel.attachmentViewModel.attachments.collectAsState()

    val listState = rememberLazyListState()
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val context = LocalContext.current
    val isDebugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    // Supported attachment MIME types (images + documents).
    val ALL_ATTACHMENT_MIME_TYPES = arrayOf(
        "image/jpeg", "image/jpg", "image/png", "image/webp",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain", "text/markdown", "text/csv",
    )

    /** Adds URIs to the selected attachments list, persisting read permission where possible. */
    fun addSelectedUris(newUris: List<Uri>) {
        for (uri in newUris) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // Non-fatal: permission may not be persistable on all providers.
            }
            selectedUrisMutable = selectedUrisMutable + uri
        }
        selectedUris = selectedUrisMutable
    }

    // Document picker launcher (SAF): all supported types, multiple selection,
    // directory navigation (Gallery, Downloads, Documents, internal storage, etc.).
    val pickDocumentsLauncher = rememberLauncherForActivityResult(
        StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@rememberLauncherForActivityResult
            val uris = mutableListOf<Uri>()
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            }
            data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) {
                addSelectedUris(uris)
            }
        }
    }

    // Photo Picker launcher (API 33+, gracefully falls back on older devices):
    // image-only selection for Gallery / Photos access.
    val pickVisualMediaLauncher = rememberLauncherForActivityResult(
        PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            addSelectedUris(listOf(uri))
        }
    }

    /** Safely launches the document picker with a resolvable type, guarding against
     *  ActivityNotFoundException on devices without a compatible DocumentsUI. */
    fun launchBrowseFiles() {
        showAttachmentChooser = false
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, ALL_ATTACHMENT_MIME_TYPES)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            pickDocumentsLauncher.launch(intent)
        } catch (e: Exception) {
            // Swallow rather than crash; user can still type and send text messages.
        }
    }

    /** Opens the Android Photo Picker for Gallery / Photos selection. */
    fun launchPhotos() {
        showAttachmentChooser = false
        try {
            pickVisualMediaLauncher.launch(
                PickVisualMediaRequest(PickVisualMedia.ImageOnly)
            )
        } catch (e: Exception) {
            // Swallow rather than crash.
        }
    }

    // Keep the newest message (or the live token stream) in view.
    LaunchedEffect(messages.size, streamingText) {
        val target = if (streamingText != null) messages.size else messages.lastIndex
        if (target >= 0) listState.scrollToItem(target)
    }

    LaunchedEffect(isGenerating) {
        elapsedSeconds = 0
        while (isGenerating) {
            delay(1_000L)
            elapsedSeconds++
        }
    }

    fun copyMessage(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Offline AI", style = MaterialTheme.typography.titleMedium)
                        Text(
                            modelName?.let { "loaded · $it" } ?: "no model loaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (modelName == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Text(
                            "≡",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    if (isGenerating) {
                        Text(
                            "generating ${elapsedSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = viewModel::stopGeneration) {
                            Text("■ STOP", style = MaterialTheme.typography.labelMedium)
                        }
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
                .imePadding(),
        ) {
            if (modelName == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "No model loaded.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Select a model to start chatting.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenModels) {
                        Text("MODELS →", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (messages.isEmpty() && streamingText == null) {
                EmptyState(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageRow(
                            message = msg,
                            attachments = messageAttachments[msg.conversationId]?.filter { it.messageId == msg.id } ?: emptyList(),
                            onCopy = ::copyMessage,
                            onReport = { reportTarget = it },
                            onRetry = { attachmentId ->
                                scope.launch {
                                    viewModel.attachmentViewModel.retryAttachment(attachmentId)
                                }
                            },
                        )
                    }
                    if (streamingText != null) {
                        item(key = "streaming") {
                            StreamingRow(streamingText.orEmpty(), isGenerating)
            }
        }
    }
}


            if (error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "! ${error}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    TextButton(onClick = viewModel::clearError) {
                        Text("✕", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (isDebugBuild && performanceSummary != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        performanceSummary.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    TextButton(onClick = viewModel::clearPerformanceSummary) {
                        Text("✕", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Composer(
                input = input,
                onInputChange = { input = it },
                generating = isGenerating,
                selectedUris = selectedUris,
                onAttachmentAdded = { showAttachmentChooser = true },
                onAttachmentRemoved = { index ->
                    selectedUrisMutable = selectedUrisMutable.filterIndexed { i, _ -> i != index }
                    selectedUris = selectedUrisMutable
                },
                onSend = {
                    val hasText = input.isNotBlank()
                    val hasAttachments = selectedUris.isNotEmpty()
                    if (hasText || hasAttachments) {
                        if (viewModel.sendMessage(input, selectedUris)) {
                            input = ""
                            selectedUrisMutable = emptyList()
                            selectedUris = emptyList()
                        }
                    }
                },
                onStop = viewModel::stopGeneration,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            // Attachment chooser dialog (Browse Files).
            if (showAttachmentChooser) {
                AlertDialog(
                    onDismissRequest = { showAttachmentChooser = false },
                    confirmButton = { },
                    title = {
                        Text(
                            text = "ATTACHMENTS",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { launchPhotos() },
                            ) {
                                Text(
                                    text = "PHOTOS & VIDEOS",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            TextButton(
                                onClick = { launchBrowseFiles() },
                            ) {
                                Text(
                                    text = "BROWSE FILES",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            TextButton(
                                onClick = { showAttachmentChooser = false },
                            ) {
                                Text(
                                    text = "CANCEL",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    val submission = reportSubmission
    reportTarget?.let { target ->
        ReportDialog(
            message = target,
            submitting = submission is ReportSubmissionUiState.Submitting,
            onSubmit = { category, details ->
                viewModel.submitReport(target, category, details)
            },
            onDismiss = {
                if (submission !is ReportSubmissionUiState.Submitting) {
                    reportTarget = null
                    viewModel.clearReportSubmissionState()
                }
            },
        )
    }

    when (submission) {
        is ReportSubmissionUiState.Success -> {
            AlertDialog(
                onDismissRequest = {
                    viewModel.clearReportSubmissionState()
                    reportTarget = null
                },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("REPORT SUBMITTED", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Thank you for helping us improve Offline AI.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Your report has been successfully submitted to Shadow Studio Labs.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "We sincerely apologise for the inconvenience. Our team will review the reported content and use your feedback to improve the app's safety.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Reference: ${submission.reference}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearReportSubmissionState()
                        reportTarget = null
                    }) {
                        Text("OK", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        }
        is ReportSubmissionUiState.Failure -> {
            AlertDialog(
                onDismissRequest = {
                    viewModel.clearReportSubmissionState()
                    reportTarget = null
                },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("REPORT NOT SUBMITTED", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            submission.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "The report has been saved locally and marked as pending/failed. It has not been marked as submitted. You can retry from Settings → Safety & Reports when ready.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.retryReport(submission.reportId)
                    }) {
                        Text("RETRY SUBMISSION", style = MaterialTheme.typography.labelMedium)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.clearReportSubmissionState()
                        reportTarget = null
                    }) {
                        Text("CLOSE", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        }
        else -> Unit
    }
}

@Composable
private fun ReportDialog(
    message: Message,
    submitting: Boolean,
    onSubmit: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val categories = remember {
        listOf(
            "Harmful or dangerous content",
            "Hate or harassment",
            "Sexual content",
            "Child safety concern",
            "Self-harm content",
            "Illegal or regulated content",
            "Misinformation or deception",
            "Privacy or personal data concern",
            "Offensive or inappropriate response",
            "Other safety concern",
        )
    }
    var selected by remember { mutableStateOf(categories.first()) }
    var details by remember { mutableStateOf("") }
    val preview = remember(message.content) {
        if (message.content.length > 1200) message.content.take(1200) + "\n…" else message.content
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("REPORT AI RESPONSE", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Reason",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                categories.forEach { category ->
                    TextButton(onClick = { if (!submitting) selected = category }, enabled = !submitting) {
                        Text(
                            "${if (selected == category) "[x]" else "[ ]"} $category",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected == category) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { if (it.length <= 2000) details = it },
                    label = { Text("DETAILS (OPTIONAL)", style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !submitting,
                    minLines = 2,
                    maxLines = 4,
                )
                Text(
                    "Content to be sent (preview)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    preview.ifBlank { "(empty response)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "By tapping SUBMIT REPORT, this report (selected reason, the reported AI response content shown above, your optional details, and limited technical metadata such as model/app version and a report reference context) will be sent to Shadow Studio Labs for safety review. Do not include sensitive personal information in details. Nothing is sent automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (submitting) {
                    Text(
                        "Submitting report…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(selected, details) },
                enabled = !submitting,
            ) {
                Text(
                    if (submitting) "SUBMITTING…" else "SUBMIT REPORT",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text("CANCEL", style = MaterialTheme.typography.labelMedium)
            }
        },
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "offline. local. private.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Download or import a model, load it in Models, then start chatting. Everything runs on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExamplePrompts.forEach { prompt ->
                Text(
                    "> $prompt",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: Message,
    attachments: List<DocumentAttachment> = emptyList(),
    onCopy: (String) -> Unit,
    onReport: (Message) -> Unit,
    onRetry: ((String) -> Unit)? = null,
) {
    val isUser = message.role == "user"
    var showActions by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500L)
            copied = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier.combinedClickable(
                onClick = { showActions = false },
                onLongClick = { showActions = !showActions },
            ),
        ) {
            Bubble(message.content, isUser = isUser)
        }
        if (attachments.isNotEmpty()) {
            AttachmentStrip(
                attachments = attachments,
                isUser = isUser,
                onRetry = onRetry,
            )
        }
        if (showActions) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    onCopy(MessageCopyPayload.from(message))
                    copied = true
                    showActions = false
                }) {
                    Text("COPY", style = MaterialTheme.typography.labelSmall)
                }
                if (!isUser) {
                    TextButton(onClick = {
                        onReport(message)
                        showActions = false
                    }) {
                        Text("REPORT", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (copied) {
                    Text(
                        "Copied",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else if (copied) {
            Text(
                "Copied",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun StreamingRow(text: String, isGenerating: Boolean) {
    val visible = when {
        text.isEmpty() && isGenerating -> "▌"
        isGenerating -> "$text▌"
        else -> text
    }
    Bubble(visible, isUser = false)
}

@Composable
private fun Bubble(text: String, isUser: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .background(
                    color = if (isUser) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(
                        topStart = 10.dp,
                        topEnd = 10.dp,
                        bottomStart = if (isUser) 10.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 10.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    generating: Boolean,
    selectedUris: List<Uri>,
    onAttachmentAdded: () -> Unit,
    onAttachmentRemoved: (Int) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Compact attachment previews inside the composer container.
        if (selectedUris.isNotEmpty()) {
            AttachmentComposer(
                uris = selectedUris,
                onRemove = onAttachmentRemoved,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }

        // One rounded rectangular input container: + button, message input, send button.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                )
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Plus (+) attachment button inside the container.
                IconButton(
                    onClick = onAttachmentAdded,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Message text input (BasicTextField so the outer Box provides the
                // single rounded border — no inner border between + and the text).
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 192.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    singleLine = false,
                    maxLines = 6,
                    decorationBox = { content ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            content()
                            if (input.isEmpty()) {
                                Text(
                                    text = "Message…",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                        }
                    },
                )

                // Send/stop button.
                IconButton(
                    onClick = { if (generating) onStop() else onSend() },
                    enabled = generating || input.isNotBlank() || selectedUris.isNotEmpty(),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = if (generating) "■" else "▶",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * Shows compact attachment chips inside a sent/received message bubble.
 * Images show a small thumbnail; documents show a type + filename chip.
 */
@Composable
private fun AttachmentStrip(
    attachments: List<DocumentAttachment>,
    isUser: Boolean,
    onRetry: ((String) -> Unit)? = null,
) {
    val color = if (isUser) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { att ->
            MessageAttachmentChip(att, color, onRetry = onRetry?.let { { it(att.id) } })
        }
    }
}

@Composable
private fun MessageAttachmentChip(
    att: DocumentAttachment,
    backgroundColor: Color,
    onRetry: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val isImage = att.mimeType.lowercase().startsWith("image/")
    val displayName = att.fileName.take(24)
    val typeLabel = att.mimeType.lowercase().takeIf { it.isNotEmpty() }?.substringAfterLast("/")?.uppercase()?.firstOrNull()?.toString() ?: "DOC"
    val chipText = if (isImage) "IMG" else typeLabel
    val statusText = when (att.status) {
        AttachmentStatus.Ready -> "✓"
        AttachmentStatus.Failed -> "⚠️"
        AttachmentStatus.Unsupported -> "❌"
        AttachmentStatus.Processing, AttachmentStatus.ExtractingText, AttachmentStatus.ExtractingImages,
        AttachmentStatus.RunningOcr, AttachmentStatus.PreparingContext, AttachmentStatus.PartiallyProcessed -> "⟳"
        AttachmentStatus.Selected, AttachmentStatus.Saved, AttachmentStatus.VisionRequired -> "•"
    }

    Row(
        modifier = Modifier
            .background(backgroundColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .size(width = 120.dp, height = 36.dp)
            .clickable {
                val file = DocumentStorage.resolvePath(context, att.localPath)
                if (file != null && file.isFile) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.fromFile(file), att.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = chipText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$statusText $displayName",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
        )
        if (att.status == AttachmentStatus.Failed && onRetry != null) {
            IconButton(onClick = onRetry) {
                Text(
                    text = "↻",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
