package app.seanime.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Nested detail/discovery routes must respect a newer focus choice in the shell. */
internal val LocalNativeNavigationOwnsFocus = staticCompositionLocalOf { false }

/** The drawer paints over content; expanding it never resizes or reorders the poster grid. */
@Composable
internal fun NativeTvScaffold(
    destination: TvFeature,
    statusLabel: String,
    railExpanded: Boolean,
    onNavigate: (TvFeature) -> Unit,
    onRailFocusChanged: (Boolean) -> Unit,
    contentFocus: FocusRequester,
    railFocus: FocusRequester,
    railFocusGranted: MutableState<Boolean>,
    railState: LazyListState = rememberLazyListState(),
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .padding(horizontal = 40.dp, vertical = 24.dp).testTag("native-navigation")) {
        Box(Modifier.fillMaxSize().padding(start = 84.dp).testTag("native-content")
            .focusRequester(contentFocus).focusRestorer().focusGroup()) { content() }
        if (railExpanded) Box(Modifier.fillMaxSize().padding(start = 64.dp)
            .background(Color.Black.copy(alpha = .24f)))
        Column(Modifier.width(if (railExpanded) 216.dp else 64.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(16.dp))
            .onFocusChanged { onRailFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    // An expanded overlay overlaps the first content column. Use the
                    // content focus group instead of unreliable spatial overlap search.
                    contentFocus.requestFocus()
                    true
                } else false
            }.focusGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.heightIn(min = 40.dp).fillMaxWidth().padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                Text(if (railExpanded) "seanime" else "s", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            LazyColumn(state = railState, verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(8.dp), modifier = Modifier.weight(1f).testTag("navigation-rail")) {
                items(TvFeature.entries, key = { it.name }) { feature ->
                    val selected = feature == destination
                    Button(onClick = { onNavigate(feature) },
                        modifier = Modifier.fillMaxWidth().height(44.dp).testTag("nav-${feature.name}")
                            .semantics { contentDescription = feature.label }
                            .then(if (selected) Modifier.initialTvFocus(railFocus, railFocusGranted) else Modifier),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        colors = ButtonDefaults.colors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .18f) else Color.Transparent,
                            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.primary,
                            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                        )) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            TvDestinationGlyph(feature)
                            if (railExpanded) Text(feature.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            Row(Modifier.heightIn(min = 28.dp).fillMaxWidth().padding(horizontal = 12.dp).testTag("navigation-footer"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(Modifier.size(8.dp)) { drawCircle(MaterialStatusColor) }
                if (railExpanded) Text(statusLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val MaterialStatusColor = Color(0xFF9EB5D4)

/** Original small line pictograms, sharing the button's focused foreground. */
@Composable
private fun TvDestinationGlyph(feature: TvFeature) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(1.7f * u)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x*u,y*u), Offset(x2*u,y2*u), strokeWidth = 1.7f*u)
        fun rect(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, Offset(x*u,y*u), Size(w*u,h*u), CornerRadius(u), style=stroke)
        fun circle(x: Float,y: Float,r: Float) = drawCircle(color,r*u,Offset(x*u,y*u),style=stroke)
        fun play(x: Float=9f,y: Float=6f) {
            val p=Path().apply { moveTo(x*u,y*u); lineTo((x+8)*u,(y+6)*u); lineTo(x*u,(y+12)*u); close() }
            drawPath(p,color,style=stroke)
        }
        when(feature) {
            TvFeature.LIBRARY -> { rect(3f,4f,5f,16f); rect(10f,4f,4f,16f); line(17f,4f,21f,20f) }
            TvFeature.ANILIST -> { circle(12f,7f,3f); drawArc(color,180f,180f,false,Offset(4*u,13*u),Size(16*u,12*u),style=stroke) }
            TvFeature.MANGA -> { rect(2f,4f,20f,16f); line(12f,4f,12f,20f); line(5f,8f,9f,8f); line(15f,8f,19f,8f) }
            TvFeature.OFFLINE -> { rect(3f,4f,18f,16f); line(7f,12f,10f,15f); line(10f,15f,17f,8f) }
            TvFeature.PLAYLISTS -> { line(3f,5f,19f,5f); line(3f,10f,12f,10f); line(3f,15f,9f,15f); play(13f,10f) }
            TvFeature.EXTENSIONS -> { rect(3f,3f,7f,7f); rect(14f,3f,7f,7f); rect(3f,14f,7f,7f); line(17.5f,14f,17.5f,21f); line(14f,17.5f,21f,17.5f) }
            TvFeature.STREAMING -> { rect(2f,4f,20f,16f); play(9f,6f) }
            TvFeature.DOWNLOADS -> { line(12f,3f,12f,15f); line(7f,10f,12f,15f); line(17f,10f,12f,15f); line(4f,17f,4f,21f); line(4f,21f,20f,21f); line(20f,21f,20f,17f) }
            TvFeature.NAKAMA -> { circle(8f,7f,3f); circle(17f,9f,2.5f); drawArc(color,180f,180f,false,Offset(2*u,13*u),Size(13*u,12*u),style=stroke); drawArc(color,180f,180f,false,Offset(14*u,15*u),Size(9*u,9*u),style=stroke) }
            TvFeature.SETTINGS -> { for (y in listOf(6f,12f,18f)) line(3f,y,21f,y); circle(8f,6f,2f); circle(16f,12f,2f); circle(10f,18f,2f) }
            TvFeature.LOGS -> { rect(5f,2f,14f,20f); line(8f,7f,16f,7f); line(8f,12f,16f,12f); line(8f,17f,13f,17f) }
        }
    }
}
