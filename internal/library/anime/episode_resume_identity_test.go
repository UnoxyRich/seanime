package anime

import (
	"testing"

	"seanime/internal/api/anilist"
	"seanime/internal/api/metadata"
)

type resumeIdentityMetadataWrapper struct{}

func (resumeIdentityMetadataWrapper) GetEpisodeMetadata(string) metadata.EpisodeMetadata {
	return metadata.EpisodeMetadata{}
}

func TestLocalEpisodeConstructorsRetainAniDBIdentityWithoutMetadata(t *testing.T) {
	format := anilist.MediaFormatTv
	media := &anilist.BaseAnime{ID: 73, Format: &format}
	localFile := &LocalFile{
		Path: "/library/episode-02.mkv",
		Name: "episode-02.mkv",
		Metadata: &LocalFileMetadata{
			Episode:      2,
			AniDBEpisode: "2",
			Type:         LocalFileTypeMain,
		},
	}
	metadataWrapper := resumeIdentityMetadataWrapper{}

	fullEpisode := NewEpisode(&NewEpisodeOptions{
		LocalFile:       localFile,
		Media:           media,
		MetadataWrapper: metadataWrapper,
	})
	simpleEpisode := NewSimpleEpisode(&NewSimpleEpisodeOptions{
		LocalFile:       localFile,
		Media:           media,
		MetadataWrapper: metadataWrapper,
	})

	if fullEpisode.AniDBEpisode != "2" {
		t.Fatalf("full local episode lost AniDB identity: %q", fullEpisode.AniDBEpisode)
	}
	if simpleEpisode.AniDBEpisode != "2" {
		t.Fatalf("simple local episode lost AniDB identity: %q", simpleEpisode.AniDBEpisode)
	}
}
