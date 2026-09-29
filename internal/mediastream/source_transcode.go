package mediastream

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/labstack/echo/v4"
	"seanime/internal/mediastream/cassette"
	"seanime/internal/mediastream/videofile"
)

const sourceSegmentCacheBytes = 128 << 20
const maxSourceTranscodeSessions = 4

type SourceTranscodeResponse struct {
	SessionID string `json:"sessionId"`
	StreamURL string `json:"streamUrl"`
	// The full VOD playlist and TS timestamps use the original source timeline,
	// including after arbitrary seeks. Clients must not add a resume offset.
	TimeOffset float64             `json:"timeOffset"`
	Chapters   []videofile.Chapter `json:"chapters"`
}

type sourceTranscode struct {
	id, clientID, playbackID, sourceURL, input, hash, directory string
	ctx                                                         context.Context
	cancel                                                      context.CancelFunc
	gateway                                                     *sourceGateway
	probeCancel                                                 context.CancelFunc
	mu                                                          sync.Mutex
	diskMu                                                      sync.Mutex
	info                                                        *videofile.MediaInfo
	transcoder                                                  *cassette.Cassette
	encoderCancel                                               context.CancelFunc
	suspended                                                   bool
	closed                                                      bool
	once                                                        sync.Once
}

func (s *sourceTranscode) close() {
	s.once.Do(func() {
		s.cancel()
		s.gateway.close()
		s.mu.Lock()
		s.closed = true
		if s.encoderCancel != nil {
			s.encoderCancel()
		}
		transcoder := s.transcoder
		s.transcoder = nil
		s.mu.Unlock()
		if transcoder != nil {
			transcoder.Destroy()
		}
		s.diskMu.Lock()
		_ = os.RemoveAll(s.directory)
		s.diskMu.Unlock()
	})
}

// RequestAndroidTVSourceTranscode is independent of the existing singleton
// local-file player. Source identity and original subtitle/font extraction are
// retained by VideoCore; only its browser media URL is replaced.
func (r *Repository) RequestAndroidTVSourceTranscode(ctx context.Context, sourceURL, playbackID, clientID, serverOrigin string, headers http.Header) (*SourceTranscodeResponse, error) {
	if _, err := parseSourceURL(sourceURL); err != nil {
		return nil, err
	}
	if strings.TrimSpace(clientID) == "" || strings.TrimSpace(playbackID) == "" {
		return nil, errors.New("client and playback identity are required")
	}
	settings, ok := r.settings.Get()
	if !ok || !settings.TranscodeEnabled || r.transcodeDir == "" {
		return nil, errors.New("enable media transcoding in Settings to convert this stream")
	}
	var random [24]byte
	if _, err := rand.Read(random[:]); err != nil {
		return nil, err
	}
	id := hex.EncodeToString(random[:])
	lifetime, cancel := context.WithCancel(context.Background())
	gateway, input, err := newSourceGateway(lifetime, sourceURL, serverOrigin, headers)
	if err != nil {
		cancel()
		return nil, err
	}
	hash := sha256.Sum256([]byte(playbackID + "\x00" + sourceURL))
	probeCtx, probeCancel := context.WithTimeout(lifetime, 40*time.Second)
	s := &sourceTranscode{id: id, clientID: clientID, playbackID: playbackID, sourceURL: sourceURL,
		input: input, hash: hex.EncodeToString(hash[:]), directory: filepath.Join(r.transcodeDir, "sources", id),
		ctx: lifetime, cancel: cancel, gateway: gateway, probeCancel: probeCancel}
	r.sourceMu.Lock()
	if r.sourceSuspended {
		r.sourceMu.Unlock()
		probeCancel()
		s.close()
		return nil, errors.New("source conversion is paused while Android TV is in the background")
	}
	var previous []*sourceTranscode
	for key, existing := range r.sourceTranscodes {
		if existing.clientID == clientID {
			previous = append(previous, existing)
			delete(r.sourceTranscodes, key)
		}
	}
	if len(r.sourceTranscodes) >= maxSourceTranscodeSessions {
		r.sourceMu.Unlock()
		probeCancel()
		s.close()
		return nil, errors.New("too many source conversions are active")
	}
	if r.sourceTranscodes == nil {
		r.sourceTranscodes = make(map[string]*sourceTranscode)
	}
	r.sourceTranscodes[id] = s
	r.sourceMu.Unlock()
	for _, existing := range previous {
		existing.close()
	}
	stopRequestCancel := context.AfterFunc(ctx, probeCancel)
	info, err := videofile.FfprobeGetInfoContext(probeCtx, settings.FfprobePath, input, s.hash)
	stopRequestCancel()
	probeCancel()
	if err == nil && (info.Video == nil || info.Video.Width == 0 || info.Video.Height == 0 || math.IsNaN(float64(info.Duration)) || math.IsInf(float64(info.Duration), 0) || info.Duration <= 0 || info.Duration > 24*60*60) {
		err = errors.New("conversion requires a seekable video with a duration of up to 24 hours")
	}
	if err == nil {
		err = ctx.Err()
	}
	if err == nil {
		err = s.ctx.Err()
	}
	if err != nil {
		r.StopAndroidTVSourceTranscode(id, clientID)
		return nil, fmt.Errorf("inspect media source: %w", err)
	}
	// No persistent cache of transient provider URLs and no remote media staging.
	info.Path = sourceURL
	s.mu.Lock()
	s.info = info
	s.mu.Unlock()
	go s.trimLoop()
	return &SourceTranscodeResponse{SessionID: id, StreamURL: "/api/v1/mediastream/source/" + id + "/master.m3u8", TimeOffset: 0, Chapters: info.Chapters}, nil
}

