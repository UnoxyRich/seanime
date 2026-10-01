package app.seanime.tv.platform

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** Fresh TV presentation. No Media3 controls, legacy widgets, layouts, or track dialogs. */
@Stable
class NativeTvPlayerState {
    var title by mutableStateOf("")
    var position by mutableLongStateOf(0)
    var duration by mutableLongStateOf(0)
    var paused by mutableStateOf(true)
    var buffering by mutableStateOf(false)
    var controls by mutableStateOf(true)
    var error by mutableStateOf("")
    var translation by mutableStateOf("")
    var dialog by mutableStateOf<PlayerChoiceDialog?>(null)
    var interaction by mutableIntStateOf(0)
    var screenshot by mutableStateOf(false)
    var notice by mutableStateOf("")
    var noticeVersion by mutableIntStateOf(0)
    var skipLabel by mutableStateOf("")
    var canPrevious by mutableStateOf(false)
    var canNext by mutableStateOf(false)
    fun back(onExit: () -> Unit) {
        when { dialog != null -> dialog = null; controls && error.isBlank() -> controls = false; else -> onExit() }
    }
}

data class PlayerChoice(val id: String, val label: String, val selected: Boolean = false, val choose: () -> Unit)
data class PlayerChoiceDialog(val title: String, val choices: List<PlayerChoice>, val hint: String = "")
private val Ink = Color(0xFF08141E)
private val Panel = Color(0xFF152634)
private val Accent = Color(0xFF82E9D0)
private val Muted = Color(0xFFB6C8D2)

@Stable
private class PlayerFocusTransfer(val target: String?) {
    var placed by mutableStateOf(false)
    var applied by mutableStateOf(false)
    fun modifier(id: String): Modifier = if (id == target) Modifier.onGloballyPositioned {
        if (it.isAttached) placed = true
    } else Modifier
}

