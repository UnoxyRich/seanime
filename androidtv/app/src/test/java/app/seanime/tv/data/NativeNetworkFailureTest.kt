package app.seanime.tv.data

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.system.ErrnoException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import coil.network.HttpException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class NativeNetworkFailureTest {
    private val secret = "https://private.example/video?token=signed-secret Cookie: private-cookie Authorization: Bearer private-token"
    private fun summary(error: Throwable) = NativeNetworkFailure.summary(error)

    @Test fun providerDnsRejectionIsDistinctFromPlatformDnsWithIdenticalText() {
        val dns = ProviderUrlPolicy.publicDns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        })
        val rejected = runCatching { dns.lookup("fixture.example") }.exceptionOrNull()!!
        assertTrue(summary(rejected).startsWith("category=provider_dns_policy "))
        val platform = UnknownHostException(rejected.message)
        val delegate = ProviderUrlPolicy.publicDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw platform
        })
        assertSame(platform, runCatching { delegate.lookup("fixture.example") }.exceptionOrNull())
        assertTrue(summary(platform).startsWith("category=platform_dns "))
    }

    @Test fun wrappedPlayerDnsFailurePreservesOnlyNumericCodeOperationAndClassChain() {
        val source = HttpDataSource.HttpDataSourceException.createForIOException(UnknownHostException(secret),
            DataSpec(Uri.parse("https://private.example/video?token=signed-secret")), HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        val player = PlaybackException(secret, source, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val result = NativeNetworkFailure.summary(player, player.errorCode)
        assertTrue(result.startsWith("category=platform_dns "))
        assertTrue(result.contains("androidx.media3.common.PlaybackException>androidx.media3.datasource.HttpDataSource\$HttpDataSourceException>java.net.UnknownHostException"))
        assertTrue(result.contains("playerCode=2001"))
        assertTrue(result.contains("operation=open"))
        assertRedacted(result)
    }

    @Test fun proxyAndUrlPolicyFailuresHaveFixedCategoriesWithoutChangingRoutes() {
        var selections = 0
        val selector = ProviderUrlPolicy.validatingProxySelector(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                selections++
                return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080)))
            }
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        val rejected = runCatching { selector.select(URI("https://private.example/video?token=signed-secret")) }.exceptionOrNull()!!
        assertTrue(summary(rejected).startsWith("category=provider_proxy_policy "))
        assertEquals(1, selections)
        val url = runCatching { ProviderUrlPolicy.requirePublicUrl("http://127.0.0.1/private?token=signed-secret") }.exceptionOrNull()!!
        assertTrue(summary(url).startsWith("category=provider_url_policy "))
        assertRedacted(summary(rejected) + summary(url))
    }

    @Test fun redirectPolicyRecognitionRequiresBothKnownTextAndPolicyThrowingFrame() {
        val error = IOException("The provider redirect is not public HTTP(S)")
        assertTrue(summary(error).startsWith("category=io "))
        error.stackTrace = arrayOf(StackTraceElement(ProviderUrlPolicy::class.java.name, "redirectGuard", "ProviderUrlPolicy.kt", 107))
        assertTrue(summary(error).startsWith("category=provider_redirect_policy "))
        val unknown = IOException(secret).also { it.stackTrace = error.stackTrace }
        assertTrue(summary(unknown).startsWith("category=io "))
        assertRedacted(summary(unknown))
    }

    @Test fun tlsSocketAndTimeoutCausesRemainDistinctWithoutMessageInspection() {
        for ((cause, category) in listOf(SSLHandshakeException(secret) to "tls", SocketException(secret) to "socket",
            SocketTimeoutException(secret) to "timeout", IOException(secret) to "io")) {
            val result = summary(IOException(secret, cause))
            assertTrue(result.startsWith("category=$category "))
            assertRedacted(result)
        }
    }

    @Test fun httpFailuresExposeStatusButNeverResponseData() {
        val response = Response.Builder().request(Request.Builder().url("https://private.example/video?token=signed-secret")
            .header("Authorization", "Bearer private-token").build()).protocol(Protocol.HTTP_1_1)
            .code(403).message(secret).header("Set-Cookie", "private-cookie").build()
        val coil = summary(HttpException(response))
        val media = summary(HttpDataSource.InvalidResponseCodeException(403, secret, null,
            mapOf("Set-Cookie" to listOf("private-cookie")), DataSpec(Uri.parse("https://private.example/video?token=signed-secret")), secret.toByteArray()))
        for (result in listOf(coil, media)) {
            assertTrue(result.startsWith("category=http "))
            assertTrue(result.contains("httpStatus=403"))
            assertRedacted(result)
        }
    }

    @Test fun errnoUsesOnlyPublicNumericField() {
        val result = summary(IOException(secret, ErrnoException(secret, 13)))
        assertTrue(result.contains("android.system.ErrnoException"))
        assertTrue(result.contains("errno=13"))
        assertRedacted(result)
    }

    @Test fun cyclesAndLongChainsAreBounded() {
        val first = IOException(secret)
        val second = IOException(secret, first)
        first.initCause(second)
        val cycle = summary(first)
        assertTrue(cycle.contains("cycle=true truncated=false"))
        assertEquals(1, cycle.count { it == '>' })
        var deep: Throwable = UnknownHostException(secret)
        repeat(20) { deep = IOException(secret, deep) }
        val bounded = summary(deep)
        assertTrue(bounded.contains("cycle=false truncated=true"))
        assertEquals(7, bounded.count { it == '>' })
        assertTrue(bounded.length < 1100)
        assertRedacted(cycle + bounded)
    }

    private class UnrecognizedFailure(message: String) : RuntimeException(message) {
        override fun toString(): String = throw AssertionError("Throwable formatting must never run")
    }

    private class ThrowingCause(message: String) : RuntimeException(message) {
        override val cause: Throwable? get() = throw AssertionError(message)
    }

    @Test fun unknownExceptionAndSuppressedSecretsAreNotFormatted() {
        val unknown = UnrecognizedFailure(secret).also { it.addSuppressed(IOException(secret)) }
        val result = summary(unknown)
        assertTrue(result.startsWith("category=other "))
        assertTrue(result.contains("UnrecognizedFailure"))
        assertRedacted(result)
    }

    @Test fun throwingIntrospectionCannotReplaceTheOriginalFailureWithACrash() {
        val context = RuntimeEnvironment.getApplication()
        val original = context.applicationInfo.flags
        ShadowLog.clear()
        try {
            context.applicationInfo.flags = original or ApplicationInfo.FLAG_DEBUGGABLE
            NativeNetworkFailure.logDebug(context, NativeNetworkFailure.Surface.PLAYER, ThrowingCause(secret), 2001)
            val entry = ShadowLog.getLogsForTag(NativeNetworkFailure.TAG).single()
            assertEquals("surface=PLAYER category=diagnostic_unavailable", entry.msg)
            assertNull(entry.throwable)
            assertRedacted(entry.msg)
        } finally { context.applicationInfo.flags = original; ShadowLog.clear() }
    }

    @Test fun releaseFlagEmitsNothingAndDebugLogHasOnlyBoundedSafeFields() {
        val context = RuntimeEnvironment.getApplication()
        val original = context.applicationInfo.flags
        ShadowLog.clear()
        try {
            context.applicationInfo.flags = original and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            NativeNetworkFailure.logDebug(context, NativeNetworkFailure.Surface.PLAYER, UnknownHostException(secret), 2001)
            assertTrue(ShadowLog.getLogsForTag(NativeNetworkFailure.TAG).isEmpty())
            context.applicationInfo.flags = original or ApplicationInfo.FLAG_DEBUGGABLE
            NativeNetworkFailure.logDebug(context, NativeNetworkFailure.Surface.MANGA_IMAGE, UnknownHostException(secret))
            val entry = ShadowLog.getLogsForTag(NativeNetworkFailure.TAG).single()
            assertTrue(entry.msg.startsWith("surface=MANGA_IMAGE category=platform_dns "))
            assertNull(entry.throwable)
            assertRedacted(entry.msg)
        } finally { context.applicationInfo.flags = original; ShadowLog.clear() }
    }

    private fun assertRedacted(result: String) {
        for (value in listOf("https://", "http://", "private.example", "token=", "signed-secret", "Cookie", "private-cookie", "Authorization", "Bearer", "private-token")) {
            assertFalse("Sensitive text leaked: $value", result.contains(value))
        }
        assertFalse(result.contains('\n'))
        assertFalse(result.contains('\r'))
    }
}
