import { HibikeTorrent_AnimeTorrent, HibikeTorrent_BatchEpisodeFiles } from "@/api/generated/types"
import { useDebridStartStream } from "@/api/hooks/debrid.hooks"
import {
    PlaybackTorrentStreaming,
    useCurrentDevicePlaybackSettings,
    useExternalPlayerLink,
} from "@/app/(main)/_atoms/playback.atoms"
import { __debridStream_currentSessionAutoSelectAtom } from "@/app/(main)/entry/_containers/debrid-stream/debrid-stream-page"
import { __debridstream_stateAtom } from "@/app/(main)/entry/_containers/torrent-stream/playback-play-pill"
import { ForcePlaybackMethod, useForcePlaybackMethod } from "@/app/(main)/entry/_lib/handle-play-media"
import { clientIdAtom } from "@/app/websocket-provider"
import { logger } from "@/lib/helpers/debug"
import { __isAndroidTV__, __isElectronDesktop__ } from "@/types/constants"
import { streamPlaybackType } from "@/lib/playback-platform"
import { toast } from "sonner"
import { useQueryClient } from "@tanstack/react-query"
import { useAtomValue, useSetAtom } from "jotai"
import { useAtom } from "jotai/react"
import React from "react"

type DebridStreamSelectionProps = {
    torrent: HibikeTorrent_AnimeTorrent
    mediaId: number
    episodeNumber: number
    aniDBEpisode: string
    chosenFileId: string
    batchEpisodeFiles: HibikeTorrent_BatchEpisodeFiles | undefined
    forcePlaybackMethod?: ForcePlaybackMethod
}
type DebridStreamAutoSelectProps = {
    mediaId: number
    episodeNumber: number
    aniDBEpisode: string
    forcePlaybackMethod?: ForcePlaybackMethod
}

export function useHandleStartDebridStream() {

    const { mutate, isPending } = useDebridStartStream()
    const qc = useQueryClient()

    const { torrentStreamingPlayback, electronPlaybackMethod } = useCurrentDevicePlaybackSettings()
    const { externalPlayerLink } = useExternalPlayerLink()
    const clientId = useAtomValue(clientIdAtom)

    const setCurrentSessionAutoSelect = useSetAtom(__debridStream_currentSessionAutoSelectAtom)

    const [state, setState] = useAtom(__debridstream_stateAtom)

    const { resetForcePlaybackMethod, getForcePlaybackMethod } = useForcePlaybackMethod()

    const getPlaybackType = React.useCallback((forcePlaybackMethod?: ForcePlaybackMethod) => streamPlaybackType({
        androidTV: __isAndroidTV__,
        electron: __isElectronDesktop__,
        electronPlaybackMethod,
        externalPlayerSelected: torrentStreamingPlayback === PlaybackTorrentStreaming.ExternalPlayerLink,
        externalPlayerLink: externalPlayerLink ?? "",
        force: forcePlaybackMethod,
    }), [externalPlayerLink, torrentStreamingPlayback, electronPlaybackMethod])

    const handleStreamSelection = (params: DebridStreamSelectionProps) => {
        const forcePlaybackMethod = getForcePlaybackMethod()
        resetForcePlaybackMethod()
        const playbackType = getPlaybackType(forcePlaybackMethod)
        if (!playbackType) {
            toast.error("Configure an external player link in Playback settings before playing in another app.")
            return
        }
        logger("DEBRID STREAM SELECTION").info("Starting debrid stream", params, playbackType)
        mutate({
            mediaId: params.mediaId,
            episodeNumber: params.episodeNumber,
            torrent: params.torrent,
            aniDBEpisode: params.aniDBEpisode,
            fileId: params.chosenFileId,
            playbackType,
            clientId: clientId || "",
            autoSelect: false,
            batchEpisodeFiles: params.batchEpisodeFiles,
        }, {
            onSuccess: () => {
            },
            onError: () => {
                setState(null)
            },
        })
    }

    const handleAutoSelectStream = (params: DebridStreamAutoSelectProps) => {
        const forcePlaybackMethod = getForcePlaybackMethod()
        resetForcePlaybackMethod()
        const playbackType = getPlaybackType(forcePlaybackMethod)
        if (!playbackType) {
            toast.error("Configure an external player link in Playback settings before playing in another app.")
            return
        }
        logger("DEBRID STREAM SELECTION").info("Starting debrid stream (auto select)", params, playbackType)
        mutate({
            mediaId: params.mediaId,
            episodeNumber: params.episodeNumber,
            torrent: undefined,
            aniDBEpisode: params.aniDBEpisode,
            fileId: "",
            playbackType,
            clientId: clientId || "",
            autoSelect: true,
        }, {
            onSuccess: () => {
            },
            onError: () => {
                setState(null)
                React.startTransition(() => {
                    setCurrentSessionAutoSelect(false)
                })
            },
        })
    }

    return {
        isUsingNativePlayer: getPlaybackType() === "nativeplayer",
        handleStreamSelection,
        handleAutoSelectStream,
        isPending,
    }
}
