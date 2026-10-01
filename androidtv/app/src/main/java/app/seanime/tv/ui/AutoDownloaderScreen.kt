package app.seanime.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.ExtensionItem
import app.seanime.tv.data.SeanimeRepository
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.UUID

/** Rules and profiles use the existing backend strategy engine; no scheduler is duplicated on Android. */
@Composable
internal fun AutoDownloaderScreen(repo: SeanimeRepository, onBack: () -> Unit) {
    val action = rememberFeatureAction()
    val entryFocus = remember { FocusRequester() }
    val entryFocusGranted = remember { mutableStateOf(false) }
    var rules by remember { mutableStateOf<List<AutoRule>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<AutoProfile>>(emptyList()) }
    var queue by remember { mutableStateOf<List<AutoQueueItem>>(emptyList()) }
    var results by remember { mutableStateOf<List<AutoSimulationItem>>(emptyList()) }
    var settings by remember { mutableStateOf(JSONObject()) }
    var libraryPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var providers by remember { mutableStateOf<List<ExtensionItem>>(emptyList()) }
    var providerError by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf<Boolean?>(null) }
    var debridReady by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("rules") }
    var editingRule by remember { mutableStateOf<AutoRule?>(null) }
    var editingProfile by remember { mutableStateOf<AutoProfile?>(null) }
    var deleteRule by remember { mutableStateOf<AutoRule?>(null) }
    var deleteProfile by remember { mutableStateOf<AutoProfile?>(null) }
    var deleteItem by remember { mutableStateOf<AutoQueueItem?>(null) }
    var runConfirmation by remember { mutableStateOf(false) }
    var runEnabling by remember { mutableStateOf(false) }
    var intervalDialog by remember { mutableStateOf(false) }
    var configurationOpen by remember { mutableStateOf(false) }
    var simulationDone by remember { mutableStateOf(false) }
    var simulationScope by remember { mutableStateOf("") }
    var batchRules by remember { mutableStateOf(false) }
    var cleanupRules by remember { mutableStateOf<List<AutoRule>?>(null) }
    val batchDraft = remember { AutoBatchDraft() }
    val listState = rememberLazyListState()
    var returningTo by remember { mutableStateOf("") }
    val returnFocus = remember { FocusRequester() }
    val returnGranted = remember { mutableStateOf(true) }
    suspend fun reload() {
        rules = repo.request("GET", "/api/v1/auto-downloader/rules").jsonObjects().map(AutoRule::from)
        profiles = repo.request("GET", "/api/v1/auto-downloader/profiles").jsonObjects().map(AutoProfile::from)
        queue = repo.request("GET", "/api/v1/auto-downloader/items").jsonObjects().map(AutoQueueItem::from)
        val current = repo.settings()
        settings = current.optJSONObject("autoDownloader") ?: JSONObject().put("enabled", false).put("interval", 20)
        val library = current.optJSONObject("library") ?: JSONObject()
        libraryPaths = (listOf(library.text("libraryPath")) + library.autoStrings("libraryPaths")).filter(String::isNotBlank).distinct()
        loaded = true
    }
    suspend fun saveSetting(field: String, value: Any) {
        val current = repo.settings().optJSONObject("autoDownloader") ?: JSONObject().put("interval", 20)
        repo.request("PATCH", "/api/v1/settings/auto-downloader", AutoDownloaderPayload.settings(current, field, value))
        reload()
    }
    fun simulate(ids: List<Int>) {
        action.run("Simulation finished. No downloads were started.") {
            results = repo.request("POST", "/api/v1/auto-downloader/run/simulation", AutoDownloaderPayload.simulationBody(ids)).jsonObjects().map(AutoSimulationItem::from)
            simulationDone = true
            simulationScope = if (ids.isEmpty()) "All enabled rules" else "Rule ${ids.joinToString()}"
            tab = "simulation"
        }
    }
    LaunchedEffect(repo) { action.run { reload() } }
    // Provider/account failures do not block editing rules or profiles already stored on this backend.
    suspend fun refreshReadiness() {
        try { providers = repo.providers("anime-torrent-provider"); providerError = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { providerError = error.message ?: "Couldn't load torrent providers" }
        try {
            val status = repo.status()
            offline = status.offline
            val debrid = status.raw.optJSONObject("debridSettings")
            debridReady = debrid?.optBoolean("enabled") == true && debrid.text("provider").let { it.isNotBlank() && it != "none" }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { offline = null; debridReady = false }
    }
    LaunchedEffect(repo) { refreshReadiness() }
    LaunchedEffect(repo) { repo.client.events.collectOnMain { event ->
        if (event.type == "auto-downloader-item-added" && !action.busy) action.run { queue = repo.request("GET", "/api/v1/auto-downloader/items").jsonObjects().map(AutoQueueItem::from) }
    } }
    val enabled = settings.optBoolean("enabled")
    val selectedProvider = settings.text("provider")
    val providerReady = providerError == null && providers.isNotEmpty() && selectedProvider != "none" && (selectedProvider.isBlank() || providers.any { it.id == selectedProvider })
    val canRun = loaded && enabled && offline == false && providerReady
    val useDebrid = settings.optBoolean("useDebrid")
    BackHandler(onBack = onBack)
    if (batchRules) {
        AutoBatchRuleScreen(repo, profiles, providers, libraryPaths, batchDraft) {
            batchRules = false; returningTo = "batch"; returnGranted.value = false; action.run { reload() }
        }
        return
    }
    LaunchedEffect(returningTo, returnGranted.value, action.busy) {
        if (returningTo.isNotBlank() && !returnGranted.value && !action.busy) listState.scrollToItem(if (configurationOpen) 5 else 4)
    }
    editingRule?.let { original ->
        AutoRuleEditor(repo, original, profiles, providers, libraryPaths, action, onBack = { editingRule = null }) { edited ->
            action.run("Rule saved") {
                val current = if (edited.id > 0) repo.request("GET", "/api/v1/auto-downloader/rule/${edited.id}") as? JSONObject ?: error("Rule was not returned") else JSONObject()
                repo.request(if (edited.id > 0) "PATCH" else "POST", "/api/v1/auto-downloader/rule", AutoDownloaderPayload.ruleBody(edited, current))
                editingRule = null; reload()
            }
        }
        return
    }
    editingProfile?.let { original ->
        AutoProfileEditor(original, providers, action, onBack = { editingProfile = null }) { edited ->
            action.run("Profile saved") {
                val current = if (edited.id > 0) repo.request("GET", "/api/v1/auto-downloader/profile/${edited.id}") as? JSONObject ?: error("Profile was not returned") else JSONObject()
                repo.request(if (edited.id > 0) "PATCH" else "POST", "/api/v1/auto-downloader/profile", AutoDownloaderPayload.profileBody(edited, current))
                editingProfile = null; reload()
            }
        }
        return
    }
    FeaturePage("Auto downloader", "Rules select episodes; profiles rank and filter releases", action, state = listState) {
        item { ActionRow {
            // Request once on entry, independently of backend loading and refreshes.
            ActionButton("Downloads", modifier = Modifier.testTag("auto-downloader-back")
                .initialTvFocus(entryFocus, entryFocusGranted), onClick = onBack)
            ActionButton("Refresh", !action.busy) { action.run { reload(); refreshReadiness() } }
            ActionButton("Configure", !action.busy) { configurationOpen = !configurationOpen }
            ActionButton("Simulate all", !action.busy && canRun && rules.any { it.enabled }) { simulate(emptyList()) }
            ActionButton("Check now", !action.busy && canRun && rules.any { it.enabled } && (!useDebrid || debridReady)) { runConfirmation = true }
        } }
        item { FeaturePanel(if (enabled) "Automatic checks enabled" else "Automatic checks disabled",
            "Every ${settings.optInt("interval", 20)} minutes · ${if (settings.optBoolean("downloadAutomatically")) "Download matches automatically" else "Queue matches for approval"}") {
            Text("${rules.count { it.enabled }} active rules · ${profiles.size} profiles · ${queue.size} queued items")
            if (offline != false) Text(if (offline == true) "The backend is offline. Rules and profiles can still be edited." else "Server online status is unavailable. Refresh before running a check.")
            if (!providerReady) Text(providerError ?: "Choose an installed torrent provider in Configure. Rule and profile editing remains available.")
            if (useDebrid && !debridReady) Text("Debrid downloading is selected but its account is not enabled. Configure the debrid provider in Settings before starting downloads.")
            Text("Check now requests a backend run; use Refresh to inspect queued matches. The API does not report a completed-run status.", style = MaterialTheme.typography.bodyMedium)
        } }
        if (configurationOpen) item { FeaturePanel("Auto downloader settings") {
            ActionRow {
                ActionButton(if (enabled) "Disable checks" else "Enable checks", !action.busy) {
                    if (enabled) action.run("Automatic checks disabled") { saveSetting("enabled", false) } else runEnabling = true
                }
                ActionButton("Interval: ${settings.optInt("interval", 20)} minutes", !action.busy) { intervalDialog = true }
                ActionButton(if (settings.optBoolean("downloadAutomatically")) "Switch to approval queue" else "Download automatically", !action.busy) {
                    action.run("Download mode saved") { saveSetting("downloadAutomatically", !settings.optBoolean("downloadAutomatically")) }
                }
            }
            ActionRow {
                ActionButton(if (useDebrid) "Use torrent client" else "Use debrid", !action.busy) { action.run("Download destination service saved") { saveSetting("useDebrid", !useDebrid) } }
                ActionButton(if (settings.optBoolean("enableSeasonCheck")) "Season check on" else "Season check off", !action.busy) {
                    action.run("Season check saved") { saveSetting("enableSeasonCheck", !settings.optBoolean("enableSeasonCheck")) }
                }
            }
            Text("Default torrent provider")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(providers, key = { it.id }) { provider ->
                ActionButton((if (selectedProvider == provider.id) "✓ " else "") + provider.name, !action.busy) { action.run("Default provider saved") { saveSetting("provider", provider.id) } }
            } }
        } }
        item { ActionRow { listOf("rules" to "Rules", "profiles" to "Profiles", "queue" to "Queue", "simulation" to "Simulation").forEach { (key, label) ->
            ActionButton((if (tab == key) "✓ " else "") + label, !action.busy) { tab = key }
        } } }
        when (tab) {
            "rules" -> {
                item { ActionRow {
                    ActionButton("New rule", !action.busy) { editingRule = AutoRule(destination = libraryPaths.firstOrNull().orEmpty()) }
                    ActionButton("Create several rules", !action.busy && loaded, Modifier.testTag("auto-batch-open")
                        .then(if ((returningTo == "batch" || returningTo == "cleanup" && rules.isEmpty()) && !action.busy && loaded) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier)) { batchRules = true }
                    ActionButton("Remove finished-series rules", !action.busy && loaded && rules.isNotEmpty(), Modifier.testTag("auto-cleanup-open")
                        .then(if (returningTo == "cleanup" && !action.busy && loaded && rules.isNotEmpty()) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier)) {
                        action.run { cleanupRules = finishedAutoRuleCandidates(repo) }
                    }
                } }
                if (loaded && rules.isEmpty()) item { EmptyFeature("No rules yet", "Create a rule to choose an anime, destination, and release preferences") }
                items(rules, key = { it.id }) { rule ->
                    FeaturePanel(rule.title.ifBlank { "Anime ${rule.mediaId}" },
                        "${if (rule.enabled) "Enabled" else "Disabled"} · ${if (rule.episodeType == "selected") "Episodes ${rule.episodes.joinToString()}" else "Recent episodes"} · ${profiles.firstOrNull { it.id == rule.profileId }?.name ?: "No linked profile"}") {
                        Text(rule.destination)
                        ActionRow {
                            ActionButton("Edit", !action.busy) { editingRule = rule }
                            ActionButton(if (rule.enabled) "Disable" else "Enable", !action.busy) { action.run("Rule updated") {
                                val current = repo.request("GET", "/api/v1/auto-downloader/rule/${rule.id}") as? JSONObject ?: error("Rule was not returned")
                                repo.request("PATCH", "/api/v1/auto-downloader/rule", AutoDownloaderPayload.ruleBody(AutoRule.from(current).copy(enabled = !rule.enabled), current)); reload()
                            } }
                            ActionButton("Simulate", !action.busy && canRun && rule.enabled) { simulate(listOf(rule.id)) }
                            ActionButton("Delete", !action.busy) { deleteRule = rule }
                        }
                    }
                }
            }
            "profiles" -> {
                item { ActionButton("New profile", !action.busy) { editingProfile = AutoProfile() } }
                if (loaded && profiles.isEmpty()) item { EmptyFeature("No profiles yet", "Profiles can be shared by several rules or applied globally to every rule") }
                items(profiles, key = { it.id }) { profile -> FeaturePanel(profile.name, "${if (profile.global) "Global · applies to every rule" else "Linked rules only"} · ${profile.conditions.size} conditions · minimum score ${profile.minimumScore}") {
                    if (profile.resolutions.isNotEmpty()) Text("Resolution order: ${profile.resolutions.joinToString(" → ")}")
                    ActionRow {
                        ActionButton("Edit", !action.busy) { editingProfile = profile }
                        ActionButton(if (profile.global) "Stop applying globally" else "Apply globally", !action.busy) { action.run("Profile updated") {
                            val current = repo.request("GET", "/api/v1/auto-downloader/profile/${profile.id}") as? JSONObject ?: error("Profile was not returned")
                            repo.request("PATCH", "/api/v1/auto-downloader/profile", AutoDownloaderPayload.profileBody(AutoProfile.from(current).copy(global = !profile.global), current)); reload()
                        } }
                        ActionButton("Delete", !action.busy) { deleteProfile = profile }
                    }
                } }
            }
            "queue" -> {
                if (loaded && queue.isEmpty()) item { EmptyFeature("Queue is empty", "Checks add matching episodes here until they are downloaded or scanned") }
                items(queue, key = { it.id }) { item -> FeaturePanel(item.name, "Episode ${item.episode} · Score ${item.score} · ${when { item.downloaded -> "Downloaded, awaiting scan"; item.delayed -> "Delayed until ${item.delayUntil}"; else -> "Awaiting download" }}") {
                    ActionRow {
                        if (!item.downloaded && !useDebrid) ActionButton("Download to rule folder", !action.busy && offline == false) { action.run("Torrent sent to your configured client") {
                            repo.request("POST", "/api/v1/torrent-client/rule-magnet", AutoDownloaderPayload.queueDownload(item)); reload()
                        } }
                        ActionButton("Remove queue record", !action.busy) { deleteItem = item }
                    }
                    if (!item.downloaded && useDebrid) Text("Manage this queued download in the Debrid tab. The rule-magnet endpoint uses the torrent client.")
                } }
            }
            "simulation" -> {
                if (!simulationDone) item { EmptyFeature("No simulation run yet", "Simulate one rule or all enabled rules to preview matching releases without downloading") }
                else item { FeaturePanel(simulationScope, "${results.size} matching releases · simulation only") {
                    if (results.isEmpty()) Text("No matches were returned. Check title, provider, episode filters, and the server log if you expected a match.")
                } }
                items(results) { result -> FeaturePanel(result.name, "Rule ${result.ruleId} · Episode ${result.episode} · Score ${result.score} · ${result.provider}${if (result.delayed) " · Would be delayed" else ""}") }
            }
        }
    }
    deleteRule?.let { rule -> ConfirmFeatureDialog("Delete rule?", "${rule.title}\nThis removes the rule. Existing downloads are kept.", { deleteRule = null }) { action.run("Rule deleted") {
        repo.request("DELETE", "/api/v1/auto-downloader/rule/${rule.id}"); reload()
    } } }
    deleteProfile?.let { profile -> ConfirmFeatureDialog("Delete ${profile.name}?", "${rules.count { it.profileId == profile.id }} rules refer to this profile. They will lose its filtering strategy. Existing files are kept.", { deleteProfile = null }) { action.run("Profile deleted") {
        repo.request("DELETE", "/api/v1/auto-downloader/profile/${profile.id}"); reload()
    } } }
    deleteItem?.let { item -> ConfirmFeatureDialog("Remove queue record?", "${item.name}\nThis removes duplicate-download tracking, not the media file. An active rule may find this episode again.", { deleteItem = null }) { action.run("Queue record removed") {
        repo.request("DELETE", "/api/v1/auto-downloader/item", JSONObject().put("id", item.id)); reload()
    } } }
    if (runConfirmation) ConfirmFeatureDialog("Check for matching releases now?", if (settings.optBoolean("downloadAutomatically")) "Matching torrents may start downloading immediately to their rule destinations." else if (useDebrid) "Matches will be added to your debrid account. Downloads to the rule folders wait for approval." else "Matches will be added to the queue for approval.", { runConfirmation = false }) { action.run("Backend check requested. Refresh the queue to inspect new matches.") {
        repo.request("POST", "/api/v1/auto-downloader/run")
    } }
    if (runEnabling) ConfirmFeatureDialog("Enable automatic checks?", "The backend will start checking now and every ${settings.optInt("interval", 20)} minutes. ${if (settings.optBoolean("downloadAutomatically")) "Matching releases can download automatically." else if (useDebrid) "Matching releases will be added to your debrid account; local downloads wait for approval." else "Matching releases will be queued for approval."}", { runEnabling = false }) { action.run("Automatic checks enabled") { saveSetting("enabled", true) } }
    if (intervalDialog) TextEntryDialog("Check interval", "Minutes (at least 15)", settings.optInt("interval", 20).toString(), onDismiss = { intervalDialog = false }) { text -> action.run("Check interval saved") {
        saveSetting("interval", text.toIntOrNull() ?: error("Enter a whole number of minutes"))
    } }
    cleanupRules?.let { candidates -> AutoFinishedRulesDialog(repo, candidates,
        onClose = { cleanupRules = null; returningTo = "cleanup"; returnGranted.value = false; action.run { reload() } }) }
}

