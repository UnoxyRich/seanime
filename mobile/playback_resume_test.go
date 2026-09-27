package mobile

import (
	"os"
	"path/filepath"
	"seanime/internal/api/anilist"
	"seanime/internal/directstream"
	"seanime/internal/player"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func localCheckpoint(id string) *playbackCheckpoint {
	return &playbackCheckpoint{ID: id, Source: &directstream.ResumeSource{
		Version: 1, Type: player.PlaybackTypeLocalFile, Media: &anilist.BaseAnime{ID: 7}, AniDBEpisode: "1", Path: "/androidtv/usb/episode.mkv",
	}}
}

func TestPlaybackCheckpointSurvivesReloadAndRejectsOldTickets(t *testing.T) {
	directory := t.TempDir()
	require.NoError(t, writePlaybackCheckpoint(directory, localCheckpoint("first")))
	reloaded, err := readPlaybackCheckpoint(directory, "first")
	require.NoError(t, err)
	require.Equal(t, "/androidtv/usb/episode.mkv", reloaded.Source.Path)
	file, err := os.Stat(filepath.Join(directory, "androidtv-playback", "active.json"))
	require.NoError(t, err)
	require.Equal(t, os.FileMode(0600), file.Mode().Perm())
	require.NoError(t, writePlaybackCheckpoint(directory, localCheckpoint("next")))
	_, err = readPlaybackCheckpoint(directory, "first")
	require.ErrorContains(t, err, "replaced")
	_, err = readPlaybackCheckpoint(directory, "../../other-file")
	require.Error(t, err)
	entries, err := os.ReadDir(filepath.Join(directory, "androidtv-playback"))
	require.NoError(t, err)
	require.Len(t, entries, 1, "checkpoints should not accumulate per-episode files")
}

func TestPlaybackCheckpointReusesTicketForRecreatedActivity(t *testing.T) {
	directory := t.TempDir()
	source := localCheckpoint("fixture").Source
	first, err := savePlaybackResume(directory, "first-stream", source)
	require.NoError(t, err)
	recreated, err := savePlaybackResume(directory, "first-stream", source)
	require.NoError(t, err)
	require.Equal(t, first, recreated, "a late callback must not invalidate the saved activity's ticket")
	_, err = readPlaybackCheckpoint(directory, first)
	require.NoError(t, err)
	next, err := savePlaybackResume(directory, "next-stream", source)
	require.NoError(t, err)
	require.NotEqual(t, first, next)
	_, err = readPlaybackCheckpoint(directory, first)
	require.ErrorContains(t, err, "replaced")
	_, err = savePlaybackResume(directory, "next-stream", nil)
	require.Error(t, err)
	_, err = savePlaybackResume(directory, "", source)
	require.Error(t, err)
}

func TestPlaybackResumeRefreshRequiresMatchingSourceAndRotatesTicket(t *testing.T) {
	directory := t.TempDir()
	expected := localCheckpoint("old").Source
	oldTicket, err := savePlaybackResume(directory, "old-stream", expected)
	require.NoError(t, err)

	actual := *expected
	actual.Media = &anilist.BaseAnime{ID: expected.Media.ID}
	refreshedTicket, err := refreshPlaybackCheckpoint(directory, oldTicket, "new-stream", expected, &actual)
	require.NoError(t, err)
	require.NotEqual(t, oldTicket, refreshedTicket)
	_, err = readPlaybackCheckpoint(directory, oldTicket)
	require.ErrorContains(t, err, "replaced")
	refreshed, err := readPlaybackCheckpoint(directory, refreshedTicket)
	require.NoError(t, err)
	require.Equal(t, "new-stream", refreshed.PlaybackID)
	require.Equal(t, expected.Path, refreshed.Source.Path)

	wrongSource := *expected
	wrongSource.Path = "/androidtv/usb/different.mkv"
	_, err = refreshPlaybackCheckpoint(directory, refreshedTicket, "other-stream", expected, &wrongSource)
	require.ErrorContains(t, err, "does not match")
	_, err = readPlaybackCheckpoint(directory, refreshedTicket)
	require.NoError(t, err, "a rejected source must not invalidate the valid ticket")
}

func TestPlaybackCheckpointInvalidWritesPreservePreviousSource(t *testing.T) {
	directory := t.TempDir()
	require.NoError(t, writePlaybackCheckpoint(directory, localCheckpoint("previous")))
	invalid := localCheckpoint("invalid")
	invalid.Source.Version = 99
	require.Error(t, writePlaybackCheckpoint(directory, invalid))
	oversized := localCheckpoint("large")
	oversized.Source.Path = strings.Repeat("x", maxPlaybackCheckpointBytes)
	require.Error(t, writePlaybackCheckpoint(directory, oversized))
	_, err := readPlaybackCheckpoint(directory, "previous")
	require.NoError(t, err)
}

func TestPlaybackCheckpointRejectsCorruptionAndMissingClient(t *testing.T) {
	directory := t.TempDir()
	require.NoError(t, writePlaybackCheckpoint(directory, localCheckpoint("current")))
	path := filepath.Join(directory, "androidtv-playback", "active.json")
	for _, content := range []string{"{", `{"id":"current","source":null}`, strings.Repeat("x", maxPlaybackCheckpointBytes+1)} {
		require.NoError(t, os.WriteFile(path, []byte(content), 0600))
		_, err := readPlaybackCheckpoint(directory, "current")
		require.Error(t, err)
	}
	require.ErrorContains(t, RestorePlaybackResume("current", " "), "client ID")
}