func (s *sourceTranscode) trimLoop() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.mu.Lock()
			transcoder := s.transcoder
			s.mu.Unlock()
			if transcoder != nil {
				s.diskMu.Lock()
				transcoder.TrimCachedSegments(sourceSegmentCacheBytes)
				s.diskMu.Unlock()
			}
		}
	}
}

func (r *Repository) StopAndroidTVSourceTranscode(id, clientID string) {
	r.sourceMu.Lock()
	s := r.sourceTranscodes[id]
	if s != nil && s.clientID == clientID {
		delete(r.sourceTranscodes, id)
	} else {
		s = nil
	}
	r.sourceMu.Unlock()
	if s != nil {
		s.close()
	}
}

func (r *Repository) stopAndroidTVSourceTranscodes(clientID string) {
	r.sourceMu.Lock()
	var stopped []*sourceTranscode
	for id, s := range r.sourceTranscodes {
		if clientID == "" || s.clientID == clientID {
			stopped = append(stopped, s)
			delete(r.sourceTranscodes, id)
		}
	}
	r.sourceMu.Unlock()
	for _, s := range stopped {
		s.close()
	}
}

// StopAndroidTVClientSourceTranscodes releases encoders, gateways, probes and
// cache after the client's last WebSocket disconnects.
func (r *Repository) StopAndroidTVClientSourceTranscodes(clientID string) {
	if clientID != "" {
		r.stopAndroidTVSourceTranscodes(clientID)
	}
}

// false is Android background suspension. true clears suspension on foreground
// return. The original session URL, source gateway and timeline remain intact;
// new segment requests recreate encoders lazily. Active foreground native
// playback is preserved by the Android lifecycle's foreground-activity check.
func (r *Repository) SuspendAndroidTVSourceTranscodes(foreground bool) {
	r.sourceMu.Lock()
	r.sourceSuspended = !foreground
	sessions := make([]*sourceTranscode, 0, len(r.sourceTranscodes))
	for _, s := range r.sourceTranscodes {
		sessions = append(sessions, s)
	}
	r.sourceMu.Unlock()
	for _, s := range sessions {
		s.mu.Lock()
		s.suspended = !foreground
		var stopped *cassette.Cassette
		if !foreground {
			if s.encoderCancel != nil {
				s.encoderCancel()
			}
			if s.info == nil {
				s.probeCancel()
			}
			stopped = s.transcoder
			s.transcoder = nil
		}
		s.mu.Unlock()
		if stopped != nil {
			stopped.Destroy()
		}
	}
}

func (r *Repository) sourceEncoder(s *sourceTranscode) (*cassette.Cassette, *videofile.MediaInfo, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.ctx.Err() != nil {
		return nil, nil, errors.New("source conversion has stopped")
	}
	if s.suspended {
		return nil, nil, echo.NewHTTPError(http.StatusServiceUnavailable, "source conversion is paused in the background")
	}
	if s.info == nil {
		return nil, nil, errors.New("source conversion is not ready")
	}
	if s.transcoder == nil {
		settings, ok := r.settings.Get()
		if !ok {
			return nil, nil, errors.New("media transcoding is not initialized")
		}
		ctx, cancel := context.WithCancel(s.ctx)
		transcoder, err := cassette.New(&cassette.NewCassetteOptions{
			Logger: r.logger, HwAccelKind: settings.TranscodeHwAccel, Preset: settings.TranscodePreset,
			TempOutDir: s.directory, FfmpegPath: settings.FfmpegPath, FfprobePath: settings.FfprobePath,
			HwAccelCustomSettings: settings.TranscodeHwAccelCustomSettings, MaxConcurrency: 2,
			FixedSegmentDuration: 2, Context: ctx,
		})
		if err != nil {
			cancel()
			return nil, nil, err
		}
		s.transcoder = transcoder
		s.encoderCancel = cancel
	}
	return s.transcoder, s.info, nil
}