private data class AutoEditorField(val id: String, val label: String, val value: String, val hint: String = "", val allowEmpty: Boolean = true, val save: (String) -> Unit)
private fun autoInteger(value: String, nonnegative: Boolean = false): Int {
    val parsed = value.toIntOrNull() ?: error("Enter a whole number")
    require(!nonnegative || parsed >= 0) { "Enter zero or a positive number" }
    return parsed
}

@Composable
private fun AutoEditorFieldDialog(field: AutoEditorField?, action: FeatureAction, onDismiss: () -> Unit) {
    field?.let { item -> TextEntryDialog(item.label, "Value", item.value, helper = item.hint, allowEmpty = item.allowEmpty, multiline = false,
        onDismiss = onDismiss) { value -> action.run { item.save(value) } } }
}

@Composable
private fun AutoProviderPicker(providers: List<ExtensionItem>, selected: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit) {
    FeaturePanel("Provider overrides", "None selected uses the default provider. Existing unavailable providers are retained until you remove them.") {
        val ids = (providers.map { it.id } + selected).distinct()
        if (ids.isEmpty()) Text("No torrent providers are available. Install one in Extensions; this does not prevent saving the rule.")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ids, key = { it }) { id ->
                ActionButton((if (id in selected) "✓ " else "") + (providers.firstOrNull { it.id == id }?.name ?: "$id (unavailable)"), enabled) {
                    onChange(if (id in selected) selected - id else selected + id)
                }
            }
        }
    }
}

