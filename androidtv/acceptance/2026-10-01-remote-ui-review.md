# Remote UI review — October 1, 2026

The current revision is awaiting a new device run. Its local R23 aggregate
passes **375 JVM tests**, **34 unchanged-reference screenshot comparisons**,
lint (zero errors, 85 warnings), ARM64/x86_64 and instrumentation APK builds.
The aggregate took 220 seconds; all 258 recorded Android source hashes remained
unchanged during it. The two added lint advisories concern newer versions of
unchanged dependencies. Twenty evidence-collector tests also pass. Signatures,
16 KiB native/ZIP alignment and the 145 literal API contracts pass. Go core,
module files, routes, payloads and database schema remain unchanged.

## Actual device result that prompted this correction

[GitHub run 36844301124](https://github.com/UnoxyRich/seanime/actions/runs/36844301124)
tested source `8ff4ce9e572131c9ba1aa8816a845c3f9d05f38e` on an Android TV API 36
x86_64 emulator with KVM. SDK setup, frontend/Go checks, both APK builds and
alignment passed. Instrumentation reached **71 of 181 methods**, reporting
**9 failures and 4 skips**, then the app process crashed. The remaining 110
methods did not run. That workflow did not retain per-method XML or visual
artifacts, so this is not a complete UI acceptance result.

| Observed failure | Correction in this source | Current verification |
| --- | --- | --- |
| Four startup tests expected obsolete inline search/account controls | Actual D-pad navigation through Search and Lists & sort; save/reopen/recreate/background assertions retained | Compiled; device rerun pending |
| Playlist Cancel left the editor open | Real rail/editor/footer keys; hide an observed IME before navigating the app footer; preserve backend no-write/CRUD assertions | Compiled; device rerun pending |
| Video dimensions read as zero | Atomically wait for the current source, exact dimensions and a new decoded frame after seek | Compiled; device rerun pending |
| Stopped player status was lost | Retire decoder ownership before release callbacks; ignore retired decoder events | Compiled and existing host checks pass; device rerun pending |
| Disabled episode control retained focus | Preserve placement for a new transfer to the same visible Play target; retain strict disabled/not-focused/Play-focused checks | Three new JVM regressions pass; device rerun pending |
| AutoDownloader dialog measurement crashed | Supply the production artwork provider in the direct test fixture; preserve the first exception through cleanup | Host reproduction identified the missing provider; reopen/reconcile regression and eight model tests pass |

Four further direct artwork-rendering test fixtures now receive the same
provider as production. Production `SeanimeTvApp` already provided it. No
production exception was suppressed or failed test skipped. The corrected tests
retain their behavioral requirements while following the current UI.

## Genuine remote journeys

The coverage review also found an untested production bug: Files/Explorer showed
Play for unmatched files, but called the metadata-dependent directstream route,
which rejects media ID zero. An explicit unmatched-file source now uses the
existing signed `/api/v1/mediastream/file` endpoint. Matched files retain their
existing route. The raw source keeps its exact path/title, no fabricated anime
or episode identity, fresh playback identity, and the existing library-root
boundary. Player return restores the file's Play control.

The new isolated journey generates an owned color-changing MKV, two audio tones
and embedded SRT, imports only its unmatched index through the existing API,
then drives real MainActivity using D-pad/Select/Back through Files and Explorer
to playback. It checks signed ranges, rendered colors before/after seek, decoded
audio selection, visible subtitle pixels/on-off, recovery and return focus.
It does not call player/coordinator play or seek methods or force semantic focus.
This test remains **unrun** in the current source. The normal startup suite also
adds a directional sweep across all eleven destinations and bottom-rail bounds.

CI uses separate newly created disposable AVDs for the ordinary suite and the
single isolated journey. The selected journey must report exactly one pass,
zero skips and matched installed app/test APK hashes. Evidence is captured
before teardown: approved screenshots, a bounded 60-second recording, sanitized
JUnit/source frames and validated owned-fixture observations. The recording is
an early-run excerpt without audio, not proof of every interaction. All original
test exit codes remain authoritative; evidence validation is a separate gate.
The [current inventory](2026-10-01-remote-ui-device-plan.json) contains 183 methods.

## Remaining acceptance limits

| Scope | Status |
| --- | --- |
| Current native routes, D-pad navigation, playback controls and real image pipeline | Implemented; host checks pass; current device rerun required |
| Library, playlists, manga, offline, downloads, providers, Nakama, settings and reports | Preserve the feature-level gates in the [layout/data matrix](2026-10-01-tv-layout-and-live-data.md); the partial CI run does not close them |
| Real AniList titles/covers and Bloom Into You streaming | Blocked by the recorded HTTP 403; no retry, proxy or fabricated catalog used |
| Owned generated video/artwork | Test media only; must be labeled separately from real anime/provider success |
| Arbitrary browser-DOM plugin presentation and independent MAL APIs | Previously documented partial/missing capabilities remain |
| Physical TV/USB, device codecs/GPU, real accounts/providers, two Nakama peers and release signing | Unverified; unchanged external/device gates |

| Local R23 artifact | SHA-256 |
| --- | --- |
| ARM64 | `c49e489aebff2cf2791340bd4bb6998b039a6c89077c16eacd266637336a93c2` |
| x86_64 | `0e7c9c46cb067d74d58ea712aab1e675a1dd50babd8f3c41a220384051dee781` |
| Instrumentation | `1325c3c9e6136d992d163bd4d1c27b1521154a5550d931b946e021efcc2aad5a` |

These local artifacts use the existing debug certificate. GitHub builds record
their own exact installed/build hashes; do not substitute the local hashes for
runner-built APK identities. Earlier delivered R21 artifacts remain unchanged.
