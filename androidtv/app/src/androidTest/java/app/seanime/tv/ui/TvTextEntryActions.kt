package app.seanime.tv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

/** Done hides the editor's IME; it does not submit or assign focus to a footer action. */
internal fun ComposeTestRule.performTvImeDone(editorTag: String) {
    val editor = onNodeWithTag(editorTag).assertIsDisplayed().assertIsFocused()
    val view = (editor.fetchSemanticsNode().root as ViewRootForTest).view
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val previousFlags = automation.serviceInfo.flags
    try {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        editor.performImeAction()
        // A floating TV keyboard may reserve no inset. Check its actual window
        // as well as the editor's insets before sending the first physical key.
        waitUntil(10_000) {
            val windows = automation.windows
            try {
                view.isAttachedToWindow && view.isLaidOut && view.hasWindowFocus() &&
                    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false &&
                    windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            } finally {
                windows.forEach { it.recycle() }
            }
        }
        waitForIdle()
        editor.assertIsDisplayed().assertIsFocused()
    } finally {
        automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
    }
}
