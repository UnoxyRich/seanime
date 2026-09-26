package manga_providers

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	// "image/jpeg"
	"io"
	"os"
	"path/filepath"
	"seanime/internal/androidtvstorage"
	hibikemanga "seanime/internal/extension/hibike/manga"
	"seanime/internal/util/comparison"
	"seanime/internal/util/result"
	"slices"
	"strconv"
	"strings"
	"sync"

	// "github.com/gen2brain/go-fitz"
	"github.com/rs/zerolog"
	"github.com/samber/lo"
)

const (
	LocalServePath = "{{manga-local-assets}}"
)

type Local struct {
	dir      string // Directory to scan for manga
	stageDir string // App-managed location for SAF archives that require filesystem access.
	logger   *zerolog.Logger

	mu                 sync.Mutex
	currentChapterPath string
	currentStagedPath  string
	currentZipCloser   io.Closer
	currentPages       *result.Map[string, *loadedPage]
}

type loadedPage struct {
	buf  []byte
	page *hibikemanga.ChapterPage
}

// chapterEntry represents a potential chapter file or directory found during scanning
type chapterEntry struct {
	RelativePath string // Path relative to manga root (e.g., "mangaID/chapter1.cbz" or "mangaID/vol1/ch1.cbz")
	IsDir        bool   // Whether this entry is a directory
}

type localSourceEntry struct {
	name        string
	isDirectory bool
}

func (p *Local) readDirectory(dirPath string) ([]localSourceEntry, error) {
	if androidtvstorage.IsPath(dirPath) {
		entries, err := androidtvstorage.List(dirPath)
		if err != nil {
			return nil, err
		}
		ret := make([]localSourceEntry, 0, len(entries))
		for _, entry := range entries {
			ret = append(ret, localSourceEntry{name: entry.Name, isDirectory: entry.IsDirectory})
		}
		return ret, nil
	}

	entries, err := os.ReadDir(dirPath)
	if err != nil {
		return nil, err
	}
	ret := make([]localSourceEntry, 0, len(entries))
	for _, entry := range entries {
		ret = append(ret, localSourceEntry{name: entry.Name(), isDirectory: entry.IsDir()})
	}
	return ret, nil
}

func readLocalSourceFile(filePath string) ([]byte, error) {
	if !androidtvstorage.IsPath(filePath) {
		return os.ReadFile(filePath)
	}
	reader, size, err := androidtvstorage.NewReaderAt(filePath)
	if err != nil {
		return nil, err
	}
	return io.ReadAll(io.NewSectionReader(reader, 0, size))
}

func NewLocal(dir string, logger *zerolog.Logger, stagingDir ...string) hibikemanga.Provider {
	if dir != "" && !androidtvstorage.IsPath(dir) {
		_ = os.MkdirAll(dir, 0755)
	}
	localStageDir := filepath.Join(dir, "saf-source")
	if len(stagingDir) > 0 && stagingDir[0] != "" {
		localStageDir = filepath.Join(stagingDir[0], "manga-saf-source")
	}

	return &Local{
		dir:          dir,
		stageDir:     localStageDir,
		logger:       logger,
		currentPages: result.NewMap[string, *loadedPage](),
	}
}

func (p *Local) GetSettings() hibikemanga.Settings {
	return hibikemanga.Settings{
		SupportsMultiScanlator: false,
		SupportsMultiLanguage:  false,
	}
}

func (p *Local) SetSourceDirectory(dir string) {
	if dir != "" {
		p.dir = dir
	}
}

func (p *Local) getAllManga() (res []*hibikemanga.SearchResult, err error) {
	if p.dir == "" {
		return make([]*hibikemanga.SearchResult, 0), nil
	}

	entries, err := p.readDirectory(p.dir)
	if err != nil {
		return nil, err
	}

	res = make([]*hibikemanga.SearchResult, 0)
	for _, entry := range entries {
		if entry.isDirectory {
			res = append(res, &hibikemanga.SearchResult{
				ID:       entry.name,
				Title:    entry.name,
				Provider: LocalProvider,
			})
		}
	}

	return res, nil
}

