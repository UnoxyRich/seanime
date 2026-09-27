package manga

import (
	"testing"

	"github.com/stretchr/testify/require"
	"seanime/internal/database/models"
	"seanime/internal/events"
	chapter_downloader "seanime/internal/manga/downloader"
	"seanime/internal/testutil"
	"seanime/internal/util"
)

func TestNewDownloaderRestoresBackgroundPausedQueueIntent(t *testing.T) {
	env := testutil.NewTestEnv(t)
	logger := env.Logger()
	database := env.MustNewDatabase(logger)
	downloadDir := env.MustMkdirData("manga-downloads")
	require.NoError(t, database.InsertChapterDownloadQueueItem(&models.ChapterDownloadQueueItem{
		Provider:      "test-provider",
		MediaID:       123,
		ChapterID:     "chapter-1",
		ChapterNumber: "1",
		PageData:      []byte("[]"),
		Status:        "background_paused",
	}))

	downloader := NewDownloader(&NewDownloaderOptions{
		Database:       database,
		Logger:         logger,
		WSEventManager: events.NewMockWSEventManager(logger),
		DownloadDir:    downloadDir,
		IsOfflineRef:   util.NewRef(false),
	})
	downloader.ResumeInterruptedQueue()
	t.Cleanup(downloader.StopChapterDownloadQueue)

	require.Equal(t, string(chapter_downloader.QueueStatusDownloading), queueItemStatus(t, database))

	downloader.StopChapterDownloadQueue()
	require.Equal(t, string(chapter_downloader.QueueStatusNotStarted), queueItemStatus(t, database))
}

func queueItemStatus(t *testing.T, database interface {
	GetChapterDownloadQueue() ([]*models.ChapterDownloadQueueItem, error)
}) string {
	t.Helper()
	items, err := database.GetChapterDownloadQueue()
	require.NoError(t, err)
	require.Len(t, items, 1)
	return items[0].Status
}
