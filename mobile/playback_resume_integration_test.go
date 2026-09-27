package mobile

import (
	"bytes"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"seanime/internal/api/anilist"
	"seanime/internal/api/metadata"
	"seanime/internal/api/metadata_provider"
	"seanime/internal/core"
	"seanime/internal/database/db_bridge"
	"seanime/internal/directstream"
	"seanime/internal/library/anime"
	"seanime/internal/util"
	"seanime/internal/util/result"

	"github.com/samber/mo"
)

const playbackRecoveryMediaID = 784512

type recoveryMetadataProvider struct {
	cache *result.BoundedCache[string, *metadata.AnimeMetadata]
}

func (p recoveryMetadataProvider) GetAnimeMetadata(metadata.Platform, int) (*metadata.AnimeMetadata, error) {
	return nil, errors.New("fixture metadata unavailable")
}

func (recoveryMetadataProvider) GetAnimeMetadataWrapper(*anilist.BaseAnime, *metadata.AnimeMetadata) metadata_provider.AnimeMetadataWrapper {
	return recoveryMetadataWrapper{}
}

func (p recoveryMetadataProvider) GetCache() *result.BoundedCache[string, *metadata.AnimeMetadata] {
	return p.cache
}

func (recoveryMetadataProvider) SetUseFallbackProvider(bool) {}
func (recoveryMetadataProvider) ClearCache()                 {}
func (recoveryMetadataProvider) Close()                      {}

type recoveryMetadataWrapper struct{}

func (recoveryMetadataWrapper) GetEpisodeMetadata(string) metadata.EpisodeMetadata {
	return metadata.EpisodeMetadata{}
}

