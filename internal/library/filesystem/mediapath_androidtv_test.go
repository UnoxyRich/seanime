package filesystem

import (
	"encoding/json"
	"errors"
	"testing"

	"seanime/internal/androidtvstorage"
)

type filesystemStorageAdapter struct {
	entries map[string][]androidtvstorage.Entry
	stats   map[string]androidtvstorage.Entry
}

func (a filesystemStorageAdapter) List(path string) (string, error) {
	payload, err := json.Marshal(a.entries[path])
	return string(payload), err
}

func (a filesystemStorageAdapter) Stat(path string) (string, error) {
	entry, ok := a.stats[path]
	if !ok {
		return "", errors.New("not found")
	}
	payload, err := json.Marshal(entry)
	return string(payload), err
}

func (filesystemStorageAdapter) ReadAt(string, int64, int64) (string, error) {
	return "", errors.New("unused")
}
func (filesystemStorageAdapter) BeginWrite(string, bool) (string, error) {
	return "", errors.New("unused")
}
func (filesystemStorageAdapter) WriteChunk(string, string) error { return errors.New("unused") }
func (filesystemStorageAdapter) FinishWrite(string) error        { return errors.New("unused") }
func (filesystemStorageAdapter) CancelWrite(string) error        { return nil }
func (filesystemStorageAdapter) MkdirAll(string) error           { return errors.New("unused") }
func (filesystemStorageAdapter) Remove(string) error             { return errors.New("unused") }
func (filesystemStorageAdapter) URI(path string) (string, error) {
	return "content://test/" + path, nil
}

func TestGetMediaFilePathsFromDirSReadsAndroidTVStorage(t *testing.T) {
	root := "/androidtv/0123456789abcdef"
	series := root + "/Series"
	androidtvstorage.SetAdapter(filesystemStorageAdapter{
		entries: map[string][]androidtvstorage.Entry{
			root: {
				{Name: "Series", IsDirectory: true},
			},
			series: {
				{Name: "episode.mkv"},
				{Name: "special.mp4"},
				{Name: "cover.jpg"},
				{Name: "._hidden.mp4"},
			},
		},
		stats: map[string]androidtvstorage.Entry{
			root:   {Name: "USB", IsDirectory: true},
			series: {Name: "Series", IsDirectory: true},
		},
	})
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })

	paths, err := GetMediaFilePathsFromDirS(root)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{series + "/episode.mkv", series + "/special.mp4"}
	if len(paths) != len(want) {
		t.Fatalf("unexpected media paths: got=%#v want=%#v", paths, want)
	}
	for i := range want {
		if paths[i] != want[i] {
			t.Fatalf("unexpected media paths: got=%#v want=%#v", paths, want)
		}
	}
}
