package mediastream

import (
	"encoding/json"
	"errors"
	"io"
	"testing"

	"github.com/rs/zerolog"
	"seanime/internal/androidtvstorage"
)

type playbackStorageAdapter struct{}

func (playbackStorageAdapter) List(string) (string, error) { return "[]", nil }
func (playbackStorageAdapter) Stat(path string) (string, error) {
	return `{"name":"episode.mkv","isDirectory":false,"size":42,"modTime":1000}`, nil
}
func (playbackStorageAdapter) ReadAt(string, int64, int64) (string, error) { return "", nil }
func (playbackStorageAdapter) BeginWrite(string, bool) (string, error) {
	return "", errors.New("unused")
}
func (playbackStorageAdapter) WriteChunk(string, string) error { return errors.New("unused") }
func (playbackStorageAdapter) FinishWrite(string) error        { return errors.New("unused") }
func (playbackStorageAdapter) CancelWrite(string) error        { return nil }
func (playbackStorageAdapter) MkdirAll(string) error           { return errors.New("unused") }
func (playbackStorageAdapter) Remove(string) error             { return errors.New("unused") }
func (playbackStorageAdapter) URI(path string) (string, error) { return "content://test/" + path, nil }

func TestSAFPlaybackUsesDirectMedia3StreamWithoutFFprobe(t *testing.T) {
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
