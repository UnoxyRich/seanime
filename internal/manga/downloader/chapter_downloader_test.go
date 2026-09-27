package chapter_downloader

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"seanime/internal/database/db"
	"seanime/internal/database/models"
	"seanime/internal/events"
	hibikemanga "seanime/internal/extension/hibike/manga"
	"seanime/internal/testutil"

	"github.com/goccy/go-json"
	"github.com/stretchr/testify/require"
)

func newTestDownloader(t *testing.T) (*Downloader, *db.Database, string) {
	t.Helper()

	env := testutil.NewTestEnv(t)
	logger := env.Logger()
	database := env.MustNewDatabase(logger)
	downloadDir := env.MustMkdir("downloads")

	downloader := NewDownloader(&NewDownloaderOptions{
		Logger:         logger,
		WSEventManager: events.NewMockWSEventManager(logger),
		Database:       database,
		DownloadDir:    downloadDir,
	})

	return downloader, database, downloadDir
}

func newTestPNG(t *testing.T) []byte {
	t.Helper()

	var buf bytes.Buffer
	img := image.NewRGBA(image.Rect(0, 0, 1, 1))
	img.Set(0, 0, color.RGBA{R: 255, G: 128, B: 64, A: 255})
	require.NoError(t, png.Encode(&buf, img))

	return buf.Bytes()
}

func TestQueueAddsItemToDatabase(t *testing.T) {
	downloader, database, _ := newTestDownloader(t)

	pages := []*hibikemanga.ChapterPage{{
		Index: 0,
		URL:   "https://example.com/01.png",
	}}
	id := DownloadID{
		Provider:      "test-provider",
		MediaId:       101517,
		ChapterId:     "chapter-1",
		ChapterNumber: "1",
	}

	err := downloader.AddToQueue(DownloadOptions{
		DownloadID: id,
		Pages:      pages,
		StartNow:   false,
	})
	require.NoError(t, err)

	next, err := database.GetNextChapterDownloadQueueItem()
	require.NoError(t, err)
	require.NotNil(t, next)
	require.Equal(t, id.Provider, next.Provider)
	require.Equal(t, id.MediaId, next.MediaID)
	require.Equal(t, id.ChapterId, next.ChapterID)
	require.Equal(t, id.ChapterNumber, next.ChapterNumber)
	require.Equal(t, string(QueueStatusNotStarted), next.Status)
	require.NotEmpty(t, next.PageData)
}

func TestQueueMarksMalformedItemErroredAndContinues(t *testing.T) {
	downloader, database, _ := newTestDownloader(t)
	malformed := &models.ChapterDownloadQueueItem{
		Provider:      "test-provider",
		MediaID:       101517,
		ChapterID:     "malformed",
		ChapterNumber: "1",
		PageData:      []byte("not-json"),
		Status:        string(QueueStatusNotStarted),
	}
	validPageData, err := json.Marshal([]*hibikemanga.ChapterPage{})
	require.NoError(t, err)
	valid := &models.ChapterDownloadQueueItem{
		Provider:      "test-provider",
		MediaID:       101517,
		ChapterID:     "valid",
		ChapterNumber: "2",
		PageData:      validPageData,
		Status:        string(QueueStatusNotStarted),
	}
	require.NoError(t, database.InsertChapterDownloadQueueItem(malformed))
	require.NoError(t, database.InsertChapterDownloadQueueItem(valid))

	require.True(t, downloader.Run())
	items, err := database.GetChapterDownloadQueue()
	require.NoError(t, err)
	require.Len(t, items, 2)
	require.Equal(t, QueueStatusErrored, QueueStatus(items[0].Status))
	require.Equal(t, QueueStatusDownloading, QueueStatus(items[1].Status))
	current, ok := downloader.queue.GetCurrent()
	require.True(t, ok)
	require.Equal(t, "valid", current.ChapterId)
	downloader.Stop()
}