func TestLocalPlaybackResumeReopensPersistedSourceAfterServerRestart(t *testing.T) {
	dataDir := filepath.Join(t.TempDir(), "data")
	cacheDir := filepath.Join(t.TempDir(), "cache")
	mediaDir := filepath.Join(dataDir, "recovery-fixture")
	if err := os.MkdirAll(mediaDir, 0700); err != nil {
		t.Fatal(err)
	}
	fixturePath := filepath.Join(mediaDir, "episode-01.mkv")
	fixtureBytes := []byte("Seanime Android TV local playback recovery fixture")
	if err := os.WriteFile(fixturePath, fixtureBytes, 0600); err != nil {
		t.Fatal(err)
	}

	previousFiles := db_bridge.CurrLocalFiles
	previousFilesID := db_bridge.CurrLocalFilesDbId
	db_bridge.CurrLocalFiles = mo.None[[]*anime.LocalFile]()
	db_bridge.CurrLocalFilesDbId = 0
	t.Cleanup(func() {
		StopServer()
		SetAppInForeground(true)
		db_bridge.CurrLocalFiles = previousFiles
		db_bridge.CurrLocalFilesDbId = previousFilesID
	})
	SetAppInForeground(false)
	startRecoveryTestServer(t, dataDir, cacheDir)

	firstApp := currentRecoveryTestApp(t)
	localFile := &anime.LocalFile{
		Path:     fixturePath,
		Name:     filepath.Base(fixturePath),
		Metadata: &anime.LocalFileMetadata{Episode: 1, AniDBEpisode: "1", Type: anime.LocalFileTypeMain},
		MediaId:  playbackRecoveryMediaID,
	}
	if _, err := db_bridge.InsertLocalFiles(firstApp.Database, []*anime.LocalFile{localFile}); err != nil {
		t.Fatalf("persist local recovery fixture: %v", err)
	}
	firstManager := configureRecoveryTestPlayback(firstApp)
	if err := firstManager.PlayLocalFile(t.Context(), directstream.PlayLocalFileOptions{
		ClientId:   "first-webview-client",
		Path:       fixturePath,
		LocalFiles: []*anime.LocalFile{localFile},
	}); err != nil {
		t.Fatalf("start initial local playback: %v", err)
	}
	firstPlaybackID, _, ok := firstManager.GetCurrentPlaybackIdentity()
	if !ok {
		t.Fatal("initial local playback did not become active")
	}
	firstURL := fmt.Sprintf("http://127.0.0.1/api/v1/directstream/stream?id=%s", firstPlaybackID)
	checkpointID, err := CapturePlaybackResume(firstURL)
	if err != nil {
		t.Fatalf("capture local playback source: %v", err)
	}
	assertRecoveryStreamServesFixture(t, firstManager, firstPlaybackID, fixtureBytes)

	StopServer()
	waitForServerStatus(t, "stopped")
	db_bridge.CurrLocalFiles = mo.None[[]*anime.LocalFile]()
	db_bridge.CurrLocalFilesDbId = 0
	startRecoveryTestServer(t, dataDir, cacheDir)

	restartedApp := currentRecoveryTestApp(t)
	reloadedFiles, _, err := db_bridge.GetLocalFiles(restartedApp.Database)
	if err != nil {
		t.Fatalf("reload local library after server restart: %v", err)
	}
	if len(reloadedFiles) != 1 || reloadedFiles[0].Path != fixturePath {
		t.Fatalf("local library row did not survive server restart: %+v", reloadedFiles)
	}
	restartedManager := configureRecoveryTestPlayback(restartedApp)
	if err := RestorePlaybackResume(checkpointID, "restored-webview-client"); err != nil {
		t.Fatalf("restore local playback after server restart: %v", err)
	}
	restoredPlaybackID, restoredClientID, ok := restartedManager.GetCurrentPlaybackIdentity()
	if !ok || restoredPlaybackID == firstPlaybackID {
		t.Fatalf("restore did not open a fresh local playback stream: id=%q active=%t", restoredPlaybackID, ok)
	}
	if restoredClientID != "restored-webview-client" {
		t.Fatalf("restored stream is bound to client %q", restoredClientID)
	}
	assertRecoveryStreamServesFixture(t, restartedManager, restoredPlaybackID, fixtureBytes)

	refreshedURL := fmt.Sprintf("http://127.0.0.1/api/v1/directstream/stream?id=%s", restoredPlaybackID)
	if _, err := RefreshPlaybackResume(checkpointID, refreshedURL); err != nil {
		t.Fatalf("refresh checkpoint after source reopened: %v", err)
	}
	if got, _, ok := restartedManager.GetCurrentPlaybackIdentity(); !ok || got != restoredPlaybackID {
		t.Fatal("refreshing the checkpoint disturbed the active restored stream")
	}
}

