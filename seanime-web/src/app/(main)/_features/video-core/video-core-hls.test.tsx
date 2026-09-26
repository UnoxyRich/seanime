// @vitest-environment happy-dom

import { atom, Provider } from "jotai"
import React, { act } from "react"
import { createRoot } from "react-dom/client"
import { afterEach, describe, expect, it, vi } from "vitest"
import { useVideoCoreHls } from "./video-core-hls"

const mock = vi.hoisted(() => {
    const events = { MEDIA_ATTACHED: "attached", MEDIA_DETACHED: "detached", MANIFEST_PARSED: "manifest", LEVEL_SWITCHED: "level", AUDIO_TRACK_SWITCHED: "audio", FRAG_CHANGED: "fragment", ERROR: "error" }
    class Hls {
        static isSupported = () => true
        static ErrorDetails = { BUFFER_STALLED_ERROR: "stalled" }
        static ErrorTypes = { MEDIA_ERROR: "media-error" }
        static instances: Hls[] = []
        handlers = new Map<string, Function>()
        media: HTMLVideoElement | null = null
        currentLevel = -1
        audioTrack = -1
        audioTracks = []
        loadSource = vi.fn()
        stopLoad = vi.fn()
        startLoad = vi.fn()
        destroy = vi.fn()
        recoverMediaError = vi.fn()
        swapAudioCodec = vi.fn()
        constructor() { Hls.instances.push(this) }
        on(event: string, handler: Function) { this.handlers.set(event, handler) }
        emit(event: string, data: unknown = {}) { this.handlers.get(event)?.(event, data) }
        attachMedia(video: HTMLVideoElement) { this.media = video }
    }
    return { Hls, events }
})
vi.mock("hls.js", () => ({ default: mock.Hls, Events: mock.events }))
vi.mock("@/app/(main)/_features/video-core/video-core", () => ({ vc_audioManager: atom(null) }))
vi.mock("@/app/(main)/_features/video-core/video-core.atoms", () => ({ vc_autoPlayVideoAtom: atom(true) }))
vi.mock("@/types/constants", () => ({ __isAndroidTV__: true }))
vi.mock("@/lib/helpers/debug", () => ({ logger: () => ({ info: vi.fn(), warning: vi.fn(), error: vi.fn(), success: vi.fn() }) }))

const url = "http://127.0.0.1:43211/episode.m3u8"
let cleanup: (() => Promise<void>) | undefined

afterEach(async () => {
    await cleanup?.()
    vi.unstubAllGlobals()
    delete window.AndroidTV
})

async function mount(nativeActive = false) {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true)
    window.AndroidTV = { nativePlayerActive: () => nativeActive } as NonNullable<Window["AndroidTV"]>
    const video = document.createElement("video")
    const play = vi.fn(async () => {})
    Object.defineProperty(video, "play", { value: play })
    const onFatalError = vi.fn()
    function Player() {
        useVideoCoreHls({ videoElement: video, streamUrl: url, streamType: "hls", onFatalError })
        return null
    }
    const host = document.createElement("div")
    const root = createRoot(host)
    cleanup = async () => { await act(async () => root.unmount()) }
    await act(async () => { root.render(<Provider><Player /></Provider>) })
    const hls = mock.Hls.instances.at(-1)!
    await act(async () => hls.emit(mock.events.MEDIA_ATTACHED))
    return { hls, play, onFatalError }
}

async function nativeEvent(closed: boolean) {
    await act(async () => {
        window.dispatchEvent(new CustomEvent("seanime-androidtv-player-progress", {
            detail: { url, positionMs: 45_000, active: !closed, closed },
        }))
    })
}

describe("HLS during Android native playback", () => {
    it("suspends browser loading and ignores late autoplay and recovery callbacks", async () => {
        const { hls, play, onFatalError } = await mount()
        expect(hls.loadSource).toHaveBeenCalledWith(url)
        await nativeEvent(false)
        expect(hls.stopLoad).toHaveBeenCalledOnce()
        await act(async () => {
            hls.emit(mock.events.MANIFEST_PARSED, { levels: [], audioTracks: [] })
            hls.emit(mock.events.ERROR, { fatal: true, type: "media-error" })
        })
        expect(play).not.toHaveBeenCalled()
        expect(hls.recoverMediaError).not.toHaveBeenCalled()
        expect(onFatalError).not.toHaveBeenCalled()
        await nativeEvent(true)
        expect(hls.startLoad).toHaveBeenCalledWith(45)
        await act(async () => hls.emit(mock.events.MANIFEST_PARSED, { levels: [], audioTracks: [] }))
        expect(play).not.toHaveBeenCalled()
    })

    it("defers a new episode's browser source while the native player owns playback", async () => {
        const { hls, play } = await mount(true)
        expect(hls.loadSource).not.toHaveBeenCalled()
        await nativeEvent(true)
        expect(hls.loadSource).toHaveBeenCalledWith(url)
        await act(async () => hls.emit(mock.events.MANIFEST_PARSED, { levels: [], audioTracks: [] }))
        expect(play).not.toHaveBeenCalled()
    })
})
