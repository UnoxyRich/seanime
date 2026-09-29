package mediastream

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

// sourceGateway gives ffprobe/ffmpeg a seekable loopback URL without staging a
// remote file. Seanime authentication is forwarded only to the original local
// server. Provider headers remain in the existing /proxy URL, including every
// nested HLS playlist, segment, subtitle, encryption key and initialization map.
type sourceGateway struct {
	ctx       context.Context
	cancel    context.CancelFunc
	server    *http.Server
	client    *http.Client
	transport *http.Transport
	base      string
	origin    *url.URL
	headers   http.Header
	mu        sync.RWMutex
	targets   map[string]*url.URL
}

const maxSourcePlaylistBytes = 4 << 20
const maxSourceGatewayTargets = 8192

var sourcePlaylistURI = regexp.MustCompile(`URI="([^"]+)"`)

func newSourceGateway(ctx context.Context, sourceURL, serverOrigin string, headers http.Header) (*sourceGateway, string, error) {
	u, err := parseSourceURL(sourceURL)
	if err != nil {
		return nil, "", err
	}
	origin, err := url.Parse(serverOrigin)
	if err != nil {
		return nil, "", err
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, "", err
	}
	lifetime, cancel := context.WithCancel(ctx)
	g := &sourceGateway{
		ctx: lifetime, cancel: cancel, base: "http://" + listener.Addr().String(),
		origin: origin, headers: headers.Clone(), targets: make(map[string]*url.URL),
		transport: &http.Transport{Proxy: http.ProxyFromEnvironment, DisableCompression: true,
			ResponseHeaderTimeout: 30 * time.Second, IdleConnTimeout: 30 * time.Second},
	}
	g.client = &http.Client{Transport: g.transport, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 10 {
			return errors.New("too many media source redirects")
		}
		// Redirects are reclassified, including a provider redirect back to the
		// local proxy. Never let local cookies or tokens reach a different host.
		g.applyHeaders(req)
		return nil
	}}
	g.server = &http.Server{Handler: http.HandlerFunc(g.serve), ReadHeaderTimeout: 10 * time.Second}
	root, err := g.register(u)
	if err != nil {
		_ = listener.Close()
		cancel()
		return nil, "", err
	}
	go func() { _ = g.server.Serve(listener) }()
	return g, root, nil
}

func parseSourceURL(raw string) (*url.URL, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u == nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, errors.New("media source must be an HTTP or HTTPS URL")
	}
	u.Fragment = ""
	return u, nil
}

func (g *sourceGateway) close() {
	g.cancel()
	_ = g.server.Close()
	g.transport.CloseIdleConnections()
}

func (g *sourceGateway) sameOrigin(u *url.URL) bool {
	if u.Scheme != g.origin.Scheme || u.Port() != g.origin.Port() {
		return false
	}
	if strings.EqualFold(u.Hostname(), g.origin.Hostname()) {
		return true
	}
	a, b := net.ParseIP(u.Hostname()), net.ParseIP(g.origin.Hostname())
	return a != nil && b != nil && a.IsLoopback() && b.IsLoopback()
}

func (g *sourceGateway) applyHeaders(req *http.Request) {
	for _, key := range []string{"Cookie", "Authorization", "X-Seanime-Token", "X-Seanime-Nakama-Token", "X-Seanime-Client-Id", "X-Seanime-Client-Id-Proof", "X-Seanime-Client-Platform", "Origin", "Referer"} {
		req.Header.Del(key)
		if g.sameOrigin(req.URL) {
			for _, value := range g.headers.Values(key) {
				req.Header.Add(key, value)
			}
		}
	}
}

func (g *sourceGateway) register(u *url.URL) (string, error) {
	if _, err := parseSourceURL(u.String()); err != nil {
		return "", err
	}
	hash := sha256.Sum256([]byte(u.String()))
	key := hex.EncodeToString(hash[:])
	g.mu.Lock()
	defer g.mu.Unlock()
	if _, exists := g.targets[key]; !exists && len(g.targets) >= maxSourceGatewayTargets {
		return "", errors.New("media source playlist has too many resources")
	}
	g.targets[key] = u
	// Keep an HLS extension for demuxer detection even when the backend /proxy
	// endpoint itself has no extension. Content sniffing handles extensionless HLS.
	suffix := ""
	if sourceIsPlaylist(u) {
		suffix = ".m3u8"
	}
	return g.base + "/" + key + suffix, nil
}

func sourceIsPlaylist(u *url.URL) bool {
	if strings.HasSuffix(strings.ToLower(u.Path), ".m3u8") {
		return true
	}
	if nested := u.Query().Get("url"); nested != "" {
		if parsed, err := url.Parse(nested); err == nil {
			return strings.HasSuffix(strings.ToLower(parsed.Path), ".m3u8")
		}
	}
	return false
}

