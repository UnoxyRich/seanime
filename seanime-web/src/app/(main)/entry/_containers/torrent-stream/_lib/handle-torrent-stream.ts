import { HibikeTorrent_AnimeTorrent, HibikeTorrent_BatchEpisodeFiles } from "@/api/generated/types"
import { useTorrentstreamStartStream } from "@/api/hooks/torrentstream.hooks"
import {
    PlaybackTorrentStreaming,
    useCurrentDevicePlaybackSettings,
    useExternalPlayerLink,
} from "@/app/(main)/_atoms/playback.atoms"
import { useAutoPlaySelectedTorrent, useTorrentstreamAutoplay } from "@/app/(main)/_features/autoplay/autoplay"
import { getBatchSelectionParams } from "@/app/(main)/_features/autoplay/batches.ts"
import { usePlaylistManager } from "@/app/(main)/_features/playlists/_containers/global-playlist-manager"
import { useWebsocketMessageListener } from "@/app/(main)/_hooks/handle-websockets"
import { useServerStatus } from "@/app/(main)/_hooks/use-server-status"
import {
    __torrentstream__isLoadedAtom,
    __torrentstream__loadingStateAtom,
    TorrentStreamEvents,
} from "@/app/(main)/entry/_containers/torrent-stream/playback-play-pill"
import {
    __torrentStream_autoSelectFileAtom,
    __torrentStream_currentSessionAutoSelectAtom,
} from "@/app/(main)/entry/_containers/torrent-stream/torrent-stream-page"
import { ForcePlaybackMethod, useForcePlaybackMethod } from "@/app/(main)/entry/_lib/handle-play-media"
import { clientIdAtom } from "@/app/websocket-provider"
import { logger } from "@/lib/helpers/debug"
import { WSEvents } from "@/lib/server/ws-events"
import { __isAndroidTV__, __isElectronDesktop__ } from "@/types/constants"
import { streamPlaybackType } from "@/lib/playback-platform"
import { toast } from "sonner"
import { useQueryClient } from "@tanstack/react-query"
import { useAtomValue } from "jotai"
import { useSetAtom } from "jotai/react"
import React from "react"

type ManualTorrentStreamSelectionProps = {
    torrent: HibikeTorrent_AnimeTorrent
    mediaId: number
    episodeNumber: number
    aniDBEpisode: string
    chosenFileIndex: number | undefined | null
    batchEpisodeFiles: HibikeTorrent_BatchEpisodeFiles | undefined
    preload?: boolean
}
type AutoSelectTorrentStreamProps = {
    mediaId: number
    episodeNumber: number
    aniDBEpisode: string
    preload?: boolean
}

