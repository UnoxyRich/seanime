package app.seanime.tv.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePluginPresentationProtocolTest {
    @Test fun trayAndCommandsUseTheirExactOwnerAndSurface() {
        val tray = nativePluginPresentationRequest(pluginEventEnvelope("", "tray:open", JSONObject().put("extensionId", "tray-plugin")))!!
        assertEquals(NativePluginPresentation("tray-plugin", NativePluginSurface.TRAY), tray.target)
        assertTrue(tray.open)
        val close = nativePluginPresentationRequest(pluginEventEnvelope("tray-plugin", "tray:close", JSONObject().put("extensionId", "tray-plugin")))!!
        assertEquals(tray.target, close.target)
        assertFalse(close.open)
        val commands = nativePluginPresentationRequest(pluginEventEnvelope("command-plugin", "command-palette:open"))!!
        assertEquals(NativePluginPresentation("command-plugin", NativePluginSurface.COMMANDS), commands.target)
        assertTrue(commands.open)
        assertNull(nativePluginPresentationRequest(pluginEventEnvelope("command-plugin", "command-palette:updated")))
    }
    @Test fun conflictingOrMissingExtensionIdentityCannotOpenAnotherPluginsControls() {
        assertTrue(runCatching { nativePluginPresentationRequest(pluginEventEnvelope("one", "tray:open", JSONObject().put("extensionId", "two"))) }.isFailure)
        assertTrue(runCatching { nativePluginPresentationRequest(pluginEventEnvelope("", "tray:open")) }.isFailure)
        assertTrue(runCatching { nativePluginPresentationRequest(pluginEventEnvelope("", "command-palette:open", JSONObject().put("extensionId", "untrusted-alias"))) }.isFailure)
    }
}
