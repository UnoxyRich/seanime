// @vitest-environment happy-dom

import { keyboardClickable } from "@/lib/helpers/keyboard-clickable"
import { StaticTabs } from "@/components/ui/tabs/static-tabs"
import React, { act } from "react"
import { createRoot, type Root } from "react-dom/client"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { FileTreeMultiSelector, FileTreeSelector } from "./file-tree-selector"

vi.mock("@/components/shared/sea-link", () => ({
    SeaLink: ({ children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => <a {...props}>{children}</a>,
}))

const previews = [
    { index: 1, path: "/show/episode-1.mkv", displayTitle: "Episode one", displayPath: "/show/episode-1.mkv", isLikely: true },
    { index: 2, path: "/show/episode-2.mkv", displayTitle: "Episode two", displayPath: "/show/episode-2.mkv", isLikely: false },
]
let host: HTMLDivElement
let root: Root
beforeEach(() => {
    Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
    host = document.createElement("div")
    document.body.append(host)
    root = createRoot(host)
})
afterEach(async () => {
    await act(async () => root.unmount())
    host.remove()
})

function control(text: string) {
    return Array.from(host.querySelectorAll<HTMLElement>('[role="button"]')).find(element => element.textContent?.includes(text))!
}
async function key(element: HTMLElement, value: string, repeat = false) {
    element.focus()
    const event = new KeyboardEvent("keydown", { key: value, bubbles: true, cancelable: true, repeat })
    await act(async () => { element.dispatchEvent(event) })
    return event
}

describe("TV keyboard access to shared controls", () => {
    it("selects a torrent or debrid file with Enter and preserves the likely-match ref", async () => {
        const select = vi.fn()
        const likelyMatchRef = React.createRef<HTMLDivElement>()
        await act(async () => root.render(<FileTreeSelector
            filePreviews={previews}
            selectedValue=""
            onFileSelect={select}
            getFileValue={preview => preview.path}
            hasLikelyMatch
            hasOneLikelyMatch
            likelyMatchRef={likelyMatchRef}
        />))
        const item = control("Episode one")
        expect(item).toBe(likelyMatchRef.current)
        expect(item.tabIndex).toBe(0)
        const event = await key(item, "Enter")
        expect(document.activeElement).toBe(item)
        expect(event.defaultPrevented).toBe(true)
        expect(select).toHaveBeenCalledExactlyOnceWith(previews[0].path)
    })

    it("selects download files with Space and lets the expand button work without selecting the directory", async () => {
        let selected: number[] = []
        function Picker() {
            const [indices, setIndices] = React.useState<number[]>([])
            selected = indices
            return <FileTreeMultiSelector filePreviews={previews} selectedIndices={indices} onSelectionChange={setIndices} getFileValue={preview => preview.index} />
        }
        await act(async () => root.render(<Picker />))
        const item = control("Episode one")
        await key(item, " ")
        expect(selected).toEqual([1])
        expect(item.getAttribute("aria-pressed")).toBe("true")
        const collapse = host.querySelector<HTMLButtonElement>('button[aria-label="Collapse show"]')!
        await act(async () => collapse.click())
        expect(selected).toEqual([1])
        expect(host.querySelector('button[aria-label="Expand show"]')).not.toBeNull()
        expect(control("Episode one")).toBeUndefined()
    })

    it("switches installed and marketplace extension tabs through native buttons", async () => {
        const change = vi.fn()
        await act(async () => root.render(<StaticTabs items={[
            { name: "Installed", isCurrent: true, onClick: () => change("installed") },
            { name: "Marketplace", isCurrent: false, onClick: () => change("marketplace") },
        ]} />))
        const marketplace = Array.from(host.querySelectorAll("button")).find(button => button.textContent === "Marketplace")!
        marketplace.focus()
        expect(document.activeElement).toBe(marketplace)
        expect(marketplace.type).toBe("button")
        await act(async () => marketplace.click())
        expect(change).toHaveBeenCalledExactlyOnceWith("marketplace")
    })

    it("does not activate a parent when a child handles Enter, or repeat a held remote press", async () => {
        const parent = vi.fn()
        const child = vi.fn()
        await act(async () => root.render(<div {...keyboardClickable()} onClick={parent}>
            <span {...keyboardClickable()} onClick={event => { event.stopPropagation(); child() }}>Child</span>
        </div>))
        const element = host.querySelector("span")!
        await key(element, "Enter")
        await key(element, "Enter", true)
        expect(child).toHaveBeenCalledOnce()
        expect(parent).not.toHaveBeenCalled()
    })

    it("does not activate a disabled custom control", async () => {
        const activate = vi.fn()
        await act(async () => root.render(<div {...keyboardClickable()} aria-disabled="true" onClick={activate}>Disabled</div>))
        await key(host.firstElementChild as HTMLElement, "Enter")
        expect(activate).not.toHaveBeenCalled()
    })
})
