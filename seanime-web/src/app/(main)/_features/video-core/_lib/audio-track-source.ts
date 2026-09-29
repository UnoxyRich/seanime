import type { MKVParser_TrackInfo } from "@/api/generated/types"
import type { HlsAudioTrack } from "@/app/(main)/_features/video-core/video-core-hls"

// Metadata retains MKV track numbers, while HLS audio selection uses its own
// zero-based IDs. Always follow the current manager's transport after conversion.
export function selectPlaybackAudioTracks(mkvTracks: MKVParser_TrackInfo[] | undefined, hlsTracks: HlsAudioTrack[], managerIsHls: boolean) {
    const isHls = managerIsHls || (!mkvTracks?.length && hlsTracks.length > 0)
    return { audioTracks: isHls ? hlsTracks : mkvTracks ?? null, isHls }
}
