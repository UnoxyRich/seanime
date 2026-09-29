import type { VideoCore_VideoPlaybackInfo } from "@/app/(main)/_features/video-core/video-core.atoms"

export function subtitleSourceIdentity(info: VideoCore_VideoPlaybackInfo, video: HTMLVideoElement, originalUrl: string | undefined) {
    return { video, originalUrl, id: info.id, metadata: info.mkvMetadata, tracks: info.subtitleTracks, fonts: info.libassFonts }
}

// A transport-only change keeps the same HTML video and subtitle timeline. Keep
// runtime uploads/translations/plugin tracks and the selected/off state as well
// as the original embedded fonts. Actual source/metadata changes rebuild them.
export function canPreserveSubtitleManagers(previous: ReturnType<typeof subtitleSourceIdentity> | undefined, next: ReturnType<typeof subtitleSourceIdentity>, managerExists: boolean) {
    return managerExists && !!previous && previous.video === next.video && previous.originalUrl === next.originalUrl
        && previous.id === next.id && previous.metadata === next.metadata && previous.tracks === next.tracks && previous.fonts === next.fonts
}
