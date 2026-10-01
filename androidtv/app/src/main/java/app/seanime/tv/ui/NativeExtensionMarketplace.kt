package app.seanime.tv.ui

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.ExtensionItem
import app.seanime.tv.data.SeanimeJson
import app.seanime.tv.data.SeanimeRepository
import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// Verified hosted catalog, selected by GitHub repository stars on 2026-10-01.
// Keep it remote: the catalog's data is not copied into this application.
internal const val NativeDefaultMarketplaceUrl =
    "https://raw.githubusercontent.com/Bas1874/Seanime-Marketplace/refs/heads/main/Marketplace/Main.json"

internal fun nativeMarketplaceInitialSource(preferences: SharedPreferences): String =
    if (preferences.contains("source")) preferences.getString("source", "").orEmpty()
    else NativeDefaultMarketplaceUrl

internal fun validateNativeMarketplaceUrl(value: String): String {
    val url = value.trim()
    if (url.isEmpty()) return ""
    require(url.length <= 4096 && url.none(Char::isISOControl)) { "Enter a valid repository address" }
    val uri = runCatching { URI(url) }.getOrElse { error("Enter a valid repository address") }
    require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null) {
        "Use an HTTP or HTTPS repository URL without embedded login details or a fragment"
    }
    return url
}

internal fun filterNativeMarketplace(entries: List<ExtensionItem>, query: String, type: String, language: String): List<ExtensionItem> =
    entries.filter { item ->
        (type.isBlank() || item.type == type) && (language.isBlank() || item.raw.text("lang").equals(language, true)) &&
            (query.isBlank() || listOf(item.id, item.name, item.description).any { it.contains(query, true) })
    }.sortedWith(compareBy<ExtensionItem> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })

internal fun nativeMarketplaceInstalledMatches(installed: ExtensionItem, reviewed: ExtensionItem): Boolean =
    installed.id == reviewed.id && installed.version == reviewed.version && installed.type == reviewed.type &&
        reviewed.manifestUrl.isNotBlank() && installed.manifestUrl == reviewed.manifestUrl

internal suspend fun fetchNativeMarketplace(repo: SeanimeRepository, source: String): List<ExtensionItem> {
    val valid = validateNativeMarketplaceUrl(source)
    val rows = repo.request("GET", "/api/v1/extensions/marketplace", query = if (valid.isBlank()) emptyMap() else mapOf("marketplace" to valid)) as? JSONArray
        ?: error("The server returned an invalid marketplace")
    return rows.uiObjects().map { SeanimeJson.extension(it) }.filter { it.id.isNotBlank() && it.manifestUrl.isNotBlank() }.distinctBy { it.id }
}

