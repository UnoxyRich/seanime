package app.seanime.tv.platform

import android.app.Application
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import app.seanime.tv.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real Compose directional search at the TV viewport; no decoder or native window claim. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    qualifiers = "w960dp-h540dp-land-television-mdpi-notouch-nokeys-navexposed-dpad")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class NativePlayerTransportFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("DEPRECATION")
    @Test fun disabledEpisodeEdgesStayInTransportAndKeepVerticalOptionsReachable() {
        compose.runOnUiThread {
            compose.activity.setTheme(R.style.AppTheme)
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            compose.activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        val state = NativeTvPlayerState().apply { paused = true; canPrevious = true; canNext = true }
        val actions = mutableListOf<String>()
        // Robolectric has no native WindowManager focus flag. Model a foreground
        // window, while production placement still chooses the initial control.
        // Actual Android window transfers remain covered by instrumentation.
        val foregroundWindow = object : WindowInfo { override val isWindowFocused = true }
        compose.setContent {
            // Android clickable buttons cannot focus in Robolectric's default
            // touch input mode. Model the TV's remote input mode, not a target.
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalWindowInfo provides foregroundWindow) {
                NativeTvPlayerPresentation(state, onAction = actions::add, onSeek = {})
            }
        }
        focus("play")
        key(Key.DirectionLeft); focus("rewind")
        key(Key.DirectionLeft); focus("previous")
        compose.runOnIdle { state.canPrevious = false }
        focus("play")
        compose.onNodeWithTag("native-player-previous").assertIsNotEnabled().assert(isNotFocusable())
        key(Key.DirectionLeft); focus("rewind")
        key(Key.DirectionLeft); focus("rewind")
        key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf("rewind"), actions) }
        key(Key.DirectionRight); focus("play")
        key(Key.DirectionRight); focus("forward")
        key(Key.DirectionRight); focus("next")
        compose.runOnIdle { state.canNext = false }
        focus("play")
        compose.onNodeWithTag("native-player-next").assertIsNotEnabled().assert(isNotFocusable())
        key(Key.DirectionRight); focus("forward")
        key(Key.DirectionRight); focus("forward")
        key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf("rewind", "forward"), actions) }
        key(Key.DirectionLeft); focus("play")
        key(Key.DirectionLeft); focus("rewind")
        key(Key.DirectionDown); focus("subtitles")
        key(Key.DirectionLeft); focus("audio")
        key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf("rewind", "forward", "audio"), actions) }
        key(Key.DirectionUp); focus("rewind")
        key(Key.DirectionUp); focus("seek")
    }

    private fun focus(id: String) {
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("native-player-$id") and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Expected $id; actual player tree:\n${compose.onRoot().printToString()}", failure)
        }
        compose.onNodeWithTag("native-player-$id").assertIsDisplayed().assertIsFocused()
    }

    private fun key(key: Key) {
        compose.onNodeWithTag("native-player-presentation").performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
}
