package app.seanime.tv.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Protect both content-color scopes: TV Text and Material3 widgets use different locals. */
@RunWith(AndroidJUnit4::class)
class NativeThemeContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun defaultNativeBodyTextRemainsReadableOnTheDarkRootSurface() {
        val colors = AtomicReference<Triple<Color, Color, Color>>()
        compose.setContent {
            SeanimeTheme {
                val background = androidx.compose.material3.MaterialTheme.colorScheme.background
                val materialText = androidx.compose.material3.LocalContentColor.current
                val televisionText = androidx.tv.material3.LocalContentColor.current
                SideEffect { colors.set(Triple(background, materialText, televisionText)) }
                androidx.tv.material3.Text("Native TV contrast fixture")
            }
        }
        compose.runOnIdle {
            val (background, materialText, televisionText) = requireNotNull(colors.get())
            assertTrue("Material3 default body text must meet 4.5:1 contrast against the native root",
                contrast(materialText, background) >= 4.5f)
            assertTrue("TV Material default body text must meet 4.5:1 contrast against the native root",
                contrast(televisionText, background) >= 4.5f)
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val one = first.luminance()
        val two = second.luminance()
        return (maxOf(one, two) + .05f) / (minOf(one, two) + .05f)
    }
}
