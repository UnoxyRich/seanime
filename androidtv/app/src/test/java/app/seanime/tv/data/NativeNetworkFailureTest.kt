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
        assertFalse(summary(platform).contains("dnsReason="))
    }

    @Test fun emptyDnsAndInvalidHostSummariesHaveOnlyFixedZeroAnswerDetails() {
        val empty = dnsFailure(emptyList())
        val invalid = runCatching { ProviderUrlPolicy.publicDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw AssertionError("Unexpected DNS")
        }).lookup("localhost") }.exceptionOrNull()!!
        for ((failure, reason) in listOf(empty to "empty_answers", invalid to "invalid_host")) {
            val result = summary(failure)
            assertTrue(result.startsWith("category=provider_dns_policy "))
            assertTrue(result.endsWith("dnsReason=$reason dnsAnswers=0 dnsRejected=0 dnsFamilies=none dnsKinds=none"))
            assertRedacted(result)
        }
    }

    @Test fun wrappedProviderDnsDetailsUseBoundedOrderedClassificationsWithoutAddresses() {
        val answers = listOf(
            namedIp(8, 8, 8, 8),
            namedIp(198, 18, 0, 1),
            namedIp(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8),
            namedIp(0xfc, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            namedIp(127, 0, 0, 1),
            namedIp(224, 0, 0, 1),
            namedIp(192, 0, 2, 1),
            namedIp(0x20, 2, 8, 8, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        )
        val source = HttpDataSource.HttpDataSourceException.createForIOException(dnsFailure(answers),
            DataSpec(Uri.parse("https://private.example/video?token=signed-secret")), HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        val player = PlaybackException(secret, source, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val result = NativeNetworkFailure.summary(player, player.errorCode)
        assertTrue(result.startsWith("category=provider_dns_policy "))
        assertTrue(result.contains("playerCode=2001 operation=open"))
        assertTrue(result.endsWith("dnsReason=nonpublic_answers dnsAnswers=8 dnsRejected=7 dnsFamilies=ipv4+ipv6 " +
            "dnsKinds=nat64+transition+private+local+multicast+benchmark+non_global"))
        assertRedacted(result)
        answers.forEach { assertFalse(result.contains(it.hostAddress!!)) }
        val bounded = summary(dnsFailure(List(300) { answers[1] }))
        assertTrue(bounded.endsWith("dnsReason=nonpublic_answers dnsAnswers=255 dnsRejected=255 dnsFamilies=ipv4 dnsKinds=benchmark"))
        assertTrue(bounded.length < 600)
        assertRedacted(bounded)
    }

    @Test fun legacyDnsRecognitionStillRequiresPolicyProvenanceAndInventsNoDetails() {
        val legacy = UnknownHostException("The provider address did not resolve exclusively to public IP addresses")
        assertTrue(summary(legacy).startsWith("category=platform_dns "))
        legacy.stackTrace = arrayOf(StackTraceElement(ProviderUrlPolicy::class.java.name, "publicDns", "ProviderUrlPolicy.kt", 107))
        val result = summary(legacy)
        assertTrue(result.startsWith("category=provider_dns_policy "))
        assertFalse(result.contains("dnsReason="))
    }

    private fun namedIp(vararg bytes: Int): InetAddress = InetAddress.getByAddress("private.example", bytes.map { it.toByte() }.toByteArray())

    private fun dnsFailure(answers: List<InetAddress>): ProviderUrlPolicy.DnsPolicyException {
        val dns = ProviderUrlPolicy.publicDns(object : Dns { override fun lookup(hostname: String) = answers })
        return assertThrows(ProviderUrlPolicy.DnsPolicyException::class.java) { dns.lookup("private.example") }
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
            NativeNetworkFailure.Surface.values().forEach { surface ->
                NativeNetworkFailure.logDebug(context, surface, UnknownHostException(secret))
            }
            assertTrue(ShadowLog.getLogsForTag(NativeNetworkFailure.TAG).isEmpty())
            context.applicationInfo.flags = original or ApplicationInfo.FLAG_DEBUGGABLE
            NativeNetworkFailure.Surface.values().forEach { surface ->
                ShadowLog.clear()
                NativeNetworkFailure.logDebug(context, surface, UnknownHostException(secret))
                val entry = ShadowLog.getLogsForTag(NativeNetworkFailure.TAG).single()
                assertTrue(entry.msg.startsWith("surface=${surface.name} category=platform_dns "))
                assertTrue(entry.msg.length < 1200)
                assertNull(entry.throwable)
                assertRedacted(entry.msg)
            }
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
