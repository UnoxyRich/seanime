package debrid_client

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"seanime/internal/androidtvstorage"
	"seanime/internal/database/models"
	"seanime/internal/debrid/debrid"
	"seanime/internal/events"
	"seanime/internal/testutil"
	"seanime/internal/util"
	"seanime/internal/util/result"

	"github.com/samber/mo"
	"github.com/stretchr/testify/require"
)

type debridSAFWrite struct {
	path string
	data bytes.Buffer
}

type debridSAFAdapter struct {
	mu           sync.Mutex
	files        map[string][]byte
	writes       map[string]*debridSAFWrite
	directories  []string
	nextHandle   int
	cancelled    int
	mkdirErr     error
	finishErr    error
	cancelOnCopy context.CancelFunc
}

func newDebridSAFAdapter(t *testing.T) *debridSAFAdapter {
	t.Helper()
	a := &debridSAFAdapter{files: make(map[string][]byte), writes: make(map[string]*debridSAFWrite)}
	androidtvstorage.SetAdapter(a)
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })
	return a
}

func (a *debridSAFAdapter) List(string) (string, error) { return "", errors.New("unexpected list") }
func (a *debridSAFAdapter) Stat(string) (string, error) { return "", errors.New("unexpected stat") }
func (a *debridSAFAdapter) ReadAt(string, int64, int64) (string, error) {
	return "", errors.New("unexpected read")
}
func (a *debridSAFAdapter) URI(path string) (string, error) { return "content://test" + path, nil }
func (a *debridSAFAdapter) Remove(path string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.files, path)
	return nil
}
func (a *debridSAFAdapter) MkdirAll(path string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.mkdirErr != nil {
		return a.mkdirErr
	}
	a.directories = append(a.directories, path)
	return nil
}
func (a *debridSAFAdapter) BeginWrite(path string, truncate bool) (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if !truncate {
		return "", errors.New("download did not request transactional replacement")
	}
	a.nextHandle++
	handle := fmt.Sprint(a.nextHandle)
	a.writes[handle] = &debridSAFWrite{path: path}
	return handle, nil
}
func (a *debridSAFAdapter) WriteChunk(handle, data string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	payload, err := base64.StdEncoding.DecodeString(data)
	if err != nil {
		return err
	}
	_, err = a.writes[handle].data.Write(payload)
	if a.cancelOnCopy != nil {
		a.cancelOnCopy()
		a.cancelOnCopy = nil
	}
	return err
}
func (a *debridSAFAdapter) FinishWrite(handle string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.finishErr != nil {
		return a.finishErr
	}
	write := a.writes[handle]
	a.files[write.path] = append([]byte(nil), write.data.Bytes()...)
	delete(a.writes, handle)
	return nil
}
func (a *debridSAFAdapter) CancelWrite(handle string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.writes, handle)
	a.cancelled++
	return nil
}
func (a *debridSAFAdapter) file(path string) []byte {
	a.mu.Lock()
	defer a.mu.Unlock()
	return append([]byte(nil), a.files[path]...)
}

func TestAndroidTVDownloadStagesInAppTempAndCommitsToSAF(t *testing.T) {
	setMobileDownload(t, true)
	tempRoot := t.TempDir()
	t.Setenv("TMPDIR", tempRoot)
	storage := newDebridSAFAdapter(t)
	const destination = "/androidtv/usb/Anime"
	body := bytes.Repeat([]byte("episode"), 50_000)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Disposition", `attachment; filename="Episode 01.mkv"`)
		w.Header().Set("Content-Type", "video/x-matroska")
		if r.Method != http.MethodHead {
			_, _ = w.Write(body)
		}
	}))
	t.Cleanup(server.Close)
	logger := util.NewLogger()
	repo := &Repository{logger: logger, wsEventManager: events.NewMockWSEventManager(logger)}
	require.True(t, repo.downloadFile(context.Background(), "torrent-1", server.URL+"/file", destination, result.NewMap[string, downloadStatus]()))
	require.Equal(t, body, storage.file(destination+"/Episode 01.mkv"))
	require.Contains(t, storage.directories, destination)
	require.Empty(t, storage.writes)
	entries, err := os.ReadDir(tempRoot)
	require.NoError(t, err)
	require.Empty(t, entries, "app temporary downloads must be cleaned after SAF commit")
}

func TestAndroidTVArchiveDownloadPreservesFolderAndAllContents(t *testing.T) {
	setMobileDownload(t, true)
	storage := newDebridSAFAdapter(t)
	archive := writeZipFixture(t, map[string]string{
		"hash/Anime/Episode 01.mkv":   "episode one",
		"hash/Anime/Episode 02.mkv":   "episode two",
		"hash/Anime/Subtitles/01.ass": "subtitle",
	})
	body, err := os.ReadFile(archive)
	require.NoError(t, err)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Disposition", `attachment; filename="Anime.zip"`)
		w.Header().Set("Content-Type", "application/zip")
		if r.Method != http.MethodHead {
			_, _ = w.Write(body)
		}
	}))
	t.Cleanup(server.Close)
	logger := util.NewLogger()
	repo := &Repository{logger: logger, wsEventManager: events.NewMockWSEventManager(logger)}
	require.True(t, repo.downloadFile(context.Background(), "torrent-1", server.URL+"/archive.zip", "/androidtv/usb/library", result.NewMap[string, downloadStatus]()))
	require.Equal(t, []byte("episode one"), storage.file("/androidtv/usb/library/Anime/Episode 01.mkv"))
	require.Equal(t, []byte("episode two"), storage.file("/androidtv/usb/library/Anime/Episode 02.mkv"))
	require.Equal(t, []byte("subtitle"), storage.file("/androidtv/usb/library/Anime/Subtitles/01.ass"))
	require.Len(t, storage.files, 3, "the download archive and wrapper hash directory must not be copied")
	require.Empty(t, storage.writes)
}

