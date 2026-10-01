package app.seanime.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Display strings only: routing keys and original setting values stay outside the UI. */
internal data class NativeSettingsRow(
    val id: String,
    val label: String,
    val value: String = "",
    val detail: String = "",
    val enabled: Boolean = true,
    val testTag: String = "settings-row-$id",
)

/** Root, device actions and category fields share one production TV list. */
@Composable
internal fun NativeSettingsList(
    title: String,
    rows: List<NativeSettingsRow>,
    onActivate: (String) -> Unit,
    subtitle: String = "",
    state: LazyListState = rememberLazyListState(),
    busy: Boolean = false,
    error: String? = null,
    message: String? = null,
    onBack: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    rowModifier: @Composable (String) -> Modifier = { Modifier },
    onRowFocused: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onBack != null) Button(onClick = onBack, modifier = Modifier.testTag("settings-back")) { Text("All settings") }
            if (onRefresh != null) Button(onClick = onRefresh, enabled = !busy,
                modifier = Modifier.testTag("settings-refresh")) { Text("Refresh") }
        }
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp)) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("settings-list"), state = state,
            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.id }) { row ->
                Button(onClick = { onActivate(row.id) }, enabled = row.enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(row.testTag)
                        .then(rowModifier(row.id)).onFocusChanged { if (it.isFocused) onRowFocused(row.id) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(12.dp)),
                        focusedDisabledBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(12.dp)))) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.bodySmall,
                                color = LocalContentColor.current.copy(alpha = .8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (row.value.isNotBlank()) Text(row.value, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}
