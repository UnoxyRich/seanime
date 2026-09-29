package library_explorer

import (
	"errors"
	"fmt"
	"path/filepath"
	"seanime/internal/androidtvstorage"
	"seanime/internal/database/db_bridge"
	"seanime/internal/library/anime"
	"seanime/internal/security"
	"strings"
	"sync"

	"github.com/samber/lo"
)

type SuperUpdateFileOptions struct {
	// Path to the file
	Path string `json:"path"`
	// New name of the file
	NewName string `json:"newName,omitempty"`
	// Metadata of the file
	Metadata *anime.LocalFileMetadata `json:"metadata,omitempty"`
}

func (l *LibraryExplorer) SuperUpdateFiles(opts []*SuperUpdateFileOptions) error {

	const MaxConcurrentUpdates = 10
	sem := make(chan struct{}, MaxConcurrentUpdates)

	l.logger.Debug().
		Int("count", len(opts)).
		Msg("library explorer: Updating files")

	wg := sync.WaitGroup{}
	wg.Add(len(opts))
	var updateErrors []error
	var updateErrorsMu sync.Mutex

	lfs, lfsId, err := db_bridge.GetLocalFiles(l.database)
	if err != nil {
		return err
	}

	settings, err := l.database.GetSettings()
	if err != nil {
		return err
	}
	for _, opt := range opts {
		lf, err := validateSuperUpdateFile(opt, lfs)
		if err != nil {
			return err
		}
		opt.Path = lf.Path
	}

	for _, opt := range opts {
		go func(opt *SuperUpdateFileOptions) {
			sem <- struct{}{}
			defer func() { <-sem }()
			defer wg.Done()
			if err := l.superUpdateFile(opt, lfs, lfsId, settings.GetLibrary().GetLibraryPaths()); err != nil {
				updateErrorsMu.Lock()
				updateErrors = append(updateErrors, err)
				updateErrorsMu.Unlock()
			}
		}(opt)
	}

	wg.Wait()

	// Save the local files
	_, err = db_bridge.SaveLocalFiles(l.database, lfsId, lfs)
	if err != nil {
		return err
	}

	l.fileTree = nil

	return errors.Join(updateErrors...)
}

func validateSuperUpdateFile(opt *SuperUpdateFileOptions, lfs []*anime.LocalFile) (*anime.LocalFile, error) {
	if opt == nil {
		return nil, fmt.Errorf("missing local file")
	}

	lf, found := lo.Find(lfs, func(i *anime.LocalFile) bool {
		return i.HasSamePath(opt.Path)
	})
	if !found {
		return nil, fmt.Errorf("local file not found: %s", opt.Path)
	}

	if opt.NewName != "" && !isValidSuperUpdateName(opt.NewName) {
		return nil, fmt.Errorf("invalid file name: %s", opt.NewName)
	}

	return lf, nil
}

func isValidSuperUpdateName(name string) bool {
	if strings.TrimSpace(name) == "" || name == "." || name == ".." {
		return false
	}
	if filepath.IsAbs(name) || filepath.Base(name) != name {
		return false
	}

	return !strings.ContainsAny(name, `/\\`)
}

func (l *LibraryExplorer) superUpdateFile(opt *SuperUpdateFileOptions, lfs []*anime.LocalFile, lfsId uint, libraryPaths []string) error {

	l.logger.Debug().
		Any("path", opt.Path).
		Msg("library explorer: Updating file")

	lf, found := lo.Find(lfs, func(i *anime.LocalFile) bool {
		return i.HasSamePath(opt.Path)
	})
	if security.IsStrict() { // in strict mode, only allow updates to files that are already known by the scanner
		if !found {
			return fmt.Errorf("local file not found: %s", opt.Path)
		}
	}

	if opt.NewName != "" {
		newPath := filepath.Join(filepath.Dir(opt.Path), opt.NewName)
		// Persist the real rename before changing the library record. A revoked
		// SAF grant or a full provider must not leave the DB pointing at a name
		// that was never created.
		if err := androidtvstorage.Rename(opt.Path, newPath); err != nil {
			return err
		}
		// Update the file name
		// If the local file exists, update the name
		if found {
			lf.Name = opt.NewName
			// Update the parsed info
			newLf := anime.NewLocalFileS(newPath, libraryPaths)
			lf.ParsedData = newLf.ParsedData
			lf.ParsedFolderData = newLf.ParsedFolderData
			lf.Path = newPath
		}

	}

	if opt.Metadata != nil {
		l.logger.Debug().
			Any("path", opt.Path).
			Any("metadata", opt.Metadata).
			Msg("library explorer: Updating file metadata")
		if found {
			lf.Metadata = opt.Metadata
			lf.Locked = true
			lf.Ignored = false
		}
	}

	return nil
}
