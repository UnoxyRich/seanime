package mediastream

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"seanime/internal/androidtvstorage"
	"seanime/internal/database/models"
	"seanime/internal/events"
	"seanime/internal/mediacore"
	"seanime/internal/mediastream/cassette"
	"seanime/internal/mediastream/videofile"
	"seanime/internal/player"
	"seanime/internal/util/filecache"
	"strings"
	"sync"

	"github.com/rs/zerolog"
	"github.com/samber/mo"
)

type mediaInfoExtractor interface {
	GetInfo(ffprobePath string, filePath string) (*videofile.MediaInfo, error)
	GetInfoFromURL(ffprobePath string, sourceURL string, mediaPath string, hash string) (*videofile.MediaInfo, error)
}

type (
	Repository struct {
		transcoder           mo.Option[*cassette.Cassette]
		settings             mo.Option[*models.MediastreamSettings]
		playbackManager      *PlaybackManager
		mediaInfoExtractor   mediaInfoExtractor
		logger               *zerolog.Logger
		wsEventManager       events.WSEventManagerInterface
		mediacoreCoordinator *mediacore.Coordinator
		fileCacher           *filecache.Cacher
		reqMu                sync.Mutex
		cacheDir             string // where attachments are stored
		transcodeDir         string // where stream segments are stored
		stagedSourceMu       sync.Mutex
		sourceMu             sync.Mutex
		sourceTranscodes     map[string]*sourceTranscode
		sourceSuspended      bool
		sourceCacheCleanup   sync.Once
	}

	NewRepositoryOptions struct {
		Logger               *zerolog.Logger
		WSEventManager       events.WSEventManagerInterface
		MediacoreCoordinator *mediacore.Coordinator
		FileCacher           *filecache.Cacher
	}
)

func NewRepository(opts *NewRepositoryOptions) *Repository {
	ret := &Repository{
		logger:               opts.Logger,
		settings:             mo.None[*models.MediastreamSettings](),
		transcoder:           mo.None[*cassette.Cassette](),
		wsEventManager:       opts.WSEventManager,
		mediacoreCoordinator: opts.MediacoreCoordinator,
		fileCacher:           opts.FileCacher,
		mediaInfoExtractor:   videofile.NewMediaInfoExtractor(opts.FileCacher, opts.Logger),
	}
	ret.playbackManager = NewPlaybackManager(ret)

	if opts.MediacoreCoordinator != nil {
		opts.MediacoreCoordinator.RegisterEventCallback(func(event player.Event) bool {
			switch e := event.(type) {
			case *player.TerminatedEvent:
				ret.stopAndroidTVSourceTranscodes(e.Session.ClientID)
				if ret.TranscoderIsInitialized() {
					opts.Logger.Debug().Str("clientId", e.Session.ClientID).Msg("mediastream: Received TerminatedEvent, killing transcoder")
					ret.ShutdownTranscodeStream(e.Session.ClientID)
				}
			}
			return true
		})
	}

	return ret
}

func (r *Repository) IsInitialized() bool {
	return r.settings.IsPresent()
}

func (r *Repository) OnCleanup() {
	r.stopAndroidTVSourceTranscodes("")
	if transcoder, ok := r.transcoder.Get(); ok {
		transcoder.Destroy()
		r.transcoder = mo.None[*cassette.Cassette]()
	}
}