func TestDownloadChapterImagesWritesRegistry(t *testing.T) {
	downloader, database, downloadDir := newTestDownloader(t)

	imageData := newTestPNG(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write(imageData)
	}))
	defer server.Close()

	pages := []*hibikemanga.ChapterPage{{
		Index: 0,
		URL:   server.URL + "/page.png",
	}}
	id := DownloadID{
		Provider:      "test-provider",
		MediaId:       101517,
		ChapterId:     "chapter-1",
		ChapterNumber: "1",
	}

	err := downloader.AddToQueue(DownloadOptions{
		DownloadID: id,
		Pages:      pages,
		StartNow:   false,
	})
	require.NoError(t, err)

	require.NoError(t, database.UpdateChapterDownloadQueueItemStatus(id.Provider, id.MediaId, id.ChapterId, string(QueueStatusDownloading)))
	downloader.queue.current = &QueueInfo{
		DownloadID: id,
		Pages:      pages,
		Status:     QueueStatusDownloading,
	}

	err = downloader.downloadChapterImages(downloader.queue.current)
	require.NoError(t, err)

	chapterDir := filepath.Join(downloadDir, FormatChapterDirName(id.Provider, id.MediaId, id.ChapterId, id.ChapterNumber))
	registryPath := filepath.Join(chapterDir, "registry.json")
	registryBytes, err := os.ReadFile(registryPath)
	require.NoError(t, err)

	var registry Registry
	require.NoError(t, json.Unmarshal(registryBytes, &registry))
	require.Len(t, registry, 1)
	pageInfo, ok := registry[0]
	require.True(t, ok)
	require.Equal(t, "01.png", pageInfo.Filename)
	require.Equal(t, server.URL+"/page.png", pageInfo.OriginalURL)

	_, err = os.Stat(filepath.Join(chapterDir, pageInfo.Filename))
	require.NoError(t, err)

	queueItems, err := database.GetChapterDownloadQueue()
	require.NoError(t, err)
	require.Empty(t, queueItems)
}

func TestPauseCancelsAndResumesMangaDownloadWithoutDiscardingCompletedPages(t *testing.T) {
	downloader, database, downloadDir := newTestDownloader(t)
	imageData := newTestPNG(t)
	var firstPageRequests atomic.Int32
	var slowPageRequests atomic.Int32
	slowPageStarted := make(chan struct{}, 1)
	releaseSlowPage := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/first.png":
			firstPageRequests.Add(1)
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write(imageData)
		case "/slow.png":
			if slowPageRequests.Add(1) == 1 {
				slowPageStarted <- struct{}{}
				select {
				case <-r.Context().Done():
					return
				case <-releaseSlowPage:
				}
			}
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write(imageData)
		default:
			http.NotFound(w, r)
		}
	}))
	defer func() {
		close(releaseSlowPage)
		server.Close()
	}()

	id := DownloadID{
		Provider:      "test-provider",
		MediaId:       101517,
		ChapterId:     "chapter-resume",
		ChapterNumber: "2",
	}
	pages := []*hibikemanga.ChapterPage{
		{Index: 0, URL: server.URL + "/first.png"},
		{Index: 1, URL: server.URL + "/slow.png"},
	}
	require.NoError(t, downloader.AddToQueue(DownloadOptions{DownloadID: id, Pages: pages}))
	downloader.Start()
	defer downloader.Stop()
	downloader.Run()

	select {
	case <-slowPageStarted:
	case <-time.After(10 * time.Second):
		t.Fatal("download did not reach the second page")
	}
	require.True(t, downloader.PauseForBackground())

	chapterDir := filepath.Join(downloadDir, FormatChapterDirName(id.Provider, id.MediaId, id.ChapterId, id.ChapterNumber))
	progressPath := downloadProgressPath(chapterDir)
	waitUntilChapter(t, 10*time.Second, func() bool {
		items, err := database.GetChapterDownloadQueue()
		if err != nil || len(items) != 1 || items[0].Status != string(QueueStatusBackgroundPaused) {
			return false
		}
		_, current := downloader.queue.GetCurrent()
		return !current && firstPageRequests.Load() == 1
	})
	progress := loadDownloadProgress(chapterDir, pages)
	require.Len(t, progress, 1)
	require.Contains(t, progress, 0)
	require.FileExists(t, filepath.Join(chapterDir, progress[0].Filename))
	require.EqualValues(t, 1, slowPageRequests.Load(), "canceling the active request should not silently retry it")

	downloader.Run()
	waitUntilChapter(t, 10*time.Second, func() bool {
		items, err := database.GetChapterDownloadQueue()
		return err == nil && len(items) == 0
	})
	require.EqualValues(t, 1, firstPageRequests.Load(), "completed pages should not be downloaded again after resume")
	require.EqualValues(t, 2, slowPageRequests.Load())
	require.FileExists(t, filepath.Join(chapterDir, "registry.json"))
	require.NoFileExists(t, progressPath)
}

func waitUntilChapter(t *testing.T, timeout time.Duration, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(25 * time.Millisecond)
	}
	t.Fatal("condition did not become true before timeout")
}
