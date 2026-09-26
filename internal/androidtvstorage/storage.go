package androidtvstorage

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
)

const (
	PathPrefix = "/androidtv/"
	chunkSize  = 256 * 1024
)

var (
	ErrUnavailable = errors.New("Android TV storage adapter is unavailable")
	adapterMu      sync.RWMutex
	adapter        Adapter
)

// Adapter is implemented by the Android host using persisted SAF grants.
// Payloads are JSON or base64 strings because gomobile does not bind []byte.
type Adapter interface {
	List(path string) (string, error)
	Stat(path string) (string, error)
	ReadAt(path string, offset int64, length int64) (string, error)
	BeginWrite(path string, truncate bool) (string, error)
	WriteChunk(handle string, data string) error
	FinishWrite(handle string) error
	CancelWrite(handle string) error
	MkdirAll(path string) error
	Remove(path string) error
	URI(path string) (string, error)
}

type Entry struct {
	Name        string `json:"name"`
	IsDirectory bool   `json:"isDirectory"`
	Size        int64  `json:"size"`
	ModTime     int64  `json:"modTime"`
}

type ReaderAt struct {
	path string
	size int64
}

func NewReaderAt(path string) (*ReaderAt, int64, error) {
	entry, err := Stat(path)
	if err != nil {
		return nil, 0, err
	}
	if entry.IsDirectory {
		return nil, 0, fmt.Errorf("Android TV storage path is a directory: %s", path)
	}
	return &ReaderAt{path: path, size: entry.Size}, entry.Size, nil
}

func (r *ReaderAt) ReadAt(buffer []byte, offset int64) (int, error) {
	if offset < 0 {
		return 0, fmt.Errorf("invalid Android TV storage read offset")
	}
	if offset >= r.size {
		return 0, io.EOF
	}
	requested := int64(len(buffer))
	if requested > r.size-offset {
		requested = r.size - offset
	}
	total := 0
	for int64(total) < requested {
		length := requested - int64(total)
		if length > chunkSize {
			length = chunkSize
		}
		part, err := ReadAt(r.path, offset+int64(total), length)
		if err != nil {
			return total, err
		}
		if len(part) == 0 {
			return total, io.ErrUnexpectedEOF
		}
		total += copy(buffer[total:], part)
	}
	if total < len(buffer) {
		return total, io.EOF
	}
	return total, nil
}

func CopyToLocal(path, destination string) (int64, error) {
	reader, size, err := NewReaderAt(path)
	if err != nil {
		return 0, err
	}
	if err := os.MkdirAll(filepath.Dir(destination), 0700); err != nil {
		return 0, err
	}
	output, err := os.CreateTemp(filepath.Dir(destination), ".seanime-saf-*.part")
	if err != nil {
		return 0, err
	}
	temporary := output.Name()
	complete := false
	defer func() {
		_ = output.Close()
		if !complete {
			_ = os.Remove(temporary)
		}
	}()
	section := io.NewSectionReader(reader, 0, size)
	written, err := io.CopyN(output, section, size)
	if err != nil {
		return written, err
	}
	if err := output.Sync(); err != nil {
		return written, err
	}
	if err := output.Close(); err != nil {
		return written, err
	}
	if err := os.Rename(temporary, destination); err != nil {
		return written, err
	}
	complete = true
	return written, nil
}

func SetAdapter(next Adapter) {
	adapterMu.Lock()
	adapter = next
	adapterMu.Unlock()
}

func IsPath(path string) bool {
	return strings.HasPrefix(path, PathPrefix)
}

func CleanPath(path string) (string, error) {
	cleaned := path
	if !strings.HasPrefix(cleaned, PathPrefix) {
		return "", fmt.Errorf("not an Android TV storage path: %s", path)
	}
	for _, segment := range strings.Split(strings.TrimPrefix(cleaned, PathPrefix), "/") {
		if segment == "" || segment == "." || segment == ".." {
			return "", fmt.Errorf("invalid Android TV storage path: %s", path)
		}
	}
	if filepath.ToSlash(filepath.Clean(cleaned)) != cleaned {
		return "", fmt.Errorf("invalid Android TV storage path: %s", path)
	}
	return cleaned, nil
}

func List(path string) ([]Entry, error) {
	cleaned, err := CleanPath(path)
	if err != nil {
		return nil, err
	}
	a, err := currentAdapter()
	if err != nil {
		return nil, err
	}
	payload, err := a.List(cleaned)
	if err != nil {
		return nil, err
	}
	entries := make([]Entry, 0)
	if err := json.Unmarshal([]byte(payload), &entries); err != nil {
		return nil, fmt.Errorf("decode Android TV storage listing: %w", err)
	}
	for _, entry := range entries {
		if entry.Name == "" || entry.Name == "." || entry.Name == ".." || strings.Contains(entry.Name, "/") {
			return nil, fmt.Errorf("Android TV storage returned an invalid entry name")
		}
	}
	return entries, nil
}