@Composable
private fun AutoRuleEditor(
    repo: SeanimeRepository,
    initial: AutoRule,
    profiles: List<AutoProfile>,
    providers: List<ExtensionItem>,
    destinations: List<String>,
    action: FeatureAction,
    sharedBatch: Boolean = false,
    onBack: () -> Unit,
    onSave: (AutoRule) -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var field by remember { mutableStateOf<AutoEditorField?>(null) }
    var discard by remember { mutableStateOf(false) }
    var chooseAnime by remember { mutableStateOf(false) }
    fun leave() { if (draft != initial) discard = true else onBack() }
    BackHandler { if (chooseAnime) chooseAnime = false else leave() }
    val fields = listOf(
        AutoEditorField("title", "Release title", draft.title, "The title used to match release names", false) { draft = draft.copy(title = it) },
        AutoEditorField("destination", "Download destination", draft.destination, "Use an absolute server path, or choose one of your configured library folders below", false) { draft = draft.copy(destination = it) },
        AutoEditorField("episodes", "Selected episode numbers", draft.episodes.joinToString(", "), "Comma-separated episodes or ranges: 1, 3-6, 9") { draft = draft.copy(episodes = AutoDownloaderPayload.episodes(it)) },
        AutoEditorField("groups", "Release groups", draft.releaseGroups.joinToString(", "), "Comma-separated groups; empty inherits profile preferences") { draft = draft.copy(releaseGroups = AutoDownloaderPayload.terms(it)) },
        AutoEditorField("resolutions", "Resolution preference order", draft.resolutions.joinToString(", "), "First choice first, e.g. 1080p, 720p. Empty inherits the profile.") { draft = draft.copy(resolutions = AutoDownloaderPayload.terms(it)) },
        AutoEditorField("include", "Required release terms", draft.includeTerms.joinToString(", "), "Comma-separated terms to include") { draft = draft.copy(includeTerms = AutoDownloaderPayload.terms(it)) },
        AutoEditorField("exclude", "Excluded release terms", draft.excludeTerms.joinToString(", "), "Comma-separated terms to reject") { draft = draft.copy(excludeTerms = AutoDownloaderPayload.terms(it)) },
        AutoEditorField("seeders", "Minimum seeders", draft.minSeeders.toString(), "Zero disables this minimum", false) { draft = draft.copy(minSeeders = autoInteger(it, true)) },
        AutoEditorField("minSize", "Minimum file size", draft.minSize, "Examples: 500 MB, 1.5 GiB. Leave empty for no lower limit.") { AutoDownloaderPayload.validateSizeRange(it, draft.maxSize); draft = draft.copy(minSize = it) },
        AutoEditorField("maxSize", "Maximum file size", draft.maxSize, "Examples: 2 GB. Leave empty for no upper limit.") { AutoDownloaderPayload.validateSizeRange(draft.minSize, it); draft = draft.copy(maxSize = it) },
        AutoEditorField("offset", "Absolute episode offset", draft.episodeOffset.toString(), "Signed offset for absolute episode numbering; use zero for no offset", false) { draft = draft.copy(episodeOffset = autoInteger(it)) },
    )
    if (chooseAnime) NativeMediaPickerDialog(repo, "Choose anime for the rule", onDismiss = { chooseAnime = false }) { media ->
        draft = draft.copy(mediaId = media.id, title = media.title)
    }
    FeaturePage(if (sharedBatch) "Shared batch preferences" else if (initial.id == 0) "New download rule" else "Edit download rule",
        if (sharedBatch) "These preferences apply to every selected title. Each title keeps its own name and destination." else "A rule chooses the anime and episodes; global profiles are also applied", action) {
        item { ActionRow {
            ActionButton("Cancel", !action.busy, onClick = ::leave)
            ActionButton(if (sharedBatch) "Keep preferences" else "Save rule", !action.busy, Modifier.testTag("auto-rule-save")) { onSave(draft) }
            ActionButton(if (draft.enabled) "✓ Rule enabled" else "Rule disabled", !action.busy) { draft = draft.copy(enabled = !draft.enabled) }
            if (!sharedBatch) ActionButton("Choose anime", !action.busy) { chooseAnime = true }
        } }
        if (!sharedBatch) item { FeaturePanel("Anime", draft.title.ifBlank { "Choose an anime to match its releases" }) { ActionButton("Choose title", !action.busy) { chooseAnime = true } } }
        if (!sharedBatch) items(fields.take(2), key = { it.id }) { item -> FeaturePanel(item.label, item.value.ifBlank { "Not set" }) { ActionButton("Edit", !action.busy) { field = item } } }
        if (!sharedBatch && destinations.isNotEmpty()) item { FeaturePanel("Configured folders") { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(destinations, key = { it }) { path -> ActionButton((if (draft.destination == path) "✓ " else "") + path, !action.busy) { draft = draft.copy(destination = path) } }
        } } }
        item { FeaturePanel("Matching and episode selection") {
            ActionRow {
                ActionButton(if (draft.titleMatch == "likely") "Title: fuzzy match" else "Title: contains", !action.busy) { draft = draft.copy(titleMatch = if (draft.titleMatch == "likely") "contains" else "likely") }
                if (!sharedBatch) ActionButton(if (draft.episodeType == "recent") "Recent episodes" else "Selected episodes", !action.busy) { draft = draft.copy(episodeType = if (draft.episodeType == "recent") "selected" else "recent") }
            }
        } }
        item { FeaturePanel("Linked profile", "Global profiles apply in addition to this selection") { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ActionButton(if (draft.profileId == null) "✓ No linked profile" else "No linked profile", !action.busy) { draft = draft.copy(profileId = null) } }
            items(profiles, key = { it.id }) { profile -> ActionButton((if (draft.profileId == profile.id) "✓ " else "") + profile.name, !action.busy) { draft = draft.copy(profileId = profile.id) } }
        }
            if (draft.profileId != null && profiles.none { it.id == draft.profileId }) Text("Linked profile ${draft.profileId} is unavailable. Choose another profile or clear the link.")
        } }
        items(fields.drop(2).filter { it.id != "episodes" || !sharedBatch && draft.episodeType == "selected" }, key = { it.id }) { item -> FeaturePanel(item.label, item.value.ifBlank { "None / inherited" }) {
            ActionButton("Edit", !action.busy) { field = item }
        } }
        item { AutoProviderPicker(providers, draft.providers, !action.busy) { draft = draft.copy(providers = it) } }
    }
    AutoEditorFieldDialog(field, action) { field = null }
    if (discard) ConfirmFeatureDialog("Discard rule changes?", "Your unsaved rule edits will be lost.", { discard = false }, onBack)
}

