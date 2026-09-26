// @vitest-environment happy-dom

import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query"
import React, { act } from "react"
import { createRoot, Root } from "react-dom/client"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import Page from "./page"

const fixtures = vi.hoisted(() => ({
    androidTV: true,
    settings: { transcodeEnabled: false, disableAutoSwitchToDirectPlay: true },
    entry: { media: { id: 1 }, episodes: [] },
    container: { streamType: "direct", streamUrl: "/stream?file=episode", mediaInfo: { mimeCodec: "video/mp4" } },
    requestContainer: vi.fn(),
    shutdown: vi.fn(),
    getToken: vi.fn(async () => ""),
    codecSupported: () => true,
    unmounted: vi.fn(),
}))

vi.mock("@/types/constants", () => ({ get __isAndroidTV__() { return fixtures.androidTV } }))
vi.mock("@/api/client/server-url", () => ({ getServerBaseUrl: () => "http://127.0.0.1:43211" }))
vi.mock("@/api/hooks/anime_entries.hooks", () => ({ useGetAnimeEntry: () => ({ data: fixtures.entry, isLoading: false }) }))
vi.mock("@/api/hooks/mediastream.hooks", () => ({
    useGetMediastreamSettings: () => ({ data: fixtures.settings, isFetching: false }),
    useMediastreamShutdownTranscodeStream: () => ({ mutate: fixtures.shutdown }),
    useRequestMediastreamMediaContainer: (variables: { path: string; streamType: string }, enabled: boolean) => useQuery({
        queryKey: ["container", variables.path, variables.streamType],
        queryFn: fixtures.requestContainer,
        enabled,
        retry: false,
    }),
}))
vi.mock("@/app/(main)/_hooks/use-server-status", () => ({
    useServerStatus: () => ({ mediastreamSettings: fixtures.settings }),
    useServerHMACAuth: () => ({ getHMACTokenQueryParam: fixtures.getToken }),
}))
vi.mock("@/app/(main)/mediastream/_lib/mediastream.atoms", () => ({
    useMediastreamCurrentFile: () => ({ filePath: "/episode.mp4", setFilePath: vi.fn() }),
}))
vi.mock("@/app/(main)/_features/video-core/_lib/hooks", () => ({
    useIsCodecSupported: () => ({ isCodecSupported: fixtures.codecSupported }),
}))
vi.mock("@/app/(main)/_features/video-core/video-core", () => ({
    VideoCoreProvider: ({ children }: React.PropsWithChildren) => children,
    // Exercise the real page's error callbacks and state without mounting a
    // decoder. A stable video node also verifies that native event listeners
    // owned by VideoCore would survive the parent's error transition.
    VideoCore: ({ state, onError, onHlsFatalError }: any) => {
        React.useEffect(() => () => { fixtures.unmounted() }, [])
        return <div data-testid="player">
            <video src={state.playbackInfo?.streamUrl} onError={onError} />
            <button onClick={() => onHlsFatalError(new Error("HLS failed"))}>Fail HLS</button>
            <p data-testid="player-error">{state.playbackError}</p>
        </div>
    },
}))
vi.mock("@/app/(main)/_features/video-core/video-core-inline-helpers", () => ({
    VideoCoreInlineLayout: ({ mediaPlayer }: any) => mediaPlayer,
    VideoCoreInlineHelpers: () => null,
    VideoCoreInlineHelperUpdateProgressButton: () => null,
}))
vi.mock("@/app/(main)/_features/video-core/video-core-atoms.ts", () => ({ vc_isFullscreen: "fullscreen" }))
vi.mock("@/app/websocket-provider", () => ({ clientIdAtom: "client" }))
vi.mock("@/app/(main)/_hooks/handle-websockets", () => ({ useWebsocketMessageListener: () => {} }))
vi.mock("jotai", () => ({
    useAtomValue: (atom: string) => atom === "fullscreen" ? false : "test-client",
    useAtom: () => React.useState("list"),
}))
vi.mock("jotai/utils", () => ({ atomWithStorage: () => "episode-view" }))
vi.mock("@/lib/navigation", () => ({
    usePathname: () => "/mediastream",
    useSearchParams: () => new URLSearchParams("id=1"),
    useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
}))
vi.mock("@/lib/helpers/debug", () => ({
    logger: () => ({ info: vi.fn(), warning: vi.fn(), error: vi.fn() }),
    useLatestFunction: (fn: unknown) => fn,
}))
vi.mock("sonner", () => ({ toast: { error: vi.fn(), warning: vi.fn() } }))
vi.mock("@/components/shared/page-wrapper", () => ({ PageWrapper: ({ children }: React.PropsWithChildren) => children }))
vi.mock("@/components/shared/luffy-error", () => ({ LuffyError: ({ children }: React.PropsWithChildren) => <p>{children}</p> }))
vi.mock("@/app/(main)/_features/anime/_components/episode-grid-item", () => ({ EpisodeGridItem: () => null }))
vi.mock("@/app/(main)/_features/media/_components/media-episode-info-modal", () => ({ MediaEpisodeInfoModal: () => null }))
vi.mock("@/app/(main)/_features/video-core/_components/episode-pills-grid", () => ({ EpisodePillsGrid: () => null }))
vi.mock("@/components/ui/alert", () => ({ Alert: () => null }))
vi.mock("@/components/ui/button", () => ({ Button: () => null, IconButton: () => null }))
vi.mock("@/components/ui/modal", () => ({ Modal: () => null }))
vi.mock("@/components/ui/separator", () => ({ Separator: () => null }))
vi.mock("@/components/ui/skeleton", () => ({ Skeleton: () => null }))

