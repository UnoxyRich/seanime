package torrentstream

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"seanime/internal/androidtvstorage"
)

type torrentMirrorStorage struct {
	files       map[string][]byte
	directories map[string]struct{}
	writes      map[string]*bytes.Buffer
	writePaths  map[string]string
	writeCount  int
}

func (s *torrentMirrorStorage) List(string) (string, error) { return "[]", nil }
func (s *torrentMirrorStorage) Stat(path string) (string, error) {
	if _, ok := s.directories[path]; ok {
		payload, _ := json.Marshal(androidtvstorage.Entry{Name: filepath.Base(path), IsDirectory: true})
		return string(payload), nil
	}
	if content, ok := s.files[path]; ok {
		payload, _ := json.Marshal(androidtvstorage.Entry{Name: filepath.Base(path), Size: int64(len(content))})
		return string(payload), nil
	}
	return "", errors.New("not found")
}
func (s *torrentMirrorStorage) ReadAt(string, int64, int64) (string, error) { return "", nil }
func (s *torrentMirrorStorage) BeginWrite(path string, _ bool) (string, error) {
	s.writeCount++
	handle := "write-1"
	s.writes[handle] = &bytes.Buffer{}
	s.writePaths[handle] = path
	return handle, nil
}
func (s *torrentMirrorStorage) WriteChunk(handle, data string) error {
	decoded, err := base64.StdEncoding.DecodeString(data)
	if err != nil {
		return err
	}
	_, err = s.writes[handle].Write(decoded)
	return err
}
func (s *torrentMirrorStorage) FinishWrite(handle string) error {
	s.files[s.writePaths[handle]] = append([]byte(nil), s.writes[handle].Bytes()...)
	delete(s.writes, handle)
	delete(s.writePaths, handle)
	return nil
}
func (s *torrentMirrorStorage) CancelWrite(handle string) error {
	delete(s.writes, handle)
	delete(s.writePaths, handle)
	return nil
}
func (s *torrentMirrorStorage) MkdirAll(path string) error {
	s.directories[path] = struct{}{}
	return nil
}
func (s *torrentMirrorStorage) Remove(path string) error        { delete(s.files, path); return nil }
func (s *torrentMirrorStorage) URI(path string) (string, error) { return "content://test/" + path, nil }

func TestSafTorrentDestinationValidatesAndBuildsPath(t *testing.T) {
	destination, err := safTorrentDestination("/androidtv/0123456789abcdef/Anime", "0123456789abcdef", "Season 1/episode.mkv")
	if err != nil {
		t.Fatal(err)
	}
	if destination != "/androidtv/0123456789abcdef/Anime/0123456789abcdef/Season 1/episode.mkv" {
		t.Fatalf("unexpected destination: %s", destination)
	}
	for _, input := range []string{"", "../outside.mkv", "/absolute.mkv", "Season//episode.mkv", "Season\\..\\outside.mkv"} {
		if _, err := torrentStorageRelativePath(input); err == nil {
			t.Errorf("torrentStorageRelativePath(%q) succeeded, want error", input)
		}
	}
}

func TestCopyCompletedTorrentFileMirrorsAndSkipsSameSizeFile(t *testing.T) {
	storage := &torrentMirrorStorage{
		files:       make(map[string][]byte),
		directories: make(map[string]struct{}),
		writes:      make(map[string]*bytes.Buffer),
		writePaths:  make(map[string]string),
	}
	androidtvstorage.SetAdapter(storage)
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })

	sourcePath := filepath.Join(t.TempDir(), "episode.mkv")
	if err := os.WriteFile(sourcePath, []byte("complete media"), 0600); err != nil {
		t.Fatal(err)
	}
	destination := "/androidtv/0123456789abcdef/Anime/episode.mkv"
	if err := copyCompletedTorrentFile(sourcePath, destination, int64(len("complete media"))); err != nil {
		t.Fatal(err)
	}
	if string(storage.files[destination]) != "complete media" {
		t.Fatalf("unexpected copied content: %q", storage.files[destination])
	}
	if err := copyCompletedTorrentFile(sourcePath, destination, int64(len("complete media"))); err != nil {
		t.Fatal(err)
	}
	if storage.writeCount != 1 {
		t.Fatalf("same-size destination was rewritten %d times", storage.writeCount)
	}
	if err := copyCompletedTorrentFile(sourcePath, destination, int64(len("complete media")+1)); err == nil {
		t.Fatal("incomplete or mismatched source size should be rejected")
	}
}
