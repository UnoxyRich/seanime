import { useSeaQuery } from "@/api/client/requests"
import { getServerBaseUrl } from "@/api/client/server-url"
import { __isAndroidTV__ } from "@/types/constants"
import React from "react"

type SourceTranscodeResponse = {
    sessionId: string
    streamUrl: string
    timeOffset: number
    chapters: { name: string, startTime: number, endTime: number }[]
}

export type SourcePlaybackSnapshot = {
    currentTime: number
    paused: boolean
    playbackRate: number
    volume: number
    muted: boolean
    positionReady?: boolean
}

export function captureSourcePlayback(video: HTMLVideoElement | null): SourcePlaybackSnapshot | undefined {
    if (!video) return undefined
    return {
        currentTime: Number.isFinite(video.currentTime) ? Math.max(0, video.currentTime) : 0,
        paused: video.paused,
        playbackRate: video.playbackRate,
        volume: video.volume,
        muted: video.muted,
        positionReady: Number.isFinite(video.duration) && video.duration > 0 && (video.readyState >= 1 || video.currentTime > 0),
    }
}

// Conversion exposes the entire original timeline. Applying a start-position
// offset here would corrupt chapters, continuity, plugins and Nakama progress.
export function restoreSourcePlayback(video: HTMLVideoElement, snapshot: SourcePlaybackSnapshot, timeOffset = 0) {
    if (timeOffset !== 0) throw new Error("Converted stream must retain the original timeline")
    if (snapshot.positionReady !== false) {
        video.currentTime = Number.isFinite(video.duration) && video.duration > 0
            ? Math.min(snapshot.currentTime, Math.max(0, video.duration - 0.01))
            : snapshot.currentTime
    }
    video.playbackRate = snapshot.playbackRate
    video.volume = snapshot.volume
    video.muted = snapshot.muted
    if (snapshot.paused) video.pause()
    else void video.play().catch(() => undefined)
}

