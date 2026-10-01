package app.seanime.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.seanime.tv.data.Playlist
import app.seanime.tv.data.PlaylistEpisode
import app.seanime.tv.data.SeanimeRepository

/** Capture a user's operation, never a stale complete playlist snapshot. */
internal sealed interface PlaylistEdit {
    data class Rename(val name: String) : PlaylistEdit
    data class Complete(val identity: String, val completed: Boolean) : PlaylistEdit
    data class Remove(val identity: String) : PlaylistEdit
    data class Move(val identity: String, val offset: Int) : PlaylistEdit
    data class Append(val episodes: List<PlaylistEpisode>) : PlaylistEdit
}

internal fun playlistEpisodeIdentity(item: PlaylistEpisode): String {
    val episode = requireNotNull(item.episode) { "This playlist episode is missing its source information" }
    val mediaId = episode.raw.optJSONObject("baseAnime")?.optLong("id") ?: 0
    return "$mediaId:${episodeIdentity(episode)}"
}

internal fun applyPlaylistEdit(latest: Playlist, edit: PlaylistEdit): Playlist {
    if (edit is PlaylistEdit.Rename) return latest.copy(name = edit.name)
    if (edit is PlaylistEdit.Append) {
        val known = latest.episodes.mapNotNull { runCatching { playlistEpisodeIdentity(it) }.getOrNull() }.toMutableSet()
        val added = edit.episodes.filter { known.add(playlistEpisodeIdentity(it)) }
        return latest.copy(episodes = latest.episodes + added)
    }
    val identity = when (edit) {
        is PlaylistEdit.Complete -> edit.identity
        is PlaylistEdit.Remove -> edit.identity
        is PlaylistEdit.Move -> edit.identity
        else -> error("Unsupported playlist edit")
    }
    val matches = latest.episodes.indices.filter { index ->
        runCatching { playlistEpisodeIdentity(latest.episodes[index]) }.getOrNull() == identity
    }
    check(matches.size == 1) { "The playlist changed. Refresh it and select the episode again." }
    val index = matches.single()
    val episodes = latest.episodes.toMutableList()
    when (edit) {
        is PlaylistEdit.Complete -> episodes[index] = episodes[index].copy(completed = edit.completed)
        is PlaylistEdit.Remove -> episodes.removeAt(index)
        is PlaylistEdit.Move -> {
            require(edit.offset == -1 || edit.offset == 1)
            val destination = (index + edit.offset).coerceIn(0, episodes.lastIndex)
            episodes.add(destination, episodes.removeAt(index))
        }
        else -> error("Unsupported playlist edit")
    }
    return latest.copy(episodes = episodes)
}

/** The existing API has no revision/ETag; refresh immediately before its replacement PATCH. */
internal suspend fun editCurrentPlaylist(repo: SeanimeRepository, id: Int, edit: PlaylistEdit): Playlist {
    val latest = repo.playlists().singleOrNull { it.id == id } ?: error("This playlist was removed. Refresh your playlists.")
    return repo.updatePlaylist(applyPlaylistEdit(latest, edit))
}

/** Initial loading belongs to the screen; this callback runs on subsequent foreground returns. */
@Composable
internal fun OnPlaylistScreenResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val callback = rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        var leftForeground = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) leftForeground = true
            if (event == Lifecycle.Event.ON_RESUME && leftForeground) {
                leftForeground = false
                callback.value()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
