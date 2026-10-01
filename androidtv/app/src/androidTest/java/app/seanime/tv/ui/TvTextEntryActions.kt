package app.seanime.tv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.InputDevice
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
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A temporary kernel-backed test D-pad, using Android's documented CTS uinput protocol.
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/uinput/README.md
 * Keep this outside the Compose rule: input-device configuration changes happen before
 * the Activity is launched and after it is destroyed, never in the middle of an editor.
 */
internal class TvDpadInputRule : TestRule {
    private var commands: ParcelFileDescriptor.AutoCloseOutputStream? = null

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (Build.VERSION.SDK_INT < 31) error("TV IME input testing requires Android 12+ UiAutomation shell stdin")
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val input = instrumentation.targetContext.getSystemService(Context.INPUT_SERVICE) as InputManager
            val name = "Seanime test D-pad ${UUID.randomUUID()}"
            val registered = AtomicReference<InputDevice?>()
            val added = CountDownLatch(1)
            val removed = CountDownLatch(1)
            val listener = object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) {
                    input.getInputDevice(deviceId)?.takeIf { it.name == name }?.let {
                        registered.set(it)
                        added.countDown()
                    }
                }
                override fun onInputDeviceChanged(deviceId: Int) = Unit
                override fun onInputDeviceRemoved(deviceId: Int) {
                    if (registered.get()?.id == deviceId) removed.countDown()
                }
            }
            input.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
            var pipes: Array<ParcelFileDescriptor>? = null
            var failure: Throwable? = null
            try {
                // This is a software-created test device, not a claimed physical remote.
                // Unlike sendKeySync/injectInputEvent, its events enter InputReader and
                // retain the registered device ID through InputDispatcher.
                pipes = instrumentation.uiAutomation.executeShellCommandRw("uinput -")
                commands = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
                // Generic.kl maps these Linux codes to Up, Down, Left, Right and Select.
                // All five are needed for Android to classify the device as SOURCE_DPAD.
                writeCommand("""{"id":1,"command":"register","name":"$name","vid":0,"pid":0,"bus":"usb","configuration":[{"type":100,"data":[1]},{"type":101,"data":[103,108,105,106,353]}]}""")
                check(added.await(10, TimeUnit.SECONDS)) { "Android did not register the temporary uinput D-pad" }
                val device = checkNotNull(registered.get())
                check(!device.isVirtual && device.supportsSource(InputDevice.SOURCE_DPAD)) {
                    "Temporary uinput device lacks D-pad classification: $device"
                }
                Log.i("NativeTvTextEntry", "uinput D-pad registered id=${device.id} sources=${device.sources}")
                base.evaluate()
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                var cleanupFailure: Throwable? = null
                fun cleanup(action: () -> Unit) {
                    try {
                        action()
                    } catch (caught: Throwable) {
                        val first = cleanupFailure
                        if (first == null) cleanupFailure = caught
                        else if (first !== caught) first.addSuppressed(caught)
                    }
                }
                // EOF unregisters only this process's temporary device. No settings,
                // permission identity, existing device or key layout is changed.
                cleanup { commands?.close() }
                commands = null
                // Close every descriptor independently, including stdin if the stream
                // close failed, before awaiting removal. Cleanup must not stop early.
                pipes?.forEach { descriptor -> cleanup { descriptor.close() } }
                if (registered.get() != null) {
                    cleanup {
                        check(removed.await(10, TimeUnit.SECONDS)) { "Temporary uinput D-pad was not removed after EOF" }
                        Log.i("NativeTvTextEntry", "uinput D-pad removed id=${registered.get()?.id}")
                    }
                }
                cleanup { input.unregisterInputDeviceListener(listener) }
                cleanupFailure?.let { caught ->
                    val original = failure
                    if (original == null) throw caught
                    if (original !== caught) original.addSuppressed(caught)
                }
            }
        }
    }

    fun pressSelect() {
        // EV_KEY KEY_SELECT down/up, each followed by EV_SYN SYN_REPORT. Awaiting
        // the actual IME window below is the delivery barrier for this async pipe.
        writeCommand("""{"id":1,"command":"inject","events":[1,353,1,0,0,0,1,353,0,0,0,0]}""")
    }

    private fun writeCommand(command: String) {
        checkNotNull(commands) { "TvDpadInputRule must wrap the Compose rule" }.apply {
            write((command + "\n").toByteArray(Charsets.UTF_8))
            flush()
        }
    }
}

/** Select opens the TV IME; Done hides it without submitting or focusing a footer action. */
internal fun ComposeTestRule.enterTvTextAndDismissIme(editorTag: String, text: String, dpad: TvDpadInputRule) {
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
        // Compose ignores the virtual keyboard used by Instrumentation. Send
        // Select through the temporary registered D-pad's actual kernel stream.
        dpad.pressSelect()
        // A cold input-method session can still be opening after the editor has
        // focus. Done before that transition can hide nothing and an initial
        // hidden state must not be mistaken for a completed keyboard dismissal.
        awaitKeyboard(visible = true)
        editor.assertIsFocused().performTextReplacement(text)
        editor.assertIsFocused().performImeAction()
        // A floating TV keyboard may reserve no inset. Check its actual window
        // as well as the editor's insets before sending the next D-pad key.
        awaitKeyboard(visible = false)
        waitForIdle()
        editor.assertIsDisplayed().assertIsFocused()
    } finally {
        automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
    }
}
