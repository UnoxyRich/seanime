package app.seanime.tv

import app.seanime.tv.data.SeanimeJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeStartupGateTest {
    @Test fun `cold and first run status cannot resume an old playback checkpoint`() {
        assertFalse(nativeSessionReady(null))
        val firstRun = SeanimeJson.status(JSONObject("""{"version":"3.10.3","serverReady":true,"serverHasPassword":false,"settings":null}"""))
        assertFalse(nativeSessionReady(firstRun))
        assertFalse(nativeSessionReady(firstRun.copy(settings = JSONObject())))
    }

    @Test fun `restricted authenticated and configured statuses have distinct recovery gates`() {
        // The restricted Go status can contain a zero-valued settings object, so its length
        // alone is not evidence that password authentication or initial setup succeeded.
        val restricted = SeanimeJson.status(JSONObject("""{"version":"","serverReady":true,"serverHasPassword":true,"settings":{"id":0,"library":null,"torrent":null,"mediaPlayer":null}}"""))
        assertFalse(nativeSessionReady(restricted))
        val unlockedWithoutSetup = restricted.copy(version = "3.10.3", settings = JSONObject())
        assertFalse(nativeSessionReady(unlockedWithoutSetup))
        val configured = unlockedWithoutSetup.copy(settings = JSONObject("""{"id":1,"library":{"libraryPath":"/existing/library"},"torrent":{},"mediaPlayer":{}}"""))
        assertTrue(nativeSessionReady(configured))
        assertFalse(nativeSessionReady(configured.copy(ready = false)))
        assertFalse(nativeSessionReady(configured.copy(version = "")))
        assertTrue(nativeSessionReady(configured.copy(serverHasPassword = false)))
    }
}
