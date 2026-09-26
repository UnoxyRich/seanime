export type SharedPlayerSurface = "videocore" | "mpvcore" | null

export function sharedPlayerSurface(androidTV: boolean, electron: boolean, mpvEnabled: boolean): SharedPlayerSurface {
    if (androidTV) return "videocore"
    if (electron) return mpvEnabled ? "mpvcore" : "videocore"
    return null
}

type StreamPlaybackOptions = {
    androidTV: boolean
    electron: boolean
    electronPlaybackMethod: string
    externalPlayerSelected: boolean
    externalPlayerLink: string
    force?: "playbackmanager" | "nativeplayer" | "externalPlayerLink"
}

/** Keep desktop behavior, and route TV streams to its shared player or an Android external app. */
export function streamPlaybackType(options: StreamPlaybackOptions): "nativeplayer" | "externalPlayerLink" | "default" | null {
    const { androidTV, electron, electronPlaybackMethod, externalPlayerSelected, externalPlayerLink, force } = options
    if (force === "nativeplayer" || (!force && electron && electronPlaybackMethod === "nativePlayer")) return "nativeplayer"
    const external = force === "externalPlayerLink" || (androidTV && force === "playbackmanager") || (!force && externalPlayerSelected)
    const hasExternalLink = androidTV ? !!externalPlayerLink.trim() : !!externalPlayerLink.length
    if (external && hasExternalLink) return "externalPlayerLink"
    // Android has no desktop player process to fall back to. Keep the user's
    // explicit external-player choice and let the caller show its setup error.
    if (androidTV && external) return null
    return androidTV ? "nativeplayer" : "default"
}
