import { describe, expect, it } from "vitest"
import { playlistStreamPlaybackMethod, sharedPlayerSurface, streamPlaybackType, watchPartyUsesIntegratedPlayer } from "./playback-platform"

const tv = { androidTV: true, electron: false, electronPlaybackMethod: "default", externalPlayerSelected: false, externalPlayerLink: "" }

describe("shared player platform routing", () => {
    it("uses VideoCore on TV even with imported mpv preferences", () => {
        expect(sharedPlayerSurface(true, false, true)).toBe("videocore")
        expect(sharedPlayerSurface(true, false, false)).toBe("videocore")
        expect(sharedPlayerSurface(false, true, true)).toBe("mpvcore")
        expect(sharedPlayerSurface(false, true, false)).toBe("videocore")
        expect(sharedPlayerSurface(false, false, false)).toBeNull()
    })

    it("routes default TV torrent and debrid requests to the listening player", () => {
        expect(streamPlaybackType(tv)).toBe("nativeplayer")
        expect(streamPlaybackType({ ...tv, electronPlaybackMethod: "nativePlayer" })).toBe("nativeplayer")
    })

    it("uses the shared Android player for playlist streams and preserves configured external links", () => {
        expect(playlistStreamPlaybackMethod(true, false, "default", "default", "")).toBe("nativePlayer")
        expect(playlistStreamPlaybackMethod(true, false, "nativePlayer", "externalPlayerLink", "")).toBe("nativePlayer")
        expect(playlistStreamPlaybackMethod(true, false, "default", "externalPlayerLink", "vlc://play")).toBe("externalPlayerLink")
        expect(playlistStreamPlaybackMethod(false, true, "nativePlayer", "default", "")).toBe("nativePlayer")
        expect(playlistStreamPlaybackMethod(false, false, "nativePlayer", "externalPlayerLink", "")).toBe("externalPlayerLink")
    })

    it("advertises Android TV as an integrated watch-party player regardless of imported desktop settings", () => {
        expect(watchPartyUsesIntegratedPlayer(true, false, "default")).toBe(true)
        expect(watchPartyUsesIntegratedPlayer(true, false, "nativePlayer")).toBe(true)
        expect(watchPartyUsesIntegratedPlayer(false, true, "nativePlayer")).toBe(true)
        expect(watchPartyUsesIntegratedPlayer(false, true, "default")).toBe(false)
        expect(watchPartyUsesIntegratedPlayer(false, false, "nativePlayer")).toBe(false)
    })

    it("honors the TV external app choice and requires its configured link", () => {
        for (const force of ["externalPlayerLink", "playbackmanager"] as const) {
            expect(streamPlaybackType({ ...tv, force })).toBeNull()
            expect(streamPlaybackType({ ...tv, force, externalPlayerLink: "vlc://" })).toBe("externalPlayerLink")
        }
        expect(streamPlaybackType({ ...tv, externalPlayerSelected: true })).toBeNull()
        expect(streamPlaybackType({ ...tv, externalPlayerSelected: true, externalPlayerLink: "vlc://" })).toBe("externalPlayerLink")
        expect(streamPlaybackType({ ...tv, externalPlayerSelected: true, force: "nativeplayer" })).toBe("nativeplayer")
    })

    it("retains the previous non-Android selection precedence", () => {
        for (const electron of [true, false]) {
            for (const electronPlaybackMethod of ["default", "nativePlayer"]) {
                for (const force of [undefined, "nativeplayer", "playbackmanager", "externalPlayerLink"] as const) {
                    for (const externalPlayerSelected of [true, false]) {
                        for (const externalPlayerLink of ["", "vlc://", " "]) {
                            const expected = ((!force && electron && electronPlaybackMethod === "nativePlayer") || force === "nativeplayer")
                                ? "nativeplayer"
                                : (externalPlayerLink.length && ((!force && externalPlayerSelected) || force === "externalPlayerLink"))
                                    ? "externalPlayerLink" : "default"
                            expect(streamPlaybackType({ androidTV: false, electron, electronPlaybackMethod, force, externalPlayerSelected, externalPlayerLink }))
                                .toBe(expected)
                        }
                    }
                }
            }
        }
    })
})
