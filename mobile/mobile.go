package mobile

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"seanime/internal/androidtvstorage"
	"seanime/internal/core"
	"seanime/internal/cron"
	"seanime/internal/handlers"
	"seanime/internal/torrent_clients/torrent_client"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/rs/zerolog"
)

//go:embed all:web
var webFS embed.FS

type serverInstance struct {
	done           chan struct{}
	dataDir        string
	app            *core.App
	httpServer     *http.Server
	stopJobs       context.CancelFunc
	stopRequest    atomic.Bool
	inBackground   bool
	pausedTorrents []string
	backgroundMu   sync.Mutex
}

// AndroidStorageAdapter is implemented by the Android host and exposes
// persisted Storage Access Framework trees to the Go server. Paths passed to
// this interface use the /androidtv/<root-id>/... virtual namespace.
type AndroidStorageAdapter interface {
	List(path string) (string, error)
	Stat(path string) (string, error)
	ReadAt(path string, offset int64, length int64) (string, error)
	BeginWrite(path string, truncate bool) (string, error)
	WriteChunk(handle string, data string) error
	FinishWrite(handle string) error
	CancelWrite(handle string) error
	MkdirAll(path string) error
	Remove(path string) error
	URI(path string) (string, error)
}

var serverLifecycle struct {
	sync.Mutex
	status        string
	lastErr       string
	instance      *serverInstance
	foreground    bool
	foregroundSet bool
}

// SetAndroidStorageAdapter registers the Android host's SAF implementation.
// The adapter is held for the process lifetime and can be replaced after a
// host activity recreation without restarting the Go server.
func SetAndroidStorageAdapter(adapter AndroidStorageAdapter) {
	if adapter == nil {
		androidtvstorage.SetAdapter(nil)
		return
	}
	androidtvstorage.SetAdapter(adapter)
}

// StartServer starts Seanime in the background. The app-managed data and cache
// directories are supplied by the Android host and survive process restarts.
func StartServer(dataDir string, cacheDir string, port int) {
	dataDir = strings.TrimSpace(dataDir)
	cacheDir = strings.TrimSpace(cacheDir)
	if dataDir == "" {
		setStartError("data directory is required")
		return
	}
	if cacheDir == "" {
		cacheDir = filepath.Join(dataDir, "cache")
	}
	if port < 1 || port > 65535 {
		port = 43211
	}

	serverLifecycle.Lock()
	if serverLifecycle.status == "starting" || serverLifecycle.status == "ready" || serverLifecycle.status == "stopping" {
		serverLifecycle.Unlock()
		return
	}
	instance := &serverInstance{done: make(chan struct{}), dataDir: dataDir}
	serverLifecycle.instance = instance
	serverLifecycle.status = "starting"
	serverLifecycle.lastErr = ""
	if !serverLifecycle.foregroundSet {
		serverLifecycle.foreground = true
	}
	serverLifecycle.Unlock()

	go startServer(instance, dataDir, cacheDir, port)
}

// StopServer gracefully stops HTTP requests, periodic jobs, and app modules.
func StopServer() {
	serverLifecycle.Lock()
	instance := serverLifecycle.instance
	if instance == nil {
		serverLifecycle.status = "stopped"
		serverLifecycle.Unlock()
		return
	}
	if serverLifecycle.status == "stopped" || serverLifecycle.status == "failed" {
		serverLifecycle.Unlock()
		return
	}
	serverLifecycle.status = "stopping"
	instance.stopRequest.Store(true)
	server := instance.httpServer
	stopJobs := instance.stopJobs
	serverLifecycle.Unlock()

	if stopJobs != nil {
		stopJobs()
	}
	if server != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_ = server.Shutdown(ctx)
		cancel()
	}

	select {
	case <-instance.done:
	case <-time.After(6 * time.Second):
	}
}

// ServerStatus returns starting, ready, stopping, stopped, or failed.
func ServerStatus() string {
	serverLifecycle.Lock()
	defer serverLifecycle.Unlock()
	if serverLifecycle.status == "" {
		return "stopped"
	}
	return serverLifecycle.status
}

