package app.seanime.tv.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.tooling.preview.Preview
import app.seanime.tv.data.MediaCard

/** Fixed logical TV viewports; 160 dpi keeps image pixels equal to layout dp. */
@Preview(
    name = "TV 960 x 540 dp", widthDp = 960, heightDp = 540,
    device = "spec:width=960dp,height=540dp,dpi=160",
    uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES,
    showSystemUi = false, fontScale = 1f,
)
@Preview(
    name = "TV 1280 x 720 dp", widthDp = 1280, heightDp = 720,
    device = "spec:width=1280dp,height=720dp,dpi=160",
    uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES,
    showSystemUi = false, fontScale = 1f,
)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
internal annotation class NativeTvViewports

@Preview(
    name = "TV 960 x 540 dp font130percent", widthDp = 960, heightDp = 540,
    device = "spec:width=960dp,height=540dp,dpi=160",
    uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES,
    showSystemUi = false, fontScale = 1.3f,
)
@Preview(
    name = "TV 1280 x 720 dp font130percent", widthDp = 1280, heightDp = 720,
    device = "spec:width=1280dp,height=720dp,dpi=160",
    uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES,
    showSystemUi = false, fontScale = 1.3f,
)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
internal annotation class NativeTvLargeFontViewports

internal val PreviewLibraryCards = List(24) { index ->
    val titles = listOf(
        "The Lantern Keepers of the Last Winter Observatory",
        "Night Passage",
        "A Thousand Maps Beyond the Edge of the Sea",
        "Orbit 07",
        "The Quiet City and Its Extraordinary Visitors",
        "After the Rain",
    )
    MediaCard(
        id = (index + 1).toLong(), title = titles[index % titles.size],
        status = listOf("CURRENT", "PLANNING", "COMPLETED", "PAUSED")[index % 4],
        progress = if (index % 4 == 1) 0 else index + 3,
        totalEpisodes = 24,
    )
}

/** Original fictional poster artwork supplies pixels without a service or file dependency. */
@Composable
internal fun FictionalPosterArtwork(index: Int, modifier: Modifier) {
    val palettes = listOf(
        Color(0xFF21395B) to Color(0xFFEAA776),
        Color(0xFF234B52) to Color(0xFFA2D8BD),
        Color(0xFF54304E) to Color(0xFFE7BBC7),
        Color(0xFF273468) to Color(0xFF89ADEB),
        Color(0xFF6A3D37) to Color(0xFFF1C284),
        Color(0xFF344A46) to Color(0xFFC7D8AA),
    )
    val (background, accent) = palettes[index % palettes.size]
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(background, background.copy(red = background.red * .4f))))
        drawCircle(accent, radius = size.width * .25f, center = Offset(size.width * .69f, size.height * .3f))
        val mountain = Path().apply {
            moveTo(0f, size.height * .8f)
            lineTo(size.width * .36f, size.height * .42f)
            lineTo(size.width * .72f, size.height * .86f)
            lineTo(size.width, size.height * .64f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(mountain, Color(0xFF111D2A).copy(alpha = .8f))
        repeat(3) { line ->
            drawRect(accent.copy(alpha = .55f), Offset(size.width * .12f, size.height * (.86f + line * .035f)),
                Size(size.width * (.54f - line * .1f), size.height * .009f))
        }
    }
}
