package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"seanime/internal/core"
	"seanime/internal/database/db_bridge"
	debridclient "seanime/internal/debrid/client"
	"seanime/internal/directstream"
	"seanime/internal/player"
	"seanime/internal/torrentstream"
	"strings"
	"sync"

	"github.com/google/uuid"
)

var playbackResumeMu sync.Mutex

type playbackCheckpoint struct {
	ID         string                     `json:"id"`
	PlaybackID string                     `json:"playbackId,omitempty"`
	Source     *directstream.ResumeSource `json:"source"`
}

func playbackResumeApp() (*core.App, error) {
	serverLifecycle.Lock()
	defer serverLifecycle.Unlock()
	if serverLifecycle.status != "ready" || serverLifecycle.instance == nil || serverLifecycle.instance.app == nil {
		return nil, errors.New("Seanime must be ready before restoring playback")
	}
	return serverLifecycle.instance.app, nil
}

// CapturePlaybackResume saves the original active source in app-private storage.
// Only the opaque ID crosses the Android host bridge; credentials stay in Go's
// data directory. The selected decoder's position/settings are saved by Android.
func CapturePlaybackResume(playbackURL string) (string, error) {
	playbackResumeMu.Lock()
	defer playbackResumeMu.Unlock()
	app, err := playbackResumeApp()
	if err != nil {
		return "", err
	}
	source, err := app.DirectStreamManager.CaptureResumeSource(playbackURL)
	if err != nil {
		return "", err
	}
	u, err := url.Parse(playbackURL)
	if err != nil {
		return "", err
	}
	return savePlaybackResume(app.Config.Data.AppDataDir, u.Query().Get("id"), source)
}

// The caller holds playbackResumeMu. Activity recreation can capture the same
// stream before an earlier capture's UI callback runs. Reusing its ticket keeps
// either activity's saved state valid, regardless of callback delivery order.
func savePlaybackResume(dataDir, playbackID string, source *directstream.ResumeSource) (string, error) {
	if playbackID == "" {
		return "", errors.New("playback ID is required")
	}
	if err := source.Validate(); err != nil {
		return "", err
	}
	if previous, err := loadPlaybackCheckpoint(dataDir); err == nil && previous.PlaybackID == playbackID {
		return previous.ID, nil
	}
	checkpoint := &playbackCheckpoint{ID: uuid.NewString(), PlaybackID: playbackID, Source: source}
	if err := writePlaybackCheckpoint(dataDir, checkpoint); err != nil {
		return "", err
	}
	return checkpoint.ID, nil
}

// RestorePlaybackResume opens a checkpoint through the existing source modules.
// It accepts a fresh WebView client ID so normal player/playlist events reach the
// restored UI. A successful return means the request was accepted; stream-ready
// and failure events continue to use the existing player event channel.
func RestorePlaybackResume(checkpointID string, clientID string) error {
	if strings.TrimSpace(clientID) == "" {
		return errors.New("a current WebView client ID is required")
	}
	playbackResumeMu.Lock()
	app, err := playbackResumeApp()
	if err != nil {
		playbackResumeMu.Unlock()
		return err
	}
	checkpoint, err := readPlaybackCheckpoint(app.Config.Data.AppDataDir, checkpointID)
	playbackResumeMu.Unlock()
	if err != nil {
		return err
	}
	source := checkpoint.Source
	ctx := context.Background()
	switch source.Type {
	case player.PlaybackTypeLocalFile:
		files, _, err := db_bridge.GetLocalFiles(app.Database)
		if err != nil {
			return err
		}
		return app.DirectStreamManager.PlayLocalFile(ctx, directstream.PlayLocalFileOptions{ClientId: clientID, Path: source.Path, LocalFiles: files})
	case player.PlaybackTypeTorrent:
		opts := &torrentstream.StartStreamOptions{MediaId: source.Media.ID, EpisodeNumber: source.Episode, AniDBEpisode: source.AniDBEpisode,
			Torrent: source.Torrent, FileIndex: source.FileIndex, ClientId: clientID, UserAgent: "Seanime TV/Android", PlaybackType: torrentstream.PlaybackTypeNativePlayer}
		opts.SetMedia(source.Media)
		return app.TorrentstreamRepository.StartStream(ctx, opts)
	case player.PlaybackTypeDebrid:
		return app.DebridClientRepository.StartStream(ctx, &debridclient.StartStreamOptions{MediaId: source.Media.ID, EpisodeNumber: source.Episode,
			AniDBEpisode: source.AniDBEpisode, Torrent: source.Torrent, FileId: source.FileID, ClientId: clientID,
			UserAgent: "Seanime TV/Android", PlaybackType: debridclient.PlaybackTypeNativePlayer})
	case player.PlaybackTypeURL:
		return app.DirectStreamManager.PlayUrlStream(ctx, directstream.PlayUrlStreamOptions{StreamUrl: source.StreamURL, Media: source.Media,
			AnidbEpisode: source.AniDBEpisode, ClientId: clientID})
	case player.PlaybackTypeNakama:
		return app.DirectStreamManager.PlayNakamaStream(ctx, directstream.PlayNakamaStreamOptions{StreamUrl: source.StreamURL, Media: source.Media,
			MediaId: source.Media.ID, AnidbEpisode: source.AniDBEpisode, NakamaHostPassword: source.NakamaToken, ClientId: clientID})
	}
	return errors.New("unsupported playback checkpoint")
}

const maxPlaybackCheckpointBytes = 1024 * 1024

func writePlaybackCheckpoint(dataDir string, checkpoint *playbackCheckpoint) error {
	if checkpoint == nil || checkpoint.ID == "" {
		return errors.New("invalid playback checkpoint")
	}
	if err := checkpoint.Source.Validate(); err != nil {
		return err
	}
	data, err := json.Marshal(checkpoint)
	if err != nil {
		return err
	}
	if len(data) > maxPlaybackCheckpointBytes {
		return errors.New("playback checkpoint is too large")
	}
	directory := filepath.Join(dataDir, "androidtv-playback")
	if err := os.MkdirAll(directory, 0700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(directory, ".checkpoint-*")
	if err != nil {
		return err
	}
	defer os.Remove(temporary.Name())
	defer temporary.Close()
	if _, err := temporary.Write(data); err != nil {
		return err
	}
	if err := temporary.Sync(); err != nil {
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporary.Name(), filepath.Join(directory, "active.json"))
}

func readPlaybackCheckpoint(dataDir, checkpointID string) (*playbackCheckpoint, error) {
	if checkpointID == "" {
		return nil, errors.New("playback checkpoint ID is required")
	}
	checkpoint, err := loadPlaybackCheckpoint(dataDir)
	if err != nil {
		return nil, err
	}
	if checkpoint.ID != checkpointID {
		return nil, errors.New("playback checkpoint was replaced")
	}
	return checkpoint, nil
}

func loadPlaybackCheckpoint(dataDir string) (*playbackCheckpoint, error) {
	file, err := os.Open(filepath.Join(dataDir, "androidtv-playback", "active.json"))
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, maxPlaybackCheckpointBytes+1))
	if err != nil || len(data) > maxPlaybackCheckpointBytes {
		return nil, errors.New("could not read playback checkpoint")
	}
	var checkpoint playbackCheckpoint
	if err := json.Unmarshal(data, &checkpoint); err != nil {
		return nil, fmt.Errorf("invalid playback checkpoint: %w", err)
	}
	if checkpoint.ID == "" {
		return nil, errors.New("invalid playback checkpoint ID")
	}
	if err := checkpoint.Source.Validate(); err != nil {
		return nil, err
	}
	return &checkpoint, nil
}
