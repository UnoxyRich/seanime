package mobile

import (
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestServerStartStopRestartServesEmbeddedWeb(t *testing.T) {
	for cycle := 0; cycle < 2; cycle++ {
		dataDir := filepath.Join(t.TempDir(), "data")
		cacheDir := filepath.Join(t.TempDir(), "cache")
		port := unusedLocalPort(t)

		StartServer(dataDir, cacheDir, port)
		if !WaitForServer(60_000) {
			t.Fatalf("server did not become ready (status=%s, error=%s)", ServerStatus(), ServerError())
		}

		for _, directory := range []string{dataDir, cacheDir} {
			info, err := os.Stat(directory)
			if err != nil {
				t.Fatalf("server did not create %s: %v", directory, err)
			}
			if !info.IsDir() {
				t.Fatalf("server path %s is not a directory", directory)
			}
		}

		response, err := http.Get("http://127.0.0.1:" + strconv.Itoa(port) + "/")
		if err != nil {
			t.Fatalf("request embedded web UI: %v", err)
		}
		body, readErr := io.ReadAll(response.Body)
		_ = response.Body.Close()
		if readErr != nil {
			t.Fatalf("read embedded web UI: %v", readErr)
		}
		if response.StatusCode != http.StatusOK {
			t.Fatalf("embedded web UI returned HTTP %d", response.StatusCode)
		}
		if !strings.Contains(string(body), "<title>Seanime</title>") {
			t.Fatal("embedded web UI response did not contain the Seanime page")
		}

		StopServer()
		waitForServerStatus(t, "stopped")
	}
}

func unusedLocalPort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("find unused local port: %v", err)
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port
}

func waitForServerStatus(t *testing.T, expected string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if ServerStatus() == expected {
			return
		}
		time.Sleep(25 * time.Millisecond)
	}
	t.Fatalf("server did not reach %q (status=%s, error=%s)", expected, ServerStatus(), ServerError())
}
