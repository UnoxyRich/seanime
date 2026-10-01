package app.seanime.tv.ui

import app.seanime.tv.data.ExtensionItem

internal fun selectSourceProvider(providers: List<ExtensionItem>, current: String, configured: String): String {
    val enabled = providers.filterNot { it.disabled }
    if (enabled.any { it.id == current }) return current
    if (enabled.any { it.id == configured }) return configured
    if (configured == "none") return ""
    return enabled.firstOrNull()?.id.orEmpty()
}
