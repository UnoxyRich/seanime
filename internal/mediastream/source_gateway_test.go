package mediastream

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSourceGatewayPreservesRangeAndLocalAuthentication(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Header.Get("X-Seanime-Token") != "local-secret" || req.Header.Get("Cookie") != "Seanime-Client-Id=client" {
			http.Error(w, "missing authentication", http.StatusUnauthorized)
			return
		}
		http.ServeContent(w, req, "episode.mkv", time.Time{}, strings.NewReader("0123456789"))
	}))
	defer backend.Close()
	headers := http.Header{"X-Seanime-Token": {"local-secret"}, "Cookie": {"Seanime-Client-Id=client"}}
	gateway, source, err := newSourceGateway(context.Background(), backend.URL+"/episode", backend.URL, headers)
	if err != nil {
		t.Fatal(err)
	}
	defer gateway.close()
	for _, method := range []string{http.MethodGet, http.MethodHead} {
		request, _ := http.NewRequest(method, source, nil)
		request.Header.Set("Range", "bytes=3-5")
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		if response.StatusCode != http.StatusPartialContent || response.Header.Get("Content-Range") != "bytes 3-5/10" {
			t.Fatalf("lost range semantics: %d %v", response.StatusCode, response.Header)
		}
		if method == http.MethodGet && string(body) != "345" {
			t.Fatalf("body = %q", body)
		}
		if method == http.MethodHead && len(body) != 0 {
			t.Fatalf("HEAD returned body: %q", body)
		}
	}
}

func TestSourceGatewayRewritesNestedHLSAndKeyMapURIs(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Header.Get("X-Seanime-Token") != "local-secret" {
			http.Error(w, "auth", 401)
			return
		}
		switch req.URL.Path {
		case "/root": // extensionless provider playlist and relative resources
			w.Header().Set("Content-Type", "text/plain")
			io.WriteString(w, "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio/index.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=100\nvideo/index.m3u8\n")
		case "/video/index.m3u8", "/audio/index.m3u8":
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			io.WriteString(w, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2\nsegment.ts\n")
		default:
			io.WriteString(w, req.URL.Path)
		}
	}))
	defer backend.Close()
	gateway, source, err := newSourceGateway(context.Background(), backend.URL+"/root", backend.URL, http.Header{"X-Seanime-Token": {"local-secret"}})
	if err != nil {
		t.Fatal(err)
	}
	defer gateway.close()
	read := func(target string) string {
		t.Helper()
		response, err := http.Get(target)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(response.Body)
		response.Body.Close()
		if response.StatusCode != 200 {
			t.Fatalf("GET %s: %d %s", target, response.StatusCode, body)
		}
		return string(body)
	}
	master := read(source)
	for _, ref := range sourcePlaylistURI.FindAllStringSubmatch(master, -1) {
		if !strings.HasPrefix(ref[1], gateway.base+"/") || !strings.Contains(read(ref[1]), "#EXTM3U") {
			t.Fatal("audio URI was not rewritten")
		}
	}
	video := read(strings.TrimSpace(strings.Split(master, "\n")[3]))
	for _, ref := range sourcePlaylistURI.FindAllStringSubmatch(video, -1) {
		if !strings.HasPrefix(ref[1], gateway.base+"/") {
			t.Fatal("HLS attribute URI escaped gateway")
		}
		body := read(ref[1])
		if body != "/key.bin" && body != "/video/init.mp4" {
			t.Fatalf("wrong HLS relative resource: %q", body)
		}
	}
	segment := strings.TrimSpace(strings.Split(video, "\n")[4])
	if read(segment) != "/video/segment.ts" {
		t.Fatal("segment was not rewritten")
	}
}

func TestSourceGatewayDoesNotLeakLocalHeadersAcrossRedirect(t *testing.T) {
	external := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		for _, header := range []string{"X-Seanime-Token", "Cookie", "Authorization", "Origin", "Referer"} {
			if req.Header.Get(header) != "" {
				t.Errorf("leaked %s", header)
			}
		}
		io.WriteString(w, "external media")
	}))
	defer external.Close()
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		http.Redirect(w, req, external.URL+"/media", http.StatusFound)
	}))
	defer backend.Close()
	headers := http.Header{"X-Seanime-Token": {"secret"}, "Cookie": {"secret"}, "Authorization": {"Bearer secret"}, "Origin": {backend.URL}, "Referer": {backend.URL}}
	gateway, source, err := newSourceGateway(context.Background(), backend.URL+"/redirect", backend.URL, headers)
	if err != nil {
		t.Fatal(err)
	}
	defer gateway.close()
	response, err := http.Get(source)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, response.Body)
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatal(response.StatusCode)
	}
}

func TestSourceGatewayRewritesExtensionlessHLSDiscoveredInRangeResponse(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == "/segment.ts" {
			io.WriteString(w, "segment")
			return
		}
		w.Header().Set("Content-Type", "text/plain")
		http.ServeContent(w, req, "root", time.Time{}, strings.NewReader("#EXTM3U\n#EXTINF:2\nsegment.ts\n"))
	}))
	defer backend.Close()
	gateway, source, err := newSourceGateway(context.Background(), backend.URL+"/root", backend.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer gateway.close()
	req, _ := http.NewRequest(http.MethodGet, source, nil)
	req.Header.Set("Range", "bytes=0-")
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(response.Body)
	response.Body.Close()
	if response.StatusCode != http.StatusOK || !strings.Contains(string(body), gateway.base+"/") {
		t.Fatalf("range manifest was not rewritten: %d %s", response.StatusCode, body)
	}
}

func TestSourceGatewayStopCancelsPendingRead(t *testing.T) {
	started, cancelled := make(chan struct{}), make(chan struct{})
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		close(started)
		<-req.Context().Done()
		close(cancelled)
	}))
	defer backend.Close()
	gateway, source, err := newSourceGateway(context.Background(), backend.URL+"/slow", backend.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	finished := make(chan struct{})
	go func() {
		response, _ := http.Get(source)
		if response != nil {
			response.Body.Close()
		}
		close(finished)
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("read did not start")
	}
	gateway.close()
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		t.Fatal("upstream read was not cancelled")
	}
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("gateway request survived shutdown")
	}
}

func TestSourceGatewayRejectsUnboundedPlaylistAndUnknownResources(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		io.WriteString(w, "#EXTM3U\n"+strings.Repeat("x", maxSourcePlaylistBytes))
	}))
	defer backend.Close()
	gateway, source, err := newSourceGateway(context.Background(), backend.URL+"/huge.m3u8", backend.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer gateway.close()
	for target, status := range map[string]int{source: http.StatusBadGateway, gateway.base + "/unregistered": http.StatusNotFound} {
		response, err := http.Get(target)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != status {
			t.Fatalf("%s: %d, want %d", target, response.StatusCode, status)
		}
	}
}
