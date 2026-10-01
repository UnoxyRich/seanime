package app.seanime.tv

import java.io.File

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import app.seanime.tv.data.*
import app.seanime.tv.platform.NativePlaybackCoordinator
import app.seanime.tv.platform.NativePlatformActions
import app.seanime.tv.platform.NativePrompt
import app.seanime.tv.platform.NativePromptAction
import app.seanime.tv.platform.AndroidNativeLibraryFiles
import app.seanime.tv.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Native TV presentation. The embedded Go host only supplies existing REST/WebSocket APIs. */
class MainActivity : ComponentActivity() {
    private val api = SeanimeApiClient()
    private val repository = SeanimeRepository(api)
    private lateinit var playback: NativePlaybackCoordinator
    private lateinit var platform: NativePlatformActions
    private var serverStatus by mutableStateOf<ServerStatus?>(null)
    private var startupError by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<String?>(null)
    private var platformPrompt by mutableStateOf<NativePrompt?>(null)
    private var transientNotice by mutableStateOf<String?>(null)
    private var starting by mutableStateOf(true)
    private var setupPath by mutableStateOf("")
    private var serverJob: Job? = null
    private var pendingOAuthIntent: Intent? = null
    private var serverOwner = 0L
    private var accountRevision by mutableIntStateOf(0)
    private var localListUploadRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverOwner = (application as SeanimeTvApplication).claimServerOwner()
        (application as SeanimeTvApplication).restoreNativeSession(api)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        setupPath = savedInstanceState?.getString("setup-path") ?: nativeDefaultLibraryPath(filesDir)
        platform = NativePlatformActions(this, onStorageChanged = { purpose, root ->
            if (root != null) {
                val path = root.optString("path")
                if (purpose == "screenshot") notice = "Screenshot folder connected: ${root.optString("name", path)}"
                else if (serverStatus?.settings?.length() == 0) setupPath = path
                else lifecycleScope.launch {
                    try {
                        val setting = when (purpose) { "manga-local" -> "manga.mangaLocalSourceDirectory"; "torrent-stream" -> "torrentstream.downloadDir"; else -> "library.libraryPath" }
                        if (purpose == "library-additional") {
                            val current = repository.settings().optJSONObject("library")?.optJSONArray("libraryPaths") ?: JSONArray()
                            if ((0 until current.length()).none { current.optString(it) == path }) current.put(path)
                            api.request("PATCH", "/api/v1/settings/path", jsonObject("path" to "library.libraryPaths", "value" to current))
                        } else if (purpose == "torrent-stream") {
                            val settings = api.request("GET", "/api/v1/torrentstream/settings") as? JSONObject ?: JSONObject()
                            settings.put("downloadDir", path)
                            api.request("PATCH", "/api/v1/torrentstream/settings", jsonObject("settings" to settings))
                        } else api.request("PATCH", "/api/v1/settings/path", jsonObject("path" to setting, "value" to path))
                        notice = "Folder connected: ${root.optString("name", path)}"
                        serverStatus = repository.status()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { notice = e.message }
                }
            }
        }, onError = { notice = it }, onPrompt = { platformPrompt = it }, onMessage = { transientNotice = it })
        playback = NativePlaybackCoordinator(this, api, onError = { notice = it }, onState = { notice = it.takeIf(String::isNotBlank) })
        pendingOAuthIntent = intent
        setContent {
            SeanimeTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("native-tv-root")) {
                    val status = serverStatus
                    when {
                        starting -> LoadingMessage("Starting your Seanime server…")
                        startupError != null -> ErrorMessage(startupError!!, ::startServer)
                        status == null -> ErrorMessage("The server didn't return a status", ::startServer)
                        status.serverHasPassword && status.version.isBlank() -> UnlockScreen { password ->
                            lifecycleScope.launch {
                                try {
                                    api.setServerPassword(password)
                                    val unlocked = repository.status()
                                    check(unlocked.version.isNotBlank()) { "Password wasn't accepted" }
                                    serverStatus = unlocked
                                    startReadySession()
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { api.setServerToken(null); notice = e.message }
                            }
                        }
                        status.settings.length() == 0 -> SetupScreen(setupPath,
                            { platform.openStoragePicker("library-main") },
                            { online, torrent -> completeSetup(online, torrent) })
                        else -> key(accountRevision) {
                            CompositionLocalProvider(LocalNativeLibraryFiles provides remember(platform) { AndroidNativeLibraryFiles(this@MainActivity, platform) }) {
                                SeanimeTvApp(repository, status, ::play, ::platformAction, ::finish)
                            }
                        }
                    }
                    transientNotice?.let { message ->
                        LaunchedEffect(message) { kotlinx.coroutines.delay(4_500); if (transientNotice == message) transientNotice = null }
                        Box(Modifier.align(Alignment.BottomCenter).padding(32.dp).background(MaterialTheme.colorScheme.surface).padding(20.dp)) {
                            Text(message, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    platformPrompt?.let { prompt -> PlatformPromptDialog(prompt) { platformPrompt = null } }
                    notice?.let { message ->
                        PlatformPromptDialog(NativePrompt("Seanime TV", message)) { notice = null }
                    }
                }
            }
        }
        startServer()
    }

    private fun startServer() {
        if (serverJob?.isActive == true) return
        starting = true; startupError = null
        serverJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    (application as SeanimeTvApplication).awaitAndroidRuntime()
                    ensureActive()
                    if (!(application as SeanimeTvApplication).startEmbeddedServer(serverOwner,
                        filesDir.resolve("seanime/data").absolutePath, cacheDir.resolve("seanime").absolutePath)) {
                        throw CancellationException("This Activity no longer owns server startup")
                    }
                }
                serverStatus = repository.status()
                startReadySession()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { startupError = e.message ?: "Unable to start the server" }
            finally { starting = false }
        }
    }

