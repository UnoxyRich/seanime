package directstream

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"seanime/internal/api/anilist"
	hibiketorrent "seanime/internal/extension/hibike/torrent"
	"seanime/internal/player"
)

// ResumeSource is an app-private description of the original source, independent
// of the temporary proxy URL and client identity. It must not be included in
// public playback metadata: Nakama credentials can be needed to reopen a source.
type ResumeSource struct {
	Version      int                         `json:"version"`
	Type         player.PlaybackType         `json:"type"`
	Media        *anilist.BaseAnime          `json:"media"`
	AniDBEpisode string                      `json:"aniDBEpisode"`
	Episode      int                         `json:"episode"`
	Path         string                      `json:"path,omitempty"`
	Torrent      *hibiketorrent.AnimeTorrent `json:"torrent,omitempty"`
	FileIndex    *int                        `json:"fileIndex,omitempty"`
	FileID       string                      `json:"fileId,omitempty"`
	StreamURL    string                      `json:"streamUrl,omitempty"`
	NakamaToken  string                      `json:"nakamaToken,omitempty"`
}

func (s *ResumeSource) Validate() error {
	if s == nil || s.Version != 1 || s.Media == nil || s.Media.ID <= 0 || s.AniDBEpisode == "" {
		return errors.New("invalid playback resume source")
	}
	switch s.Type {
	case player.PlaybackTypeLocalFile:
		if s.Path == "" {
			return errors.New("resume source has no local path")
		}
	case player.PlaybackTypeTorrent:
		if s.Torrent == nil || s.Torrent.MagnetLink == "" || s.FileIndex == nil || *s.FileIndex < 0 {
			return errors.New("resume source has no torrent or file selection")
		}
	case player.PlaybackTypeDebrid:
		if s.Torrent == nil || s.FileID == "" {
			return errors.New("resume source has no debrid file selection")
		}
	case player.PlaybackTypeURL, player.PlaybackTypeNakama:
		u, err := url.Parse(s.StreamURL)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return errors.New("resume source has no HTTP source URL")
		}
	default:
		return fmt.Errorf("unsupported resume source type %q", s.Type)
	}
	return nil
}

// ResumeSourcesMatch verifies that a reopened stream is the same source as an
// app-private checkpoint. It compares stable source identity, not transient
// playback IDs or mutable display metadata.
func ResumeSourcesMatch(expected, actual *ResumeSource) bool {
	if expected == nil || actual == nil || expected.Validate() != nil || actual.Validate() != nil {
		return false
	}
	if expected.Type != actual.Type || expected.Media.ID != actual.Media.ID ||
		expected.AniDBEpisode != actual.AniDBEpisode || expected.Episode != actual.Episode {
		return false
	}
	switch expected.Type {
	case player.PlaybackTypeLocalFile:
		return expected.Path == actual.Path
	case player.PlaybackTypeTorrent:
		return expected.Torrent.InfoHash == actual.Torrent.InfoHash &&
			*expected.FileIndex == *actual.FileIndex
	case player.PlaybackTypeDebrid:
		return expected.Torrent.InfoHash == actual.Torrent.InfoHash && expected.FileID == actual.FileID
	case player.PlaybackTypeURL:
		return expected.StreamURL == actual.StreamURL
	case player.PlaybackTypeNakama:
		return expected.StreamURL == actual.StreamURL && expected.NakamaToken == actual.NakamaToken
	default:
		return false
	}
}

// CaptureResumeSource only captures the stream whose playback ID is in the
// supplied proxy URL. A late callback cannot replace the next episode's source.
func (m *Manager) CaptureResumeSource(playbackURL string) (*ResumeSource, error) {
	u, err := url.Parse(playbackURL)
	if err != nil || u.Path != "/api/v1/directstream/stream" || u.Query().Get("id") == "" {
		return nil, errors.New("not a directstream playback URL")
	}
	m.playbackMu.Lock()
	defer m.playbackMu.Unlock()
	stream, ok := m.currentStream.Get()
	if !ok || m.currentPlaybackId == "" || u.Query().Get("id") != m.currentPlaybackId {
		return nil, errors.New("playback source is no longer current")
	}
	episode := stream.Episode()
	if episode == nil {
		return nil, errors.New("playback source has no episode")
	}
	source := &ResumeSource{Version: 1, Type: stream.Type(), Media: stream.Media(), AniDBEpisode: episode.AniDBEpisode, Episode: episode.EpisodeNumber}
	switch current := stream.(type) {
	case *LocalFileStream:
		if current.localFile != nil {
			source.Path = current.localFile.Path
		}
	case *TorrentStream:
		if current.torrent == nil || current.file == nil {
			return nil, errors.New("torrent source is unavailable")
		}
		metadata := current.torrent.Metainfo()
		magnet, err := metadata.MagnetV2()
		if err != nil {
			return nil, fmt.Errorf("capture torrent source: %w", err)
		}
		source.Torrent = &hibiketorrent.AnimeTorrent{Name: current.torrent.Name(), MagnetLink: magnet.String(), InfoHash: current.torrent.InfoHash().HexString()}
		for index, file := range current.torrent.Files() {
			if file == current.file {
				source.FileIndex = &index
				break
			}
		}
	case *DebridStream:
		source.Torrent = current.torrent
		source.FileID = current.fileID
	case *UrlStream:
		source.StreamURL = current.streamUrl
	case *Nakama:
		source.StreamURL = current.streamUrl
		source.NakamaToken = current.requestHeaders.Get("X-Seanime-Nakama-Token")
	default:
		return nil, errors.New("playback source cannot be checkpointed")
	}
	if err := source.Validate(); err != nil {
		return nil, err
	}
	// Detach pointers to media/torrent metadata before releasing the stream lock.
	encoded, err := json.Marshal(source)
	if err != nil {
		return nil, err
	}
	var snapshot ResumeSource
	if err := json.Unmarshal(encoded, &snapshot); err != nil {
		return nil, err
	}
	return &snapshot, nil
}