@Composable
private fun AutoProfileEditor(initial: AutoProfile, providers: List<ExtensionItem>, action: FeatureAction, onBack: () -> Unit, onSave: (AutoProfile) -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var field by remember { mutableStateOf<AutoEditorField?>(null) }
    var discard by remember { mutableStateOf(false) }
    var condition by remember { mutableStateOf<AutoCondition?>(null) }
    var removeCondition by remember { mutableStateOf<AutoCondition?>(null) }
    fun leave() { if (draft != initial) discard = true else onBack() }
    BackHandler(onBack = ::leave)
    condition?.let { selected ->
        AutoConditionEditor(selected, action, onBack = { condition = null }) { edited ->
            val existing = draft.conditions.any { it.id == edited.id }
            draft = draft.copy(conditions = if (existing) draft.conditions.map { if (it.id == edited.id) edited else it } else draft.conditions + edited)
            condition = null
        }
        return
    }
    val fields = listOf(
        AutoEditorField("name", "Profile name", draft.name, allowEmpty = false) { draft = draft.copy(name = it) },
        AutoEditorField("groups", "Preferred release groups", draft.releaseGroups.joinToString(", "), "Comma-separated groups") { draft = draft.copy(releaseGroups = AutoDownloaderPayload.terms(it)) },
        AutoEditorField("resolutions", "Resolution preference order", draft.resolutions.joinToString(", "), "First choice first, e.g. 1080p, 720p") { draft = draft.copy(resolutions = AutoDownloaderPayload.terms(it)) },
        AutoEditorField("score", "Minimum release score", draft.minimumScore.toString(), "Releases below this threshold are rejected", false) { draft = draft.copy(minimumScore = autoInteger(it)) },
        AutoEditorField("seeders", "Minimum seeders", draft.minSeeders.toString(), "Zero disables this minimum", false) { draft = draft.copy(minSeeders = autoInteger(it, true)) },
        AutoEditorField("minSize", "Minimum file size", draft.minSize, "Examples: 500 MB, 1.5 GiB. Empty means no lower limit.") { AutoDownloaderPayload.validateSizeRange(it, draft.maxSize); draft = draft.copy(minSize = it) },
        AutoEditorField("maxSize", "Maximum file size", draft.maxSize, "Example: 2 GB. Empty means no upper limit.") { AutoDownloaderPayload.validateSizeRange(draft.minSize, it); draft = draft.copy(maxSize = it) },
        AutoEditorField("delay", "Delay before downloading", draft.delayMinutes.toString(), "Minutes to wait for higher-scoring releases or repacks; zero means no delay", false) { draft = draft.copy(delayMinutes = autoInteger(it, true)) },
        AutoEditorField("skip", "Score that skips the delay", draft.skipDelayScore.toString(), "A release at this score can download or queue immediately", false) { draft = draft.copy(skipDelayScore = autoInteger(it)) },
    )
    FeaturePage(if (initial.id == 0) "New download profile" else "Edit download profile", "Reusable release rankings and filters", action) {
        item { ActionRow {
            ActionButton("Cancel", !action.busy, onClick = ::leave)
            ActionButton("Save profile", !action.busy) { onSave(draft) }
            ActionButton(if (draft.global) "✓ Applies globally" else "Linked rules only", !action.busy) { draft = draft.copy(global = !draft.global) }
        } }
        items(fields, key = { it.id }) { item -> FeaturePanel(item.label, item.value.ifBlank { "Not set" }) { ActionButton("Edit", !action.busy) { field = item } } }
        item { AutoProviderPicker(providers, draft.providers, !action.busy) { draft = draft.copy(providers = it) } }
        item { FeaturePanel("Release conditions", "Add or subtract a score, block a term, or require it. Conditions are saved with the profile.") {
            ActionButton("Add condition", !action.busy) { condition = AutoCondition(UUID.randomUUID().toString()) }
        } }
        items(draft.conditions.size, key = { "$it:${draft.conditions[it].id}" }) { index ->
            val item = draft.conditions[index]
            FeaturePanel(item.term, "${if (item.regex) "Regular expression" else "Text term"} · ${when (item.action) { "block" -> "Reject if present"; "require" -> "Require match"; else -> "Score ${if (item.score >= 0) "+" else ""}${item.score}" }}") {
                ActionRow {
                    ActionButton("Edit", !action.busy) { condition = item }
                    ActionButton("Move up", !action.busy && index > 0) { draft = draft.copy(conditions = draft.conditions.toMutableList().also { list -> list.add(index - 1, list.removeAt(index)) }) }
                    ActionButton("Move down", !action.busy && index < draft.conditions.lastIndex) { draft = draft.copy(conditions = draft.conditions.toMutableList().also { list -> list.add(index + 1, list.removeAt(index)) }) }
                    ActionButton("Remove", !action.busy) { removeCondition = item }
                }
            }
        }
    }
    AutoEditorFieldDialog(field, action) { field = null }
    if (discard) ConfirmFeatureDialog("Discard profile changes?", "Your unsaved profile edits will be lost.", { discard = false }, onBack)
    removeCondition?.let { selected -> ConfirmFeatureDialog("Remove condition?", selected.term, { removeCondition = null }) { draft = draft.copy(conditions = draft.conditions.filterNot { it.id == selected.id }) } }
}

