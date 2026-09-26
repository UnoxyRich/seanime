package mediastream

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"seanime/internal/androidtvstorage"
	"seanime/internal/events"
	"seanime/internal/util"
	"time"

	"github.com/labstack/echo/v4"
)

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Direct
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

// DirectPlayStreamUrl returns the endpoint used to directly stream the file with the given hash.
// The endpoint serves whichever file is currently loaded, the hash makes the URL unique per file so
// that the browser does not reuse the cached response of the previously played one.
func DirectPlayStreamUrl(hash string) string {
	return "/api/v1/mediastream/direct?hash=" + hash
}

func (r *Repository) ServeEchoFile(c echo.Context, rawFilePath string, clientId string, libraryPaths []string) error {
	// Unescape the file path, ignore errors
	filePath, _ := url.PathUnescape(rawFilePath)

	// If the file path is base64 encoded, decode it
	if util.IsBase64(rawFilePath) {
		var err error
		filePath, err = util.Base64DecodeStr(rawFilePath)
		if err != nil {
			// this shouldn't happen, but just in case IsBase64 is wrong
			filePath, _ = url.PathUnescape(rawFilePath)
		}
	}

	filePath = util.ResolvePhysicalPath(filePath)

	// Make sure the file is in the library directories
	inLibrary := false
	for _, libraryPath := range libraryPaths {
		if util.IsFileUnderDir(filePath, libraryPath) {
			inLibrary = true
			break
		}
	}

	if !inLibrary {
		return c.NoContent(http.StatusNotFound)
	}

	r.logger.Trace().Str("filepath", filePath).Str("payload", rawFilePath).Msg("mediastream: Served file")
	// Content disposition
	filename := filepath.Base(filePath)
	c.Response().Header().Set("Content-Disposition", fmt.Sprintf("inline; filename=\"%s\"", filename))
	if androidtvstorage.IsPath(filePath) {
		return serveAndroidTVStorageFile(c, filePath, filename)
	}

	return c.File(filePath)
}

func (r *Repository) ServeEchoDirectPlay(c echo.Context, clientId string) error {

	if !r.IsInitialized() {
		r.wsEventManager.SendEvent(events.MediastreamShutdownStream, "Module not initialized")
		return errors.New("module not initialized")
	}

	// Get current media
	mediaContainer, found := r.playbackManager.currentMediaContainer.Get()
	if !found {
		r.wsEventManager.SendEvent(events.MediastreamShutdownStream, "no file has been loaded")
		return errors.New("no file has been loaded")
	}

	// Make sure the client is asking for the file that is loaded, instead of serving another episode.
	// The hash is optional so that clients holding an older stream URL keep working.
	if requestedHash := c.QueryParam("hash"); requestedHash != "" && requestedHash != mediaContainer.Hash {
		r.logger.Warn().
			Str("requestedHash", requestedHash).
			Str("currentHash", mediaContainer.Hash).
			Msg("mediastream: Direct play requested for a file that is not loaded")
		return c.NoContent(http.StatusNotFound)
	}

	if c.Request().Method == http.MethodHead {
		r.logger.Trace().Msg("mediastream: Received HEAD request for direct play")

		// Get the file size
		var fileSize int64
		if androidtvstorage.IsPath(mediaContainer.Filepath) {
			fileInfo, err := androidtvstorage.Stat(mediaContainer.Filepath)
			if err != nil {
				r.logger.Error().Err(err).Msg("mediastream: Failed to get SAF file info")
				return c.NoContent(http.StatusInternalServerError)
			}
			fileSize = fileInfo.Size
		} else {
			fileInfo, err := os.Stat(mediaContainer.Filepath)
			if err != nil {
				r.logger.Error().Msg("mediastream: Failed to get file info")
				return c.NoContent(http.StatusInternalServerError)
			}
			fileSize = fileInfo.Size()
		}

		// Set the content length
		c.Response().Header().Set("Content-Length", fmt.Sprintf("%d", fileSize))
		c.Response().Header().Set("Content-Type", "video/mp4")
		c.Response().Header().Set("Accept-Ranges", "bytes")
		filename := filepath.Base(mediaContainer.Filepath)
		c.Response().Header().Set("Content-Disposition", fmt.Sprintf("inline; filename=\"%s\"", filename))
		return c.NoContent(http.StatusOK)
	}
	if androidtvstorage.IsPath(mediaContainer.Filepath) {
		return serveAndroidTVStorageFile(c, mediaContainer.Filepath, filepath.Base(mediaContainer.Filepath))
	}

	return c.File(mediaContainer.Filepath)
}

func serveAndroidTVStorageFile(c echo.Context, filePath, filename string) error {
	entry, err := androidtvstorage.Stat(filePath)
	if err != nil {
		return c.NoContent(http.StatusNotFound)
	}
	reader, size, err := androidtvstorage.NewReaderAt(filePath)
	if err != nil {
		return c.NoContent(http.StatusNotFound)
	}
	c.Response().Header().Set("Accept-Ranges", "bytes")
	if c.Response().Header().Get("Content-Disposition") == "" {
		c.Response().Header().Set("Content-Disposition", fmt.Sprintf("inline; filename=\"%s\"", filename))
	}
	http.ServeContent(c.Response(), c.Request(), filename, time.UnixMilli(entry.ModTime), io.NewSectionReader(reader, 0, size))
	return nil
}
