import { useIsSimulatedUser } from "@/app/(main)/_hooks/use-server-status"
import { requestAndroidTVBridgeToken } from "@/lib/android-tv-bootstrap"
import { ClientProviders, queryClient, store } from "@/app/client-providers"
import "./app/globals.css"
import { __navigationPreloadModeAtom, getActualNavigationPreloadMode, NavigationPreloadMode } from "@/lib/navigation-preload-settings"
import { __isAndroidTV__, __isElectronDesktop__ } from "@/types/constants"
import { createRouter, RouterProvider } from "@tanstack/react-router"
import { useAtomValue } from "jotai/react"
import React from "react"
import ReactDOM from "react-dom/client"
import { ErrorBoundary, FallbackProps } from "react-error-boundary"
import { LuffyError } from "./components/shared/luffy-error"
import { Button } from "./components/ui/button"
import { setupDenshiScrollRestoration } from "./lib/router/denshi-scroll-restoration"
import { getDenshiViewTransition } from "./lib/router/view-transitions"
import { routeTree } from "./routeTree.gen"
import "@fontsource-variable/inter/index.css"

function installAndroidTVBridgeFacade() {
    if (!__isAndroidTV__ || typeof window === "undefined") return
    const native = window.AndroidTVNativeBridge
    const token = window.__seanimeAndroidTVBridgeToken
    if (!native || !token) return

    window.AndroidTV = {
        serverStatus: () => native.serverStatus(token),
        serverError: () => native.serverError(token),
        requestMediaFolder: (purpose) => native.requestMediaFolder(token, purpose),
        getStorageRoots: () => native.getStorageRoots(token),
        removeStorageFolder: (uri) => native.removeStorageFolder(token, uri),
        openExternalUrl: (url) => native.openExternalUrl(token, url),
        requestDownloadTarget: (requestId, filename, mimeType) => native.requestDownloadTarget(token, requestId, filename, mimeType),
        writeDownloadChunk: (requestId, base64Data) => native.writeDownloadChunk(token, requestId, base64Data),
        finishDownload: (requestId) => native.finishDownload(token, requestId),
        cancelDownload: (requestId) => native.cancelDownload(token, requestId),
        supportedAbi: () => native.supportedAbi(token),
        downloadAndInstallUpdate: (url, filename) => native.downloadAndInstallUpdate(token, url, filename),
        playNative: (url, title, subtitleTracksJson, startPositionMs, subtitleStyleJson, playbackSettingsJson) => native.playNative(token, url, title, subtitleTracksJson, startPositionMs, subtitleStyleJson, playbackSettingsJson),
        updateNativePlayer: (url, title, subtitleTracksJson, startPositionMs, subtitleStyleJson) => native.updateNativePlayer(token, url, title, subtitleTracksJson, startPositionMs, subtitleStyleJson),
        updateNativeSubtitleStyle: (subtitleStyleJson) => native.updateNativeSubtitleStyle(token, subtitleStyleJson),
        nativePlayerActive: () => native.nativePlayerActive(token),
        controlNativePlayer: (url, command, value) => native.controlNativePlayer(token, url, command, value),
        installUpdate: (filePath) => native.installUpdate(token, filePath),
        setPlaybackActive: (active) => native.setPlaybackActive(token, active),
        pendingPlaybackRecovery: () => native.pendingPlaybackRecovery(token),
        startPlaybackRecovery: (checkpointId, clientId) => native.startPlaybackRecovery(token, checkpointId, clientId),
        finishPlaybackRecovery: (checkpointId, streamUrl) => native.finishPlaybackRecovery(token, checkpointId, streamUrl),
        discardPlaybackRecovery: (checkpointId) => native.discardPlaybackRecovery(token, checkpointId),
    }
}

async function prepareAndroidTVBridge() {
    if (!__isAndroidTV__ || window !== window.top) return
    if (!window.__seanimeAndroidTVBridgeToken && window.AndroidTVBootstrap) {
        const token = await requestAndroidTVBridgeToken(window.AndroidTVBootstrap)
        if (token) Object.defineProperty(window, "__seanimeAndroidTVBridgeToken", { value: token, writable: false, configurable: false })
    }
    installAndroidTVBridgeFacade()
}

const androidTVBridgeReady = prepareAndroidTVBridge()

type RouterPreloadMode = false | "intent" | "viewport"

function createAppRouter(defaultPreload: RouterPreloadMode, defaultPreloadDelay?: number) {
    const viewTransition = getDenshiViewTransition()
    const router = createRouter({
        routeTree,
        defaultPreload,
        defaultPreloadDelay,
        context: {
            queryClient,
            store,
        },
        scrollRestoration: false,
        defaultViewTransition: viewTransition,
        defaultPreloadStaleTime: 30 * 1000,
    })

    if (viewTransition) {
        setupDenshiScrollRestoration(router)
    }

    return router
}