func (p *Local) Search(opts hibikemanga.SearchOptions) (res []*hibikemanga.SearchResult, err error) {
	res = make([]*hibikemanga.SearchResult, 0)
	all, err := p.getAllManga()
	if err != nil {
		return nil, err
	}

	if opts.Query == "" {
		return all, nil
	}

	allTitles := make([]*string, len(all))
	for i, manga := range all {
		allTitles[i] = &manga.Title
	}
	compRes := comparison.CompareWithLevenshteinCleanFunc(&opts.Query, allTitles, cleanMangaTitle)

	var bestMatch *comparison.LevenshteinResult
	for _, res := range compRes {
		if bestMatch == nil || res.Distance < bestMatch.Distance {
			bestMatch = res
		}
	}

	if bestMatch == nil {
		return res, nil
	}

	if bestMatch.Distance > 3 {
		// If the best match is too far away, return no results
		return res, nil
	}

	manga, ok := lo.Find(all, func(manga *hibikemanga.SearchResult) bool {
		return manga.Title == *bestMatch.Value
	})

	if !ok {
		return res, nil
	}

	res = append(res, manga)

	return res, nil
}

func cleanMangaTitle(title string) string {
	title = strings.TrimSpace(title)

	// Remove some characters to make comparison easier
	title = strings.Map(func(r rune) rune {
		if r == '/' || r == '\\' || r == ':' || r == '*' || r == '?' || r == '!' || r == '"' || r == '<' || r == '>' || r == '|' || r == ',' {
			return rune(0)
		}
		return r
	}, title)

	return title
}

// FindChapters scans the manga series directory and returns the chapters.
// Supports nested folder structures up to 2 levels deep.
//
// Example:
//
//	Series title/
//	├── Chapter 1/
//	│   ├── image_1.ext
//	│   └── image_n.ext
//	├── Chapter 2.pdf
//	└── Ch 1-10/
//	    ├── Ch 1/
//	    └── Ch 2/
func (p *Local) FindChapters(mangaID string) (res []*hibikemanga.ChapterDetails, err error) {
	if p.dir == "" {
		return make([]*hibikemanga.ChapterDetails, 0), nil
	}

	mangaPath := filepath.Join(p.dir, mangaID)

	p.logger.Trace().Str("mangaPath", mangaPath).Msg("manga: Finding local chapters")

	// Collect all potential chapter entries up to 2 levels deep
	chapterEntries, err := p.collectChapterEntries(mangaPath, mangaID, 0)
	if err != nil {
		return nil, err
	}

	res = make([]*hibikemanga.ChapterDetails, 0)
	// Go through all collected entries.
	for _, entry := range chapterEntries {
		scannedEntry, ok := scanChapterFilename(filepath.Base(entry.RelativePath))
		if !ok {
			continue
		}

		if len(scannedEntry.Chapter) != 1 {
			// Handle one-shots (no chapter number and only one entry)
			if len(scannedEntry.Chapter) == 0 && len(chapterEntries) == 1 {
				chapterTitle := "Chapter 1"
				if scannedEntry.ChapterTitle != "" {
					chapterTitle += " - " + scannedEntry.ChapterTitle
				}
				res = append(res, &hibikemanga.ChapterDetails{
					Provider:   LocalProvider,
					ID:         filepath.ToSlash(entry.RelativePath), // ID is the relative filepath, e.g. "/series/chapter_1.cbz" or "/series/vol1/ch1.cbz"
					URL:        "",
					Title:      chapterTitle,
					Chapter:    "1",
					Index:      0, // placeholder, will be set later
					LocalIsPDF: scannedEntry.IsPDF,
				})
			} else if len(scannedEntry.Chapter) == 2 {
				// Handle combined chapters (e.g. "Chapter 1-2")
				chapterTitle := "Chapter " + cleanChapter(scannedEntry.Chapter[0]) + "-" + cleanChapter(scannedEntry.Chapter[1])
				if scannedEntry.ChapterTitle != "" {
					chapterTitle += " - " + scannedEntry.ChapterTitle
				}
				res = append(res, &hibikemanga.ChapterDetails{
					Provider: LocalProvider,
					ID:       filepath.ToSlash(entry.RelativePath), // ID is the relative filepath, e.g. "/series/chapter_1.cbz" or "/series/vol1/ch1.cbz"
					URL:      "",
					Title:    chapterTitle,
					// Use the last chapter number as the chapter for progress tracking
					Chapter:    cleanChapter(scannedEntry.Chapter[1]),
					Index:      0, // placeholder, will be set later
					LocalIsPDF: scannedEntry.IsPDF,
				})
			}
			continue
		}

		ch := cleanChapter(scannedEntry.Chapter[0])
		chapterTitle := "Chapter " + ch
		if scannedEntry.ChapterTitle != "" {
			chapterTitle += " - " + scannedEntry.ChapterTitle
		}

		res = append(res, &hibikemanga.ChapterDetails{
			Provider:   LocalProvider,
			ID:         filepath.ToSlash(entry.RelativePath), // ID is the relative filepath, e.g. "/series/chapter_1.cbz" or "/series/vol1/ch1.cbz"
			URL:        "",
			Title:      chapterTitle,
			Chapter:    ch,
			Index:      0, // placeholder, will be set later
			LocalIsPDF: scannedEntry.IsPDF,
		})
	}

	// sort by chapter number (ascending)
	slices.SortFunc(res, func(a, b *hibikemanga.ChapterDetails) int {
		chA, _ := strconv.ParseFloat(a.Chapter, 64)
		chB, _ := strconv.ParseFloat(b.Chapter, 64)
		return int(chA - chB)
	})

	// set the indexes
	for i, chapter := range res {
		chapter.Index = uint(i)
	}

	return res, nil
}

