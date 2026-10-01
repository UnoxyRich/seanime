package app.seanime.tv.data

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderUrlPolicyTest {
    @Test fun rejectsDangerousSchemesCredentialsRelativeUrlsAndNonpublicAddresses() {
        val rejected = listOf("file:///private/token", "content://documents/secret", "data:text/plain,secret", "javascript:alert(1)",
            "ftp://example.com/file", "/api/v1/status", "//example.com/video", "http://user:pass@example.com/video", "http://@example.com/video",
            "http://example.com:0/video", "http://example.com:65536/video", "http://example.com\\@127.0.0.1/video", "http://example.com/\nfile",
            "http://localhost/video", "http://LOCALHOST./video", "http://host.local/video", "http://printer/video", "http://127.0.0.1/video",
            "http://127.1/video", "http://2130706433/video", "http://0177.0.0.1/video", "http://0x7f000001/video", "http://0.0.0.0/video",
            "http://10.0.0.1/video", "http://172.16.2.1/video", "http://192.168.1.1/video", "http://169.254.169.254/video", "http://100.64.0.1/video",
            "http://192.0.2.1/video", "http://198.18.0.1/video", "http://198.51.100.1/video", "http://203.0.113.1/video", "http://224.0.0.1/video",
            "http://240.0.0.1/video", "http://[::1]/video", "http://[::]/video", "http://[fc00::1]/video", "http://[fe80::1]/video",
            "http://[::ffff:127.0.0.1]/video", "http://[2001:db8::1]/video", "http://[3fff::1]/video", "http://[3fff:f00::1]/video", "http://[2002:7f00:1::]/video")
        rejected.forEach { value -> assertTrue("Must reject $value", runCatching { ProviderUrlPolicy.requirePublicUrl(value) }.isFailure) }
        listOf("https://cdn.example.com/video.m3u8?token=source", "http://8.8.8.8/video.mp4", "https://[2606:4700:4700::1111]/video").forEach {
            assertNotNull(ProviderUrlPolicy.requirePublicUrl(it))
        }
    }

    @Test fun dnsRejectsPrivateAndMixedAnswersBeforeReturningAnyAddress() {
        val public = InetAddress.getByName("8.8.8.8")
        val private = InetAddress.getByName("192.168.1.2")
        for (answers in listOf(listOf(private), listOf(public, private), listOf(private, public), emptyList())) {
            val dns = ProviderUrlPolicy.publicDns(object : Dns { override fun lookup(hostname: String) = answers })
            assertTrue(runCatching { dns.lookup("provider.example") }.exceptionOrNull() is UnknownHostException)
        }
        val answers = listOf(public, InetAddress.getByName("2606:4700:4700::1111"))
        assertEquals(answers, ProviderUrlPolicy.publicDns(object : Dns { override fun lookup(hostname: String) = answers }).lookup("provider.example"))
        var lookedUp = false
        val blocked = ProviderUrlPolicy.publicDns(object : Dns { override fun lookup(hostname: String): List<InetAddress> { lookedUp = true; return answers } })
        assertTrue(runCatching { blocked.lookup("localhost") }.isFailure)
        assertFalse(lookedUp)
    }

    @Test fun dnsEmptyAnswersAndInvalidHostsHaveDistinctFixedReasons() {
        val empty = dnsRejection(emptyList())
        assertEquals(ProviderUrlPolicy.DnsReason.EMPTY_ANSWERS, empty.reason)
        assertEquals("The provider address did not resolve exclusively to public IP addresses", empty.message)
        assertEquals(0, empty.answerCount)
        assertEquals(0, empty.rejectedCount)
        assertTrue(empty.rejectedFamilies.isEmpty())
        assertTrue(empty.rejectedKinds.isEmpty())
        val dns = ProviderUrlPolicy.publicDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw AssertionError("Invalid host reached DNS")
        })
        val invalid = assertThrows(ProviderUrlPolicy.DnsPolicyException::class.java) { dns.lookup("localhost") }
        assertEquals(ProviderUrlPolicy.DnsReason.INVALID_HOST, invalid.reason)
        assertEquals("The provider address is not public", invalid.message)
        assertEquals(0, invalid.answerCount)
        assertEquals(0, invalid.rejectedCount)
    }

    @Test fun dnsMixedPublicAndPrivateAnswersCountOnlyRejectedFamiliesAndKinds() {
        val public = ip(8, 8, 8, 8)
        val privateV4 = ip(192, 168, 1, 2)
        val privateV6 = ip(0xfc, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        val publicV6 = ip(0x26, 0x06, 0x47, 0, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11)
        val mixed = dnsRejection(listOf(public, publicV6, privateV4))
        assertEquals(ProviderUrlPolicy.DnsReason.NONPUBLIC_ANSWERS, mixed.reason)
        assertEquals(3, mixed.answerCount)
        assertEquals(1, mixed.rejectedCount)
        assertEquals(listOf(ProviderUrlPolicy.DnsFamily.IPV4), mixed.rejectedFamilies)
        assertEquals(listOf(ProviderUrlPolicy.DnsRejectionKind.PRIVATE), mixed.rejectedKinds)
        val both = dnsRejection(listOf(privateV6, public, privateV4, privateV4))
        assertEquals(4, both.answerCount)
        assertEquals(3, both.rejectedCount)
        assertEquals(listOf(ProviderUrlPolicy.DnsFamily.IPV4, ProviderUrlPolicy.DnsFamily.IPV6), both.rejectedFamilies)
        assertEquals(listOf(ProviderUrlPolicy.DnsRejectionKind.PRIVATE), both.rejectedKinds)
    }

    @Test fun dnsNat64TransitionAndBenchmarkLabelsDoNotAllowRejectedAddresses() {
        val cases = listOf(
            ip(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8) to ProviderUrlPolicy.DnsRejectionKind.NAT64,
            ip(0, 0x64, 0xff, 0x9b, 0, 1, 1, 2, 3, 4, 5, 6, 8, 8, 8, 8) to ProviderUrlPolicy.DnsRejectionKind.NAT64,
            ip(0x20, 2, 8, 8, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1) to ProviderUrlPolicy.DnsRejectionKind.TRANSITION,
            ip(0x20, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1) to ProviderUrlPolicy.DnsRejectionKind.TRANSITION,
            ip(192, 88, 99, 1) to ProviderUrlPolicy.DnsRejectionKind.TRANSITION,
            ip(198, 18, 0, 1) to ProviderUrlPolicy.DnsRejectionKind.BENCHMARK,
            ip(198, 19, 255, 254) to ProviderUrlPolicy.DnsRejectionKind.BENCHMARK,
            ip(192, 0, 2, 1) to ProviderUrlPolicy.DnsRejectionKind.NON_GLOBAL,
        )
        cases.forEach { (address, kind) ->
            assertFalse(ProviderUrlPolicy.isPublicAddress(address))
            assertEquals(listOf(kind), dnsRejection(listOf(address)).rejectedKinds)
        }
        // A lookalike outside the recognized NAT64 prefixes keeps the generic rejected label.
        val other = ip(0, 0x64, 0xff, 0x9b, 0, 2, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8)
        assertEquals(listOf(ProviderUrlPolicy.DnsRejectionKind.NON_GLOBAL), dnsRejection(listOf(other)).rejectedKinds)
    }

    @Test fun dnsDiagnosticCountsSaturateAndPlatformExceptionsPropagateUnchanged() {
        val bounded = dnsRejection(List(300) { ip(10, 0, 0, 1) })
        assertEquals(255, bounded.answerCount)
        assertEquals(255, bounded.rejectedCount)
        val platform = UnknownHostException("private-host.example 192.168.1.2 token=secret")
        val dns = ProviderUrlPolicy.publicDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw platform
        })
        assertSame(platform, runCatching { dns.lookup("provider.example") }.exceptionOrNull())
    }

    private fun ip(vararg bytes: Int): InetAddress = InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())

    private fun dnsRejection(answers: List<InetAddress>): ProviderUrlPolicy.DnsPolicyException {
        val dns = ProviderUrlPolicy.publicDns(object : Dns { override fun lookup(hostname: String) = answers })
        return assertThrows(ProviderUrlPolicy.DnsPolicyException::class.java) { dns.lookup("provider.example") }
    }

    @Test fun configuredProxyIsRejectedWithoutChoosingADirectFallback() {
        var selections = 0
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))
        val selector = ProviderUrlPolicy.validatingProxySelector(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> { selections++; return listOf(proxy, Proxy.NO_PROXY) }
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        val failure = runCatching { selector.select(URI("https://provider.example/video")) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("not bypassed"))
        assertEquals(1, selections)
        val direct = ProviderUrlPolicy.validatingProxySelector(object : ProxySelector() {
            override fun select(uri: URI) = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        assertEquals(listOf(Proxy.NO_PROXY), direct.select(URI("https://provider.example/video")))
    }

    @Test fun persistedOnlineRecordsRemainProviderContextWithoutANewSecurityFlag() {
        for (record in listOf(
            JSONObject().put("playbackType", "onlinestream").put("streamUrl", "file:///private/secret"),
            JSONObject().put("streamType", "onlinestream").put("streamUrl", "content://private/secret"),
            JSONObject().put("onlinestreamParams", JSONObject().put("provider", "anidb")).put("streamUrl", "http://192.168.1.2/video"),
        )) {
            val restored = JSONObject(record.toString())
            val context = ProviderMediaContext(ProviderMediaContext.isProviderPlayback(restored))
            assertTrue(runCatching { context.requireMediaUri(restored.getString("streamUrl")) }.isFailure)
        }
        val local = JSONObject().put("playbackType", "localfile").put("streamUrl", "file:///owned/video.mkv")
        ProviderMediaContext(ProviderMediaContext.isProviderPlayback(local)).requireMediaUri(local.getString("streamUrl"))
    }

    @Test fun providerAuthorityNeverAcquiresApiCredentialsEvenForAPublicApiOrigin() {
        val sourceHeaders = mutableMapOf("Cookie" to "provider-cookie", "X-Seanime-Token" to "forged")
        val context = ProviderMediaContext(true, "https://api.example/", false, "https://provider.example/video", sourceHeaders) {
            mapOf("X-Seanime-Token" to "server-secret")
        }
        sourceHeaders["Cookie"] = "later-source-cookie"
        assertEquals(mapOf("Cookie" to "provider-cookie"), context.headersFor("https://provider.example/video", "https://provider.example/segment"))
        assertTrue(context.headersFor("https://provider.example/video", "https://api.example/target").isEmpty())
        assertTrue(context.headersFor("https://api.example/video", "https://api.example/target").isEmpty())
        assertTrue(context.headersFor("https://provider.example/video", "https://provider.example:8443/target").isEmpty())
        val local = ProviderMediaContext(false, "http://192.168.1.2:43211/", true, serverHeaders = { mapOf("X-Seanime-Token" to "server-secret") })
        local.requireMediaUri("http://192.168.1.2:43211/video")
        assertEquals("server-secret", local.headersFor("http://192.168.1.2:43211/video", "http://192.168.1.2:43211/segment")["X-Seanime-Token"])
        assertTrue(local.headersFor("http://192.168.1.2:43211/video", "https://provider.example/relay").isEmpty())
    }
}
