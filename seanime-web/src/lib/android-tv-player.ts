export type AndroidTVPlayerCommand = "play" | "pause" | "seekTo" | "speed" | "volume" | "muted" | "stop"

export type AndroidTVPlayerSnapshot = {
    url: string
    positionMs: number
    durationMs: number
    bufferedPositionMs: number
    paused: boolean
    buffering: boolean
    completed: boolean
    speed: number
    volume: number
    muted: boolean
    videoWidth: number
    videoHeight: number
    active: boolean
    closed: boolean
}

type SendCommand = (command: AndroidTVPlayerCommand, value: number) => void

const activeAdapters = new WeakMap<HTMLVideoElement, { pauseBrowser: () => void; dispose: () => void }>()
const browserPlaybackEvents = [
    "play", "playing", "pause", "timeupdate", "durationchange", "loadedmetadata", "loadeddata", "canplay", "canplaythrough",
    "waiting", "stalled", "ended", "seeking", "seeked", "ratechange", "volumechange", "resize", "error", "emptied", "abort", "suspend", "progress",
]

/** Pause only the browser decoder while handing a source to Media3. */
export function pauseAndroidTVBrowserPlayer(video: HTMLVideoElement | null | undefined) {
    if (!video) return
    const adapter = activeAdapters.get(video)
    if (adapter) adapter.pauseBrowser()
    else video.pause()
}

/**
 * VideoCore, plugins and continuity share one HTMLVideoElement. During native
 * playback, expose Media3's state on that instance and route its controls to
 * Android. No browser prototype is changed; disposal restores every property.
 */
