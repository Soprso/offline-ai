package com.soprso.dhurandharai.imagegen

import java.io.File

/**
 * Contract for a future fully-local image-generation engine (MNN, GGUF
 * diffusion, Stable Diffusion 1.5, ...).
 *
 * No implementation, model download or inference is included yet. A future
 * engine only has to write its output into [outputDirectory] (or any local
 * file) and return [ImageGenerationResult]. The chat layer then copies the
 * file into persistent app storage, creates a 256 px WebP thumbnail and links
 * the image to an assistant message via
 * `ChatViewModel.attachGeneratedImage`.
 *
 * Implementations must be safe to cancel from another thread; [cancel] is
 * deliberately not suspending so the UI can stop a run immediately.
 */
interface ImageGenerationProvider {

    /** Stable identifier, for example "sd15-mnn-q4". */
    val id: String

    /** Human-readable engine/model label shown in metadata. */
    val displayName: String

    /** False when the model or native library is not installed/loaded. */
    val isAvailable: Boolean

    /**
     * Runs text-to-image generation on this device.
     *
     * @param prompt positive text prompt.
     * @param outputDirectory engine-owned directory for the output file. The
     *        chat layer copies the result into persistent storage, so this
     *        directory may live in the engine's cache.
     * @param onProgress optional progress callback; called from a background
     *        thread and must not touch UI state directly.
     */
    suspend fun generate(
        prompt: String,
        outputDirectory: File,
        onProgress: (ImageGenerationProgress) -> Unit = {},
    ): ImageGenerationResult

    /** Requests cancellation of the current [generate] call. */
    fun cancel()
}

/** Progress for a local diffusion run. */
data class ImageGenerationProgress(
    val step: Int,
    val totalSteps: Int,
    val message: String? = null,
) {
    val fraction: Float
        get() = if (totalSteps <= 0) 0f else (step.toFloat() / totalSteps).coerceIn(0f, 1f)
}

/**
 * A locally produced image plus the metadata required by the chat image model.
 * Dimensions are optional because the storage pipeline re-reads and validates
 * them without fully decoding the image.
 */
data class ImageGenerationResult(
    val imageFile: File,
    val prompt: String,
    val modelName: String,
    val mimeType: String = "image/webp",
    val width: Int = 0,
    val height: Int = 0,
    val elapsedMillis: Long = 0L,
)

/** Typed failures so the UI can show actionable messages. */
sealed interface ImageGenerationError {
    data object Cancelled : ImageGenerationError
    data object ModelUnavailable : ImageGenerationError
    data object OutOfMemory : ImageGenerationError
    data object InvalidOutput : ImageGenerationError
    data class Unknown(val detail: String? = null) : ImageGenerationError
}

class ImageGenerationException(
    val error: ImageGenerationError,
    message: String? = null,
) : Exception(message ?: error.toString())

/**
 * Optional registry for future providers. The app intentionally starts empty:
 * no cloud provider and no bundled diffusion model are present in this build.
 * A future engine can be registered at app startup without changing the chat
 * or storage code.
 */
object ImageGenerationProviders {

    @Volatile
    private var registered: List<ImageGenerationProvider> = emptyList()

    fun register(provider: ImageGenerationProvider) {
        registered = registered.filterNot { it.id == provider.id } + provider
    }

    fun all(): List<ImageGenerationProvider> = registered

    fun available(): List<ImageGenerationProvider> = registered.filter { it.isAvailable }
}
