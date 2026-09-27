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

	"github.com/rs/zerolog"
)

func TestServerStartStopRestartServesEmbeddedWeb(t *testing.T) {
	for cycle := 0; cycle < 2; cycle++ {
		dataDir := filepath.Join(t.TempDir(), "data")
		cacheDir := filepath.Join(t.TempDir(), "cache")
		port := unusedLocalPort(t)

		SetAppInForeground(false)
		StartServer(dataDir, cacheDir, port)
		if !WaitForServer(60_000) {
			t.Fatalf("server did not become ready (status=%s, error=%s)", ServerStatus(), ServerError())
		}
		serverLifecycle.Lock()
		startedInBackground := !serverLifecycle.foreground && serverLifecycle.instance.inBackground
		backgroundJobsStopped := serverLifecycle.instance.stopJobs == nil
		serverLifecycle.Unlock()
		if !startedInBackground {
			t.Fatal("server did not honor the background state received while starting")
		}
		if !backgroundJobsStopped {
			t.Fatal("server started periodic jobs while backgrounded")
		}
		SetAppInForeground(true)
		serverLifecycle.Lock()
		resumedInForeground := serverLifecycle.foreground && !serverLifecycle.instance.inBackground
		backgroundJobsResumed := serverLifecycle.instance.stopJobs != nil
		serverLifecycle.Unlock()
		if !resumedInForeground {
			t.Fatal("server did not resume foreground work")
		}
		if !backgroundJobsResumed {
			t.Fatal("server did not restart periodic jobs after foregrounding")
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

func TestSetAppInForegroundDuringStartupOnlyRecordsDesiredState(t *testing.T) {
	serverLifecycle.Lock()
	previousInstance := serverLifecycle.instance
	previousStatus := serverLifecycle.status
	previousError := serverLifecycle.lastErr
	previousForeground := serverLifecycle.foreground
	previousForegroundSet := serverLifecycle.foregroundSet
	serverLifecycle.instance = &serverInstance{done: make(chan struct{})}
	serverLifecycle.status = "starting"
	serverLifecycle.lastErr = ""
	serverLifecycle.foreground = true
	serverLifecycle.foregroundSet = false
	serverLifecycle.Unlock()
	t.Cleanup(func() {
		serverLifecycle.Lock()
		serverLifecycle.instance = previousInstance
		serverLifecycle.status = previousStatus
		serverLifecycle.lastErr = previousError
		serverLifecycle.foreground = previousForeground
		serverLifecycle.foregroundSet = previousForegroundSet
		serverLifecycle.Unlock()
	})

	SetAppInForeground(false)
	serverLifecycle.Lock()
	defer serverLifecycle.Unlock()
	if serverLifecycle.status != "starting" {
		t.Fatalf("foreground update changed startup status to %q", serverLifecycle.status)
	}
	if serverLifecycle.foreground || !serverLifecycle.foregroundSet {
		t.Fatalf("foreground update was not retained: foreground=%t foregroundSet=%t", serverLifecycle.foreground, serverLifecycle.foregroundSet)
	}
}

func TestPausedTorrentRecoveryStateSurvivesServerRestart(t *testing.T) {
	dataDir := filepath.Join(t.TempDir(), "data")
	logger := zerolog.Nop()
	paused := &serverInstance{
		dataDir:        dataDir,
		pausedTorrents: []string{" hash-a ", "hash-b", "hash-a", ""},
	}
	persistPausedTorrents(paused, &logger)

	restored, err := loadPausedTorrents(dataDir)
	if err != nil {
		t.Fatalf("load paused torrent recovery state: %v", err)
	}
	if len(restored) != 2 || restored[0] != "hash-a" || restored[1] != "hash-b" {
		t.Fatalf("unexpected restored torrent hashes: %v", restored)
	}

	cacheDir := filepath.Join(t.TempDir(), "cache")
	port := unusedLocalPort(t)
	SetAppInForeground(false)
	t.Cleanup(func() {
		StopServer()
		SetAppInForeground(true)
	})
	StartServer(dataDir, cacheDir, port)
	if !WaitForServer(60_000) {
		t.Fatalf("server did not become ready (status=%s, error=%s)", ServerStatus(), ServerError())
	}
	serverLifecycle.Lock()
	instance := serverLifecycle.instance
	got := append([]string(nil), instance.pausedTorrents...)
	serverLifecycle.Unlock()
	if len(got) != 2 || got[0] != "hash-a" || got[1] != "hash-b" {
		t.Fatalf("server startup did not restore paused torrent intent: %v", got)
	}

	StopServer()
	waitForServerStatus(t, "stopped")
	SetAppInForeground(true)
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
