package debrid_client

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"seanime/internal/database/models"
	"seanime/internal/debrid/debrid"
	"seanime/internal/events"
	"seanime/internal/testutil"
	"seanime/internal/util"
	"seanime/internal/util/result"

	"github.com/samber/mo"
	"github.com/stretchr/testify/require"
)

func TestBackgroundSuspensionCancelsDownloadsAndResumesDestinationIntent(t *testing.T) {
	for _, mode := range []string{"queued", "manual", "manual override"} {
		t.Run(mode, func(t *testing.T) {
			initTestDownload(t, 1, func(int) time.Duration { return 0 })
			initTestDownloadManager(t)
			setMobileDownload(t, true)
			logger := util.NewLogger()
			database := testutil.NewTestEnv(t).MustNewDatabase(logger)
			ws := events.NewMockWSEventManager(logger)
			destination := t.TempDir()
			const payload = "complete episode after foreground resume"
			started, canceled := make(chan struct{}), make(chan struct{})
			var getCalls, headCalls, lookupCalls, urlCalls atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
				w.Header().Set("Content-Type", "video/x-matroska")
				w.Header().Set("Content-Disposition", `attachment; filename="Episode.mkv"`)
				if request.Method == http.MethodHead {
					headCalls.Add(1)
					return
				}
				if getCalls.Add(1) == 1 {
					_, _ = w.Write([]byte("partial episode"))
					w.(http.Flusher).Flush()
					close(started)
					<-request.Context().Done()
					close(canceled)
					return
				}
				_, _ = w.Write([]byte(payload))
			}))
			t.Cleanup(server.Close)
			item := debrid.TorrentItem{ID: "torrent-1", Name: "Episode", Hash: "abc", IsReady: true}
			provider := &fakeDebridProvider{
				getTorrent: func(string) (*debrid.TorrentItem, error) {
					lookupCalls.Add(1)
					return &item, nil
				},
				getTorrentDownloadUrl: func(debrid.DownloadTorrentOptions) (string, error) {
					urlCalls.Add(1)
					return server.URL + "/Episode.mkv", nil
				},
			}
			repo := &Repository{
				provider: mo.Some[debrid.Provider](provider), logger: logger, db: database,
				wsEventManager: ws, ctxMap: result.NewMap[string, context.CancelFunc](),
				settings: &models.DebridSettings{Enabled: true},
			}
			t.Cleanup(func() { repo.SetDownloadsSuspended(true) })
			if mode != "manual" {
				queuedDestination, queuedProvider := destination, "fake-provider"
				if mode == "manual override" {
					queuedDestination, queuedProvider = t.TempDir(), "previous-provider"
				}
				require.NoError(t, database.InsertDebridTorrentItem(&models.DebridTorrentItem{
					TorrentItemID: item.ID, Destination: queuedDestination, Provider: queuedProvider, MediaId: 21,
				}))
				require.NoError(t, database.InsertAutoDownloaderItem(&models.AutoDownloaderItem{
					RuleID: 1, MediaID: 21, Episode: 1, Hash: item.Hash, TorrentName: item.Name,
				}))
			}
			if mode == "queued" {
				repo.processQueuedDownloads(provider)
			} else {
				require.NoError(t, repo.DownloadTorrent(item, destination))
			}
			select {
			case <-started:
			case <-time.After(2 * time.Second):
				t.Fatal("download request did not start")
			}
			repo.SetDownloadsSuspended(true)
			select {
			case <-canceled:
			case <-time.After(2 * time.Second):
				t.Fatal("background suspension did not cancel the in-flight request")
			}
			require.Eventually(t, func() bool { return !repo.IsDownloadActive(item.ID) }, 2*time.Second, 10*time.Millisecond)
			queued, err := database.GetDebridTorrentItems()
			require.NoError(t, err)
			require.Len(t, queued, 1)
			require.Equal(t, destination, queued[0].Destination)
			require.Equal(t, "fake-provider", queued[0].Provider)
			if mode != "manual" {
				require.Equal(t, 21, queued[0].MediaId, "suspension must retain existing auto-download metadata")
				autoItem, err := database.GetAutoDownloaderItem(1)
				require.NoError(t, err)
				require.False(t, autoItem.Downloaded)
			}
			require.NoFileExists(t, filepath.Join(destination, "Episode.mkv"))
			require.False(t, hasDebridDownloadStatus(ws, "completed"))
			beforeGets, beforeHeads := getCalls.Load(), headCalls.Load()
			beforeLookups, beforeURLs := lookupCalls.Load(), urlCalls.Load()
			repo.processQueuedDownloads(provider)
			require.ErrorIs(t, repo.DownloadTorrent(item, destination), ErrDownloadsSuspended)
			_, err = repo.AddAndQueueTorrent(debrid.AddTorrentOptions{}, destination, 21)
			require.ErrorIs(t, err, ErrDownloadsSuspended)
			// Settings/provider refresh uses this entry point as well. It must not
			// restart the loop while the native host still holds the pause gate.
			repo.startOrStopDownloadLoop()
			require.Nil(t, repo.downloadLoopCancelFunc)
			require.Equal(t, beforeGets, getCalls.Load())
			require.Equal(t, beforeHeads, headCalls.Load())
			require.Equal(t, beforeLookups, lookupCalls.Load())
			require.Equal(t, beforeURLs, urlCalls.Load())
			repo.SetDownloadsSuspended(false)
			require.Eventually(t, func() bool {
				content, err := os.ReadFile(filepath.Join(destination, "Episode.mkv"))
				return err == nil && string(content) == payload && !repo.IsDownloadActive(item.ID)
			}, 2*time.Second, 10*time.Millisecond)
			require.Eventually(t, func() bool {
				queued, err := database.GetDebridTorrentItems()
				return err == nil && len(queued) == 0
			}, 2*time.Second, 10*time.Millisecond)
			require.Equal(t, int32(2), getCalls.Load())
			require.True(t, hasDebridDownloadStatus(ws, "completed"))
			if mode != "manual" {
				autoItem, err := database.GetAutoDownloaderItem(1)
				require.NoError(t, err)
				require.True(t, autoItem.Downloaded)
			}
		})
	}
}

