package chapter_downloader

import (
	"context"
	"sync"

	"github.com/goccy/go-json"
	"github.com/rs/zerolog"
	"seanime/internal/database/db"
	"seanime/internal/database/models"
	"seanime/internal/events"
	hibikemanga "seanime/internal/extension/hibike/manga"
	"seanime/internal/util"
)

const (
	QueueStatusNotStarted       QueueStatus = "not_started"
	QueueStatusDownloading      QueueStatus = "downloading"
	QueueStatusErrored          QueueStatus = "errored"
	QueueStatusBackgroundPaused QueueStatus = "background_paused"
)

type (
	// Queue is used to manage the download queue.
	// If feeds the downloader with the next item in the queue.
	Queue struct {
		logger           *zerolog.Logger
		mu               sync.Mutex
		db               *db.Database
		current          *QueueInfo
		runCh            chan *QueueInfo // Channel to tell downloader to run the next item
		active           bool
		backgroundPaused bool
		ctx              context.Context
		cancel           context.CancelFunc
		wsEventManager   events.WSEventManagerInterface
	}

	QueueStatus string

	// QueueInfo stores details about the download progress of a chapter.
	QueueInfo struct {
		DownloadID
		Pages          []*hibikemanga.ChapterPage
		DownloadedUrls []string
		Status         QueueStatus
		ctx            context.Context
		paused         bool
	}
)

func NewQueue(db *db.Database, logger *zerolog.Logger, wsEventManager events.WSEventManagerInterface, runCh chan *QueueInfo) *Queue {
	return &Queue{
		logger:         logger,
		db:             db,
		runCh:          runCh,
		wsEventManager: wsEventManager,
	}
}

// Add adds a chapter to the download queue.
// It tells the queue to download the next item if possible.
func (q *Queue) Add(id DownloadID, pages []*hibikemanga.ChapterPage, runNext bool) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	marshalled, err := json.Marshal(pages)
	if err != nil {
		q.logger.Error().Err(err).Msgf("Failed to marshal pages for id %v", id)
		return err
	}

	err = q.db.InsertChapterDownloadQueueItem(&models.ChapterDownloadQueueItem{
		BaseModel:     models.BaseModel{},
		Provider:      id.Provider,
		MediaID:       id.MediaId,
		ChapterNumber: id.ChapterNumber,
		ChapterID:     id.ChapterId,
		PageData:      marshalled,
		Status:        string(QueueStatusNotStarted),
	})
	if err != nil {
		q.logger.Error().Err(err).Msgf("Failed to insert chapter download queue item for id %v", id)
		return err
	}

	q.logger.Info().Msgf("chapter downloader: Added chapter to download queue: %s", id.ChapterId)

	q.wsEventManager.SendEvent(events.ChapterDownloadQueueUpdated, nil)

	if runNext && q.active {
		q.runNext()
	}

	return nil
}

func (q *Queue) HasCompleted(queueInfo *QueueInfo) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if !q.isCurrent(queueInfo) {
		return
	}

	if queueInfo.Status == QueueStatusErrored {
		q.logger.Warn().Msgf("chapter downloader: Errored %s", queueInfo.DownloadID.ChapterId)
		// Update the status of the current item in the database.
		_ = q.db.UpdateChapterDownloadQueueItemStatus(q.current.DownloadID.Provider, q.current.DownloadID.MediaId, q.current.DownloadID.ChapterId, string(QueueStatusErrored))
	} else {
		q.logger.Debug().Msgf("chapter downloader: Dequeueing %s", queueInfo.DownloadID.ChapterId)
		// Dequeue the item from the database.
		_, err := q.db.DequeueChapterDownloadQueueItemByID(queueInfo.DownloadID.Provider, queueInfo.DownloadID.MediaId, queueInfo.DownloadID.ChapterId)
		if err != nil {
			q.logger.Error().Err(err).Msgf("Failed to dequeue chapter download queue item for id %v", queueInfo.DownloadID)
			_ = q.db.UpdateChapterDownloadQueueItemStatus(queueInfo.DownloadID.Provider, queueInfo.DownloadID.MediaId, queueInfo.DownloadID.ChapterId, string(QueueStatusNotStarted))
		}
	}

	q.wsEventManager.SendEvent(events.ChapterDownloadQueueUpdated, nil)
	q.wsEventManager.SendEvent(events.RefreshedMangaDownloadData, nil)

	// Reset current item
	q.current = nil

	if q.active {
		// Tells queue to run next if possible
		q.runNext()
	}
}

// HasPaused retains the current queue item after its in-flight work is canceled.
func (q *Queue) HasPaused(queueInfo *QueueInfo) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if !q.isCurrent(queueInfo) {
		return
	}

	status := QueueStatusNotStarted
	if q.backgroundPaused && !q.active {
		status = QueueStatusBackgroundPaused
	}
	_ = q.db.UpdateChapterDownloadQueueItemStatus(queueInfo.DownloadID.Provider, queueInfo.DownloadID.MediaId, queueInfo.DownloadID.ChapterId, string(status))
	queueInfo.paused = true
	q.current = nil
	q.wsEventManager.SendEvent(events.ChapterDownloadQueueUpdated, nil)
	if q.active {
		q.runNext()
	}
}

