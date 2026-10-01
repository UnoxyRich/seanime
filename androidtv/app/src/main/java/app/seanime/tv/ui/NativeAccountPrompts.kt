package app.seanime.tv.ui

import app.seanime.tv.platform.NativePrompt
import app.seanime.tv.platform.NativePromptAction

/** Account actions stay explicit on a shared TV; opening this menu changes no account. */
internal fun nativeAccountPrompt(aniListConnected: Boolean, name: String, aniListOffline: Boolean = false, onAction: (String) -> Unit): NativePrompt =
    NativePrompt(
        title = "Accounts",
        message = (if (aniListConnected) "AniList: ${name.ifBlank { "Connected" }}" else "Using your local profile") +
            if (aniListOffline) "\nGo online from Offline before changing your AniList connection." else "",
        actions = buildList {
            if (!aniListOffline) {
                add(NativePromptAction(if (aniListConnected) "Reconnect AniList" else "Connect AniList") { onAction("oauth:anilist") })
                if (aniListConnected) add(NativePromptAction("Disconnect AniList") { onAction("logout:anilist") })
                if (aniListConnected) add(NativePromptAction("Upload local collection to AniList") { onAction("upload-local-anilist") })
            }
            add(NativePromptAction("Connect MyAnimeList") { onAction("oauth:mal") })
            // The existing status API does not expose MAL connection state. The explicit
            // logout action is available without guessing from a previous OAuth callback.
            add(NativePromptAction("Disconnect MyAnimeList") { onAction("logout:mal") })
        },
        focusDismiss = false,
    )

internal fun requireOnlineAniListConnection(offline: Boolean) {
    check(!offline) { "Open Offline and choose Go online before changing your AniList connection." }
}

internal fun nativeAccountDisconnectPrompt(provider: String, onDisconnect: () -> Unit): NativePrompt {
    require(provider in setOf("anilist", "mal"))
    val name = if (provider == "anilist") "AniList" else "MyAnimeList"
    return NativePrompt("Disconnect $name?",
        "Remove the saved $name connection from Seanime on this TV. Your account and online lists stay with $name.",
        listOf(NativePromptAction("Disconnect", onDisconnect)), dismissLabel = "Cancel")
}
