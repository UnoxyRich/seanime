package app.seanime.tv.data

import android.os.Looper
import android.os.NetworkOnMainThreadException
import android.os.StrictMode
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Owned loopback TLS mechanism regression; this does not exercise real-provider playback. */
@RunWith(AndroidJUnit4::class)
class NativePlayerHttpRetirementTest {
    @Test fun idleHttp11TlsRetiresOffMainAndKeepsReplacementUsable() {
        verifyRetirement(Protocol.HTTP_1_1)
    }

    @Test
    @SdkSuppress(minSdkVersion = 29) // Android's public SSLSockets/ALPN path is available here.
    fun idleHttp2TlsRetiresOffMainAndKeepsReplacementUsable() {
        verifyRetirement(Protocol.HTTP_2)
    }

    private fun verifyRetirement(protocol: Protocol) {
        assertNotSame("TLS setup and waiting belong on the instrumentation worker", Looper.getMainLooper(), Looper.myLooper())
        val tls = fixtureTls()
        val protocols = if (protocol == Protocol.HTTP_2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) else listOf(Protocol.HTTP_1_1)
        MockWebServer().use { server ->
            server.protocols = protocols
            server.useHttps(tls.context.socketFactory, false)
            server.start(loopback, 0)
            repeat(4) { server.enqueue(MockResponse().setBody("owned TLS media-$it")) }
            val legacy = OkHttpClient.Builder().apply { fixtureConfiguration(tls, protocols) }.build()
            val owner = NativePlayerHttpClients()
            val client = owner.createClient { fixtureConfiguration(tls, protocols) }
            val replacementOwner = NativePlayerHttpClients()
            val replacement = replacementOwner.createClient { fixtureConfiguration(tls, protocols) }
            try {
                readFixture(legacy, server, protocol, "owned TLS media-0")
                assertEquals("negative control needs a real idle TLS connection", 1, legacy.connectionPool.idleConnectionCount())
                val legacyFailure = onMainWithNetworkDeath { legacy.connectionPool.evictAll() }
                assertTrue("legacy main-thread TLS eviction must hit Android's network policy: $legacyFailure",
                    legacyFailure is NetworkOnMainThreadException)

                readFixture(client, server, protocol, "owned TLS media-1")
                assertEquals(1, client.connectionPool.idleConnectionCount())
                assertNull("owner retirement must do no main-thread network work", onMainWithNetworkDeath {
                    repeat(3) { owner.close() }
                })

                // This is a distinct owner and pool, even if old cleanup is still in flight.
                readFixture(replacement, server, protocol, "owned TLS media-2")
                awaitRetired(client)
                assertFalse("old cleanup must not shut down the replacement", replacement.dispatcher.executorService.isShutdown)
                readFixture(replacement, server, protocol, "owned TLS media-3")
                assertEquals(4, server.requestCount)
                assertNull(onMainWithNetworkDeath { replacementOwner.close() })
                awaitRetired(replacement)
            } finally {
                // Cleanup remains off main, including when a negative-control assertion fails.
                owner.close()
                replacementOwner.close()
                legacy.dispatcher.cancelAll()
                legacy.connectionPool.evictAll()
                legacy.dispatcher.executorService.shutdown()
                awaitRetired(client)
                awaitRetired(replacement)
            }
        }
    }

    private fun readFixture(client: OkHttpClient, server: MockWebServer, protocol: Protocol, body: String) {
        val request = Request.Builder().url(server.url("/owned-media").newBuilder().host(fixtureHost).build()).build()
        client.newCall(request).execute().use { response ->
            assertNotNull("fixture must complete an actual verified TLS handshake", response.handshake)
            assertEquals("fixture must negotiate the requested protocol", protocol, response.protocol)
            assertEquals(body, response.body!!.string())
        }
    }

