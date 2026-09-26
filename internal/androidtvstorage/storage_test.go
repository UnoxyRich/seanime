package androidtvstorage

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"testing"
)

type fakeAdapter struct {
	entries map[string][]Entry
	files   map[string][]byte
	writes  map[string]*bytes.Buffer
}

func (f *fakeAdapter) List(path string) (string, error) {
	payload, err := json.Marshal(f.entries[path])
	return string(payload), err
}

func (f *fakeAdapter) Stat(path string) (string, error) {
	if _, ok := f.entries[path]; ok {
		payload, _ := json.Marshal(Entry{Name: path, IsDirectory: true})
		return string(payload), nil
	}
	if content, ok := f.files[path]; ok {
		payload, _ := json.Marshal(Entry{Name: path, Size: int64(len(content))})
		return string(payload), nil
	}
	return "", errors.New("not found")
}

func (f *fakeAdapter) ReadAt(path string, offset, length int64) (string, error) {
	content, ok := f.files[path]
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

func (f *fakeAdapter) BeginWrite(path string, truncate bool) (string, error) {
	handle := "write-1"
	f.writes[handle] = &bytes.Buffer{}
	if !truncate {
		f.writes[handle].Write(f.files[path])
	}
	return handle, nil
}

func (f *fakeAdapter) WriteChunk(handle, data string) error {
	decoded, err := base64.StdEncoding.DecodeString(data)
	if err != nil {
		return err
	}
	_, err = f.writes[handle].Write(decoded)
	return err
}

func (f *fakeAdapter) FinishWrite(handle string) error {
	f.files["/androidtv/0123456789abcdef/output.mkv"] = f.writes[handle].Bytes()
	delete(f.writes, handle)
	return nil
}

func (f *fakeAdapter) CancelWrite(handle string) error {
	delete(f.writes, handle)
	return nil
}

func (f *fakeAdapter) MkdirAll(string) error { return nil }
func (f *fakeAdapter) Remove(string) error   { return nil }
func (f *fakeAdapter) URI(path string) (string, error) {
	return "content://test/" + path, nil
}

func TestCleanPathRejectsTraversal(t *testing.T) {
	for _, input := range []string{
		"/androidtv/../private",
		"/androidtv/root/../other",
		"/androidtv/root//file.mkv",
	} {
		if _, err := CleanPath(input); err == nil {
			t.Errorf("CleanPath(%q) succeeded, want error", input)
		}
	}
}

func TestWalkMediaFilesUsesPersistedTreePaths(t *testing.T) {
	old := adapter
	t.Cleanup(func() { SetAdapter(old) })

	fake := &fakeAdapter{
		entries: map[string][]Entry{
			"/androidtv/0123456789abcdef": {{Name: "Series", IsDirectory: true}},
			"/androidtv/0123456789abcdef/Series": {
				{Name: "episode.mkv"},
				{Name: "cover.jpg"},
			},
		},
		files: map[string][]byte{
			"/androidtv/0123456789abcdef/Series/episode.mkv": []byte("media"),
			"/androidtv/0123456789abcdef/Series/cover.jpg":   []byte("image"),
		},
		writes: make(map[string]*bytes.Buffer),
	}
	SetAdapter(fake)

	paths, err := WalkMediaFiles("/androidtv/0123456789abcdef", func(name string) bool {
		return strings.HasSuffix(name, ".mkv")
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(paths) != 1 || paths[0] != "/androidtv/0123456789abcdef/Series/episode.mkv" {
		t.Fatalf("unexpected media paths: %#v", paths)
	}
}

func TestWriteFromStreamsBase64ChunksAndCancelsOnFailure(t *testing.T) {
	old := adapter
	t.Cleanup(func() { SetAdapter(old) })
	fake := &fakeAdapter{files: make(map[string][]byte), writes: make(map[string]*bytes.Buffer)}
	SetAdapter(fake)

	count, err := WriteFrom("/androidtv/0123456789abcdef/output.mkv", strings.NewReader("episode"), true)
	if err != nil || count != int64(len("episode")) {
		t.Fatalf("WriteFrom returned count=%d err=%v", count, err)
	}
	if string(fake.files["/androidtv/0123456789abcdef/output.mkv"]) != "episode" {
		t.Fatal("written content did not reach the adapter")
	}

	broken := readerWithError{}
	_, err = WriteFrom("/androidtv/0123456789abcdef/output.mkv", broken, true)
	if err == nil {
		t.Fatal("expected source reader error")
	}
	if len(fake.writes) != 0 {
		t.Fatal("failed write was not cancelled")
	}
}

func TestReaderAtSupportsPartialRanges(t *testing.T) {
	old := adapter
	t.Cleanup(func() { SetAdapter(old) })
	filePath := "/androidtv/0123456789abcdef/video.mkv"
	SetAdapter(&fakeAdapter{
		files:   map[string][]byte{filePath: []byte("abcdefgh")},
		entries: make(map[string][]Entry),
		writes:  make(map[string]*bytes.Buffer),
	})

	reader, size, err := NewReaderAt(filePath)
	if err != nil || size != 8 {
		t.Fatalf("NewReaderAt returned size=%d err=%v", size, err)
	}
	buffer := make([]byte, 5)
	n, err := reader.ReadAt(buffer, 2)
	if err != nil || n != 5 || string(buffer) != "cdefg" {
		t.Fatalf("ReadAt returned n=%d data=%q err=%v", n, buffer, err)
	}
	buffer = make([]byte, 4)
	n, err = reader.ReadAt(buffer, 6)
	if err != io.EOF || n != 2 || string(buffer[:n]) != "gh" {
		t.Fatalf("trailing ReadAt returned n=%d data=%q err=%v", n, buffer[:n], err)
	}
}

type readerWithError struct{}

func (readerWithError) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }
