import { useServerStatus } from "@/app/(main)/_hooks/use-server-status"
import { sharedPlayerSurface } from "@/lib/playback-platform"
import { __isAndroidTV__, __isElectronDesktop__ } from "@/types/constants"
import React from "react"

const MpvCore = React.lazy(() => import("@/app/(main)/_features/mpv-core/mpv-core-lazy-wrapper"))
const NativePlayer = React.lazy(() => import("./native-player-lazy-wrapper"))

/** Mount the stream event listener in both the online and offline layouts. */
export function SharedIntegratedPlayer() {
    const status = useServerStatus()
    const surface = sharedPlayerSurface(__isAndroidTV__, __isElectronDesktop__, !!status?.settings?.mediaPlayer?.mpvPrismEnabled)
    if (!surface) return null
    return <React.Suspense fallback={null}>
        {surface === "mpvcore" ? <MpvCore /> : <NativePlayer />}
    </React.Suspense>
}
