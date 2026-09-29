package mediastream

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/labstack/echo/v4"
	"github.com/rs/zerolog"
	"github.com/samber/mo"
	"seanime/internal/database/models"
	"seanime/internal/mediastream/videofile"
)

func TestSourceCacheStartupCleanupPreservesActiveSettingsRefresh(t *testing.T) {
	logger := zerolog.New(io.Discard)
	r := &Repository{logger: &logger}
	r.playbackManager = NewPlaybackManager(r)
	directory := t.TempDir()
	orphan := filepath.Join(directory, "sources", "dead-process", "segment.ts")
	if err := os.MkdirAll(filepath.Dir(orphan), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(orphan, []byte("orphan"), 0600); err != nil {
		t.Fatal(err)
	}
	r.InitializeModules(&models.MediastreamSettings{}, t.TempDir(), directory)
	if _, err := os.Stat(orphan); !os.IsNotExist(err) {
		t.Fatal("orphaned process cache survived initialization")
	}
	active := filepath.Join(directory, "sources", "active-session", "segment.ts")
	if err := os.MkdirAll(filepath.Dir(active), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(active, []byte("current"), 0600); err != nil {
		t.Fatal(err)
	}
	r.InitializeModules(&models.MediastreamSettings{}, t.TempDir(), directory)
	if _, err := os.Stat(active); err != nil {
		t.Fatal("settings refresh removed current source cache")
	}
}

func TestAndroidTVSourceStopOwnershipDisconnectAndCleanup(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) { io.WriteString(w, "media") }))
	defer backend.Close()
	r := &Repository{sourceTranscodes: make(map[string]*sourceTranscode)}
	makeSession := func(id, client string) *sourceTranscode {
		t.Helper()
		ctx, cancel := context.WithCancel(context.Background())
		gateway, input, err := newSourceGateway(ctx, backend.URL+"/media", backend.URL, nil)
		if err != nil {
			t.Fatal(err)
		}
		directory := filepath.Join(t.TempDir(), "cache")
		if err := os.MkdirAll(directory, 0700); err != nil {
			t.Fatal(err)
		}
		s := &sourceTranscode{id: id, clientID: client, ctx: ctx, cancel: cancel, gateway: gateway, input: input, directory: directory}
		r.sourceTranscodes[id] = s
		return s
	}
	first, second := makeSession("first", "a"), makeSession("second", "b")
	r.StopAndroidTVSourceTranscode("first", "b")
	if first.ctx.Err() != nil {
		t.Fatal("another client stopped the source")
	}
	r.StopAndroidTVClientSourceTranscodes("a")
	if !errors.Is(first.ctx.Err(), context.Canceled) || second.ctx.Err() != nil {
		t.Fatal("disconnect cleanup did not remain client scoped")
	}
	if _, err := os.Stat(first.directory); !os.IsNotExist(err) {
		t.Fatal("source cache survived disconnect")
	}
	r.OnCleanup()
	if !errors.Is(second.ctx.Err(), context.Canceled) || len(r.sourceTranscodes) != 0 {
		t.Fatal("application cleanup left a source session")
	}
}

