package com.soprso.dhurandharai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.soprso.dhurandharai.safety.ReportSubmitState
import com.soprso.dhurandharai.safety.ReportSubmitter
import com.soprso.dhurandharai.safety.SafetyReport
import com.soprso.dhurandharai.safety.SafetyReportStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local report log and manual retry controls.
 *
 * Reports are always saved on-device. Only explicit user actions can submit/retry;
 * a report is marked SUBMITTED only after server confirmation.
 */
@Composable
fun SafetyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var submittingId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val reports = remember(refresh) { SafetyReportStore.all(context) }

    SimpleScreen(title = "Safety & Reports", onBack = onBack) {
        Text(
            "ON-DEVICE SAFETY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Prompts and replies are screened locally before and during generation. " +
                "Blocked prompts never reach the model; blocked replies are not shown " +
                "or saved. These are conservative on-device checks, not a guarantee.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(
            "REPORTS · ${reports.size} ON THIS DEVICE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Reports are saved locally. Only reports you explicitly submit are sent to " +
                "Shadow Studio Labs for safety review, and they are marked SUBMITTED only " +
                "after server confirmation. Pending/failed reports have not reached " +
                "Shadow Studio Labs and can be retried manually. No reports or " +
                "conversations are uploaded automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (statusMessage != null) {
            Text(
                statusMessage.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (reports.isEmpty()) {
            Text(
                "> no reports yet. Long-press an AI reply in chat and tap REPORT to review and submit.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                reports.forEach { report ->
                    ReportRow(
                        report = report,
                        busy = submittingId == report.id,
                        onRetry = {
                            if (submittingId != null) return@ReportRow
                            submittingId = report.id
                            statusMessage = "Submitting ${reportLabel(report)}…"
                            scope.launch {
                                val pending = SafetyReportStore.markPending(context, report.id) ?: report
                                val result = ReportSubmitter.submit(context, pending)
                                statusMessage = if (result.ok) {
                                    "REPORT SUBMITTED · ${result.reportReference ?: "reference unavailable"}"
                                } else {
                                    "REPORT NOT SUBMITTED · ${result.error ?: "Saved locally; retry when ready."}"
                                }
                                submittingId = null
                                refresh++
                            }
                        },
                        onDelete = {
                            SafetyReportStore.delete(context, report.id)
                            statusMessage = "Local report deleted."
                            refresh++
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        TextButton(onClick = { confirmClear = true }, enabled = reports.isNotEmpty()) {
            Text("CLEAR LOCAL REPORT LOG", style = MaterialTheme.typography.labelMedium)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Clear local report log?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Deletes locally stored report records from this device (including pending/failed/submitted local records). " +
                        "This does not delete reports already submitted to Shadow Studio Labs on the server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SafetyReportStore.clear(context)
                    statusMessage = "Local report log cleared."
                    refresh++
                    confirmClear = false
                }) {
                    Text("CLEAR", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("CANCEL")
                }
            },
        )
    }
}

@Composable
private fun ReportRow(
    report: SafetyReport,
    busy: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(report.createdAtMs))
    val stateLabel = when (report.submitState) {
        ReportSubmitState.SUBMITTED -> "SUBMITTED"
        ReportSubmitState.PENDING -> "PENDING"
        ReportSubmitState.FAILED -> "FAILED"
        ReportSubmitState.LOCAL -> "LOCAL"
    }
    val stateColor = when (report.submitState) {
        ReportSubmitState.SUBMITTED, ReportSubmitState.PENDING -> MaterialTheme.colorScheme.primary
        ReportSubmitState.FAILED -> MaterialTheme.colorScheme.error
        ReportSubmitState.LOCAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${report.category} · $time",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "status: $stateLabel${report.reportReference?.let { " · ref $it" } ?: ""}",
            style = MaterialTheme.typography.labelSmall,
            color = stateColor,
        )
        if (report.serverStatus != null && report.submitState == ReportSubmitState.SUBMITTED) {
            Text(
                "server status: ${report.serverStatus}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "model: ${report.modelName.ifBlank { "unknown" }}${if (report.appVersion.isNotBlank()) " · app ${report.appVersion}" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!report.lastError.isNullOrBlank() && report.submitState != ReportSubmitState.SUBMITTED) {
            Text(
                "last error: ${report.lastError}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (report.details.isNotBlank()) {
            Text(
                "details: ${report.details}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (report.reportedContent.isNotBlank()) {
            Text(
                "content: ${report.reportedContent.take(300)}${if (report.reportedContent.length > 300) "…" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (report.submitState == ReportSubmitState.PENDING || report.submitState == ReportSubmitState.FAILED) {
                TextButton(onClick = onRetry, enabled = !busy) {
                    Text(if (busy) "SUBMITTING…" else "RETRY SUBMISSION", style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Text("DELETE", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun reportLabel(report: SafetyReport): String =
    report.reportReference ?: report.category.ifBlank { "report" }
