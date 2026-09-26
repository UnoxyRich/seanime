package directstream

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"seanime/internal/androidtvstorage"
	"seanime/internal/library/anime"
	"seanime/internal/util"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

type directStreamStorageAdapter struct {
	androidtvstorage.Adapter
	files       map[string][]byte
	sizes       map[string]int64
	unavailable bool
	reads       int
}

func (a *directStreamStorageAdapter) List(dir string) (string, error) {
	if a.unavailable {
		return "", os.ErrPermission
	}
	entries := []androidtvstorage.Entry{}
	for path := range a.files {
		if filepath.Dir(path) == dir {
			entries = append(entries, androidtvstorage.Entry{Name: filepath.Base(path)})
		}
	}
	data, err := json.Marshal(entries)
	return string(data), err
}

func (a *directStreamStorageAdapter) Stat(path string) (string, error) {
	if a.unavailable {
		return "", os.ErrPermission
	}
	content, ok := a.files[path]
	if !ok {
		return "", os.ErrNotExist
	}
	size := int64(len(content))
	if override, ok := a.sizes[path]; ok {
		size = override
	}
	data, err := json.Marshal(androidtvstorage.Entry{Name: filepath.Base(path), Size: size})
	return string(data), err
}

func (a *directStreamStorageAdapter) ReadAt(path string, offset, length int64) (string, error) {
	if a.unavailable {
		return "", os.ErrPermission
	}
	a.reads++
	content, ok := a.files[path]
	if !ok {
		return "", os.ErrNotExist
	}
	if offset >= int64(len(content)) {
		return "", nil
	}
	end := min(offset+length, int64(len(content)))
	return base64.StdEncoding.EncodeToString(content[offset:end]), nil
}

func installDirectStreamStorage(t *testing.T, files map[string][]byte) *directStreamStorageAdapter {
	t.Helper()
	a := &directStreamStorageAdapter{files: files, sizes: map[string]int64{}}
	androidtvstorage.SetAdapter(a)
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })
	return a
}

func storageLocalStream(path string) *LocalFileStream {
	return &LocalFileStream{localFile: &anime.LocalFile{Path: path}, BaseStream: BaseStream{
		manager: &Manager{}, logger: util.NewLogger(),
	}}
}

func TestSAFDirectStreamServesMetadataAndRanges(t *testing.T) {
	const path = "/androidtv/root/Series/episode.mp4"
	installDirectStreamStorage(t, map[string][]byte{path: []byte("0123456789")})
	stream := storageLocalStream(path)
	info, err := stream.LoadPlaybackInfo()
	require.NoError(t, err)
	require.Equal(t, int64(10), info.ContentLength)
	require.Equal(t, path, info.StreamPath)
	require.Equal(t, path, info.PlaybackURI)
	require.Contains(t, info.StreamURL, "/api/v1/directstream/stream?id=")
	for _, tc := range []struct {
		name, method, query, byteRange, contentRange, body string
		status                                             int
	}{
		{"head", http.MethodHead, "", "", "", "", http.StatusOK},
		{"whole", http.MethodGet, "", "", "", "0123456789", http.StatusOK},
		{"seek", http.MethodGet, "", "bytes=3-6", "bytes 3-6/10", "3456", http.StatusPartialContent},
		{"suffix", http.MethodGet, "", "bytes=-2", "bytes 8-9/10", "89", http.StatusPartialContent},
		{"thumbnail", http.MethodGet, "?thumbnail=true", "bytes=1-2", "bytes 1-2/10", "12", http.StatusPartialContent},
		{"beyond-end", http.MethodGet, "", "bytes=12-14", "bytes */10", "", http.StatusRequestedRangeNotSatisfiable},
	} {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(tc.method, "/stream"+tc.query, nil)
			req.Header.Set("Range", tc.byteRange)
			rec := httptest.NewRecorder()
			stream.GetStreamHandler().ServeHTTP(rec, req)
			require.Equal(t, tc.status, rec.Code)
			require.Equal(t, tc.contentRange, rec.Header().Get("Content-Range"))
			if tc.status < 400 {
				require.Equal(t, tc.body, rec.Body.String())
				require.Equal(t, "bytes", rec.Header().Get("Accept-Ranges"))
			}
			if tc.method == http.MethodHead {
				require.Equal(t, "10", rec.Header().Get("Content-Length"))
			}
		})
	}
}

func TestSAFDirectStreamReadsMatchingSidecarSubtitles(t *testing.T) {
	const dir = "/androidtv/root/Series/"
	installDirectStreamStorage(t, map[string][]byte{
		dir + "episode.mp4":     []byte("video"),
		dir + "episode.eng.srt": []byte("1\n00:00:00,000 --> 00:00:01,000\nHello\n"),
		dir + "episode.jpn.ass": []byte("[Script Info]\nTitle: Japanese"),
		dir + "another.eng.srt": []byte("Wrong episode"),
	})
	info, err := storageLocalStream(dir + "episode.mp4").LoadPlaybackInfo()
	require.NoError(t, err)
	require.Len(t, info.SubtitleTracks, 2)
	require.Equal(t, "eng", info.SubtitleTracks[0].Language)
	require.Equal(t, "srt", *info.SubtitleTracks[0].Format)
	require.Contains(t, *info.SubtitleTracks[0].Content, "Hello")
	require.Equal(t, "ass", *info.SubtitleTracks[1].Format)
	require.Contains(t, *info.SubtitleTracks[1].Content, "Japanese")
}

func TestSAFDirectStreamBoundsSubtitlesAndRecoversStorageAccess(t *testing.T) {
	const path = "/androidtv/root/episode.mp4"
	const subtitle = "/androidtv/root/episode.srt"
	a := installDirectStreamStorage(t, map[string][]byte{path: []byte("video"), subtitle: []byte("subtitle")})
	a.sizes[subtitle] = maxLocalSubtitleFileSize + 1
	_, err := readLocalSubtitleFile(subtitle)
	require.Error(t, err)
	require.Zero(t, a.reads, "oversized sidecars must be rejected before reading")
	stream := storageLocalStream(path)
	reader, err := stream.newReader()
	require.NoError(t, err)
	defer reader.Close()
	a.unavailable = true
	_, err = reader.Read(make([]byte, 1))
	require.True(t, errors.Is(err, os.ErrPermission))
	for _, method := range []string{http.MethodGet, http.MethodHead} {
		rec := httptest.NewRecorder()
		storageLocalStream(path).GetStreamHandler().ServeHTTP(rec, httptest.NewRequest(method, "/stream", nil))
		require.Equal(t, http.StatusInternalServerError, rec.Code)
	}
	a.unavailable = false
	data, err := io.ReadAll(reader)
	require.NoError(t, err)
	require.Equal(t, "video", string(data))
	_, err = storageLocalStream(path).LoadPlaybackInfo()
	require.NoError(t, err)
}

func TestLocalStreamFileReaderRetainsFilesystemSupport(t *testing.T) {
	path := filepath.Join(t.TempDir(), "episode.mp4")
	require.NoError(t, os.WriteFile(path, []byte("local media"), 0600))
	reader, err := openLocalStreamFile(path)
	require.NoError(t, err)
	defer reader.Close()
	data, err := io.ReadAll(reader)
	require.NoError(t, err)
	require.Equal(t, "local media", string(data))
	subtitle := strings.TrimSuffix(path, ".mp4") + ".srt"
	require.NoError(t, os.WriteFile(subtitle, []byte("local subtitle"), 0600))
	data, err = readLocalSubtitleFile(subtitle)
	require.NoError(t, err)
	require.Equal(t, "local subtitle", string(data))
}
