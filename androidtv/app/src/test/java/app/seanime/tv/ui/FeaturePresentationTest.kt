package app.seanime.tv.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FeaturePresentationTest {
    @Test fun missingMediaMetadataHasReadableFallback() {
        assertEquals("Manga 5", JSONObject().mediaTitle("Manga 5"))
        assertEquals("Preferred", JSONObject("""{"title":{"userPreferred":"Preferred","english":"English"}}""").mediaTitle())
        assertEquals("English", JSONObject("""{"title":{"userPreferred":null,"english":"English"}}""").mediaTitle())
        assertEquals("Untitled", JSONObject("""{"title":null}""").mediaTitle())
    }
    @Test fun sensitiveSettingFieldsAreRecognizedCaseInsensitively() {
        listOf("remoteServerPassword", "vcTranslateApiKey", "accessToken", "clientSecret").forEach { assertTrue(it, settingIsSecret(it)) }
        assertFalse(settingIsSecret("defaultPlayer"))
    }
    @Test fun permissionsExposeServiceNetworkFileAndCommandAccess() {
        val manifest = JSONObject("""{"permissions":{"scopes":["database","settings"],"allow":{"networkAccess":{"allowedDomains":["example.com"]},"readPaths":["/media"],"writePaths":["/tmp"],"commandScopes":[{"command":"ffmpeg","description":"Transcode"}],"unsafeFlags":[{"flag":"dom-script-manipulation","reason":"Legacy UI"}]}}}""")
        val text = pluginPermissionSummary(manifest)
        listOf("database", "settings", "example.com", "/media", "/tmp", "ffmpeg", "Legacy UI").forEach { assertTrue(it, text.contains(it)) }
    }
    @Test fun advancedEditorRedactsAndPreservesSecrets() {
        val current = JSONObject("""{"remoteServerPassword":"private-value","enabled":false,"nested":{"apiKey":"key-value","host":"example.com"}}""")
        val redacted = redactSettingsForEditor(current)
        assertFalse(redacted.toString().contains("private-value"))
        assertFalse(redacted.toString().contains("key-value"))
        redacted.put("enabled", true)
        val restored = preserveSettingSecrets(redacted, current)
        assertEquals("private-value", restored.getString("remoteServerPassword"))
        assertEquals("key-value", restored.getJSONObject("nested").getString("apiKey"))
        assertTrue(restored.getBoolean("enabled"))
        assertFalse(current.getBoolean("enabled"))
    }
    @Test fun jsonNullNeverAppearsAsAFieldValue() {
        assertEquals("Not set", JSONObject("""{"value":null}""").text("value", "Not set"))
    }
}