// This is a tiny host FFmpeg smoke test, not an APK/device build. It exercises
// the actual HTTP range gateway and decoder/encoder, including a seek from zero
// to a later segment before earlier segments have ever been requested.
func TestAndroidTVSourceConversionFFmpegSeekAudioSuspendAndRestart(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("host FFmpeg unavailable")
	}
	ffprobe, err := exec.LookPath("ffprobe")
	if err != nil {
		t.Skip("host FFprobe unavailable")
	}
	directory := t.TempDir()
	fixture := filepath.Join(directory, "unsupported.mkv")
	metadata := filepath.Join(directory, "chapters.txt")
	if err := os.WriteFile(metadata, []byte(";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=4000\ntitle=Opening\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=4000\nEND=7400\ntitle=Story\n"), 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000", "-f", "ffmetadata", "-i", metadata,
		"-t", "7.4", "-map", "0:v", "-map", "1:a", "-map", "2:a", "-map_chapters", "3", "-c:v", "ffv1", "-c:a", "pcm_s16le", "-metadata:s:a:0", "language=jpn", "-metadata:s:a:1", "language=eng", fixture)
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("create tiny fixture: %v %s", err, output)
	}
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Header.Get("X-Seanime-Token") != "source-secret" {
			http.Error(w, "auth", 401)
			return
		}
		http.ServeFile(w, req, fixture)
	}))
	defer backend.Close()
	logger := zerolog.New(io.Discard)
	r := &Repository{logger: &logger, transcodeDir: filepath.Join(directory, "transcode"), settings: mo.Some(&models.MediastreamSettings{
		TranscodeEnabled: true, FfmpegPath: ffmpeg, FfprobePath: ffprobe, TranscodeHwAccel: "disabled", TranscodePreset: "ultrafast",
	})}
	defer r.OnCleanup()
	sourceURL := backend.URL + "/directstream?id=original-playback"
	result, err := r.RequestAndroidTVSourceTranscode(ctx, sourceURL, "original-playback", "client", backend.URL, http.Header{"X-Seanime-Token": {"source-secret"}})
	if err != nil {
		t.Fatal(err)
	}
	if result.TimeOffset != 0 || len(result.Chapters) != 2 || result.Chapters[1].StartTime != 4 {
		t.Fatalf("original timeline/chapters were lost: %#v", result)
	}
	session := r.sourceTranscodes[result.SessionID]
	if session.info.Path != sourceURL || session.playbackID != "original-playback" {
		t.Fatal("source identity was replaced by the gateway")
	}
	serve := func(resource string) (*httptest.ResponseRecorder, error) {
		t.Helper()
		e := echo.New()
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodGet, result.StreamURL, nil).WithContext(ctx)
		c := e.NewContext(request, recorder)
		c.SetParamNames("session", "*")
		c.SetParamValues(result.SessionID, resource)
		return recorder, r.ServeEchoAndroidTVSourceTranscode(c)
	}
	master, err := serve("master.m3u8")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(master.Body.String(), "avc1.") || !strings.Contains(master.Body.String(), "audio/1/index.m3u8") {
		t.Fatal("H.264 ladder or second audio track missing")
	}
	for _, resource := range []string{"original/segment-2.ts", "audio/1/segment-2.ts"} {
		response, err := serve(resource)
		if err != nil {
			t.Fatalf("convert %s: %v", resource, err)
		}
		file := filepath.Join(directory, strings.ReplaceAll(resource, "/", "-"))
		if err := os.WriteFile(file, response.Body.Bytes(), 0600); err != nil {
			t.Fatal(err)
		}
		info, err := videofile.FfprobeGetInfoContext(ctx, ffprobe, file, resource)
		if err != nil {
			t.Fatalf("inspect converted %s: %v", resource, err)
		}
		if strings.HasPrefix(resource, "original") && (info.Video == nil || info.Video.Codec != "h264") {
			t.Fatal("unsupported source was not encoded as H.264")
		}
		if strings.HasPrefix(resource, "audio") && (len(info.Audios) != 1 || info.Audios[0].Codec != "aac") {
			t.Fatal("selected PCM track was not encoded as AAC")
		}
		// Validate actual muxed timestamps, not just the advertised playlist grid.
		output, err := exec.CommandContext(ctx, ffprobe, "-v", "error", "-show_entries", "format=start_time,duration", "-of", "default=noprint_wrappers=1", file).CombinedOutput()
		if err != nil {
			t.Fatal(err)
		}
		values := strings.Split(strings.TrimSpace(string(output)), "\n")
		start, _ := strconv.ParseFloat(strings.TrimPrefix(values[0], "start_time="), 64)
		if start < 3.95 || start > 4.05 {
			t.Fatalf("seek segment %s has timestamp %.3f instead of source time 4.0: %s", resource, start, output)
		}
	}
	r.SuspendAndroidTVSourceTranscodes(false)
	if _, err := serve("original/segment-0.ts"); err == nil {
		t.Fatal("background suspension allowed new encoder work")
	}
	if r.sourceTranscodes[result.SessionID] != session || session.ctx.Err() != nil {
		t.Fatal("background suspension destroyed source identity")
	}
	r.SuspendAndroidTVSourceTranscodes(true)
	if _, err := serve("original/segment-0.ts"); err != nil {
		t.Fatalf("same URL did not resume after foreground return: %v", err)
	}
	r.StopAndroidTVSourceTranscode(result.SessionID, "client")
	if _, err := os.Stat(session.directory); !os.IsNotExist(err) {
		t.Fatal("source conversion cache survived stop")
	}
}

func TestAndroidTVSourceProbeCancellation(t *testing.T) {
	ffprobe, err := exec.LookPath("ffprobe")
	if err != nil {
		t.Skip("host FFprobe unavailable")
	}
	started, stopped := make(chan struct{}), make(chan struct{})
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) { close(started); <-req.Context().Done(); close(stopped) }))
	defer backend.Close()
	logger := zerolog.New(io.Discard)
	r := &Repository{logger: &logger, transcodeDir: t.TempDir(), settings: mo.Some(&models.MediastreamSettings{TranscodeEnabled: true, FfprobePath: ffprobe})}
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() {
		_, err := r.RequestAndroidTVSourceTranscode(ctx, backend.URL+"/slow", "episode", "client", backend.URL, nil)
		result <- err
	}()
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("probe did not start")
	}
	cancel()
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("cancelled probe succeeded")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("probe survived request cancellation")
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("cancelled probe retained upstream connection")
	}
	if len(r.sourceTranscodes) != 0 {
		t.Fatal("cancelled probe left a session")
	}
}