export function attachAndroidTVPlayer(video: HTMLVideoElement, url: string, send: SendCommand) {
    activeAdapters.get(video)?.dispose()
    const originalPlay = video.play.bind(video)
    const originalPause = video.pause.bind(video)
    const originals = new Map<string, PropertyDescriptor | undefined>()
    let disposed = false
    let seeking = false
    let receivedMetadata = false
    let completionDelivered = false
    let snapshot: AndroidTVPlayerSnapshot = {
        url,
        positionMs: Math.max(0, video.currentTime || 0) * 1000,
        durationMs: Number.isFinite(video.duration) ? video.duration * 1000 : 0,
        bufferedPositionMs: 0,
        paused: true,
        buffering: true,
        completed: false,
        speed: video.playbackRate,
        volume: video.volume,
        muted: video.muted,
        videoWidth: video.videoWidth,
        videoHeight: video.videoHeight,
        active: true,
        closed: false,
    }

    const emit = (type: string) => video.dispatchEvent(new Event(type))
    // Browser decoding can finish or fail after a handoff. Only Media3 events
    // may update continuity, Nakama and playlists while it owns playback.
    const suppressBrowserEvent = (event: Event) => {
        if (event.isTrusted && !snapshot.closed) event.stopImmediatePropagation()
    }
    for (const type of browserPlaybackEvents) video.addEventListener(type, suppressBrowserEvent, true)
    const duration = () => snapshot.durationMs > 0 ? snapshot.durationMs / 1000 : Number.NaN
    const command = (name: AndroidTVPlayerCommand, value = 0) => {
        if (!disposed && !snapshot.closed) send(name, value)
    }

    const define = (name: string, descriptor: PropertyDescriptor) => {
        originals.set(name, Object.getOwnPropertyDescriptor(video, name))
        Object.defineProperty(video, name, { configurable: true, ...descriptor })
    }

    function dispose() {
        if (disposed) return
        disposed = true
        if (activeAdapters.get(video) === registration) activeAdapters.delete(video)
        for (const type of browserPlaybackEvents) video.removeEventListener(type, suppressBrowserEvent, true)
        for (const [name, descriptor] of originals) {
            if (descriptor) Object.defineProperty(video, name, descriptor)
            else Reflect.deleteProperty(video, name)
        }
    }

    const registration = { pauseBrowser: originalPause, dispose }
    activeAdapters.set(video, registration)

    define("duration", { get: duration })
    define("currentTime", {
        get: () => snapshot.positionMs / 1000,
        set: (seconds: number) => {
            if (!Number.isFinite(seconds)) return
            const positionMs = Math.max(0, Math.min(seconds * 1000, snapshot.durationMs || Infinity))
            if (snapshot.closed) {
                dispose()
                video.currentTime = positionMs / 1000
                return
            }
            snapshot = { ...snapshot, positionMs, completed: false }
            seeking = true
            emit("seeking")
            command("seekTo", positionMs)
        },
    })
    define("paused", { get: () => snapshot.paused })
    define("ended", { get: () => snapshot.completed })
    define("seeking", { get: () => seeking })
    define("readyState", { get: () => snapshot.buffering ? 2 : (receivedMetadata ? 4 : 0) })
    define("videoWidth", { get: () => snapshot.videoWidth })
    define("videoHeight", { get: () => snapshot.videoHeight })
    define("playbackRate", {
        get: () => snapshot.speed,
        set: (speed: number) => { if (Number.isFinite(speed) && speed > 0) command("speed", speed) },
    })
    define("volume", {
        get: () => snapshot.volume,
        set: (volume: number) => { if (Number.isFinite(volume)) command("volume", Math.max(0, Math.min(1, volume))) },
    })
    define("muted", { get: () => snapshot.muted, set: (muted: boolean) => command("muted", muted ? 1 : 0) })
    define("buffered", {
        get: (): TimeRanges => {
            const end = snapshot.bufferedPositionMs / 1000
            const check = (index: number) => {
                if (index !== 0 || end <= 0) throw new DOMException("No buffered range", "IndexSizeError")
            }
            return { length: end > 0 ? 1 : 0, start: index => { check(index); return 0 }, end: index => { check(index); return end } }
        },
    })
    define("play", {
        value: () => {
            if (snapshot.closed) {
                const position = snapshot.positionMs / 1000
                dispose()
                try { video.currentTime = position } catch { /* An undecodable browser source may reject seeking. */ }
                return originalPlay()
            }
            command("play")
            return Promise.resolve()
        },
    })
    define("pause", { value: () => snapshot.closed ? originalPause() : command("pause") })

    return {
        dispose,
        isDisposed: () => disposed,
        update(next: AndroidTVPlayerSnapshot) {
            if (disposed || next.url !== url || !Number.isFinite(next.positionMs) || next.positionMs < 0) return false
            const previous = snapshot
            snapshot = {
                ...next,
                durationMs: Number.isFinite(next.durationMs) && next.durationMs > 0 ? next.durationMs : 0,
                bufferedPositionMs: Number.isFinite(next.bufferedPositionMs) ? Math.max(0, next.bufferedPositionMs) : 0,
                paused: next.closed || !next.active || next.paused,
                speed: Number.isFinite(next.speed) && next.speed > 0 ? next.speed : 1,
                volume: Number.isFinite(next.volume) ? Math.max(0, Math.min(1, next.volume)) : previous.volume,
            }
            const firstMetadata = !receivedMetadata && snapshot.durationMs > 0
            if (firstMetadata) receivedMetadata = true
            if (snapshot.durationMs !== previous.durationMs || firstMetadata) emit("durationchange")
            if (snapshot.videoWidth !== previous.videoWidth || snapshot.videoHeight !== previous.videoHeight) emit("resize")
            if (snapshot.paused !== previous.paused) emit(snapshot.paused ? "pause" : "play")
            if (snapshot.speed !== previous.speed) emit("ratechange")
            if (snapshot.volume !== previous.volume || snapshot.muted !== previous.muted) emit("volumechange")
            if (snapshot.buffering && !previous.buffering) emit("waiting")
            if (snapshot.durationMs > 0 && !snapshot.buffering && !snapshot.completed && (previous.buffering || firstMetadata) && snapshot.active) emit("canplay")
            if (seeking) { seeking = false; emit("seeked") }
            emit("timeupdate")
            if (!snapshot.completed && previous.completed) completionDelivered = false
            if (snapshot.completed && !completionDelivered) {
                completionDelivered = true
                emit("ended")
            }
            return firstMetadata
        },
    }
}
