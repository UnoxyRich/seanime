package app.seanime.tv.ui

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry

/** TV Material intentionally has no touch-click handler: exercise the physical remote path. */
internal fun SemanticsNodeInteraction.performTvClick(): SemanticsNodeInteraction {
    performSemanticsAction(SemanticsActions.RequestFocus)
    assertIsFocused()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
    instrumentation.waitForIdleSync()
    return this
}
