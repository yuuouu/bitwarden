package com.x8bit.bitwarden.ui.vault.feature.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bitwarden.vault.CipherView
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.DownloadAttachmentResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Subdirectory under cache for decrypted media preview files.
 * Files in this directory are cleaned up on ViewModel clear or Vault lock.
 */
private const val MEDIA_PREVIEW_CACHE_DIR = "media_previews"

/**
 * Cross-page shared ViewModel for media attachment preview and fullscreen viewing.
 *
 * ## Architecture Highlights
 *
 * 1. **Single Source of Truth**: Owns ALL decryption state and file lifecycle.
 *    [VaultItemViewModel] no longer participates in preview logic.
 *
 * 2. **Route-Driven Initialization**: When navigated to via [MediaViewerRoute],
 *    reads `filePath` and `fileName` from [SavedStateHandle] to set the fullscreen
 *    state immediately — zero-delay rendering for already-decrypted files.
 *
 * 3. **Vault Lock Cleanup (Step 3)**: Subscribes to [AuthRepository.userStateFlow].
 *    When `isVaultUnlocked` transitions to `false`, immediately deletes all
 *    decrypted files and resets all states to [MediaPreviewState.Masked].
 *
 * 4. **Concurrency Control (Step 4)**: Uses [ConcurrentHashMap] to track in-flight
 *    decryption [Job]s per attachment ID, preventing duplicate downloads from
 *    rapid user taps.
 *
 * 5. **Auto-Unmask All (Step 5)**: When [isAutoUnmaskAllEnabled] is `true`,
 *    decrypting any single attachment triggers decryption of all other image
 *    attachments in the same cipher.
 */