func Stat(path string) (Entry, error) {
	cleaned, err := CleanPath(path)
	if err != nil {
		return Entry{}, err
	}
	a, err := currentAdapter()
	if err != nil {
		return Entry{}, err
	}
	payload, err := a.Stat(cleaned)
	if err != nil {
		return Entry{}, err
	}
	var entry Entry
	if err := json.Unmarshal([]byte(payload), &entry); err != nil {
		return Entry{}, fmt.Errorf("decode Android TV storage metadata: %w", err)
	}
	return entry, nil
}

func ReadAt(path string, offset, length int64) ([]byte, error) {
	if offset < 0 || length < 0 || length > chunkSize {
		return nil, fmt.Errorf("invalid Android TV storage read range")
	}
	cleaned, err := CleanPath(path)
	if err != nil {
		return nil, err
	}
	a, err := currentAdapter()
	if err != nil {
		return nil, err
	}
	payload, err := a.ReadAt(cleaned, offset, length)
	if err != nil {
		return nil, err
	}
	data, err := base64.StdEncoding.DecodeString(payload)
	if err != nil {
		return nil, fmt.Errorf("decode Android TV storage file data: %w", err)
	}
	if int64(len(data)) > length {
		return nil, fmt.Errorf("Android TV storage returned more data than requested")
	}
	return data, nil
}

func URI(path string) (string, error) {
	cleaned, err := CleanPath(path)
	if err != nil {
		return "", err
	}
	a, err := currentAdapter()
	if err != nil {
		return "", err
	}
	return a.URI(cleaned)
}

func WalkMediaFiles(root string, isMedia func(string) bool) ([]string, error) {
	if !IsPath(root) {
		return nil, fmt.Errorf("not an Android TV storage path: %s", root)
	}
	rootInfo, err := Stat(root)
	if err != nil {
		return nil, err
	}
	if !rootInfo.IsDirectory {
		return nil, fmt.Errorf("Android TV storage path is not a directory: %s", root)
	}
	files := make([]string, 0)
	var walk func(string) error
	walk = func(directory string) error {
		entries, err := List(directory)
		if err != nil {
			return err
		}
		for _, entry := range entries {
			child := path.Join(directory, entry.Name)
			if entry.IsDirectory {
				if err := walk(child); err != nil {
					return err
				}
				continue
			}
			if isMedia(entry.Name) {
				files = append(files, child)
			}
		}
		return nil
	}
	if err := walk(root); err != nil {
		return nil, fmt.Errorf("could not traverse Android TV storage directory %s: %w", root, err)
	}
	return files, nil
}

func DirSize(root string) (uint64, error) {
	if !IsPath(root) {
		return 0, fmt.Errorf("not an Android TV storage path: %s", root)
	}
	rootInfo, err := Stat(root)
	if err != nil {
		return 0, err
	}
	if !rootInfo.IsDirectory {
		return 0, fmt.Errorf("Android TV storage path is not a directory: %s", root)
	}
	var size uint64
	var walk func(string) error
	walk = func(directory string) error {
		entries, err := List(directory)
		if err != nil {
			return err
		}
		for _, entry := range entries {
			if entry.IsDirectory {
				if err := walk(path.Join(directory, entry.Name)); err != nil {
					return err
				}
			} else if entry.Size > 0 {
				size += uint64(entry.Size)
			}
		}
		return nil
	}
	if err := walk(root); err != nil {
		return 0, err
	}
	return size, nil
}

func MkdirAll(path string) error {
	cleaned, err := CleanPath(path)
	if err != nil {
		return err
	}
	a, err := currentAdapter()
	if err != nil {
		return err
	}
	return a.MkdirAll(cleaned)
}

func Remove(path string) error {
	cleaned, err := CleanPath(path)
	if err != nil {
		return err
	}
	a, err := currentAdapter()
	if err != nil {
		return err
	}
	return a.Remove(cleaned)
}

func WriteFrom(path string, source io.Reader, truncate bool) (int64, error) {
	cleaned, err := CleanPath(path)
	if err != nil {
		return 0, err
	}
	a, err := currentAdapter()
	if err != nil {
		return 0, err
	}
	handle, err := a.BeginWrite(cleaned, truncate)
	if err != nil {
		return 0, err
	}
	complete := false
	defer func() {
		if !complete {
			_ = a.CancelWrite(handle)
		}
	}()
	buffer := make([]byte, chunkSize)
	var written int64
	for {
		n, readErr := source.Read(buffer)
		if n > 0 {
			if err := a.WriteChunk(handle, base64.StdEncoding.EncodeToString(buffer[:n])); err != nil {
				return written, err
			}
			written += int64(n)
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return written, readErr
		}
	}
	if err := a.FinishWrite(handle); err != nil {
		return written, err
	}
	complete = true
	return written, nil
}

func currentAdapter() (Adapter, error) {
	adapterMu.RLock()
	defer adapterMu.RUnlock()
	if adapter == nil {
		return nil, ErrUnavailable
	}
	return adapter, nil
}
