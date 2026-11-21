package com.soprso.dhurandharai.safety

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ReportSubmitState {
    LOCAL,      // legacy/local-only record (not queued for server submission)
    PENDING,    // saved locally, waiting for explicit/manual submission
    SUBMITTED,  // server-confirmed submission
    FAILED,     // last submission attempt failed; kept locally for manual retry
}

data class SafetyReport(
    val id: String,
    val idempotencyKey: String,
    val createdAtMs: Long,
    val category: String,
    val modelName: String,
    val appVersion: String,
    val conversationId: String,
    val details: String,
    val reportedContent: String,
    val submitState: ReportSubmitState,
    val reportReference: String? = null,
    val serverStatus: String? = null,
    val lastError: String? = null,
    val submittedAtMs: Long? = null,
    val clientSubmittedAtMs: Long? = null,
)

/**
 * Local store for AI response reports.
 *
 * Reports are always saved on-device first. Nothing is transmitted automatically.
 * A report is marked SUBMITTED only after explicit user action and server confirmation.
 */
object SafetyReportStore {

    private const val FILE_NAME = "safety_reports.json"
    private const val LEGACY_FILE_NAME = "safety_reports.tsv"
    private const val CONTENT_LIMIT = 4000
    private const val DETAILS_LIMIT = 2000
    private val lock = Any()

    fun all(context: Context): List<SafetyReport> = synchronized(lock) {
        ensureLoaded(context)
    }

    fun get(context: Context, id: String): SafetyReport? = all(context).firstOrNull { it.id == id }

    fun saveLocal(
        context: Context,
        category: String,
        modelName: String,
        appVersion: String,
        conversationId: String,
        details: String,
        reportedContent: String,
        queueForSubmission: Boolean,
    ): SafetyReport = synchronized(lock) {
        val now = System.currentTimeMillis()
        val report = SafetyReport(
            id = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString().replace("-", ""),
            createdAtMs = now,
            category = sanitize(category, 80),
            modelName = sanitize(modelName, 120),
            appVersion = sanitize(appVersion, 40),
            conversationId = sanitize(conversationId, 120),
            details = sanitize(details, DETAILS_LIMIT),
            reportedContent = sanitize(reportedContent, CONTENT_LIMIT),
            submitState = if (queueForSubmission) ReportSubmitState.PENDING else ReportSubmitState.LOCAL,
            clientSubmittedAtMs = if (queueForSubmission) now else null,
        )
        val reports = ensureLoaded(context).toMutableList()
        reports.add(0, report)
        write(context, reports)
        report
    }

    fun markPending(context: Context, id: String): SafetyReport? = update(context, id) { r ->
        if (r.submitState == ReportSubmitState.SUBMITTED) r else r.copy(
            submitState = ReportSubmitState.PENDING,
            lastError = null,
            clientSubmittedAtMs = System.currentTimeMillis(),
        )
    }

    fun markFailed(context: Context, id: String, error: String): SafetyReport? = update(context, id) { r ->
        if (r.submitState == ReportSubmitState.SUBMITTED) r else r.copy(
            submitState = ReportSubmitState.FAILED,
            lastError = sanitize(error, 300),
        )
    }

    fun markSubmitted(
        context: Context,
        id: String,
        reportReference: String,
        serverStatus: String?,
    ): SafetyReport? = update(context, id) { r ->
        r.copy(
            submitState = ReportSubmitState.SUBMITTED,
            reportReference = sanitize(reportReference, 80).ifBlank { r.reportReference },
            serverStatus = sanitize(serverStatus ?: "NEW", 40),
            lastError = null,
            submittedAtMs = System.currentTimeMillis(),
        )
    }

    fun delete(context: Context, id: String) = synchronized(lock) {
        val reports = ensureLoaded(context).filterNot { it.id == id }
        write(context, reports)
    }

    fun count(context: Context): Int = all(context).size

    fun clear(context: Context) = synchronized(lock) {
        file(context).delete()
        legacyFile(context).delete()
    }

    private fun update(context: Context, id: String, transform: (SafetyReport) -> SafetyReport): SafetyReport? = synchronized(lock) {
        val reports = ensureLoaded(context).toMutableList()
        val idx = reports.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized null
        val updated = transform(reports[idx])
        reports[idx] = updated
        write(context, reports)
        updated
    }