@Composable
fun NativeTvPlayerPresentation(state: NativeTvPlayerState, onAction: (String) -> Unit, onSeek: (Long) -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = Ink, onSurface = Color.White)) {
        val rootFocus = remember { FocusRequester() }
        val requesters = remember { mutableMapOf<String, FocusRequester>() }
        var lastFocused by remember { mutableStateOf("play") }
        // Android can assign focus to the first node before the window is ready.
        // Freeze the intended target until our explicit transfer succeeds, so
        // that automatic timeline focus cannot replace the initial Play target.
        val focus = remember(state.controls, state.error, state.dialog, state.screenshot, state.skipLabel.isNotBlank(), state.canPrevious, state.canNext) {
            PlayerFocusTransfer(when {
                state.screenshot || state.dialog != null -> null
                state.error.isNotBlank() -> "retry"
                state.controls -> lastFocused.takeUnless {
                    (it == "skip" && state.skipLabel.isBlank()) || (it == "previous" && !state.canPrevious) || (it == "next" && !state.canNext)
                } ?: "play"
                else -> "root"
            })
        }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val touch = { state.interaction++ }
        val onControlFocused: (String) -> Unit = { id ->
            if (focus.applied) { lastFocused = id; touch() }
        }
        BackHandler { state.back { onAction("exit") } }
        LaunchedEffect(focus, focus.placed, windowFocused) {
            if (focus.target == null || !focus.placed || !windowFocused || focus.applied) return@LaunchedEffect
            withFrameNanos { }
            val requester = if (focus.target == "root") rootFocus else requesters[focus.target]
            focus.applied = requester?.requestFocus() == true
            if (focus.applied && state.controls && state.error.isBlank() && focus.target !in setOf("root", "retry")) {
                focus.target?.let { lastFocused = it }
            }
        }
        LaunchedEffect(state.controls, state.paused, state.error, state.dialog, state.interaction, windowFocused, focus.applied) {
            if (state.controls && !state.paused && state.error.isBlank() && state.dialog == null && windowFocused && focus.applied) {
                delay(6000); state.controls = false
            }
        }
        LaunchedEffect(state.noticeVersion) { if (state.notice.isNotBlank()) { delay(6000); state.notice = "" } }
        Box(Modifier.fillMaxSize().testTag("native-player-presentation")
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    touch()
                    if (!state.controls && state.dialog == null && event.key in listOf(Key.DirectionCenter, Key.Enter, Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) {
                        state.controls = true; return@onPreviewKeyEvent true
                    }
                }
                false
            }) {
            Box(Modifier.fillMaxSize().then(focus.modifier("root")).focusRequester(rootFocus).focusable(!state.controls && state.error.isBlank() && state.dialog == null)
                .pointerInput(Unit) { detectTapGestures { touch(); state.controls = !state.controls } })
            if (state.translation.isNotBlank()) {
                Text(state.translation, fontSize = 26.sp, lineHeight = 33.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 70.dp).padding(bottom = if (state.controls && !state.screenshot) 270.dp else 45.dp)
                        .widthIn(max = 1000.dp).background(Color.Black.copy(alpha = .82f), RoundedCornerShape(10.dp)).padding(horizontal = 22.dp, vertical = 10.dp)
                        .testTag("native-translated-caption"))
            }
            if (state.notice.isNotBlank() && !state.screenshot) Text(state.notice, fontSize = 19.sp, color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.TopCenter).padding(top = 125.dp, start = 48.dp, end = 48.dp)
                    .widthIn(max = 800.dp).background(Panel, RoundedCornerShape(12.dp)).border(1.dp, Accent, RoundedCornerShape(12.dp))
                    .padding(20.dp).testTag("native-player-notice"))
            if (state.buffering && state.error.isBlank() && !state.screenshot) Text("Buffering…", color = Color.White, fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center).background(Ink.copy(alpha = .9f), RoundedCornerShape(14.dp)).padding(22.dp))
            if (state.controls && state.error.isBlank() && !state.screenshot) {
                Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Ink.copy(alpha = .95f), Color.Transparent))))
                Column(Modifier.align(Alignment.TopStart).padding(horizontal = 48.dp, vertical = 32.dp)) {
                    Text("NOW PLAYING", color = Accent, fontSize = 13.sp, letterSpacing = 2.sp)
                    Text(state.title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .98f), Ink)))
                    .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SeekRail(state, onSeek, requesters.getOrPut("seek") { FocusRequester() }, focus.modifier("seek")) { onControlFocused("seek") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("previous" to "Previous", "rewind" to "−10 sec", "play" to if (state.paused) "Play" else "Pause", "forward" to "+10 sec", "next" to "Next").forEach { (id, label) ->
                            PlayerTile(label, "native-player-$id", requesters.getOrPut(id) { FocusRequester() }, Modifier.weight(1f).then(focus.modifier(id)),
                                enabled = when (id) { "previous" -> state.canPrevious; "next" -> state.canNext; else -> true }, onFocused = { onControlFocused(id) }) {
                                touch(); onAction(id)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        (listOfNotNull(state.skipLabel.takeIf { it.isNotBlank() }?.let { "skip" to it }) +
                            listOf("audio" to "Audio", "subtitles" to "Subtitles", "picture" to "Picture", "options" to "More")).forEach { (id, label) ->
                            PlayerTile(label, "native-player-$id", requesters.getOrPut(id) { FocusRequester() }, Modifier.weight(1f).then(focus.modifier(id)), secondary = true, onFocused = { onControlFocused(id) }) {
                                if (id == "skip") {
                                    // A seek can replace Intro with Ending before
                                    // Compose observes the intervening empty label.
                                    lastFocused = "play"
                                    requesters["play"]?.requestFocus()
                                }
                                touch(); onAction(id)
                            }
                        }
                    }
                    Text("Back hides controls · Hold left or right on the timeline to seek", color = Muted, fontSize = 13.sp)
                }
            }
            if (state.error.isNotBlank() && !state.screenshot) Column(Modifier.fillMaxSize().background(Ink).testTag("native-player-error")
                .padding(horizontal = 64.dp, vertical = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Playback needs attention", fontSize = 32.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(state.error, fontSize = 20.sp, color = Muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 20.dp).widthIn(max = 760.dp).testTag("native-player-error-reason"))
                Text("Check the source or storage connection, then try again.", fontSize = 18.sp, color = Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, bottom = 24.dp).widthIn(max = 650.dp).testTag("native-player-error-help"))
                listOf("retry" to "Try again", "convert" to "Convert for this device", "external" to "Open in another player", "exit" to "Return to Seanime").forEach { (id, label) ->
                    PlayerTile(label, "native-player-$id", requesters.getOrPut(id) { FocusRequester() }, Modifier.width(430.dp).padding(vertical = 6.dp).then(focus.modifier(id))) { onAction(id) }
                }
            }
        }
        state.dialog?.let { dialog -> PlayerChoices(dialog, onMediaAction = onAction, onDismiss = { state.dialog = null; touch() }) }
    }
}

