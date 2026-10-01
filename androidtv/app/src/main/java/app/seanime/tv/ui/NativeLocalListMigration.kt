package app.seanime.tv.ui

import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.ServerStatus
import app.seanime.tv.platform.NativePrompt
import app.seanime.tv.platform.NativePromptAction

internal fun requireLocalListMigrationAccount(status: ServerStatus, expectedName: String? = null): String {
    check(!status.offline) { "Go online before uploading your local collection to AniList." }
    val accountName = status.raw.optJSONObject("user")?.optJSONObject("viewer")?.optString("name").orEmpty()
    check(!status.isSimulated && accountName.isNotBlank()) { "Connect your AniList account before uploading your local collection." }
    check(expectedName == null || accountName == expectedName) { "The connected account changed. Review the upload confirmation again." }
    return accountName
}

internal fun nativeLocalListMigrationPrompt(name: String, upload: () -> Unit): NativePrompt = NativePrompt(
    "Upload local collection to AniList?",
    "Send your saved local anime and manga statuses, progress, ratings and dates to $name on AniList. Differing online entries may be updated. Review your AniList lists afterward; the server does not report each title's upload result.",
    listOf(NativePromptAction("Upload to $name", upload)), dismissLabel = "Cancel",
)

internal suspend fun uploadNativeLocalList(repo: SeanimeRepository, expectedName: String) {
    requireLocalListMigrationAccount(repo.status(), expectedName)
    check(repo.request("POST", "/api/v1/local/sync-simulated-to-anilist") == true) { "The server did not confirm the upload request. Check AniList before retrying." }
}
