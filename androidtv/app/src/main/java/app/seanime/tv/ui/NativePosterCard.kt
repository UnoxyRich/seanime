package app.seanime.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import app.seanime.tv.data.MediaCard
import app.seanime.tv.data.personalCollectionStatusLabel

/** Constant title/metadata slots keep every poster in a row aligned, including long names. */
@Composable
internal fun NativePosterCard(media: MediaCard, posterHeight: Dp, modifier: Modifier = Modifier,
    artwork: @Composable (Modifier) -> Unit = { imageModifier ->
        NativeArtwork(media.imageUrl, media.title, imageModifier, ContentScale.Crop)
    }, onClick: () -> Unit) {
    Card(onClick = onClick, scale = CardDefaults.scale(focusedScale = 1f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(12.dp))),
        modifier = modifier.fillMaxWidth()) {
        Column {
            artwork(Modifier.fillMaxWidth().height(posterHeight).background(MaterialTheme.colorScheme.surface))
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(media.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(personalCollectionStatusLabel(media.status) + if (media.progress > 0) " · Ep ${media.progress}" else "",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
