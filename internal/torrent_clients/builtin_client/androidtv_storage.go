package builtin_client

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"seanime/internal/androidtvstorage"
	"seanime/internal/database/models"

	anacrolix "github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/metainfo"
)

const safExportRetryInterval = 30 * time.Second

type safExportManifest struct {
	Destination string           `json:"destination"`
	RootName    string           `json:"rootName"`
	Files       map[string]int64 `json:"files"`
}

func ensureTorrentDestination(destination string) error {
	if androidtvstorage.IsPath(destination) {
		return androidtvstorage.MkdirAll(destination)
	}
	return os.MkdirAll(destination, 0755)
}

func checkTorrentDestination(destination string) error {
	if androidtvstorage.IsPath(destination) {
		entry, err := androidtvstorage.Stat(destination)
		if err != nil {
			return err
		}
		if !entry.IsDirectory {
			return errors.New("save directory is not a directory")
		}
		return nil
	}
	_, err := os.Stat(destination)
	return err
}

func (c *Client) safStageDirectory(hash string) (string, error) {
	decoded, err := hex.DecodeString(hash)
	if err != nil || len(decoded) != 20 || c.dataDir == "" || androidtvstorage.IsPath(c.dataDir) {
		return "", errors.New("invalid Android torrent staging directory or info hash")
	}
	// Random piece writes and seeding use ordinary local files with the current
	// SAF bridge. Keep them in persistent app data across pause/restart; exports
	// provide the user's chosen USB destination without discarding seed data.
	return filepath.Join(c.dataDir, "saf-torrents", strings.ToLower(hash), "data"), nil
}

func (c *Client) torrentDataDirectory(model *models.LocalTorrent) (string, error) {
	if !androidtvstorage.IsPath(model.Destination) {
		return model.Destination, nil
	}
	if _, err := androidtvstorage.CleanPath(model.Destination); err != nil {
		return "", err
	}
	directory, err := c.safStageDirectory(model.Hash)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(directory, 0700); err != nil {
		return "", err
	}
	return directory, nil
}

