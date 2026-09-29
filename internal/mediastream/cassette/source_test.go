package cassette

import (
	"context"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/rs/zerolog"
	"seanime/internal/mediastream/videofile"
)

func TestRemoteTimelineUsesAbsoluteFixedBoundariesAndForcesEncoding(t *testing.T) {
	logger := zerolog.New(io.Discard)
	info := &videofile.MediaInfo{Duration: 6.5, Video: &videofile.Video{Codec: "h264", Width: 1280, Height: 720, Bitrate: 3000000}}
	session := NewSession("http://127.0.0.1/source", "source", info, &Settings{FixedSegmentDuration: 2, StreamDir: t.TempDir(), Context: context.Background()}, nil, &logger)
	if err := session.WaitReady(); err != nil {
		t.Fatal(err)
	}
	if session.Ladder[0].OriginalCanTransmux || !session.Ladder[0].NeedsTranscode {
		t.Fatal("synthetic boundaries must force independently seekable encoding")
	}
	playlist := GenerateVariantPlaylist(session.Keyframes, 6.5, "signed-token")
	if strings.Count(playlist, "#EXTINF:2.000000") != 3 || !strings.Contains(playlist, "#EXTINF:0.500000\nsegment-3.ts?token=signed-token") {
		t.Fatalf("incorrect original timeline: %s", playlist)
	}
	if exact := fixedSegmentIndex("exact", 6, 2); len(exact.Keyframes) != 3 {
		t.Fatal("exact duration introduced an empty last segment")
	}
}

func TestCassetteDestroyCancelsLocalKeyframeProbeAndReleasesWaiters(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("fixture uses a POSIX executable")
	}
	directory := t.TempDir()
	started := filepath.Join(directory, "started")
	probe := filepath.Join(directory, "ffprobe")
	if err := os.WriteFile(probe, []byte("#!/bin/sh\nprintf started > '"+started+"'\nexec sleep 60\n"), 0700); err != nil {
		t.Fatal(err)
	}
	logger := zerolog.New(io.Discard)
	transcoder, err := New(&NewCassetteOptions{Logger: &logger, HwAccelKind: "disabled", TempOutDir: directory, FfprobePath: probe})
	if err != nil {
		t.Fatal(err)
	}
	completed := make(chan error, 1)
	go func() {
		_, err := transcoder.GetMaster("local-file.mkv", "local-cancel-test", &videofile.MediaInfo{Duration: 10, Video: &videofile.Video{Width: 64, Height: 64}}, "client", "")
		completed <- err
	}()
	deadline := time.After(2 * time.Second)
	for {
		if _, err := os.Stat(started); err == nil {
			break
		}
		select {
		case <-deadline:
			t.Fatal("local probe did not start")
		case <-time.After(time.Millisecond):
		}
	}
	destroyed := make(chan struct{})
	go func() { transcoder.Destroy(); close(destroyed) }()
	select {
	case <-destroyed:
	case <-time.After(2 * time.Second):
		t.Fatal("Destroy did not cancel/reap the local probe")
	}
	select {
	case err := <-completed:
		if err == nil {
			t.Fatal("cancelled local probe succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled probe left GetMaster blocked")
	}
}

func TestRemoteSegmentCacheEvictionAllowsBackwardRegeneration(t *testing.T) {
	directory := t.TempDir()
	logger := zerolog.New(io.Discard)
	pipeline := &Pipeline{segments: NewSegmentTable(2), outPathFmt: func(int) string { return filepath.Join(directory, "segment-%d.ts") }}
	for index := int32(0); index < 2; index++ {
		if err := os.WriteFile(pipeline.segmentPath(index), []byte("1234"), 0600); err != nil {
			t.Fatal(err)
		}
		pipeline.segments.MarkReady(index, 0)
	}
	orphan := filepath.Join(directory, "segment-original-old-0.ts")
	if err := os.WriteFile(orphan, []byte("old encoder output"), 0600); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-time.Minute)
	if err := os.Chtimes(orphan, old, old); err != nil {
		t.Fatal(err)
	}
	session := &Session{Out: directory, videos: map[Quality]*Pipeline{Original: pipeline}, logger: &logger}
	session.trimCachedSegments(4)
	if _, err := os.Stat(orphan); !os.IsNotExist(err) {
		t.Fatal("unreferenced encoder output escaped cache eviction")
	}
	ready := 0
	for index := int32(0); index < 2; index++ {
		if pipeline.segments.IsReady(index) {
			ready++
		} else {
			pipeline.segments.MarkReady(index, 1)
		}
	}
	if ready != 1 {
		t.Fatalf("ready segments after eviction = %d, want 1", ready)
	}
}
