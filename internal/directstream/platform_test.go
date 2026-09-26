package directstream

import "testing"

func TestDefaultPlaybackTarget(t *testing.T) {
	for _, goos := range []string{"android", "linux", "darwin", "windows"} {
		for _, mpvEnabled := range []bool{false, true} {
			want := PlaybackTargetVideoCore
			if mpvEnabled && goos != "android" {
				want = PlaybackTargetMpvCore
			}
			if got := DefaultPlaybackTarget(goos, mpvEnabled); got != want {
				t.Errorf("DefaultPlaybackTarget(%q, %v) = %q, want %q", goos, mpvEnabled, got, want)
			}
		}
	}
}