func (r *Repository) InitializeModules(settings *models.MediastreamSettings, cacheDir string, transcodeDir string) {
	if settings == nil {
		r.logger.Error().Msg("mediastream: Settings not present")
		return
	}
	// Clean cache left by a killed process once per repository lifetime. Settings
	// refreshes must not remove the output of current source sessions.
	r.sourceCacheCleanup.Do(func() {
		if transcodeDir != "" {
			if err := os.RemoveAll(filepath.Join(transcodeDir, "sources")); err != nil {
				r.logger.Warn().Err(err).Msg("mediastream: Could not remove orphaned source conversion cache")
			}
		}
	})
	// Create the temp directory
	err := os.MkdirAll(transcodeDir, 0755)
	if err != nil {
		r.logger.Error().Err(err).Msg("mediastream: Failed to create transcode directory")
	}

	settings.FfmpegPath = strings.TrimSpace(strings.Trim(settings.FfmpegPath, "\""))
	if settings.FfmpegPath == "" {
		settings.FfmpegPath = "ffmpeg"
	}

	settings.FfprobePath = strings.TrimSpace(strings.Trim(settings.FfprobePath, "\""))
	if settings.FfprobePath == "" {
		settings.FfprobePath = "ffprobe"
	}

	// Set the settings
	r.settings = mo.Some[*models.MediastreamSettings](settings)

	r.cacheDir = cacheDir
	r.transcodeDir = transcodeDir

	// Initialize the transcoder
	if ok := r.initializeTranscoder(r.settings); ok {
	}

	r.logger.Info().Msg("mediastream: Module initialized")
}

// CacheWasCleared should be called when the cache directory is manually cleared.
func (r *Repository) CacheWasCleared() {
	r.playbackManager.mediaContainers.Clear()
}

