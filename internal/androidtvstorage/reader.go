package androidtvstorage

import (
	"fmt"
	"io"
	"os"
	"sync/atomic"
)

// ReadFile reads a persisted document without staging it in the app cache.
// Ordinary paths retain os.ReadFile semantics for shared callers.
func ReadFile(path string) ([]byte, error) {
	if !IsPath(path) {
		return os.ReadFile(path)
	}
	reader, err := Open(path)
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	return io.ReadAll(reader)
}

// Rename moves a regular document through the provider's transactional writer.
// The source is kept until the complete destination has been committed. SAF
// providers need not expose a native rename operation, so this streams directly
// between documents and does not require enough app cache for the media file.
func Rename(oldPath, newPath string) error {
	if !IsPath(oldPath) && !IsPath(newPath) {
		return os.Rename(oldPath, newPath)
	}
	if !IsPath(oldPath) || !IsPath(newPath) {
		return fmt.Errorf("cannot rename between Android TV storage and a local filesystem")
	}
	if _, err := CleanPath(oldPath); err != nil {
		return err
	}
	if _, err := CleanPath(newPath); err != nil {
		return err
	}
	if oldPath == newPath {
		return nil
	}
	reader, err := Open(oldPath)
	if err != nil {
		return err
	}
	defer reader.Close()
	if _, err := WriteFrom(newPath, reader, true); err != nil {
		return fmt.Errorf("could not commit renamed document: %w", err)
	}
	if err := Remove(oldPath); err != nil {
		return fmt.Errorf("renamed document saved to %s; original remains at %s: %w", newPath, oldPath, err)
	}
	return nil
}

// Open exposes a document as a seekable stream without copying it into cache.
// The Android adapter owns and closes each range request's document descriptor.
func Open(path string) (io.ReadSeekCloser, error) {
	reader, size, err := NewReaderAt(path)
	if err != nil {
		return nil, err
	}
	return &documentReader{section: io.NewSectionReader(reader, 0, size)}, nil
}

type documentReader struct {
	section *io.SectionReader
	closed  atomic.Bool
}

func (r *documentReader) Read(data []byte) (int, error) {
	if r.closed.Load() {
		return 0, os.ErrClosed
	}
	return r.section.Read(data)
}

func (r *documentReader) Seek(offset int64, whence int) (int64, error) {
	if r.closed.Load() {
		return 0, os.ErrClosed
	}
	return r.section.Seek(offset, whence)
}

// Close prevents further requests. An in-flight JNI range read owns its own
// descriptor and finishes before its resources can be released by Android.
func (r *documentReader) Close() error {
	r.closed.Store(true)
	return nil
}
