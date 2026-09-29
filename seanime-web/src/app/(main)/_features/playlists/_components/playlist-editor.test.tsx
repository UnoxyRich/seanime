// @vitest-environment happy-dom

import type { Anime_LibraryCollection, Anime_LibraryCollectionEntry, Anime_PlaylistEpisode } from "@/api/generated/types"
import { Provider } from "jotai"
import React, { act } from "react"
import { createRoot, type Root } from "react-dom/client"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

const environment = vi.hoisted(() => ({ tv: true, availableEpisodes: [] as unknown[] }))
vi.mock("@/types/constants", () => ({ get __isAndroidTV__() { return environment.tv } }))
vi.mock("@/api/hooks/playlist.hooks", () => ({
    useGetPlaylistEpisodes: () => ({ data: environment.availableEpisodes, isLoading: false }),
}))
vi.mock("@/app/(main)/_hooks/use-server-status", () => ({
    useHasDebridService: () => ({ hasDebridService: true }),
    useHasTorrentStreaming: () => ({ hasTorrentStreaming: true }),
    useHasOnlineStreaming: () => ({ hasOnlineStreaming: true }),
}))
vi.mock("@/lib/server/assets", () => ({ getImageUrl: (url: string) => url }))
vi.mock("@/components/shared/sea-image", () => ({ SeaImage: () => null }))
vi.mock("@/components/ui/modal", () => ({
    Modal: ({ open, trigger, children }: React.PropsWithChildren<{ open?: boolean, trigger?: React.ReactElement }>) => {
        const [uncontrolledOpen, setOpen] = React.useState(false)
        return <>
            {trigger && React.cloneElement(trigger as React.ReactElement<React.ButtonHTMLAttributes<HTMLButtonElement>>, {
                onClick: () => setOpen(true),
            })}
            {(open ?? uncontrolledOpen) && <div role="dialog">{children}</div>}
        </>
    },
}))
vi.mock("@/components/ui/select", () => ({ Select: () => null }))
vi.mock("@/components/ui/text-input", () => ({ TextInput: () => null }))

import { PlaylistEditor, PlaylistMediaEntry } from "./playlist-editor"

const entry = {
    mediaId: 1,
    media: { title: { userPreferred: "Test anime" }, format: "TV" },
} as Anime_LibraryCollectionEntry
const library = { lists: [{ type: "CURRENT", entries: [entry] }] } as Anime_LibraryCollection
function episode(number: number): Anime_PlaylistEpisode {
    return {
        episode: {
            baseAnime: { id: 1, title: { userPreferred: "Test anime" }, format: "TV" },
            episodeNumber: number,
            progressNumber: number,
            aniDBEpisode: String(number),
        },
        watchType: "torrent",
    } as Anime_PlaylistEpisode
}

let container: HTMLDivElement
let root: Root
let latestEpisodes: Anime_PlaylistEpisode[]
beforeEach(() => {
    Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true })
    environment.tv = true
    environment.availableEpisodes = [episode(1), episode(2)]
    container = document.createElement("div")
    document.body.append(container)
    root = createRoot(container)
})
afterEach(async () => {
    await act(async () => root.unmount())
    container.remove()
})

async function render(initialEpisodes: Anime_PlaylistEpisode[] = []) {
    function Editor() {
        const [episodes, setEpisodes] = React.useState(initialEpisodes)
        latestEpisodes = episodes
        return <>
            <PlaylistEditor episodes={episodes} setEpisodes={setEpisodes} libraryCollection={library} />
            <PlaylistMediaEntry entry={entry} episodes={episodes} setEpisodes={setEpisodes} />
        </>
    }
    await act(async () => root.render(<Provider><Editor /></Provider>))
}

function button(text: string, scope: ParentNode = container): HTMLButtonElement {
    const found = Array.from(scope.querySelectorAll<HTMLButtonElement>("button")).find(item => item.textContent === text)
    expect(found, `Expected a button named ${text}`).toBeDefined()
    return found!
}

describe("playlist controls on Android TV", () => {
    it("lets native focus reach anime and episode choices and toggles the selected episode", async () => {
        await render()
        await act(async () => button("Add episodes").click())
        const anime = container.querySelector<HTMLButtonElement>('button[aria-label="Select Test anime"]')!
        expect(anime.tabIndex).toBe(0)
        anime.focus()
        expect(document.activeElement).toBe(anime)
        await act(async () => anime.click())
        const chooseEpisode = button("Episode 1")
        expect(chooseEpisode.getAttribute("aria-pressed")).toBe("false")
        chooseEpisode.focus()
        expect(document.activeElement).toBe(chooseEpisode)
        await act(async () => chooseEpisode.click())
        expect(latestEpisodes.map(item => item.episode?.episodeNumber)).toEqual([1])
        expect(chooseEpisode.getAttribute("aria-pressed")).toBe("true")
        await act(async () => chooseEpisode.click())
        expect(latestEpisodes).toEqual([])
    })

    it("offers every streaming source as a focusable toggle", async () => {
        await render([episode(1)])
        const source = button("Online streaming")
        source.focus()
        expect(document.activeElement).toBe(source)
        expect(button("Torrent streaming").getAttribute("aria-pressed")).toBe("true")
        await act(async () => source.click())
        expect(latestEpisodes[0].watchType).toBe("online")
        expect(source.getAttribute("aria-pressed")).toBe("true")
        await act(async () => source.click())
        expect(latestEpisodes[0].watchType).toBe("")
    })

    it("reorders episodes with remote buttons while keeping boundary actions disabled", async () => {
        await render([episode(1), episode(2), episode(3)])
        let rows = container.querySelectorAll("li")
        expect(button("Move up", rows[0]).disabled).toBe(true)
        expect(button("Move down", rows[2]).disabled).toBe(true)
        expect(rows[1].querySelector('[aria-roledescription="sortable"]')).toBeNull()
        const moveUp = button("Move up", rows[1])
        moveUp.focus()
        await act(async () => moveUp.click())
        expect(latestEpisodes.map(item => item.episode?.episodeNumber)).toEqual([2, 1, 3])
        rows = container.querySelectorAll("li")
        await act(async () => button("Move down", rows[0]).click())
        expect(latestEpisodes.map(item => item.episode?.episodeNumber)).toEqual([1, 2, 3])
    })

    it("permits adding episodes until the existing twenty-episode limit", async () => {
        await render(Array.from({ length: 12 }, (_, index) => episode(index + 1)))
        expect(button("Add episodes").disabled).toBe(false)
    })

    it("keeps desktop dragging and does not add TV ordering buttons", async () => {
        environment.tv = false
        await render([episode(1), episode(2)])
        expect(container.querySelector('[aria-roledescription="sortable"]')).not.toBeNull()
        expect(Array.from(container.querySelectorAll("button")).some(item => item.textContent === "Move up")).toBe(false)
    })
})
