package app.seanime.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.seanime.tv.data.MediaCard
import app.seanime.tv.data.SeanimeRepository
import app.seanime.tv.data.TorrentItem

/** Confirm a selected release while sharing the native server-folder picker with Downloads. */
@Composable
internal fun SourceDownloadDialog(repo: SeanimeRepository, media: MediaCard, torrent: TorrentItem, initialDebrid: Boolean,
    onClose: () -> Unit, onRequested: (String) -> Unit) {
    var debrid by remember { mutableStateOf(initialDebrid) }
    NativeDownloadDestinationDialog(repo, "Download release", torrent.name, "source-download", onClose,
        onDownload = { destination ->
            if (debrid) repo.addDebridTorrents(listOf(torrent), destination, media)
            else repo.downloadTorrents(listOf(torrent), destination, media)
        }, onRequested = {
            onRequested(if (debrid) "Release added to debrid for download. Manage its progress in Downloads." else "Download requested. Manage its progress in Downloads.")
        }) { busy ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton((if (!debrid) "✓ " else "") + "Torrent client", !busy, Modifier.testTag("source-download-torrent")) { debrid = false }
            ActionButton((if (debrid) "✓ " else "") + "Debrid", !busy, Modifier.testTag("source-download-debrid")) { debrid = true }
        }
    }
}