export function useAndroidTVSourceTranscode(options: {
    playbackId: string | undefined
    sourceUrl: string | undefined
    active: boolean
    playbackError?: string | null
    hasRestoredPosition?: () => boolean
    videoRef: React.MutableRefObject<HTMLVideoElement | null>
}) {
    const { seaFetch } = useSeaQuery()
    const fetchRef = React.useRef(seaFetch)
    fetchRef.current = seaFetch
    const available = __isAndroidTV__ && options.active && !!options.playbackId && /^https?:\/\//i.test(options.sourceUrl ?? "")
    const sourceKey = available ? `${options.playbackId}\u0000${options.sourceUrl}` : undefined
    const keyRef = React.useRef(sourceKey)
    keyRef.current = sourceKey
    const originalError = React.useRef(options.playbackError)
    originalError.current = options.playbackError
    const generation = React.useRef(0)
    const request = React.useRef<AbortController | undefined>(undefined)
    const session = React.useRef<string | undefined>(undefined)
    const resume = React.useRef<SourcePlaybackSnapshot | undefined>(undefined)
    const conversionIntent = React.useRef(false)
    const responseRef = React.useRef<SourceTranscodeResponse | undefined>(undefined)
    const continuation = React.useRef<{ playbackId?: string, snapshot?: SourcePlaybackSnapshot, response?: SourceTranscodeResponse, intent: boolean } | undefined>(undefined)
    const convertLatest = React.useRef<(snapshot?: SourcePlaybackSnapshot, response?: SourceTranscodeResponse) => Promise<void>>(async () => {})
    const [conversion, setConversion] = React.useState<{
        key?: string
        playbackId?: string
        response?: SourceTranscodeResponse
        pending?: boolean
        error?: string
        originalPlaybackError?: string | null
    }>({})

    const stopSession = React.useCallback((sessionId: string) => {
        void fetchRef.current("/api/v1/mediastream/source/stop", "POST", { sessionId }).catch(() => undefined)
    }, [])

    const cancel = React.useCallback(() => {
        generation.current++
        request.current?.abort()
        request.current = undefined
        if (session.current) stopSession(session.current)
        session.current = undefined
        resume.current = undefined
    }, [stopSession])

    const reset = React.useCallback(() => {
        conversionIntent.current = false
        const snapshot = captureSourcePlayback(options.videoRef.current)
        cancel()
        // Reverting to the original URL also retains position and play state.
        resume.current = snapshot
        setConversion({ key: sourceKey })
    }, [cancel, sourceKey, options.videoRef])

    const convert = React.useCallback(async (savedSnapshot?: SourcePlaybackSnapshot, previousResponse?: SourceTranscodeResponse) => {
        if (!available || !sourceKey) return
        const snapshot = savedSnapshot ?? captureSourcePlayback(options.videoRef.current)
        if (!savedSnapshot && snapshot && options.hasRestoredPosition) {
            // loadedmetadata alone does not mean initialState/continuity ran.
            snapshot.positionReady = options.hasRestoredPosition() || snapshot.currentTime > 0 || !!window.AndroidTV?.nativePlayerActive()
        } else if (snapshot && window.AndroidTV?.nativePlayerActive()) snapshot.positionReady = true
        cancel()
        conversionIntent.current = true
        const currentGeneration = generation.current
        const controller = new AbortController()
        request.current = controller
        resume.current = snapshot
        // The native adapter remains bound to the original source. Release it
        // before using the same HTML video for the advanced browser controls.
        if (window.AndroidTV?.nativePlayerActive()) {
            window.AndroidTV.controlNativePlayer(options.sourceUrl!, "stop", 0)
        }
        options.videoRef.current?.pause()
        setConversion({ key: sourceKey, playbackId: options.playbackId, pending: true, response: previousResponse })
        try {
            const response = await fetchRef.current<SourceTranscodeResponse>("/api/v1/mediastream/source/request", "POST", {
                sourceUrl: options.sourceUrl,
                playbackId: options.playbackId,
            }, undefined, controller.signal)
            if (!response?.sessionId) throw new Error("The server did not return a conversion session")
            if (generation.current !== currentGeneration || keyRef.current !== sourceKey || controller.signal.aborted) {
                stopSession(response.sessionId)
                return
            }
            if (response.timeOffset !== 0) {
                stopSession(response.sessionId)
                throw new Error("Converted stream must retain the original timeline")
            }
            session.current = response.sessionId
            setConversion({ key: sourceKey, playbackId: options.playbackId, response, originalPlaybackError: originalError.current })
        } catch (error) {
            if (generation.current !== currentGeneration || controller.signal.aborted) return
            resume.current = undefined
            setConversion({ key: sourceKey, error: error instanceof Error ? error.message : "Unable to convert this stream" })
        } finally {
            if (request.current === controller) request.current = undefined
        }
    }, [available, sourceKey, options.playbackId, options.sourceUrl, options.videoRef, options.hasRestoredPosition, cancel, stopSession])

    convertLatest.current = convert
    responseRef.current = conversion.response
    React.useEffect(() => {
        const saved = continuation.current
        if (sourceKey && saved?.intent && saved.playbackId === options.playbackId) {
            void convertLatest.current(saved.snapshot, saved.response)
        } else {
            conversionIntent.current = false
            setConversion({})
        }
        return () => {
            // This cleanup runs before the HLS effect detaches the old source.
            // Retain its URL in render during a same-ID refresh so changing the
            // <video src> cannot reset the old frame before this capture either.
            continuation.current = {
                playbackId: options.playbackId,
                snapshot: resume.current ?? captureSourcePlayback(options.videoRef.current),
                response: responseRef.current,
                intent: conversionIntent.current,
            }
            cancel()
        }
    }, [sourceKey, cancel])

    const stop = React.useCallback(() => {
        conversionIntent.current = false
        cancel()
    }, [cancel])

    const reportPlaybackError = React.useCallback((error: string) => {
        setConversion(previous => previous.key === sourceKey && previous.response && !previous.pending
            ? { ...previous, error }
            : previous)
    }, [sourceKey])

    const current = conversion.key === sourceKey ? conversion
        : available && conversion.playbackId === options.playbackId && conversion.response
            ? { ...conversion, pending: true, error: undefined }
            : {}
    return {
        available,
        pending: !!current.pending,
        converted: !!current.response,
        error: current.error,
        stalePlaybackError: current.originalPlaybackError,
        reportPlaybackError,
        streamUrl: current.response ? new URL(current.response.streamUrl, `${getServerBaseUrl()}/`).toString() : options.sourceUrl,
        chapters: current.response?.chapters?.map((chapter, uid) => ({
            uid, start: chapter.startTime, end: chapter.endTime > chapter.startTime ? chapter.endTime : undefined, text: chapter.name,
        })),
        convert,
        reset,
        stop,
        restore: (video: HTMLVideoElement): "position" | "settings" | false => {
            if (!resume.current || current.pending) return false
            const snapshot = resume.current
            resume.current = undefined
            restoreSourcePlayback(video, snapshot, current.response?.timeOffset ?? 0)
            return snapshot.positionReady === false ? "settings" : "position"
        },
    }
}
