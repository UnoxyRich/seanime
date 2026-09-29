package filesystem

import (
	"errors"
	"github.com/rs/zerolog"
	"os"
	"path"
	"path/filepath"
	"seanime/internal/androidtvstorage"
)

// RemoveEmptyDirectories deletes all empty directories in a given directory.
// It ignores errors.
func RemoveEmptyDirectories(root string, logger *zerolog.Logger) {
	if androidtvstorage.IsPath(root) {
		removeEmptyStorageDirectories(root, false, logger)
		return
	}

	_ = filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil
		}

		// Skip the root directory
		if path == root {
			return nil
		}

		if info.IsDir() {
			// Check if the directory is empty
			isEmpty, err := isDirectoryEmpty(path)
			if err != nil {
				return nil
			}

			// Delete the empty directory
			if isEmpty {
				err := os.Remove(path)
				if err != nil {
					logger.Warn().Err(err).Str("path", path).Msg("filesystem: Could not delete empty directory")
				}
				logger.Info().Str("path", path).Msg("filesystem: Deleted empty directory")
				// ignore error
			}
		}

		return nil
	})

}

func removeEmptyStorageDirectories(directory string, removeCurrent bool, logger *zerolog.Logger) bool {
	entries, err := androidtvstorage.List(directory)
	if err != nil {
		logger.Warn().Err(err).Str("path", directory).Msg("filesystem: Could not inspect Android TV directory")
		return false
	}
	empty := true
	for _, entry := range entries {
		if !entry.IsDirectory {
			empty = false
			continue
		}
		if !removeEmptyStorageDirectories(path.Join(directory, entry.Name), true, logger) {
			empty = false
		}
	}
	if !empty || !removeCurrent {
		return empty
	}
	if err := androidtvstorage.Remove(directory); err != nil {
		logger.Warn().Err(err).Str("path", directory).Msg("filesystem: Could not delete empty directory")
		return false
	}
	logger.Info().Str("path", directory).Msg("filesystem: Deleted empty directory")
	return true
}

func isDirectoryEmpty(path string) (bool, error) {
	dir, err := os.Open(path)
	if err != nil {
		return false, err
	}
	defer dir.Close()

	_, err = dir.Readdir(1)
	if err == nil {
		// Directory is not empty
		return false, nil
	}

	if errors.Is(err, os.ErrNotExist) {
		// Directory does not exist
		return false, nil
	}

	// Directory is empty
	return true, nil
}
