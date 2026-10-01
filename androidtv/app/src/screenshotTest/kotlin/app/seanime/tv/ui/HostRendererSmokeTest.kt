package app.seanime.tv.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

/** Verifies the host rendering pipeline only; this is not a production screen. */
@PreviewTest
@Preview(
    name = "TV 960 x 540 dp",
    widthDp = 960,
    heightDp = 540,
    device = "spec:width=960dp,height=540dp,dpi=160",
    uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES,
    showSystemUi = false,
    fontScale = 1f,
)
@Preview(
    name = "TV 1280 x 720 dp",
    widthDp = 1280,
    heightDp = 720,
    device = "spec:width=1280dp,height=720dp,dpi=160",
    uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES,
    showSystemUi = false,
    fontScale = 1f,
)
@Composable
fun hostRendererSmoke() {
    SeanimeTheme {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Seanime TV renderer check", style = MaterialTheme.typography.headlineLarge)
            Text("Production theme and controls. This fixture only checks host rendering.")
            ActionRow {
                ActionButton("Enabled action") {}
                ActionButton("Disabled action", enabled = false) {}
            }
        }
    }
}