// ServeEchoAndroidTVSourceTranscode uses an unguessable session capability plus
// ordinary server authentication for media GETs, which cannot carry client
// platform headers in the browser's native media stack.
func (r *Repository) ServeEchoAndroidTVSourceTranscode(c echo.Context) error {
	r.sourceMu.Lock()
	s := r.sourceTranscodes[c.Param("session")]
	suspended := r.sourceSuspended
	r.sourceMu.Unlock()
	if s == nil {
		return echo.NewHTTPError(http.StatusGone, "source conversion has stopped")
	}
	if suspended {
		return echo.NewHTTPError(http.StatusServiceUnavailable, "source conversion is paused in the background")
	}
	transcoder, info, err := r.sourceEncoder(s)
	if err != nil {
		return err
	}
	path, token := c.Param("*"), c.QueryParam("token")
	parts := strings.Split(path, "/")
	playlist := func(text string, err error) error {
		if err != nil {
			return err
		}
		c.Response().Header().Set("Cache-Control", "no-store")
		return c.Blob(http.StatusOK, "application/vnd.apple.mpegurl", []byte(text))
	}
	if path == "master.m3u8" {
		return playlist(transcoder.GetMaster(s.input, s.hash, info, s.clientID, token))
	}
	audio := int32(-1)
	quality := cassette.Original
	if len(parts) == 3 && parts[0] == "audio" {
		index, parseErr := strconv.ParseInt(parts[1], 10, 32)
		if parseErr != nil || index < 0 || index >= int64(len(info.Audios)) {
			return echo.NewHTTPError(http.StatusBadRequest, "invalid audio track")
		}
		audio = int32(index)
	} else if len(parts) == 2 {
		quality, err = cassette.QualityFromString(parts[0])
		if err != nil {
			return echo.NewHTTPError(http.StatusBadRequest, "invalid video quality")
		}
		found := false
		for _, entry := range cassette.BuildQualityLadder(info) {
			if entry.Quality == quality {
				found = true
				break
			}
		}
		if !found {
			return echo.NewHTTPError(http.StatusBadRequest, "video quality exceeds source")
		}
	} else {
		return echo.NewHTTPError(http.StatusBadRequest, "invalid source stream path")
	}
	resource := parts[len(parts)-1]
	if resource == "index.m3u8" {
		if audio >= 0 {
			return playlist(transcoder.GetAudioIndex(s.input, s.hash, info, audio, s.clientID, token))
		}
		return playlist(transcoder.GetVideoIndex(s.input, s.hash, info, quality, s.clientID, token))
	}
	segment, err := cassette.ParseSegment(resource)
	if err != nil || segment < 0 || float64(segment)*2 >= float64(info.Duration) {
		return echo.NewHTTPError(http.StatusBadRequest, "invalid source segment")
	}
	get := func() (string, error) {
		if audio >= 0 {
			return transcoder.GetAudioSegment(c.Request().Context(), s.input, s.hash, info, audio, segment, s.clientID)
		}
		return transcoder.GetVideoSegment(c.Request().Context(), s.input, s.hash, info, quality, segment, s.clientID)
	}
	for attempt := 0; attempt < 2; attempt++ {
		file, getErr := get()
		if getErr != nil {
			return getErr
		}
		s.diskMu.Lock()
		if _, statErr := os.Stat(file); statErr != nil {
			s.diskMu.Unlock()
			continue
		}
		// Protect in-flight file reads against LRU removal and refresh their age.
		now := time.Now()
		_ = os.Chtimes(file, now, now)
		c.Response().Header().Set("Content-Type", "video/mp2t")
		c.Response().Header().Set("Cache-Control", "no-store")
		err = c.File(file)
		s.diskMu.Unlock()
		return err
	}
	return errors.New("source segment was evicted; retry playback")
}