// Run activates the queue and invokes runNext
func (q *Queue) Run() bool {
	q.mu.Lock()
	defer q.mu.Unlock()

	if !q.active {
		q.logger.Debug().Msg("chapter downloader: Starting queue")
		if err := q.db.ResetDownloadingChapterDownloadQueueItems(); err != nil {
			q.logger.Error().Err(err).Msg("chapter downloader: Could not reset interrupted queue items")
			return false
		}
		q.backgroundPaused = false
		q.ctx, q.cancel = context.WithCancel(context.Background())
	}

	q.active = true

	// Tells queue to run next if possible
	q.runNext()
	return true
}

// Stop deactivates the queue
func (q *Queue) Stop() {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.stopLocked(false)
}

func (q *Queue) PauseForBackground() bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	wasActive := q.active
	if wasActive {
		q.backgroundPaused = true
		if err := q.db.PauseChapterDownloadQueueItemsForBackground(); err != nil {
			q.logger.Error().Err(err).Msg("chapter downloader: Could not save manga queue state for backgrounding")
		}
	}
	q.stopLocked(true)
	return wasActive
}

func (q *Queue) stopLocked(background bool) {
	if q.active {
		q.logger.Debug().Msg("chapter downloader: Stopping queue")
	}

	q.active = false
	if q.cancel != nil {
		q.cancel()
		q.cancel = nil
		q.ctx = nil
	}
	if q.current != nil && !background {
		_ = q.db.UpdateChapterDownloadQueueItemStatus(q.current.DownloadID.Provider, q.current.DownloadID.MediaId, q.current.DownloadID.ChapterId, string(QueueStatusNotStarted))
	}
}

// runNext runs the next item in the queue.
//   - Checks if there is a current item, if so, it returns.
//   - If nothing is running, it gets the next item (QueueInfo) from the database, sets it as current and sends it to the downloader.
func (q *Queue) runNext() {
	if !q.active || q.current != nil {
		return
	}

	q.logger.Debug().Msg("chapter downloader: Processing next item in queue")

	// Catch panic in runNext, so it doesn't bubble up and stop goroutines.
	defer util.HandlePanicInModuleThen("internal/manga/downloader/runNext", func() {
		q.logger.Error().Msg("chapter downloader: Panic in 'runNext'")
	})

	if q.current != nil {
		q.logger.Debug().Msg("chapter downloader: Current item is not nil")
		return
	}

	for {
		q.logger.Debug().Msg("chapter downloader: Checking next item in queue")

		// Get next item from the database.
		next, _ := q.db.GetNextChapterDownloadQueueItem()
		if next == nil {
			q.logger.Debug().Msg("chapter downloader: No next item in queue")
			return
		}

		id := DownloadID{
			Provider:      next.Provider,
			MediaId:       next.MediaID,
			ChapterId:     next.ChapterID,
			ChapterNumber: next.ChapterNumber,
		}

		q.logger.Debug().Msgf("chapter downloader: Preparing next item in queue: %s", id.ChapterId)

		var pages []*hibikemanga.ChapterPage
		if err := json.Unmarshal(next.PageData, &pages); err != nil {
			q.logger.Error().Err(err).Msgf("Failed to unmarshal pages for id %v", id)
			if statusErr := q.db.UpdateChapterDownloadQueueItemStatus(id.Provider, id.MediaId, id.ChapterId, string(QueueStatusErrored)); statusErr != nil {
				q.logger.Error().Err(statusErr).Msgf("Failed to mark malformed chapter queue item errored: %s", id.ChapterId)
				return
			}
			q.wsEventManager.SendEvent(events.ChapterDownloadQueueUpdated, nil)
			continue
		}

		_ = q.db.UpdateChapterDownloadQueueItemStatus(id.Provider, id.MediaId, id.ChapterId, string(QueueStatusDownloading))
		q.current = &QueueInfo{
			DownloadID:     id,
			Pages:          pages,
			DownloadedUrls: make([]string, 0),
			Status:         QueueStatusDownloading,
			ctx:            q.ctx,
		}
		q.wsEventManager.SendEvent(events.ChapterDownloadQueueUpdated, nil)

		q.logger.Info().Msgf("chapter downloader: Running next item in queue: %s", id.ChapterId)

		// Tell Downloader to run.
		q.runCh <- q.current
		return
	}
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

func (q *Queue) GetCurrent() (qi *QueueInfo, ok bool) {
	q.mu.Lock()
	defer q.mu.Unlock()

	if q.current == nil {
		return nil, false
	}

	return q.current, true
}

func (q *Queue) isCurrent(queueInfo *QueueInfo) bool {
	return queueInfo != nil && q.current != nil &&
		q.current.DownloadID.Provider == queueInfo.DownloadID.Provider &&
		q.current.DownloadID.MediaId == queueInfo.DownloadID.MediaId &&
		q.current.DownloadID.ChapterId == queueInfo.DownloadID.ChapterId
}
