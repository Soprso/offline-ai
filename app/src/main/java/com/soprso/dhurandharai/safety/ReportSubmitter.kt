package com.soprso.dhurandharai.safety

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SubmitResult(
    val ok: Boolean,
    val reportReference: String? = null,
    val serverStatus: String? = null,
    val error: String? = null,
)

object ReportSubmitter {
    // Backend endpoint only (no direct Supabase access, no secrets in app).
    const val ENDPOINT: String = "https://shadow-studio-labs.netlify.app/api/ai-reports/submit"

    suspend fun submit(context: Context, report: SafetyReport): SubmitResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val payload = JSONObject()
                .put("idempotencyKey", report.idempotencyKey)
                .put("category", report.category)
                .put("reportedContent", report.reportedContent)
                .put("additionalDetails", report.details)
                .put("modelName", report.modelName)
                .put("appVersion", report.appVersion)
                .put("conversationReference", report.conversationId)
                .put("clientSubmittedAt", isoUtc(report.clientSubmittedAtMs ?: report.createdAtMs))

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()

            if (code in 200..299 && json?.optBoolean("ok") == true) {
                val ref = json.optString("reportReference").takeIf { it.isNotBlank() }
                val status = json.optString("status").takeIf { it.isNotBlank() } ?: "NEW"
                if (ref != null) {
                    SafetyReportStore.markSubmitted(context, report.id, ref, status)
                    SubmitResult(ok = true, reportReference = ref, serverStatus = status)
                } else {
                    val err = "Submission succeeded but no reference was returned."
                    SafetyReportStore.markFailed(context, report.id, err)
                    SubmitResult(ok = false, error = err)
                }
            } else {
                val err = json?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: when (code) {
                        429 -> "Too many submissions. Please try again later."
                        in 500..599 -> "Reporting service is temporarily unavailable."
                        else -> "Could not submit report (HTTP $code)."
                    }
                SafetyReportStore.markFailed(context, report.id, err)
                SubmitResult(ok = false, error = err)
            }
        } catch (t: Throwable) {
            val err = friendlyNetworkError(t)
            SafetyReportStore.markFailed(context, report.id, err)
            SubmitResult(ok = false, error = err)
        }
    }

    private fun friendlyNetworkError(t: Throwable): String {
        val msg = (t.message ?: "").lowercase(Locale.ROOT)
        return when {
            "unable to resolve host" in msg || "unknownhost" in msg -> "No network connection. Report saved locally; retry when online."
            "timeout" in msg -> "Connection timed out. Report saved locally; you can retry."
            "failed to connect" in msg || "connection refused" in msg -> "Could not reach reporting service. Report saved locally; you can retry."
            else -> "Could not submit report. It has been saved locally; you can retry."
        }
    }

    private fun isoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }
}
