package androidtvstorage

import (
	"errors"
	"io"
	"os"
	"testing"
)

func TestOpenDocumentSeeksReadsAndCloses(t *testing.T) {
	old := adapter
	t.Cleanup(func() { SetAdapter(old) })
	const path = "/androidtv/root/video.mp4"
	SetAdapter(&fakeAdapter{files: map[string][]byte{path: []byte("0123456789")}})
	reader, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	if size, err := reader.Seek(0, io.SeekEnd); err != nil || size != 10 {
		t.Fatalf("size=%d err=%v", size, err)
	}
	if _, err := reader.Seek(-3, io.SeekEnd); err != nil {
		t.Fatal(err)
	}
	data, err := io.ReadAll(reader)
	if err != nil || string(data) != "789" {
		t.Fatalf("tail=%q err=%v", data, err)
	}
	if err := reader.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := reader.Read(make([]byte, 1)); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("read after close: %v", err)
	}
	if _, err := reader.Seek(0, io.SeekStart); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("seek after close: %v", err)
	}
}

type metadataAdapter struct {
	Adapter
	metadata string
}

func (a metadataAdapter) Stat(string) (string, error) { return a.metadata, nil }

func TestOpenDocumentRejectsDirectoryAndUnknownSize(t *testing.T) {
	old := adapter
	t.Cleanup(func() { SetAdapter(old) })
	for _, metadata := range []string{`{"isDirectory":true}`, `{"size":-1}`} {
		SetAdapter(metadataAdapter{metadata: metadata})
		if _, err := Open("/androidtv/root/video.mp4"); err == nil {
			t.Fatalf("unexpectedly opened %s", metadata)
		}
	}
}
