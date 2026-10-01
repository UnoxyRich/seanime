# Remote UI review — October 1, 2026

The current R24 corrective revision passes **377 JVM tests**, **34 unchanged-reference
layout comparisons**, lint (zero errors, 86 warnings), ARM64/x86_64 and matching
instrumentation APK builds. Its device run is still pending. No host result is
presented as a device pass or complete feature parity.

The frozen aggregate took 217 seconds. All 258 Android source hashes and all 34
reference images remained unchanged. Its source digest is
`55fade96a568b7d6bf5dd5eba58929316c8cbbe390cd58efe95ad94667a26e98`.
The one additional lint warning is a SharedPreferences KTX-style suggestion.
All APKs pass signature and ZIP alignment checks, both main APKs contain seven
16 KiB-aligned native libraries, and 145 literal API contracts pass. Go core,
module files, routes, payloads and schema remain unchanged from `63200d6a`.
The expanded compressed shader retains its pinned original SHA-256 in both APKs.

## Actual emulator result

[Run 36850609039](https://github.com/UnoxyRich/seanime/actions/runs/36850609039)
tested source `e1231dfaa3eecd0ddc1ecfe2f57cbed2c002b764` on Android TV API 36
x86_64 with KVM. The full ordinary suite completed without a process crash:
**183 unique cases: 150 passed, 28 failed, 5 skipped**. Sanitized JUnit is the
counting source; console progress transiently counted skipped cases twice.
Both installed app/test APK hashes matched the exact runner builds.

All ten startup tests passed, including real Go startup/restart, search and
recreation, and D-pad/content/Back recovery across all eleven destinations.
Offline poster loading through the existing asset route, cover fallback states,
and manga page-image loading/retry also passed with fixture data. These results
do not establish live AniList metadata or covers.

The independent cold owned-media journey ran once and failed. Real remote keys
reached Library → Manage → Files → Play. The decoder reported video/audio buffers,
and the recording visibly showed changing generated color frames. The native
video size was zero instead of 320×180, so the test stopped before its pixel,
seek, audio-switch, subtitle and return assertions. The final visible buffering
HUD is a startup frame; it does not independently prove a persistent HUD defect.
This was owned generated test media, not anime or Bloom Into You.

Two genuine recordings were retained (58.22s ordinary startup excerpt and 25.83s
owned-journey failure), and four unaltered frames were extracted for visual
review. Screenshot/fixture-manifest collection failed because ADB exec-out was
fed a doubly quoted script. The ZIP SHA-256 is
`13ae72f55225bde4149c680c9bc734bca2f032de0ee16df647cc23c6d15c2bdf`.
The tested x86_64 APK SHA-256 is
`764bb01bd07ccbab7ae2afca14efd38dc291a35b97f33a17c65d2b788576145c`;
test APK: `3b90141ca4b75c59dc7193424ab4a1478a047255ebaf69e1e5d561b85f1defd4`.

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

## Remaining acceptance limits

| Scope | Status |
| --- | --- |
| Current R24 native routes, focus, forms and playback | Implemented; local aggregate passes; current device rerun required |
| R23 ordinary automation | 150 passed / 28 failed / 5 intentionally isolated skips |
| Isolated library management, raw media and external-player handoff | Not run in the ordinary suite; require separate fresh-process fixtures |
| Metadata-assisted local scan, live AniList titles/covers and Bloom streaming | Blocked by the recorded HTTP 403; no proxy, retry workaround or synthetic catalog |
| Library, lists, manga, offline, downloads, providers, Nakama, settings and reports | Feature-level gates remain in the [layout/data matrix](2026-10-01-tv-layout-and-live-data.md) |
| Arbitrary browser-DOM plugin presentation and independent MAL APIs | Previously documented partial/missing capabilities remain |
| Physical TV/USB, codecs/GPU/HDR, accounts/providers, two Nakama peers and release signing | Unverified; unchanged external/device gates |

The [device inventory](2026-10-01-remote-ui-device-plan.json) contains 183 methods.
The earlier [run 36844301124](https://github.com/UnoxyRich/seanime/actions/runs/36844301124)
reached 71/181 methods before nine failures and a process crash; it is historical
and has not been promoted to acceptance of either current revision.

| Local R24 artifact | SHA-256 |
| --- | --- |
| ARM64 | `32944f8b4cc4fdf496bf3e364942c746c5141a39cfa0b1c0cad2823093826c4a` |
| x86_64 | `f37075ca3a178e2441ad57765357d1af5fc43c44c6a72a3478933b7f89ba6ade` |
| Instrumentation | `ce839da1778005792be9f4db2351f86919f7f61243111e917103c2e6f8e52df0` |

These local artifacts retain the existing debug certificate. GitHub records its
own exact built/installed pair; local hashes must not be substituted for runner
identities. Earlier delivered R21 artifacts remain unchanged.