/** Browses metadata through the server. No repository code runs before explicit review/install. */
@Composable
internal fun NativeExtensionMarketplace(repo: SeanimeRepository, initialType: String = "", onBack: () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("native-extension-marketplace", 0)
    // An explicit empty source selects Seanime's built-in catalog and must survive upgrades.
    var source by rememberSaveable { mutableStateOf(nativeMarketplaceInitialSource(prefs)) }
    var sourceDraft by rememberSaveable { mutableStateOf(source) }
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(initialType) }
    var language by rememberSaveable { mutableStateOf("") }
    var editor by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<ExtensionItem>>(emptyList()) }
    var installed by remember { mutableStateOf<List<ExtensionItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ExtensionItem?>(null) }
    var reviewedUrl by remember { mutableStateOf("") }
    var accepted by remember { mutableStateOf(false) }
    var focusedId by rememberSaveable { mutableStateOf("") }
    val returnFocus = remember { FocusRequester() }
    val returnGranted = remember { mutableStateOf(true) }
    val backFocus = remember { FocusRequester() }
    val backGranted = remember { mutableStateOf(false) }
    val action = rememberFeatureAction()
    val listState = rememberLazyListState()
    suspend fun load(url: String = source) {
        val next = fetchNativeMarketplace(repo, url)
        val local = repo.extensions()
        entries = next; installed = local; loaded = true
    }
    fun closePreview() { preview = null; returnGranted.value = false }
    BackHandler(onBack = onBack)
    ReportNativePluginScreen(NativeScreenLocation("/extensions", buildMap {
        put("tab", "marketplace"); if (type.isNotBlank()) put("type", type)
    }))
    LaunchedEffect(repo) { action.run { load() } }
    FeaturePage("Extension marketplace", "Browse repository metadata, then review each extension before installing", action, state = listState,
        modifier = Modifier.testTag("marketplace-list")) {
        item { ActionRow {
            ActionButton("Installed extensions", modifier = Modifier.testTag("marketplace-back").initialTvFocus(backFocus, backGranted), onClick = onBack)
            ActionButton("Refresh", !action.busy, Modifier.testTag("marketplace-refresh")) { action.run { load() } }
            ActionButton("Repository", !action.busy, Modifier.testTag("marketplace-source")) { editor = "source" }
            ActionButton("Search", !action.busy, Modifier.testTag("marketplace-search")) { editor = "search" }
            ActionButton("Type: ${type.ifBlank { "All" }}", !action.busy, Modifier.testTag("marketplace-type")) { choices = "type" }
            ActionButton("Language: ${language.ifBlank { "All" }}", !action.busy, Modifier.testTag("marketplace-language")) { choices = "language" }
        } }
        item { Text("Source: ${if (source == NativeDefaultMarketplaceUrl) "Bas1874 community marketplace" else source.ifBlank { "Seanime built-in marketplace" }}", style = MaterialTheme.typography.bodyMedium) }
        if (source.isBlank()) item { Text("The default marketplace may omit content providers. Choose a repository you trust to browse other providers.") }
        val visible = filterNativeMarketplace(entries, query, type, language)
        if (loaded && visible.isEmpty()) item { EmptyFeature("No matching extensions", "Change Search, Type or Language, or choose another repository.") }
        items(visible.size, key = { visible[it].id }) { index ->
            val item = visible[index]
            val local = installed.firstOrNull { it.id == item.id }
            val current = local?.let { nativeMarketplaceInstalledMatches(it, item) } == true
            FeaturePanel(item.name, "${item.version} · ${humanizeField(item.type)} · ${item.raw.text("lang").ifBlank { "Language not specified" }}") {
                if (item.description.isNotBlank()) Text(item.description)
                if (local != null) Text("Installed ${local.version}${if (local.disabled) " · Disabled" else ""}${if (local.manifestUrl != item.manifestUrl) " · Different source" else ""}")
                ActionButton(if (current) "Installed" else if (local != null) "Review version ${item.version}" else "Review installation", !action.busy && !current,
                    Modifier.testTag("marketplace-review-${item.id}").then(if (focusedId == item.id) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier)) {
                    focusedId = item.id
                    action.run {
                        validateNativeMarketplaceUrl(item.manifestUrl)
                        val raw = repo.request("POST", "/api/v1/extensions/external/fetch", JSONObject().put("manifestUri", item.manifestUrl)) as? JSONObject
                            ?: error("The server returned no extension manifest")
                        val actual = SeanimeJson.extension(raw)
                        check(actual.id == item.id) { "The manifest identifies a different extension. Refresh the repository before installing." }
                        reviewedUrl = item.manifestUrl; accepted = false; preview = actual
                    }
                }
            }
        }
    }
    when (editor) {
        "source" -> TextEntryDialog("Marketplace repository", "Repository JSON URL", sourceDraft, allowEmpty = true,
            helper = "Leave empty for Seanime's built-in catalog. A new setup uses the Bas1874 community catalog. This loads metadata; installation is a separate reviewed action.",
            inputModifier = Modifier.testTag("marketplace-source-editor"), onDismiss = { editor = null }) { value ->
            sourceDraft = value
            action.run {
                val valid = validateNativeMarketplaceUrl(value); load(valid)
                source = valid; prefs.edit().putString("source", valid).apply()
            }
        }
        "search" -> TextEntryDialog("Find extension", "Name, description or ID", query, allowEmpty = true,
            inputModifier = Modifier.testTag("marketplace-search-editor"), onDismiss = { editor = null }) { query = it }
    }
    if (choices == "type") SettingChoiceDialog("Extension type", type,
        listOf(SettingChoice("", "All types")) + entries.map { it.type }.distinct().sorted().map { SettingChoice(it, humanizeField(it)) },
        { choices = null }) { type = it }
    if (choices == "language") SettingChoiceDialog("Extension language", language,
        listOf(SettingChoice("", "All languages")) + entries.map { it.raw.text("lang").lowercase(Locale.ROOT) }.filter(String::isNotBlank).distinct().sorted().map { SettingChoice(it, it.uppercase(Locale.ROOT)) },
        { choices = null }) { language = it }
    preview?.let { item ->
        val cancelFocus = remember(item) { FocusRequester() }
        val cancelGranted = remember(item) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!action.busy) closePreview() }, title = { Text("Install ${item.name}?") }, text = {
            TvScrollableText(Modifier.heightIn(max = 280.dp), exitFocus = cancelFocus) {
                Text("Version ${item.version} · ${item.raw.text("author")}")
                Text(item.description)
                Text("Source: $reviewedUrl")
                Text("This extension runs code in your Seanime backend. Install only if you trust this source. Existing versions of the same extension will be replaced.")
                item.raw.optJSONObject("plugin")?.let { Text(pluginPermissionSummary(it)) }
                action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (accepted) Text("The install request succeeded. Refresh the installed list to verify its state.")
            }
        }, dismissButton = {
            ActionButton("Cancel", !action.busy, Modifier.testTag("marketplace-install-cancel").initialTvFocus(cancelFocus, cancelGranted), ::closePreview)
        }, confirmButton = {
            ActionButton(if (accepted) "Verify installation" else "Install", !action.busy, Modifier.testTag("marketplace-install-confirm")) {
                action.run {
                    // Reconcile a lost response before retrying a non-idempotent install.
                    val before = repo.extensions()
                    if (before.any { nativeMarketplaceInstalledMatches(it, item) }) accepted = true
                    if (!accepted) {
                        val fresh = repo.request("POST", "/api/v1/extensions/external/fetch", JSONObject().put("manifestUri", reviewedUrl)) as? JSONObject
                            ?: error("The manifest could not be rechecked")
                        if (fresh.toString() != item.raw.toString()) {
                            check(fresh.text("id") == item.id) { "The manifest identifies a different extension. Cancel and refresh the repository." }
                            preview = SeanimeJson.extension(fresh)
                            error("The manifest changed. Review the new details, then confirm again.")
                        }
                        val result = repo.request("POST", "/api/v1/extensions/external/install", JSONObject().put("manifestUri", reviewedUrl)) as? JSONObject
                        check(!result?.text("message").isNullOrBlank()) { "The server did not confirm installation. Verify the installed list before retrying." }
                        accepted = true
                    }
                    installed = repo.extensions()
                    check(installed.any { nativeMarketplaceInstalledMatches(it, item) }) { "The reviewed extension source and version were not returned by the installed list. Retry verification or review the server logs." }
                    closePreview(); action.message = "${item.name} ${item.version} is installed. Review its configuration and permissions in Installed extensions."
                    // Its review button is now disabled: move focus to a stable control.
                    listState.scrollToItem(0)
                    backGranted.value = false
                }
            }
        })
    }
}
