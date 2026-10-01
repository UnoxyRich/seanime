# Remote UI review — October 1, 2026

The latest completed device run, R30 source `62c0187c`, has **174 ordinary device passes,
four failures and five explicit isolated skips** across 183 unique cases.
The complete owned-media journey and independent signed Go media route pass;
isolated filename editing and external-player handoff still fail. The Mac's
installed R27 ARM64 build loads real AniList anime/manga covers and Bloom Into
You metadata/chapter lists, but live AnimeHeaven playback and Atsumaru page
pixels fail. R30 has not yet been installed on the Mac. Full parity and public
release remain open.

R30 includes the R28 custom-artwork correction, R29 harness changes and bounded
diagnostic evidence. Its local aggregate passes **441 JVM tests, 34 unchanged
reference comparisons, 52 collector tests**, lint and both ABI builds. Exact
per-source and per-APK results below must not be transferred to newer builds.

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

## R26 actual device result

[Run 36879535950](https://github.com/UnoxyRich/seanime/actions/runs/36879535950)
tested published source `fe003631a704efe01532b002425f8c19e3802da5`, whose tree
equals local reviewed commit `53aff6f1`. It completed with failure at 15:24 UTC.
The ordinary suite **did not run**: the emulator action's unlock command
(`adb shell input keyevent 82`) exited 255 after the first boot-complete read,
before the instrumentation script started. There is no new 183-case score;
R25's 170 passes / 8 failures / 5 skips remain historical results only.

All four isolated cases ran once without skips, with exact built/installed APK
identity verified. Two passed and two failed:

| Isolated flow | Verified result | Remaining limit |
| --- | --- | --- |
| Library → Files → native player → Explorer → native player | **Passed completely**: real D-pad navigation, signed Go ranges, decoded frame/audio, 10-second seek, visible embedded subtitle on/off, Mode C/direct rendering round-trip, same-file focus restored from both routes | Owned generated 320×240 multitrack media, not live anime or representative hardware decoding |
| Independent raw Go media route | **Passed** signed 206/range/boundary checks, native playback, pause/seek and recovery | Generated owned silent media |
| Library management | Import, two-file selection, bulk ignore and signed readback passed | Filename editor remained open with Save focused after an IME transition; timeout before parent preview/rename/delete completion |
| External player | Raw media subflow passed | Implicit HTTP receiver query returned no matching test player; guard failed before native More/chooser navigation |

The journey's canonical root and exact Explorer index membership checks passed;
the fixture observed Android's app-files alias. This verifies the R26 canonical
default/fixture correction, not a rewrite of existing user-selected roots.
Post-seek readiness, decoded-buffer and unobstructed pixel checks passed at
11,996 ms. Subtitle pixels were 7,835 enabled and zero disabled. Second audio
track `fr` decoded 15 buffers. Mode C/direct aspect checks retained 4:3 content
with 240-pixel side bars in the 1920×1080 viewport. Twelve actual journey PNGs,
the bounded silent recording and a typed manifest were retained.

A source review found that Android's `parseQueries` supplies the content
scheme for MIME-only visibility queries, so R27 adds explicit HTTP/HTTPS
queries alongside content queries. This is not a proven explanation of the
isolated failure: R27 still fails that assertion, and AOSP separately makes an
instrumentation package visible to its target. Actual package-state diagnostics
remain necessary. The HTTP-only test receiver keeps its original filter. The management recording narrows its failure to the filename editor's
completion boundary; it does not demonstrate failed restoration after a
successful save.

Artifact `11172821332` SHA-256:
`1b9e065e77458bf95794a6a52c6ac180c480c6d2759bc6a580abb239b6ee0882`.
The tested runner x86_64 APK SHA-256 is
`0a232a799cde34821dc893f772f006c5c5675c8f1ce1d2670d06f0e97d585918`;
test APK: `412c899b0136442551a2e77bba2a50340cf992212efe60c2478bb44e4ded9f0c`.
These identities differ from the locally built R26 artifacts below.

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
| Current local R30 aggregate | Passed:441 JVM,34 visual,52 collector tests; APK signatures/alignment/API boundaries verified |
| Published R30 TV automation |174 ordinary passes,4 failures,5 isolated skips; two isolated passes and two isolated failures |
| Library Explorer / native owned playback | Complete generated-media D-pad journey, seek, decoded audio, subtitle pixels, picture round-trip and same-file return focus pass |
| Library management and external handoff | Partial subflows pass; filename-editor physical footer transition and real standalone player acceptance remain open |
| Settings reentry and previous ordinary failures | All ordinary methods pass at R27; this does not validate later source |
| Live AniList metadata and covers | R27 ARM64 native Discover and Bloom details work on the authorized Mac TV emulator; prior cloud403 remains historical environment evidence |
| Live Bloom streaming / English manga | AnimeHeaven source resolves but native playback has a transport error; AniDB subbed episode1 has no result; manga reader acceptance pending |
| Browser-DOM-only plugin presentation and independent MAL capabilities | Previously documented partial/missing API capabilities remain |
| Physical TV/USB, device codecs/GPU/HDR, API23,16KiB runtime, accounts/providers, two Nakama peers and release signing | Unverified; source/host/emulator evidence cannot replace them |

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

These are locally built debug artifacts. R26 CI recorded its own built/installed
identities above; local hashes are not substituted for runner hashes.

## R27 bundled-provider test candidate

The next candidate contains the three pinned English providers and the Android
origin/transport boundaries described in the
[provider report](2026-10-01-bundled-provider-boundary.md). It also corrects
network-only player discovery and replaces synthetic rename-footer focus with
measured IME closure plus physical Down/Right/Select navigation.

Validation passed on 295 unchanged Android source/asset files: **420 JVM tests,
34 unchanged visual comparisons, 48 collector tests, zero lint errors and 87
warnings**, both main APKs plus instrumentation, signatures, ZIP/native 16 KiB
alignment, exact provider/shader bytes, 145 literal API contracts and the
unchanged-Go baseline. Source digest:
`3f349024f430b67020d8a1225440df88eb63d9e842831fb1cc55627b0876aae6`.

The original combined process completed tests, lint and APK assembly, then its
daemon disappeared during visual validation. The same visual task passed in a
fresh 2 GiB daemon in 31 seconds; source, reference images and all three APK
hashes remained unchanged. This recovery is preserved separately rather than
described as one uninterrupted green build. Two earlier setup failures are
retained: an unsupported public-SDK constant and an unavailable Robolectric
API28 test fixture; both were corrected before the successful checks.

| Local R27 artifact | SHA-256 |
| --- | --- |
| ARM64 | `53696f06c9cfa54d8acc04c350e6873eaf4b38f9004e7874ea8fd48a700d2cbd` |
| x86_64 | `4afd39cec79b04b81745a74c53b3c2639a2af64bd39140d3a68e00419e9b3063` |
| Instrumentation | `03baba7e740a884c16aca9945e97b6b35106ed1ae63c0bd3919c5ccf0726a5b0` |

This is a physical-computer test candidate, not a release-final build. Its
subsequent exact-source device and live-provider results are recorded below. Custom-source catalog artwork
is guarded, but propagation into shared detail/library artwork still needs a
narrow follow-up; none of the three bundled providers is a custom-source
provider. R26's device results cannot be transferred to these new bytes.

## R28 custom artwork candidate

The [custom artwork correction](2026-10-01-custom-artwork-origin.md) closes the
R27 propagation gap across shared detail, metadata, related/list cards,
playlist thumbnails and episode fallback images. Explicit Offline/downloaded
contexts preserve own-title cached covers online and offline through a
credential-free, redirect-disabled static asset request. Direct provider URLs
and forged API/local paths retain provider restrictions. Go remains unchanged.

The frozen aggregate passed uninterrupted in **233 seconds** using a 2 GiB
Gradle daemon: **430 JVM tests, 34 unchanged visual comparisons, zero lint
errors and 88 warnings**, both main APKs plus instrumentation, signatures,
ZIP/native 16 KiB alignment and all 17 source/APK contracts. The extra lint
warning reports a newer Compose test dependency; dependencies were not changed.
All 297 Android source/asset hashes and all reference PNGs stayed unchanged.
Source digest:
`0960019685dacfcd36e9f60c60e957211352b2e6e503320b82b0f4a034933bba`.
The unchanged collector retains its 48-test R27 result.

| Local R28 artifact | SHA-256 |
| --- | --- |
| ARM64 | `2f1fac14373db23aebe37012c6849f49a1479ae3ae9a1900000778c27572e3ae` |
| x86_64 | `fa9bbe71e5a85ea2bbacf63b525042aa91c6024efb54c48bcb6bbbee2572460d` |
| Instrumentation | `02b8f285ede14ac961c992f632704e9e00deb489a1c51532c316330cb6c714f9` |

R28 device execution is pending. R27 CI and the Mac's installed R27 pair have
their own immutable identities; their results must not be relabeled as R28
passes. A public release remains held while required live/device flows are open.

## R27 terminal Android TV result and live Mac check

[Run36893231348](https://github.com/UnoxyRich/seanime/actions/runs/36893231348)
tested published commit `8f2b780025cbc334b24a00c1756683e1ab133e0f`, with the
same Git tree as local `2b0683dc`. Sanitized JUnit has **183 unique ordinary
class/method identities:178 passed,0 failed,0 errors,5 skipped**. All five
invocations have verified runner-built/installed APK identity. The four isolated
cases contain two passes and two failures, with no skips:

| Isolated flow | Exact result |
| --- | --- |
| Library→Files→player→Explorer→player | Passed the complete owned-media journey, including real decoded pixels, audio-track selection, subtitle on/off, picture-mode round-trip and restored file focus |
| Independent signed Go media route | Passed ranges, boundary rejection, native playback, pause/seek and recovery |
| Library management | Failed the first physical Down→Cancel focus assertion after filename editing; preview/save/rename/delete completion remains unverified |
| External player | Failed the installed implicit HTTP receiver query before More/chooser; external UID streaming and paused return were not reached |

The artifact includes79 ordinary screenshots,12 journey screenshots and one
screenshot from each other isolated invocation. Its SHA-256 is
`34db43b72ede702a31a422bb6b2924ab28c7a629bd39cd0175f2ad0f7f93218e`
(artifact11180033029). Runner main APK SHA-256:
`f7e280a78384d8a9437613ed1d19cdfba5b1f27bfbb2d255da3eb782360dfd49`;
runner test APK:
`ad3349e0807d423c7e048eedc630218542a5be4c10042b856e1dd7d52ea45451`.
These differ from the local R27 pair installed on the Mac.

On the authorized Mac's isolated official API36 ARM64 Android TV emulator,
the exact local R27 pair listed above was installed after byte verification.
Real native Discover anime/manga covers load. Searching Bloom Into You returns
Yagate Kimi ni Naru with its real cover, synopsis and episode1. AniDB's subbed
episode1 selection has no result. AnimeHeaven resolves an auto source, but
native playback reports that the source connection was interrupted; one retry
remains black. Back twice recovers the detail screen. A screen-recording file is
not proof that an anime frame decoded; no live playback success is claimed.
The existing Media3 exception chain is being collected before changing transport.
Atsumaru returns 108 raw chapters (61 after deduplication) and 49 pages for chapter 1 through the existing HTTP200 pages API, but the first two page images fail to load. No real manga page pixels are verified. This Mac result supersedes a blanket claim
that all live AniList access is blocked; it does not erase the earlier cloud403.

The next harness correction is intentionally distinct from a production fix:
Compose's pinned TV editor handler ignores virtual-keyboard Center events, so
filename testing must physically activate an enumerated nonvirtual D-pad,
observe the IME shown, invoke Done, then observe it gone before retaining the
original single Down→Cancel→Right→Save assertions. Separately the test-only
external receiver references Kotlin/AndroidX classes omitted from its standalone
APK; a framework/Java-only receiver preserves the same implicit intent filter,
separate UID, anonymous range reads and paused return. That dependency finding
does not itself explain the earlier resolver failure. No production workaround
or relaxed resolver assertion is justified without runtime evidence.

## R29 harness validation checkpoint

R29 preserves the exact R28 main APK bytes and changes only instrumentation and
acceptance evidence. The receiver's three compiled classes reference only Java,
Android framework and their own classes; the packaged test manifest is identical
to R27, including its HTTP-only filter, exported activity and separate process.
The filename test now establishes a real shown-to-hidden IME transition before
its unchanged physical footer assertions. These are validated harness changes,
not a claim that either previously failing device flow now passes.

The corrected combined aggregate passed in 80 seconds: **430 JVM tests, 34
unchanged visual comparisons, lint with zero errors and 88 warnings**, both main
APKs and instrumentation, signatures, alignment, exact provider/shader assets and
all 17 source/APK boundary checks. All 298 source/asset files, now explicitly
including Java, remained unchanged during the run. Source digest:
`ac737187514630062815268e5f760712b26f2719fdb30856efca58c7b8e711cb`.
Test APK SHA-256:
`e7571b5d41556c31b6d7e7cfa7131d8c3e830f3033cbdf117fe9e70cc66aaf52`.
The first 87-second attempt passed all 430 host tests then failed lint on the
legacy pre-33 receiver overload. A narrowly scoped documented annotation on that
legacy helper preserves explicit exported registration on API33+; no lint
baseline, AndroidX dependency, runtime security change or old evidence was removed.

Mac shell package queries resolve the installed R27 test player, but shell UID
visibility does not prove what the app UID sees. The original app query remains
asserted, with bounded failure-only package facts in R29. Being stopped alone is
not sufficient to explain it: the fresh VIEW intent does not exclude stopped
packages. Device execution of R29 is pending.

The exact R27 APK also confirms why the live transport error is still
undetermined: Media3's `Log.getThrowableString` replaces any cause-chain
`UnknownHostException` with the literal `UnknownHostException (no network)`.
That text is not the original platform or provider-policy exception message.
Narrow debug-only failure categories are the next diagnostic change; no proxy,
DNS override, repeated source request or guard relaxation follows from this log.

## R30 safe live-failure diagnostics

R30 adds failure-only `SeanimeNetworkFailure` diagnostics for the native player's
error callback and manga reader's existing image-error callback. Debuggable
builds emit a fixed surface/category, at most eight restricted class identifiers,
cycle/truncation flags and optional numeric player code, HTTP status, operation
and public errno. Nondebuggable builds emit nothing. URLs, origins, headers,
request/response bodies, exception messages and stack traces are never emitted.
Even throwing exception introspection falls back to a fixed diagnostic-unavailable
category rather than crashing the actual failure handler.

Provider DNS/proxy/redirect/URL policy categories require the known fixed message
and the policy's throwing-frame provenance; platform DNS remains distinguishable.
The implementation changes neither transport nor guard decisions, does not retry
requests and does not change Go. Eleven focused tests cover redaction, identical
platform/policy message text, HTTP metadata, errno, cycles/depth, unknown causes,
release suppression and throwing introspection.

The external-player resolver now saves fixed typed failure facts in its owned
manifest. The collector accepts exact bounded numbers, enums and booleans only,
including a seven-boolean receiver state or fixed unavailable value. Unexpected
keys, nested fields, URLs/headers, arbitrary exception text and type confusion
are rejected. The 52-test collector suite passes; sanitized JUnit continues to
omit assertion text and raw logs. The resolver assertion and original fixture
stage vocabulary are unchanged.

The aggregate passed uninterrupted in **216 seconds**: **441 JVM tests, 34
unchanged visual comparisons, lint with zero errors and 88 warnings**, both main
APKs and instrumentation, signature/ZIP/native 16KiB alignment, unchanged bundled
provider/shader bytes and all 17 source/APK boundaries. All 300 Android source
and asset hashes, both collector script hashes and all 34 reference images
remained unchanged. Source digest:
`ecbfeb715bbf38d6cf2cf10109a95cee3154940f282cdfbd4ae3899499ca00ba`.

| Local R30 artifact | SHA-256 |
| --- | --- |
| ARM64 | `445dc2bd84cb51e98b3f44cd7e53b3971cf128c1a844b41d65d5373e20f3dc3b` |
| x86_64 | `7c6cf6f28bcf3814f9aca9bbaea8a273391ed71662a41f495a3317a0062de10b` |
| Instrumentation | `d83b1ea2f7ff3b7468772e1e8d432dd8739ef2229340a213765f5703bb80ca23` |

These are a new diagnostic candidate, not a live playback or manga fix. R27's
actual device passes and failures remain tied to its exact APKs; R30 device and
live-provider results are pending. Public release remains held.

## R30 terminal device evidence

[Run 36903247274](https://github.com/UnoxyRich/seanime/actions/runs/36903247274)
completed with failure on published `62c0187cc9f072a7748d60272912d46d8b90c17d`,
whose tree equals local `22cf28df`. The sanitized XML contains **183 unique
ordinary cases: 174 passed, 4 failed, 0 errors and 5 skipped**. Console counters
that finish at 188 are not additional cases. All five invocations verify that
the installed app/test APKs match their corresponding runner-built artifacts.

| Ordinary failure | Evidence and limit |
| --- | --- |
| Player recreation | `NativePlayerLifecycleTest` sees an unexpected-runtime `ExoPlaybackException` while awaiting READY. The retained log/recording does not identify the lifecycle stage or nested cause; no runtime cause is invented |
| Filename editing | The helper fails before activation because the TV emulator has no enumerated nonvirtual D-pad. This invalidates the R29 harness assumption; it does not establish a new production footer defect |
| Personal manga collection | Timeout waiting for search-opener focus after cancelling its second editor; dialog dismissal and IME handoff were not separately established |
| Torrent details | Timeout waiting for Add tracker focus after cancelling its editor; prior priority retry/cancel succeeded |

The full Library→Files→player→Explorer→player journey and independent raw Go
media flow both pass once without skips. Isolated management fails at the same
missing-device precondition after verified import/bulk-ignore/signed readback.
The external case fails its original unscoped receiver query before chooser
launch; scoped wildcard and concrete-video queries find the enabled/exported
receiver under a different UID. It is stopped, but the intent does not exclude
stopped packages. These typed diagnostics survived strict artifact sanitization.
The known separate-process receiver dependency defect is corrected in the APK;
it is not an explanation of the remaining query result.

Artifact 11186200194 has SHA-256
`da3fd43e2397fcf8cc0b6b0e397ab3feadcdce7ad8a715d597468ed7597d3a47`.
It retains 76 ordinary screenshots, 12 journey screenshots, and one from each
other isolated flow. Generated-media screenshots/recordings are not live anime
or manga evidence. Neither failure nor a missing capture is counted as a pass.

R31's pending test corrections use a temporary CTS-style uinput D-pad before
Activity creation to exercise Compose's real input path. Instrumentation key
injection rewrites device IDs to the virtual keyboard, so borrowing a device ID
cannot establish the intended TV Select behavior. Temporary input is removed
after teardown; this is emulated remote coverage, not physical hardware proof.
The original footer and backend assertions remain. The personal/torrent tests
will establish actual editor dismissal before opener restoration.

The external fixture needs the normal BROWSABLE category on its existing HTTP,
video-only filter. Android 16's domain resolver applies to these VIEW intents
even with video MIME; package-scoped queries bypass that post-filter. Generic
web-capable handlers are classified through BROWSABLE. This source-backed
fixture correction keeps unscoped MATCH_DEFAULT_ONLY and the real chooser,
without broader permissions or an explicit test-component target. The observed
candidate's identity was intentionally not captured; its exact domain status is
an inference from the source and the scoped/unscoped differential. Runtime
confirmation remains required.

Primary platform sources: [domain intent classification](https://android.googlesource.com/platform/frameworks/base/+/android16-release/services/core/java/com/android/server/pm/verify/domain/DomainVerificationUtils.java),
[domain preference filtering](https://android.googlesource.com/platform/frameworks/base/+/android16-release/services/core/java/com/android/server/pm/CrossProfileIntentResolverEngine.java),
[generic handler classification](https://android.googlesource.com/platform/frameworks/base/+/android16-release/core/java/android/content/IntentFilter.java),
[input dispatch](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/inputflinger/dispatcher/InputDispatcher.cpp)
and [CTS uinput protocol](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/uinput/README.md).

R30's Mac update is blocked before byte transfer: the supported Library helper
cannot reach its service, and Chrome blocks the GitHub artifact host. Neither
route is bypassed. An independent authorized local source build uses its own
signature and APK identity in a separate AVD, preserving the original R27 data.
A live diagnostic result remains pending; no transport or provider guard change
is justified by the current generic error.

## R31 harness candidate validation

R31 changes test and evidence handling only. It adds the temporary uinput remote
for IME activation, explicit editor-dismissal observation, BROWSABLE on the owned
network-player fixture, and bounded lifecycle stage/cause evidence. Production
Kotlin, provider payloads, transport policy, Go/API/schema and native media code
remain unchanged from R30. Both locally built main APKs are **byte-identical to
R30**, including their signatures and verified provider/shader assets.

The lifecycle test keeps its original first-READY transition timing and immediate
error assertion, while requiring the exact expected media. An initial validation
variant added a 250ms dwell; review removed it because waiting could avoid the
unexplained transition race. That first passed aggregate is retained separately.
No sleeps or retries were added to make this failure disappear, and no runtime
repair is inferred from the new diagnostics.

The final aggregate completed in **60 seconds**, reusing unchanged host/build
inputs as Gradle reports them up to date: **441 host tests, 34 unchanged visual
comparisons, lint with zero errors and 88 warnings**, main and test packaging,
signatures, ZIP/native16KiB alignment and all 17 source/APK boundaries pass.
All 301 Android source/asset hashes and the captured workflow/collector hashes
remained unchanged. Source digest:
`b9662032063b07270572299c2965e532d96939f828fdfc5dbab8cb3e84d00e3b`.
The collector's **58 tests** pass, including stale/future timestamps, strict
stage/summary/frame grammar, malicious values/types, duplicate keys, size limits
and symlink rejection. The fixed diagnostics path is read only for the ordinary
suite; absence never turns a failing test green.

The final instrumentation APK SHA-256 is
`56409540117a74beedb7deb974bbb9d93238bd08d00bc4adc39a8bcd50f0e5c5`
(2,080,326 bytes). Its packaged receiver declaration preserves VIEW,
DEFAULT+BROWSABLE, HTTP/video-only matching, exported separate process and the
same merged INTERNET/REORDER_TASKS permissions. Genuine chooser and lifecycle
results require the new exact-commit device run.

CI now retains the matching instrumentation APK and all three APK checksums in
one installable artifact. Failed ordinary-suite evidence is uploaded early with
a one-day retention period, using only the existing sanitized ZIP/status files;
the final full evidence artifact remains. No raw log, credential, signing key or
broader workflow permission is added. Public release remains held.
