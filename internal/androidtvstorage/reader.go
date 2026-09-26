package androidtvstorage

import (
	"io"
	"os"
	"sync/atomic"
)

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
