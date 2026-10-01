package app.seanime.tv.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry

/** TV Material intentionally has no touch-click handler: exercise the physical remote path. */
internal fun SemanticsNodeInteraction.performTvClick(): SemanticsNodeInteraction {
    awaitTvWindowFocus()
    performSemanticsAction(SemanticsActions.RequestFocus)
    assertIsFocused()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
    instrumentation.waitForIdleSync()
    return this
}

/** A composed dialog can expose semantics before Android routes remote keys to its window. */
internal fun SemanticsNodeInteraction.awaitTvWindowFocus(timeoutMillis: Long = 10_000): SemanticsNodeInteraction {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    do {
        // Reading the current node also synchronizes Compose work after a dialog is opened
        // or dismissed. Observing another resumed Activity would not establish this window.
        val view = (fetchSemanticsNode().root as ViewRootForTest).view
        if (view.isAttachedToWindow && view.isLaidOut && view.hasWindowFocus()) return this
        SystemClock.sleep(16)
    } while (SystemClock.uptimeMillis() < deadline)
    throw AssertionError("The remote target's attached, laid-out window did not acquire focus within $timeoutMillis ms")
}
