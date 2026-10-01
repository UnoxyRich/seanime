package app.seanime.tv.platform

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Network (not application) interceptor: reapplies credentials for every redirect hop.
 * Media3 DataSpec headers and previously injected provider headers are never inherited
 * as authority for a different origin. The callback must return only headers approved
 * for the exact scheme/host/port of its argument.
 */
class PlaybackHeaderInterceptor(private val headersForUrl: (String) -> Map<String, String>) : Interceptor {
    // Origin and Referer can arrive in the initial Media3 DataSpec, before this interceptor
    // has seen an approved copy. Bind them to the callback's exact-origin policy as well.
    private val managedNames = mutableSetOf("authorization", "proxy-authorization", "cookie", "origin", "referer")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val transportHeaders = setOf("host", "connection", "content-length", "range", "if-range", "accept-encoding")
        val approved = headersForUrl(request.url.toString()).filterKeys { it.lowercase() !in transportHeaders }
        val namesToRemove = synchronized(managedNames) {
            managedNames.addAll(approved.keys.map { it.lowercase() })
            managedNames.toSet()
        }
        val builder = request.newBuilder()
        request.headers.names().forEach { name ->
            if (name.lowercase() in namesToRemove || name.startsWith("X-Seanime-", ignoreCase = true)) builder.removeHeader(name)
        }
        approved.forEach { (name, value) -> builder.header(name, value) }
        if (builder.build().header("User-Agent") == null) builder.header("User-Agent", "Seanime TV/0.1.0")
        return chain.proceed(builder.build())
    }
}
