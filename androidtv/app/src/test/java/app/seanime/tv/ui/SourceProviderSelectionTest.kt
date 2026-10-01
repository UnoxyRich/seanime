package app.seanime.tv.ui

import app.seanime.tv.data.ExtensionItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceProviderSelectionTest {
    private val providers = listOf(ExtensionItem("first", "First"), ExtensionItem("saved", "Saved"), ExtensionItem("disabled", "Disabled", disabled = true))
    @Test fun savedProviderWinsInitiallyAndManualChoiceSurvivesRefresh() {
        assertEquals("saved", selectSourceProvider(providers, "", "saved"))
        assertEquals("first", selectSourceProvider(providers, "first", "saved"))
    }
    @Test fun unavailableProvidersFallBackAndNoneDoesNotSelectAnUnexpectedSource() {
        assertEquals("first", selectSourceProvider(providers, "removed", "disabled"))
        assertEquals("first", selectSourceProvider(providers, "", "unknown"))
        assertEquals("", selectSourceProvider(providers, "", "none"))
        assertEquals("first", selectSourceProvider(providers, "first", "none"))
        assertEquals("", selectSourceProvider(emptyList(), "saved", "saved"))
    }
}