// collectChapterEntries walks the directory tree up to maxDepth levels deep and collects
// all potential chapter files and directories.
func (p *Local) collectChapterEntries(currentPath, mangaID string, currentDepth int) (entries []*chapterEntry, err error) {
	const maxDepth = 2

	if currentDepth > maxDepth {
		return entries, nil
	}

	dirEntries, err := p.readDirectory(currentPath)
	if err != nil {
		return nil, err
	}

	entries = make([]*chapterEntry, 0)

	for _, entry := range dirEntries {
		entryPath := filepath.Join(currentPath, entry.name)

		// Calculate relative path from manga root
		var relativePath string
		if currentDepth == 0 {
			// At manga root level
			relativePath = filepath.Join(mangaID, entry.name)
		} else {
			// Get the relative part from current path
			relativeFromManga, err := filepath.Rel(filepath.Join(p.dir, mangaID), entryPath)
			if err != nil {
				continue
			}
			relativePath = filepath.Join(mangaID, relativeFromManga)
		}

		if entry.isDirectory {
			// Check if this directory contains only images (making it a chapter directory)
			isImageDirectory, _ := p.isImageOnlyDirectory(entryPath)

			if isImageDirectory {
				// Directory contains only images, treat it as a chapter
				entries = append(entries, &chapterEntry{
					RelativePath: relativePath,
					IsDir:        true,
				})
			} else if currentDepth < maxDepth {
				// Directory doesn't contain only images, recursively scan subdirectories
				subEntries, err := p.collectChapterEntries(entryPath, mangaID, currentDepth+1)
				if err != nil {
					continue
				}

				// If subdirectory contains chapters, add them
				if len(subEntries) > 0 {
					entries = append(entries, subEntries...)
				} else {
					// If no sub-chapters found, treat directory itself as potential chapter
					entries = append(entries, &chapterEntry{
						RelativePath: relativePath,
						IsDir:        true,
					})
				}
			} else {
				// At max depth, treat directory as potential chapter
				entries = append(entries, &chapterEntry{
					RelativePath: relativePath,
					IsDir:        true,
				})
			}
		} else {
			// File entry - check if it's a potential chapter file
			ext := strings.ToLower(filepath.Ext(entry.name))
			if ext == ".cbz" || ext == ".cbr" || ext == ".pdf" || ext == ".zip" {
				entries = append(entries, &chapterEntry{
					RelativePath: relativePath,
					IsDir:        false,
				})
			}
		}
	}

	return entries, nil
}