func TestBackgroundSuspensionCancelsFilenameLookup(t *testing.T) {
	initTestDownload(t, 1, func(int) time.Duration { return 0 })
	initTestDownloadManager(t)
	logger := util.NewLogger()
	ws := events.NewMockWSEventManager(logger)
	started, canceled := make(chan struct{}), make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if request.Method == http.MethodHead {
			close(started)
			<-request.Context().Done()
			close(canceled)
			return
		}
		_, _ = w.Write([]byte("complete body"))
	}))
	t.Cleanup(server.Close)
	provider := &fakeDebridProvider{getTorrentDownloadUrl: func(debrid.DownloadTorrentOptions) (string, error) {
		return server.URL + "/Episode.mkv", nil
	}}
	repo := &Repository{provider: mo.Some[debrid.Provider](provider), logger: logger, wsEventManager: ws}
	t.Cleanup(func() { repo.SetDownloadsSuspended(true) })
	destination := t.TempDir()
	require.NoError(t, repo.DownloadTorrent(debrid.TorrentItem{ID: "torrent-head", Name: "Episode"}, destination))
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("filename lookup did not start")
	}
	repo.SetDownloadsSuspended(true)
	select {
	case <-canceled:
	case <-time.After(2 * time.Second):
		t.Fatal("filename lookup ignored background cancellation")
	}
	require.Eventually(t, func() bool { return !repo.IsDownloadActive("torrent-head") }, 2*time.Second, 10*time.Millisecond)
	require.NoFileExists(t, filepath.Join(destination, "Episode.mkv"))
	require.False(t, hasDebridDownloadStatus(ws, "completed"))
}

type backgroundDownloadProvider struct {
	*fakeDebridProvider
	settingsRead chan struct{}
}

func (p *backgroundDownloadProvider) GetSettings() debrid.Settings {
	select {
	case p.settingsRead <- struct{}{}:
	default:
	}
	return p.fakeDebridProvider.GetSettings()
}

func TestBackgroundSuspensionImmediateResumeWakesAfterCanceledSetupFinishes(t *testing.T) {
	for _, mode := range []string{"manual", "queued loop"} {
		t.Run(mode, func(t *testing.T) {
			initTestDownload(t, 1, func(int) time.Duration { return 0 })
			initTestDownloadManager(t)
			logger := util.NewLogger()
			database := testutil.NewTestEnv(t).MustNewDatabase(logger)
			ws := events.NewMockWSEventManager(logger)
			destination := t.TempDir()
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
				if request.Method != http.MethodHead {
					_, _ = w.Write([]byte("resumed episode"))
				}
			}))
			t.Cleanup(server.Close)
			started, release := make(chan struct{}), make(chan struct{})
			var urlCalls atomic.Int32
			item := debrid.TorrentItem{ID: "torrent-delayed-url", Name: "Episode", IsReady: true}
			provider := &backgroundDownloadProvider{
				settingsRead: make(chan struct{}, 1),
				fakeDebridProvider: &fakeDebridProvider{
					getTorrent: func(string) (*debrid.TorrentItem, error) { return &item, nil },
					getTorrentDownloadUrl: func(debrid.DownloadTorrentOptions) (string, error) {
						if urlCalls.Add(1) == 1 {
							close(started)
							<-release
						}
						return server.URL + "/Episode.mkv", nil
					},
				},
			}
			repo := &Repository{
				provider: mo.Some[debrid.Provider](provider), logger: logger, db: database,
				wsEventManager: ws, settings: &models.DebridSettings{Enabled: true},
			}
			t.Cleanup(func() { repo.SetDownloadsSuspended(true) })
			t.Cleanup(func() {
				select {
				case <-release:
				default:
					close(release)
				}
			})
			firstResult := make(chan error, 1)
			if mode == "queued loop" {
				require.NoError(t, database.InsertDebridTorrentItem(&models.DebridTorrentItem{
					TorrentItemID: item.ID, Destination: destination, Provider: "fake-provider", MediaId: 21,
				}))
				repo.startOrStopDownloadLoop()
			} else {
				go func() { firstResult <- repo.DownloadTorrent(item, destination) }()
			}
			select {
			case <-started:
			case <-time.After(2 * time.Second):
				t.Fatal("provider URL lookup did not start")
			}
			for len(provider.settingsRead) > 0 {
				<-provider.settingsRead
			}
			repo.downloadMu.Lock()
			oldWake := repo.downloadLoopWake
			repo.downloadMu.Unlock()
			repo.SetDownloadsSuspended(true)
			repo.SetDownloadsSuspended(false)
			repo.downloadMu.Lock()
			newWake := repo.downloadLoopWake
			repo.downloadMu.Unlock()
			if mode == "queued loop" {
				require.NotNil(t, oldWake)
				require.NotEqual(t, oldWake, newWake, "the canceled loop must not consume the new loop's wake")
			}
			select {
			case <-provider.settingsRead: // The new loop saw the still-active canceled job.
			case <-time.After(2 * time.Second):
				t.Fatal("foreground queue loop did not restart immediately")
			}
			require.True(t, repo.IsDownloadActive(item.ID))
			require.Equal(t, int32(1), urlCalls.Load())
			close(release)
			if mode == "manual" {
				select {
				case err := <-firstResult:
					require.ErrorIs(t, err, context.Canceled)
				case <-time.After(2 * time.Second):
					t.Fatal("canceled provider lookup did not finish")
				}
			}
			require.Eventually(t, func() bool {
				content, err := os.ReadFile(filepath.Join(destination, "Episode.mkv"))
				queued, queueErr := database.GetDebridTorrentItems()
				return err == nil && string(content) == "resumed episode" && queueErr == nil && len(queued) == 0 && !repo.IsDownloadActive(item.ID)
			}, 2*time.Second, 10*time.Millisecond)
			require.Equal(t, int32(2), urlCalls.Load())
		})
	}
}
