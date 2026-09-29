// @vitest-environment happy-dom

import React, { act } from "react"
import { createRoot, Root } from "react-dom/client"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { captureSourcePlayback, restoreSourcePlayback, useAndroidTVSourceTranscode } from "./use-androidtv-source-transcode"

const mock = vi.hoisted(() => ({ seaFetch: vi.fn(), androidTV: true }))
vi.mock("@/api/client/requests", () => ({ useSeaQuery: () => ({ seaFetch: mock.seaFetch }) }))
vi.mock("@/api/client/server-url", () => ({ getServerBaseUrl: () => "http://127.0.0.1:43211" }))
vi.mock("@/types/constants", () => ({ get __isAndroidTV__() { return mock.androidTV } }))

let root: Root | undefined
let current: ReturnType<typeof useAndroidTVSourceTranscode>
let video: HTMLVideoElement
let hasRestoredPosition = true
let resetOnDetach = false
let playback = { id: "original-episode", url: "http://127.0.0.1:43211/api/v1/directstream/stream?id=original-episode&token=original", active: true }
const response = { sessionId: "conversion", streamUrl: "/api/v1/mediastream/source/conversion/master.m3u8?token=converted", timeOffset: 0, chapters: [{ name: "Opening", startTime: 0, endTime: 90 }] }
function Harness() {
    const videoRef = React.useRef<HTMLVideoElement | null>(video)
    current = useAndroidTVSourceTranscode({ playbackId: playback.id, sourceUrl: playback.url, active: playback.active, hasRestoredPosition: () => hasRestoredPosition, videoRef })
    React.useEffect(() => () => { if (resetOnDetach) video.currentTime = 0 }, [playback.url])
    return <span data-source={playback.url}>{current.streamUrl}</span>
}
async function mount() {
    root = createRoot(document.createElement("div"))
    await act(async () => root!.render(<Harness />))
}
beforeEach(() => {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true)
    mock.seaFetch.mockReset()
    mock.androidTV = true
    hasRestoredPosition = true
    resetOnDetach = false
    video = document.createElement("video")
    Object.defineProperty(video, "pause", { value: vi.fn() })
    Object.defineProperty(video, "play", { value: vi.fn(async () => {}) })
    Object.defineProperty(video, "paused", { value: false, configurable: true })
    Object.defineProperty(video, "duration", { value: 1000, configurable: true })
    video.currentTime = 245.5
    video.playbackRate = 1.25
    video.volume = 0.4
    video.muted = true
    playback = { id: "original-episode", url: "http://127.0.0.1:43211/api/v1/directstream/stream?id=original-episode&token=original", active: true }
    mock.seaFetch.mockImplementation(async endpoint => endpoint.endsWith("/request") ? response : true)
})
afterEach(async () => {
    if (root) await act(async () => root!.unmount())
    root = undefined
    delete window.AndroidTV
    vi.unstubAllGlobals()
})

