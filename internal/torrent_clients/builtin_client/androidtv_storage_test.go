package builtin_client

import (
	"bytes"
	"crypto/sha1"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"seanime/internal/androidtvstorage"
	"seanime/internal/database/db"

	anacrolix "github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/bencode"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/rs/zerolog"
	"github.com/stretchr/testify/require"
)

type safTorrentTestStorage struct {
	mu          sync.Mutex
	files       map[string][]byte
	directories map[string]bool
	writes      map[string]*bytes.Buffer
	writePaths  map[string]string
	sequence    int
	failCommit  bool
	blockWrite  <-chan struct{}
	writeReady  chan struct{}
	canceled    int
}

func newSAFTorrentTestStorage(t *testing.T) *safTorrentTestStorage {
	s := &safTorrentTestStorage{
		files: make(map[string][]byte), directories: make(map[string]bool),
		writes: make(map[string]*bytes.Buffer), writePaths: make(map[string]string),
	}
	androidtvstorage.SetAdapter(s)
	t.Cleanup(func() { androidtvstorage.SetAdapter(nil) })
	return s
}

func (s *safTorrentTestStorage) List(directory string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var entries []androidtvstorage.Entry
	for filename := range s.files {
		if path.Dir(filename) == directory {
			entries = append(entries, androidtvstorage.Entry{Name: path.Base(filename)})
		}
	}
	for filename := range s.directories {
		if path.Dir(filename) == directory {
			entries = append(entries, androidtvstorage.Entry{Name: path.Base(filename), IsDirectory: true})
		}
	}
	payload, err := json.Marshal(entries)
	return string(payload), err
}

func (s *safTorrentTestStorage) Stat(filename string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry := androidtvstorage.Entry{Name: path.Base(filename)}
	if s.directories[filename] {
		entry.IsDirectory = true
	} else if content, ok := s.files[filename]; ok {
		entry.Size = int64(len(content))
	} else {
		return "", os.ErrNotExist
	}
	payload, err := json.Marshal(entry)
	return string(payload), err
}

func (s *safTorrentTestStorage) ReadAt(filename string, offset, length int64) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	content, ok := s.files[filename]
	if !ok {
		return "", os.ErrNotExist
	}
	if offset >= int64(len(content)) {
		return "", nil
	}
	return base64.StdEncoding.EncodeToString(content[offset:min(offset+length, int64(len(content)))]), nil
}

func (s *safTorrentTestStorage) BeginWrite(filename string, _ bool) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sequence++
	handle := fmt.Sprint(s.sequence)
	s.writes[handle], s.writePaths[handle] = &bytes.Buffer{}, filename
	return handle, nil
}

func (s *safTorrentTestStorage) WriteChunk(handle, value string) error {
	s.mu.Lock()
	blocked, ready := s.blockWrite, s.writeReady
	s.mu.Unlock()
	if blocked != nil {
		select {
		case ready <- struct{}{}:
		default:
		}
		<-blocked
	}
	content, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err = s.writes[handle].Write(content)
	return err
}

func (s *safTorrentTestStorage) FinishWrite(handle string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failCommit {
		return errors.New("USB storage rejected the write")
	}
	s.files[s.writePaths[handle]] = append([]byte(nil), s.writes[handle].Bytes()...)
	delete(s.writes, handle)
	delete(s.writePaths, handle)
	return nil
}

func (s *safTorrentTestStorage) CancelWrite(handle string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.canceled++
	delete(s.writes, handle)
	delete(s.writePaths, handle)
	return nil
}

func (s *safTorrentTestStorage) MkdirAll(directory string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for current := directory; androidtvstorage.IsPath(current); current = path.Dir(current) {
		s.directories[current] = true
	}
	return nil
}

func (s *safTorrentTestStorage) Remove(filename string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.files, filename)
	delete(s.directories, filename)
	return nil
}

func (s *safTorrentTestStorage) URI(filename string) (string, error) {
	return "content://torrent-test/" + filename, nil
}

func (s *safTorrentTestStorage) content(filename string) []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]byte(nil), s.files[filename]...)
}

func newSAFTorrentTestClient(t *testing.T, database *db.Database, directory string) *Client {
	logger := zerolog.Nop()
	client, err := New(&NewClientOptions{Logger: &logger, Database: database, Dir: directory, Port: -1, DisableNetwork: true})
	if err != nil && strings.Contains(err.Error(), "operation not permitted") {
		t.Skip("environment does not permit a localhost torrent listener")
	}
	require.NoError(t, err)
	t.Cleanup(client.Close)
	return client
}

func addCompleteSAFTorrentFixture(t *testing.T, client *Client, destination string) (*anacrolix.Torrent, []byte, string) {
	payload := bytes.Repeat([]byte("verified torrent data"), 32_000)
	pieceHash := sha1.Sum(payload)
	info := &metainfo.Info{Name: "episode.mkv", Length: int64(len(payload)), PieceLength: int64(len(payload)), Pieces: pieceHash[:]}
	encoded, err := bencode.Marshal(info)
	require.NoError(t, err)
	metadata := &metainfo.MetaInfo{InfoBytes: encoded}
	torrent, err := client.AddMagnet(metadata.Magnet(nil, info).String(), destination)
	require.NoError(t, err)
	hash := torrent.InfoHash().HexString()
	entry, err := client.getEntry(hash)
	require.NoError(t, err)
	stage, err := client.torrentDataDirectory(entry.model)
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(filepath.Join(stage, info.Name), payload, 0600))
	require.NoError(t, torrent.SetInfoBytes(encoded))
	require.Eventually(t, func() bool { return torrent.BytesCompleted() == int64(len(payload)) }, 20*time.Second, 20*time.Millisecond)
	return torrent, payload, hash
}

func triggerSAFTorrentExport(t *testing.T, client *Client, hash string) {
	entry, err := client.getEntry(hash)
	require.NoError(t, err)
	entry.safMu.Lock()
	entry.safLastAttempt = time.Time{}
	entry.safMu.Unlock()
	client.exportSAFTorrents()
	require.Eventually(t, func() bool {
		entry.safMu.Lock()
		defer entry.safMu.Unlock()
		return entry.safDone == nil
	}, 10*time.Second, 20*time.Millisecond)
}

func TestSAFTorrentExportFailureRetriesBeforeReportingCompletion(t *testing.T) {
	storage := newSAFTorrentTestStorage(t)
	storage.failCommit = true
	logger := zerolog.Nop()
	database, err := db.NewDatabase("", "seanime-saf-test", &logger)
	require.NoError(t, err)
	client := newSAFTorrentTestClient(t, database, t.TempDir())
	const destination = "/androidtv/root/Anime"
	_, payload, hash := addCompleteSAFTorrentFixture(t, client, destination)
	triggerSAFTorrentExport(t, client, hash)
	snapshot := client.Snapshots()[0]
	require.Equal(t, destination, snapshot.Destination)
	require.Less(t, snapshot.Completed, snapshot.Length)
	require.Contains(t, snapshot.Error, "USB storage rejected")
	require.Empty(t, storage.content(destination+"/episode.mkv"))
	storage.mu.Lock()
	storage.failCommit = false
	// Equal size is insufficient to claim an existing document as this torrent.
	storage.files[destination+"/episode.mkv"] = bytes.Repeat([]byte("x"), len(payload))
	storage.mu.Unlock()
	triggerSAFTorrentExport(t, client, hash)
	require.Equal(t, payload, storage.content(destination+"/episode.mkv"))
	snapshot = client.Snapshots()[0]
	require.Equal(t, snapshot.Length, snapshot.Completed)
	require.Empty(t, snapshot.Error)
	entry, err := client.getEntry(hash)
	require.NoError(t, err)
	stage, err := client.torrentDataDirectory(entry.model)
	require.NoError(t, err)
	contents, err := os.ReadFile(filepath.Join(stage, "episode.mkv"))
	require.NoError(t, err)
	require.Equal(t, payload, contents, "staging must remain available for seeding")
}

func TestSAFTorrentPauseCancelsExportAndRestartRecoversIt(t *testing.T) {
	storage := newSAFTorrentTestStorage(t)
	release := make(chan struct{})
	ready := make(chan struct{}, 1)
	storage.blockWrite, storage.writeReady = release, ready
	logger := zerolog.Nop()
	database, err := db.NewDatabase("", "seanime-saf-recovery", &logger)
	require.NoError(t, err)
	directory := t.TempDir()
	client := newSAFTorrentTestClient(t, database, directory)
	t.Cleanup(func() {
		select {
		case <-release:
		default:
			close(release)
		}
	})
	const destination = "/androidtv/root/Anime"
	_, payload, hash := addCompleteSAFTorrentFixture(t, client, destination)
	client.exportSAFTorrents()
	select {
	case <-ready:
	case <-time.After(10 * time.Second):
		t.Fatal("export never reached the document provider")
	}
	paused := make(chan error, 1)
	go func() { paused <- client.PauseTorrent(hash) }()
	entry, err := client.getEntry(hash)
	require.NoError(t, err)
	require.Eventually(t, func() bool {
		client.mu.RLock()
		defer client.mu.RUnlock()
		return entry.model.Paused
	}, 5*time.Second, 10*time.Millisecond)
	storage.mu.Lock()
	storage.blockWrite = nil
	storage.mu.Unlock()
	close(release)
	require.NoError(t, <-paused)
	require.Empty(t, storage.content(destination+"/episode.mkv"))
	storage.mu.Lock()
	require.Positive(t, storage.canceled)
	storage.mu.Unlock()
	client.Close()

	restored := newSAFTorrentTestClient(t, database, directory)
	require.True(t, restored.Snapshots()[0].Paused)
	require.Equal(t, destination, restored.Snapshots()[0].Destination)
	require.NoError(t, restored.ResumeTorrent(hash))
	current, err := restored.getEntry(hash)
	require.NoError(t, err)
	require.Eventually(t, func() bool { return current.torrent.BytesCompleted() == int64(len(payload)) }, 20*time.Second, 20*time.Millisecond)
	triggerSAFTorrentExport(t, restored, hash)
	require.Equal(t, payload, storage.content(destination+"/episode.mkv"))
	require.Equal(t, restored.Snapshots()[0].Length, restored.Snapshots()[0].Completed)
	require.NoError(t, restored.PauseTorrent(hash))
	require.NoError(t, restored.RemoveTorrent(hash, true))
	require.Empty(t, storage.content(destination+"/episode.mkv"))
	require.True(t, storage.directories[destination], "selected destination folder must be retained")
	require.Empty(t, restored.Snapshots())
	stage, err := restored.safStageDirectory(hash)
	require.NoError(t, err)
	_, err = os.Stat(stage)
	require.ErrorIs(t, err, os.ErrNotExist)
}

func TestSAFTorrentStorageMovePreservesPausedDataAndDestinations(t *testing.T) {
	storage := newSAFTorrentTestStorage(t)
	logger := zerolog.Nop()
	database, err := db.NewDatabase("", "seanime-saf-move", &logger)
	require.NoError(t, err)
	client := newSAFTorrentTestClient(t, database, t.TempDir())
	const original = "/androidtv/root/Anime"
	const moved = "/androidtv/second/Anime"
	_, payload, hash := addCompleteSAFTorrentFixture(t, client, original)
	triggerSAFTorrentExport(t, client, hash)
	require.NoError(t, client.PauseTorrent(hash))
	require.NoError(t, client.RenameTorrent(hash, "Custom display name"))
	require.NoError(t, client.MoveStorage(hash, moved))
	require.True(t, client.Snapshots()[0].Paused)
	require.Equal(t, moved, client.Snapshots()[0].Destination)
	require.Equal(t, payload, storage.content(moved+"/episode.mkv"))
	require.Empty(t, storage.content(original+"/episode.mkv"))
	localDestination := t.TempDir()
	require.NoError(t, client.MoveStorage(hash, localDestination))
	require.Equal(t, localDestination, client.Snapshots()[0].Destination)
	content, err := os.ReadFile(filepath.Join(localDestination, "episode.mkv"))
	require.NoError(t, err)
	require.Equal(t, payload, content)
	require.Empty(t, storage.content(moved+"/episode.mkv"))
	require.NoError(t, client.ResumeTorrent(hash))
}

