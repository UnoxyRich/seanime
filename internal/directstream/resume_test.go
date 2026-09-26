package directstream

import (
	"net/http"
	"seanime/internal/api/anilist"
	hibiketorrent "seanime/internal/extension/hibike/torrent"
	"seanime/internal/library/anime"
	"seanime/internal/player"
	"testing"

	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/bencode"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/samber/mo"
	"github.com/stretchr/testify/require"
)

func TestCaptureResumeSourcePreservesTorrentFileTrackersAndWebSeeds(t *testing.T) {
	config := torrent.NewDefaultClientConfig()
	config.DataDir = t.TempDir()
	config.NoDHT = true
	config.DisableTrackers = true
	config.NoDefaultPortForwarding = true
	config.DisableTCP = true
	config.DisableUTP = true
	config.NoUpload = true
	client, err := torrent.NewClient(config)
	require.NoError(t, err)
	t.Cleanup(func() { client.Close() })
	info, err := bencode.Marshal(metainfo.Info{Name: "Selected batch", PieceLength: 16_384, Pieces: make([]byte, 20),
		Files: []metainfo.FileInfo{{Length: 1, Path: []string{"episode-one.mkv"}}, {Length: 1, Path: []string{"episode-two.mkv"}}}})
	require.NoError(t, err)
	metadata := &metainfo.MetaInfo{InfoBytes: info, AnnounceList: [][]string{{"https://tracker.example/announce"}},
		UrlList: []string{"https://seed.example/batch/"}}
	item, err := client.AddTorrent(metadata)
	require.NoError(t, err)
	stream := &TorrentStream{torrent: item, file: item.Files()[1], BaseStream: BaseStream{
		media: &anilist.BaseAnime{ID: 7}, episode: &anime.Episode{AniDBEpisode: "2", EpisodeNumber: 2},
	}}
	manager := &Manager{currentStream: mo.Some[Stream](stream), currentPlaybackId: "batch-second-file"}
	source, err := manager.CaptureResumeSource("/api/v1/directstream/stream?id=batch-second-file")
	require.NoError(t, err)
	require.Equal(t, 1, *source.FileIndex)
	require.Equal(t, item.InfoHash().HexString(), source.Torrent.InfoHash)
	magnet, err := metainfo.ParseMagnetV2Uri(source.Torrent.MagnetLink)
	require.NoError(t, err)
	require.Equal(t, []string{"https://tracker.example/announce"}, magnet.Trackers)
	require.Equal(t, "https://seed.example/batch/", magnet.Params.Get("ws"))
}

func TestCaptureResumeSourceKeepsOriginalSourceAndDetachesMetadata(t *testing.T) {
	base := BaseStream{media: &anilist.BaseAnime{ID: 7}, episode: &anime.Episode{AniDBEpisode: "S1", EpisodeNumber: 0}}
	fixtures := []struct {
		name   string
		stream Stream
		check  func(*testing.T, *ResumeSource)
	}{
		{"local", &LocalFileStream{BaseStream: base, localFile: &anime.LocalFile{Path: "/androidtv/usb/episode.mkv"}}, func(t *testing.T, s *ResumeSource) {
			require.Equal(t, "/androidtv/usb/episode.mkv", s.Path)
		}},
		{"url", &UrlStream{httpBaseStream{BaseStream: base, streamUrl: "https://media.example/episode.mkv"}}, func(t *testing.T, s *ResumeSource) {
			require.Equal(t, "https://media.example/episode.mkv", s.StreamURL)
		}},
		{"debrid", &DebridStream{httpBaseStream: httpBaseStream{BaseStream: base, streamUrl: "https://expired.example/temporary-url"},
			torrent: &hibiketorrent.AnimeTorrent{Name: "Selected release", MagnetLink: "magnet:?xt=urn:btih:source"}, fileID: "selected-file"}, func(t *testing.T, s *ResumeSource) {
			require.Equal(t, "selected-file", s.FileID)
			require.Equal(t, "Selected release", s.Torrent.Name)
			require.Empty(t, s.StreamURL, "debrid must resolve a fresh URL from the selected file")
		}},
		{"nakama", &Nakama{httpBaseStream: httpBaseStream{BaseStream: base, streamUrl: "http://host.example/stream",
			requestHeaders: http.Header{"X-Seanime-Nakama-Token": {"test-host-token"}}}}, func(t *testing.T, s *ResumeSource) {
			require.Equal(t, "test-host-token", s.NakamaToken)
			require.Equal(t, "http://host.example/stream", s.StreamURL)
		}},
	}
	for _, fixture := range fixtures {
		t.Run(fixture.name, func(t *testing.T) {
			manager := &Manager{currentStream: mo.Some(fixture.stream), currentPlaybackId: "current"}
			source, err := manager.CaptureResumeSource("http://127.0.0.1:43211/api/v1/directstream/stream?id=current&token=temporary")
			require.NoError(t, err)
			require.Equal(t, fixture.stream.Type(), source.Type)
			require.Equal(t, "S1", source.AniDBEpisode)
			fixture.check(t, source)
			source.Media.ID = 9
			require.Equal(t, 7, fixture.stream.Media().ID)
			if source.Torrent != nil {
				source.Torrent.Name = "Changed copy"
				require.Equal(t, "Selected release", fixture.stream.(*DebridStream).torrent.Name)
			}
		})
	}
}

func TestCaptureResumeSourceRejectsStalePlaybackAndMissingSelections(t *testing.T) {
	stream := &LocalFileStream{BaseStream: BaseStream{media: &anilist.BaseAnime{ID: 1}, episode: &anime.Episode{AniDBEpisode: "1"}},
		localFile: &anime.LocalFile{Path: "/anime/episode.mkv"}}
	manager := &Manager{currentStream: mo.Some[Stream](stream), currentPlaybackId: "new-episode"}
	for _, url := range []string{"http://127.0.0.1:43211/api/v1/directstream/stream?id=old-episode", "/api/v1/directstream/stream", "/other?id=new-episode"} {
		_, err := manager.CaptureResumeSource(url)
		require.Error(t, err)
	}
	manager.currentStream = mo.None[Stream]()
	_, err := manager.CaptureResumeSource("/api/v1/directstream/stream?id=new-episode")
	require.Error(t, err)
	for _, source := range []*ResumeSource{
		nil,
		{Version: 2, Type: player.PlaybackTypeLocalFile, Media: &anilist.BaseAnime{ID: 1}, AniDBEpisode: "1", Path: "/anime/file.mkv"},
		{Version: 1, Type: player.PlaybackTypeTorrent, Media: &anilist.BaseAnime{ID: 1}, AniDBEpisode: "1"},
		{Version: 1, Type: player.PlaybackTypeDebrid, Media: &anilist.BaseAnime{ID: 1}, AniDBEpisode: "1"},
		{Version: 1, Type: player.PlaybackTypeURL, Media: &anilist.BaseAnime{ID: 1}, AniDBEpisode: "1", StreamURL: "file:///tmp/episode"},
	} {
		require.Error(t, source.Validate())
	}
}