type AppRouter = ReturnType<typeof createAppRouter>

const intentRouter = createAppRouter("intent")
const fasterIntentRouter = createAppRouter("intent", 0)
const viewportRouter = createAppRouter("viewport")
const disabledRouter = createAppRouter(false)

const routersByPreloadMode: Record<NavigationPreloadMode, AppRouter> = {
    disable: disabledRouter,
    default: intentRouter,
    faster: fasterIntentRouter,
    viewport: viewportRouter,
}

declare module "@tanstack/react-router" {
    interface Register {
        router: AppRouter
    }
}

function AppRouterProvider() {
    const _preloadMode = useAtomValue(__navigationPreloadModeAtom)
    const isSimulatedUser = useIsSimulatedUser()
    const preloadMode = getActualNavigationPreloadMode(_preloadMode, isSimulatedUser)

    return <RouterProvider router={routersByPreloadMode[preloadMode]} />
}

function DesktopStartupReady() {
    React.useEffect(() => {
        if (!__isElectronDesktop__ || window.location.pathname.startsWith("/splashscreen") || !window.electron?.startup?.ready) {
            return
        }

        let sent = false
        let ff = 0
        let sf = 0
        let fallbackId = 0

        const sendReady = () => {
            if (sent) return

            sent = true
            window.electron?.startup?.ready()
        }

        ff = window.requestAnimationFrame(() => {
            sf = window.requestAnimationFrame(() => {
                sendReady()
            })
        })

        fallbackId = window.setTimeout(() => {
            sendReady()
        }, 500)

        return () => {
            window.cancelAnimationFrame(ff)
            window.cancelAnimationFrame(sf)
            window.clearTimeout(fallbackId)
        }
    }, [])

    return null
}