    private fun onMainWithNetworkDeath(action: () -> Unit): Throwable? {
        val failure = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val previous = StrictMode.getThreadPolicy()
            try {
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previous)
                    .detectNetwork().penaltyDeathOnNetwork().build())
                try { action() } catch (error: Throwable) { failure.set(error) }
            } finally {
                StrictMode.setThreadPolicy(previous)
            }
        }
        return failure.get()
    }

    private fun awaitRetired(client: OkHttpClient) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline &&
            (client.connectionPool.connectionCount() != 0 || !client.dispatcher.executorService.isShutdown)) {
            SystemClock.sleep(10)
        }
        assertEquals("retired pool must empty", 0, client.connectionPool.connectionCount())
        assertTrue("retired dispatcher must shut down", client.dispatcher.executorService.isShutdown)
        assertTrue("retired dispatcher must finish", client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
    }

    private fun OkHttpClient.Builder.fixtureConfiguration(tls: Tls, protocols: List<Protocol>) {
        sslSocketFactory(tls.context.socketFactory, tls.trust)
        dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                require(hostname == fixtureHost)
                return listOf(loopback)
            }
        })
        proxySelector(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                require(uri.host == fixtureHost) { "Only the explicit loopback fixture alias is allowed" }
                return listOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        protocols(protocols)
        connectTimeout(5, TimeUnit.SECONDS)
        readTimeout(5, TimeUnit.SECONDS)
    }

    private data class Tls(val context: SSLContext, val trust: X509TrustManager)
    private fun fixtureTls(): Tls {
        // Same public test identity as NativePlayerHttpClientsTest, valid 2000-2100 for this alias.
        // Trust is confined to these clients/server; the default hostname verifier remains enabled.
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.decode(TEST_CERTIFICATE, Base64.DEFAULT).inputStream())
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(TEST_PRIVATE_KEY, Base64.DEFAULT)))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("fixture", privateKey, CharArray(0), arrayOf(certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("fixture", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trust), SecureRandom()) }
        return Tls(context, trust)
    }

    private companion object {
        val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
        const val fixtureHost = "player-fixture.invalid"
        const val TEST_CERTIFICATE = "MIIC9zCCAd+gAwIBAgIUQHcDWbxTqQSR3rhjXlVGV9olRewwDQYJKoZIhvcNAQELBQAwITEfMB0GA1UEAwwWcGxheWVyLWZpeHR1cmUuaW52YWxpZDAgFw0wMDAxMDEwMDAwMDBaGA8yMTAwMDEwMTAwMDAwMFowITEfMB0GA1UEAwwWcGxheWVyLWZpeHR1cmUuaW52YWxpZDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAJ3MtpBPRZzxQJg9CDGTbU3oUG2NM+Wim5SZOF8RQbZHsfBqt3HBW3+bq78UdoDpwhjLvkHLEEGIqRVQICF7MW8ioKJ/G0YJmk/nnvHiGbt2L8ko8HG9wjGAYepDT/LTpvPn+0gOQhy2EYMH4j0oI9QfMEmwWyHW7fDN31PXssEJc6DAMvu76DXB9fPQqJkGsDayeWESycPfMnbngkr0pV+HVrJmpbBlZz3msSeYEe9PvD24vy5sIfJTNfa+u59LH4yTWb2t0wXktD5pD/WuwGbIXwdIpSDUgHYpP1jzZ7qSrxEB+1EsrbK+1tCiKLCXetufum/qJHjFLzaBxT7iZRsCAwEAAaMlMCMwIQYDVR0RBBowGIIWcGxheWVyLWZpeHR1cmUuaW52YWxpZDANBgkqhkiG9w0BAQsFAAOCAQEAfXIm8jD0lScdvkSAstTfn0AhlotY1jqwFDRNuO7P6E4wJrg695FdiKYlH9ztEXAz6mrcM3g4SY2t8/CpCboqTPJDv02JdWizHgXHbE1Mqw/cVEfcsTGVS4XZ1dpy00G2yYpWCtCaffB5ayV74NZuGIdL9bF4e/7PLWoV/iHd12QYukqa8Tb0I2OZuFUjDACILuVuTK0Y+wpetClCGZI70ybwOLs7PT2MI4d1CfPYs1OXwp8EVKReV7b/31MimsXYeFAQYavZyYLaR/tHMo+muPDTqmjOnePaWrMk8fGhURjeQ2ZSBVGlrXY7fM4/j52Drcv6RbM7YztEU2LhRsa71g=="
        const val TEST_PRIVATE_KEY = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCdzLaQT0Wc8UCYPQgxk21N6FBtjTPlopuUmThfEUG2R7HwardxwVt/m6u/FHaA6cIYy75ByxBBiKkVUCAhezFvIqCifxtGCZpP557x4hm7di/JKPBxvcIxgGHqQ0/y06bz5/tIDkIcthGDB+I9KCPUHzBJsFsh1u3wzd9T17LBCXOgwDL7u+g1wfXz0KiZBrA2snlhEsnD3zJ254JK9KVfh1ayZqWwZWc95rEnmBHvT7w9uL8ubCHyUzX2vrufSx+Mk1m9rdMF5LQ+aQ/1rsBmyF8HSKUg1IB2KT9Y82e6kq8RAftRLK2yvtbQoiiwl3rbn7pv6iR4xS82gcU+4mUbAgMBAAECggEAS5bQDyHCDXNth1+ZCLJ/5hV2TXwZ05MIpu6cl8Ga8je2z503IblHXMHTzBT/zTHsxdb3XOnqcBIIOMrokVFDdWKngx+TD4IzFrqzo1e1Dt0G9/vx7fJBz1eZz8+NwRrM+0JxVutplPpMOjGxGK2dOBP2nB3sEbI0yai8pZuKJ7hfSCuFFqVt49USfTrug9rQpCDL0f1Ifi5ABtaFYUyoM9M4R5snKJ+wv+Zft+ZR8ix1GFM7hewaCi9OAZgkxPDkYE2l7rdKXG9WHzq2KJdK1B4ULEaw41RxWJIZYKhtpNBzMicba/6U/5jgNHqmOvmTJ4MZ+4YDGLlDlRSpRQvdAQKBgQDenEkzNIGBOx9Vz0r75lkk1ItdXvUXOiUlS0f291sQ/bN4NHvFisdXT+NQA2y22wukONVtGdOhVSmyMVmgPDmdi0/VJZRmIiqLB8H2/d9a4Yj26HlvSYpenGvQa8A95tUXSCBtMCMpMh+0IQi8Z5kWNfduBjKNJ4jsuXGtG2AbAQKBgQC1d9hh/pGhmI6Bckr0qu/BYiIFeZNHMAp+kzfhu9KujvOTxeoiq2b7uuyFcnxCJICjoo7EbSpWuLzxhRyDHcYUZu0msQSPsPzp6L3L8RMrctXgUuCaRsBhRsynee7H6dSc9kVbsTrkupGkLWemY69nK5Pb8LQf2ecch8SPU/uMGwKBgG4GwQdWBExjdHFtK5qll5nkk51quajpTELKmp8uUwxq2LGo/yP8G9rD2Y5Kowkd6vsYPCTYhwlOlnVEfw/7tF5x5Nts35Q7ftuI0g3KHQNGRfQDo0GmD4YDuiYhm7r8xIXlWGGfUUGjTJgzW6YDbl7T/Z+b4JBz4fFfFxiAV1gBAoGBAKUSCmxBpHmpi1/m9pYPdB6mRKpUSAuGgNVY14loUCJneNygOPYmknxEMejGFpAYIkg3k8TMRKo0S/MrEZ+Xktp2Mh1zAuIurjGcfCGq/rQUNsdivFq6Jz+Vpo5l1TZW1wec4cShuB/eMqN0hgeOQD0KH+r+zur2TUvfmIaEb5qnAoGBALHt7DQ3m/83ShLzfxte1CbO21HJxF4Nz/PQSIpv28yuMidithbHSFTFBDnn2EIgUrBGOR63XFGwXM1ezrkwP5hfM/yH3LeavV9JxjMQdDOJXuHfabwA1T1G2uFOAY9OCOb7VE0DoFszkugBd07s17mD+x3e5crwq6evLqJT4WgJ"
    }
}
