package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFeatureDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancellingConfirmationNeverInvokesMutation() {
        var confirmed = false
        var dismissed = false
        compose.setContent { MaterialTheme { ConfirmFeatureDialog("Delete item?", "This removes the item.", { dismissed = true }) { confirmed = true } } }
        compose.onNodeWithText("Cancel").performTvClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(confirmed) }
    }

    @Test fun blankNameCannotBeSubmitted() {
        var saved: String? = null
        compose.setContent { MaterialTheme { TextEntryDialog("New playlist", "Name", onDismiss = {}, onSubmit = { saved = it }) } }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Weekend")
        compose.onNodeWithText("Save").assertIsEnabled().performTvClick()
        compose.runOnIdle { assertEquals("Weekend", saved) }
    }

    @Test fun optionalSettingsCanBeClearedExplicitly() {
        var saved: String? = null
        compose.setContent { MaterialTheme { TextEntryDialog("Optional setting", "Value", initial = "Old", allowEmpty = true, onDismiss = {}, onSubmit = { saved = it }) } }
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("Save").assertIsEnabled().performTvClick()
        compose.runOnIdle { assertEquals("", saved) }
    }

    @Test fun longConfirmationCanBeReadWithArrowsAndReturnsToCancel() {
        var confirmed = false
        var dismissed = false
        val detail = (1..40).joinToString("\n") { "File $it: Review this affected media file before confirming." } + "\nFinal affected file"
        compose.setContent { SeanimeTheme { ConfirmFeatureDialog("Review files", detail, { dismissed = true }) { confirmed = true } } }
        compose.onNodeWithText("Cancel").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_UP)
        val body = compose.onNodeWithTag("feature-confirm-body")
        body.assertIsFocused()
        val start = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > start)
        remote(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(start, body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        var steps = 0
        while (body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].let { it.value() < it.maxValue() }) {
            assertTrue("Each remote press must advance through the bounded detail", steps++ < 100)
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
            body.assertIsFocused()
        }
        // Reaching the maximum scroll position exposes the last line; one further Down exits the body.
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Cancel").assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertTrue(dismissed); assertFalse(confirmed) }
    }

    private fun remote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }
}
