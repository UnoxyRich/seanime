package app.seanime.tv.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class NativeExternalPlaybackPlanTest {
    private val origin = "http://127.0.0.1:43211"
    private fun token(endpoint: String = "/api/v1/directstream/stream", expiry: Long = 5000): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("endpoint", endpoint).put("exp", expiry).toString().toByteArray()) + ".server-signature"
    private fun source(query: String = "id=episode-a&token=${token()}") = NativeExternalPlaybackSource(
        "$origin/api/v1/directstream/stream?$query", "episode-a", origin, "localfile")
    private fun rejected(source: NativeExternalPlaybackSource): String {
        return try { planExternalPlayback(source, 1000); error("Unsafe external source was accepted") }
        catch (expected: IllegalArgumentException) { expected.message.orEmpty() }
    }

    @Test fun `current direct stream shares only existing media read authority and requires Go validation`() {
        val input = source()
        val plan = planExternalPlayback(input, 1000)
        assertEquals(input.url, plan.uri)
        assertTrue(plan.needsHost)
        assertTrue(plan.probe)
        assertNull(plan.safPath)
        assertEquals("video/*", plan.mimeType)
    }

    @Test fun `generic expired wrong source and ambiguous authority cannot leave the app`() {
        for (query in listOf("id=episode-a", "id=episode-b&token=${token()}", "id=episode-a&id=episode-b&token=${token()}",
            "id=episode-a&token=${token("*")}", "id=episode-a&token=${token("client-id:episode-a")}",
            "id=episode-a&token=${token("/api/v1")}", "id=episode-a&token=${token(expiry = 999)}",
            "id=episode-a&token=server-password-hash", "id=episode-a&token=${token()}&proof=generic-client-proof")) {
            val message = rejected(source(query))
            assertFalse(message.contains("server-password-hash"))
            assertFalse(message.contains("generic-client-proof"))
            assertFalse(message.contains(token()))
        }
        rejected(source().copy(url = "http://user:secret@127.0.0.1:43211/api/v1/directstream/stream?id=episode-a&token=${token()}"))
        rejected(source().copy(url = "$origin/api/v1/settings?token=${token()}"))
    }

    @Test fun `raw file requires matching path and an anonymous byte range probe`() {
        val path = "/media/Show/episode 3.mp4"
        val input = source().copy(url = "$origin/api/v1/mediastream/file?path=${URLEncoder.encode(path, "UTF-8")}", streamPath = path)
        assertTrue(planExternalPlayback(input).probe)
        assertTrue(planExternalPlayback(input).needsHost)
        rejected(input.copy(streamPath = "/media/another.mp4"))
        rejected(input.copy(url = input.url + "&token=broad-server-token"))
        rejected(input.copy(url = "$origin/api/v1/mediastream/direct?hash=unverified-file"))
    }

    @Test fun `online headerless and exact SAF sources need no local host lease`() {
        val online = source().copy(url = "https://cdn.example/video.m3u8?episode=3", playbackType = "onlinestream")
        assertFalse(planExternalPlayback(online).needsHost)
        rejected(online.copy(headers = mapOf("Referer" to "https://provider.example/")))
        rejected(online.copy(headers = mapOf("Authorization" to "private-token")))
        rejected(online.copy(url = "http://localhost:9988/video"))
        rejected(online.copy(url = "file:///private/video.mp4"))
        rejected(online.copy(url = "content://unverified/video"))
        val saf = planExternalPlayback(source().copy(streamPath = "/androidtv/owned-tree/series/episode.mp4"))
        assertEquals("/androidtv/owned-tree/series/episode.mp4", saf.safPath)
        assertEquals("", saf.uri)
        assertFalse(saf.needsHost)
        rejected(source().copy(streamPath = "/androidtv/owned-tree/../private.mp4"))
    }

    @Test fun `converted sources and watch parties never create an external plan`() {
        rejected(source().copy(converted = true))
        rejected(source().copy(url = "$origin/api/v1/mediastream/source/session/master.m3u8"))
        rejected(source().copy(watchParty = true))
        rejected(source().copy(playbackType = "nakama"))
        rejected(source().copy(converted = true, streamPath = "/androidtv/owned-tree/video.mp4"))
    }

    @Test fun `stale return and owner shutdown cannot release the next external session`() {
        val lease = NativeExternalHostLease()
        val first = NativeExternalHostLease.Ticket("first", "a", "url-a", 4, true)
        val next = NativeExternalHostLease.Ticket("next", "b", "url-b", 5, true)
        lease.acquire(first)
        lease.markBackground()
        assertTrue(lease.leftApplication)
        assertTrue(lease.protects(4))
        assertFalse(lease.protects(5))
        lease.acquire(next)
        assertFalse(lease.leftApplication)
        assertNull(lease.release(first.id))
        assertEquals(next, lease.current)
        assertFalse(lease.protects(4))
        assertEquals(next, lease.release(next.id))
        assertNull(lease.current)
        assertFalse(lease.protects(5))
    }

    @Test fun `temporary documents are released on replacement and every terminal release without touching a newer lease`() {
        val revoked = mutableListOf<String>()
        val lease = NativeExternalHostLease { it.grantedDocument?.let(revoked::add) }
        val first = NativeExternalHostLease.Ticket("first", "a", "url-a", 4, false, 3, "content://owned/document/a")
        val next = NativeExternalHostLease.Ticket("next", "b", "url-b", 4, false, 4, "content://owned/document/b")
        lease.acquire(first); lease.acquire(next)
        assertEquals(listOf(first.grantedDocument), revoked)
        assertNull(lease.release(first.id))
        assertEquals(next, lease.current)
        assertNull(lease.matchingSource("a", "url-b", 4, 4))
        assertNull(lease.matchingSource("b", "url-a", 4, 4))
        assertNull(lease.matchingSource("b", "url-b", 3, 4))
        assertNull(lease.matchingSource("b", "url-b", 4, 5))
        assertEquals(next, lease.matchingSource("b", "url-b", 4, 4))
        lease.release(next.id)
        assertEquals(listOf(first.grantedDocument, next.grantedDocument), revoked)
        // Return, failed launch, task removal and remote terminate all reach this one release boundary.
        for (reason in listOf("return", "failed-launch", "task-removed", "remote-terminate")) {
            lease.acquire(first.copy(id = reason))
            lease.release(reason); lease.release(reason)
        }
        assertEquals(6, revoked.size)
    }

    @Test fun `stopped paused and obsolete preparations cannot launch after their probe completes`() {
        val gate = NativeExternalLaunchGate()
        val beforeStop = gate.begin()
        assertTrue(gate.canLaunch(beforeStop, resumed = true, currentSource = true))
        assertFalse(gate.canLaunch(beforeStop, resumed = false, currentSource = true))
        assertFalse(gate.canLaunch(beforeStop, resumed = true, currentSource = false))
        assertTrue(gate.stopPreparation())
        assertFalse(gate.canLaunch(beforeStop, resumed = true, currentSource = true))
        val replacement = gate.begin()
        gate.launched(beforeStop) // Late completion cannot clear the replacement preparation.
        assertTrue(gate.canLaunch(replacement, resumed = true, currentSource = true))
        assertFalse(gate.canLaunch(beforeStop, resumed = true, currentSource = true))
        gate.launched(replacement)
        assertFalse(gate.stopPreparation()) // Normal chooser Stop preserves the launched host lease.
        assertFalse(gate.canLaunch(replacement, resumed = true, currentSource = true))
    }
}