let host: HTMLDivElement
let root: Root
let queryClient: QueryClient

async function until(assertion: () => void) {
    await vi.waitFor(async () => {
        await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) })
        assertion()
    })
}

async function renderPage() {
    await act(async () => {
        root.render(<QueryClientProvider client={queryClient}><Page /></QueryClientProvider>)
    })
}

async function click(label: string) {
    const button = Array.from(host.querySelectorAll("button")).find(el => el.textContent === label)
    expect(button, `Button ${label} should be available`).toBeDefined()
    await act(async () => { button!.click() })
}

beforeEach(() => {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true)
    vi.clearAllMocks()
    fixtures.androidTV = true
    fixtures.settings.transcodeEnabled = false
    fixtures.container.streamType = "direct"
    fixtures.getToken.mockResolvedValue("")
    fixtures.requestContainer.mockReset().mockImplementation(async () => ({ ...fixtures.container }))
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    host = document.createElement("div")
    document.body.append(host)
    root = createRoot(host)
})

afterEach(async () => {
    await act(async () => { root.unmount() })
    queryClient.clear()
    host.remove()
    vi.unstubAllGlobals()
})

describe("mediastream playback recovery", () => {
    it.each(["video", "hls"])("retains the TV player and source after a %s error", async errorType => {
        fixtures.settings.transcodeEnabled = true
        fixtures.container.streamType = "transcode"
        await renderPage()
        await until(() => expect(host.querySelector("video")?.src).toContain("/stream?file=episode"))
        const video = host.querySelector("video")!
        if (errorType === "video") {
            await act(async () => { video.dispatchEvent(new Event("error")) })
        } else {
            await click("Fail HLS")
        }
        expect(host.querySelector("video")).toBe(video)
        expect(video.src).toContain("/stream?file=episode")
        expect(host.querySelector('[data-testid="player-error"]')?.textContent).toContain("Try the TV player")
        expect(fixtures.unmounted).not.toHaveBeenCalled()
        expect(fixtures.shutdown).not.toHaveBeenCalled()
    })

    it("retries desktop playback when the refetched container has the same URL", async () => {
        fixtures.androidTV = false
        await renderPage()
        await until(() => expect(host.querySelector("video")?.src).toContain("/stream?file=episode"))
        const video = host.querySelector("video")!
        await act(async () => { video.dispatchEvent(new Event("error")) })
        expect(host.querySelector("video")).toBeNull()
        expect(host.textContent).toContain("Playback error triggered")
        await click("Retry")
        await until(() => expect(host.querySelector("video")?.src).toBe(video.src))
        expect(fixtures.requestContainer).toHaveBeenCalledTimes(2)
        expect(host.querySelector('[data-testid="player-error"]')?.textContent).toBe("")
    })

    it("keeps a failed retry actionable and stops the desktop transcode", async () => {
        fixtures.androidTV = false
        fixtures.settings.transcodeEnabled = true
        fixtures.container.streamType = "transcode"
        await renderPage()
        await until(() => expect(host.querySelector("video")?.src).toContain("/stream?file=episode"))
        await click("Fail HLS")
        expect(fixtures.shutdown).toHaveBeenCalledOnce()
        fixtures.requestContainer.mockRejectedValueOnce(new Error("Storage unavailable"))
        await click("Retry")
        await until(() => {
            expect(host.querySelector("video")).toBeNull()
            expect(host.querySelector("button")?.disabled).toBe(false)
        })
        await click("Retry")
        await until(() => expect(host.querySelector("video")?.src).toContain("/stream?file=episode"))
    })

    it("retries an initial container failure before mounting the TV player", async () => {
        fixtures.requestContainer.mockRejectedValueOnce(new Error("Storage unavailable"))
        await renderPage()
        await until(() => expect(host.textContent).toContain("Could not load media container"))
        expect(host.querySelector("video")).toBeNull()
        await click("Retry")
        await until(() => expect(host.querySelector("video")?.src).toContain("/stream?file=episode"))
    })

    it("adds a transcode token that resolves after the initial container", async () => {
        fixtures.settings.transcodeEnabled = true
        fixtures.container.streamType = "transcode"
        let resolveToken!: (token: string) => void
        const delayedToken = new Promise<string>(resolve => { resolveToken = resolve })
        fixtures.getToken.mockImplementation((...args: any[]) => args[0].endsWith("/transcode") ? delayedToken : Promise.resolve(""))
        await renderPage()
        await until(() => expect(host.querySelector("video")?.src).toContain("/stream?file=episode"))
        await act(async () => { resolveToken("&token=transcode-proof") })
        expect(host.querySelector("video")?.src).toContain("token=transcode-proof")
    })
})
