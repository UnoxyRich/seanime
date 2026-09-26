package manga_providers

import (
	"archive/zip"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"path"
	"testing"

	"github.com/rs/zerolog"
	"github.com/stretchr/testify/require"
	"seanime/internal/androidtvstorage"
	"seanime/internal/extension/hibike/manga"
)

type localProviderStorageAdapter struct {
	directories map[string][]androidtvstorage.Entry
	files       map[string][]byte
}

func (a localProviderStorageAdapter) List(path string) (string, error) {
	payload, err := json.Marshal(a.directories[path])
	return string(payload), err
}

func (a localProviderStorageAdapter) Stat(storagePath string) (string, error) {
	entry, ok := a.files[storagePath]
	if ok {
		payload, err := json.Marshal(androidtvstorage.Entry{Name: path.Base(storagePath), Size: int64(len(entry))})
		return string(payload), err
	}
	if _, ok := a.directories[storagePath]; ok {
		payload, err := json.Marshal(androidtvstorage.Entry{Name: path.Base(storagePath), IsDirectory: true})
		return string(payload), err
	}
	return "", errors.New("not found")
}

func (a localProviderStorageAdapter) ReadAt(storagePath string, offset, length int64) (string, error) {
	content, ok := a.files[storagePath]
	if !ok {
		return "", errors.New("not found")
	}
	if offset >= int64(len(content)) {
		return "", nil
	}
	end := offset + length
	if end > int64(len(content)) {
		end = int64(len(content))
	}
	return base64.StdEncoding.EncodeToString(content[offset:end]), nil
}

func (localProviderStorageAdapter) BeginWrite(string, bool) (string, error) {
	return "", errors.New("unused")
}
func (localProviderStorageAdapter) WriteChunk(string, string) error { return errors.New("unused") }
func (localProviderStorageAdapter) FinishWrite(string) error        { return errors.New("unused") }
func (localProviderStorageAdapter) CancelWrite(string) error        { return nil }
func (localProviderStorageAdapter) MkdirAll(string) error           { return errors.New("unused") }
func (localProviderStorageAdapter) Remove(string) error             { return errors.New("unused") }
func (localProviderStorageAdapter) URI(value string) (string, error) {
	return "content://test/" + value, nil
}

func TestLocalProviderReadsSAFImageDirectory(t *testing.T) {
	root := "/androidtv/0123456789abcdef"
	mangaPath := root + "/My Manga"
	chapterPath := mangaPath + "/Chapter 1"
	imagePath := chapterPath + "/001.jpg"
	androidtvstorage.SetAdapter(localProviderStorageAdapter{
		directories: map[string][]androidtvstorage.Entry{
			root:        {{Name: "My Manga", IsDirectory: true}},
			mangaPath:   {{Name: "Chapter 1", IsDirectory: true}},
			chapterPath: {{Name: "001.jpg", Size: int64(len("page bytes"))}},
		},
		files: map[string][]byte{imagePath: []byte("page bytes")},
	})
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })

	logger := zerolog.New(io.Discard)
	provider := NewLocal(t.TempDir(), &logger).(*Local)
	provider.SetSourceDirectory(root)

	results, err := provider.Search(hibikemanga.SearchOptions{})
	require.NoError(t, err)
	require.Len(t, results, 1)
	require.Equal(t, "My Manga", results[0].ID)

	chapters, err := provider.FindChapters(results[0].ID)
	require.NoError(t, err)
	require.Len(t, chapters, 1)
	pages, err := provider.FindChapterPages(chapters[0].ID)
	require.NoError(t, err)
	require.Len(t, pages, 1)
	page, err := provider.ReadPage(pages[0].URL)
	require.NoError(t, err)
	defer page.Close()
	content, err := io.ReadAll(page)
	require.NoError(t, err)
	require.Equal(t, "page bytes", string(content))
}

func TestLocalProviderStagesSAFZipArchive(t *testing.T) {
	var archive bytes.Buffer
	writer := zip.NewWriter(&archive)
	file, err := writer.Create("001.jpg")
	require.NoError(t, err)
	_, err = file.Write([]byte("archive page"))
	require.NoError(t, err)
	require.NoError(t, writer.Close())

	root := "/androidtv/0123456789abcdef"
	mangaPath := root + "/My Manga"
	archivePath := mangaPath + "/Chapter 1.cbz"
	androidtvstorage.SetAdapter(localProviderStorageAdapter{
		directories: map[string][]androidtvstorage.Entry{
			root:      {{Name: "My Manga", IsDirectory: true}},
			mangaPath: {{Name: "Chapter 1.cbz", Size: int64(archive.Len())}},
		},
		files: map[string][]byte{archivePath: archive.Bytes()},
	})
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })

	logger := zerolog.New(io.Discard)
	cacheDir := t.TempDir()
	provider := NewLocal(t.TempDir(), &logger, cacheDir).(*Local)
	provider.SetSourceDirectory(root)
	chapters, err := provider.FindChapters("My Manga")
	require.NoError(t, err)
	require.Len(t, chapters, 1)
	pages, err := provider.FindChapterPages(chapters[0].ID)
	require.NoError(t, err)
	require.Len(t, pages, 1)
	page, err := provider.ReadPage(pages[0].URL)
	require.NoError(t, err)
	defer page.Close()
	content, err := io.ReadAll(page)
	require.NoError(t, err)
	require.Equal(t, "archive page", string(content))
	require.FileExists(t, provider.currentStagedPath)
}