// isImageOnlyDirectory checks if a directory contains only image files (no subdirectories or other files)
func (p *Local) isImageOnlyDirectory(dirPath string) (bool, error) {
	entries, err := p.readDirectory(dirPath)
	if err != nil {
		return false, err
	}

	if len(entries) == 0 {
		return false, nil
	}

	hasImages := false
	for _, entry := range entries {
		if entry.isDirectory {
			return false, nil
		}

		if isFileImage(entry.name) {
			hasImages = true
		} else {
			return false, nil
		}
	}

	return hasImages, nil
}

// "0001" -> "1", "0" -> "0"
func cleanChapter(ch string) string {
	if ch == "" {
		return ""
	}
	if ch == "0" {
		return "0"
	}
	if strings.HasPrefix(ch, "0") {
		return strings.TrimLeft(ch, "0")
	}
	return ch
}

// FindChapterPages will extract the images
func (p *Local) FindChapterPages(id string) (ret []*hibikemanga.ChapterPage, err error) {
	if p.dir == "" {
		return make([]*hibikemanga.ChapterPage, 0), nil
	}

	// id = filepath
	// e.g. "series/chapter_1.cbz"
	fullpath := filepath.Join(p.dir, id) // e.g. "/collection/series/chapter_1.cbz"

	// Prefix with {{manga-local-assets}} to signal the client that this is a local file
	// e.g. "{{manga-local-assets}}/series/chapter_1.cbz/image_1.jpg"
	formatUrl := func(fileName string) string {
		return filepath.ToSlash(filepath.Join(LocalServePath, id, fileName))
	}

	ext := strings.ToLower(filepath.Ext(fullpath))

	// Close the current pages
	if p.currentZipCloser != nil {
		_ = p.currentZipCloser.Close()
	}
	if p.currentStagedPath != "" {
		_ = os.Remove(p.currentStagedPath)
		p.currentStagedPath = ""
	}

	p.currentPages.Range(func(_ string, loadedPage *loadedPage) bool {
		loadedPage.buf = nil
		return true
	})
	p.currentPages.Clear()
	p.currentZipCloser = nil
	p.currentChapterPath = fullpath

	switch ext {
	case ".zip", ".cbz":
		archivePath := fullpath
		if androidtvstorage.IsPath(fullpath) {
			archivePath, err = p.stageSAFArchive(fullpath, ext)
			if err != nil {
				return nil, err
			}
			p.currentStagedPath = archivePath
		}
		r, err := zip.OpenReader(archivePath)
		if err != nil {
			return nil, err
		}
		defer r.Close()

		for _, f := range r.File {
			if !isFileImage(f.Name) {
				continue
			}

			page, err := f.Open()
			if err != nil {
				return nil, fmt.Errorf("failed to open page: %w", err)
			}
			buf, err := io.ReadAll(page)
			if err != nil {
				return nil, fmt.Errorf("failed to read page: %w", err)
			}
			p.currentPages.Set(strings.ToLower(f.Name), &loadedPage{
				buf: buf,
				page: &hibikemanga.ChapterPage{
					Provider: LocalProvider,
					URL:      formatUrl(f.Name),
					Index:    0, // placeholder, will be set later
					Buf:      buf,
				},
			})
		}
	case ".pdf":
		// doc, err := fitz.New(fullpath)
		// if err != nil {
		// 	return nil, fmt.Errorf("failed to open PDF file: %w", err)
		// }
		// defer doc.Close()

		// // Load images into memory
		// for n := 0; n < doc.NumPage(); n++ {
		// 	img, err := doc.Image(n)
		// 	if err != nil {
		// 		panic(err)
		// 	}

		// 	var buf bytes.Buffer
		// 	err = jpeg.Encode(&buf, img, &jpeg.Options{Quality: jpeg.DefaultQuality})
		// 	if err != nil {
		// 		panic(err)
		// 	}

		// 	p.currentPages[fmt.Sprintf("page_%d.jpg", n)] = &loadedPage{
		// 		buf: buf.Bytes(),
		// 		page: &hibikemanga.ChapterPage{
		// 			Provider: LocalProvider,
		// 			URL:      formatUrl(fmt.Sprintf("page_%d.jpg", n)),
		// 			Index:    n,
		// 		},
		// 	}
		// }
	default:
		// If it's a directory of images
		isDirectory := false
		if androidtvstorage.IsPath(fullpath) {
			entry, err := androidtvstorage.Stat(fullpath)
			if err != nil {
				return nil, fmt.Errorf("failed to stat file: %w", err)
			}
			isDirectory = entry.IsDirectory
		} else {
			stat, err := os.Stat(fullpath)
			if err != nil {
				return nil, fmt.Errorf("failed to stat file: %w", err)
			}
			isDirectory = stat.IsDir()
		}
		if !isDirectory {
			return nil, fmt.Errorf("file is not a directory: %s", fullpath)
		}

		entries, err := p.readDirectory(fullpath)
		if err != nil {
			return nil, fmt.Errorf("failed to read directory: %w", err)
		}

		for _, entry := range entries {
			if entry.isDirectory || !isFileImage(entry.name) {
				continue
			}

			buf, err := readLocalSourceFile(filepath.Join(fullpath, entry.name))
			if err != nil {
				return nil, fmt.Errorf("failed to read page: %w", err)
			}
			p.currentPages.Set(strings.ToLower(entry.name), &loadedPage{
				buf: buf,
				page: &hibikemanga.ChapterPage{
					Provider: LocalProvider,
					URL:      formatUrl(entry.name),
					Index:    0, // placeholder, will be set later
					Buf:      buf,
				},
			})
		}
	}

	type pageStruct struct {
		Number     float64
		LoadedPage *loadedPage
	}

	pages := make([]*pageStruct, 0)

	// Parse and order the pages
	p.currentPages.Range(func(key string, loadedPage *loadedPage) bool {
		scannedPage, ok := parsePageFilename(filepath.Base(loadedPage.page.URL))
		if !ok {
			return true
		}
		pages = append(pages, &pageStruct{
			Number:     scannedPage.Number,
			LoadedPage: loadedPage,
		})
		return true
	})

	// Sort pages
	slices.SortFunc(pages, func(a, b *pageStruct) int {
		return strings.Compare(filepath.Base(a.LoadedPage.page.URL), filepath.Base(b.LoadedPage.page.URL))
	})

	ret = make([]*hibikemanga.ChapterPage, 0)
	for idx, pageStruct := range pages {
		pageStruct.LoadedPage.page.Index = idx
		ret = append(ret, pageStruct.LoadedPage.page)
	}

	return ret, nil
}

