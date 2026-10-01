package app.seanime.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo

/** Dialog/lazy targets must be placed and own a focused window before their first focus request. */
@Composable
internal fun Modifier.initialTvFocus(requester: FocusRequester, granted: MutableState<Boolean>): Modifier {
    var placed by remember(requester) { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(requester, placed, windowFocused, granted.value) {
        if (placed && windowFocused && !granted.value && requester.requestFocus()) granted.value = true
    }
    return focusRequester(requester).onGloballyPositioned { placed = it.isAttached }
}