describe("Android TV advanced-player source conversion", () => {
    it("changes only browser URL and retains original identity, chapters and absolute position", async () => {
        await mount()
        const original = { ...playback }
        await act(async () => current.convert())
        expect(mock.seaFetch).toHaveBeenCalledWith("/api/v1/mediastream/source/request", "POST", { sourceUrl: original.url, playbackId: original.id }, undefined, expect.any(AbortSignal))
        expect(playback).toEqual(original)
        expect(current.converted).toBe(true)
        expect(current.streamUrl).toBe("http://127.0.0.1:43211/api/v1/mediastream/source/conversion/master.m3u8?token=converted")
        expect(current.chapters).toEqual([{ uid: 0, start: 0, end: 90, text: "Opening" }])
        video.currentTime = 0
        video.playbackRate = 1
        video.volume = 1
        video.muted = false
        expect(current.restore(video)).toBe("position")
        expect(video.currentTime).toBe(245.5)
        expect(video.playbackRate).toBe(1.25)
        expect(video.volume).toBe(0.4)
        expect(video.muted).toBe(true)
        expect(video.play).toHaveBeenCalledOnce()
        expect(current.restore(video)).toBe(false)
    })

    it("keeps a paused source paused and restores when returning to the original stream", async () => {
        Object.defineProperty(video, "paused", { value: true, configurable: true })
        await mount()
        await act(async () => current.convert())
        expect(current.restore(video)).toBe("position")
        expect(video.play).not.toHaveBeenCalled()
        await act(async () => current.reset())
        expect(current.converted).toBe(false)
        expect(current.streamUrl).toBe(playback.url)
        expect(current.restore(video)).toBe("position")
        expect(video.currentTime).toBe(245.5)
        expect(mock.seaFetch).toHaveBeenCalledWith("/api/v1/mediastream/source/stop", "POST", { sessionId: "conversion" })
    })

    it("aborts pending probes on source replacement and stops late results", async () => {
        let resolve: (value: typeof response) => void = () => {}
        mock.seaFetch.mockImplementation(endpoint => endpoint.endsWith("/request") ? new Promise(done => { resolve = done }) : Promise.resolve(true))
        await mount()
        let pending: Promise<void> | undefined
        await act(async () => { pending = current.convert() })
        const signal = mock.seaFetch.mock.calls[0][4] as AbortSignal
        expect(current.pending).toBe(true)
        playback = { ...playback, id: "next-episode", url: "http://127.0.0.1:43211/next.mkv" }
        await act(async () => root!.render(<Harness />))
        expect(signal.aborted).toBe(true)
        await act(async () => { resolve(response); await pending })
        expect(current.converted).toBe(false)
        expect(current.streamUrl).toBe(playback.url)
        expect(mock.seaFetch).toHaveBeenCalledWith("/api/v1/mediastream/source/stop", "POST", { sessionId: "conversion" })
    })

    it("reconverts a paused same-episode URL refresh before detaching the old frame", async () => {
        Object.defineProperty(video, "paused", { value: true, configurable: true })
        await mount()
        await act(async () => current.convert())
        current.restore(video)
        video.currentTime = 378.5
        resetOnDetach = true
        const refreshed = "http://127.0.0.1:43211/api/v1/directstream/stream?id=original-episode&token=refreshed"
        mock.seaFetch.mockImplementation(async endpoint => endpoint.endsWith("/request") ? { ...response, sessionId: "renewed", streamUrl: "/api/v1/mediastream/source/renewed/master.m3u8" } : true)
        playback = { ...playback, url: refreshed }
        await act(async () => root!.render(<Harness />))
        expect(current.converted).toBe(true)
        expect(current.streamUrl).toContain("/source/renewed/")
        expect(mock.seaFetch).toHaveBeenCalledWith("/api/v1/mediastream/source/request", "POST", { sourceUrl: refreshed, playbackId: "original-episode" }, undefined, expect.any(AbortSignal))
        expect(current.restore(video)).toBe("position")
        expect(video.currentTime).toBe(378.5)
        expect(video.play).not.toHaveBeenCalled()
        expect(video.playbackRate).toBe(1.25)
        expect(video.volume).toBe(0.4)
        expect(video.muted).toBe(true)
        expect(mock.seaFetch).toHaveBeenCalledWith("/api/v1/mediastream/source/stop", "POST", { sessionId: "conversion" })
    })

    it("stops conversion on player unmount", async () => {
        await mount()
        await act(async () => current.convert())
        await act(async () => root!.unmount())
        root = undefined
        expect(mock.seaFetch).toHaveBeenCalledWith("/api/v1/mediastream/source/stop", "POST", { sessionId: "conversion" })
    })

    it("restores settings without consuming initial continuity when the original codec never loaded", async () => {
        hasRestoredPosition = false
        video.currentTime = 0
        Object.defineProperty(video, "duration", { value: NaN, configurable: true })
        Object.defineProperty(video, "paused", { value: true, configurable: true })
        await mount()
        await act(async () => current.convert())
        Object.defineProperty(video, "duration", { value: 1000, configurable: true })
        // VideoCore can now perform its normal initialState/watch-history seek.
        expect(current.restore(video)).toBe("settings")
        expect(video.currentTime).toBe(0)
        expect(video.playbackRate).toBe(1.25)
        expect(video.pause).toHaveBeenCalled()
    })

    it("does not treat loadedmetadata as an applied initialState or continuity seek", async () => {
        hasRestoredPosition = false
        video.currentTime = 0
        Object.defineProperty(video, "readyState", { value: 1 })
        await mount()
        await act(async () => current.convert())
        expect(current.restore(video)).toBe("settings")
    })

    it("shows a later conversion playback failure while retaining converted source identity", async () => {
        await mount()
        await act(async () => current.convert())
        await act(async () => current.reportPlaybackError("Converted decoder failed"))
        expect(current.error).toBe("Converted decoder failed")
        expect(current.converted).toBe(true)
        expect(current.streamUrl).toContain("/source/conversion/")
        expect(playback.id).toBe("original-episode")
    })

    it("releases native playback through its original URL before browser conversion", async () => {
        const controlNativePlayer = vi.fn()
        window.AndroidTV = { nativePlayerActive: () => true, controlNativePlayer } as unknown as NonNullable<Window["AndroidTV"]>
        await mount()
        await act(async () => current.convert())
        expect(controlNativePlayer).toHaveBeenCalledWith(playback.url, "stop", 0)
    })

    it("never activates conversion for ordinary web clients", async () => {
        mock.androidTV = false
        await mount()
        await act(async () => current.convert())
        expect(current.available).toBe(false)
        expect(mock.seaFetch).not.toHaveBeenCalled()
    })

    it("rejects nonzero offsets and stops the invalid session", async () => {
        mock.seaFetch.mockResolvedValue({ ...response, timeOffset: 245.5 })
        await mount()
        await act(async () => current.convert())
        expect(current.converted).toBe(false)
        expect(current.error).toMatch(/original timeline/)
        expect(current.streamUrl).toBe(playback.url)
        expect(mock.seaFetch).toHaveBeenCalledWith("/api/v1/mediastream/source/stop", "POST", { sessionId: "conversion" })
    })
})

describe("source timeline snapshot", () => {
    it("clamps nonfinite input and seek-at-end without adding an offset", () => {
        Object.defineProperty(video, "currentTime", { value: NaN, writable: true })
        const snapshot = captureSourcePlayback(video)!
        expect(snapshot.currentTime).toBe(0)
        restoreSourcePlayback(video, { ...snapshot, currentTime: 2000, positionReady: true })
        expect(video.currentTime).toBe(999.99)
        expect(() => restoreSourcePlayback(video, snapshot, 20)).toThrow(/original timeline/)
    })
})