func (p *Local) stageSAFArchive(sourcePath, extension string) (string, error) {
	info, err := androidtvstorage.Stat(sourcePath)
	if err != nil {
		return "", err
	}
	if info.IsDirectory {
		return "", fmt.Errorf("manga archive path is a directory")
	}
	if p.stageDir == "" {
		return "", fmt.Errorf("manga archive staging directory is not configured")
	}
	digest := sha256.Sum256([]byte(fmt.Sprintf("%s:%d:%d", sourcePath, info.ModTime, info.Size)))
	stagedPath := filepath.Join(p.stageDir, hex.EncodeToString(digest[:])+extension)
	if _, err := os.Stat(stagedPath); err == nil {
		return stagedPath, nil
	} else if !os.IsNotExist(err) {
		return "", err
	}
	if _, err := androidtvstorage.CopyToLocal(sourcePath, stagedPath); err != nil {
		return "", fmt.Errorf("stage local manga archive: %w", err)
	}
	return stagedPath, nil
}

func (p *Local) ReadPage(path string) (ret io.ReadCloser, err error) {
	// e.g. path = "/series/chapter_1.cbz/image_1.jpg"

	// If the pages are already in memory, return them

	if len(p.currentPages.Keys()) > 0 {
		page, ok := p.currentPages.Get(strings.ToLower(filepath.Base(path)))
		if ok {
			return io.NopCloser(bytes.NewReader(page.buf)), nil // Return the page
		}
	}

	return nil, fmt.Errorf("page not found: %s", path)
}
