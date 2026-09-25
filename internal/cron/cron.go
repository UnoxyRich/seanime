package cron

import (
	"context"
	"seanime/internal/core"
	"time"
)

type JobCtx struct {
	App *core.App
}

// RunJobs starts the server's periodic jobs and stops their ticker goroutines
// when ctx is cancelled. Work already in progress is allowed to finish.
func RunJobs(ctx context.Context, app *core.App) {

	// Run the jobs only if the server is online
	jobCtx := &JobCtx{
		App: app,
	}

	refreshAnilistTicker := time.NewTicker(10 * time.Minute)
	refreshAnilistSimulatedTicker := time.NewTicker(30 * time.Minute)
	refreshLocalDataTicker := time.NewTicker(30 * time.Minute)
	refetchReleaseTicker := time.NewTicker(1 * time.Hour)
	refetchAnnouncementsTicker := time.NewTicker(10 * time.Minute)

	go func() {
		defer refreshAnilistTicker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-refreshAnilistTicker.C:
				if app.IsOffline() || app.GetUser().IsSimulated {
					continue
				}
				RefreshAnilistDataJob(jobCtx)
				app.SyncAnilistToSimulatedCollection()
			}
		}
	}()

	go func() {
		defer refreshAnilistSimulatedTicker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-refreshAnilistSimulatedTicker.C:
				if app.IsOffline() || !app.GetUser().IsSimulated {
					continue
				}
				RefreshAnilistDataJob(jobCtx)
			}
		}
	}()

	go func() {
		defer refreshLocalDataTicker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-refreshLocalDataTicker.C:
				if app.IsOffline() {
					continue
				}
				SyncLocalDataJob(jobCtx)
			}
		}
	}()

	go func() {
		defer refetchReleaseTicker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-refetchReleaseTicker.C:
				if app.IsOffline() {
					continue
				}
				app.Updater.ShouldRefetchReleases()
			}
		}
	}()

	go func() {
		defer refetchAnnouncementsTicker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-refetchAnnouncementsTicker.C:
				if app.IsOffline() {
					continue
				}
				app.Updater.FetchAnnouncements()
			}
		}
	}()

}
