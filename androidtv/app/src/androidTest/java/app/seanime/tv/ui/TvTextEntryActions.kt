package app.seanime.tv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

/** Select opens the TV IME; Done hides it without submitting or focusing a footer action. */
internal fun ComposeTestRule.enterTvTextAndDismissIme(editorTag: String, text: String) {
    val editor = onNodeWithTag(editorTag).assertIsDisplayed().assertIsFocused()
    val view = (editor.fetchSemanticsNode().root as ViewRootForTest).view
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val previousFlags = automation.serviceInfo.flags
    fun awaitKeyboard(visible: Boolean) {
        var lastObservation = "not observed"
        try {
            waitUntil(10_000) {
                val windows = automation.windows
                try {
                    val imeWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    val imeWindowVisible = imeWindows.any { !Rect().also(it::getBoundsInScreen).isEmpty }
                    val insetVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())
                    val ready = view.isAttachedToWindow && view.isLaidOut
                    val windowFocused = view.hasWindowFocus()
                    lastObservation = "editorReady=$ready windowFocused=$windowFocused insetVisible=$insetVisible imeWindows=${imeWindows.size} imeWindowVisible=$imeWindowVisible"
                    // The keyboard may own the active window while it is shown.
                    // After dismissal, this editor's window must own input again.
                    ready && (if (visible) insetVisible == true || imeWindowVisible
                        else windowFocused && insetVisible == false && imeWindows.isEmpty())
                } finally {
                    windows.forEach { it.recycle() }
                }
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("$editorTag IME visible=$visible was not observed: $lastObservation", failure)
        }
        Log.i("NativeTvTextEntry", "$editorTag IME visible=$visible $lastObservation")
    }
    try {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // Compose's TV text-field Select handler deliberately ignores virtual
        // keyboards and SOURCE_KEYBOARD (sendKeyDownUpSync uses both). Activate
        // the focused field through an actual enumerated DPAD input device.
        val dpad = requireNotNull(InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull()
            .firstOrNull { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_DPAD) }) {
            "No nonvirtual DPAD input device is available to activate $editorTag"
        }
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), action,
                KeyEvent.KEYCODE_DPAD_CENTER, 0, 0, dpad.id, 0, 0, InputDevice.SOURCE_DPAD))
        }
        // A cold input-method session can still be opening after the editor has
        // focus. Done before that transition can hide nothing and an initial
        // hidden state must not be mistaken for a completed keyboard dismissal.
        awaitKeyboard(visible = true)
        editor.assertIsFocused().performTextReplacement(text)
        editor.assertIsFocused().performImeAction()
        // A floating TV keyboard may reserve no inset. Check its actual window
        // as well as the editor's insets before sending the first physical key.
        awaitKeyboard(visible = false)
        waitForIdle()
        editor.assertIsDisplayed().assertIsFocused()
    } finally {
        automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
    }
}
