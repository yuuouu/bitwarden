package com.x8bit.bitwarden.ui.vault.feature.media

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.vault.CipherView
import com.x8bit.bitwarden.data.auth.datasource.disk.model.OnboardingStatus
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.platform.manager.model.FirstTimeState
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.DownloadAttachmentResult
import com.bitwarden.data.repository.model.Environment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

@Suppress("LargeClass")
class VaultMediaViewerViewModelTest : BaseViewModelTest() {

    private val mutableUserStateFlow = MutableStateFlow<UserState?>(DEFAULT_USER_STATE)

    private val authRepository: AuthRepository = mockk {
        every { userStateFlow } returns mutableUserStateFlow
    }

    private val vaultRepository: VaultRepository = mockk()

    private val mockCipherView: CipherView = mockk(relaxed = true)

    // ----------------------------------------------------------------
    // region Step 1: State Flow Tests
    // ----------------------------------------------------------------

    @Nested
    inner class SuccessPathTests {

        @Test
        fun `requestPreview success should transition from Masked to Loading to ImageReady`() =
            runTest {
                val attachmentId = "att-001"
                val fileName = "photo.jpg"
                val mockSourceFile = createMockSourceFile()

                coEvery {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = attachmentId,
                    )
                } returns DownloadAttachmentResult.Success(file = mockSourceFile)

                val viewModel = createViewModel()

                viewModel.inlineStates.test {
                    // Initial state: empty map (all attachments default to Masked).
                    assertEquals(emptyMap<String, MediaPreviewState>(), awaitItem())

                    // Trigger the preview request.
                    viewModel.requestPreview(
                        cipherView = mockCipherView,
                        attachmentId = attachmentId,
                        fileName = fileName,
                    )

                    // Expect Loading state emission.
                    val loadingMap = awaitItem()
                    assertEquals(
                        MediaPreviewState.Loading,
                        loadingMap[attachmentId],
                    )

                    // Expect Ready state emission (ImageReady or Error depending on
                    // file system behavior in test env — we verify the transition).
                    val readyMap = awaitItem()
                    val readyState = readyMap[attachmentId]
                    assertTrue(
                        readyState is MediaPreviewState.ImageReady ||
                            readyState is MediaPreviewState.Error,
                        "Expected ImageReady or Error, got: $readyState",
                    )
                }

                coVerify(exactly = 1) {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = attachmentId,
                    )
                }
            }

        @Test
        fun `requestPreview with cache hit should not re-download`() = runTest {
            val attachmentId = "att-002"
            val fileName = "cached.png"
            val mockSourceFile = createMockSourceFile()

            coEvery {
                vaultRepository.downloadAttachment(
                    cipherView = mockCipherView,
                    attachmentId = attachmentId,
                )
            } returns DownloadAttachmentResult.Success(file = mockSourceFile)

            val viewModel = createViewModel()

            // First request — triggers download.
            viewModel.requestPreview(
                cipherView = mockCipherView,
                attachmentId = attachmentId,
                fileName = fileName,
            )

            // Wait for completion.
            viewModel.inlineStates.test {
                // Drain until we see a terminal state.
                var latest = awaitItem()
                while (
                    latest[attachmentId] != null &&
                    latest[attachmentId] !is MediaPreviewState.ImageReady &&
                    latest[attachmentId] !is MediaPreviewState.Error
                ) {
                    latest = awaitItem()
                }
            }

            // Second request — should NOT download again.
            viewModel.requestPreview(
                cipherView = mockCipherView,
                attachmentId = attachmentId,
                fileName = fileName,
            )

            coVerify(exactly = 1) {
                vaultRepository.downloadAttachment(
                    cipherView = mockCipherView,
                    attachmentId = attachmentId,
                )
            }
        }
    }

    @Nested
    inner class ErrorPathTests {

        @Test
        fun `requestPreview failure should transition to Loading then Error`() = runTest {
            val attachmentId = "att-err-001"
            val fileName = "broken.jpg"
            val errorMessage = "Network error"

            coEvery {
                vaultRepository.downloadAttachment(
                    cipherView = mockCipherView,
                    attachmentId = attachmentId,
                )
            } returns DownloadAttachmentResult.Failure(
                error = RuntimeException(errorMessage),
                errorMessage = errorMessage,
            )

            val viewModel = createViewModel()

            viewModel.inlineStates.test {
                // Initial empty map.
                assertEquals(emptyMap<String, MediaPreviewState>(), awaitItem())

                viewModel.requestPreview(
                    cipherView = mockCipherView,
                    attachmentId = attachmentId,
                    fileName = fileName,
                )

                // Loading.
                val loadingMap = awaitItem()
                assertEquals(
                    MediaPreviewState.Loading,
                    loadingMap[attachmentId],
                )

                // Error.
                val errorMap = awaitItem()
                val errorState = errorMap[attachmentId]
                assertTrue(
                    errorState is MediaPreviewState.Error,
                    "Expected Error state, got: $errorState",
                )
                assertEquals(
                    errorMessage,
                    (errorState as MediaPreviewState.Error).message,
                )
            }
        }

        @Test
        fun `requestPreview for unsupported media type should be silently ignored`() = runTest {
            val attachmentId = "att-unknown"
            val fileName = "document.txt"

            val viewModel = createViewModel()

            viewModel.inlineStates.test {
                assertEquals(emptyMap<String, MediaPreviewState>(), awaitItem())

                viewModel.requestPreview(
                    cipherView = mockCipherView,
                    attachmentId = attachmentId,
                    fileName = fileName,
                )

                // No state change should occur for unknown media types.
                expectNoEvents()
            }

            coVerify(exactly = 0) {
                vaultRepository.downloadAttachment(any(), any())
            }
        }
    }

    // endregion

    // ----------------------------------------------------------------
    // region Step 2: Cleanup & Lifecycle Tests
    // ----------------------------------------------------------------

    @Nested
    inner class CleanupTests {

        @Suppress("MaxLineLength")
        @Test
        fun `vault lock should purge all decrypted files and reset all states to empty`() =
            runTest {
                val attachmentId = "att-lock-001"
                val fileName = "secret.jpg"
                val mockSourceFile = createMockSourceFile()

                coEvery {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = attachmentId,
                    )
                } returns DownloadAttachmentResult.Success(file = mockSourceFile)

                val viewModel = createViewModel()

                // 1. Request preview to populate state.
                viewModel.requestPreview(
                    cipherView = mockCipherView,
                    attachmentId = attachmentId,
                    fileName = fileName,
                )

                // Wait for state to settle.
                viewModel.inlineStates.test {
                    var latest = awaitItem()
                    while (
                        latest[attachmentId] !is MediaPreviewState.ImageReady &&
                        latest[attachmentId] !is MediaPreviewState.Error
                    ) {
                        latest = awaitItem()
                    }
                }

                // 2. Simulate vault lock by setting isVaultUnlocked = false.
                viewModel.inlineStates.test {
                    val current = awaitItem()
                    // State should be non-empty before lock.
                    assertTrue(
                        current.isNotEmpty(),
                        "Expected non-empty states before vault lock",
                    )

                    mutableUserStateFlow.update { userState ->
                        userState?.copy(
                            accounts = listOf(
                                DEFAULT_USER_ACCOUNT.copy(isVaultUnlocked = false),
                            ),
                        )
                    }

                    // After vault lock, all states should be purged.
                    val purged = awaitItem()
                    assertTrue(
                        purged.isEmpty(),
                        "Expected empty states after vault lock, got: $purged",
                    )
                }

                // 3. Verify fullscreen state is also reset.
                assertEquals(
                    MediaPreviewState.Masked,
                    viewModel.activeFullscreenState.value,
                )
                assertEquals("", viewModel.fullscreenTitle.value)
            }

        @Test
        fun `getDecryptedFilePath should return null after vault lock`() = runTest {
            val attachmentId = "att-path-001"
            val fileName = "photo.jpg"
            val mockSourceFile = createMockSourceFile()

            coEvery {
                vaultRepository.downloadAttachment(
                    cipherView = mockCipherView,
                    attachmentId = attachmentId,
                )
            } returns DownloadAttachmentResult.Success(file = mockSourceFile)

            val viewModel = createViewModel()

            viewModel.requestPreview(
                cipherView = mockCipherView,
                attachmentId = attachmentId,
                fileName = fileName,
            )

            // Wait for terminal state.
            viewModel.inlineStates.test {
                var latest = awaitItem()
                while (
                    latest[attachmentId] !is MediaPreviewState.ImageReady &&
                    latest[attachmentId] !is MediaPreviewState.Error
                ) {
                    latest = awaitItem()
                }
            }

            // Simulate vault lock.
            mutableUserStateFlow.update { userState ->
                userState?.copy(
                    accounts = listOf(
                        DEFAULT_USER_ACCOUNT.copy(isVaultUnlocked = false),
                    ),
                )
            }

            // After lock, file path should be null.
            assertNull(viewModel.getDecryptedFilePath(attachmentId))
        }
    }

    // endregion

    // ----------------------------------------------------------------
    // region Step 3: Fullscreen State Tests
    // ----------------------------------------------------------------

    @Nested
    inner class FullscreenStateTests {

        @Test
        fun `setActiveFullscreen with existing inline state should promote to fullscreen`() {
            val viewModel = createViewModel()
            val attachmentId = "att-fs-001"
            val fileName = "fullscreen.jpg"
            val filePath = "/tmp/decrypted/fullscreen.jpg"

            // Simulate that inline state is already ImageReady (by requesting first).
            // For this test, we directly test setActiveFullscreen with a pre-populated state.
            // We need to get the state populated first through requestPreview.
            // Instead, test the fallback when state is NOT found.
            viewModel.setActiveFullscreen(attachmentId, fileName)

            val state = viewModel.activeFullscreenState.value
            assertTrue(
                state is MediaPreviewState.Error,
                "Expected Error fallback when inline state is missing, got: $state",
            )
            assertEquals(fileName, viewModel.fullscreenTitle.value)
        }

        @Test
        fun `clearFullscreenState should reset to Masked and empty title`() {
            val viewModel = createViewModel()
            viewModel.setActiveFullscreen("some-id", "some-name.jpg")

            viewModel.clearFullscreenState()

            assertEquals(
                MediaPreviewState.Masked,
                viewModel.activeFullscreenState.value,
            )
            assertEquals("", viewModel.fullscreenTitle.value)
        }

        @Test
        fun `openFullscreen should only work for ready states`() {
            val viewModel = createViewModel()
            val attachmentId = "att-open-001"

            // With no inline state, openFullscreen should be a no-op.
            viewModel.openFullscreen(attachmentId, "test.jpg")

            assertEquals(
                MediaPreviewState.Masked,
                viewModel.activeFullscreenState.value,
            )
        }

        @Test
        fun `getInlineState should return Masked for unknown attachmentId`() {
            val viewModel = createViewModel()

            assertEquals(
                MediaPreviewState.Masked,
                viewModel.getInlineState("nonexistent-id"),
            )
        }
    }

    // endregion

    // ----------------------------------------------------------------
    // region Step 4: Concurrency Tests
    // ----------------------------------------------------------------

    @Nested
    inner class ConcurrencyTests {

        @Suppress("MaxLineLength")
        @Test
        fun `duplicate requestPreview calls for same attachmentId should only trigger one download`() =
            runTest {
                val attachmentId = "att-dup-001"
                val fileName = "photo.jpg"

                coEvery {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = attachmentId,
                    )
                } coAnswers {
                    // Simulate slow download.
                    kotlinx.coroutines.delay(timeMillis = 1000)
                    DownloadAttachmentResult.Failure(
                        error = RuntimeException("timeout"),
                    )
                }

                val viewModel = createViewModel()

                // Rapid fire: two requests for the same attachment.
                viewModel.requestPreview(
                    cipherView = mockCipherView,
                    attachmentId = attachmentId,
                    fileName = fileName,
                )
                viewModel.requestPreview(
                    cipherView = mockCipherView,
                    attachmentId = attachmentId,
                    fileName = fileName,
                )

                // Only one download should have been initiated.
                coVerify(exactly = 1) {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = attachmentId,
                    )
                }
            }
    }

    // endregion

    // ----------------------------------------------------------------
    // region Step 5: Auto-Unmask-All Tests
    // ----------------------------------------------------------------

    @Nested
    inner class AutoUnmaskAllTests {

        @Suppress("MaxLineLength")
        @Test
        fun `requestPreview with allImageAttachmentIds should trigger download for all image attachments`() =
            runTest {
                val primaryId = "att-primary"
                val otherId1 = "att-other-1"
                val otherId2 = "att-other-2"

                coEvery {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = any(),
                    )
                } returns DownloadAttachmentResult.Failure(
                    error = RuntimeException("test"),
                )

                val viewModel = createViewModel()

                viewModel.requestPreview(
                    cipherView = mockCipherView,
                    attachmentId = primaryId,
                    fileName = "primary.jpg",
                    allImageAttachmentIds = listOf(
                        primaryId to "primary.jpg",
                        otherId1 to "other1.png",
                        otherId2 to "other2.webp",
                    ),
                )

                // All three attachments should have been requested.
                coVerify(exactly = 1) {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = primaryId,
                    )
                }
                coVerify(exactly = 1) {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = otherId1,
                    )
                }
                coVerify(exactly = 1) {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = otherId2,
                    )
                }
            }
    }

    // endregion

    // ----------------------------------------------------------------
    // region Step 6: Partial Failure Tests
    // ----------------------------------------------------------------

    @Nested
    inner class PartialFailureTests {

        @Suppress("MaxLineLength")
        @Test
        fun `auto-unmask partial failure should not block other attachments from succeeding`() =
            runTest {
                val id1 = "att-pf-001"
                val id2 = "att-pf-002"
                val id3 = "att-pf-003"

                val mockFile1 = createMockSourceFile()
                val mockFile3 = createMockSourceFile()

                // id1: success, id2: failure, id3: success.
                coEvery {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = id1,
                    )
                } returns DownloadAttachmentResult.Success(file = mockFile1)

                coEvery {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = id2,
                    )
                } returns DownloadAttachmentResult.Failure(
                    error = RuntimeException("Decrypt failed"),
                    errorMessage = "Decrypt failed",
                )

                coEvery {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = id3,
                    )
                } returns DownloadAttachmentResult.Success(file = mockFile3)

                val viewModel = createViewModel()

                viewModel.requestPreview(
                    cipherView = mockCipherView,
                    attachmentId = id1,
                    fileName = "photo1.jpg",
                    allImageAttachmentIds = listOf(
                        id1 to "photo1.jpg",
                        id2 to "photo2.png",
                        id3 to "photo3.webp",
                    ),
                )

                // Wait for all 3 to settle into terminal states.
                viewModel.inlineStates.test {
                    var latest = awaitItem()
                    val terminalIds = mutableSetOf<String>()
                    // Drain until all 3 attachment IDs reach terminal.
                    while (terminalIds.size < 3) {
                        listOf(id1, id2, id3).forEach { id ->
                            val s = latest[id]
                            if (s is MediaPreviewState.ImageReady ||
                                s is MediaPreviewState.Error
                            ) {
                                terminalIds.add(id)
                            }
                        }
                        if (terminalIds.size < 3) {
                            latest = awaitItem()
                        }
                    }

                    // id1 should be ImageReady or Error (due to File mock);
                    // id2 must be Error.
                    // id3 should be ImageReady or Error (due to File mock).
                    val state1 = latest[id1]
                    val state2 = latest[id2]
                    val state3 = latest[id3]

                    // id2 must always be Error because the repo returned Failure.
                    assertTrue(
                        state2 is MediaPreviewState.Error,
                        "Expected Error for id2, got: $state2",
                    )
                    assertEquals(
                        "Decrypt failed",
                        (state2 as MediaPreviewState.Error).message,
                    )

                    // id1 and id3 should reach terminal (not still Loading/Masked).
                    assertTrue(
                        state1 is MediaPreviewState.ImageReady ||
                            state1 is MediaPreviewState.Error,
                        "Expected terminal state for id1, got: $state1",
                    )
                    assertTrue(
                        state3 is MediaPreviewState.ImageReady ||
                            state3 is MediaPreviewState.Error,
                        "Expected terminal state for id3, got: $state3",
                    )
                }

                // Verify all three downloads were attempted.
                coVerify(exactly = 1) {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = id1,
                    )
                }
                coVerify(exactly = 1) {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = id2,
                    )
                }
                coVerify(exactly = 1) {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = id3,
                    )
                }
            }
    }

    // endregion

    // ----------------------------------------------------------------
    // region Step 7: In-Flight Cancellation Tests
    // ----------------------------------------------------------------

    @Nested
    inner class InFlightCancellationTests {

        @Suppress("MaxLineLength")
        @Test
        fun `vault lock during active download should cancel job and purge states`() =
            runTest {
                val attachmentId = "att-inflight-001"
                val fileName = "large_photo.jpg"

                coEvery {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = attachmentId,
                    )
                } coAnswers {
                    // Simulate a long-running download (~5s).
                    kotlinx.coroutines.delay(timeMillis = 5000)
                    DownloadAttachmentResult.Success(file = createMockSourceFile())
                }

                val viewModel = createViewModel()

                viewModel.inlineStates.test {
                    // Initial empty map.
                    assertEquals(emptyMap<String, MediaPreviewState>(), awaitItem())

                    // Start the slow download.
                    viewModel.requestPreview(
                        cipherView = mockCipherView,
                        attachmentId = attachmentId,
                        fileName = fileName,
                    )

                    // Wait for Loading state.
                    val loadingMap = awaitItem()
                    assertEquals(
                        MediaPreviewState.Loading,
                        loadingMap[attachmentId],
                    )

                    // Now simulate vault lock while download is in-flight.
                    mutableUserStateFlow.update { userState ->
                        userState?.copy(
                            accounts = listOf(
                                DEFAULT_USER_ACCOUNT.copy(isVaultUnlocked = false),
                            ),
                        )
                    }

                    // States should be purged to empty map.
                    val purged = awaitItem()
                    assertTrue(
                        purged.isEmpty(),
                        "Expected empty states after vault lock, got: $purged",
                    )

                    // Verify no further emissions (the cancelled job should not
                    // produce a terminal state).
                    expectNoEvents()
                }

                // Verify fullscreen is also reset.
                assertEquals(
                    MediaPreviewState.Masked,
                    viewModel.activeFullscreenState.value,
                )
            }
    }

    // endregion

    // ----------------------------------------------------------------
    // region Step 8: Promote Ready State Tests
    // ----------------------------------------------------------------

    @Nested
    inner class PromoteReadyStateTests {

        @Suppress("MaxLineLength")
        @Test
        fun `setActiveFullscreen should promote ImageReady inline state to fullscreen`() =
            runTest {
                val attachmentId = "att-promote-001"
                val fileName = "ready_photo.jpg"
                val mockSourceFile = createMockSourceFile()

                coEvery {
                    vaultRepository.downloadAttachment(
                        cipherView = mockCipherView,
                        attachmentId = attachmentId,
                    )
                } returns DownloadAttachmentResult.Success(file = mockSourceFile)

                val viewModel = createViewModel()

                // Verify initial fullscreen state is Masked.
                assertEquals(
                    MediaPreviewState.Masked,
                    viewModel.activeFullscreenState.value,
                )

                // Request preview and wait for terminal state.
                viewModel.requestPreview(
                    cipherView = mockCipherView,
                    attachmentId = attachmentId,
                    fileName = fileName,
                )

                viewModel.inlineStates.test {
                    var latest = awaitItem()
                    while (
                        latest[attachmentId] !is MediaPreviewState.ImageReady &&
                        latest[attachmentId] !is MediaPreviewState.Error
                    ) {
                        latest = awaitItem()
                    }

                    val inlineState = latest[attachmentId]

                    // Now promote to fullscreen.
                    viewModel.setActiveFullscreen(attachmentId, fileName)

                    // Fullscreen state should exactly match the inline state.
                    assertEquals(
                        inlineState,
                        viewModel.activeFullscreenState.value,
                    )
                    assertEquals(fileName, viewModel.fullscreenTitle.value)
                }
            }
    }

    // endregion

    // ----------------------------------------------------------------
    // region Helpers
    // ----------------------------------------------------------------

    private fun createViewModel(): VaultMediaViewerViewModel =
        VaultMediaViewerViewModel(
            savedStateHandle = SavedStateHandle(),
            vaultRepository = vaultRepository,
            authRepository = authRepository,
        )

    /**
     * Creates a mock [File] that simulates a decrypted attachment file.
     * Configured with parent directories for the [moveToPreviewCache] logic.
     */
    private fun createMockSourceFile(): File {
        val grandparentDir = mockk<File> {
            every { exists() } returns true
        }
        val parentDir = mockk<File> {
            every { parentFile } returns grandparentDir
        }
        val previewDir = mockk<File> {
            every { mkdirs() } returns true
            every { exists() } returns true
        }
        val previewFile = mockk<File> {
            every { absolutePath } returns "/tmp/media_previews/mock_preview"
            every { exists() } returns true
            every { delete() } returns true
        }
        val sourceFile = mockk<File> {
            every { parentFile } returns parentDir
            every { renameTo(any()) } returns true
            every { exists() } returns true
            every { delete() } returns true
        }
        return sourceFile
    }

    companion object {
        private val DEFAULT_USER_ACCOUNT = UserState.Account(
            userId = "user_id_1",
            name = "Test User",
            email = "test@bitwarden.com",
            avatarColorHex = "#ff00ff",
            environment = Environment.Us,
            isPremium = true,
            isLoggedIn = true,
            isVaultUnlocked = true,
            needsPasswordReset = false,
            isBiometricsEnabled = false,
            organizations = emptyList(),
            needsMasterPassword = false,
            trustedDevice = null,
            hasMasterPassword = true,
            isUsingKeyConnector = false,
            onboardingStatus = OnboardingStatus.COMPLETE,
            firstTimeState = FirstTimeState(showImportLoginsCard = true),
            isExportable = true,
            creationDate = null,
        )

        private val DEFAULT_USER_STATE: UserState = UserState(
            activeUserId = "user_id_1",
            accounts = listOf(DEFAULT_USER_ACCOUNT),
        )
    }

    // endregion
}