// ServerError returns the most recent startup or runtime error, if any.
func ServerError() string {
	serverLifecycle.Lock()
	defer serverLifecycle.Unlock()
	return serverLifecycle.lastErr
}

// WaitForServer waits for the HTTP listener to be ready up to timeoutMillis.
func WaitForServer(timeoutMillis int) bool {
	if timeoutMillis < 0 {
		timeoutMillis = 0
	}
	deadline := time.Now().Add(time.Duration(timeoutMillis) * time.Millisecond)
	for {
		status := ServerStatus()
		if status == "ready" {
			return true
		}
		if status == "failed" || status == "stopped" || time.Now().After(deadline) {
			return false
		}
		time.Sleep(25 * time.Millisecond)
	}
}

// SetAppInForeground pauses periodic work, automatic scanning and downloads
// while the TV app is backgrounded, then resumes them when the app returns.
func SetAppInForeground(foreground bool) {
	serverLifecycle.Lock()
	serverLifecycle.foreground = foreground
	serverLifecycle.foregroundSet = true
	instance := serverLifecycle.instance
	if instance == nil || serverLifecycle.status != "ready" {
		serverLifecycle.Unlock()
		return
	}
	app := instance.app
	serverLifecycle.Unlock()

	instance.backgroundMu.Lock()
	defer instance.backgroundMu.Unlock()
	if instance.stopRequest.Load() || app == nil || instance.inBackground == !foreground {
		return
	}
	instance.inBackground = !foreground

	if !foreground {
		serverLifecycle.Lock()
		stopJobs := instance.stopJobs
		instance.stopJobs = nil
		serverLifecycle.Unlock()
		if stopJobs != nil {
			stopJobs()
		}
		suspendAppBackgroundWork(instance, app)
		return
	}

	if app.AutoDownloader != nil {
		app.AutoDownloader.SetSuspended(false)
	}
	if app.AutoScanner != nil {
		app.AutoScanner.SetSuspended(false)
	}
	if app.MangaDownloader != nil {
		app.MangaDownloader.ResumeChapterDownloadQueueFromBackground()
	}
	if app.DebridClientRepository != nil {
		app.DebridClientRepository.SetDownloadsSuspended(false)
	}
	if app.MediastreamRepository != nil {
		app.MediastreamRepository.SuspendAndroidTVSourceTranscodes(true)
	}
	resumePausedTorrents(instance, app)
	jobsCtx, cancelJobs := context.WithCancel(context.Background())
	serverLifecycle.Lock()
	if instance.stopRequest.Load() {
		serverLifecycle.Unlock()
		cancelJobs()
		return
	}
	instance.stopJobs = cancelJobs
	serverLifecycle.Unlock()
	cron.RunJobs(jobsCtx, app)
}

func suspendAppBackgroundWork(instance *serverInstance, app *core.App) {
	if app.AutoDownloader != nil {
		app.AutoDownloader.SetSuspended(true)
	}
	if app.AutoScanner != nil {
		app.AutoScanner.SetSuspended(true)
	}
	if app.MangaDownloader != nil {
		app.MangaDownloader.PauseChapterDownloadQueueForBackground()
	}
	if app.DebridClientRepository != nil {
		app.DebridClientRepository.SetDownloadsSuspended(true)
	}
	if app.MediastreamRepository != nil {
		// All TV activities, including the decoder, have stopped before this
		// transition. Keep source sessions for foreground resume, but release
		// their encoders and segment caches while the WebView is paused.
		app.MediastreamRepository.SuspendAndroidTVSourceTranscodes(false)
	}
	if app.TorrentClientRepository == nil {
		return
	}
	torrents, err := app.TorrentClientRepository.GetList(&torrent_client.GetListOptions{Sort: "queue"})
	if err != nil {
		app.Logger.Warn().Err(err).Msg("mobile: Could not inspect torrent downloads before backgrounding")
		persistPausedTorrents(instance, app.Logger)
		return
	}
	var newlyPaused []string
	for _, item := range torrents {
		if item.Status == torrent_client.TorrentStatusDownloading || item.Status == torrent_client.TorrentStatusQueued {
			newlyPaused = append(newlyPaused, item.Hash)
		}
	}
	newlyPaused = uniqueTorrentHashes(newlyPaused)
	instance.pausedTorrents = uniqueTorrentHashes(append(instance.pausedTorrents, newlyPaused...))
	persistPausedTorrents(instance, app.Logger)
	if len(newlyPaused) > 0 {
		if err := app.TorrentClientRepository.PauseTorrents(newlyPaused); err != nil {
			app.Logger.Warn().Err(err).Msg("mobile: Could not pause active torrent downloads")
		}
	}
}

