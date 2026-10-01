package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.seanime.tv.platform.NativePrompt
import app.seanime.tv.NativeScreenshotEvidence
import app.seanime.tv.platform.NativePromptAction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePlatformDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lateInformationalNoticeFocusesCloseOnlyAfterDialogPlacement() {
        var showing by mutableStateOf(false)
        compose.setContent { SeanimeTheme { if (showing) PlatformPromptDialog(NativePrompt("Request needs attention", "The fixture server rejected the request.")) { showing = false } } }
        compose.runOnIdle { showing = true }
        compose.onNodeWithText("Close").assertIsDisplayed().assertIsFocused().performTvClick()
        compose.onNodeWithTag("platform-prompt").assertDoesNotExist()
        compose.runOnIdle { showing = true }
        compose.onNodeWithText("Close").assertIsFocused()
    }

    @Test fun destructivePromptInitiallyFocusesCancelAndNeverInvokesActionWhenCancelled() {
        var invoked = false
        var showing by mutableStateOf(true)
        compose.setContent { SeanimeTheme { if (showing) PlatformPromptDialog(NativePrompt("Disconnect folder?", "Files stay on the device.",
            listOf(NativePromptAction("Disconnect") { invoked = true }), "Cancel")) { showing = false } } }
        compose.onNodeWithText("Cancel").assertIsFocused()
        NativeScreenshotEvidence.capture("platform-confirm-cancel-focus")
        compose.onNodeWithText("Cancel").performTvClick()
        compose.onNodeWithTag("platform-prompt").assertDoesNotExist()
        compose.runOnIdle { assertFalse(invoked) }
    }

    @Test fun selectingActionCanReplacePromptWithoutItsDismissalClosingTheReplacement() {
        var prompt by mutableStateOf(NativePrompt("Folders", "Choose a folder", focusDismiss = false))
        prompt = prompt.copy(actions = listOf(NativePromptAction("USB library") { prompt = NativePrompt("USB library", "Ready to manage") }))
        var showing by mutableStateOf(true)
        compose.setContent { SeanimeTheme { if (showing) PlatformPromptDialog(prompt) { showing = false } } }
        // Same host contract as MainActivity: dismiss first, then publish the next prompt.
        compose.runOnIdle {
            prompt = prompt.copy(actions = listOf(NativePromptAction("USB library") {
                prompt = NativePrompt("USB library details", "Ready to manage")
                showing = true
            }))
        }
        compose.onNodeWithText("USB library").assertIsFocused().performTvClick()
        compose.onNodeWithText("USB library details").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsFocused()
    }

    @Test fun longPromptActionListRemainsRemoteNavigable() {
        var selected = -1
        var showing by mutableStateOf(true)
        val prompt = NativePrompt("Storage", "Select one folder", (0..29).map { index -> NativePromptAction("Folder $index") { selected = index } }, focusDismiss = false)
        compose.setContent { SeanimeTheme { if (showing) PlatformPromptDialog(prompt) { showing = false } } }
        compose.onNodeWithText("Folder 0").assertIsFocused()
        compose.onNodeWithTag("platform-actions").performScrollToNode(hasText("Folder 24"))
        compose.onNodeWithText("Folder 24").performTvClick()
        compose.runOnIdle { assertEquals(24, selected) }
        compose.onNodeWithTag("platform-prompt").assertDoesNotExist()
    }

    @Test fun longPromptMessageIsReadableWithArrowsWithoutInvokingTheAction() {
        var invoked = false
        var showing by mutableStateOf(true)
        val message = (1..40).joinToString("\n") { "Detail $it: Check this information before continuing." } + "\nFinal prompt detail"
        val prompt = NativePrompt("Review update", message, listOf(NativePromptAction("Download") { invoked = true }), "Cancel")
        compose.setContent { SeanimeTheme { if (showing) PlatformPromptDialog(prompt) { showing = false } } }
        compose.onNodeWithText("Cancel").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("Download").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_UP)
        val body = compose.onNodeWithTag("platform-message")
        body.assertIsFocused()
        var steps = 0
        while (body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].let { it.value() < it.maxValue() }) {
            assertTrue("The remote must reach the final prompt detail", steps++ < 100)
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            body.assertIsFocused()
        }
        assertTrue("The long prompt must require scrolling", steps > 0)
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Download").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Cancel").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("platform-prompt").assertDoesNotExist()
        compose.runOnIdle { assertFalse(invoked) }
    }

    private fun remote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }
}
