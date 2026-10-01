package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.seanime.tv.platform.NativeTvPlayerPresentation
import app.seanime.tv.platform.NativeTvPlayerState
import app.seanime.tv.platform.PlayerChoice
import app.seanime.tv.platform.PlayerChoiceDialog
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@NativeTvViewports
@Composable
fun nativePlayerHud() {
    val state = remember {
        NativeTvPlayerState().apply {
            title = "The Lantern Keepers of the Last Winter Observatory · Episode 12"
            position = 721_000
            duration = 1_440_000
            paused = true
            canPrevious = true
            canNext = true
            skipLabel = "Skip ending"
            translation = "We will meet again when the last lantern reaches the horizon."
        }
    }
    PlayerPreviewSurface { NativeTvPlayerPresentation(state, onAction = {}, onSeek = {}) }
}

@PreviewTest
@NativeTvViewports
@Composable
fun nativePlayerTrackDialog() {
    val state = remember {
        NativeTvPlayerState().apply {
            title = "Night Passage · Episode 7"
            position = 421_000
            duration = 1_440_000
            dialog = PlayerChoiceDialog(
                title = "Audio language",
                choices = List(24) { index ->
                    PlayerChoice("track-$index", "Audio track ${index + 1} · Original language · Stereo", selected = index == 18) {}
                },
                hint = "Select an audio track or return to playback.",
            )
        }
    }
    PlayerPreviewSurface { NativeTvPlayerPresentation(state, onAction = {}, onSeek = {}) }
}

@PreviewTest
@NativeTvViewports
@Composable
fun nativePlayerErrorActions() {
    val state = remember {
        NativeTvPlayerState().apply {
            title = "A Thousand Maps Beyond the Edge of the Sea"
            error = "This video format could not be decoded. Choose another playback option or return to the library."
        }
    }
    PlayerPreviewSurface { NativeTvPlayerPresentation(state, onAction = {}, onSeek = {}) }
}

@Composable
private fun PlayerPreviewSurface(content: @Composable () -> Unit) {
    // This is the actual player overlay on an empty video plane; no decoder is started.
    Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
}