func TestHTTPPlaybackResumeReopensURLAndNakamaSourcesAfterServerRestart(t *testing.T) {
	for _, sourceType := range []string{"url", "nakama"} {
		t.Run(sourceType, func(t *testing.T) {
			dataDir := filepath.Join(t.TempDir(), "data")
			cacheDir := filepath.Join(t.TempDir(), "cache")
			fixtureBytes := []byte("Seanime Android TV HTTP playback recovery fixture")
			const nakamaToken = "nakama-recovery-test-token"
			var sourceRequests atomic.Int64
			var authenticatedNakamaRequests atomic.Int64
			remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				sourceRequests.Add(1)
				if r.Header.Get("X-Seanime-Nakama-Token") == nakamaToken {
					authenticatedNakamaRequests.Add(1)
				}
				w.Header().Set("Content-Type", "video/mp4")
				w.Header().Set("Accept-Ranges", "bytes")
				start, end := 0, len(fixtureBytes)-1
				if rangeHeader := r.Header.Get("Range"); rangeHeader != "" {
					if _, err := fmt.Sscanf(rangeHeader, "bytes=%d-%d", &start, &end); err != nil || start < 0 || end < start || end >= len(fixtureBytes) {
						http.Error(w, "invalid test range", http.StatusRequestedRangeNotSatisfiable)
						return
					}
					w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(fixtureBytes)))
					w.Header().Set("Content-Length", fmt.Sprint(end-start+1))
					w.WriteHeader(http.StatusPartialContent)
				} else {
					w.Header().Set("Content-Length", fmt.Sprint(len(fixtureBytes)))
				}
				if r.Method != http.MethodHead {
					_, _ = w.Write(fixtureBytes[start : end+1])
				}
			}))
			defer remote.Close()
			streamURL := remote.URL + "/episode.mp4"

			SetAppInForeground(false)
			t.Cleanup(func() {
				StopServer()
				SetAppInForeground(true)
			})
			startRecoveryTestServer(t, dataDir, cacheDir)

			firstApp := currentRecoveryTestApp(t)
			firstManager := configureRecoveryTestPlayback(firstApp)
			if sourceType == "nakama" {
				err := firstManager.PlayNakamaStream(t.Context(), directstream.PlayNakamaStreamOptions{
					StreamUrl: streamURL, MediaId: playbackRecoveryMediaID, AnidbEpisode: "1",
					Media: recoveryPlaybackMedia(), NakamaHostPassword: nakamaToken, ClientId: "first-webview-client",
				})
				if err != nil {
					t.Fatalf("start initial Nakama playback: %v", err)
				}
			} else {
				err := firstManager.PlayUrlStream(t.Context(), directstream.PlayUrlStreamOptions{
					StreamUrl: streamURL, Media: recoveryPlaybackMedia(), AnidbEpisode: "1", ClientId: "first-webview-client",
				})
				if err != nil {
					t.Fatalf("start initial URL playback: %v", err)
				}
			}

			firstPlaybackID, _, ok := waitForRecoveryPlayback(t, firstManager)
			if !ok {
				t.Fatal("initial HTTP playback did not become active")
			}
			firstURL := fmt.Sprintf("http://127.0.0.1/api/v1/directstream/stream?id=%s", firstPlaybackID)
			checkpointID, err := CapturePlaybackResume(firstURL)
			if err != nil {
				t.Fatalf("capture %s playback source: %v", sourceType, err)
			}
			checkpoint, err := readPlaybackCheckpoint(firstApp.Config.Data.AppDataDir, checkpointID)
			if err != nil {
				t.Fatalf("read %s playback checkpoint: %v", sourceType, err)
			}
			if sourceType == "nakama" && checkpoint.Source.NakamaToken != nakamaToken {
				t.Fatalf("Nakama source credential was not checkpointed: %q", checkpoint.Source.NakamaToken)
			}
			assertRecoveryStreamServesFixture(t, firstManager, firstPlaybackID, fixtureBytes)
			requestsBeforeRestart := sourceRequests.Load()
			authenticatedRequestsBeforeRestart := authenticatedNakamaRequests.Load()

			StopServer()
			waitForServerStatus(t, "stopped")
			startRecoveryTestServer(t, dataDir, cacheDir)

			restartedApp := currentRecoveryTestApp(t)
			restartedManager := configureRecoveryTestPlayback(restartedApp)
			if err := RestorePlaybackResume(checkpointID, "restored-webview-client"); err != nil {
				t.Fatalf("restore %s playback after server restart: %v", sourceType, err)
			}
			restoredPlaybackID, restoredClientID, ok := waitForRecoveryPlayback(t, restartedManager)
			if !ok || restoredPlaybackID == firstPlaybackID {
				t.Fatalf("restore did not open a fresh %s stream: id=%q active=%t", sourceType, restoredPlaybackID, ok)
			}
			if restoredClientID != "restored-webview-client" {
				t.Fatalf("restored %s stream is bound to client %q", sourceType, restoredClientID)
			}
			assertRecoveryStreamServesFixture(t, restartedManager, restoredPlaybackID, fixtureBytes)
			if sourceRequests.Load() <= requestsBeforeRestart {
				t.Fatalf("restored %s source did not make a new HTTP request", sourceType)
			}
			if sourceType == "nakama" && authenticatedNakamaRequests.Load() <= authenticatedRequestsBeforeRestart {
				t.Fatal("restored Nakama source did not forward its host credential")
			}

			restoredURL := fmt.Sprintf("http://127.0.0.1/api/v1/directstream/stream?id=%s", restoredPlaybackID)
			if _, err := RefreshPlaybackResume(checkpointID, restoredURL); err != nil {
				t.Fatalf("refresh %s checkpoint after source reopened: %v", sourceType, err)
			}
		})
	}
}

