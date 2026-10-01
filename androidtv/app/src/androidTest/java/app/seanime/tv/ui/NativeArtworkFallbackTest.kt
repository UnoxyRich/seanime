package app.seanime.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import app.seanime.tv.data.SeanimeApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalTestApi::class)
class NativeArtworkFallbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingCoverHasUsefulSemanticsKeepsItsSizeAndDoesNotTakeRemoteFocus() = fixture { _, server, show ->
        show {
            Row {
                Button(onClick = {}, modifier = Modifier.testTag("before-artwork")) { Text("Before") }
                NativeArtwork(null, "Missing title cover", Modifier.size(140.dp, 210.dp).testTag("missing-artwork"))
                Button(onClick = {}, modifier = Modifier.testTag("after-artwork")) { Text("After") }
            }
        }
        compose.onNodeWithTag("missing-artwork").assertIsDisplayed().assertWidthIsEqualTo(140.dp).assertHeightIsEqualTo(210.dp)
            .assertContentDescriptionEquals("Missing title cover")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No artwork"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        compose.onNodeWithText("No artwork", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("before-artwork").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("before-artwork").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("after-artwork").assertIsFocused()
        assertEquals(0, server.requestCount)
    }

    @Test fun failedCoverUsesAStableFallbackWhilePendingCoverShowsStaticLoading() = fixture { api, server, show ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/pending.jpg") {
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            } else MockResponse().setResponseCode(404)
        }
        show {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NativeArtwork(api.absoluteUrl("/missing.jpg"), "Unavailable title cover", Modifier.size(140.dp, 210.dp).testTag("failed-artwork"))
                NativeArtwork(api.absoluteUrl("/pending.jpg"), "Pending title cover", Modifier.size(140.dp, 210.dp).testTag("pending-artwork"))
            }
        }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Image unavailable", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("failed-artwork").assertWidthIsEqualTo(140.dp).assertHeightIsEqualTo(210.dp)
            .assertContentDescriptionEquals("Unavailable title cover")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Image unavailable"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        compose.onNodeWithTag("pending-artwork").assertIsDisplayed().assertWidthIsEqualTo(140.dp).assertHeightIsEqualTo(210.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading image"))
        compose.onNodeWithText("Loading image", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun smallPickerArtworkUsesIconOnlyButRetainsAccessibleStateAndExactBounds() = fixture { _, server, show ->
        show { NativeArtwork(null, "Small title cover", Modifier.size(48.dp, 68.dp).testTag("small-artwork")) }
        compose.onNodeWithTag("small-artwork").assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(68.dp)
            .assertContentDescriptionEquals("Small title cover")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No artwork"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        compose.onAllNodesWithText("No artwork", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(0, server.requestCount)
    }

    private fun fixture(test: (SeanimeApiClient, MockWebServer, (@Composable () -> Unit) -> Unit) -> Unit) {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            SeanimeApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString()).use { api ->
                var showing by mutableStateOf(true)
                try {
                    test(api, server) { content -> compose.setContent {
                        if (showing) SeanimeTheme { NativeArtworkProvider(api, content) }
                    } }
                } finally { compose.runOnIdle { showing = false }; compose.waitForIdle() }
            }
        }
    }
}
