// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from "vitest"
import { attachAndroidTVPlayer, pauseAndroidTVBrowserPlayer, type AndroidTVPlayerSnapshot } from "./android-tv-player"

const url = "http://127.0.0.1:43211/stream/episode-one"
const state = (overrides: Partial<AndroidTVPlayerSnapshot> = {}): AndroidTVPlayerSnapshot => ({
    url, positionMs: 40_000, durationMs: 100_000, bufferedPositionMs: 60_000,
    paused: false, buffering: false, completed: false, speed: 1, volume: 0.7,
    muted: false, videoWidth: 1920, videoHeight: 1080, active: true, closed: false,
    ...overrides,
})

let video: HTMLVideoElement
let play: ReturnType<typeof vi.fn>
let pause: ReturnType<typeof vi.fn>

beforeEach(() => {
    video = document.createElement("video")
    play = vi.fn(async () => {})
    pause = vi.fn()
    Object.defineProperty(video, "play", { configurable: true, value: play })
    Object.defineProperty(video, "pause", { configurable: true, value: pause })
})

describe("Android TV media adapter", () => {
    it("ignores late browser pause and completion events while Media3 owns playback", () => {
        const paused = vi.fn()
        const ended = vi.fn()
        video.addEventListener("pause", paused)
        video.addEventListener("ended", ended)
        const adapter = attachAndroidTVPlayer(video, url, vi.fn())
        adapter.update(state())
        const browserEvent = (type: string) => {
            const event = new Event(type)
            Object.defineProperty(event, "isTrusted", { value: true })
            video.dispatchEvent(event)
        }
        browserEvent("pause")
        browserEvent("ended")
        expect(paused).not.toHaveBeenCalled()
        expect(ended).not.toHaveBeenCalled()
        adapter.update(state({ paused: true, completed: true }))
        expect(paused).toHaveBeenCalledOnce()
        expect(ended).toHaveBeenCalledOnce()
        adapter.update(state({ paused: true, completed: true, active: false, closed: true }))
        browserEvent("pause")
        expect(paused).toHaveBeenCalledTimes(2)
        adapter.dispose()
        browserEvent("ended")
        expect(ended).toHaveBeenCalledTimes(2)
    })

    it("pauses the browser decoder during URL refresh without pausing native playback", () => {
        pauseAndroidTVBrowserPlayer(video)
        expect(pause).toHaveBeenCalledOnce()
        const send = vi.fn()
        const adapter = attachAndroidTVPlayer(video, url, send)
        adapter.update(state())
        pauseAndroidTVBrowserPlayer(video)
        expect(pause).toHaveBeenCalledTimes(2)
        expect(send).not.toHaveBeenCalled()
        expect(video.paused).toBe(false)
        adapter.dispose()
        pauseAndroidTVBrowserPlayer(video)
        expect(pause).toHaveBeenCalledTimes(3)
        expect(send).not.toHaveBeenCalled()
        pauseAndroidTVBrowserPlayer(null)
    })

    it("replaces an adapter without wrapping stale native controls", () => {
        const previousSend = vi.fn()
        const previous = attachAndroidTVPlayer(video, url, previousSend)
        const send = vi.fn()
        const next = attachAndroidTVPlayer(video, `${url}?refreshed`, send)
        expect(previous.isDisposed()).toBe(true)
        pauseAndroidTVBrowserPlayer(video)
        expect(pause).toHaveBeenCalledOnce()
        video.pause()
        expect(send).toHaveBeenCalledWith("pause", 0)
        expect(previousSend).not.toHaveBeenCalled()
        previous.dispose()
        next.dispose()
        expect(video.pause).toBe(pause)
    })

    it("reports native duration and progress even when the browser cannot decode metadata", () => {
        expect(video.duration).toBeNaN()
        const adapter = attachAndroidTVPlayer(video, url, vi.fn())
        const statuses: { time: number; duration: number; paused: boolean }[] = []
        video.addEventListener("timeupdate", () => statuses.push({ time: video.currentTime, duration: video.duration, paused: video.paused }))
        expect(adapter.update(state())).toBe(true)
        expect(statuses).toEqual([{ time: 40, duration: 100, paused: false }])
        expect(video.readyState).toBe(4)
        expect(video.videoWidth).toBe(1920)
        expect(video.buffered.end(0)).toBe(60)
        expect(() => video.buffered.end(1)).toThrow()
        adapter.update(state({ positionMs: 85_000 }))
        expect(video.currentTime / video.duration).toBe(0.85)
        adapter.dispose()
        expect(video.duration).toBeNaN()
    })

    it("forwards controls and emits Media3 acknowledgements without command echoes", async () => {
        const send = vi.fn()
        const adapter = attachAndroidTVPlayer(video, url, send)
        const events: string[] = []
        for (const name of ["play", "pause", "seeking", "seeked", "ratechange", "volumechange"]) {
            video.addEventListener(name, () => events.push(name))
        }
        adapter.update(state())
        expect(send).not.toHaveBeenCalled()
        video.pause()
        await video.play()
        video.currentTime = 45
        video.playbackRate = 1.5
        video.volume = 0.4
        video.muted = true
        expect(send.mock.calls).toEqual([["pause", 0], ["play", 0], ["seekTo", 45_000], ["speed", 1.5], ["volume", 0.4], ["muted", 1]])
        expect(video.seeking).toBe(true)
        adapter.update(state({ positionMs: 45_000, paused: true, speed: 1.5, volume: 0.4, muted: true }))
        expect(events).toEqual(["play", "volumechange", "seeking", "pause", "ratechange", "volumechange", "seeked"])
        expect(send).toHaveBeenCalledTimes(6)
        expect(video.seeking).toBe(false)
        expect(video.muted).toBe(true)
        expect(play).not.toHaveBeenCalled()
        expect(pause).not.toHaveBeenCalled()
        adapter.dispose()
    })

    it("restores continuity through the native seek command on first canplay", () => {
        const send = vi.fn()
        const adapter = attachAndroidTVPlayer(video, url, send)
        video.addEventListener("canplay", () => { video.currentTime = 72 })
        adapter.update(state({ positionMs: 0 }))
        expect(send).toHaveBeenCalledWith("seekTo", 72_000)
        expect(video.currentTime).toBe(72)
        adapter.dispose()
    })

    it("waits for native metadata before triggering continuity restoration", () => {
        const adapter = attachAndroidTVPlayer(video, url, vi.fn())
        const canplay = vi.fn()
        video.addEventListener("canplay", canplay)
        adapter.update(state({ durationMs: 0 }))
        expect(canplay).not.toHaveBeenCalled()
        adapter.update(state())
        expect(canplay).toHaveBeenCalledOnce()
        adapter.dispose()
    })

    it("advances a completed episode only once, including the final close snapshot", () => {
        const adapter = attachAndroidTVPlayer(video, url, vi.fn())
        const ended = vi.fn()
        const canplay = vi.fn()
        video.addEventListener("ended", ended)
        video.addEventListener("canplay", canplay)
        adapter.update(state({ positionMs: 100_000, completed: true, paused: true }))
        adapter.update(state({ positionMs: 100_000, completed: true, paused: true }))
        adapter.update(state({ positionMs: 100_000, completed: true, paused: true, active: false, closed: true }))
        expect(ended).toHaveBeenCalledOnce()
        expect(canplay).not.toHaveBeenCalled()
        adapter.dispose()
    })

    it("ignores stale episodes and invalid native positions", () => {
        const adapter = attachAndroidTVPlayer(video, url, vi.fn())
        adapter.update(state())
        const ended = vi.fn()
        video.addEventListener("ended", ended)
        adapter.update(state({ url: "http://127.0.0.1:43211/stream/previous", completed: true }))
        adapter.update(state({ positionMs: Number.NaN, completed: true }))
        adapter.update(state({ positionMs: -1, completed: true }))
        expect(video.currentTime).toBe(40)
        expect(ended).not.toHaveBeenCalled()
        adapter.dispose()
    })

    it("preserves metadata while backgrounded and restores browser methods when closed", async () => {
        const adapter = attachAndroidTVPlayer(video, url, vi.fn())
        adapter.update(state())
        adapter.update(state({ active: false }))
        expect(video.paused).toBe(true)
        expect(video.duration).toBe(100)
        adapter.update(state({ positionMs: 41_000 }))
        expect(video.paused).toBe(false)
        adapter.update(state({ active: false, closed: true, positionMs: 42_000 }))
        await video.play()
        expect(video.play).toBe(play)
        expect(video.pause).toBe(pause)
        expect(video.currentTime).toBe(42)
        expect(video.duration).toBeNaN()
        expect(play).toHaveBeenCalledOnce()
        adapter.dispose()
    })
})