func TestAndroidTVSAFCopyCancellationPreservesExistingFile(t *testing.T) {
	storage := newDebridSAFAdapter(t)
	const destination = "/androidtv/usb/library"
	storage.files[destination+"/Episode.mkv"] = []byte("existing episode")
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	storage.cancelOnCopy = cancel
	source := t.TempDir()
	sourceFile := filepath.Join(source, "Episode.mkv")
	require.NoError(t, os.WriteFile(sourceFile, bytes.Repeat([]byte("new episode"), 100_000), 0o600))
	err := moveDownloadedContentsToContext(ctx, source, destination, true)
	require.ErrorIs(t, err, context.Canceled)
	require.Equal(t, []byte("existing episode"), storage.file(destination+"/Episode.mkv"))
	require.FileExists(t, sourceFile)
	require.Empty(t, storage.writes)
	require.Equal(t, 1, storage.cancelled)
}

func TestAndroidTVFailedDestinationRemainsQueuedAndResumes(t *testing.T) {
	for _, failure := range []string{"revoked grant", "commit failed"} {
		t.Run(failure, func(t *testing.T) {
			initTestDownload(t, 1, func(int) time.Duration { return 0 })
			initTestDownloadManager(t)
			setMobileDownload(t, true)
			storage := newDebridSAFAdapter(t)
			storageError := errors.New("USB unavailable")
			if failure == "revoked grant" {
				storage.mkdirErr = storageError
			} else {
				storage.finishErr = storageError
			}
			const destination = "/androidtv/usb/library/Anime"
			storage.files[destination+"/Episode.mkv"] = []byte("old episode")
			logger := util.NewLogger()
			database := testutil.NewTestEnv(t).MustNewDatabase(logger)
			ws := events.NewMockWSEventManager(logger)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "video/x-matroska")
				if r.Method != http.MethodHead {
					_, _ = w.Write([]byte("complete episode"))
				}
			}))
			t.Cleanup(server.Close)
			require.NoError(t, database.InsertDebridTorrentItem(&models.DebridTorrentItem{
				TorrentItemID: "torrent-1", Destination: destination, Provider: "fake-provider", MediaId: 21,
			}))
			require.NoError(t, database.InsertAutoDownloaderItem(&models.AutoDownloaderItem{
				RuleID: 1, MediaID: 21, Episode: 1, Hash: "abc", TorrentName: "test",
			}))
			provider := &fakeDebridProvider{
				getTorrent: func(string) (*debrid.TorrentItem, error) {
					return &debrid.TorrentItem{ID: "torrent-1", Name: "test", Hash: "ABC", IsReady: true}, nil
				},
				getTorrentDownloadUrl: func(debrid.DownloadTorrentOptions) (string, error) { return server.URL + "/Episode.mkv", nil },
			}
			repo := &Repository{provider: mo.Some[debrid.Provider](provider), logger: logger, db: database, wsEventManager: ws, ctxMap: result.NewMap[string, context.CancelFunc]()}
			repo.processQueuedDownloads(provider)
			require.Eventually(t, func() bool { return !repo.IsDownloadActive("torrent-1") && hasDebridDownloadStatus(ws, "cancelled") }, 2*time.Second, 10*time.Millisecond)
			queued, err := database.GetDebridTorrentItems()
			require.NoError(t, err)
			require.Len(t, queued, 1)
			require.Equal(t, destination, queued[0].Destination)
			require.Equal(t, []byte("old episode"), storage.file(destination+"/Episode.mkv"))
			autoItem, err := database.GetAutoDownloaderItem(1)
			require.NoError(t, err)
			require.False(t, autoItem.Downloaded)
			storage.mu.Lock()
			storage.mkdirErr, storage.finishErr = nil, nil
			storage.mu.Unlock()
			repo.processQueuedDownloads(provider)
			require.Eventually(t, func() bool {
				items, err := database.GetDebridTorrentItems()
				return err == nil && len(items) == 0 && !repo.IsDownloadActive("torrent-1")
			}, 2*time.Second, 10*time.Millisecond)
			require.Equal(t, []byte("complete episode"), storage.file(destination+"/Episode.mkv"))
			autoItem, err = database.GetAutoDownloaderItem(1)
			require.NoError(t, err)
			require.True(t, autoItem.Downloaded)
			require.True(t, hasDebridDownloadStatus(ws, "completed"))
			require.Empty(t, storage.writes)
		})
	}
}

func TestAndroidTVDestinationStagesLocallyEvenOffMobile(t *testing.T) {
	setMobileDownload(t, false)
	tempRoot := t.TempDir()
	t.Setenv("TMPDIR", tempRoot)
	tmp, err := createDownloadTempDir("/androidtv/usb/library")
	require.NoError(t, err)
	t.Cleanup(func() { _ = os.RemoveAll(tmp) })
	require.Equal(t, tempRoot, filepath.Dir(tmp))
}

func TestCancelledArchiveExtractionDoesNotStart(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	for _, extract := range []func(context.Context, string, string) (string, error){unzipFileContext, unrarFileContext} {
		destination := t.TempDir()
		_, err := extract(ctx, "unused archive", destination)
		require.ErrorIs(t, err, context.Canceled)
		entries, err := os.ReadDir(destination)
		require.NoError(t, err)
		require.Empty(t, entries)
	}
}
