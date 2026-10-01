package app.seanime.tv.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.seanime.tv.NativeScreenshotEvidence
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSettingsFieldsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancellingListEditsDoesNotMutateOriginalOrSubmit() {
        val original = JSONArray().put(12).put(42)
        var submitted = false
        var dismissed = false
        compose.setContent { SeanimeTheme { SettingListDialog("hostUnsharedAnimeIds", original, { dismissed = true }) { submitted = true } } }
        compose.onNodeWithText("Remove 1").performTvClick()
        compose.onNodeWithText("Cancel").performTvClick()
        compose.runOnIdle {
            assertFalse(submitted); assertTrue(dismissed)
            assertEquals(2, original.length()); assertEquals(12, original.getInt(0))
        }
    }

    @Test fun listRemovalSavesNumericServerPayload() {
        var result: JSONArray? = null
        compose.setContent { SeanimeTheme { SettingListDialog("hostUnsharedAnimeIds", JSONArray().put(12).put(42), {}) { result = it } } }
        compose.onNodeWithText("Remove 1").performTvClick()
        compose.onNodeWithText("Save list").performTvClick()
        compose.runOnIdle { assertEquals(1, result?.length()); assertEquals(42, result?.getInt(0)); assertTrue(result?.get(0) is Number) }
    }

    @Test fun nativeChoiceReturnsContractValueAndKeepsCurrentVisible() {
        var selected: String? = null
        // Keep the selected contract value/index while exercising a two-line focus surface.
        val choices = settingChoices("mediastream", "transcodeHwAccel", "mediacodec").map {
            if (it.value == "mediacodec") it.copy(label = "${it.label}\nAndroid hardware encoding") else it
        }
        val selectedLabel = "✓ Android MediaCodec\nAndroid hardware encoding"
        compose.setContent { SeanimeTheme { SettingChoiceDialog("Encoder", "mediacodec", choices, {}) { selected = it } } }
        compose.onNodeWithText(selectedLabel).assertIsDisplayed().assertIsFocused()
        assertFocusedActionFitsVerticalViewport(compose, hasText(selectedLabel))
        NativeScreenshotEvidence.capture("native-setting-choice-scaled-focus")
        compose.onNodeWithText(selectedLabel).performTvClick()
        compose.runOnIdle { assertEquals("mediacodec", selected) }
    }
}
