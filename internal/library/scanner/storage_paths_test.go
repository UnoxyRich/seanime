package scanner

import (
	"testing"

	"seanime/internal/library/anime"
)

func TestIsFilePathUnderAnyUnavailableStorageRoot(t *testing.T) {
	roots := []string{
		"/androidtv/0123456789abcdef",
		"/mnt/Anime",
	}
	tests := []struct {
		name string
		path string
		want bool
	}{
		{name: "SAF file", path: "/androidtv/0123456789abcdef/Series/episode.mkv", want: true},
		{name: "filesystem file", path: "/mnt/Anime/Film/movie.mkv", want: true},
		{name: "sibling storage root", path: "/androidtv/0123456789abcdee/Series/episode.mkv", want: false},
		{name: "prefix collision", path: "/mnt/Anime2/episode.mkv", want: false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := isFilePathUnderAnyRoot(tt.path, roots); got != tt.want {
				t.Fatalf("isFilePathUnderAnyRoot(%q) = %v, want %v", tt.path, got, tt.want)
			}
		})
	}
}

func TestUnavailableStorageKeepsPreviouslyShelvedFiles(t *testing.T) {
	root := "/androidtv/0123456789abcdef"
	path := root + "/Series/episode.mkv"
	localFile := &anime.LocalFile{Path: path}
	scn := &Scanner{ExistingShelvedFiles: []*anime.LocalFile{localFile}}

	scn.addRemainingShelvedFiles(map[string]*anime.LocalFile{}, []string{root}, []string{root})
	if len(scn.shelvedLocalFiles) != 1 || scn.shelvedLocalFiles[0].Path != path {
		t.Fatalf("unavailable SAF storage lost a shelved file: %#v", scn.shelvedLocalFiles)
	}
}
