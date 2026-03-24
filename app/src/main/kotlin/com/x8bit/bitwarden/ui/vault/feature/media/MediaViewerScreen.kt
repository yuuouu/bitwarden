package com.x8bit.bitwarden.ui.vault.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.components.appbar.BitwardenTopAppBar
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import java.io.File

private const val MAX_ZOOM = 5f
private const val MIN_ZOOM = 1f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Fullscreen immersive media viewer.
 *
 * ## Multi-Type Rendering (OCP)
 * Uses the [VaultMediaViewerViewModel] to determine the [MediaPreviewState] and render
 * the appropriate viewer. Currently supports images via [FullscreenImageViewer],
 * with PDF support reserved for future extension.
 *
 * The decrypted file path is passed via navigation args and loaded by the ViewModel,
 * which uses [MediaType] inference to set the correct ready state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    viewModel: VaultMediaViewerViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.activeFullscreenState.collectAsStateWithLifecycle()
    val title by viewModel.fullscreenTitle.collectAsStateWithLifecycle()

    BitwardenScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BitwardenTopAppBar(
                title = title,
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                navigationIcon = rememberVectorPainter(id = BitwardenDrawable.ic_close),
                navigationIconContentDescription = stringResource(
                    id = BitwardenString.close,
                ),
                onNavigationIconClick = onNavigateBack,
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when (val currentState = state) {
                is MediaPreviewState.Masked -> {
                    MediaViewerErrorContent(
                        message = "Image not decrypted",
                    )
                }

                is MediaPreviewState.Loading -> {
                    MediaViewerLoadingContent()
                }

                is MediaPreviewState.ImageReady -> {
                    FullscreenImageViewer(filePath = currentState.decryptedFilePath)
                }

                is MediaPreviewState.PdfReady -> {
                    // TODO: Mount PdfViewer(filePath = currentState.decryptedFilePath)
                    MediaViewerErrorContent(
                        message = "PDF viewer coming soon",
                    )
                }

                is MediaPreviewState.Error -> {
                    MediaViewerErrorContent(
                        message = currentState.message
                            ?: stringResource(id = BitwardenString.generic_error_message),
                    )
                }
            }
        }
    }
}

/**
 * Pinch-to-zoom, pan, and double-tap-to-zoom image viewer backed by Glide.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun FullscreenImageViewer(
    filePath: String,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        offset = if (scale > MIN_ZOOM) {
            offset + panChange
        } else {
            Offset.Zero
        }
    }

    GlideImage(
        model = File(filePath),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            )
            .transformable(state = transformableState)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > MIN_ZOOM) {
                            scale = MIN_ZOOM
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                        }
                    },
                )
            }
            .testTag("FullscreenImage"),
    )
}

@Composable
private fun MediaViewerLoadingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = Color.White,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = BitwardenString.downloading),
            style = BitwardenTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun MediaViewerErrorContent(
    message: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = rememberVectorPainter(id = BitwardenDrawable.ic_warning),
            contentDescription = null,
            tint = BitwardenTheme.colorScheme.status.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = BitwardenTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}
