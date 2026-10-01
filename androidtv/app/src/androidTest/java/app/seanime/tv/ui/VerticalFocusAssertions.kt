package app.seanime.tv.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.assertTrue

/** Test the painted 1.1x TV button extent; clipped semantics bounds alone can conceal this regression. */
internal fun assertFocusedActionFitsVerticalViewport(compose: ComposeContentTestRule, matcher: SemanticsMatcher) {
    val target = compose.onNode(matcher).assertIsFocused().assertIsDisplayed().fetchSemanticsNode()
    val viewport = generateSequence(target.parent) { it.parent }
        .firstOrNull { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        ?.boundsInRoot ?: error("Focused action must have a vertical scrolling viewport")
    val scaledTop = target.positionInRoot.y - target.size.height * .05f
    val scaledBottom = target.positionInRoot.y + target.size.height * 1.05f
    assertTrue("Focused 1.1x button [$scaledTop, $scaledBottom] must fit the scroll viewport $viewport",
        scaledTop >= viewport.top - 1f && scaledBottom <= viewport.bottom + 1f)
}