func TestSAFTorrentStorageMoveDoesNotDeleteTheOnlyExportedCopy(t *testing.T) {
	storage := newSAFTorrentTestStorage(t)
	logger := zerolog.Nop()
	database, err := db.NewDatabase("", "seanime-saf-missing-stage", &logger)
	require.NoError(t, err)
	client := newSAFTorrentTestClient(t, database, t.TempDir())
	const destination = "/androidtv/root/Anime"
	_, payload, hash := addCompleteSAFTorrentFixture(t, client, destination)
	triggerSAFTorrentExport(t, client, hash)
	require.NoError(t, client.PauseTorrent(hash))
	stage, err := client.safStageDirectory(hash)
	require.NoError(t, err)
	require.NoError(t, os.Remove(filepath.Join(stage, "episode.mkv")))
	require.Error(t, client.MoveStorage(hash, t.TempDir()))
	require.Equal(t, destination, client.Snapshots()[0].Destination)
	require.Equal(t, payload, storage.content(destination+"/episode.mkv"))
}

func TestSAFTorrentStorageMoveRetainsExportsWhenStagingIsPartiallyMissing(t *testing.T) {
	for _, target := range []string{"local", "saf"} {
		t.Run(target, func(t *testing.T) {
			storage := newSAFTorrentTestStorage(t)
			logger := zerolog.Nop()
			database, err := db.NewDatabase("", "seanime-saf-partial-stage-"+target, &logger)
			require.NoError(t, err)
			client := newSAFTorrentTestClient(t, database, t.TempDir())
			const original = "/androidtv/root/Anime"
			first := bytes.Repeat([]byte("a"), 64*1024)
			second := bytes.Repeat([]byte("b"), 64*1024)
			firstHash, secondHash := sha1.Sum(first), sha1.Sum(second)
			info := &metainfo.Info{
				Name: "Series", PieceLength: int64(len(first)),
				Pieces: append(firstHash[:], secondHash[:]...),
				Files: []metainfo.FileInfo{
					{Length: int64(len(first)), Path: []string{"episode-01.mkv"}},
					{Length: int64(len(second)), Path: []string{"episode-02.mkv"}},
				},
			}
			encoded, err := bencode.Marshal(info)
			require.NoError(t, err)
			metadata := &metainfo.MetaInfo{InfoBytes: encoded}
			torrent, err := client.AddMagnet(metadata.Magnet(nil, info).String(), original)
			require.NoError(t, err)
			hash := torrent.InfoHash().HexString()
			stage, err := client.safStageDirectory(hash)
			require.NoError(t, err)
			root := filepath.Join(stage, info.Name)
			require.NoError(t, os.MkdirAll(root, 0700))
			require.NoError(t, os.WriteFile(filepath.Join(root, "episode-01.mkv"), first, 0600))
			require.NoError(t, os.WriteFile(filepath.Join(root, "episode-02.mkv"), second, 0600))
			require.NoError(t, torrent.SetInfoBytes(encoded))
			require.Eventually(t, func() bool { return torrent.BytesCompleted() == info.TotalLength() }, 20*time.Second, 20*time.Millisecond)
			triggerSAFTorrentExport(t, client, hash)
			require.NoError(t, client.PauseTorrent(hash))
			require.NoError(t, os.Remove(filepath.Join(root, "episode-02.mkv")))
			require.FileExists(t, filepath.Join(root, "episode-01.mkv"))
			destination := t.TempDir()
			if target == "saf" {
				destination = "/androidtv/second/Anime"
			}
			err = client.MoveStorage(hash, destination)
			require.ErrorContains(t, err, "committed file Series/episode-02.mkv is missing or incomplete")
			require.Equal(t, original, client.Snapshots()[0].Destination)
			require.Equal(t, first, storage.content(original+"/Series/episode-01.mkv"))
			require.Equal(t, second, storage.content(original+"/Series/episode-02.mkv"))
			if target == "saf" {
				require.Empty(t, storage.content(destination+"/Series/episode-01.mkv"))
				require.Empty(t, storage.content(destination+"/Series/episode-02.mkv"))
			}
		})
	}
}
