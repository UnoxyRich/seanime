package app.seanime.tv.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSkipDataTest {
    private fun entry(start: Any, end: Any) = JSONObject().put("interval", JSONObject().put("startTime", start).put("endTime", end))

    @Test fun `skip targets respect exact intervals and known source duration`() {
        val data = NativeSkipData.parse(JSONObject().put("op", entry(1.25, 8.5)).put("ed", entry(10, 12)))!!
        assertNull(data.target(1249, 12000))
        assertEquals(NativeSkipTarget("Skip intro", 8500), data.target(1250, 12000))
        assertNull(data.target(8500, 12000))
        assertEquals(NativeSkipTarget("Skip ending", 12000), data.target(11999, 12000))
        assertNull(data.target(12000, 12000))
        assertNull(data.target(1250, -1))
        assertNull(data.target(11000, 11500))
    }

    @Test fun `malformed negative reversed overflowing and submillisecond entries are rejected`() {
        listOf(entry(-1, 3), entry(3, 3), entry(3, 2), entry("1", 3), entry(0, 1e300), entry(0.0001, 0.0002),
            JSONObject(), JSONObject().put("interval", "wrong")).forEach { invalid ->
            val data = NativeSkipData.parse(JSONObject().put("op", invalid))!!
            assertTrue(data.toJson().isNull("op"))
            assertNull(data.target(1000, 20000))
        }
    }

    @Test fun `overlapping or touching ending is discarded without changing caller data`() {
        val source = JSONObject().put("op", entry(0, 8)).put("ed", entry(8, 12))
        val data = NativeSkipData.parse(source)!!
        assertTrue(data.toJson().isNull("ed"))
        assertFalse(source.isNull("ed"))
        source.getJSONObject("op").getJSONObject("interval").put("endTime", 2)
        assertEquals(8.0, data.toJson().getJSONObject("op").getJSONObject("interval").getDouble("endTime"), 0.0)
        data.toJson().getJSONObject("op").getJSONObject("interval").put("endTime", 1)
        assertEquals(NativeSkipTarget("Skip intro", 8000), data.target(3000, 12000))
    }

    @Test fun `skip data cannot cross a playback reset or accept stale updates`() {
        val state = NativeSkipState()
        val raw = JSONObject().put("op", entry(0, 8))
        state.set("", raw)
        assertNull(state.data(""))
        state.reset("first")
        state.set("first", raw)
        assertNotNull(state.data("first"))
        assertNull(state.data("second"))
        state.reset("second")
        state.set("first", raw)
        assertNull(state.data("second"))
        state.set("second", raw)
        assertNotNull(state.data("second"))
        state.set("second", null)
        assertNull(state.data("second"))
    }
}
