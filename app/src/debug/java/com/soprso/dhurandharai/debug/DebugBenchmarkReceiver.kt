package com.soprso.dhurandharai.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.soprso.dhurandharai.inference.ModelEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Debug-only shell entry point for repeatable on-device measurements. */
class DebugBenchmarkReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_LOAD -> loadModel(app, intent)
                    ACTION_BENCH -> runBenchmark(app, intent)
                    else -> Log.w(TAG, "unknown action: ${intent.action}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "debug command failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun loadModel(app: Context, intent: Intent) {
        val file = File(intent.getStringExtra(EXTRA_PATH) ?: defaultModelPath(app))
        val contextLength = intent.getIntExtra(EXTRA_CONTEXT, 4096)
        val threads = intent.getIntExtra(EXTRA_THREADS, 4)
        if (!file.exists()) {
            Log.e(TAG, "load model missing: ${file.absolutePath}")
            return
        }
        val error = ModelEngine.load(file.absolutePath, BENCH_MODEL_NAME, contextLength)
        if (error == null && threads > 0) {
            ModelEngine.setThreads(threads)
        }
        Log.i(TAG, "load label=${intent.getStringExtra(EXTRA_LABEL) ?: "bench"} error=$error")
    }

    private suspend fun runBenchmark(app: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "bench"
        val threads = intent.getIntExtra(EXTRA_THREADS, 0)
        val maxTokens = intent.getIntExtra(EXTRA_MAX_TOKENS, 48)
        val reload = intent.getBooleanExtra(EXTRA_RELOAD, false)

        if (reload) {
            val file = File(intent.getStringExtra(EXTRA_PATH) ?: defaultModelPath(app))
            val error = ModelEngine.load(file.absolutePath, BENCH_MODEL_NAME, 4096)
            if (error != null) {
                Log.e(TAG, "bench label=$label reload failed: $error")
                return
            }
        }
        if (!ModelEngine.isLoaded) {
            Log.e(TAG, "bench label=$label no model loaded")
            return
        }
        if (threads > 0) {
            val error = ModelEngine.setThreads(threads)
            if (error != null) {
                Log.e(TAG, "bench label=$label setThreads failed: $error")
                return
            }
        }

        val messages = messagesFor(intent.getStringExtra(EXTRA_KIND) ?: KIND_SHORT)
        val wallStart = System.currentTimeMillis()
        val result = ModelEngine.generate(
            modelName = BENCH_MODEL_NAME,
            messages = messages,
            maxTokens = maxTokens,
            temperature = 0f,
            topK = 0,
            topP = 1f,
            minP = 0f,
            onToken = {},
        )
        val wallMs = System.currentTimeMillis() - wallStart
        val metrics = ModelEngine.lastMetrics.value
        Log.i(
            TAG,
            "bench label=$label threads=$threads wall_ms=$wallMs result_chars=${result?.length ?: -1} " +
                "native_error=${ModelEngine.lastError()} metrics=${metrics?.compactSummary() ?: "none"}",
        )
    }

    private fun messagesFor(kind: String): Array<String> = when (kind) {
        KIND_SHORT -> arrayOf("user", "Hi")
        KIND_MEDIUM -> arrayOf(
            "user",
            "Explain in three short paragraphs why a phone can run a small language model without internet access. " +
                "Mention weights, local memory and inference. Keep each paragraph under two sentences.",
        )
        KIND_LONG -> arrayOf(
            "user",
            buildString {
                repeat(8) { index ->
                    append("Section ${index + 1}: local inference keeps model weights on the device, uses quantized arithmetic, ")
                    append("and stores conversation context in a fixed cache. ")
                }
                append("Summarize the sections in four bullet points.")
            },
        )
        KIND_HISTORY -> arrayOf(
            "user", "Hi",
            "assistant", "Hello! How can I help you today?",
            "user", "What model are you?",
        )
        else -> arrayOf("user", "Hi")
    }

    private fun defaultModelPath(app: Context): String =
        File(app.filesDir, "models/qwen2.5-0.5b-instruct-q4_k_m.gguf").absolutePath

    companion object {
        private const val TAG = "OfflineAI-Bench"
        private const val BENCH_MODEL_NAME = "Qwen2.5 0.5B Instruct"

        const val ACTION_LOAD = "com.soprso.dhurandharai.debug.LOAD"
        const val ACTION_BENCH = "com.soprso.dhurandharai.debug.BENCH"

        private const val EXTRA_LABEL = "label"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_CONTEXT = "context"
        private const val EXTRA_THREADS = "threads"
        private const val EXTRA_MAX_TOKENS = "max_tokens"
        private const val EXTRA_RELOAD = "reload"
        private const val EXTRA_KIND = "kind"

        private const val KIND_SHORT = "short"
        private const val KIND_MEDIUM = "medium"
        private const val KIND_LONG = "long"
        private const val KIND_HISTORY = "history"
    }
}
