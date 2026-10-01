package app.seanime.tv.data

import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.json.JSONTokener

class ApiException(val statusCode: Int, override val message: String, val path: String) : IOException(message)

/** Native transport for internal/handlers/routes.go. Credentials never leave this server origin. */
class SeanimeApiClient(
    baseUrl: String = "http://127.0.0.1:43211",
    serverToken: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) : Closeable {
    val baseUrl: String = baseUrl.trimEnd('/')
    private val origin = this.baseUrl.toHttpUrl()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Process-memory-only handoff. Intentionally not Parcelable/Serializable and never persisted. */
    class SessionSnapshot internal constructor(
        val canonicalOrigin: String,
        val clientId: String,
        val identityProof: String?,
        val serverToken: String?,
    ) {
        override fun toString(): String = "SessionSnapshot(redacted)"
    }

    private class SessionEpoch(val value: Long)
    private val canonicalOrigin = origin.newBuilder().username("").password("").encodedPath("/")
        .query(null).fragment(null).build().toString().trimEnd('/')
    @Volatile private var session = SessionSnapshot(canonicalOrigin, UUID.randomUUID().toString(), null, serverToken)
    @Volatile private var sessionEpoch = 0L
    val clientId: String get() = session.clientId
    val serverToken: String? get() = session.serverToken
    @Volatile private var socket: WebSocket? = null
    private var eventJob: Job? = null
    @Volatile private var eventGeneration = 0L
    private val mutableEvents = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<ServerEvent> = mutableEvents.asSharedFlow()
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    init { require(origin.scheme == "http" || origin.scheme == "https") }

    /** A single immutable holder makes identity, proof and token a coherent atomic snapshot. */
    fun snapshotSession(): SessionSnapshot = session

    @Synchronized fun restoreSession(snapshot: SessionSnapshot): Boolean {
        if (snapshot.canonicalOrigin != canonicalOrigin || snapshot.clientId.isBlank()) return false
        if (snapshot === session) return true
        val reconnect = eventJob?.isActive == true
        if (reconnect) closeEvents()
        ++sessionEpoch // Ignore HTTP responses already in flight for the replaced session.
        session = snapshot
        if (reconnect) connectEvents()
        return true
    }

    @Synchronized fun setServerToken(token: String?) {
        val current = session
        restoreSession(SessionSnapshot(canonicalOrigin, current.clientId, current.identityProof, token?.takeIf { it.isNotBlank() }))
    }
    fun setServerPassword(password: String) { setServerToken(if (password.isEmpty()) null else hashPassword(password)) }

    fun requestHeaders(): Map<String, String> = requestHeaders(session)
    private fun requestHeaders(snapshot: SessionSnapshot): Map<String, String> = buildMap {
        // local_security.go deliberately permits originless reads but requires origin metadata for
        // privileged mutations. Describe this server's actual origin; never impersonate app://-
        // or hardcode a trusted loopback origin for a different destination. The unchanged server
        // still checks its password, request host, client IP and forwarded-header boundary.
        put("Origin", canonicalOrigin)
        put("X-Seanime-Client-Platform", "androidtv")
        put("X-Seanime-Client-Id", snapshot.clientId)
        snapshot.identityProof?.let { put("X-Seanime-Client-Id-Proof", it) }
        snapshot.serverToken?.let { put("X-Seanime-Token", it) }
    }

    @Synchronized private fun requestSession(): Pair<SessionSnapshot, Long> = session to sessionEpoch

    fun absoluteUrl(path: String): String = origin.resolve(path)?.toString() ?: throw IllegalArgumentException("Invalid server URL")
    fun isServerUrl(url: String): Boolean = runCatching { val candidate = url.toHttpUrl(); candidate.scheme == origin.scheme && candidate.host == origin.host && candidate.port == origin.port }.getOrDefault(false)

    private fun requestBuilder(method: String, path: String, body: JSONObject?, query: Map<String, String>): Request {
        val url = absoluteUrl(path).toHttpUrl().newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        require(isServerUrl(url.toString())) { "Refusing to send Seanime credentials to another origin" }
        val normalizedMethod = method.uppercase()
        val requestBody = if (normalizedMethod in setOf("GET", "HEAD")) null else (body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA_TYPE)
        val (snapshot, epoch) = requestSession()
        return Request.Builder().url(url).method(normalizedMethod, requestBody).header("Accept", "application/json")
            .tag(SessionEpoch::class.java, SessionEpoch(epoch))
            .apply { requestHeaders(snapshot).forEach { (name, value) -> header(name, value) } }.build()
    }

    suspend fun request(method: String, path: String, body: JSONObject? = null, query: Map<String, String> = emptyMap()): Any? {
        val (code, bytes) = execute(requestBuilder(method, path, body, query))
        return decodeResponse(code, bytes.toString(Charsets.UTF_8), path)
    }

    suspend fun download(path: String, maxBytes: Int = 32 * 1024 * 1024, query: Map<String, String> = emptyMap(),
        readTimeoutSeconds: Long? = null): ByteArray {
        require(maxBytes > 0) { "A download must have a positive size limit" }
        require(readTimeoutSeconds == null || readTimeoutSeconds in 1L..360L) { "Invalid download timeout" }
        val transport = if (readTimeoutSeconds == null) httpClient else httpClient.newBuilder()
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).callTimeout(readTimeoutSeconds + 15, TimeUnit.SECONDS).build()
        val (code, bytes) = execute(requestBuilder("GET", path, null, query), maxBytes, transport)
        if (code !in 200..299) decodeResponse(code, bytes.toString(Charsets.UTF_8), path)
        return bytes
    }

    /** Read on OkHttp's dispatcher, never the UI thread, keeping cancellation active until body EOF. */
    private suspend fun execute(request: Request, maxBytes: Int? = null, transport: OkHttpClient = httpClient): Pair<Int, ByteArray> = suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        updateIdentity(it)
                        val body = it.body
                        val bytes = when {
                            body == null -> byteArrayOf()
                            maxBytes == null -> body.bytes()
                            else -> {
                                // Bound the transfer before allocation, including chunked and
                                // compressed bodies whose decoded length is unknown to OkHttp.
                                val limit = if (maxBytes % (1024 * 1024) == 0) "${maxBytes / (1024 * 1024)} MiB" else "$maxBytes bytes"
                                val tooLarge = "This download exceeds its size limit ($limit)"
                                if (body.contentLength() > maxBytes) throw IOException(tooLarge)
                                val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
                                val buffer = ByteArray(8192)
                                body.byteStream().use { input ->
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (count > maxBytes - output.size()) throw IOException(tooLarge)
                                        output.write(buffer, 0, count)
                                    }
                                }
                                output.toByteArray()
                            }
                        }
                        it.code to bytes
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    @Synchronized private fun updateIdentity(response: Response) {
        if (response.request.tag(SessionEpoch::class.java)?.value != sessionEpoch) return
        updateIdentity(response.header("X-Seanime-Client-Id"), response.header("X-Seanime-Client-Id-Proof"))
    }

    @Synchronized private fun updateIdentity(clientId: String?, proof: String?) {
        val current = session
        val nextId = clientId?.takeIf { it.isNotBlank() } ?: current.clientId
        val nextProof = proof?.takeIf { it.isNotBlank() }
            ?: current.identityProof.takeIf { nextId == current.clientId }
        session = SessionSnapshot(canonicalOrigin, nextId, nextProof, current.serverToken)
    }

    /** One lifecycle-owned event connection, with bounded backoff and no parallel sockets. */
    @Synchronized fun connectEvents() {
        if (eventJob?.isActive == true) return
        val generation = ++eventGeneration
        eventJob = scope.launch {
            var retryDelay = 1000L
            while (isActive) {
                val disconnected = CompletableDeferred<Unit>()
                try {
                    // Establish a signed identity before opening /events, including after server restart.
                    request("GET", "/api/v1/status")
                    if (generation != eventGeneration) return@launch
                    val (snapshot, epoch) = requestSession()
                    val wsUrl = origin.newBuilder().encodedPath("/events").query(null)
                        .addQueryParameter("id", snapshot.clientId).addQueryParameter("platform", "androidtv")
                        .apply { snapshot.identityProof?.let { addQueryParameter("proof", it) }; snapshot.serverToken?.let { addQueryParameter("token", it) } }.build()
                    val request = Request.Builder().url(wsUrl).tag(SessionEpoch::class.java, SessionEpoch(epoch))
                        .apply { requestHeaders(snapshot).forEach { (key, value) -> header(key, value) } }.build()
                    val connection = httpClient.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            synchronized(this@SeanimeApiClient) {
                                if (generation != eventGeneration) { webSocket.cancel(); return }
                                socket = webSocket
                                updateIdentity(response)
                                mutableConnected.value = true
                                retryDelay = 1000L
                            }
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (generation != eventGeneration) return
                            val event = runCatching { val raw = JSONObject(text); ServerEvent(raw.getString("type"), raw.opt("payload").takeUnless { it == JSONObject.NULL }, raw) }.getOrNull() ?: return
                            if (event.type == "client-identity") (event.payload as? JSONObject)?.let { identity ->
                                synchronized(this@SeanimeApiClient) {
                                    if (generation == eventGeneration) updateIdentity(identity.stringOrNull("clientId"), identity.stringOrNull("proof"))
                                }
                            }
                            if (!mutableEvents.tryEmit(event)) scope.launch { mutableEvents.emit(event) }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            synchronized(this@SeanimeApiClient) { if (generation == eventGeneration) mutableConnected.value = false }
                            disconnected.complete(Unit)
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            synchronized(this@SeanimeApiClient) { if (generation == eventGeneration) mutableConnected.value = false }
                            response?.close(); disconnected.complete(Unit)
                        }
                    })
                    synchronized(this@SeanimeApiClient) {
                        if (generation != eventGeneration) { connection.cancel(); return@launch }
                        socket = connection
                    }
                    val pingJob = launch {
                        while (isActive) { delay(20_000); sendEvent("ping", jsonObject("timestamp" to System.currentTimeMillis())) }
                    }
                    try { disconnected.await() } finally {
                        pingJob.cancel(); connection.cancel()
                        synchronized(this@SeanimeApiClient) { if (socket === connection) socket = null }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) {
                    synchronized(this@SeanimeApiClient) { if (generation == eventGeneration) mutableConnected.value = false }
                }
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000)
            }
        }
    }

    suspend fun awaitEventsReady(timeoutMillis: Long = 10_000) {
        connectEvents()
        withTimeout(timeoutMillis) { connected.first { it } }
    }

    fun sendEvent(type: String, payload: JSONObject = JSONObject()): Boolean = socket?.send(jsonObject("type" to type, "payload" to payload).toString()) ?: false
    @Synchronized fun closeEvents() { ++eventGeneration; eventJob?.cancel(); eventJob = null; socket?.close(1000, "Native UI stopped"); socket = null; mutableConnected.value = false }
    override fun close() { closeEvents(); scope.cancel() }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        fun hashPassword(password: String): String = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        internal fun decodeResponse(code: Int, text: String, path: String): Any? {
            val parsed = runCatching { JSONTokener(text).nextValue() }.getOrNull()
            val envelope = parsed as? JSONObject
            val serverError = envelope?.stringOrNull("error") ?: envelope?.stringOrNull("message")
            if (code !in 200..299 || !envelope?.stringOrNull("error").isNullOrBlank()) {
                throw ApiException(code, serverError ?: when (code) { 401 -> "Server authentication required"; 403 -> "This feature is restricted by the server"; 404 -> "Endpoint is not available on this server"; else -> "Server request failed (HTTP $code)" }, path)
            }
            if (code == 204 || text.isBlank()) return null
            if (envelope == null) throw ApiException(code, "Server returned an invalid JSON response", path)
            return envelope.opt("data").takeUnless { it == JSONObject.NULL }
        }
    }
}
