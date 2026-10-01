package app.seanime.tv.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@NativeTvViewports
@Composable
fun nativeSettingsRoot() = SettingsPreview("Settings", RootSettingsRows)

@PreviewTest
@NativeTvViewports
@Composable
fun nativeSettingsDeviceAndAccounts() = SettingsPreview(
    "Device & accounts", DeviceSettingsRows, "Folders, accounts and app updates", detailPage = true,
)

@PreviewTest
@NativeTvViewports
@Composable
fun nativeSettingsCategoryValues() = SettingsPreview(
    "Library & playback", CategorySettingsRows, detailPage = true,
)

@PreviewTest
@NativeTvLargeFontViewports
@Composable
fun nativeSettingsCategoryLargeFont() = SettingsPreview(
    "Library & playback", CategorySettingsRows, detailPage = true,
)

@Composable
private fun SettingsPreview(title: String, rows: List<NativeSettingsRow>, subtitle: String = "", detailPage: Boolean = false) {
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val railFocusGranted = remember { mutableStateOf(true) }
    // A supplied scroll position keeps the selected Settings row visible without claiming D-pad focus.
    val railState = rememberLazyListState(initialFirstVisibleItemIndex = (TvFeature.SETTINGS.ordinal - 4).coerceAtLeast(0))
    SeanimeTheme {
        NativeTvScaffold(
            destination = TvFeature.SETTINGS,
            statusLabel = "Ready",
            railExpanded = false,
            onNavigate = {},
            onRailFocusChanged = {},
            contentFocus = contentFocus,
            railFocus = railFocus,
            railFocusGranted = railFocusGranted,
            railState = railState,
        ) {
            NativeSettingsList(
                title = title, rows = rows, onActivate = {}, subtitle = subtitle,
                onBack = if (detailPage) ({}) else null,
                onRefresh = {},
            )
        }
    }
}

private val RootSettingsRows = listOf(
    NativeSettingsRow("device", "Device & accounts", detail = "Folders, accounts and app updates"),
    NativeSettingsRow("library-index", "Library index backup", detail = "Back up or restore file matches, locks and ignored flags"),
    NativeSettingsRow("section:library", "Library & playback"),
    NativeSettingsRow("section:manga", "Manga"),
    NativeSettingsRow("section:anilist", "AniList"),
    NativeSettingsRow("section:torrent", "Torrent client"),
    NativeSettingsRow("section:mediaPlayer", "Media player"),
    NativeSettingsRow("section:nakama", "Nakama"),
    NativeSettingsRow("section:notifications", "Notifications"),
    NativeSettingsRow("section:discord", "Discord"),
    NativeSettingsRow("section:autoDownloader", "Auto downloader"),
    NativeSettingsRow("section:listSync", "List synchronization"),
    NativeSettingsRow("section:debrid", "Debrid provider"),
    NativeSettingsRow("section:torrentstream", "Torrent streaming"),
    NativeSettingsRow("section:mediastream", "Media streaming & transcoding"),
)

private val DeviceSettingsRows = listOf(
    "Anime folder", "Manga folder", "Additional anime folder", "Screenshot folder", "Torrent download folder",
    "Manage folder access", "Connect AniList", "Connect MyAnimeList", "Manage accounts", "Check app update",
).mapIndexed { index, label -> NativeSettingsRow("device-$index", label) }

private val CategorySettingsRows = listOf(
    NativeSettingsRow("enableOnlineStreaming", "Enable online streaming", "Enabled"),
    NativeSettingsRow("defaultPlaybackSource", "Default playback source", "Online streaming"),
    NativeSettingsRow("libraryPaths", "Library folders", "3 folders"),
    NativeSettingsRow("apiToken", "API token", "Saved ••••••••"),
    NativeSettingsRow("torrentProvider", "Torrent provider", "Saved provider unavailable", detail = "Install a provider from Extensions to select it here."),
    NativeSettingsRow("autoUpdate", "Automatic updates", "Disabled"),
    NativeSettingsRow("advanced", "Advanced JSON editor", detail = "Edit structured settings"),
)