export function useHandleStartTorrentStream() {

    const { mutate, isPending } = useTorrentstreamStartStream()
    const qc = useQueryClient()

    const setLoadingState = useSetAtom(__torrentstream__loadingStateAtom)
    const setIsLoaded = useSetAtom(__torrentstream__isLoadedAtom)
    const { torrentStreamingPlayback, electronPlaybackMethod } = useCurrentDevicePlaybackSettings()
    const { externalPlayerLink } = useExternalPlayerLink()
    const clientId = useAtomValue(clientIdAtom)

    const setCurrentSessionAutoSelect = useSetAtom(__torrentStream_currentSessionAutoSelectAtom)

    const { resetForcePlaybackMethod, getForcePlaybackMethod } = useForcePlaybackMethod()

    const getPlaybackType = React.useCallback((forcePlaybackMethod?: ForcePlaybackMethod) => streamPlaybackType({
        androidTV: __isAndroidTV__,
        electron: __isElectronDesktop__,
        electronPlaybackMethod,
        externalPlayerSelected: torrentStreamingPlayback === PlaybackTorrentStreaming.ExternalPlayerLink,
        externalPlayerLink: externalPlayerLink ?? "",
        force: forcePlaybackMethod,
    }), [externalPlayerLink, torrentStreamingPlayback, electronPlaybackMethod])

    const handleStreamSelection = (params: ManualTorrentStreamSelectionProps) => {
        const forcePlaybackMethod = getForcePlaybackMethod()
        resetForcePlaybackMethod()
        const playbackType = params.preload ? "nativeplayer" : getPlaybackType(forcePlaybackMethod)
        if (!playbackType) {
            toast.error("Configure an external player link in Playback settings before playing in another app.")
            return
        }
        logger("TORRENT STREAM SELECTION").info("Starting torrent stream", params, playbackType)
        mutate({
            mediaId: params.mediaId,
            episodeNumber: params.episodeNumber,
            torrent: params.torrent,
            aniDBEpisode: params.aniDBEpisode,
            autoSelect: false,
            fileIndex: params.chosenFileIndex ?? undefined,
            playbackType,
            clientId: clientId || "",
            batchEpisodeFiles: params.batchEpisodeFiles,
            preload: params.preload,
        }, {
            onSuccess: () => {
                // setLoadingState(null)
            },
            onError: () => {
                setLoadingState(null)
                setIsLoaded(false)
            },
        })
    }

    const handleAutoSelectStream = (params: AutoSelectTorrentStreamProps) => {
        const forcePlaybackMethod = getForcePlaybackMethod()
        resetForcePlaybackMethod()
        const playbackType = params.preload ? "nativeplayer" : getPlaybackType(forcePlaybackMethod)
        if (!playbackType) {
            toast.error("Configure an external player link in Playback settings before playing in another app.")
            return
        }
        logger("TORRENT STREAM SELECTION").info("Starting torrent stream (auto select)", params, playbackType)
        mutate({
            mediaId: params.mediaId,
            episodeNumber: params.episodeNumber,
            aniDBEpisode: params.aniDBEpisode,
            autoSelect: true,
            torrent: undefined,
            playbackType,
            clientId: clientId || "",
            preload: params.preload,
        }, {
            onError: () => {
                setLoadingState(null)
                setIsLoaded(false)
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

export function useTorrentStreamListener() {
    const serverStatus = useServerStatus()
    const { currentPlaylist, nextPlaylistEpisode } = usePlaylistManager()
    const { torrentstreamAutoplayInfo, autoplayNextTorrentstreamEpisode } = useTorrentstreamAutoplay()
    const { autoPlayTorrent } = useAutoPlaySelectedTorrent()
    const { handleStreamSelection, handleAutoSelectStream } = useHandleStartTorrentStream()
    const torrentStream_autoSelectFile = useAtomValue(__torrentStream_autoSelectFileAtom)

    const torrentStream_currentSessionAutoSelect = serverStatus?.torrentstreamSettings?.autoSelect

    useWebsocketMessageListener({
        type: WSEvents.TORRENTSTREAM_STATE,
        onMessage: ({ state }: { state: TorrentStreamEvents }) => {
            switch (state) {
                case TorrentStreamEvents.PreloadNextStream:
                    if (currentPlaylist && nextPlaylistEpisode) {
                        const episode = nextPlaylistEpisode.episode
                        if (!episode) return
                        if (torrentStream_currentSessionAutoSelect) {
                            logger("TORRENT STREAM LISTENER").info("Auto select is enabled, preparing next stream with auto select")
                            handleAutoSelectStream({
                                mediaId: episode.baseAnime?.id!,
                                episodeNumber: episode.episodeNumber!,
                                aniDBEpisode: episode.aniDBEpisode!,
                                preload: true,
                            })
                            return
                        } else if (
                            autoPlayTorrent?.torrent?.isBatch &&
                            torrentStream_autoSelectFile &&
                            autoPlayTorrent.entry.mediaId === episode.baseAnime?.id
                        ) {
                            logger("TORRENT STREAM LISTENER")
                                .info("Previous selection matches, preparing next stream by auto-selecting file for torrent stream")
                            const batchParams = getBatchSelectionParams(autoPlayTorrent.batchFiles, episode.episodeNumber!, episode.aniDBEpisode!)
                            handleStreamSelection({
                                mediaId: episode.baseAnime?.id!,
                                episodeNumber: episode.episodeNumber!,
                                aniDBEpisode: episode.aniDBEpisode!,
                                torrent: autoPlayTorrent.torrent,
                                chosenFileIndex: batchParams.fileIndex,
                                batchEpisodeFiles: batchParams.batchEpisodeFiles,
                                preload: true,
                            })
                            return
                        }
                    } else if (torrentstreamAutoplayInfo) {
                        logger("TORRENT STREAM LISTENER").info("Preparing next stream for episode", torrentstreamAutoplayInfo)
                        autoplayNextTorrentstreamEpisode(true)
                    }
                    break
            }
        },
    })
}
