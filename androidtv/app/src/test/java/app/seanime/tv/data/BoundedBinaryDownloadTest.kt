package app.seanime.tv.data

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class BoundedBinaryDownloadTest {
    @Test fun exactLimitPreservesBinaryBytesAndIdentityHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val expected = byteArrayOf(0, 0xff.toByte(), 0x50, 0x4b, 4, 7, 9, 11)
            server.enqueue(MockResponse().setBody(Buffer().write(expected)))
            SeanimeApiClient(server.url("/").toString(), "fixture-hash").use { client ->
                assertArrayEquals(expected, client.download("/api/v1/report/issue/download", maxBytes = expected.size))
                val request = server.takeRequest()
                assertEquals("/api/v1/report/issue/download", request.path)
                assertEquals("fixture-hash", request.getHeader("X-Seanime-Token"))
                assertEquals("androidtv", request.getHeader("X-Seanime-Client-Platform"))
            }
        }
    }

    @Test fun advertisedOversizedResponseIsRejectedAndNextRequestStillWorks() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("123456789"))
            server.enqueue(MockResponse().setBody("PK"))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val error = runCatching { client.download("/api/v1/report/issue/download", maxBytes = 8) }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error?.message.orEmpty().contains("size limit"))
                assertEquals("PK", client.download("/api/v1/report/issue/download", maxBytes = 8).decodeToString())
            }
        }
    }

    @Test fun chunkedBodyCannotBypassTheDecodedByteLimit() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setChunkedBody("123456789", 2))
            server.enqueue(MockResponse().setChunkedBody("12345678", 3))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val error = runCatching { client.download("/api/v1/directstream/att/font.ttf", maxBytes = 8) }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error?.message.orEmpty().contains("size limit"))
                assertEquals("12345678", client.download("/api/v1/directstream/att/font.ttf", maxBytes = 8).decodeToString())
            }
        }
    }

    @Test fun failedDownloadsKeepExistingHttpErrorSemantics() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"fixture denied"}"""))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val error = runCatching { client.download("/api/v1/report/issue/download", maxBytes = 128) }.exceptionOrNull()
                assertTrue(error is ApiException)
                assertEquals(403, (error as ApiException).statusCode)
                assertEquals("fixture denied", error.message)
            }
        }
    }

    @Test fun gzipLimitAppliesToDecodedBytesRatherThanCompressedLength() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val encoded = ByteArrayOutputStream().apply {
                GZIPOutputStream(this).use { it.write(ByteArray(4096) { 0x41 }) }
            }.toByteArray()
            assertTrue(encoded.size < 128)
            server.enqueue(MockResponse().setBody(Buffer().write(encoded)).setHeader("Content-Encoding", "gzip"))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                val error = runCatching { client.download("/api/v1/directstream/att/font.ttf", maxBytes = 128) }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error?.message.orEmpty().contains("size limit"))
            }
        }
    }

    @Test fun invalidLimitDoesNotStartARequestAndWaitingDownloadCanBeCancelled() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            SeanimeApiClient(server.url("/").toString()).use { client ->
                assertTrue(runCatching { client.download("/api/v1/report/issue/download", maxBytes = 0) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(0, server.requestCount)
                val pending = async { client.download("/api/v1/report/issue/download", maxBytes = 8) }
                yield()
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                pending.cancel()
                pending.join()
                assertTrue(pending.isCancelled)
            }
        }
    }
}
