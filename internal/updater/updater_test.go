package updater

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestUpdater_GetLatestUpdateShouldFallback(t *testing.T) {
	fixture := newUpdaterTestFixture(t)
	websiteUrl = fixture.deadAPIURL

	u := fixture.newUpdater("2.0.2", nil)
	// update channel is "github"

	update, err := u.GetLatestUpdate()
	require.NoError(t, err)
	require.NotNilf(t, update, "update should contain the latest release")
	assert.Equal(t, fixture.release.TagName, update.Release.TagName)
	assert.Equal(t, MajorRelease, update.Type)
}

func TestUpdater_GetLatestUpdateSeanime(t *testing.T) {
	fixture := newUpdaterTestFixture(t)

	u := fixture.newUpdater("2.0.2", nil)
	u.UpdateChannel = "seanime"

	update, err := u.GetLatestUpdate()
	require.NoError(t, err)
	require.NotNilf(t, update, "update should contain the latest release")
	assert.Equal(t, fixture.release.TagName, update.Release.TagName)
	assert.Equal(t, MajorRelease, update.Type)
}

func TestUpdater_GetLatestUpdate(t *testing.T) {
	fixture := newUpdaterTestFixture(t)
	u := fixture.newUpdater(fixture.release.Version, nil)
	u.UpdateChannel = "seanime"

	update, err := u.GetLatestUpdate()
	require.NoError(t, err)
	require.Nil(t, update)
}

func TestUpdater_GetLatestAndroidTVRelease(t *testing.T) {
	var statusProbeCalled atomic.Bool
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/status" {
			statusProbeCalled.Store(true)
			http.Error(w, "not expected for Android TV", http.StatusInternalServerError)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"url":          "https://github.com/UnoxyRich/seanime/releases/tag/v3.5.2",
			"html_url":     "https://github.com/UnoxyRich/seanime/releases/tag/v3.5.2",
			"tag_name":     "v3.5.2",
			"name":         "Seanime TV v3.5.2",
			"draft":        false,
			"prerelease":   false,
			"published_at": "2026-09-01T00:00:00Z",
			"body":         "Android TV release",
			"assets": []map[string]any{{
				"name":                 "app-arm64-v8a-release.apk",
				"content_type":         "application/vnd.android.package-archive",
				"state":                "uploaded",
				"size":                 1024,
				"browser_download_url": "https://github.com/UnoxyRich/seanime/releases/download/v3.5.2/app-arm64-v8a-release.apk",
			}},
		})
	}))
	defer server.Close()
	oldAndroidTVURL, oldGitHubStatusURL := androidTVGithubUrl, githubCheckUrl
	androidTVGithubUrl = server.URL + "/releases/latest"
	githubCheckUrl = server.URL + "/status"
	defer func() {
		androidTVGithubUrl, githubCheckUrl = oldAndroidTVURL, oldGitHubStatusURL
	}()

	u := New("3.4.0", nil, nil)
	u.client = server.Client()
	u.UpdateChannel = "androidtv"

	update, err := u.GetLatestUpdate()
	require.NoError(t, err)
	require.NotNil(t, update)
	assert.Equal(t, "v3.5.2", update.Release.TagName)
	require.Len(t, update.Release.Assets, 1)
	assert.Equal(t, "app-arm64-v8a-release.apk", update.Release.Assets[0].Name)
	assert.Equal(t, "androidtv", u.UpdateChannel)
	assert.False(t, statusProbeCalled.Load())
}
