package app.seanime.tv.platform

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSubtitleProtocolTest {
    private fun event(text: String = "{\\move(10,20,30,40)}Hello, world", start: Int = 1000) = JSONObject()
        .put("trackNumber", 2).put("startTime", start).put("duration", 2000).put("codecID", "S_TEXT/ASS").put("text", text)
        .put("extraData", JSONObject().put("readorder", "7").put("layer", "3").put("style", "Signs")
            .put("name", "Actor").put("marginl", "10").put("marginr", "20").put("marginv", "30").put("effect", "Scroll up;0;50;5"))
    private fun batch(id: String, generation: Int, value: JSONObject = event()) = JSONObject().put("playbackId", id)
        .put("generationId", generation).put("events", JSONArray().put(value))

    @Test fun `ASS packet retains authored style layer margins effects tags and comma text`() {
        val parsed = NativeSubtitleProtocol.parse(event())!!
        assertEquals(1000L, parsed.startMs)
        assertEquals(3000L, parsed.endMs)
        assertEquals("7,3,Signs,Actor,10,20,30,Scroll up;0;50;5,{\\move(10,20,30,40)}Hello, world", NativeSubtitleProtocol.assChunk(parsed).toString(Charsets.UTF_8))
        val header = "[Script Info]\nPlayResX: 1920\n[V4+ Styles]\nStyle: Signs,CustomFont,48\n\u0000"
        assertEquals(header.trimEnd('\u0000'), NativeSubtitleProtocol.header(JSONObject().put("codecPrivate", header)).toString(Charsets.UTF_8))
    }

    @Test fun `seek generation and playback identity reject stale subtitle delivery`() {
        val queue = NativeSubtitleTimeline("current")
        assertFalse(queue.accept(batch("old", 4)))
        assertTrue(queue.accept(batch("current", 4)))
        assertEquals(1, queue.active(2, 1500).size)
        assertTrue(queue.active(2, 3000).isEmpty())
        assertTrue(queue.accept(batch("current", 4)))
        assertEquals(1, queue.events.size)
        queue.seek()
        assertTrue(queue.events.isEmpty())
        assertFalse(queue.accept(batch("current", 4)))
        assertTrue(queue.accept(batch("current", 5, event("new", 6000))))
        assertFalse(queue.accept(batch("current", 3)))
        assertEquals("new", queue.active(2, 6500).single().text)
    }

    @Test fun `PGS keeps original bitmap crop canvas and placement metadata`() {
        val source = event("base64-image").put("codecID", "S_HDMV/PGS").put("extraData", JSONObject()
            .put("canvas_width", "1920").put("canvas_height", "1080").put("x", "123").put("y", "850")
            .put("crop_x", "2").put("crop_y", "4").put("crop_width", "100").put("crop_height", "30"))
        val parsed = NativeSubtitleProtocol.parse(source)!!
        assertTrue(parsed.isPgs)
        assertEquals("1920", parsed.extra.getString("canvas_width"))
        assertEquals("123", parsed.extra.getString("x"))
        assertEquals("100", parsed.extra.getString("crop_width"))
        assertNull(NativeSubtitleProtocol.parse(event().put("duration", 0)))
    }
    @Test fun `PGS final duration corrections replace the earlier display interval`() {
        val queue = NativeSubtitleTimeline("current")
        val image = event("png").put("codecID", "S_HDMV/PGS").put("duration", 5000)
        assertTrue(queue.accept(batch("current", 1, image)))
        assertTrue(queue.accept(batch("current", 1, JSONObject(image.toString()).put("duration", 1000))))
        assertEquals(1, queue.events.size)
        assertTrue(queue.active(2, 2200).isEmpty())
        assertTrue(queue.correctionRevision > 0)
        assertNull(NativeSubtitleProtocol.parse(JSONObject(image.toString()).put("duration", 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversize authored style header is rejected before JNI`() {
        NativeSubtitleProtocol.header(JSONObject().put("codecPrivate", "a".repeat(1024 * 1024 + 1)))
    }

    @Test fun `duplicate or omitted read orders do not drop separate dialogue`() {
        val orders = NativeAssReadOrders()
        val first = NativeSubtitleProtocol.parse(event().put("extraData", JSONObject().put("readorder", "0")))!!
        val second = first.copy(startMs = 2000, text = "Another line")
        assertEquals(0L, orders.next(first))
        assertEquals(1L, orders.next(second))
        assertEquals(2L, orders.next(first.copy(extra = JSONObject())))
        orders.clear()
        assertEquals(0L, orders.next(first))
    }

    @Test fun `PGS pixel memory counts retained displayed frames as well as cache`() {
        assertEquals(8_294_400L, NativePgsBudget.allocationBytes(1920, 1080))
        assertTrue(NativePgsBudget.fits(1920, 1080, 3 * 8_294_400L))
        assertFalse(NativePgsBudget.fits(1920, 1080, 4 * 8_294_400L))
        assertNull(NativePgsBudget.allocationBytes(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(NativePgsBudget.allocationBytes(0, 1080))
        assertNull(NativePgsBudget.allocationBytes(8192, 8192))
    }

}