@Composable
private fun AutoConditionEditor(initial: AutoCondition, action: FeatureAction, onBack: () -> Unit, onSave: (AutoCondition) -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var field by remember { mutableStateOf<AutoEditorField?>(null) }
    var discard by remember { mutableStateOf(false) }
    fun leave() { if (draft != initial) discard = true else onBack() }
    BackHandler(onBack = ::leave)
    FeaturePage("Profile condition", "Choose how this release-name match changes selection", action) {
        item { ActionRow {
            ActionButton("Cancel", onClick = ::leave)
            ActionButton("Keep condition", !action.busy && draft.term.isNotBlank()) { action.run { draft.payload(); onSave(draft) } }
        } }
        item { FeaturePanel(if (draft.regex) "Regular expression" else "Text term", draft.term.ifBlank { "Not set" }) {
            ActionButton("Edit term") { field = AutoEditorField("term", "Match term", draft.term, if (draft.regex) "Go / RE2 regular-expression syntax. Test the saved profile with Simulation before enabling downloads." else "A term to search for in the release name", false) { draft = draft.copy(term = it) } }
            ActionButton(if (draft.regex) "Use plain text" else "Use regular expression") { draft = draft.copy(regex = !draft.regex) }
        } }
        item { FeaturePanel("When this term matches") {
            ActionRow { listOf("score" to "Adjust score", "block" to "Reject release", "require" to "Require term").forEach { (value, label) ->
                ActionButton((if (draft.action == value) "✓ " else "") + label) { draft = draft.copy(action = value) }
            } }
            if (draft.action == "score") ActionButton("Score adjustment: ${draft.score}") { field = AutoEditorField("score", "Score adjustment", draft.score.toString(), "Positive adds to the score; negative subtracts", false) { draft = draft.copy(score = autoInteger(it)) } }
        } }
    }
    AutoEditorFieldDialog(field, action) { field = null }
    if (discard) ConfirmFeatureDialog("Discard condition changes?", "This condition has unsaved edits.", { discard = false }, onBack)
}

