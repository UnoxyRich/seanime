# Remote UI review — October 1, 2026

R26 passes **383 JVM tests**, **34 unchanged-reference layout comparisons**,
lint with zero errors (87 warnings), both ABI builds and instrumentation packaging.
No R26 device pass is claimed. The latest
completed Android TV run has **170 ordinary passes, 8 failures and 5 intentional
isolated skips**, plus one passing and three incomplete isolated workflows.
The playback portion of the real remote Library journey now has verified video,
seek, audio-track, subtitle and picture-mode evidence. Full parity remains open.

## Actual R25 emulator evidence

[Run 36868848440](https://github.com/UnoxyRich/seanime/actions/runs/36868848440)
tested `30d9249e9c240230e5b3e2bbdec49cab00815f00` on Android TV API 36 x86_64.
The ordinary sanitized JUnit contains **183 unique class/method identities**:
170 passed, 8 failed, 0 errors, 5 skipped. There was no process crash. Seven R24
failures passed, six previously passing cases failed, and two Settings reentry
failures persisted. The console's extra five progress counts are not extra tests.
All five invocations installed app/test APKs matching the exact runner outputs.

| Real device workflow | Verified result | Remaining limit |
| --- | --- | --- |
| Ordinary navigation, forms, media and API automation | 170/183 pass; 71 actual screenshots retained | Eight failures listed below; five isolated methods skipped here |
| Remote Library → Manage → Files → Play | Real 320×240 decoded video, one-minute duration, pause, 10-second seek and changed compositor pixels | Later Explorer playback did not complete |
| Audio and subtitles | Second PCM track selected and decoded (`fr`); SRT cue pixels 7,835 on / 0 off | No audible speaker-quality claim; screen recordings have no audio |
| Picture Off → Mode C → Off | Real shader pixels, square marker geometry, 240-pixel black pillarboxes at 1920×1080, exact native Off dimensions, source/checkpoint/track/pause continuity | One emulator/GPU and owned SDR media; physical GPU/HDR acceptance remains |
| Player Back → Files | Original Play button regained actual focus | Explorer then opened an empty folder and could not reach Play |
| Independent signed Go raw-media playback | Passed: signed HTTP 206/256-byte range, outside-root rejection, decoded native frame, play/pause/seek, recovery validation and dismissal | Generated silent H.264; not live streaming or metadata-assisted scanning |
| Isolated library management | Real existing import API and exact two signed index rows verified | Test scrolled the rail instead of content before file selection; bulk rename/delete not reached |
| External-player handoff | Raw-media subflow passed | Native Playback options remained open; Android chooser/receiver/background lease not reached |

The owned journey retained ten screenshots and a typed manifest. Its strict
post-seek check required READY, a new rendered buffer, the real buffering badge
gone, and unchanged ±25 color tolerance. It passed at 11,562 ms; mean RGB changed
from `[166,69,41]` before seek to `[198,93,123]` after seek. Mode C and direct
rendering preserved the square's aspect and black side bars. This establishes
these subflows, not successful completion of the failed journey.

The eight ordinary failures are player lifecycle navigation; Discovery advanced
filters; rename preview/cancel; two list-entry dialog transitions; two Settings
page reentries; and torrent limits dialog transitions. The exact case identities
and sanitized source frames remain in the run artifact. The full ordinary score
is not inferred from host tests or screenshot presence.

The evidence ZIP SHA-256 is
`05ba14aebae673eadc8203bfbd531bc06953472076154020e4ba20c8b186906e`.
The tested runner x86_64 APK SHA-256 is
`39732155ce2235ac33bc850c30ff28e0484abfdecc36d6543286650a398e00f3`;
test APK: `bcbb023b83892969ebddea54af4dcf73e4e8e420e4fd3d52b32debeb18121bad`.

Four actual screenshots and a 47.02-second video excerpt were delivered for
visual review. The clip retains source frames from 5.9346–52.9655 seconds with
unchanged 1280×720 geometry; the original 113.10-second recording is retained.
It is a faithful H.264 excerpt, not a simulated UI. All media is generated owned
test content, not anime or Bloom Into You.

## R26 corrections and verification boundaries

| Finding | Correction | Evidence / limit |
| --- | --- | --- |
| Old lifecycle test assumes Left can escape the transport edge diagonally into Audio | Follow Rewind → Subtitles → Audio using real keys and exact focus checks | Intentional R25 transport behavior remains unchanged; 12 focused player tests and instrumentation compilation pass |
| Tests request semantic focus and send Select while the target dialog does not own Android input | Wait for that exact root's attachment, layout and window focus before one RequestFocus; observe restored openers and use D-pad/IME actions where needed | No action retries or relaxed payload/date/cancel assertions; four dialog mirrors pass; device rerun required |
| External test never activated its native menu option | Real D-pad option traversal, target-window observation, installed receiver resolution and actual menu dismissal checks | No player/receiver behavior change; actual chooser/receiver execution remains pending |
| Outgoing Settings focus callback can overwrite the saved row after Back changes the logical page | Ignore callbacks from a page that is no longer current | Same-turn controlled fallback reproduced Accounts replacing Update; the identical regression passes with the narrow guard and exact saved Y-position. Native cause confirmation remains pending |
| Management helper chooses the final scroll container in the whole shell | Restrict it to `native-content` | Host test demonstrates the old selector chooses the rail; unchanged management payload/index assertions remain |
| New internal library root may use an Android filesystem alias inconsistent with Explorer enumeration | Canonicalize only newly created app-owned defaults and owned fixture roots; keep both spellings recognized in the setup label | Filesystem regression passes; next device fixtures assert canonical state and signed Explorer membership. Existing saved choices/indexes are not rewritten |
| Failed management stops before its first screenshot checkpoint | Distinguish a verified no-screenshot receipt from a corrupt archive | Collector keeps failures authoritative; no screenshot is invented or counted as a pass |

The Settings fallback regression explicitly injects the real old row's focus
action during the Back UI turn to model platform fallback. It uses real rail,
row and Back navigation otherwise. It is evidence of the state defect, not a
claim that the emulator's unrecorded callback sequence has been observed.
Native before-return/failure screenshots and bounded safe focus/page/window
failure details remain to distinguish page entry from wrong restoration.

The Explorer alias analysis is grounded in unchanged server code:
[enumeration resolves symlinks](../../internal/library/filesystem/mediapath.go#L136),
while [tree membership uses a string-prefix comparison](../../internal/library_explorer/filetree.go#L150).
[AOSP creates the app-data alias](https://android.googlesource.com/platform/frameworks/base/+/3468cd94064c/core/jni/com_android_internal_os_Zygote.cpp).
The R25 recording proves an empty tree despite imported owned media; it does not
itself record the canonical-path comparison. R26 records bounded booleans and
exact owned file membership to test that explanation. Existing noncanonical
configurations are preserved and are not represented as automatically repaired.
No missing API is invented: the directory-children handler's current loading
method is a no-op because the full tree is built up front.

## Evidence collection and environment

The AGP keep-installed option worked in R25: app-private screenshots and all four
owned manifests were read after Gradle, with exact app/test identity checks.
The collector exports only named fixture screenshots, sanitized JUnit and typed
bounded owned observations. Raw logs, credentials, app databases and unrelated
cache files are excluded. A missing screenshot checkpoint never makes a failing
test green. Each owned method runs in a distinct fresh disposable AVD.

The ordinary recording is bounded to 60 seconds at 2 Mbps; the full owned journey
gets up to 120 seconds at 1 Mbps. Both are 1280×720, silent, capped at 32 MiB and
labeled excerpts. They are not automatically complete-flow recordings.

Local validation reuses Go's documented [module-cache proxy](https://go.dev/ref/mod#module-cache)
without network fallback or checksum-policy changes. An earlier 14-second R25
attempt stopped before app compilation when `gomobile init` queried the ordinary
registry and received 403. The verified cache retry succeeded. The existing
gomobile module is `v0.0.0-20260602190626-68735029466e`; gobind is
`v0.0.0-20260908204917-8b95e45f8d3e`, with preserved SHA-256
`288d97e96388a568180fe81591717299028528a4c2db0263ee7b6bd0d3e2fe4b`.
This process-local build configuration changes no app or server networking.

## Remaining acceptance gates

| Scope | Status |
| --- | --- |
| Current R26 local aggregate | Passed:383 JVM,34 visual,48 collector regressions; APK signatures/alignment/API boundaries verified |
| R26 actual TV automation | Unrun; R25 results above do not establish the new revision |
| Library Explorer, management and external handoff | Specific repairs ready; complete isolated flows require rerun |
| Settings reentry and other failed ordinary cases | Corrections/diagnostics retain exact assertions; require native confirmation |
| Live AniList metadata/covers, metadata-assisted scan and Bloom streaming | Recorded HTTP 403 still blocks these; no proxy, alternate route or synthetic catalog |
| Browser-DOM-only plugin presentation and independent MAL capabilities | Previously documented partial/missing API capabilities remain |
| Physical TV/USB, device codecs/GPU/HDR, API 23, ARM64/16 KiB runtime, accounts/providers, two Nakama peers and release signing | Unverified; source/host/emulator evidence cannot replace them |

Feature-level gates remain in the [layout and live-data matrix](2026-10-01-tv-layout-and-live-data.md).
The [device inventory](2026-10-01-remote-ui-device-plan.json) contains 183 methods.
Historical [R24](https://github.com/UnoxyRich/seanime/actions/runs/36859935391)
recorded 169 passes / 9 failures / 5 isolated skips; its owned journey stopped
at post-seek pixel readiness. Historical
[R23](https://github.com/UnoxyRich/seanime/actions/runs/36850609039) recorded
150 passes / 28 failures / 5 skips. Older incomplete/crashed runs and delivered
R21 artifacts remain historical evidence, not current passes.

## Frozen local R26 artifacts

The aggregate completed in 199 seconds. All 260 Android source hashes and all 34
reference images remained unchanged. Source digest:
`071687791f5044a4d615116c37d6738555d663cfb08d7173df6f848f73d4dbf2`.
All APKs retain the existing debug certificate and pass signature/ZIP alignment
checks; both main APKs contain seven 16 KiB-aligned native libraries. All 145
literal API contracts pass; Go core, modules and schema remain unchanged from
`63200d6a`. All bundled Anime4K shader bytes match the prior verified assets.

| Local artifact | SHA-256 |
| --- | --- |
| ARM64 | `61483cea8699284d637e46ea8113f583ba226bfd472ae5ef9d167a5be149075d` |
| x86_64 | `e13a091e1a6c7a694889580c89ecadfe5bae0031b4efe1d827240000fe85b5e3` |
| Instrumentation | `b3323903acfda8677eac30a3f53739d5430d63652030cd448f39471b6ff47d1b` |

These are locally built debug artifacts. The next CI run must record its own
built/installed identities; local hashes are not substituted for runner hashes.