@Composable
private fun SeekRail(state: NativeTvPlayerState, onSeek: (Long) -> Unit, requester: FocusRequester, modifier: Modifier = Modifier, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().focusRequester(requester).testTag("native-player-seek").semantics { contentDescription = "Seek timeline, left and right seek ten seconds" }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                state.interaction++; onSeek(state.position + if (event.key == Key.DirectionLeft) -10_000 else 10_000); true
            } else false
        }.focusable().border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(state.position), color = Color.White, fontSize = 16.sp)
            Text(clock(state.duration), color = Muted, fontSize = 16.sp)
        }
        Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp)) {
            drawRoundRect(Panel)
            drawRoundRect(Accent, size = size.copy(width = size.width * if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f))
        }
    }
}

@Composable
private fun PlayerTile(label: String, tag: String, requester: FocusRequester? = null, modifier: Modifier = Modifier,
    secondary: Boolean = false, selected: Boolean = false, enabled: Boolean = true, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.heightIn(min = if (secondary) 48.dp else 56.dp)
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .background(if (!enabled) Panel.copy(alpha = .38f) else if (focused) Accent else Panel.copy(alpha = if (secondary) .78f else 1f), RoundedCornerShape(12.dp))
        .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else if (selected) Accent else Color.White.copy(alpha = .15f), RoundedCornerShape(12.dp))
        .testTag(tag).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text((if (selected) "✓  " else "") + label, color = if (!enabled) Muted.copy(alpha = .55f) else if (focused) Ink else Color.White, fontSize = if (secondary) 17.sp else 19.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PlayerChoices(dialog: PlayerChoiceDialog, onMediaAction: (String) -> Unit, onDismiss: () -> Unit) {
    val selected = dialog.choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val initialFocus = remember(dialog) { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = remember(dialog) { PlayerFocusTransfer("choice") }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val focusWhenPlaced = focus.modifier("choice")
        LaunchedEffect(focus, focus.placed, windowFocused) {
            if (!focus.placed || !windowFocused || focus.applied) return@LaunchedEffect
            withFrameNanos { }
            focus.applied = initialFocus.requestFocus()
        }
        LaunchedEffect(dialog) { listState.scrollToItem(selected) }
        Column(Modifier.widthIn(max = 670.dp).fillMaxWidth(.75f).heightIn(max = 460.dp).onPreviewKeyEvent { event ->
            val action = when (event.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_HEADSETHOOK -> "play"
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> "resume"
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward"
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> "exit"
                else -> null
            }
            if (action != null && event.type == KeyEventType.KeyDown) onMediaAction(action)
            action != null
        }.background(Ink, RoundedCornerShape(22.dp))
            .border(1.dp, Accent.copy(alpha = .5f), RoundedCornerShape(22.dp)).testTag("native-player-dialog").padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(dialog.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            if (dialog.hint.isNotBlank()) Text(dialog.hint, color = Muted, fontSize = 16.sp)
            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false).testTag("native-player-choices"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(dialog.choices, key = { _, item -> item.id }) { index, choice ->
                    PlayerTile(choice.label, "native-player-choice-${choice.id}", if (index == selected) initialFocus else null,
                        Modifier.fillMaxWidth().then(if (index == selected) focusWhenPlaced else Modifier), selected = choice.selected) { onDismiss(); choice.choose() }
                }
                item { PlayerTile("Close", "native-player-dialog-close", if (dialog.choices.isEmpty()) initialFocus else null, Modifier.fillMaxWidth().then(if (dialog.choices.isEmpty()) focusWhenPlaced else Modifier), secondary = true, onClick = onDismiss) }
            }
        }
    }
}

private fun clock(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}