func safeTorrentRelativePath(relative string) (string, error) {
	relative = strings.ReplaceAll(relative, `\`, "/")
	if path.IsAbs(relative) || relative == "" {
		return "", errors.New("invalid torrent file path")
	}
	for _, segment := range strings.Split(relative, "/") {
		if segment == "" || segment == "." || segment == ".." {
			return "", errors.New("invalid torrent file path")
		}
	}
	return relative, nil
}

func safExportPath(destination, relative string) (string, error) {
	if _, err := androidtvstorage.CleanPath(destination); err != nil {
		return "", err
	}
	cleaned, err := safeTorrentRelativePath(relative)
	if err != nil {
		return "", err
	}
	return androidtvstorage.CleanPath(path.Join(destination, cleaned))
}

func (c *Client) manifestPath(hash string) (string, error) {
	directory, err := c.safStageDirectory(hash)
	if err != nil {
		return "", err
	}
	return filepath.Join(filepath.Dir(directory), "exports.json"), nil
}

func (c *Client) loadSAFManifest(hash string) (*safExportManifest, error) {
	manifestPath, err := c.manifestPath(hash)
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(manifestPath)
	if errors.Is(err, os.ErrNotExist) {
		return &safExportManifest{Files: make(map[string]int64)}, nil
	}
	if err != nil {
		return nil, err
	}
	var manifest safExportManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		return nil, err
	}
	if manifest.Files == nil {
		manifest.Files = make(map[string]int64)
	}
	for relative, size := range manifest.Files {
		if _, err := safExportPath(manifest.Destination, relative); err != nil || size < 0 {
			return nil, errors.New("invalid Android torrent export manifest")
		}
	}
	return &manifest, nil
}

func (c *Client) saveSAFManifest(hash string, manifest *safExportManifest) error {
	manifestPath, err := c.manifestPath(hash)
	if err != nil {
		return err
	}
	data, err := json.Marshal(manifest)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(manifestPath), 0700); err != nil {
		return err
	}
	temporary := manifestPath + ".tmp"
	if err := os.WriteFile(temporary, data, 0600); err != nil {
		return err
	}
	if err := os.Rename(temporary, manifestPath); err != nil {
		_ = os.Remove(temporary)
		return err
	}
	return nil
}

func (e *torrentEntry) cancelSAFExport() {
	e.safMu.Lock()
	if e.safCancel != nil {
		e.safCancel()
	}
	e.safMu.Unlock()
}

func (e *torrentEntry) operationMutex() *sync.Mutex {
	e.safMu.Lock()
	defer e.safMu.Unlock()
	if e.operationMu == nil {
		e.operationMu = new(sync.Mutex)
	}
	return e.operationMu
}

func (e *torrentEntry) stopSAFExport() {
	e.safMu.Lock()
	if e.safCancel != nil {
		e.safCancel()
	}
	done := e.safDone
	e.safMu.Unlock()
	if done != nil {
		<-done
	}
}

func safReadyCompleted(entry *torrentEntry, torrent *anacrolix.Torrent, length, completed int64) int64 {
	if !androidtvstorage.IsPath(entry.model.Destination) || length <= 0 || completed < length {
		return completed
	}
	entry.safMu.Lock()
	defer entry.safMu.Unlock()
	for _, file := range torrent.Files() {
		if size, ok := entry.safExported[file.Path()]; !ok || size != file.Length() {
			return length - 1
		}
	}
	return completed
}

func (c *Client) exportSAFTorrents() {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.closed {
		return
	}
	for _, entry := range c.torrents {
		if entry.model.Paused || entry.torrent == nil || entry.torrent.Info() == nil || !androidtvstorage.IsPath(entry.model.Destination) {
			continue
		}
		entry.safMu.Lock()
		if entry.safDone != nil || entry.safMoving || time.Since(entry.safLastAttempt) < safExportRetryInterval {
			entry.safMu.Unlock()
			continue
		}
		ctx, cancel := context.WithCancel(context.Background())
		done := make(chan struct{})
		entry.safCancel, entry.safDone = cancel, done
		entry.safLastAttempt = time.Now()
		torrent, destination := entry.torrent, entry.model.Destination
		entry.safMu.Unlock()
		go func(entry *torrentEntry) {
			err := c.exportCompletedSAFFiles(ctx, entry, torrent, destination)
			entry.safMu.Lock()
			if !errors.Is(err, context.Canceled) {
				entry.safExportError = err
			}
			entry.safCancel, entry.safDone = nil, nil
			close(done)
			entry.safMu.Unlock()
			cancel()
		}(entry)
	}
}

func (c *Client) exportCompletedSAFFiles(ctx context.Context, entry *torrentEntry, torrent *anacrolix.Torrent, destination string) error {
	directory, err := c.safStageDirectory(entry.model.Hash)
	if err != nil {
		return err
	}
	manifest, err := c.loadSAFManifest(entry.model.Hash)
	if err != nil {
		return err
	}
	if manifest.Destination != destination {
		manifest = &safExportManifest{Destination: destination, Files: make(map[string]int64)}
	}
	manifest.RootName = torrent.Info().BestName()
	if err := c.saveSAFManifest(entry.model.Hash, manifest); err != nil {
		return err
	}
	for _, file := range torrent.Files() {
		if file.BytesCompleted() != file.Length() {
			continue
		}
		relative, err := safeTorrentRelativePath(file.Path())
		if err != nil {
			return err
		}
		entry.safMu.Lock()
		size, alreadyExported := entry.safExported[relative]
		entry.safMu.Unlock()
		if alreadyExported && size == file.Length() {
			continue
		}
		document, err := safExportPath(destination, relative)
		if err != nil {
			return err
		}
		if err := copyTorrentFileToSAF(ctx, filepath.Join(directory, filepath.FromSlash(relative)), document, file.Length()); err != nil {
			return fmt.Errorf("could not save completed torrent file %s: %w", relative, err)
		}
		manifest.Files[relative] = file.Length()
		if err := c.saveSAFManifest(entry.model.Hash, manifest); err != nil {
			return err
		}
		entry.safMu.Lock()
		if entry.safExported == nil {
			entry.safExported = make(map[string]int64)
		}
		entry.safExported[relative] = file.Length()
		entry.safMu.Unlock()
	}
	return ctx.Err()
}

type safContextReader struct {
	context context.Context
	io.Reader
}

func (r safContextReader) Read(data []byte) (int, error) {
	if err := r.context.Err(); err != nil {
		return 0, err
	}
	return r.Reader.Read(data)
}

func copyTorrentFileToSAF(ctx context.Context, source, destination string, size int64) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	info, err := os.Stat(source)
	if err != nil {
		return err
	}
	if info.IsDir() || size < 0 || info.Size() != size {
		return errors.New("completed torrent file is not fully available")
	}
	if existing, err := androidtvstorage.Stat(destination); err == nil {
		if existing.IsDirectory {
			return errors.New("torrent destination is a directory")
		}
	}
	if err := androidtvstorage.MkdirAll(path.Dir(destination)); err != nil {
		return err
	}
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	written, err := androidtvstorage.WriteFrom(destination, safContextReader{ctx, input}, true)
	if err != nil {
		return err
	}
	if written != size {
		return io.ErrShortWrite
	}
	return ctx.Err()
}

func (c *Client) removeSAFTorrentData(entry *torrentEntry) error {
	manifest, err := c.loadSAFManifest(entry.model.Hash)
	if err != nil {
		return err
	}
	if manifest.Destination != "" && manifest.Destination != entry.model.Destination {
		return errors.New("Android torrent destination does not match its export manifest")
	}
	return removeSAFExports(entry.model.Destination, manifest.Files)
}

func removeSAFExports(destination string, files map[string]int64) error {
	directories := make(map[string]struct{})
	for relative := range files {
		document, err := safExportPath(destination, relative)
		if err != nil {
			return err
		}
		if _, err := androidtvstorage.Stat(document); err != nil {
			// A removed document is already deleted, while a revoked tree grant
			// must retain the torrent record so the user can retry the action.
			if errors.Is(err, os.ErrNotExist) || strings.Contains(err.Error(), "Document not found:") {
				continue
			}
			return err
		}
		if err := androidtvstorage.Remove(document); err != nil {
			return err
		}
		for directory := path.Dir(document); directory != destination && strings.HasPrefix(directory, destination+"/"); directory = path.Dir(directory) {
			directories[directory] = struct{}{}
		}
	}
	ordered := make([]string, 0, len(directories))
	for directory := range directories {
		ordered = append(ordered, directory)
	}
	sort.Slice(ordered, func(i, j int) bool { return len(ordered[i]) > len(ordered[j]) })
	for _, directory := range ordered {
		entries, err := androidtvstorage.List(directory)
		if err == nil && len(entries) == 0 {
			_ = androidtvstorage.Remove(directory)
		}
	}
	return nil
}

func (c *Client) metadataPath(hash string) (string, error) {
	directory, err := c.safStageDirectory(hash)
	if err != nil {
		return "", err
	}
	return filepath.Join(filepath.Dir(directory), "metadata.torrent"), nil
}

func (c *Client) saveTorrentMetadata(hash string, metadata *metainfo.MetaInfo) error {
	filename, err := c.metadataPath(hash)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(filename), 0700); err != nil {
		return err
	}
	file, err := os.CreateTemp(filepath.Dir(filename), ".metadata-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	writeErr := metadata.Write(file)
	closeErr := file.Close()
	if err := errors.Join(writeErr, closeErr); err != nil {
		return err
	}
	return os.Rename(file.Name(), filename)
}

func (c *Client) loadTorrentMetadata(hash string) (*metainfo.MetaInfo, error) {
	filename, err := c.metadataPath(hash)
	if err != nil {
		return nil, err
	}
	return metainfo.LoadFromFile(filename)
}

func (c *Client) moveSAFTorrentStorage(entry *torrentEntry, destination string) (retErr error) {
	if err := ensureTorrentDestination(destination); err != nil {
		return err
	}
	entry.safMu.Lock()
	if entry.safMoving {
		entry.safMu.Unlock()
		return errors.New("torrent storage is already moving")
	}
	entry.safMoving = true
	entry.safMu.Unlock()
	entry.stopSAFExport()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	entry.safMu.Lock()
	entry.safCancel, entry.safDone = cancel, done
	entry.safMu.Unlock()
	defer func() {
		cancel()
		entry.safMu.Lock()
		entry.safMoving = false
		entry.safCancel, entry.safDone = nil, nil
		close(done)
		entry.safMu.Unlock()
		if current, err := c.getEntry(entry.model.Hash); err == nil && current != entry {
			current.safMu.Lock()
			current.safMoving = false
			current.safMu.Unlock()
		}
		c.reconcileQueue()
	}()

	c.mu.RLock()
	previous := *entry.model
	torrent, closer := entry.torrent, entry.storageCloser
	c.mu.RUnlock()
	if previous.Destination == destination {
		return nil
	}
	var metadata *metainfo.MetaInfo
	if torrent != nil {
		if torrent.Info() == nil {
			return errors.New("torrent metadata is not available")
		}
		metadata = new(torrent.Metainfo())
		torrent.DisallowDataDownload()
		torrent.DisallowDataUpload()
	} else {
		metadata, _ = c.loadTorrentMetadata(previous.Hash)
	}
	oldDirectory, err := c.torrentDataDirectory(&previous)
	if err != nil {
		return err
	}
	next := previous
	next.Destination = destination
	newDirectory, err := c.torrentDataDirectory(&next)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(newDirectory, 0755); err != nil {
		return err
	}
	oldManifest, err := c.loadSAFManifest(previous.Hash)
	if err != nil {
		return err
	}
	rootName := oldManifest.RootName
	var info *metainfo.Info
	if metadata != nil {
		parsed, err := metadata.UnmarshalInfo()
		if err != nil {
			return err
		}
		info = &parsed
		rootName = info.BestName()
	}
	if rootName == "" {
		rootName = previous.Name
	}
	oldRoot, err := torrentRootFromModel(oldDirectory, rootName)
	if err != nil {
		return err
	}
	newRoot, err := torrentRootFromModel(newDirectory, rootName)
	if err != nil {
		return err
	}
	exported := make(map[string]int64)
	if torrent != nil {
		for _, file := range torrent.Files() {
			if file.BytesCompleted() == file.Length() {
				exported[file.Path()] = file.Length()
			}
		}
	} else if info != nil && c.pieceCompletion != nil {
		var offset int64
		for _, file := range info.UpvertedFiles() {
			verified := true
			if file.Length > 0 {
				for index := int(offset / info.PieceLength); int64(index)*info.PieceLength < offset+file.Length; index++ {
					complete, err := c.pieceCompletion.Get(metainfo.PieceKey{InfoHash: metadata.HashInfoBytes(), Index: index})
					if err != nil || !complete.Ok || !complete.Complete {
						verified = false
						break
					}
				}
			}
			offset += file.Length
			if !verified {
				continue
			}
			filename, err := classicTorrentFilePath(oldDirectory, info, &file)
			if err != nil {
				return err
			}
			if stat, err := os.Stat(filename); err != nil || stat.IsDir() || stat.Size() != file.Length {
				continue
			}
			relative, err := filepath.Rel(oldDirectory, filename)
			if err != nil {
				return err
			}
			exported[filepath.ToSlash(relative)] = file.Length
		}
	} else if androidtvstorage.IsPath(previous.Destination) && oldManifest.Destination == previous.Destination {
		for relative, size := range oldManifest.Files {
			exported[relative] = size
		}
	} else if previous.Length > 0 && previous.Completed >= previous.Length {
		if err := filepath.WalkDir(oldRoot, func(filename string, item os.DirEntry, walkErr error) error {
			if walkErr != nil {
				return walkErr
			}
			if item.IsDir() {
				return nil
			}
			info, err := item.Info()
			if err != nil {
				return err
			}
			relative, err := filepath.Rel(oldDirectory, filename)
			if err != nil {
				return err
			}
			exported[filepath.ToSlash(relative)] = info.Size()
			return nil
		}); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
	}
	if androidtvstorage.IsPath(previous.Destination) && oldManifest.Destination == previous.Destination {
		// Every previously committed USB file must reach the new destination
		// before any old export is removed, even if only part of staging survives.
		for relative, size := range oldManifest.Files {
			if _, err := safeTorrentRelativePath(relative); err != nil {
				return err
			}
			filename := filepath.Join(oldDirectory, filepath.FromSlash(relative))
			stat, err := os.Stat(filename)
			if err != nil || stat.IsDir() || stat.Size() != size {
				return fmt.Errorf("cannot move torrent: committed file %s is missing or incomplete in staging", relative)
			}
			exported[relative] = size
		}
	}
	if androidtvstorage.IsPath(destination) {
		for relative, size := range exported {
			document, err := safExportPath(destination, relative)
			if err != nil {
				return err
			}
			if err := copyTorrentFileToSAF(ctx, filepath.Join(oldDirectory, filepath.FromSlash(relative)), document, size); err != nil {
				return err
			}
		}
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if metadata != nil {
		if err := c.saveTorrentMetadata(previous.Hash, metadata); err != nil {
			return err
		}
	}
	if torrent != nil {
		torrent.Drop()
	}
	if closer != nil {
		_ = closer.Close()
	}
	c.mu.Lock()
	entry.torrent, entry.storageCloser = nil, nil
	c.mu.Unlock()
	moved := false
	if oldRoot != newRoot {
		if err := os.Rename(oldRoot, newRoot); err != nil {
			if !errors.Is(err, os.ErrNotExist) || len(exported) > 0 || len(oldManifest.Files) > 0 {
				_, _ = c.addPersistedWithMetadata(&previous, metadata)
				return fmt.Errorf("move staged torrent data: %w", err)
			}
		} else {
			moved = true
		}
	}
	if err := c.database.UpdateLocalTorrent(previous.Hash, map[string]interface{}{"destination": destination}); err != nil {
		if moved {
			_ = os.Rename(newRoot, oldRoot)
		}
		_, _ = c.addPersistedWithMetadata(&previous, metadata)
		return err
	}
	if _, err := c.addPersistedStorage(&next, metadata, true); err != nil {
		if moved {
			_ = os.Rename(newRoot, oldRoot)
		}
		_ = c.database.UpdateLocalTorrent(previous.Hash, map[string]interface{}{"destination": previous.Destination})
		_, _ = c.addPersistedWithMetadata(&previous, metadata)
		return err
	}
	if androidtvstorage.IsPath(destination) {
		manifest := &safExportManifest{Destination: destination, RootName: rootName, Files: exported}
		if err := c.saveSAFManifest(previous.Hash, manifest); err != nil {
			return err
		}
		if current, err := c.getEntry(previous.Hash); err == nil {
			current.safMu.Lock()
			current.safExported = exported
			current.safMu.Unlock()
		}
	} else {
		if filename, err := c.manifestPath(previous.Hash); err == nil {
			_ = os.Remove(filename)
		}
	}
	if androidtvstorage.IsPath(previous.Destination) && oldManifest.Destination == previous.Destination {
		if err := removeSAFExports(previous.Destination, oldManifest.Files); err != nil {
			return fmt.Errorf("torrent moved; could not remove its previous Android storage files: %w", err)
		}
	}
	return nil
}