function AndroidTVInputSupport() {
    React.useEffect(() => {
        if (!__isAndroidTV__) return

        document.documentElement.classList.add("android-tv")

        const isVisible = (element: HTMLElement) => {
            const style = window.getComputedStyle(element)
            const rect = element.getBoundingClientRect()
            return style.display !== "none" && style.visibility !== "hidden" && rect.width > 0 && rect.height > 0
        }

        const focusables = () => {
            const overlays = Array.from(document.querySelectorAll<HTMLElement>(
                '[role="dialog"],[role="alertdialog"],[data-radix-dialog-content]',
            ))
            const scope = overlays.reverse().find(isVisible) ?? document
            return Array.from(scope.querySelectorAll<HTMLElement>(
                'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [role="button"], [tabindex]:not([tabindex="-1"])',
            )).filter(isVisible)
        }

        let lastFocusedElement: HTMLElement | null = null
        let windowFocusWasLost = false

        const onFocusIn = (event: FocusEvent) => {
            const target = event.target
            if (!(target instanceof HTMLElement) || target === document.body || target === document.documentElement || !isVisible(target)) return
            if (lastFocusedElement !== target) {
                lastFocusedElement?.removeAttribute("data-android-tv-focus-restored")
            }
            lastFocusedElement = target
        }

        const restoreLastFocusedElement = () => {
            const target = lastFocusedElement
            if (!target?.isConnected || !isVisible(target)) return
            target.setAttribute("data-android-tv-focus-restored", "true")
            target.focus({ preventScroll: true })
            target.scrollIntoView({ block: "nearest", inline: "nearest" })
        }

        const onWindowBlur = () => {
            windowFocusWasLost = true
        }

        const onWindowFocus = () => {
            if (!windowFocusWasLost) return
            windowFocusWasLost = false
            window.requestAnimationFrame(restoreLastFocusedElement)
        }

        const onNativeWindowFocusRestored = () => {
            windowFocusWasLost = false
            window.requestAnimationFrame(restoreLastFocusedElement)
        }

        const onKeyDown = (event: KeyboardEvent) => {
            if (!["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(event.key)) return
            if (windowFocusWasLost) {
                windowFocusWasLost = false
                restoreLastFocusedElement()
            }
            const active = document.activeElement
            const activeElement = active instanceof Element ? active : null
            const slider = activeElement?.closest('[role="slider"]')
            if (slider) {
                const verticalSlider = slider.getAttribute("aria-orientation") === "vertical"
                const sliderArrow = verticalSlider
                    ? event.key === "ArrowUp" || event.key === "ArrowDown"
                    : event.key === "ArrowLeft" || event.key === "ArrowRight"
                if (sliderArrow && slider.getAttribute("aria-disabled") !== "true") return
            } else if (activeElement?.closest(
                'input,textarea,select,[contenteditable="true"],[role="spinbutton"],[role="combobox"][aria-expanded="true"],[role="listbox"],[role="option"],[role="menu"],[role="menuitem"],[role="tablist"],[role="tree"],[role="grid"]',
            )) {
                return
            }
            if (active instanceof HTMLVideoElement || active?.closest("[data-vc-element='video']")) return

            const items = focusables()
            if (items.length === 0) return
            const current = active instanceof HTMLElement ? active : null
            const from = current?.getBoundingClientRect()
            if (!from || from.width === 0 || from.height === 0) {
                event.preventDefault()
                items[0].focus({ preventScroll: true })
                items[0].scrollIntoView({ block: "nearest", inline: "nearest" })
                return
            }

            const fromX = from.left + from.width / 2
            const fromY = from.top + from.height / 2
            let best: HTMLElement | undefined
            let bestScore = Number.POSITIVE_INFINITY

            for (const item of items) {
                if (item === current || current?.contains(item)) continue
                const rect = item.getBoundingClientRect()
                const x = rect.left + rect.width / 2
                const y = rect.top + rect.height / 2
                const dx = x - fromX
                const dy = y - fromY
                const primary = event.key === "ArrowRight" ? dx
                    : event.key === "ArrowLeft" ? -dx
                        : event.key === "ArrowDown" ? dy : -dy
                if (primary <= 6) continue
                const cross = event.key === "ArrowLeft" || event.key === "ArrowRight" ? Math.abs(dy) : Math.abs(dx)
                const score = primary + cross * 1.8 + Math.hypot(rect.width - from.width, rect.height - from.height) * 0.05
                if (score < bestScore) {
                    best = item
                    bestScore = score
                }
            }

            if (best) {
                event.preventDefault()
                best.focus({ preventScroll: true })
                best.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" })
            }
        }

        document.addEventListener("keydown", onKeyDown, true)
        document.addEventListener("focusin", onFocusIn, true)
        window.addEventListener("blur", onWindowBlur)
        window.addEventListener("focus", onWindowFocus)
        window.addEventListener("seanime-tv-native-focus-restored", onNativeWindowFocusRestored)
        return () => {
            document.documentElement.classList.remove("android-tv")
            document.removeEventListener("keydown", onKeyDown, true)
            document.removeEventListener("focusin", onFocusIn, true)
            window.removeEventListener("blur", onWindowBlur)
            window.removeEventListener("focus", onWindowFocus)
            window.removeEventListener("seanime-tv-native-focus-restored", onNativeWindowFocusRestored)
        }
    }, [])

    return null
}

function RootErrorFallback({ error, resetErrorBoundary }: FallbackProps) {
    return (
        <div className="min-h-screen bg-[#0c0c0c] text-white flex items-center justify-center p-6">
            <div className="w-full max-w-lg rounded-2xl border bg-black/60 p-6 text-center backdrop-blur-sm space-y-4">
                <LuffyError
                    title="Client error"
                >
                    Seanime encountered an unexpected error. Please try again.
                </LuffyError>

                {!!(error as Error)?.message && (
                    <pre className="max-h-48 overflow-auto rounded-xl bg-black/50 p-3 text-left text-xs text-red-200 whitespace-pre-wrap break-words">
                        {(error as Error).message}
                    </pre>
                )}

                <div className="flex items-center justify-center gap-3">
                    <Button
                        type="button"
                        intent="gray-outline"
                        className="rounded-full"
                        onClick={resetErrorBoundary}
                    >
                        Retry
                    </Button>
                    <Button
                        type="button"
                        intent="gray-outline"
                        className="rounded-full"
                        onClick={() => window.location.reload()}
                    >
                        Reload
                    </Button>
                </div>
            </div>
        </div>
    )
}

// if (import.meta.env.DEV) {
//     const script = document.createElement("script")
//     script.src = "https://unpkg.com/react-scan/dist/auto.global.js"
//     script.crossOrigin = "anonymous"
//     document.head.appendChild(script)
// }
androidTVBridgeReady.then(() => ReactDOM.createRoot(document.getElementById("root")!, {
    onUncaughtError: (error, errorInfo) => {
        console.error("[Root] Uncaught renderer error", error, errorInfo)
    },
    onCaughtError: (error, errorInfo) => {
        console.error("[Root] Caught renderer error", error, errorInfo)
    },
}).render(
    <ErrorBoundary FallbackComponent={RootErrorFallback}>
        <ClientProviders>
            <DesktopStartupReady />
            <AndroidTVInputSupport />
            <AppRouterProvider />
        </ClientProviders>
    </ErrorBoundary>,
))