func (r *Repository) ClearTranscodeDir() {
	r.stopAndroidTVSourceTranscodes("")
	r.reqMu.Lock()
	defer r.reqMu.Unlock()

	r.logger.Trace().Msg("mediastream: Clearing transcode directory")

	// Empty the transcode directory
	if r.transcodeDir != "" {
		files, err := os.ReadDir(r.transcodeDir)
		if err != nil {
			r.logger.Error().Err(err).Msg("mediastream: Failed to read transcode directory")
			return
		}

		for _, file := range files {
			err = os.RemoveAll(filepath.Join(r.transcodeDir, file.Name()))
			if err != nil {
				r.logger.Error().Err(err).Msg("mediastream: Failed to remove file from transcode directory")
			}
		}
	}

	r.logger.Debug().Msg("mediastream: Transcode directory cleared")

	r.playbackManager.mediaContainers.Clear()
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Transcode
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

func (r *Repository) TranscoderIsInitialized() bool {
	return r.IsInitialized() && r.transcoder.IsPresent()
}

// PrepareAndroidTVTranscodeSource stages a SAF document in app-managed cache
// because FFmpeg requires a local, seekable filesystem path.
func (r *Repository) PrepareAndroidTVTranscodeSource(sourcePath string) (string, error) {
	if !androidtvstorage.IsPath(sourcePath) {
		return sourcePath, nil
	}
	if r.cacheDir == "" {
		return "", errors.New("media cache directory is not set")
	}

	r.stagedSourceMu.Lock()
	defer r.stagedSourceMu.Unlock()

	info, err := androidtvstorage.Stat(sourcePath)
	if err != nil {
		return "", fmt.Errorf("inspect Android TV media source: %w", err)
	}
	if info.IsDirectory {
		return "", errors.New("Android TV transcode source is a directory")
	}

	identity := fmt.Sprintf("%s:%d:%d", sourcePath, info.Size, info.ModTime)
	sum := sha256.Sum256([]byte(identity))
	stageDirectory := filepath.Join(r.cacheDir, "androidtv-transcode-input")
	destination := filepath.Join(stageDirectory, hex.EncodeToString(sum[:16])+path.Ext(sourcePath))
	if cached, statErr := os.Stat(destination); statErr == nil && cached.Size() == info.Size {
		return destination, nil
	}

	if _, err := androidtvstorage.CopyToLocal(sourcePath, destination); err != nil {
		return "", fmt.Errorf("stage Android TV media source for transcoding: %w", err)
	}
	return destination, nil
}

// RemoveStagedAndroidTVTranscodeSource removes a cache file created for a SAF
// transcode without accepting arbitrary local paths.
func (r *Repository) RemoveStagedAndroidTVTranscodeSource(stagedPath string) error {
	if r.cacheDir == "" || stagedPath == "" {
		return nil
	}
	stageDirectory := filepath.Clean(filepath.Join(r.cacheDir, "androidtv-transcode-input"))
	cleanedPath := filepath.Clean(stagedPath)
	if filepath.Dir(cleanedPath) != stageDirectory {
		return nil
	}
	r.stagedSourceMu.Lock()
	defer r.stagedSourceMu.Unlock()
	if err := os.Remove(cleanedPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func (r *Repository) RequestTranscodeStream(filepath string, clientId string) (ret *MediaContainer, err error) {
	r.reqMu.Lock()
	defer r.reqMu.Unlock()

	r.logger.Debug().Str("filepath", filepath).Msg("mediastream: Transcode stream requested")

	if !r.IsInitialized() {
		return nil, errors.New("module not initialized")
	}

	// Reinitialize the transcoder for each new transcode request
	if ok := r.initializeTranscoder(r.settings); !ok {
		return nil, errors.New("real-time transcoder not initialized, check your settings")
	}

	ret, err = r.playbackManager.RequestPlayback(filepath, StreamTypeTranscode)

	return
}

func (r *Repository) RequestPreloadTranscodeStream(filepath string) (err error) {
	r.logger.Debug().Str("filepath", filepath).Msg("mediastream: Transcode stream preloading requested")

	if !r.IsInitialized() {
		return errors.New("module not initialized")
	}

	_, err = r.playbackManager.PreloadPlayback(filepath, StreamTypeTranscode)

	return
}

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Direct Play
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

func (r *Repository) RequestDirectPlay(filepath string, clientId string) (ret *MediaContainer, err error) {
	r.reqMu.Lock()
	defer r.reqMu.Unlock()

	r.logger.Debug().Str("filepath", filepath).Msg("mediastream: Direct play requested")

	if !r.IsInitialized() {
		return nil, errors.New("module not initialized")
	}

	ret, err = r.playbackManager.RequestPlayback(filepath, StreamTypeDirect)

	return
}

func (r *Repository) RequestPreloadDirectPlay(filepath string) (err error) {
	r.logger.Debug().Str("filepath", filepath).Msg("mediastream: Direct stream preloading requested")

	if !r.IsInitialized() {
		return errors.New("module not initialized")
	}

	_, err = r.playbackManager.PreloadPlayback(filepath, StreamTypeDirect)

	return
}

///////////////////////////////////////////////////////////////////////////////////////////////

func (r *Repository) initializeTranscoder(settings mo.Option[*models.MediastreamSettings]) bool {
	// Destroy the old transcoder if it exists
	if r.transcoder.IsPresent() {
		tc, _ := r.transcoder.Get()
		tc.Destroy()
	}

	r.transcoder = mo.None[*cassette.Cassette]()

	// If the transcoder is not enabled, don't initialize the transcoder
	if !settings.MustGet().TranscodeEnabled {
		return false
	}

	// If the temp directory is not set, don't initialize the transcoder
	if r.transcodeDir == "" {
		r.logger.Error().Msg("mediastream: Transcode directory not set, could not initialize transcoder")
		return false
	}

	opts := &cassette.NewCassetteOptions{
		Logger:                r.logger,
		HwAccelKind:           settings.MustGet().TranscodeHwAccel,
		Preset:                settings.MustGet().TranscodePreset,
		FfmpegPath:            settings.MustGet().FfmpegPath,
		FfprobePath:           settings.MustGet().FfprobePath,
		HwAccelCustomSettings: settings.MustGet().TranscodeHwAccelCustomSettings,
		TempOutDir:            r.transcodeDir,
	}

	tc, err := cassette.New(opts)
	if err != nil {
		r.logger.Error().Err(err).Msg("mediastream: Failed to initialize cassette")
		return false
	}

	r.playbackManager.mediaContainers.Clear()

	r.logger.Info().Msg("mediastream: Cassette module initialized")
	r.transcoder = mo.Some[*cassette.Cassette](tc)

	return true
}