const pausedTorrentStateFilename = "android-tv-paused-torrents.json"

func pausedTorrentStatePath(dataDir string) string {
	return filepath.Join(dataDir, pausedTorrentStateFilename)
}

func loadPausedTorrents(dataDir string) ([]string, error) {
	data, err := os.ReadFile(pausedTorrentStatePath(dataDir))
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var hashes []string
	if err := json.Unmarshal(data, &hashes); err != nil {
		return nil, err
	}
	return uniqueTorrentHashes(hashes), nil
}

func persistPausedTorrents(instance *serverInstance, logger *zerolog.Logger) {
	if instance.dataDir == "" || len(instance.pausedTorrents) == 0 {
		return
	}
	data, err := json.Marshal(uniqueTorrentHashes(instance.pausedTorrents))
	if err != nil {
		logger.Warn().Err(err).Msg("mobile: Could not encode paused torrent recovery state")
		return
	}
	if err := os.MkdirAll(instance.dataDir, 0700); err != nil {
		logger.Warn().Err(err).Msg("mobile: Could not create torrent recovery state directory")
		return
	}
	path := pausedTorrentStatePath(instance.dataDir)
	tempPath := path + ".tmp"
	if err := os.WriteFile(tempPath, data, 0600); err != nil {
		logger.Warn().Err(err).Msg("mobile: Could not save paused torrent recovery state")
		return
	}
	if err := os.Rename(tempPath, path); err != nil {
		_ = os.Remove(tempPath)
		logger.Warn().Err(err).Msg("mobile: Could not commit paused torrent recovery state")
	}
}

func resumePausedTorrents(instance *serverInstance, app *core.App) {
	if app.TorrentClientRepository == nil || len(instance.pausedTorrents) == 0 {
		return
	}
	hashes := uniqueTorrentHashes(instance.pausedTorrents)
	if err := app.TorrentClientRepository.ResumeTorrents(hashes); err != nil {
		app.Logger.Warn().Err(err).Msg("mobile: Could not resume background-paused torrent downloads")
		return
	}
	instance.pausedTorrents = nil
	if err := os.Remove(pausedTorrentStatePath(instance.dataDir)); err != nil && !errors.Is(err, os.ErrNotExist) {
		app.Logger.Warn().Err(err).Msg("mobile: Could not clear resumed torrent recovery state")
	}
}

func uniqueTorrentHashes(hashes []string) []string {
	seen := make(map[string]struct{}, len(hashes))
	unique := make([]string, 0, len(hashes))
	for _, hash := range hashes {
		hash = strings.TrimSpace(hash)
		if hash == "" {
			continue
		}
		if _, ok := seen[hash]; ok {
			continue
		}
		seen[hash] = struct{}{}
		unique = append(unique, hash)
	}
	return unique
}

