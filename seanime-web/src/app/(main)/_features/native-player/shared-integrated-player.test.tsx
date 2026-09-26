// @vitest-environment happy-dom
import React, { act } from "react"
import { createRoot, type Root } from "react-dom/client"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const environment = vi.hoisted(() => ({ tv: true, electron: false, mpv: false }))
vi.mock("@/types/constants", () => ({
    get __isAndroidTV__() { return environment.tv },
    get __isElectronDesktop__() { return environment.electron },
}))
vi.mock("@/app/(main)/_hooks/use-server-status", () => ({
    useServerStatus: () => ({ settings: { mediaPlayer: { mpvPrismEnabled: environment.mpv } } }),
}))
vi.mock("./native-player-lazy-wrapper", () => ({ default: () => <div data-player="videocore" /> }))
vi.mock("@/app/(main)/_features/mpv-core/mpv-core-lazy-wrapper", () => ({ default: () => <div data-player="mpvcore" /> }))

import { SharedIntegratedPlayer } from "./shared-integrated-player"

let container: HTMLDivElement
let root: Root
beforeEach(() => {
    Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
    container = document.createElement("div")
    document.body.append(container)
    root = createRoot(container)
})
afterEach(async () => {
    await act(async () => root.unmount())
    container.remove()
})

describe("integrated player listener mounting", () => {
    it.each([
        [true, false, false, "videocore"],
        [true, false, true, "videocore"],
        [false, true, false, "videocore"],
        [false, true, true, "mpvcore"],
        [false, false, true, null],
    ] as const)("selects TV=%s Electron=%s mpv=%s", async (tv, electron, mpv, expected) => {
        Object.assign(environment, { tv, electron, mpv })
        await act(async () => {
            root.render(<SharedIntegratedPlayer />)
            await vi.dynamicImportSettled()
        })
        expect(container.querySelector("[data-player]")?.getAttribute("data-player") ?? null).toBe(expected)
    })
})
