package filesystem

import (
	"encoding/json"
	"errors"
	"testing"

	"seanime/internal/androidtvstorage"

	"github.com/rs/zerolog"
)

type cleanStorageAdapter struct {
	androidtvstorage.Adapter
	entries map[string][]androidtvstorage.Entry
	removed map[string]bool
}

func (a *cleanStorageAdapter) List(path string) (string, error) {
	if path == "/androidtv/root/unavailable" {
		return "", errors.New("USB directory is unavailable")
	}
	value, err := json.Marshal(a.entries[path])
	return string(value), err
}

func (a *cleanStorageAdapter) Remove(path string) error {
	a.removed[path] = true
	return nil
}

func TestRemoveEmptyDirectoriesUsesSAFAndKeepsRootsAndMedia(t *testing.T) {
	storage := &cleanStorageAdapter{
		entries: map[string][]androidtvstorage.Entry{
			"/androidtv/root": {
				{Name: "empty", IsDirectory: true},
				{Name: "nested", IsDirectory: true},
				{Name: "series", IsDirectory: true},
				{Name: "unavailable", IsDirectory: true},
			},
			"/androidtv/root/nested": {{Name: "empty", IsDirectory: true}},
			"/androidtv/root/series": {{Name: "episode.mkv"}},
		},
		removed: make(map[string]bool),
	}
	androidtvstorage.SetAdapter(storage)
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })
	logger := zerolog.Nop()
	RemoveEmptyDirectories("/androidtv/root", &logger)
	for _, path := range []string{"/androidtv/root/empty", "/androidtv/root/nested/empty", "/androidtv/root/nested"} {
		if !storage.removed[path] {
			t.Errorf("empty directory was not removed: %s", path)
		}
	}
	for _, path := range []string{"/androidtv/root", "/androidtv/root/series", "/androidtv/root/series/episode.mkv", "/androidtv/root/unavailable"} {
		if storage.removed[path] {
			t.Errorf("nonempty, uninspectable, or selected directory was removed: %s", path)
		}
	}
}
