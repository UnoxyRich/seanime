package app.seanime.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.seanime.tv.platform.NativePrompt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeAccountPromptTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingConnectionsRequiresTheNamedActionAndDisconnectConfirmation() {
        val connections = mutableListOf<String>()
        var disconnected: String? = null
        var prompt by mutableStateOf<NativePrompt?>(null)
        fun openAccounts() {
            prompt = nativeAccountPrompt(true, "Fixture account") { action ->
                if (action.startsWith("logout:")) {
                    val provider = action.substringAfter(':')
                    prompt = nativeAccountDisconnectPrompt(provider) { disconnected = provider }
                } else connections += action
            }
        }
        openAccounts()
        compose.setContent { SeanimeTheme { prompt?.let { PlatformPromptDialog(it) { prompt = null } } } }
        compose.onNodeWithText("AniList: Fixture account").assertIsDisplayed()
        compose.onNodeWithText("Reconnect AniList").assertIsFocused()
        compose.onNodeWithText("Disconnect AniList").performTvClick()
        compose.onNodeWithText("Disconnect AniList?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsFocused().performTvClick()
        compose.runOnIdle { assertNull(disconnected); assertTrue(connections.isEmpty()); openAccounts() }
        compose.onNodeWithTag("platform-actions").performScrollToNode(hasText("Disconnect MyAnimeList"))
        compose.onNodeWithText("Disconnect MyAnimeList").performTvClick()
        compose.onNodeWithText("Disconnect MyAnimeList?").assertIsDisplayed()
        compose.onNodeWithText("Disconnect").performTvClick()
        compose.runOnIdle { assertEquals("mal", disconnected); openAccounts() }
        compose.onNodeWithText("Reconnect AniList").performTvClick()
        compose.runOnIdle { assertEquals(listOf("oauth:anilist"), connections) }
    }

    @Test fun localProfileDoesNotOfferAnAniListDisconnectOrGuessMalConnectionState() {
        var connected = ""
        compose.setContent { SeanimeTheme {
            PlatformPromptDialog(nativeAccountPrompt(false, "") { connected = it }) {}
        } }
        compose.onNodeWithText("Using your local profile").assertIsDisplayed()
        compose.onNodeWithText("Disconnect AniList").assertDoesNotExist()
        compose.onNodeWithText("Upload local collection to AniList").assertDoesNotExist()
        compose.onNodeWithText("Connect MyAnimeList").performTvClick()
        compose.runOnIdle { assertEquals("oauth:mal", connected) }
    }

    @Test fun offlineAccountKeepsAniListChangesUnavailableWithoutChangingMalActions() {
        compose.setContent { SeanimeTheme {
            PlatformPromptDialog(nativeAccountPrompt(true, "Fixture account", aniListOffline = true) {}) {}
        } }
        compose.onNodeWithText("Reconnect AniList").assertDoesNotExist()
        compose.onNodeWithText("Disconnect AniList").assertDoesNotExist()
        compose.onNodeWithText("Upload local collection to AniList").assertDoesNotExist()
        compose.onNodeWithText("Connect MyAnimeList").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Disconnect MyAnimeList").assertIsDisplayed()
    }

    @Test fun localCollectionUploadNamesItsTargetAndRequiresItsOwnConfirmation() {
        var uploads = 0
        var prompt by mutableStateOf<NativePrompt?>(null)
        fun open() { prompt = nativeAccountPrompt(true, "Fixture account") { action ->
            if (action == "upload-local-anilist") prompt = nativeLocalListMigrationPrompt("Fixture account") { uploads++ }
        } }
        open()
        compose.setContent { SeanimeTheme { prompt?.let { PlatformPromptDialog(it) { prompt = null } } } }
        compose.onNodeWithTag("platform-actions").performScrollToNode(hasText("Upload local collection to AniList"))
        compose.onNodeWithText("Upload local collection to AniList").performTvClick()
        compose.onNodeWithText("Upload local collection to AniList?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsFocused().performTvClick()
        compose.runOnIdle { assertEquals(0, uploads); open() }
        compose.onNodeWithTag("platform-actions").performScrollToNode(hasText("Upload local collection to AniList"))
        compose.onNodeWithText("Upload local collection to AniList").performTvClick()
        compose.onNodeWithTag("platform-actions").performScrollToNode(hasText("Upload to Fixture account"))
        compose.onNodeWithText("Upload to Fixture account").performTvClick()
        compose.runOnIdle { assertEquals(1, uploads) }
    }
}
