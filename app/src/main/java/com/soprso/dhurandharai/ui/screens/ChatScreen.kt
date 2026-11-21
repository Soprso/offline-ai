package com.soprso.dhurandharai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.soprso.dhurandharai.chat.ChatViewModel
import com.soprso.dhurandharai.chat.MessageCopyPayload
import com.soprso.dhurandharai.chat.ReportSubmissionUiState
import com.soprso.dhurandharai.database.Message
import kotlinx.coroutines.delay

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

    val messages by viewModel.messages.collectAsState()
    val modelName by viewModel.currentModelName.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val error by viewModel.error.collectAsState()
    val performanceSummary by viewModel.performanceSummary.collectAsState()
    val reportSubmission by viewModel.reportSubmission.collectAsState()

    val listState = rememberLazyListState()
    val context = LocalContext.current
    val isDebugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

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
                            onCopy = ::copyMessage,
                            onReport = { reportTarget = it },
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
                onSend = {
                    if (input.isNotBlank() && viewModel.sendMessage(input)) {
                        input = ""
                    }
                },
                onStop = viewModel::stopGeneration,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
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
    onCopy: (String) -> Unit,
    onReport: (Message) -> Unit,
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
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "Message…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            minLines = 1,
            maxLines = 6,
        )
        IconButton(
            onClick = { if (generating) onStop() else onSend() },
            enabled = generating || input.isNotBlank(),
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
