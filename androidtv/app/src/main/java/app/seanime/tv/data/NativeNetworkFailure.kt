package app.seanime.tv.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.system.ErrnoException
import android.util.Log
import androidx.media3.datasource.HttpDataSource
import coil.network.HttpException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

/** Failure-only diagnostics. Never formats a Throwable, request, response, URL or header. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object NativeNetworkFailure {
    private const val MAX_CAUSES = 8
    private const val MAX_CLASS_LENGTH = 96
    internal const val TAG = "SeanimeNetworkFailure"

    enum class Surface { PLAYER, MANGA_IMAGE }

    fun logDebug(context: Context, surface: Surface, error: Throwable, playerErrorCode: Int? = null) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        // Deliberately use the String-only overload; framework loggers may print or hide causes.
        val fields = runCatching { summary(error, playerErrorCode) }.getOrDefault("category=diagnostic_unavailable")
        Log.w(TAG, "surface=${surface.name} $fields")
    }

    internal fun summary(error: Throwable, playerErrorCode: Int? = null): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = ArrayList<Throwable>(MAX_CAUSES)
        var next: Throwable? = error
        while (next != null && causes.size < MAX_CAUSES && seen.add(next)) {
            causes.add(next)
            next = next.cause
        }
        val cycle = next != null && next in seen
        val truncated = next != null && !cycle
        val policy = causes.firstNotNullOfOrNull(::policyCategory)
        val httpStatus = causes.firstNotNullOfOrNull {
            when (it) {
                is HttpDataSource.InvalidResponseCodeException -> it.responseCode
                is HttpException -> it.response.code
                else -> null
            }?.takeIf { status -> status in 100..599 }
        }
        val category = policy ?: when {
            causes.any { it is UnknownHostException } -> "platform_dns"
            causes.any { it is SSLException } -> "tls"
            causes.any { it is SocketTimeoutException } -> "timeout"
            causes.any { it is SocketException } -> "socket"
            httpStatus != null -> "http"
            causes.any { it is IOException } -> "io"
            else -> "other"
        }
        val operation = causes.filterIsInstance<HttpDataSource.HttpDataSourceException>().firstOrNull()?.let {
            when (it.type) {
                HttpDataSource.HttpDataSourceException.TYPE_OPEN -> "open"
                HttpDataSource.HttpDataSourceException.TYPE_READ -> "read"
                HttpDataSource.HttpDataSourceException.TYPE_CLOSE -> "close"
                else -> "other"
            }
        }
        val errno = causes.filterIsInstance<ErrnoException>().firstOrNull()?.errno
        return buildString {
            append("category=").append(category)
            append(" causes=").append(causes.joinToString(">") { classIdentifier(it) })
            append(" cycle=").append(cycle).append(" truncated=").append(truncated)
            playerErrorCode?.let { append(" playerCode=").append(it) }
            httpStatus?.let { append(" httpStatus=").append(it) }
            operation?.let { append(" operation=").append(it) }
            errno?.let { append(" errno=").append(it) }
        }
    }

    private fun classIdentifier(error: Throwable): String = error.javaClass.name.take(MAX_CLASS_LENGTH)
        .takeIf { name -> name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._$" } }
        ?: "unknown"

    private fun policyCategory(error: Throwable): String? {
        // Matching a message alone could mislabel a platform DNS error with the same text.
        // Require the throwing frame to belong to our policy, and never emit either value.
        val owner = error.stackTrace.firstOrNull()?.className ?: return null
        val policyClass = ProviderUrlPolicy::class.java.name
        if (owner != policyClass && !owner.startsWith("$policyClass$")) return null
        return when (error.message) {
            "The provider address is not public",
            "The provider address did not resolve exclusively to public IP addresses" ->
                if (error is UnknownHostException) "provider_dns_policy" else "provider_url_policy"
            "Provider requests cannot validate destinations through the configured proxy",
            "Provider requests cannot validate destinations through the configured proxy. The proxy was not bypassed." -> "provider_proxy_policy"
            "The provider redirect is not public HTTP(S)",
            "The provider redirect is invalid",
            "The provider redirect contains credentials" -> "provider_redirect_policy"
            "The provider returned an invalid address",
            "The provider returned an unsupported address",
            "The provider address contains credentials",
            "The provider address is local",
            "The provider address uses an ambiguous IP address" -> "provider_url_policy"
            else -> null
        }
    }
}
