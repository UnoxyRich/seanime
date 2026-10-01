package app.seanime.tv.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Resolves the Go offline-image marker against the native client's server, without browser state. */
object NativeArtworkUrl {
    private const val LOCAL_ASSETS = "{{LOCAL_ASSETS}}"
    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun resolve(serverBaseUrl: String, value: String?): String? {
        val source = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val server = serverBaseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.username("")?.password("")?.encodedPath("/")?.query(null)?.fragment(null)?.build() ?: return null
        if (source.startsWith(LOCAL_ASSETS)) {
            if (!source.startsWith("$LOCAL_ASSETS/")) return null
            val segments = source.removePrefix("$LOCAL_ASSETS/").split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
            return server.newBuilder().addPathSegment("offline-assets")
                .apply { segments.forEach(::addPathSegment) }.build().toString()
        }
        if (source.contains("{{") || source.contains("}}")) return null
        // Metadata and plugin images may use remote or server-relative HTTP URLs. They must
        // not turn into file/content/resource loads with access to private Android storage.
        val resolved = if (scheme.containsMatchIn(source)) source.toHttpUrlOrNull() else server.resolve(source)
        return resolved?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
    }
}