func startServer(instance *serverInstance, dataDir string, cacheDir string, port int) {
	var app *core.App
	var httpServer *http.Server
	var runErr error

	defer close(instance.done)
	defer func() {
		if recovered := recover(); recovered != nil {
			runErr = fmt.Errorf("Seanime server panicked: %v", recovered)
		}
		serverLifecycle.Lock()
		stopJobs := instance.stopJobs
		instance.stopJobs = nil
		serverLifecycle.Unlock()
		if stopJobs != nil {
			stopJobs()
		}
		if httpServer != nil {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			_ = httpServer.Shutdown(ctx)
			cancel()
		}
		if app != nil {
			instance.backgroundMu.Lock()
			app.Cleanup()
			instance.backgroundMu.Unlock()
		}
		serverLifecycle.Lock()
		defer serverLifecycle.Unlock()
		if serverLifecycle.instance != instance {
			return
		}
		if runErr != nil && !instance.stopRequest.Load() {
			serverLifecycle.lastErr = runErr.Error()
			serverLifecycle.status = "failed"
			return
		}
		serverLifecycle.status = "stopped"
	}()

	if err := os.MkdirAll(dataDir, 0700); err != nil {
		runErr = fmt.Errorf("create data directory: %w", err)
		return
	}
	if err := os.MkdirAll(cacheDir, 0700); err != nil {
		runErr = fmt.Errorf("create cache directory: %w", err)
		return
	}
	if err := os.RemoveAll(filepath.Join(cacheDir, "androidtv-transcode-input")); err != nil {
		runErr = fmt.Errorf("clear stale Android TV transcode sources: %w", err)
		return
	}
	if err := os.Setenv("SEANIME_DATA_DIR", dataDir); err != nil {
		runErr = err
		return
	}
	if err := os.Setenv("SEANIME_WORKING_DIR", dataDir); err != nil {
		runErr = err
		return
	}
	if err := os.Setenv("SEANIME_CACHE_DIR", cacheDir); err != nil {
		runErr = err
		return
	}
	if runtime.GOOS == "android" {
		binaryDir := filepath.Join(filepath.Dir(dataDir), "bin")
		if err := os.Setenv("PATH", binaryDir+string(os.PathListSeparator)+os.Getenv("PATH")); err != nil {
			runErr = fmt.Errorf("configure Android app binary path: %w", err)
			return
		}
	}

	flags := core.SeanimeFlags{
		DataDir:          dataDir,
		Host:             "127.0.0.1",
		Port:             port,
		IsDesktopSidecar: false,
	}
	app = core.NewApp(&core.ConfigOptions{Flags: flags}, nil)
	app.InitLogging(false)
	pausedTorrents, err := loadPausedTorrents(dataDir)
	if err != nil {
		app.Logger.Warn().Err(err).Msg("mobile: Could not load paused torrent recovery state")
	} else {
		instance.pausedTorrents = pausedTorrents
	}
	echoApp := core.NewEchoApp(app, &webFS)
	handlers.InitRoutes(app, echoApp)

	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", app.Config.Server.Port))
	if err != nil {
		runErr = fmt.Errorf("listen on local server port: %w", err)
		return
	}
	httpServer = &http.Server{Handler: echoApp}

	instance.backgroundMu.Lock()
	serverLifecycle.Lock()
	if serverLifecycle.instance != instance || instance.stopRequest.Load() {
		serverLifecycle.Unlock()
		instance.backgroundMu.Unlock()
		_ = listener.Close()
		return
	}
	instance.app = app
	instance.httpServer = httpServer
	instance.inBackground = !serverLifecycle.foreground
	startedInBackground := instance.inBackground
	serverLifecycle.status = "ready"
	serverLifecycle.Unlock()
	if startedInBackground {
		suspendAppBackgroundWork(instance, app)
	} else if app.MangaDownloader != nil {
		app.MangaDownloader.ResumeInterruptedQueue()
	}
	if !startedInBackground {
		resumePausedTorrents(instance, app)
	}
	instance.backgroundMu.Unlock()

	_ = startBackgroundJobs(instance, app)

	if err := httpServer.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		runErr = fmt.Errorf("serve local HTTP requests: %w", err)
	}
}

func startBackgroundJobs(instance *serverInstance, app *core.App) bool {
	instance.backgroundMu.Lock()
	defer instance.backgroundMu.Unlock()
	if instance.inBackground || instance.stopRequest.Load() {
		return false
	}
	jobsCtx, cancelJobs := context.WithCancel(context.Background())
	serverLifecycle.Lock()
	if instance.stopRequest.Load() || serverLifecycle.instance != instance {
		serverLifecycle.Unlock()
		cancelJobs()
		return false
	}
	instance.stopJobs = cancelJobs
	serverLifecycle.Unlock()
	cron.RunJobs(jobsCtx, app)
	return true
}

func setStartError(message string) {
	serverLifecycle.Lock()
	serverLifecycle.status = "failed"
	serverLifecycle.lastErr = message
	serverLifecycle.Unlock()
}
