package mediastream

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/rs/zerolog"
	"github.com/samber/mo"
	"seanime/internal/androidtvstorage"
	"seanime/internal/database/models"
	"seanime/internal/mediastream/videofile"
)

type playbackStorageAdapter struct{}

func (playbackStorageAdapter) List(string) (string, error) { return "[]", nil }
func (playbackStorageAdapter) Stat(path string) (string, error) {
	return `{"name":"episode.mkv","isDirectory":false,"size":42,"modTime":1000}`, nil
}
func (playbackStorageAdapter) ReadAt(_ string, offset int64, length int64) (string, error) {
	content := make([]byte, 42)
	if offset >= int64(len(content)) {
		return base64.StdEncoding.EncodeToString(nil), nil
	}
	end := offset + length
	if end > int64(len(content)) {
		end = int64(len(content))
	}
	return base64.StdEncoding.EncodeToString(content[int(offset):int(end)]), nil
}
func (playbackStorageAdapter) BeginWrite(string, bool) (string, error) {
	return "", errors.New("unused")
}
func (playbackStorageAdapter) WriteChunk(string, string) error { return errors.New("unused") }
func (playbackStorageAdapter) FinishWrite(string) error        { return errors.New("unused") }
func (playbackStorageAdapter) CancelWrite(string) error        { return nil }
func (playbackStorageAdapter) MkdirAll(string) error           { return errors.New("unused") }
func (playbackStorageAdapter) Remove(string) error             { return errors.New("unused") }
func (playbackStorageAdapter) URI(path string) (string, error) { return "content://test/" + path, nil }

type playbackMediaInfoExtractor struct {
	sourceURL  string
	mediaPath  string
	hash       string
	mediaInfo  *videofile.MediaInfo
	probeError error
}

func (f *playbackMediaInfoExtractor) GetInfo(string, string) (*videofile.MediaInfo, error) {
	return nil, errors.New("unexpected local media probe")
}

func (f *playbackMediaInfoExtractor) GetInfoFromURL(_ string, sourceURL, mediaPath, hash string) (*videofile.MediaInfo, error) {
	f.sourceURL = sourceURL
	f.mediaPath = mediaPath
	f.hash = hash
	if f.probeError != nil {
		return nil, f.probeError
	}

	request, err := http.NewRequest(http.MethodGet, sourceURL, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Range", "bytes=5-8")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusPartialContent {
		return nil, errors.New("SAF source did not honor a byte-range request")
	}
	data, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, err
	}
	if len(data) != 4 {
		return nil, errors.New("SAF source returned an incorrect byte range")
	}
	mediaInfo := *f.mediaInfo
	return &mediaInfo, nil
}

func TestSAFPlaybackKeepsDirectMedia3FallbackWithoutFFprobe(t *testing.T) {
	androidtvstorage.SetAdapter(playbackStorageAdapter{})
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })

	logger := zerolog.New(io.Discard)
	repository := &Repository{logger: &logger}
	manager := NewPlaybackManager(repository)
	path := "/androidtv/0123456789abcdef/Series/episode.mkv"
	container, err := manager.newMediaContainer(path, StreamTypeDirect)
	if err != nil {
		t.Fatal(err)
	}
	if container.StreamUrl != DirectPlayStreamUrl(container.Hash) {
		t.Fatalf("unexpected stream URL: %q", container.StreamUrl)
	}
	if container.Filepath != path || container.MediaInfo.Path != path {
		t.Fatalf("SAF source path was lost: container=%q media=%q", container.Filepath, container.MediaInfo.Path)
	}
	if container.MediaInfo.Size != 42 || container.MediaInfo.Extension != "mkv" {
		t.Fatalf("unexpected SAF media metadata: %#v", container.MediaInfo)
	}
	encoded, err := json.Marshal(container.MediaInfo)
	if err != nil || len(encoded) == 0 {
		t.Fatalf("could not serialize SAF media metadata: %v", err)
	}
	if container.MediaInfo == nil || container.MediaInfo.Videos == nil || container.MediaInfo.Audios == nil {
		t.Fatalf("native player metadata collections were left nil: %#v", container.MediaInfo)
	}
}

