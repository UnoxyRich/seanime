import type { MKVParser_TrackInfo } from "@/api/generated/types"
import { describe, expect, it } from "vitest"
import { selectPlaybackAudioTracks } from "./audio-track-source"

const mkv = [{ number: 2, name: "Japanese" }, { number: 5, name: "English" }] as MKVParser_TrackInfo[]
const hls = [{ id: 0, name: "Japanese" }, { id: 1, name: "English" }]

describe("audio menu track identity after web conversion", () => {
    it("selects HLS IDs while retaining the original MKV metadata", () => {
        const selected = selectPlaybackAudioTracks(mkv, hls, true)
        expect(selected.isHls).toBe(true)
        expect(selected.audioTracks).toBe(hls)
        expect(mkv.map(track => track.number)).toEqual([2, 5])
    })
    it("uses original MKV numbers when the native transport is active", () => {
        const selected = selectPlaybackAudioTracks(mkv, hls, false)
        expect(selected.isHls).toBe(false)
        expect(selected.audioTracks).toBe(mkv)
    })
})
