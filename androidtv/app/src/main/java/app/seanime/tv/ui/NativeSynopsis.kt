package app.seanime.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.text.HtmlCompat

/** Descriptions are metadata text, never a rendered or executable web interface. */
@Composable
internal fun rememberNativeSynopsis(value: String): String = remember(value) {
    val textOnly = value.replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
    HtmlCompat.fromHtml(textOnly, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        .replace("\uFFFC", "").replace(Regex("\\n{3,}"), "\n\n").trim()
}