func TestSAFPlaybackProbesRangeSourceAndExtractsAttachmentsWithoutStaging(t *testing.T) {
	androidtvstorage.SetAdapter(playbackStorageAdapter{})
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })

	logger := zerolog.New(io.Discard)
	cacheDir := t.TempDir()
	ffmpegArgsPath := filepath.Join(t.TempDir(), "ffmpeg-args.txt")
	ffmpegPath := filepath.Join(t.TempDir(), "ffmpeg")
	ffmpegScript := "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$SEANIME_TEST_FFMPEG_ARGS\"\n"
	if err := os.WriteFile(ffmpegPath, []byte(ffmpegScript), 0755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("SEANIME_TEST_FFMPEG_ARGS", ffmpegArgsPath)

	subtitleExtension := "srt"
	extractor := &playbackMediaInfoExtractor{mediaInfo: &videofile.MediaInfo{
		Videos:    []videofile.Video{{Codec: "h264", Width: 1920, Height: 1080}},
		Audios:    []videofile.Audio{{Index: 0, Codec: "aac", Language: new("eng"), Channels: 2}},
		Subtitles: []videofile.Subtitle{{Index: 0, Codec: "subrip", Extension: &subtitleExtension}},
		Fonts:     []string{"fonts/selected.ttf"},
		Chapters:  []videofile.Chapter{{StartTime: 0, EndTime: 1, Name: "Opening"}},
	}}
	repository := &Repository{
		logger:             &logger,
		cacheDir:           cacheDir,
		mediaInfoExtractor: extractor,
		settings: mo.Some(&models.MediastreamSettings{
			FfmpegPath:  ffmpegPath,
			FfprobePath: "ffprobe",
		}),
	}
	manager := NewPlaybackManager(repository)
	path := "/androidtv/0123456789abcdef/Series/episode.mkv"
	container, err := manager.newMediaContainer(path, StreamTypeDirect)
	if err != nil {
		t.Fatal(err)
	}
	if extractor.sourceURL == "" || extractor.mediaPath != path || extractor.hash != container.Hash {
		t.Fatalf("FFprobe source identity was not preserved: %#v", extractor)
	}
	if container.MediaInfo.Path != path || container.MediaInfo.Sha != container.Hash || container.MediaInfo.Size != 42 {
		t.Fatalf("SAF media metadata identity mismatch: %#v", container.MediaInfo)
	}
	if len(container.MediaInfo.Videos) != 1 || len(container.MediaInfo.Audios) != 1 || len(container.MediaInfo.Subtitles) != 1 || len(container.MediaInfo.Fonts) != 1 || len(container.MediaInfo.Chapters) != 1 {
		t.Fatalf("FFprobe metadata was not retained: %#v", container.MediaInfo)
	}
	if _, err := os.Stat(filepath.Join(cacheDir, "androidtv-transcode-input")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("direct SAF inspection unexpectedly staged a full media copy: %v", err)
	}
	ffmpegArgs, err := os.ReadFile(ffmpegArgsPath)
	if err != nil {
		t.Fatalf("FFmpeg attachment extraction did not run: %v", err)
	}
	if !strings.Contains(string(ffmpegArgs), "-dump_attachment:t") || !strings.Contains(string(ffmpegArgs), extractor.sourceURL) {
		t.Fatalf("FFmpeg did not receive the range-readable SAF source: %s", ffmpegArgs)
	}
}

func TestSAFTranscodeSourceStagesIntoAndCleansAppCache(t *testing.T) {
	androidtvstorage.SetAdapter(playbackStorageAdapter{})
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })

	repository := &Repository{cacheDir: t.TempDir()}
	const source = "/androidtv/0123456789abcdef/Series/episode.mkv"
	staged, err := repository.PrepareAndroidTVTranscodeSource(source)
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(staged)
	if err != nil {
		t.Fatalf("staged transcode source is missing: %v", err)
	}
	if info.Size() != 42 {
		t.Fatalf("staged source size = %d, want 42", info.Size())
	}
	if err := repository.RemoveStagedAndroidTVTranscodeSource(staged); err != nil {
		t.Fatalf("remove staged source: %v", err)
	}
	if _, err := os.Stat(staged); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("staged source was not removed: %v", err)
	}
}