    private fun ensureLoaded(context: Context): List<SafetyReport> {
        val f = file(context)
        if (!f.exists()) {
            val migrated = readLegacy(context)
            if (migrated.isNotEmpty()) {
                write(context, migrated)
                // keep legacy file untouched? remove to avoid duplicate migration confusion
                legacyFile(context).delete()
                return migrated
            }
            return emptyList()
        }
        return runCatching {
            val arr = JSONArray(f.readText())
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    fromJson(o)?.let { add(it) }
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun readLegacy(context: Context): List<SafetyReport> {
        val f = legacyFile(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 7) return@mapNotNull null
            runCatching {
                SafetyReport(
                    id = decode(parts[0]).ifBlank { UUID.randomUUID().toString() },
                    idempotencyKey = UUID.randomUUID().toString().replace("-", ""),
                    createdAtMs = decode(parts[1]).toLongOrNull() ?: System.currentTimeMillis(),
                    category = sanitize(decode(parts[2]), 80),
                    modelName = sanitize(decode(parts[3]), 120),
                    appVersion = "",
                    conversationId = sanitize(decode(parts[4]), 120),
                    details = sanitize(decode(parts[5]), DETAILS_LIMIT),
                    reportedContent = sanitize(decode(parts[6]), CONTENT_LIMIT),
                    submitState = ReportSubmitState.LOCAL,
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAtMs }
    }

    private fun write(context: Context, reports: List<SafetyReport>) {
        val arr = JSONArray()
        reports.forEach { arr.put(toJson(it)) }
        file(context).writeText(arr.toString())
    }

    private fun toJson(r: SafetyReport): JSONObject = JSONObject()
        .put("id", r.id)
        .put("idempotencyKey", r.idempotencyKey)
        .put("createdAtMs", r.createdAtMs)
        .put("category", r.category)
        .put("modelName", r.modelName)
        .put("appVersion", r.appVersion)
        .put("conversationId", r.conversationId)
        .put("details", r.details)
        .put("reportedContent", r.reportedContent)
        .put("submitState", r.submitState.name)
        .put("reportReference", r.reportReference)
        .put("serverStatus", r.serverStatus)
        .put("lastError", r.lastError)
        .put("submittedAtMs", r.submittedAtMs)
        .put("clientSubmittedAtMs", r.clientSubmittedAtMs)

    private fun fromJson(o: JSONObject): SafetyReport? = runCatching {
        val state = runCatching { ReportSubmitState.valueOf(o.optString("submitState")) }
            .getOrDefault(ReportSubmitState.LOCAL)
        SafetyReport(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            idempotencyKey = o.optString("idempotencyKey").ifBlank { UUID.randomUUID().toString().replace("-", "") },
            createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
            category = sanitize(o.optString("category"), 80),
            modelName = sanitize(o.optString("modelName"), 120),
            appVersion = sanitize(o.optString("appVersion"), 40),
            conversationId = sanitize(o.optString("conversationId"), 120),
            details = sanitize(o.optString("details"), DETAILS_LIMIT),
            reportedContent = sanitize(o.optString("reportedContent"), CONTENT_LIMIT),
            submitState = state,
            reportReference = o.optString("reportReference").takeIf { it.isNotBlank() },
            serverStatus = o.optString("serverStatus").takeIf { it.isNotBlank() },
            lastError = o.optString("lastError").takeIf { it.isNotBlank() },
            submittedAtMs = if (o.has("submittedAtMs") && !o.isNull("submittedAtMs")) o.optLong("submittedAtMs") else null,
            clientSubmittedAtMs = if (o.has("clientSubmittedAtMs") && !o.isNull("clientSubmittedAtMs")) o.optLong("clientSubmittedAtMs") else null,
        )
    }.getOrNull()

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)
    private fun legacyFile(context: Context): File = File(context.applicationContext.filesDir, LEGACY_FILE_NAME)

    private fun sanitize(value: String?, max: Int): String {
        val v = (value ?: "").replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "").trim()
        return if (v.length > max) v.take(max) else v
    }

    private fun decode(value: String): String = runCatching {
        String(Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
    }.getOrDefault("")
}
