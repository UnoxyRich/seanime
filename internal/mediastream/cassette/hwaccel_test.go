package cassette

import (
	"reflect"
	"testing"

	"seanime/internal/mediastream/videofile"
)

func TestHardwareEncoderCandidates(t *testing.T) {
	tests := []struct {
		goos string
		want []string
	}{
		{goos: "android", want: []string{"mediacodec:h264_mediacodec"}},
		{goos: "darwin", want: []string{"nvidia:h264_nvenc", "qsv:h264_qsv", "vaapi:h264_vaapi", "videotoolbox:h264_videotoolbox"}},
		{goos: "linux", want: []string{"nvidia:h264_nvenc", "qsv:h264_qsv", "vaapi:h264_vaapi"}},
	}

	for _, tt := range tests {
		t.Run(tt.goos, func(t *testing.T) {
			candidates := hardwareEncoderCandidates(tt.goos)
			got := make([]string, 0, len(candidates))
			for _, candidate := range candidates {
				got = append(got, candidate.name+":"+candidate.encoder)
			}
			if !reflect.DeepEqual(got, tt.want) {
				t.Fatalf("hardwareEncoderCandidates(%q) = %v, want %v", tt.goos, got, tt.want)
			}
		})
	}
}

func TestMediaCodecProfileConvertsToEightBitFrames(t *testing.T) {
	profile := mediaCodecProfile()
	if profile.Name != "mediacodec" {
		t.Fatalf("profile name = %q, want mediacodec", profile.Name)
	}
	if !reflect.DeepEqual(profile.EncodeFlags[:2], []string{"-c:v", "h264_mediacodec"}) {
		t.Fatalf("encode flags = %v, expected MediaCodec H.264 encoder", profile.EncodeFlags)
	}

	filter := BuildVideoFilter(&profile, &videofile.Video{
		Width: 1920, Height: 1080, PixFmt: "yuv420p10le",
	}, 1280, 720)
	if filter != "scale=1280:720,format=yuv420p" {
		t.Fatalf("10-bit source filter = %q, want 8-bit scaled MediaCodec frames", filter)
	}

	filter = BuildVideoFilter(&profile, &videofile.Video{
		Width: 1280, Height: 720, PixFmt: "yuv420p",
	}, 1280, 720)
	if filter != "format=yuv420p" {
		t.Fatalf("same-size source filter = %q, want format conversion only", filter)
	}
}

func TestDetectHwAccelFailureIncludesMediaCodec(t *testing.T) {
	if !DetectHwAccelFailure("MediaCodec encoder initialization failed") {
		t.Fatal("expected MediaCodec initialization errors to trigger CPU fallback")
	}
}
