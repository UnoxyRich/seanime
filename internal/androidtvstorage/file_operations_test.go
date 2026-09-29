package androidtvstorage

import (
	"bytes"
	"errors"
	"testing"
)

type renameStorageAdapter struct {
	*fakeAdapter
	destination string
	commitError error
	removeError error
}

func (a *renameStorageAdapter) BeginWrite(path string, truncate bool) (string, error) {
	a.destination = path
	return a.fakeAdapter.BeginWrite(path, truncate)
}

func (a *renameStorageAdapter) FinishWrite(handle string) error {
	if a.commitError != nil {
		return a.commitError
	}
	a.files[a.destination] = append([]byte(nil), a.writes[handle].Bytes()...)
	delete(a.writes, handle)
	return nil
}

func (a *renameStorageAdapter) Remove(path string) error {
	if a.removeError != nil {
		return a.removeError
	}
	delete(a.files, path)
	return nil
}

func TestDocumentRenameCommitsBeforeDeletingTheOriginal(t *testing.T) {
	const original = "/androidtv/root/episode.mkv"
	const renamed = "/androidtv/root/renamed.mkv"
	payload := bytes.Repeat([]byte("episode"), chunkSize)
	for _, testCase := range []struct {
		name        string
		commitError error
		removeError error
	}{
		{name: "complete"},
		{name: "provider write fails", commitError: errors.New("storage disconnected")},
		{name: "provider delete fails", removeError: errors.New("read-only document")},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			storage := &renameStorageAdapter{
				fakeAdapter: &fakeAdapter{
					files:  map[string][]byte{original: payload},
					writes: make(map[string]*bytes.Buffer),
				},
				commitError: testCase.commitError,
				removeError: testCase.removeError,
			}
			SetAdapter(storage)
			t.Cleanup(func() { SetAdapter(nil) })
			err := Rename(original, renamed)
			if testCase.commitError != nil || testCase.removeError != nil {
				if err == nil || !bytes.Equal(storage.files[original], payload) {
					t.Fatalf("provider failure lost the original: err=%v", err)
				}
			} else if err != nil || storage.files[original] != nil {
				t.Fatalf("rename did not remove the original after commit: err=%v", err)
			}
			if testCase.commitError == nil && !bytes.Equal(storage.files[renamed], payload) {
				t.Fatal("renamed document was incomplete")
			}
			if len(storage.writes) != 0 {
				t.Fatal("unfinished write handles remained")
			}
		})
	}
}

func TestDocumentReadFileReadsImportDataAndSameNameRenameKeepsIt(t *testing.T) {
	const path = "/androidtv/root/library.json"
	payload := []byte(`[{"path":"/androidtv/root/episode.mkv"}]`)
	storage := &renameStorageAdapter{fakeAdapter: &fakeAdapter{
		files: map[string][]byte{path: payload}, writes: make(map[string]*bytes.Buffer),
	}}
	SetAdapter(storage)
	t.Cleanup(func() { SetAdapter(nil) })
	data, err := ReadFile(path)
	if err != nil || !bytes.Equal(data, payload) {
		t.Fatalf("import data=%q err=%v", data, err)
	}
	if err := Rename(path, path); err != nil || !bytes.Equal(storage.files[path], payload) {
		t.Fatalf("same-name rename altered the document: %v", err)
	}
}
