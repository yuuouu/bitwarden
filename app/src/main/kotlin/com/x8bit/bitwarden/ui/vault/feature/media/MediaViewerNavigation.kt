package com.x8bit.bitwarden.ui.vault.feature.media

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import com.bitwarden.ui.platform.base.util.composableWithSlideTransitions
import kotlinx.serialization.Serializable

/**
 * The type-safe route for the media viewer screen.
 *
 * @property filePath Absolute path to the decrypted file in the app's secure cache.
 * @property fileName Original attachment file name (displayed in the top bar).
 */
@Serializable
data class MediaViewerRoute(
    val filePath: String,
    val fileName: String,
)

/**
 * Add the media viewer screen to the nav graph.
 *
 * @param getSharedMediaViewModel A lambda that produces the NavGraph-scoped
 *        [VaultMediaViewerViewModel] so the fullscreen viewer shares
 *        the same instance as [VaultItemScreen].
 */
fun NavGraphBuilder.mediaViewerDestination(
    getSharedMediaViewModel: @Composable () -> VaultMediaViewerViewModel,
    onNavigateBack: () -> Unit,
) {
    composableWithSlideTransitions<MediaViewerRoute> {
        MediaViewerScreen(
            viewModel = getSharedMediaViewModel(),
            onNavigateBack = onNavigateBack,
        )
    }
}

/**
 * Navigate to the media viewer screen.
 */
fun NavController.navigateToMediaViewer(
    filePath: String,
    fileName: String,
    navOptions: NavOptions? = null,
) {
    navigate(
        route = MediaViewerRoute(
            filePath = filePath,
            fileName = fileName,
        ),
        navOptions = navOptions,
    )
}
