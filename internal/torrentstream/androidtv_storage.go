package torrentstream

import (
	"fmt"
	"os"
	"path"
	"path/filepath"
	"seanime/internal/androidtvstorage"
	"strings"
	"time"

	itorrent "github.com/anacrolix/torrent"
)

const safMirrorRetryInterval = 30 * time.Second

func torrentStorageRelativePath(filePath string) (string, error) {
	filePath = strings.ReplaceAll(filePath, `\`, "/")
	segments := strings.Split(filePath, "/")
	if len(segments) == 0 {
		return "", fmt.Errorf("torrent file path is empty")
	}
	for _, segment := range segments {
		if segment == "" || segment == "." || segment == ".." {
			return "", fmt.Errorf("invalid torrent file path")
		}
	}
	cleaned := path.Clean(filePath)
	if cleaned == "." || strings.HasPrefix(cleaned, "../") || path.IsAbs(cleaned) {
		return "", fmt.Errorf("invalid torrent file path")
	}
	return cleaned, nil
}

func safTorrentDestination(root, infoHash, filePath string) (string, error) {
	if _, err := androidtvstorage.CleanPath(root); err != nil {
		return "", err
	}
	if infoHash == "" || strings.ContainsAny(infoHash, `/\\`) || infoHash == "." || infoHash == ".." {
		return "", fmt.Errorf("invalid torrent info hash")
	}
	relativePath, err := torrentStorageRelativePath(filePath)
	if err != nil {
		return "", err
	}
	return androidtvstorage.CleanPath(path.Join(root, infoHash, relativePath))
}

func copyCompletedTorrentFile(sourcePath, destinationPath string, expectedSize int64) error {
	if expectedSize < 0 {
		return fmt.Errorf("invalid torrent file size")
	}
	sourceInfo, err := os.Stat(sourcePath)
	if err != nil {
		return err
	}
	if sourceInfo.IsDir() || sourceInfo.Size() != expectedSize {
		return fmt.Errorf("completed torrent file is not fully available")
	}
	if existing, err := androidtvstorage.Stat(destinationPath); err == nil {
		if existing.IsDirectory {
			return fmt.Errorf("torrent destination is a directory")
		}
		if existing.Size == expectedSize {
			return nil
		}
	}
	if err := androidtvstorage.MkdirAll(path.Dir(destinationPath)); err != nil {
		return err
	}
	input, err := os.Open(sourcePath)
	if err != nil {
		return err
	}
	defer input.Close()
	written, err := androidtvstorage.WriteFrom(destinationPath, input, true)
	if err != nil {
		return err
	}
	if written != expectedSize {
		return fmt.Errorf("copied %d of %d torrent bytes", written, expectedSize)
	}
	return nil
}

func (r *Repository) preserveCompletedTorrentFile(torrent *itorrent.Torrent, file *itorrent.File) {
	if r == nil || torrent == nil || file == nil || file.Length() <= 0 || file.BytesCompleted() != file.Length() {
		return
	}

	infoHash := torrent.InfoHash().HexString()
	root := ""
	r.safMirrorMu.Lock()
	root = r.safDownloadDir
	if root == "" {
		r.safMirrorMu.Unlock()
		return
	}
	key := root + "|" + infoHash + "|" + file.Path()
	now := time.Now()
	if _, ok := r.safMirrorCompleted[key]; ok {
		r.safMirrorMu.Unlock()
		return
	}
	if _, ok := r.safMirrorInFlight[key]; ok || now.Sub(r.safMirrorLastAttempt[key]) < safMirrorRetryInterval {
		r.safMirrorMu.Unlock()
		return
	}
	r.safMirrorInFlight[key] = struct{}{}
	r.safMirrorLastAttempt[key] = now
	r.safMirrorMu.Unlock()
	defer func() {
		r.safMirrorMu.Lock()
		delete(r.safMirrorInFlight, key)
		r.safMirrorMu.Unlock()
	}()

	destination, err := safTorrentDestination(root, infoHash, file.Path())
	if err == nil {
		stageRelativePath, pathErr := torrentStorageRelativePath(file.Path())
		err = pathErr
		if err == nil {
			sourcePath := filepath.Join(r.GetDownloadDir(), infoHash, filepath.FromSlash(stageRelativePath))
			err = copyCompletedTorrentFile(sourcePath, destination, file.Length())
		}
	}
	if err != nil {
		if r.logger != nil {
			r.logger.Warn().Err(err).Str("file", file.Path()).Msg("torrentstream: Could not copy completed file to Android storage")
		}
		return
	}

	r.safMirrorMu.Lock()
	r.safMirrorCompleted[key] = struct{}{}
	r.safMirrorMu.Unlock()
	if r.logger != nil {
		r.logger.Info().Str("file", file.Path()).Msg("torrentstream: Saved completed file to Android storage")
	}
}