/** Kept by the parent screen so reopening a batch retains uncertain results and never blindly resends them. */
private class AutoBatchDraft {
    var entries by mutableStateOf<List<AutoBatchEntry>>(emptyList())
    var preferences by mutableStateOf(AutoRule())
    var results by mutableStateOf<Map<Long, AutoBatchResult>>(emptyMap())
}

@Composable
private fun AutoBatchRuleScreen(repo: SeanimeRepository, profiles: List<AutoProfile>, providers: List<ExtensionItem>,
    destinations: List<String>, draft: AutoBatchDraft, onBack: () -> Unit) {
    val action = rememberFeatureAction()
    val listState = rememberLazyListState()
    var choosingAnime by remember { mutableStateOf(false) }
    var sharedPreferences by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var review by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var returnTarget by remember { mutableStateOf(if (draft.results.isEmpty()) "add" else if (draft.entries.all { draft.results[it.mediaId]?.complete == true }) "close" else "run") }
    val returnFocus = remember { FocusRequester() }
    val returnGranted = remember { mutableStateOf(false) }
    fun restored(target: String) { returnTarget = target; returnGranted.value = false }
    @Composable fun focus(target: String) = if (returnTarget == target && !action.busy) Modifier.initialTvFocus(returnFocus, returnGranted) else Modifier
    BackHandler { if (!action.busy) onBack() }
    if (sharedPreferences) {
        AutoRuleEditor(repo, draft.preferences, profiles, providers, destinations, action, sharedBatch = true,
            onBack = { sharedPreferences = false; restored("shared") }) {
            draft.preferences = it.copy(episodeType = "recent", episodes = emptyList())
            sharedPreferences = false; restored("shared")
        }
        return
    }
    LaunchedEffect(returnTarget, returnGranted.value, action.busy) {
        if (!returnGranted.value && !action.busy && ':' !in returnTarget) listState.scrollToItem(0)
    }
    val complete = draft.entries.count { draft.results[it.mediaId]?.complete == true }
    FeaturePage("Create several rules", "Choose titles, review each destination, then create one rule per title", action, state = listState) {
        item { ActionRow {
            ActionButton("Close", !action.busy, Modifier.testTag("auto-batch-close").then(focus("close")), onBack)
            ActionButton("Add anime", !action.busy && draft.results.isEmpty(), Modifier.testTag("auto-batch-add").then(focus("add"))) { choosingAnime = true }
            ActionButton("Shared preferences", !action.busy && draft.results.isEmpty(), Modifier.testTag("auto-batch-preferences").then(focus("shared"))) { sharedPreferences = true }
            ActionButton(if (draft.results.isEmpty()) "Review rules" else "Retry / refresh results", !action.busy && draft.entries.isNotEmpty() && complete < draft.entries.size,
                Modifier.testTag("auto-batch-review").then(focus("run"))) { review = true }
            if (draft.results.isNotEmpty()) ActionButton("New batch", !action.busy && draft.results.values.none { it.state == AutoBatchState.UNCERTAIN }, Modifier.testTag("auto-batch-reset")) { reset = true }
        } }
        item { FeaturePanel("${draft.entries.size} selected · $complete confirmed", "${if (draft.preferences.enabled) "Enabled" else "Disabled"} rules · recent episodes · ${profiles.firstOrNull { it.id == draft.preferences.profileId }?.name ?: "No linked profile"}") {
            if (draft.results.isEmpty()) Text("Add titles from your collection or search. Each title can have its own release name and folder.")
            else Text("Saved rules are kept. Rejected requests can be corrected and retried. Unconfirmed requests are checked against current rules without resending.")
        } }
        items(draft.entries, key = { it.mediaId }) { entry ->
            val result = draft.results[entry.mediaId]
            val editable = !action.busy && (result == null || result.state == AutoBatchState.FAILED)
            FeaturePanel(entry.displayTitle, entry.destination) {
                Text("Release title: ${entry.releaseTitle}")
                result?.let { Text(it.message, modifier = Modifier.testTag("auto-batch-result-${entry.mediaId}")) }
                ActionRow {
                    ActionButton("Edit release title", editable, Modifier.testTag("auto-batch-title-${entry.mediaId}").then(focus("title:${entry.mediaId}"))) {
                        returnTarget = "title:${entry.mediaId}"; edit = entry.mediaId to "title"
                    }
                    ActionButton("Edit folder", editable, Modifier.testTag("auto-batch-folder-${entry.mediaId}").then(focus("folder:${entry.mediaId}"))) {
                        returnTarget = "folder:${entry.mediaId}"; edit = entry.mediaId to "folder"
                    }
                    ActionButton("Remove from batch", editable, Modifier.testTag("auto-batch-remove-${entry.mediaId}")) {
                        draft.entries = draft.entries.filterNot { it.mediaId == entry.mediaId }; draft.results = draft.results - entry.mediaId
                        restored(if (draft.entries.isEmpty()) "add" else "run")
                    }
                }
            }
        }
    }
    if (choosingAnime) NativeMediaPickerDialog(repo, "Add anime to this batch", onDismiss = { choosingAnime = false; restored("add") }) { media ->
        if (draft.entries.any { it.mediaId == media.id }) action.error = "This anime is already in the batch"
        else draft.entries = draft.entries + AutoBatchEntry(media.id, media.title, media.title, destinations.firstOrNull().orEmpty())
    }
    edit?.let { (mediaId, field) -> draft.entries.firstOrNull { it.mediaId == mediaId }?.let { entry ->
        TextEntryDialog(if (field == "title") "Release title" else "Download folder", if (field == "title") "Title used to match releases" else "Absolute server folder",
            if (field == "title") entry.releaseTitle else entry.destination,
            helper = if (field == "folder") "Use a folder under your configured library roots: ${destinations.joinToString()}" else "This changes release matching, not the anime selected for the rule.",
            inputModifier = Modifier.testTag("auto-batch-editor-$field"), onDismiss = { edit = null; restored("$field:$mediaId") }) { value ->
            draft.entries = draft.entries.map { if (it.mediaId != mediaId) it else if (field == "title") it.copy(releaseTitle = value) else it.copy(destination = value) }
            draft.results = draft.results - mediaId
        }
    } }
    if (review) {
        val cancelFocus = remember { FocusRequester() }
        val cancelGranted = remember { mutableStateOf(false) }
        val confirmFocus = remember { FocusRequester() }
        val confirmGranted = remember { mutableStateOf(true) }
        LaunchedEffect(action.error, action.busy) { if (action.error != null && !action.busy) confirmGranted.value = false }
        Dialog(onDismissRequest = { if (!action.busy) { review = false; restored("run") } }) {
            Column(Modifier.widthIn(min = 440.dp, max = 700.dp).heightIn(max = 480.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
                .padding(24.dp).testTag("auto-batch-confirmation"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Create or reconcile ${draft.entries.size - complete} rules?", style = MaterialTheme.typography.titleLarge)
                Text("Enabled rules follow your auto-downloader settings and may start downloads when the backend checks for releases.")
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(draft.entries.filter { draft.results[it.mediaId]?.complete != true }, key = { it.mediaId }) { Text("${it.displayTitle}\n${it.releaseTitle}\n${it.destination}") }
                    action.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                }
                ActionRow {
                    ActionButton("Cancel", !action.busy, Modifier.testTag("auto-batch-confirm-cancel").initialTvFocus(cancelFocus, cancelGranted)) { review = false; restored("run") }
                    ActionButton(if (action.busy) "Working…" else "Create / reconcile", !action.busy, Modifier.testTag("auto-batch-confirm")
                        .then(if (!action.busy) Modifier.initialTvFocus(confirmFocus, confirmGranted) else Modifier)) {
                        action.run {
                            draft.results = createNativeAutoBatch(repo, draft.entries.map { autoBatchRule(draft.preferences, it) }, draft.results)
                            review = false; restored(if (draft.entries.all { draft.results[it.mediaId]?.complete == true }) "close" else "run")
                        }
                    }
                }
            }
        }
    }
    if (reset) ConfirmFeatureDialog("Start another batch?", "Saved rules stay on the server. This clears the current batch selection and its unsaved drafts.", { reset = false }) {
        draft.entries = emptyList(); draft.results = emptyMap(); restored("add")
    }
}

@Composable
private fun AutoFinishedRulesDialog(repo: SeanimeRepository, candidates: List<AutoRule>, onClose: () -> Unit) {
    val action = rememberFeatureAction()
    var outcome by remember { mutableStateOf(AutoCleanupResult(emptySet(), emptyMap())) }
    var attempted by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }
    val closeGranted = remember { mutableStateOf(false) }
    val retryFocus = remember { FocusRequester() }
    val retryGranted = remember { mutableStateOf(true) }
    val remaining = candidates.count { it.id !in outcome.removed }
    LaunchedEffect(action.busy, attempted) {
        if (!action.busy && attempted) { if (remaining == 0) closeGranted.value = false else retryGranted.value = false }
    }
    Dialog(onDismissRequest = { if (!action.busy) onClose() }) {
        Column(Modifier.widthIn(min = 440.dp, max = 700.dp).heightIn(max = 480.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
            .padding(24.dp).testTag("auto-cleanup-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Remove finished-series rules?", style = MaterialTheme.typography.titleLarge)
            Text(if (candidates.isEmpty()) "No rules belong to series currently marked finished." else "Only these ${candidates.size} rules are selected. Existing downloads, files and other rules stay in place.")
            if (attempted) Text("${outcome.removed.size} of ${candidates.size} removals confirmed")
            LazyColumn(Modifier.weight(1f, fill = false).testTag("auto-cleanup-list"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(candidates, key = { it.id }) { rule ->
                    Column(Modifier.testTag("auto-cleanup-rule-${rule.id}")) {
                        Text(rule.title); Text(rule.destination)
                        if (rule.id in outcome.removed) Text("Removed")
                        outcome.errors[rule.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                action.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
            ActionRow {
                ActionButton(if (attempted) "Close" else "Cancel", !action.busy, Modifier.testTag("auto-cleanup-cancel")
                    .then(if (!action.busy) Modifier.initialTvFocus(closeFocus, closeGranted) else Modifier), onClose)
                ActionButton(if (action.busy) "Removing…" else if (attempted) "Retry remaining" else "Remove selected rules", !action.busy && remaining > 0,
                    Modifier.testTag("auto-cleanup-confirm").then(if (!action.busy && remaining > 0) Modifier.initialTvFocus(retryFocus, retryGranted) else Modifier)) {
                    attempted = true
                    action.run { outcome = removeFinishedAutoRules(repo, candidates, outcome.removed) }
                }
            }
        }
    }
}