    private fun completeSetup(online: Boolean, torrent: Boolean) {
        if (starting) return
        starting = true
        lifecycleScope.launch {
            try {
                serverStatus = repository.completeSetup(setupPath, online, torrent)
                startReadySession()
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Setup couldn't be saved" }
            finally { starting = false }
        }
    }

    private fun startReadySession() {
        if (!nativeSessionReady(serverStatus)) return
        playback.start()
        pendingOAuthIntent?.let { handleOAuth(it) }
        pendingOAuthIntent = null
        intent.removeExtra("playback-recovery-id")
        playback.recoverIfAvailable()
    }

    private fun play(request: PlaybackRequest) {
        request.playlistId?.let { playback.startPlaylist(it); return }
        request.playlistEpisode?.let { playback.playSinglePlaylistItem(it); return }
        request.unmatchedFile?.let { playback.playUnmatchedFile(it); return }
        val stream = request.stream
        if (request.episode?.isNakama == true && stream == null) {
            lifecycleScope.launch {
                try {
                    api.awaitEventsReady()
                    repository.request("POST", "/api/v1/nakama/play", jsonObject("mediaId" to request.mediaId,
                        "path" to request.episode.localPath.orEmpty(), "anidbEpisode" to request.episode.aniDbEpisode, "clientId" to api.clientId, "forcePlaybackMethod" to "nativeplayer"))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = e.message ?: "Couldn't open the shared episode" }
            }
        } else if (stream != null) {
            val subtitles = JSONArray().apply { stream.subtitles.forEach { track ->
                put(JSONObject().put("src", track.url).put("url", track.url).put("label", track.language)
                    .put("language", track.language).put("default", track.isDefault))
            } }
            playback.playStream(stream.url, request.title, request.mediaId, request.episode?.raw, subtitles, stream.headers, sourceType = stream.type, rawMedia = request.media)
        } else request.episode?.localPath?.let(playback::playLocalFile)
            ?: run { notice = "This episode needs a source. Open the show and choose Online, Torrent or Debrid." }
    }

    private fun platformAction(action: String) {
        when {
            action == "storage:manage" -> showStorageManager()
            action.startsWith("storage:") -> platform.openStoragePicker(action.substringAfter(':'))
            action == "accounts" -> lifecycleScope.launch {
                try {
                    val status = repository.status()
                    serverStatus = status
                    platformPrompt = nativeAccountPrompt(!status.isSimulated, status.userName, status.offline, ::platformAction)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = e.message ?: "Accounts could not be loaded" }
            }
            action == "upload-local-anilist" -> {
                if (localListUploadRunning) { notice = "The local collection upload is still running"; return }
                lifecycleScope.launch {
                    try {
                        val target = requireLocalListMigrationAccount(repository.status())
                        platformPrompt = nativeLocalListMigrationPrompt(target) {
                            if (!localListUploadRunning) {
                                localListUploadRunning = true
                                notice = "Uploading the saved local collection to $target…"
                                lifecycleScope.launch {
                                    try {
                                        uploadNativeLocalList(repository, target)
                                        accountRevision++
                                        notice = "Upload request finished. Review your AniList lists for any titles that need another attempt."
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { notice = "Could not confirm the upload. Check AniList before retrying. ${e.message.orEmpty()}" }
                                    finally { localListUploadRunning = false }
                                }
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { notice = e.message ?: "The local collection upload is unavailable" }
                }
            }
            action == "logout:anilist" || action == "logout:mal" -> {
                val provider = action.substringAfter(':')
                platformPrompt = nativeAccountDisconnectPrompt(provider) {
                    lifecycleScope.launch {
                        try {
                            if (provider == "anilist") {
                                // The unchanged Go logout leaves its offline flag untouched.
                                // Never switch that offline account into an inconsistent local platform.
                                requireOnlineAniListConnection(repository.status().offline)
                                serverStatus = repository.logoutAniList()
                            }
                            else {
                                check(repository.logoutMal() == true) { "MyAnimeList could not be disconnected" }
                                serverStatus = repository.status()
                            }
                            // Drop collection/detail snapshots from the previous account.
                            accountRevision++
                            notice = "${if (provider == "anilist") "AniList" else "MyAnimeList"} disconnected"
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { notice = e.message ?: "Account could not be disconnected" }
                    }
                }
            }
            action == "oauth:anilist" -> {
                lifecycleScope.launch {
                    try {
                        val status = repository.status()
                        requireOnlineAniListConnection(status.offline)
                        val clientId = status.anilistClientId.ifBlank { "15168" }
                        platform.openOAuth("https://anilist.co/api/v2/oauth/authorize?client_id=${Uri.encode(clientId)}&response_type=token")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { notice = e.message ?: "AniList sign-in could not be started" }
                }
            }
            action == "oauth:mal" -> platform.beginMALLogin()
            action == "update" -> platform.checkForUpdate()
            action == "report" -> lifecycleScope.launch {
                try {
                    val bytes = repository.downloadIssueReport()
                    platform.saveReport("seanime-tv-report.zip", "application/zip", bytes)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = e.message ?: "Couldn't create the report" }
            }
            action == "refresh" -> lifecycleScope.launch { runCatching { repository.status() }.onSuccess { serverStatus = it }.onFailure { notice = it.message } }
            else -> notice = "This Android action isn't available: $action"
        }
    }

    private fun showStorageManager() {
        val rootsJson = platform.storageRoots()
        val roots = (0 until rootsJson.length()).mapNotNull(rootsJson::optJSONObject)
        if (roots.isEmpty()) {
            notice = "No external folders are connected. Choose an anime, manga, torrent or screenshot folder in Settings."
            return
        }
        platformPrompt = NativePrompt("Connected folders", "Choose a folder to reconnect or manage this TV's access.",
            actions = roots.map { root -> NativePromptAction(root.optString("name", root.optString("path")) + if (root.optBoolean("available")) "" else " · unavailable") {
                platformPrompt = NativePrompt(root.optString("name", "Folder"), root.optString("path") + "\nYour files stay on the storage device.",
                    actions = listOf(
                        NativePromptAction("Reconnect folder") { platform.openStoragePicker(root.optString("purpose", "library-additional")) },
                        NativePromptAction("Disconnect folder") {
                            platformPrompt = NativePrompt("Disconnect this folder?", "Seanime will lose its saved access to ${root.optString("name", "this folder")}. This does not delete files.",
                                actions = listOf(NativePromptAction("Disconnect") { platform.removeStorageTree(root.optString("uri")); showStorageManager() }), dismissLabel = "Cancel")
                        }), dismissLabel = "Back")
            } }, focusDismiss = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!nativeSessionReady(serverStatus)) pendingOAuthIntent = intent else {
            handleOAuth(intent)
            if (intent.hasExtra("playback-recovery-id")) {
                intent.removeExtra("playback-recovery-id")
                playback.recoverIfAvailable()
            }
        }
    }

    private fun handleOAuth(intent: Intent) {
        val returned = platform.consumeOAuthReturn(intent) ?: return
        val uri = Uri.parse(returned)
        lifecycleScope.launch {
            try {
                val fragment = Uri.parse("https://local/?" + uri.encodedFragment.orEmpty())
                val token = fragment.getQueryParameter("access_token")
                val code = uri.getQueryParameter("code")
                when {
                    !token.isNullOrBlank() -> {
                        requireOnlineAniListConnection(repository.status().offline)
                        repository.loginAniList(token)
                    }
                    !code.isNullOrBlank() -> repository.loginMal(code, uri.getQueryParameter("state").orEmpty(), platform.consumeMalVerifier(uri))
                    else -> error(uri.getQueryParameter("error_description") ?: "The account provider did not return an authorization code")
                }
                serverStatus = repository.status()
                accountRevision++
                notice = "Your account is connected"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Account connection failed" }
        }
    }

    @Deprecated("Android activity result bridge for document providers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!platform.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        if (::platform.isInitialized) platform.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("setup-path", setupPath)
        super.onSaveInstanceState(outState)
    }

    internal fun openExternalUrl(url: String) = platform.openExternalUrl(url)

    /** Opaque recovery metadata only, retained for host instrumentation. */
    internal fun pendingPlaybackRecoveryJson(): String = PlaybackRecoverySnapshot.read(filesDir)?.let {
        JSONObject().put("checkpointId", it.checkpointId).toString()
    }.orEmpty()

    override fun onDestroy() {
        (application as SeanimeTvApplication).saveNativeSession(api)
        val detached = playback.detachToPlayer()
        if (!detached) {
            playback.close()
            api.close()
        }
        platform.close()
        if (isFinishing && !NativePlayerActivity.isVisible()) (application as SeanimeTvApplication).stopEmbeddedServer(serverOwner)
        super.onDestroy()
    }

}

/** New app-owned libraries must use the same physical path as filesystem enumeration. */
internal fun nativeDefaultLibraryPath(filesDir: File): String =
    filesDir.resolve("seanime/library").apply { mkdirs() }.canonicalPath

/** Restricted status intentionally omits version; a new server has no saved settings yet. */
internal fun nativeSessionReady(status: ServerStatus?): Boolean =
    status != null && status.ready && status.version.isNotBlank() && status.settings.length() > 0

@Composable
internal fun SetupScreen(path: String, chooseFolder: () -> Unit, onContinue: (Boolean, Boolean) -> Unit) {
    var online by rememberSaveable { mutableStateOf(true) }
    var torrent by rememberSaveable { mutableStateOf(false) }
    val continueFocus = remember { FocusRequester() }
    val continueFocusGranted = remember { mutableStateOf(false) }
    val internalLibrary = LocalContext.current.filesDir.resolve("seanime/library")
    val folderLabel = if (path == internalLibrary.absolutePath || path == internalLibrary.canonicalPath) "Internal library on this TV" else path
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 740.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Text("Welcome to Seanime TV", style = MaterialTheme.typography.headlineLarge)
            Text("Your media library, made for your TV. Keep your collection here or connect a USB folder.")
            Text("Media folder: $folderLabel", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = chooseFolder) { Text("Choose USB / media folder") }
                Button(onClick = { online = !online }) { Text("Online streaming: ${if (online) "On" else "Off"}") }
            }
            Button(onClick = { torrent = !torrent }) { Text("Built-in torrent streaming: ${if (torrent) "On" else "Off"}") }
            Text("Provider extensions and account connections can be added in Settings. No account is needed to start with local files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onContinue(online, torrent) }, modifier = Modifier.testTag("setup-continue").initialTvFocus(continueFocus, continueFocusGranted)) { Text("Start using Seanime") }
        }
    }
}

@Composable
internal fun UnlockScreen(onUnlock: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    val initialFocus = remember { FocusRequester() }
    val focusGranted = remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Text("Unlock your Seanime server", style = MaterialTheme.typography.headlineMedium)
            Text("The local server requires its existing password. It will stay in memory for this session.")
            OutlinedTextField(password, { password = it }, label = { Text("Server password") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    .testTag("server-password").initialTvFocus(initialFocus, focusGranted))
            Button(onClick = { onUnlock(password); password = "" }, enabled = password.isNotEmpty(),
                modifier = Modifier.testTag("server-unlock")) { Text("Unlock") }
        }
    }
}
