package app.seanime.tv

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.seanime.tv.data.ProviderMediaContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ProviderMediaDataSourceTest {
    private class RecordingSource : DataSource {
        val opened = mutableListOf<String>()
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long { opened.add(dataSpec.uri.toString()); return 0 }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = opened.lastOrNull()?.let(Uri::parse)
        override fun close() = Unit
    }
    private fun spec(value: String) = DataSpec(Uri.parse(value))

    @Test fun recoveredProviderContextChecksEveryChildOpenAndSurvivesLaterSourceSwitch() {
        val restored = JSONObject("""{"playbackType":"onlinestream","streamUrl":"https://cdn.example/master.m3u8"}""")
        var selected = ProviderMediaContext(ProviderMediaContext.isProviderPlayback(restored))
        val delegate = RecordingSource()
        val source = ProviderMediaDataSource(delegate, selected, emptySet())
        source.open(spec(restored.getString("streamUrl")))
        selected = ProviderMediaContext(false)
        selected.requireMediaUri("file:///owned/local.mkv")
        for (child in listOf("file:///private/key", "content://documents/secret", "data:text/plain,key", "http://192.168.1.2/segment", "http://127.0.0.1/subtitle")) {
            assertTrue(child, runCatching { source.open(spec(child)) }.isFailure)
        }
        source.open(spec("https://cdn.example/segment.ts"))
        assertEquals(listOf("https://cdn.example/master.m3u8", "https://cdn.example/segment.ts"), delegate.opened)
    }

    @Test fun inlineSubtitleExceptionIsAnExactImmutableUriSet() {
        val inline = "file:///owned/cache/native-subtitle-1.ass"
        val exceptions = mutableSetOf(inline)
        val delegate = RecordingSource()
        val source = ProviderMediaDataSource(delegate, ProviderMediaContext(true), exceptions)
        exceptions.add("file:///owned/cache/private-token")
        source.open(spec(inline))
        for (other in listOf("file:///owned/cache/native-subtitle-2.ass", "file:///owned/cache/private-token", "content://owned/native-subtitle-1.ass")) {
            assertTrue(runCatching { source.open(spec(other)) }.isFailure)
        }
        assertEquals(listOf(inline), delegate.opened)
        val nextInline = "file:///owned/cache/native-subtitle-2.ass"
        val nextItem = ProviderMediaDataSource(RecordingSource(), ProviderMediaContext(true), setOf(nextInline))
        assertTrue(runCatching { nextItem.open(spec(inline)) }.isFailure)
        nextItem.open(spec(nextInline))
    }

    @Test fun trustedLocalLanAndConvertedServerMediaKeepTheirExistingAccess() {
        val local = RecordingSource()
        val source = ProviderMediaDataSource(local, ProviderMediaContext(false), emptySet())
        for (url in listOf("file:///owned/video.mkv", "content://owned/video", "http://192.168.1.2:43211/video", "http://127.0.0.1:43211/video")) source.open(spec(url))
        assertEquals(4, local.opened.size)
        val converted = ProviderMediaDataSource(RecordingSource(), ProviderMediaContext(true, "http://127.0.0.1:43211", true), emptySet())
        converted.open(spec("http://127.0.0.1:43211/api/v1/mediastream/source/segment"))
        assertTrue(runCatching { converted.open(spec("http://192.168.1.2/private")) }.isFailure)
        assertTrue(runCatching { converted.open(spec("file:///private/subtitle")) }.isFailure)
    }
}