@HiltViewModel
class VaultMediaViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    // TODO: Move this to user settings / FeatureFlag in the future.
    @Suppress("PrivatePropertyName")
    private val isAutoUnmaskAllEnabled = true

    /**
     * The currently-active fullscreen media state.
     */
    private val _activeFullscreenState =
        MutableStateFlow<MediaPreviewState>(MediaPreviewState.Masked)
    val activeFullscreenState: StateFlow<MediaPreviewState> =
        _activeFullscreenState.asStateFlow()

    /**
     * Title for the fullscreen viewer (attachment file name).
     */
    private val _fullscreenTitle = MutableStateFlow("")
    val fullscreenTitle: StateFlow<String> = _fullscreenTitle.asStateFlow()

    /**
     * Per-attachment inline preview states (used by VaultItemScreen).
     */
    private val _inlineStates =
        MutableStateFlow<Map<String, MediaPreviewState>>(emptyMap())
    val inlineStates: StateFlow<Map<String, MediaPreviewState>> =
        _inlineStates.asStateFlow()

    /**
     * Tracks decrypted files for secure cleanup.
     * Thread-safe because [requestPreview] may launch concurrent coroutines.
     */
    private val decryptedFilesRegistry = ConcurrentHashMap<String, File>()

    /**
     * (Step 4) Tracks in-flight decryption jobs per attachment ID.
     * Prevents duplicate downloads from rapid user taps.
     */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    init {
        // (Step 3) Subscribe to vault lock state changes.
        observeVaultLockState()
    }

    /**
     * (Step 3) Monitors vault lock state. When the vault becomes locked,
     * immediately purges all decrypted files from disk and resets all states.
     */
    private fun observeVaultLockState() {
        authRepository.userStateFlow
            .map { it?.activeAccount?.isVaultUnlocked == true }
            .distinctUntilChanged()
            .onEach { isUnlocked ->
                if (!isUnlocked) {
                    purgeAllDecryptedFiles()
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Elevates the decrypted state of a specific attachment to the fullscreen state.
     * This is called by VaultItemScreen right before navigating to the viewer.
     */
    fun setActiveFullscreen(attachmentId: String, fileName: String) {
        _fullscreenTitle.value = fileName
        val currentState = _inlineStates.value[attachmentId]
        if (currentState != null) {
            _activeFullscreenState.value = currentState
        } else {
            // Fallback if not decrypted yet (though it should be)
            _activeFullscreenState.value = MediaPreviewState.Error(
                message = "Image not ready",
            )
        }
    }

    /**
     * Requests decryption and preview for an inline attachment thumbnail.
     *
     * (Step 4) If a decryption job for this attachment is already in-flight,
     * subsequent calls are silently ignored to prevent race conditions.
     *
     * (Step 5) When [isAutoUnmaskAllEnabled] is true, also triggers decryption
     * for all other image-type attachments via [allImageAttachmentIds].
     *
     * @param cipherView The cipher that owns the attachment.
     * @param attachmentId The ID of the specific attachment to decrypt.
     * @param fileName The original file name (used for MediaType inference).
     * @param allImageAttachmentIds All image attachment IDs in this cipher.
     *        Used for auto-unmask-all when enabled.
     */
    fun requestPreview(
        cipherView: CipherView,
        attachmentId: String,
        fileName: String,
        allImageAttachmentIds: List<Pair<String, String>> = emptyList(),
    ) {
        // Dispatch the primary request.
        requestSinglePreview(
            cipherView = cipherView,
            attachmentId = attachmentId,
            fileName = fileName,
        )

        // (Step 5) Auto-unmask all other image attachments if enabled.
        if (isAutoUnmaskAllEnabled) {
            allImageAttachmentIds
                .filter { (id, _) -> id != attachmentId }
                .forEach { (otherId, otherFileName) ->
                    requestSinglePreview(
                        cipherView = cipherView,
                        attachmentId = otherId,
                        fileName = otherFileName,
                    )
                }
        }
    }

    /**
     * Core decryption logic for a single attachment.
     * (Step 4) Guarded by [activeJobs] to prevent duplicate concurrent downloads.
     */
    private fun requestSinglePreview(
        cipherView: CipherView,
        attachmentId: String,
        fileName: String,
    ) {
        val mediaType = MediaType.fromFileName(fileName)
        if (mediaType == MediaType.UNKNOWN) return

        // Cache hit: already decrypted.
        val existingState = _inlineStates.value[attachmentId]
        if (existingState is MediaPreviewState.ImageReady ||
            existingState is MediaPreviewState.PdfReady
        ) {
            return
        }

        // (Step 4) Concurrency guard: if a job is already running, skip.
        if (activeJobs.containsKey(attachmentId)) return

        updateInlineState(attachmentId, MediaPreviewState.Loading)

        val job = viewModelScope.launch {
            try {
                val result = vaultRepository.downloadAttachment(
                    cipherView = cipherView,
                    attachmentId = attachmentId,
                )

                when (result) {
                    is DownloadAttachmentResult.Failure -> {
                        updateInlineState(
                            attachmentId,
                            MediaPreviewState.Error(result.errorMessage),
                        )
                    }

                    is DownloadAttachmentResult.Success -> {
                        val previewFile = moveToPreviewCache(
                            sourceFile = result.file,
                            attachmentId = attachmentId,
                        )

                        val readyState = if (previewFile != null) {
                            when (mediaType) {
                                MediaType.IMAGE -> MediaPreviewState.ImageReady(
                                    decryptedFilePath = previewFile.absolutePath,
                                )
                                MediaType.PDF -> MediaPreviewState.PdfReady(
                                    decryptedFilePath = previewFile.absolutePath,
                                )
                                MediaType.UNKNOWN -> MediaPreviewState.Error(
                                    "Unsupported media type",
                                )
                            }
                        } else {
                            MediaPreviewState.Error(
                                message = "Failed to prepare preview file",
                            )
                        }

                        updateInlineState(attachmentId, readyState)
                    }
                }
            } finally {
                // (Step 4) Always remove the job reference when done.
                activeJobs.remove(attachmentId)
            }
        }

        activeJobs[attachmentId] = job
    }

    /**
     * Promotes an already-decrypted inline attachment to fullscreen.
     * This is the "zero-delay instant open" path.
     */
    fun openFullscreen(attachmentId: String, fileName: String) {
        val existingState = _inlineStates.value[attachmentId]
        if (existingState is MediaPreviewState.ImageReady ||
            existingState is MediaPreviewState.PdfReady
        ) {
            _fullscreenTitle.value = fileName
            _activeFullscreenState.value = existingState
        }
    }

    /**
     * Clears the fullscreen viewer state.
     */
    fun clearFullscreenState() {
        _activeFullscreenState.value = MediaPreviewState.Masked
        _fullscreenTitle.value = ""
    }

    /**
     * Returns the inline [MediaPreviewState] for a specific attachment.
     */
    fun getInlineState(attachmentId: String): MediaPreviewState =
        _inlineStates.value[attachmentId] ?: MediaPreviewState.Masked

    /**
     * Returns the decrypted file path for a specific attachment, or null.
     * Used by VaultItemScreen to pass the path via navigation args.
     */
    fun getDecryptedFilePath(attachmentId: String): String? =
        decryptedFilesRegistry[attachmentId]?.absolutePath

    // ---- Private Helpers ----

    private fun updateInlineState(attachmentId: String, state: MediaPreviewState) {
        _inlineStates.update {
            it.toMutableMap().apply { put(attachmentId, state) }
        }
    }

    /**
     * Moves the decrypted file into our dedicated preview cache directory.
     */
    private fun moveToPreviewCache(
        sourceFile: File,
        attachmentId: String,
    ): File? {
        val previewDir = File(
            sourceFile.parentFile?.parentFile,
            MEDIA_PREVIEW_CACHE_DIR,
        ).also { it.mkdirs() }

        val previewFile = File(
            previewDir,
            "${attachmentId}_${System.currentTimeMillis()}",
        )

        val moved = sourceFile.renameTo(previewFile)
        if (!moved) {
            try {
                sourceFile.delete()
            } catch (e: SecurityException) {
                Timber.e(e, "Failed to delete source file after move failure")
            }
            return null
        }

        decryptedFilesRegistry[attachmentId] = previewFile
        return previewFile
    }

    /**
     * (Step 3) Securely deletes all decrypted media files and resets all states.
     * Called on vault lock and on [onCleared].
     */
    private fun purgeAllDecryptedFiles() {
        // Cancel all in-flight jobs.
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()

        // Delete all decrypted files from disk.
        decryptedFilesRegistry.values.forEach { file ->
            try {
                if (file.exists()) file.delete()
            } catch (e: SecurityException) {
                Timber.e(e, "Failed to delete preview file: ${file.name}")
            }
        }
        decryptedFilesRegistry.clear()

        // Reset all states to Masked (privacy veil restored).
        _inlineStates.value = emptyMap()
        _activeFullscreenState.value = MediaPreviewState.Masked
        _fullscreenTitle.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        purgeAllDecryptedFiles()
    }
}