func (g *sourceGateway) rewritePlaylist(body []byte, base *url.URL) ([]byte, error) {
	var rewriteErr error
	rewrite := func(raw string) string {
		u, err := url.Parse(raw)
		if err != nil {
			rewriteErr = err
			return raw
		}
		mapped, err := g.register(base.ResolveReference(u))
		if err != nil {
			rewriteErr = err
			return raw
		}
		return mapped
	}
	lines := strings.Split(string(body), "\n")
	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if strings.HasPrefix(trimmed, "#") {
			lines[i] = sourcePlaylistURI.ReplaceAllStringFunc(line, func(value string) string {
				return `URI="` + rewrite(sourcePlaylistURI.FindStringSubmatch(value)[1]) + `"`
			})
		} else {
			lines[i] = rewrite(trimmed)
		}
	}
	if rewriteErr != nil {
		return nil, rewriteErr
	}
	return []byte(strings.Join(lines, "\n")), nil
}

func (g *sourceGateway) serve(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodGet && req.Method != http.MethodHead {
		http.Error(w, "unsupported media method", http.StatusMethodNotAllowed)
		return
	}
	key := strings.TrimSuffix(strings.TrimPrefix(req.URL.Path, "/"), ".m3u8")
	g.mu.RLock()
	target := g.targets[key]
	g.mu.RUnlock()
	if target == nil {
		http.NotFound(w, req)
		return
	}
	ctx, cancel := context.WithCancel(req.Context())
	stop := context.AfterFunc(g.ctx, cancel)
	defer func() { stop(); cancel() }()
	upstream, err := http.NewRequestWithContext(ctx, req.Method, target.String(), nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	g.applyHeaders(upstream)
	for _, key := range []string{"Range", "If-Range", "User-Agent"} {
		if value := req.Header.Get(key); value != "" {
			upstream.Header.Set(key, value)
		}
	}
	if sourceIsPlaylist(target) {
		// FFmpeg's HTTP layer can request bytes=0- even for a playlist. Fetch
		// manifests whole so every relative resource is rewritten consistently.
		upstream.Header.Del("Range")
		upstream.Header.Del("If-Range")
	}
	response, err := g.client.Do(upstream)
	if err != nil {
		http.Error(w, "media source unavailable", http.StatusBadGateway)
		return
	}
	defer response.Body.Close()
	contentType := response.Header.Get("Content-Type")
	isPlaylist := sourceIsPlaylist(target) || strings.Contains(strings.ToLower(contentType), "mpegurl")
	// Do not buffer media responses. A small peek also supports provider HLS
	// endpoints with neither a filename extension nor the correct MIME type.
	var prefix []byte
	startsAtZero := response.StatusCode == http.StatusPartialContent && strings.HasPrefix(response.Header.Get("Content-Range"), "bytes 0-")
	if req.Method == http.MethodGet && (response.StatusCode == http.StatusOK || startsAtZero) && !isPlaylist {
		prefix = make([]byte, 7)
		n, _ := io.ReadFull(response.Body, prefix)
		prefix = prefix[:n]
		isPlaylist = string(prefix) == "#EXTM3U"
	}
	if req.Method == http.MethodGet && isPlaylist && response.StatusCode == http.StatusPartialContent {
		// Extensionless/mislabeled HLS was discovered from the first bytes of a
		// partial response. Retry once without Range before rewriting the manifest.
		_ = response.Body.Close()
		retry := upstream.Clone(ctx)
		retry.Header = upstream.Header.Clone()
		retry.Header.Del("Range")
		retry.Header.Del("If-Range")
		response, err = g.client.Do(retry)
		if err != nil {
			http.Error(w, "media playlist unavailable", http.StatusBadGateway)
			return
		}
		defer response.Body.Close()
		prefix = nil
	}
	if req.Method == http.MethodGet && response.StatusCode == http.StatusOK && isPlaylist {
		body, readErr := io.ReadAll(io.LimitReader(io.MultiReader(strings.NewReader(string(prefix)), response.Body), maxSourcePlaylistBytes+1))
		if readErr != nil || len(body) > maxSourcePlaylistBytes {
			http.Error(w, "media playlist is too large", http.StatusBadGateway)
			return
		}
		body, err = g.rewritePlaylist(body, response.Request.URL)
		if err != nil {
			http.Error(w, fmt.Sprintf("invalid media playlist: %v", err), http.StatusBadGateway)
			return
		}
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(body)
		return
	}
	for _, key := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag"} {
		if value := response.Header.Get(key); value != "" {
			w.Header().Set(key, value)
		}
	}
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(response.StatusCode)
	if req.Method != http.MethodHead {
		_, _ = io.Copy(w, io.MultiReader(strings.NewReader(string(prefix)), response.Body))
	}
}
