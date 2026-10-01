package app.seanime.tv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import app.seanime.tv.NativeScreenshotEvidence
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class NativeTextEntryImeTest {
    @get:Rule val compose = createComposeRule()
    private var timeline: File? = null

    private fun record(stage: String) {
        timeline?.appendText("${SystemClock.elapsedRealtime()} $stage\n")
    }

    @RequiresApi(29) private fun awaitIme(visible: Boolean) {
        var polls = 0
        var lastTop: Int? = null
        var lastProbeStart = 0L
        var lastProbeEnd = 0L
        var maxProbeDuration = 0L
        fun probeSummary() = "polls=$polls top=$lastTop lastStartMs=$lastProbeStart lastEndMs=$lastProbeEnd maxDurationMs=$maxProbeDuration"
        record("ime-wait-start visible=$visible")
        try {
            compose.waitUntil(10_000) {
                polls++
                lastProbeStart = SystemClock.elapsedRealtime()
                lastTop = imeTop()
                lastProbeEnd = SystemClock.elapsedRealtime()
                maxProbeDuration = maxOf(maxProbeDuration, lastProbeEnd - lastProbeStart)
                (lastTop != null) == visible
            }
            record("ime-wait-pass visible=$visible ${probeSummary()}")
        } catch (failure: Throwable) {
            // Preserve the last probe before the more expensive failure capture.
            // A keyboard visible in a later PNG does not prove it met this bound.
            record("ime-wait-fail visible=$visible ${probeSummary()} type=${failure.javaClass.simpleName}")
            throw failure
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29) fun numericKeyboardLeavesTheFieldAndFooterVisibleAndDoneDoesNotSave() = withImeWindowEvidence("numeric") {
        val saved = CopyOnWriteArrayList<String>()
        render(saved, multiline = false)
        compose.onNodeWithTag("fixture-editor").assertIsFocused().performTextReplacement("8.5")
        record("focused-text-replaced")
        awaitIme(visible = true)
        captureWindowEvidence("numeric-shown")
        assertEditorAndFooterVisibleAboveIme()
        NativeScreenshotEvidence.capture("text-entry-numeric-ime-full-footer")
        compose.onNodeWithTag("fixture-editor").performImeAction()
        awaitIme(visible = false)
        compose.onNodeWithTag("fixture-editor").assertIsFocused().assertTextContains("8.5")
        assertTrue(saved.isEmpty())
        pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("text-entry-cancel").assertIsFocused()
        pressRemote(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("text-entry-save").assertIsFocused()
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("fixture-editor").assertDoesNotExist()
        assertEquals(listOf("8.5"), saved.toList())
    }

    @Test @SdkSuppress(minSdkVersion = 29) fun firstPhysicalBackHidesMultilineKeyboardAndLeavesTheDraftOpen() = withImeWindowEvidence("multiline") {
        val saved = CopyOnWriteArrayList<String>()
        val draft = (1..12).joinToString("\n") { "Draft line $it" }
        val longHelp = (1..20).joinToString("\n") { "Help line $it: Keep this guidance available." }
        render(saved, multiline = true, helper = longHelp)
        compose.onNodeWithTag("fixture-editor").assertIsFocused().performTextReplacement(draft)
        record("focused-text-replaced")
        awaitIme(visible = true)
        captureWindowEvidence("multiline-shown")
        assertEditorAndFooterVisibleAboveIme()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitIme(visible = false)
        compose.onNodeWithTag("fixture-editor").assertIsFocused().assertTextContains(draft)
        assertTrue(saved.isEmpty())
        pressRemote(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("text-entry-helper").assertIsFocused().assertTextContains(longHelp)
        val start = helperScrollPosition()
        pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
        val afterDown = helperScrollPosition()
        assertTrue("D-pad Down must scroll overflowing helper text", afterDown > start)
        pressRemote(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("D-pad Up must scroll helper text back", helperScrollPosition() < afterDown)
        while (helperScrollPosition() < helperScrollMaximum()) {
            val before = helperScrollPosition()
            pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("text-entry-helper").assertIsFocused()
            assertTrue("Each D-pad Down must advance to the end of help", helperScrollPosition() > before)
        }
        NativeScreenshotEvidence.capture("text-entry-long-helper-focused")
        pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("fixture-editor").assertIsFocused().assertTextContains(draft)
        assertTrue(saved.isEmpty())
        awaitIme(visible = true)
        pressRemote(KeyEvent.KEYCODE_BACK)
        awaitIme(visible = false)
        compose.onNodeWithTag("fixture-editor").assertIsFocused().assertTextContains(draft)
        pressRemote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("text-entry-cancel").assertIsFocused()
        pressRemote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("fixture-editor").assertDoesNotExist()
        assertTrue(saved.isEmpty())
    }

    private fun pressRemote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }

    private fun helperScrollPosition(): Float = compose.onNodeWithTag("text-entry-helper").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun helperScrollMaximum(): Float = compose.onNodeWithTag("text-entry-helper").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    @RequiresApi(29) private fun assertEditorAndFooterVisibleAboveIme() {
        val top = requireNotNull(imeTop())
        for (tag in listOf("fixture-editor", "text-entry-cancel", "text-entry-save")) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("$tag overlaps IME at $top", node.positionOnScreen.y + node.size.height <= top + 1f)
        }
        val editor = compose.onNodeWithTag("fixture-editor").fetchSemanticsNode()
        val body = compose.onNodeWithTag("text-entry-body").fetchSemanticsNode()
        val helper = compose.onNodeWithTag("text-entry-helper").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Helper overlaps the editor frame", helper.positionOnScreen.y + helper.size.height <= editor.positionOnScreen.y + 1f)
        assertTrue("Editor frame is clipped above its bounded body", editor.positionOnScreen.y >= body.positionOnScreen.y - 1f)
        assertTrue("Editor frame extends below its bounded body",
            editor.positionOnScreen.y + editor.size.height <= body.positionOnScreen.y + body.size.height + 1f)
    }

    private fun render(saved: MutableList<String>, multiline: Boolean,
        helper: String = "Use one decimal place. Leave empty to remove your rating.") {
        record("render-start")
        compose.setContent {
            SeanimeTheme {
                var open by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize().background(androidx.tv.material3.MaterialTheme.colorScheme.background)) {
                    if (open) TextEntryDialog("Rating", "Rating (0–10)", "9.5",
                        helper = helper,
                        multiline = multiline, inputModifier = Modifier.testTag("fixture-editor"),
                        keyboardType = if (multiline) KeyboardType.Text else KeyboardType.Decimal,
                        onDismiss = { open = false }, onSubmit = { saved.add(it) })
                }
            }
        }
        record("render-end")
    }

    @RequiresApi(29) private fun imeTop(): Int? {
        // Leanback's floating numeric IME is a visible input-method window but
        // may reserve no application inset. Use its actual window geometry.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val inputWindows = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val visibleWindowTop = inputWindows.mapNotNull { window ->
            Rect().also(window::getBoundsInScreen).takeUnless(Rect::isEmpty)?.top
        }.minOrNull()
        if (visibleWindowTop != null) return visibleWindowTop
        var top: Int? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            top = WindowInspector.getGlobalWindowViews().mapNotNull { view ->
                val insets = ViewCompat.getRootWindowInsets(view) ?: return@mapNotNull null
                if (!insets.isVisible(WindowInsetsCompat.Type.ime())) return@mapNotNull null
                val bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                if (bottom <= 0) return@mapNotNull null
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                location[1] + view.height - bottom
            }.minOrNull()
        }
        return top
    }

    @RequiresApi(29) private fun withImeWindowEvidence(name: String, test: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "native-test-diagnostics").apply { mkdirs() }
        timeline = File(directory, "text-entry-ime-$name-timeline.txt").apply {
            writeText("scenario=$name epochMs=${System.currentTimeMillis()}\n")
        }
        record("test-start")
        val automation = instrumentation.uiAutomation
        val original = automation.serviceInfo
        val originalFlags = original.flags
        original.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = original
        try { test() }
        catch (failure: Throwable) {
            record("test-failure type=${failure.javaClass.simpleName}")
            runCatching { captureWindowEvidence("$name-failure") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            val current = automation.serviceInfo
            current.flags = originalFlags
            automation.serviceInfo = current
            record("test-finish")
            timeline = null
        }
    }

    @RequiresApi(29) private fun captureWindowEvidence(stage: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val text = StringBuilder("stage=$stage\n")
        instrumentation.uiAutomation.windows.forEach { window ->
            val bounds = Rect().also(window::getBoundsInScreen)
            val root = window.root
            text.append("window id=${window.id} type=${window.type} bounds=$bounds active=${window.isActive} focused=${window.isFocused} title=${window.title} rootPackage=${root?.packageName}\n")
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && root != null) {
                val pending = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                pending.add(root)
                var inspected = 0
                while (pending.isNotEmpty() && inspected++ < 100) {
                    val node = pending.removeFirst()
                    text.append("  ime-node class=${node.className} visible=${node.isVisibleToUser} bounds=${Rect().also(node::getBoundsInScreen)}\n")
                    for (i in 0 until node.childCount) node.getChild(i)?.let(pending::addLast)
                }
            }
        }
        instrumentation.runOnMainSync {
            WindowInspector.getGlobalWindowViews().forEach { view ->
                val insets = ViewCompat.getRootWindowInsets(view)
                val location = IntArray(2).also(view::getLocationOnScreen)
                text.append("app-window ${view.javaClass.name} location=${location.toList()} size=${view.width}x${view.height} imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} imeInsets=${insets?.getInsets(WindowInsetsCompat.Type.ime())}\n")
            }
        }
        for (tag in listOf("fixture-editor", "text-entry-viewport", "text-entry-dialog", "text-entry-body", "text-entry-helper", "text-entry-cancel", "text-entry-save")) {
            val bounds = runCatching {
                val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                "screen=${node.positionOnScreen} size=${node.size} rootBounds=${node.boundsInRoot}"
            }.getOrElse { "unavailable=${it.message}" }
            text.append("native-node $tag $bounds\n")
        }
        val directory = File(instrumentation.targetContext.cacheDir, "native-test-diagnostics").apply { mkdirs() }
        File(directory, "text-entry-ime-$stage.txt").writeText(text.toString())
        // Capture before the fixture is torn down, including a failed visibility probe.
        NativeScreenshotEvidence.capture("text-entry-ime-$stage")
    }
}
