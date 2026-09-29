package cassette

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type cachedSegment struct {
	pipeline *Pipeline
	segment  int32
	path     string
	size     int64
	modified time.Time
}

// Only completed segments are eligible: an active FFmpeg output must never be
// removed. Remote heads cover at most 24 seconds, bounding the unfinished data.
func (s *Session) trimCachedSegments(maxBytes int64) {
	var segments []cachedSegment
	var total int64
	completed := make(map[string]bool)
	var activePrefixes []string
	collect := func(p *Pipeline) {
		p.headsMu.RLock()
		for id, head := range p.heads {
			if head.segment >= 0 && head.cmd != nil {
				activePrefixes = append(activePrefixes, strings.TrimSuffix(filepath.Base(p.outPathFmt(id)), "%d.ts"))
			}
		}
		p.headsMu.RUnlock()
		for i := int32(0); i < int32(p.segments.Len()); i++ {
			if !p.segments.IsReady(i) {
				continue
			}
			path := p.segmentPath(i)
			info, err := os.Stat(path)
			if err != nil {
				p.segments.Forget(i)
				continue
			}
			total += info.Size()
			completed[path] = true
			segments = append(segments, cachedSegment{p, i, path, info.Size(), info.ModTime()})
		}
	}
	s.videosMu.Lock()
	for _, p := range s.videos {
		collect(p)
	}
	s.videosMu.Unlock()
	s.audiosMu.Lock()
	for _, p := range s.audios {
		collect(p)
	}
	s.audiosMu.Unlock()
	// Failed, superseded or seek-cancelled encoder heads can leave outputs which
	// are no longer referenced by a ready table. Remove these too; otherwise
	// repeated seeks could grow disk usage outside the completed-segment quota.
	entries, _ := os.ReadDir(s.Out)
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".ts") {
			continue
		}
		file := filepath.Join(s.Out, entry.Name())
		if completed[file] {
			continue
		}
		active := false
		for _, prefix := range activePrefixes {
			if strings.HasPrefix(entry.Name(), prefix) {
				active = true
				break
			}
		}
		// A new head may have started after the prefix snapshot. Fresh files are
		// protected until a later pass can classify that encoder safely.
		info, err := entry.Info()
		if !active && err == nil && time.Since(info.ModTime()) > 30*time.Second {
			_ = os.Remove(file)
		}
	}
	sort.Slice(segments, func(i, j int) bool { return segments[i].modified.Before(segments[j].modified) })
	for _, segment := range segments {
		if total <= maxBytes {
			break
		}
		if os.Remove(segment.path) == nil {
			segment.pipeline.segments.Forget(segment.segment)
			total -= segment.size
		}
	}
}
