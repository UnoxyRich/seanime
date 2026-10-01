# Remote UI review — October 1, 2026

The current R25 corrective revision passes **381 JVM tests**, **34 unchanged-reference
layout comparisons**, lint (zero errors, 87 warnings), ARM64/x86_64 and matching
instrumentation APK builds. Its device run is pending. The latest completed R24 run is summarized below. No host result is
presented as a device pass or complete feature parity.

The frozen aggregate took 205 seconds. All 259 Android source hashes and all 34
reference images remained unchanged. Its source digest is
`090a0474433ce24d06468639e651ecf069fde5b4f85e92d4efc92f14435c9698`.
The one additional lint warning concerns the optional Modifier parameter order.
All APKs pass signature and ZIP alignment checks, both main APKs contain seven
16 KiB-aligned native libraries, and 145 literal API contracts pass. Go core,
module files, routes, payloads and schema remain unchanged from `63200d6a`.
The expanded compressed shader retains its pinned original SHA-256 in both APKs.

## Actual emulator result

[Run 36859935391](https://github.com/UnoxyRich/seanime/actions/runs/36859935391)
tested source `00e8303e5cdf79af13cf662e26e26c3e5f30123a` on Android TV API 36
x86_64 with KVM. The full ordinary suite completed without a process crash:
**183 unique cases: 169 passed, 9 failed, 5 skipped**. Sanitized JUnit is the
counting source; console progress transiently counted skipped cases twice.
Both installed app/test APK hashes matched the exact runner builds.

All ten startup tests passed, including real Go startup/restart, search and
recreation, and D-pad/content/Back recovery across all eleven destinations.
Offline poster loading through the existing asset route, cover fallback states,
and manga page-image loading/retry passed with fixture data. These results do
not establish live AniList metadata or covers.

The nine failures cover transport focus at unavailable episode boundaries;
rail-to-Logs entry before Back restoration; manga reader jump with the IME;
picker reopen; manga catalog/chapter return; two plugin handoffs; and two
Settings return-focus cases. Passing individual coverage does not supersede
these failures or establish the full workflows.

The independent cold owned-media journey ran once and failed. Real remote keys
reached Library → Manage → Files → Play. Exact native 320×240 video dimensions,
one-minute duration, decoded video/audio buffers, pause, and a roughly ten-second
seek were observed. The test then failed its post-seek compositor pixel check.
The recording shows the Buffering badge covering the sampled region. This
establishes an insufficiently synchronized observation; it does not establish
that buffering reliably settles. Audio switching, subtitles, Picture mode,
Explorer playback, and full return acceptance were **not reached** in this run.
This was owned generated test media, not anime or Bloom Into You.

Two genuine recordings were retained (59.64s ordinary startup excerpt and
106.61s owned-journey recording). A 15s stream-copied excerpt and actual frames
were extracted for progress review. The post-seek image deliberately preserves
the visible Buffering badge. A bright Downloads rail item shows acquired focus;
the Settings image shows selection only. All images retain actual emulator
pixels; none use a simulated focus ring.

Screenshot/fixture-manifest collection still failed. Pinned AGP 8.10.1 bytecode
shows `android.injected.androidTest.leaveApksInstalledAfterRun` defaults false,
and its connected-test factory requests UTP package removal before Gradle
returns. That removes app-private evidence while `/data/local/tmp` recordings
survive. The default R24 console did not expose the uninstall event itself.
The collection ZIP SHA-256 is
`d47c7779d5625b253f49cb9d8de799d69ece4b1bd4a1d5d053da9fc3af1322a0`.
The tested x86_64 APK SHA-256 is
`46a7eeffcfdf2695de19cfa1570080e573c782c2f2d1df32a45227ccdea63a24`;
test APK: `85ea70ebcf3bace1af40fce2207d66badee3e4db1a47779fc2d689c53fee951b`.

The earlier [R23 run](https://github.com/UnoxyRich/seanime/actions/runs/36850609039)
recorded 150 passes, 28 failures and 5 isolated skips. Its owned journey stopped
at zero native video dimensions before seeking. Those historical failures and
artifacts are retained; they are not current passes.

## Corrections in the current source

| Observed cause | Current correction | Verification limit |
| --- | --- | --- |
| Empty Media3 effects list creates a video graph, with zero native video-size reporting | Ordinary Off playback stays on the direct surface path; real presets initialize before prepare; Off↔On rebuilds preserve playback checkpoints; HDR is checked from actual decoder-format callbacks | Player host tests and compilation pass; new runtime verification required |
| Details removal can automatically focus the rail and cancel saved-card restoration | Preserve explicit return intent until the saved card wins or a later real remote input replaces it | New loading/focus regressions and offscreen/large-ID return host mirrors pass |
| Title picker has no IME Search/hide contract | Shared IME/button submission hides the dialog keyboard and restores Search | Compiles; physical IME acceptance remains pending |
| Nested hidden-title picker loses its Add title opener | Rearm that exact opener on child dismissal | Compiles; device rerun required |
| Successful marketplace install arms restoration on the newly disabled row | Restore an actionable row only; successful install returns to the stable header | Host mirror passes; device rerun required |
| Tests act while an old dialog still owns the Android window or controls remain disabled | Require exact target-window ownership and enabled/readback state; use D-pad from safe focus | Real assertions retained; host cannot establish physical window timing |
| Fixtures/expectations differ from current routes and labels | Correct loopback hostname, missing offline queue response, duplicate status selectors, complete labels and lazy-page restoration expectations | Original payload, cancellation and request-count requirements remain |
| Screenshot collector double-quotes exec-out scripts and trusts its remote exit status | Supply raw arguments, validate a cleanup receipt, retain bounded allowlisted evidence and safe diagnostic reasons | 32 Python regressions pass; live collection rerun required |

### R25 focused corrections (device verification pending)

- Rapid destination Center→Right previously focused the departing content, then
  left the new Logs screen without any focused control for the full ten-second
  host bound. Entry now follows the latest route identity and waits for placement
  and window readiness. Later keys or Back cancel it; immediate Back returns to
  the rail without opening Exit. All 20 permanent Host/Loading cases pass,
  including normal saved focus and the three new real-key regressions. A separate
  Compose batch had already entered content before Up; its Refresh focus is
  preserved as diagnostic evidence, not described as lost focus.
- Explicit left/right transport neighbors keep focus in the transport row at
  unavailable Previous/Next edges. A real Compose-key regression reproduced
  focus escaping to Audio and passes after the correction; vertical controls
  remain reachable. Eleven focused player tests and instrumentation compilation
  pass.
- The owned journey now requires READY, a newly rendered decoder buffer, and
  disappearance of the actual buffering badge before the unchanged ±25 color
  and 4:3 aspect checks. No color tolerance or successful-playback criterion was
  relaxed. A persistent buffering problem will still fail.
- Settings preserves the saved row while automatic fallback focus arrives during
  page reattachment; a newer real navigation/Select key cancels that pending
  restoration. Device reentry passes its host mirror, with native confirmation
  still required.
- Picker/reader/manga and plugin tests observe disposal, the exact return target
  and native window readiness before the next key. Real-device IME/window
  acceptance remains required; inconclusive host dialog results are retained.
- The collector requests AGP's supported keep-installed option on an explicit
  disposable emulator, then checks package/run-as access before collecting.
  A teardown simulation reproduces both R24 error classes without the option
  and retains both evidence kinds with it. All 44 collector regressions pass.
  Actual post-run screenshot/manifest collection remains unverified.
- Three separate cold AVDs now run the existing owned-library-management,
  signed raw-media playback, and separate external-player handoff methods.
  Exact opt-in/fresh-process arguments, APK identity, one test/no skip, typed
  owned manifest, and normal test exit status are required. The metadata-assisted
  scan test remains gated by the recorded AniList denial.

Robolectric mirrors reproduced several fixture/readiness defects. Some dialog
cases remain inconclusive because its physical window activation differs from
Android. A cleanup diagnostic reached the exact DELETE sequence `[1, 2, 1]` and
2/2 confirmed results after evaluating the same UI-and-counter conjunction in
UI-first order; its final dialog-focus check remains device-only. Failed host
mirrors have not been silently relabeled as passes.

The strengthened owned journey now generates a 320×240 4:3 video with a square
marker, two PCM tracks and SRT. It verifies real seek/audio/subtitles/return,
then Picture Off→Mode C→Off with retained source/checkpoint/pause/track state,
timestamp-derived pixels, square geometry and black pillarboxes. Native Off
video dimensions remain exact. Effects-on videoSize reporting is still not
substituted with coded input dimensions; the new graph geometry checks are
**unrun**. No Anime4K runtime pass is claimed from compilation or colored pixels.

CI continues to use separate disposable AVDs. Original test exit codes remain
authoritative. Its collector now retains exact approved screenshots, sanitized
JUnit, installed/build identities and typed fixture observations. The ordinary
recording remains bounded to 60s at 2Mbps; the exact owned journey receives up
to 120s at 1Mbps. Both are 1280×720 without audio, capped at 32MiB and labeled
bounded excerpts, not automatically complete-flow evidence.

### Local build environment

The first R25 aggregate stopped after 14 seconds, before app compilation, when
`gomobile init` queried `gobind@latest` and the ordinary registry request returned
403. The retry uses Go's documented [local module-cache proxy](https://go.dev/ref/mod#module-cache)
with no network fallback and no checksum-policy change. The existing gomobile
module is `v0.0.0-20260602190626-68735029466e`; the existing gobind module is
`v0.0.0-20260908204917-8b95e45f8d3e`. Gobind remains byte-identical after init:
`288d97e96388a568180fe81591717299028528a4c2db0263ee7b6bd0d3e2fe4b`.
The failed attempt and its exact source snapshot remain separate evidence.
This build-cache configuration does not change app or server networking.

## Remaining acceptance limits

| Scope | Status |
| --- | --- |
| Current native routes, focus, forms and playback | R25 local aggregate passes; current corrections require fresh device verification |
| R24 ordinary automation | 169 passed / 9 failed / 5 intentionally isolated skips |
| Isolated library management, raw media and external-player handoff | Added as separate disposable-AVD cases for R25; not yet run |
| Metadata-assisted local scan, live AniList titles/covers and Bloom streaming | Blocked by the recorded HTTP 403; no proxy, retry workaround or synthetic catalog |
| Library, lists, manga, offline, downloads, providers, Nakama, settings and reports | Feature-level gates remain in the [layout/data matrix](2026-10-01-tv-layout-and-live-data.md) |
| Arbitrary browser-DOM plugin presentation and independent MAL APIs | Previously documented partial/missing capabilities remain |
| Physical TV/USB, codecs/GPU/HDR, accounts/providers, two Nakama peers and release signing | Unverified; unchanged external/device gates |

The [device inventory](2026-10-01-remote-ui-device-plan.json) contains 183 methods.
The earlier [run 36844301124](https://github.com/UnoxyRich/seanime/actions/runs/36844301124)
reached 71/181 methods before nine failures and a process crash; it is historical
and has not been promoted to acceptance of either current revision.

| Local R25 artifact | SHA-256 |
| --- | --- |
| ARM64 | `d98c6ac2cf53e6fdb5b2d72b0ce3d3cd72caf72ffe15930a917af3dae23fe6f7` |
| x86_64 | `54e1480e792ad2064b60e84525897c5d1fc438edce96dcee4f4db7adc4b9b2cf` |
| Instrumentation | `2fb9765f9abbdf3db16dd3973b1b153813b2e0415cf56c71f271a32b6c3c19d2` |

These local artifacts retain the existing debug certificate. GitHub records its
own exact built/installed pair; local hashes must not be substituted for runner
identities. Earlier delivered R21 artifacts remain unchanged.
