package app.seanime.tv.ui

import androidx.compose.runtime.Composable
import app.seanime.tv.data.DownloadItem
import app.seanime.tv.data.SeanimeRepository

internal suspend fun requestExistingDebridDownload(repo: SeanimeRepository, item: DownloadItem, destination: String) {
    require(destination.isNotBlank() && !destination.contains('\u0000')) { "Choose a valid download folder" }
    check(repo.downloadDebridTorrent(item, destination) == true) { "The server did not confirm the debrid download request" }
}

@Composable
internal fun NativeDebridDownloadDialog(repo: SeanimeRepository, item: DownloadItem,
    onClose: () -> Unit, onRequested: () -> Unit) {
    NativeDownloadDestinationDialog(repo, "Download debrid files", item.name, "debrid-download", onClose,
        onDownload = { requestExistingDebridDownload(repo, item, it) }, onRequested = onRequested)
}
