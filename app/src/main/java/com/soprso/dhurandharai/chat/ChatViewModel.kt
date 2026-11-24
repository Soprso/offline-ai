package com.soprso.dhurandharai.chat

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soprso.dhurandharai.database.AppDatabase
import com.soprso.dhurandharai.database.Conversation
import com.soprso.dhurandharai.database.Message
import com.soprso.dhurandharai.document.AttachmentViewModel
import com.soprso.dhurandharai.document.DocumentAttachment
import com.soprso.dhurandharai.document.DocumentStorage
import com.soprso.dhurandharai.imagegen.ImageGenerationResult
import com.soprso.dhurandharai.inference.EngineState
import com.soprso.dhurandharai.inference.ModelEngine
import com.soprso.dhurandharai.safety.ContentSafety
import com.soprso.dhurandharai.safety.ReportSubmitter
import com.soprso.dhurandharai.safety.SafetyCategory
import com.soprso.dhurandharai.safety.SafetyReportStore
import com.soprso.dhurandharai.safety.SafetyVerdict
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReportSubmissionUiState {
    data object Idle : ReportSubmissionUiState
    data object Submitting : ReportSubmissionUiState
    data class Success(val reference: String) : ReportSubmissionUiState
    data class Failure(val reportId: String, val message: String) : ReportSubmissionUiState
}

