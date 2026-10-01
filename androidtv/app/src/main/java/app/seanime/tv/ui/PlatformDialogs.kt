package app.seanime.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.platform.NativePrompt

/** Fresh remote-first dialog presentation, shared by storage, exports and app updates. */
@Composable
internal fun PlatformPromptDialog(prompt: NativePrompt, onDismiss: () -> Unit) {
    val actionFocus = remember(prompt) { FocusRequester() }
    val dismissFocus = remember(prompt) { FocusRequester() }
    val initialFocusGranted = remember(prompt) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.78f).widthIn(max = 720.dp).heightIn(max = 480.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(28.dp)
            .testTag("platform-prompt"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(prompt.title, style = MaterialTheme.typography.headlineMedium)
            if (prompt.message.isNotBlank()) TvScrollableText(
                Modifier.fillMaxWidth().weight(1f, fill = false).testTag("platform-message"),
                exitFocus = if (prompt.actions.isEmpty()) dismissFocus else actionFocus,
            ) { Text(prompt.message, style = MaterialTheme.typography.bodyLarge) }
            if (prompt.actions.isNotEmpty()) LazyColumn(Modifier.weight(1f, fill = false).testTag("platform-actions"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(prompt.actions) { index, action ->
                    ActionButton(action.label, modifier = Modifier.fillMaxWidth()
                        .then(when {
                            index != 0 -> Modifier
                            !prompt.focusDismiss -> Modifier.initialTvFocus(actionFocus, initialFocusGranted)
                            else -> Modifier.focusRequester(actionFocus)
                        })) {
                        // Dismiss first so an action can safely open the next native prompt.
                        onDismiss()
                        action.invoke()
                    }
                }
            }
            ActionButton(prompt.dismissLabel, modifier = Modifier
                .then(if (prompt.focusDismiss || prompt.actions.isEmpty()) Modifier.initialTvFocus(dismissFocus, initialFocusGranted)
                    else Modifier.focusRequester(dismissFocus)), onClick = onDismiss)
        }
    }
}
