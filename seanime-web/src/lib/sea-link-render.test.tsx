// @vitest-environment happy-dom
// @vitest-environment-options {"settings":{"navigation":{"disableChildPageNavigation":true}}}
import { createStore, Provider } from "jotai"
import React, { act } from "react"
import { createRoot, type Root } from "react-dom/client"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const environment = vi.hoisted(() => ({ tv: true }))
vi.mock("@/types/constants", () => ({
    get __isAndroidTV__() { return environment.tv },
}))
vi.mock("@/app/(main)/_hooks/use-server-status", () => ({ useIsSimulatedUser: () => false }))
vi.mock("@/lib/entry-preloader", () => ({ preloadMediaEntry: vi.fn() }))
vi.mock("@/lib/navigation", () => ({ useRouter: () => ({}) }))
vi.mock("@/lib/navigation-preload-settings", async () => {
    const { atom } = await import("jotai")
    return {
        __navigationPreloadModeAtom: atom("off"),
        getNavigationPreloadDelay: () => 0,
        getNavigationRoutePreload: () => false,
        getNavigationWarmDelay: () => 0,
        isNavigationPreloadingEnabled: () => false,
        shouldWarmEntryOnIntent: () => false,
        shouldWarmEntryOnViewport: () => false,
    }
})

import {
    __externalPlayerLinkButton_linkAtom,
    ExternalPlayerLinkButton,
} from "@/app/(main)/_features/external-player/external-player-link-button"
import { SeaLink } from "@/components/shared/sea-link"
import { Button } from "@/components/ui/button"

let container: HTMLDivElement
let root: Root
beforeEach(() => {
    environment.tv = true
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true)
    container = document.createElement("div")
    document.body.append(container)
    root = createRoot(container)
})
afterEach(async () => {
    await act(async () => root.unmount())
    container.remove()
    delete window.AndroidTV
    vi.unstubAllGlobals()
})

function installBridge() {
    const openExternalUrl = vi.fn()
    window.AndroidTV = { openExternalUrl } as unknown as NonNullable<Window["AndroidTV"]>
    return openExternalUrl
}

async function clickButton() {
    const button = container.querySelector("button")
    expect(button).not.toBeNull()
    const event = new MouseEvent("click", { bubbles: true, cancelable: true, button: 0 })
    await act(async () => { button!.dispatchEvent(event) })
    return event
}

describe("rendered SeaLink external handoff", () => {
    it("uses an anchor and invokes its callback before handing a nested button click to Android", async () => {
        const calls: string[] = []
        const openExternalUrl = installBridge().mockImplementation(() => calls.push("open"))
        const onClick = vi.fn(() => calls.push("click"))
        await act(async () => {
            root.render(<SeaLink href="  vlc://play/movie  " target="_blank" onClick={onClick}>
                <Button>Open media</Button>
            </SeaLink>)
        })
        expect(container.querySelector("a")?.getAttribute("target")).toBe("_blank")

        const event = await clickButton()

        expect(event.defaultPrevented).toBe(true)
        expect(calls).toEqual(["click", "open"])
        expect(onClick).toHaveBeenCalledOnce()
        expect(openExternalUrl).toHaveBeenCalledExactlyOnceWith("vlc://play/movie")
    })

    it("respects a rendered anchor callback that cancels the click", async () => {
        const openExternalUrl = installBridge()
        await act(async () => {
            root.render(<SeaLink href="https://example.com" target="_blank" onClick={event => event.preventDefault()}>
                <Button>Open website</Button>
            </SeaLink>)
        })

        expect((await clickButton()).defaultPrevented).toBe(true)
        expect(openExternalUrl).not.toHaveBeenCalled()
    })

    it.each(["non-TV browser", "missing Android bridge"])("keeps browser navigation in control for %s", async mode => {
        const openExternalUrl = installBridge()
        if (mode === "non-TV browser") environment.tv = false
        else delete window.AndroidTV
        const onClick = vi.fn()
        await act(async () => {
            root.render(<SeaLink href="vlc://play/movie" target="_blank" onClick={onClick}>
                <Button>Open media</Button>
            </SeaLink>)
        })

        expect((await clickButton()).defaultPrevented).toBe(false)
        expect(onClick).toHaveBeenCalledOnce()
        expect(openExternalUrl).not.toHaveBeenCalled()
    })

    it("clears the real external-player button while dispatching its URL once", async () => {
        const openExternalUrl = installBridge()
        const store = createStore()
        store.set(__externalPlayerLinkButton_linkAtom, "mpv://http://127.0.0.1:43211/stream/movie")
        await act(async () => { root.render(<Provider store={store}><ExternalPlayerLinkButton /></Provider>) })

        expect(container.querySelector("a")?.getAttribute("target")).toBe("_blank")
        expect((await clickButton()).defaultPrevented).toBe(true)
        expect(openExternalUrl).toHaveBeenCalledExactlyOnceWith("mpv://http://127.0.0.1:43211/stream/movie")
        expect(store.get(__externalPlayerLinkButton_linkAtom)).toBeNull()
        expect(container.querySelector("button")).toBeNull()
    })
})