func startRecoveryTestServer(t *testing.T, dataDir, cacheDir string) {
	t.Helper()
	SetAppInForeground(false)
	StartServer(dataDir, cacheDir, unusedLocalPort(t))
	if !WaitForServer(60_000) {
		t.Fatalf("server did not become ready (status=%s, error=%s)", ServerStatus(), ServerError())
	}
}

func currentRecoveryTestApp(t *testing.T) *core.App {
	t.Helper()
	serverLifecycle.Lock()
	defer serverLifecycle.Unlock()
	if serverLifecycle.instance == nil || serverLifecycle.instance.app == nil {
		t.Fatal("ready server has no app instance")
	}
	return serverLifecycle.instance.app
}

func configureRecoveryTestPlayback(app *core.App) *directstream.Manager {
	media := recoveryPlaybackMedia()
	collection := &anilist.AnimeCollection{MediaListCollection: &anilist.AnimeCollection_MediaListCollection{
		Lists: []*anilist.AnimeCollection_MediaListCollection_Lists{{
			Entries: []*anilist.AnimeCollection_MediaListCollection_Lists_Entries{{Media: media}},
		}},
	}}
	metadataProviderRef := util.NewRef[metadata_provider.Provider](recoveryMetadataProvider{
		cache: result.NewBoundedCache[string, *metadata.AnimeMetadata](2),
	})
	manager := directstream.NewManager(directstream.NewManagerOptions{
		Logger:              app.Logger,
		MetadataProviderRef: metadataProviderRef,
		PlatformRef:         app.AnilistPlatformRef,
		IsOfflineRef:        util.NewRef(false),
	})
	manager.SetAnimeCollection(collection)
	manager.SetPlaybackTarget(directstream.PlaybackTargetMpvCore)
	app.DirectStreamManager = manager
	return manager
}

func recoveryPlaybackMedia() *anilist.BaseAnime {
	format := anilist.MediaFormatTv
	status := anilist.MediaStatusReleasing
	episodes := 1
	return &anilist.BaseAnime{
		ID:       playbackRecoveryMediaID,
		Format:   &format,
		Status:   &status,
		Episodes: &episodes,
		Title:    &anilist.BaseAnime_Title{},
	}
}

func waitForRecoveryPlayback(t *testing.T, manager *directstream.Manager) (string, string, bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if playbackID, clientID, ok := manager.GetCurrentPlaybackIdentity(); ok {
			return playbackID, clientID, true
		}
		time.Sleep(20 * time.Millisecond)
	}
	return "", "", false
}

func assertRecoveryStreamServesFixture(t *testing.T, manager *directstream.Manager, playbackID string, expected []byte) {
	t.Helper()
	request := httptest.NewRequest("GET", "/api/v1/directstream/stream?id="+playbackID, nil)
	request.Header.Set("Range", fmt.Sprintf("bytes=0-%d", len(expected)-1))
	response := httptest.NewRecorder()
	manager.ServeEchoStream().ServeHTTP(response, request)
	if response.Code != http.StatusPartialContent {
		t.Fatalf("reopened stream returned HTTP %d: %s", response.Code, response.Body.String())
	}
	if !bytes.Equal(response.Body.Bytes(), expected) {
		t.Fatalf("reopened stream returned fixture bytes %q, want %q", response.Body.Bytes(), expected)
	}
	if manager.GetPlaybackTarget() != directstream.PlaybackTargetMpvCore {
		t.Fatalf("playback target changed unexpectedly: %s", manager.GetPlaybackTarget())
	}
}