/**
 * Chat state, persistence and generation.
 *
 * Streaming model output is held in [streamingText] for immediate display and
 * persisted to Room only when the turn finishes (complete, cancelled or
 * failed) — this avoids a database write per token while still guaranteeing
 * that history survives restarts.
 *
 * No output is ever fabricated: if no model is loaded, nothing is generated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    val attachmentViewModel: AttachmentViewModel = AttachmentViewModel(application)

    /** Currently open conversation, null until the first message is sent. */
    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = db.conversationDao()
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages: StateFlow<List<Message>> = _activeConversationId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else db.messageDao().observeForConversation(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Only a model that llama.cpp has actually loaded is shown as active. */
    val currentModelName: StateFlow<String?> = ModelEngine.state
        .map { engine ->
            when (engine) {
                is EngineState.Ready -> engine.modelName
                is EngineState.Generating -> engine.modelName
                else -> null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Native engine lifecycle (loading / ready / generating / error). */
    val engineState: StateFlow<EngineState> = ModelEngine.state

    private val submissionGuard = SubmissionGuard()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** Assistant text currently being streamed, null when idle. */
    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    /** Transient user-facing error (no model loaded, context overflow, ...). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Debug-only measured summary for the last completed request. */
    private val _performanceSummary = MutableStateFlow<String?>(null)
    val performanceSummary: StateFlow<String?> = _performanceSummary.asStateFlow()

    private val _reportSubmission = MutableStateFlow<ReportSubmissionUiState>(ReportSubmissionUiState.Idle)
    val reportSubmission: StateFlow<ReportSubmissionUiState> = _reportSubmission.asStateFlow()

    /** Transient confirmation for image save/delete operations. */
    private val _imageNotice = MutableStateFlow<String?>(null)
    val imageNotice: StateFlow<String?> = _imageNotice.asStateFlow()

    init {
        // Attachments (including persisted images) must be observed whenever a
        // saved conversation is opened, not only for the newly created one.
        viewModelScope.launch {
            _activeConversationId.collect { id ->
                if (id != null) attachmentViewModel.observeAttachments(id)
            }
        }
    }

    fun clearImageNotice() {
        _imageNotice.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun clearPerformanceSummary() {
        _performanceSummary.value = null
    }

    fun clearReportSubmissionState() {
        _reportSubmission.value = ReportSubmissionUiState.Idle
    }

    /** Opens an existing conversation (from History). */
    fun openConversation(id: String) {
        _activeConversationId.value = id
        _error.value = null
    }

    /** Starts a fresh conversation; the old one is kept in the database. */
    fun newConversation() {
        _activeConversationId.value = null
        _error.value = null
    }

    /**
     * Persists the user's message with optional attachments, then runs generation
     * if a model is loaded. Document context from attachments is prepended to the
     * prompt for generation but the original text is persisted as-is.
     * Returns true only when this call accepted the submission.
     *
     * [uris] are the content URIs selected by the user in this composition. They are
     * copied to local storage, processed (text extraction / OCR), and linked to the
     * persisted message before generation runs.
     */
    fun sendMessage(text: String, uris: List<Uri> = emptyList()): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && uris.isEmpty()) return false
        _error.value = null
        _performanceSummary.value = null

        val promptVerdict = ContentSafety.screenPrompt(trimmed)
        if (promptVerdict.blocked) {
            _error.value = "SAFETY · ${promptVerdict.category?.label ?: "BLOCKED"} — " +
                ContentSafety.PROMPT_REFUSAL
            return false
        }
        if (!submissionGuard.tryAcquire()) return false

        _isGenerating.value = true
        viewModelScope.launch {
            try {
                sendInternal(trimmed, uris)
            } catch (t: Throwable) {
                _error.value = friendlyError(t.message ?: "Message could not be sent")
            } finally {
                _streamingText.value = null
                _isGenerating.value = false
                submissionGuard.release()
            }
        }
        return true
    }

    private suspend fun sendInternal(text: String, uris: List<Uri>) {
        val now = System.currentTimeMillis()
        val existingId = _activeConversationId.value
        val conversationId = existingId ?: UUID.randomUUID().toString()

        if (existingId == null) {
            db.conversationDao().upsert(
                Conversation(
                    id = conversationId,
                    title = text.take(48),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        _activeConversationId.value = conversationId

        // Process attachments: copy to local storage, extract text / OCR, persist metadata.
        val attachmentIds = if (uris.isNotEmpty()) {
            attachmentViewModel.addAttachments(conversationId, null, uris)
        } else emptyList()

        // Persist the user message (original text only, no document context).
        val messageId = UUID.randomUUID().toString()
        db.messageDao().insert(
            Message(
                id = messageId,
                conversationId = conversationId,
                role = "user",
                content = text,
                createdAt = now,
            ),
        )
        db.conversationDao().touchUpdatedAt(conversationId, now)

        // Link attachments to this message so they appear in conversation history.
        if (attachmentIds.isNotEmpty()) {
            attachmentViewModel.linkAttachmentsToMessage(attachmentIds, messageId)
        }

        // Build document context from all ready attachments in conversation for follow-ups.
        val docContext = attachmentViewModel.buildContextForConversation(conversationId)
            .takeIf { it.isNotBlank() }

        // Build generation messages: history already includes this user message.
        generateReply(conversationId, docContext)
    }

    private suspend fun generateReply(
        conversationId: String,
        documentContext: String?,
    ) {
        if (!ModelEngine.isLoaded) {
            _error.value = "No model loaded. Open Models and select one first."
            return
        }

        val history = db.messageDao().getForConversation(conversationId)
        val flat = ArrayList<String>(history.size * 2)
        history.forEach { msg ->
            flat.add(msg.role)
            if (msg.role == "user" && msg.id == history.lastOrNull { it.role == "user" }?.id && !documentContext.isNullOrBlank()) {
                flat.add("$documentContext\n\n--- USER MESSAGE ---\n${msg.content}")
            } else {
                flat.add(msg.content)
            }
        }
        if (flat.isEmpty()) return

        val modelName = currentModelName.value ?: "model"
        _streamingText.value = ""

        val collected = StringBuilder()
        var lastStreamEmitMs = 0L
        var blockedCategory: SafetyCategory? = null

        val result = try {
            ModelEngine.generate(
                modelName = modelName,
                messages = flat.toTypedArray(),
                onToken = onToken@{ piece ->
                    if (blockedCategory != null) return@onToken
                    collected.append(piece)
                    val verdict = ContentSafety.screenResponse(collected.toString())
                    if (verdict.blocked) {
                        blockedCategory = verdict.category
                        _streamingText.value = null
                        ModelEngine.cancel()
                        return@onToken
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStreamEmitMs >= 60L || piece.contains('\n')) {
                        _streamingText.value = collected.toString()
                        lastStreamEmitMs = now
                    }
                },
            )
        } catch (t: Throwable) {
            _error.value = friendlyError(t.message ?: "Generation failed")
            null
        }

        ModelEngine.lastMetrics.value?.let { metrics ->
            val code = if (metrics.nativeCode != 0) " rc=${metrics.nativeCode}" else ""
            _performanceSummary.value = "perf · ${metrics.compactSummary()}$code"
        }

        val outputVerdict = blockedCategory?.let { SafetyVerdict(true, it) }
            ?: ContentSafety.screenResponse(result ?: collected.toString())

        when {
            outputVerdict.blocked -> {
                persistAssistant(conversationId, ContentSafety.RESPONSE_REFUSAL)
                _error.value = "SAFETY · ${outputVerdict.category?.label ?: "BLOCKED"} — " +
                    "reply blocked by the on-device filter. Nothing was uploaded."
            }
            result != null && result.isNotEmpty() -> persistAssistant(conversationId, result)
            result != null -> persistAssistant(conversationId, collected.toString())
            else -> {
                val nativeError = ModelEngine.lastError()
                if (collected.isNotBlank()) {
                    persistAssistant(conversationId, collected.toString())
                }
                _error.value = if (nativeError.isNotEmpty()) {
                    friendlyError(nativeError)
                } else {
                    "Generation failed. Try again; if it repeats, unload and reload the model."
                }
            }
        }
    }

    private fun friendlyError(raw: String): String = when {
        raw.contains("prompt processing failed", ignoreCase = true) ->
            "The model state was invalid and has been reset. Please send the message again."
        raw.contains("generation failed", ignoreCase = true) ->
            "Generation stopped inside the model. Try again; if it repeats, unload and reload the model."
        raw.contains("context length", ignoreCase = true) ->
            "The conversation is too long for this model context. Shorten it or start a new chat."
        raw.contains("no model loaded", ignoreCase = true) ->
            "No model loaded. Open Models and select one first."
        raw.contains("failed to load model", ignoreCase = true) ->
            "The model file could not be loaded. It may be corrupt or incompatible."
        else -> raw
    }

    private suspend fun persistAssistant(conversationId: String, text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        db.messageDao().insert(
            Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "assistant",
                content = text,
                createdAt = now,
            ),
        )
        db.conversationDao().touchUpdatedAt(conversationId, now)
    }

    /** Stops the in-flight generation; partial text is kept. */
    fun stopGeneration() {
        ModelEngine.cancel()
    }

    fun renameConversation(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            db.conversationDao().rename(id, trimmed.take(48), System.currentTimeMillis())
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            if (_activeConversationId.value == id) _activeConversationId.value = null
            val attachments = db.documentAttachmentDao().getForConversation(id)
            db.conversationDao().delete(id) // messages and attachment rows cascade
            // Remove files only after the rows are gone, and only if no other
            // attachment (in this or another conversation) still references them.
            attachments.forEach { attachment ->
                val local = attachment.localPath
                if (local != null &&
                    db.documentAttachmentDao().countOtherLocalReferences(local, attachment.id) == 0
                ) {
                    DocumentStorage.deletePath(getApplication(), local)
                }
                val thumbnail = attachment.thumbnailPath
                if (thumbnail != null &&
                    db.documentAttachmentDao().countOtherThumbnailReferences(thumbnail, attachment.id) == 0
                ) {
                    DocumentStorage.deletePath(getApplication(), thumbnail)
                }
            }
        }
    }

    /**
     * Saves an image's original bytes to a destination chosen by the user via
     * the Storage Access Framework. Never writes to a cache directory.
     */
    fun saveImageAttachment(attachment: DocumentAttachment, destination: Uri) {
        viewModelScope.launch {
            val saved = attachmentViewModel.saveImageToUri(attachment, destination)
            _imageNotice.value = if (saved) {
                "Image saved to the selected location."
            } else {
                "The original image is missing or could not be saved."
            }
        }
    }

    /**
     * Deletes an image attachment. If its message only held that image, the
     * now-empty message is removed too. Files stay on disk when shared.
     */
    fun deleteImageAttachment(attachment: DocumentAttachment, message: Message) {
        viewModelScope.launch {
            attachmentViewModel.deleteAttachment(attachment.id)
            val remaining = db.documentAttachmentDao().getForMessage(message.id)
            if (message.content.isBlank() && remaining.isEmpty()) {
                db.messageDao().deleteById(message.id)
            }
            db.conversationDao().touchUpdatedAt(message.conversationId, System.currentTimeMillis())
            _imageNotice.value = "Image deleted from this device."
        }
    }

    /**
     * Integration point for a future local diffusion engine. The engine writes
     * an image file in its own cache/output directory and returns an
     * [ImageGenerationResult]; this method persists it and attaches it to an
     * assistant message using the same validated pipeline as picked images.
     */
    fun attachGeneratedImage(result: ImageGenerationResult) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existingId = _activeConversationId.value
            val conversationId = existingId ?: UUID.randomUUID().toString()
            if (existingId == null) {
                db.conversationDao().upsert(
                    Conversation(
                        id = conversationId,
                        title = result.prompt.take(48).ifBlank { "Generated image" },
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            _activeConversationId.value = conversationId

            val messageId = UUID.randomUUID().toString()
            db.messageDao().insert(
                Message(
                    id = messageId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = "",
                    createdAt = now,
                ),
            )

            val attachment = attachmentViewModel.importGeneratedImage(
                conversationId = conversationId,
                messageId = messageId,
                sourceFile = result.imageFile,
                fileName = result.imageFile.name.ifBlank { "generated_image" },
                mimeType = result.mimeType,
                prompt = result.prompt,
                modelName = result.modelName,
            )

            if (attachment == null) {
                db.messageDao().deleteById(messageId)
                if (existingId == null) db.conversationDao().delete(conversationId)
                _error.value = "The generated image could not be stored. The file may be missing or corrupt."
                return@launch
            }
            db.conversationDao().touchUpdatedAt(conversationId, now)
            _imageNotice.value = "Image generated and saved locally."
        }
    }

    /**
     * Saves an AI response report locally and attempts explicit user-initiated
     * submission to the Shadow Studio Labs backend. The report is always preserved
     * on-device; it is marked SUBMITTED only after server confirmation.
     */
    fun submitReport(message: Message, category: String, details: String) {
        viewModelScope.launch {
            _reportSubmission.value = ReportSubmissionUiState.Submitting
            val app = getApplication<Application>()
            val report = SafetyReportStore.saveLocal(
                context = app,
                category = category,
                modelName = currentModelName.value ?: "model",
                appVersion = appVersionName(),
                conversationId = message.conversationId,
                details = details,
                reportedContent = message.content,
                queueForSubmission = true,
            )
            val result = ReportSubmitter.submit(app, report)
            _reportSubmission.value = if (result.ok && result.reportReference != null) {
                ReportSubmissionUiState.Success(result.reportReference)
            } else {
                ReportSubmissionUiState.Failure(
                    reportId = report.id,
                    message = result.error ?: "Could not submit report. It has been saved locally; you can retry.",
                )
            }
        }
    }

    /** Retries a previously saved pending/failed report (manual, explicit). */
    fun retryReport(reportId: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val pending = SafetyReportStore.markPending(app, reportId) ?: run {
                _reportSubmission.value = ReportSubmissionUiState.Failure(reportId, "Report not found.")
                return@launch
            }
            _reportSubmission.value = ReportSubmissionUiState.Submitting
            val result = ReportSubmitter.submit(app, pending)
            _reportSubmission.value = if (result.ok && result.reportReference != null) {
                ReportSubmissionUiState.Success(result.reportReference)
            } else {
                ReportSubmissionUiState.Failure(
                    reportId = pending.id,
                    message = result.error ?: "Could not submit report. It has been saved locally; you can retry.",
                )
            }
        }
    }

    private fun appVersionName(): String = runCatching {
        val app = getApplication<Application>()
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    override fun onCleared() {
        // The model is owned by ModelEngine (app-scoped), not the ViewModel:
        // leaving it loaded lets the next conversation start instantly.
        super.onCleared()
    }

    /** Loads a GGUF file into the native engine. Returns null on success. */
    suspend fun loadModel(path: String, displayName: String): String? =
        withContext(Dispatchers.Main) { ModelEngine.load(path, displayName) }

    suspend fun unloadModel() = ModelEngine.unload()
}
