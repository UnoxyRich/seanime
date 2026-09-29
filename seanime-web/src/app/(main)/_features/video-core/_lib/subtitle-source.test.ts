// @vitest-environment happy-dom
import type { VideoCore_VideoPlaybackInfo } from "@/app/(main)/_features/video-core/video-core.atoms"
import { describe, expect, it } from "vitest"
import { canPreserveSubtitleManagers, subtitleSourceIdentity } from "./subtitle-source"

describe("subtitle state across browser transport conversion", () => {
    it("preserves runtime tracks and selected/off state by retaining the same manager", () => {
        const video = document.createElement("video")
        const info = { id: "original", mkvMetadata: { subtitleTracks: [] }, subtitleTracks: [], libassFonts: ["font.ttf"] } as unknown as VideoCore_VideoPlaybackInfo
        const original = subtitleSourceIdentity(info, video, "https://source/episode.mkv")
        const converted = subtitleSourceIdentity({ ...info }, video, "https://source/episode.mkv")
        expect(canPreserveSubtitleManagers(original, converted, true)).toBe(true)
        expect(canPreserveSubtitleManagers(original, converted, false)).toBe(false)
    })
    it("rebuilds when the original source, video element, tracks or fonts change", () => {
        const video = document.createElement("video")
        const info = { id: "original", subtitleTracks: [], libassFonts: ["font.ttf"] } as unknown as VideoCore_VideoPlaybackInfo
        const original = subtitleSourceIdentity(info, video, "https://source/original")
        for (const next of [
            subtitleSourceIdentity({ ...info, id: "next" }, video, "https://source/original"),
            subtitleSourceIdentity(info, video, "https://source/next"),
            subtitleSourceIdentity(info, document.createElement("video"), "https://source/original"),
            subtitleSourceIdentity({ ...info, subtitleTracks: [] }, video, "https://source/original"),
            subtitleSourceIdentity({ ...info, libassFonts: ["new.ttf"] }, video, "https://source/original"),
        ]) expect(canPreserveSubtitleManagers(original, next, true)).toBe(false)
    })
})
