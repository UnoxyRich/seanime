# Native TV layout and live-data correction — October 1, 2026

This records the correction following feedback that the previous UI was not
usable enough on TV. It supersedes the presentation claims in the earlier
[review handoff](2026-10-01-native-tv-readiness.md), whose APKs are historical.
The revised app is not yet accepted on an Android TV device. The Go core,
API contracts and database remain unchanged. The later
[source-packaging correction](2026-10-01-source-packaging.md) records the
build-only change used for source publication; the R21 APKs below are retained.

The current immutable APK source is
`43c2f02ea9ceb7b5b856f57fee8a00380fc1438b`, exported in
`toolchain/runtime-tv-host-focus-r21` in the build workspace. Its full aggregate
passed in **3m26s**: **366 JVM tests**, lint, both ABI APKs, instrumentation APK
and **34 screenshot comparisons**. No tests were skipped. Lint has zero errors
and 82 warnings. All 253 source files match the committed candidate; screenshot
reference bytes were not updated. Device execution is a separate gate.

| Current APK | SHA-256 |
| --- | --- |
| ARM64 | `a0da5319e157f2e26b1637e51db68a94a16b33d829cc8054a4351ed34db1a19d` |
| x86_64 | `2912aeac66df240f0b4620fa23e93dc2cec0c6ae3d023ae041a1f0605f791f0f` |
| Instrumentation | `eeadac9dba09772199e7c0e8b1ceb551a4f93ed5a200c0653c8c7180e7b983e0` |

V1/V2 signatures use the existing task debug certificate. Seven native
libraries per ABI pass 16 KiB ELF/ZIP alignment; all fourteen library bytes
match R12. All 145 existing API method/path contracts and the unchanged-Go
guard pass. These checks do not establish physical 16 KiB runtime or device
playback.

## Presentation and navigation

- Navigation occupies a 64 dp icon rail while browsing and opens a 216 dp
  overlay on focus. Opening it does not resize the poster grid or move content.
- Library and Discovery use compact controls and a separate remote-friendly
  search dialog. Poster sizes are bounded by the actual remaining viewport;
  titles retain two readable lines. Settings uses full-width labelled rows.
- The shell owns the overscan inset. Nested feature screens no longer add a
  second large margin. Focused cards do not scale beyond their grid bounds.
- Back and Left/Right preserve the exact content opener. Library management,
  Manga and Auto-downloader keep their query, selected title/tab and return
  focus. Async responses must not steal focus after the user returns to the rail.
- Long confirmation and permission descriptions can be scrolled with a remote.
  Temporarily disabled loading controls remain visibly focused; the disabled
  focus border follows the same row shape as the normal control.

These are newly written native Compose components, not transplanted React or
old Android layouts. Host screenshots render the actual production components.
The static screenshot fixtures contain original geometric artwork solely for
layout inspection; those pictures are not evidence of real anime data.

## Real anime data and marketplace default

Production `MainActivity` creates the ordinary `SeanimeApiClient` connected to
the local Go server. The app does not install a fake catalog, synthetic titles,
MockWebServer, or screenshot fixtures into its production source sets.
Discovery requests the same `/api/v1/anilist/list-anime` route as the desktop UI;
the library uses `/library/collection` and details use `/library/anime-entry/:id`.

At 05:56 UTC, the unchanged Go `ListAnimeM` query was executed with search
`Bloom Into You`, page 1, 40 results, and `SEARCH_MATCH`. It made exactly one
POST to `https://graphql.anilist.co` using the existing HTTP client/transport,
without account credentials. Proxy environment variables were present; that
observation alone does not independently establish the actual transport hops.
No user-provided proxy was used. The response was **HTTP 403 after 5.82 seconds**,
containing Cloudflare's “Sorry, you have been blocked” HTML. No titles or covers
were returned. The existing Go JSON decoder produced
`failed to decode response: invalid character '<' looking for beginning of value`.
The normal handler turns this generic error into HTTP 500 and the native client
shows a retryable failure. That propagation was traced in source, not observed
on the emulator. No alternate route or proxy credential was used, and no retry
was made. Live anime screenshots and Bloom Into You playback are therefore
still blocked; loopback fixtures do not satisfy either request.

The saved Cloudflare Ray ID is `a4393b40180c0a82`. A separate read-only review of
AniList's [request format](https://docs.anilist.co/guide/graphql/) and
[authentication guide](https://docs.anilist.co/guide/auth/) confirms that the
existing POST/JSON request is correct and public anime data needs no login.
The [rate-limit guide](https://docs.anilist.co/guide/rate-limiting) describes
HTTP 429 with retry headers, not this 403 HTML response. No documented retry
time or supported configuration repair was established; no additional API
request was made during that review.

The default marketplace source, when no Android preference exists, is
[Bas1874/Seanime-Marketplace](https://github.com/Bas1874/Seanime-Marketplace),
verified at 05:31–05:33 UTC as the highest-starred compatible candidate (95 stars;
next candidate 65). The app requests its
[hosted catalog](https://raw.githubusercontent.com/Bas1874/Seanime-Marketplace/refs/heads/main/Marketplace/Main.json)
through the existing marketplace API. It preserves an explicitly saved custom
or empty source and does not automatically install extensions. Catalog data is
not vendored or redistributed. Community popularity is not a safety or working
guarantee; some catalog entries are marked broken or deprecated.

## Thumbnail evidence

`NativeArtwork` is used for anime and manga cards, discovery/custom-source
results, episode and playlist rows, tracked offline titles, manga mappings and
reader covers. Provider image headers retain origin scoping; server auth never
follows a cross-origin redirect. Header-sensitive cache identities prevent
recycled cards from showing a different authenticated image at the same URL.

The shared loader uses a bounded in-memory cache. It does not promise a
persistent remote-image disk cache. Downloaded `{{LOCAL_ASSETS}}` covers use the
existing local offline-assets endpoint and can remain available without the
external service. Missing, invalid, loading and failed images have fixed-size,
nonfocusable fallback content.

Twelve host tests make real loopback HTTP requests, decode PNGs with Coil and
inspect rendered pixels in production components. Ten additional URL/transport
tests verify resolution, supported schemes, authorization and redirects. These
22 tests pass on R11; they are fixture evidence, not a live AniList/CDN result.

## Exact verification scopes

| Evidence | Result | Limits |
| --- | --- | --- |
| Static host layouts, 05:12 source manifest `e89e9dc09b0cb12b18e59f382d6d6dc6f91bf4c11f5bf88765943db74f879b9c` | 34 updates and 34 comparisons pass; 32 production layouts and 2 pipeline smoke cases | Layoutlib does not acquire real focus. No focus ring is simulated. Later source changes require their own checks |
| R11 source manifest `04df8a31cff151c8ab881608cf14f85f94866d73038f804376ea3a7eba808b85` | 42 tests pass, zero failures/errors: 12 settled navigation, 4 pending-response focus, 4 marketplace preferences/API, 12 image/pixel and 10 URL/transport | Real Compose key delivery and host Android drawing; no real Android TV system, physical IME, media engine or account/provider acceptance |
| R12 / commit `c5fd0d171f7be7a37154a1887f9e256f96230303`, source manifest `a8c9d169af9ec907526f40135195e6667c57aec370a05c1bc6c271e4ee4b4972` | 347 JVM tests across 61 classes pass, zero failures/errors/skips; lint and ARM64/x86_64/test APK aggregate passes in 5m25s; signatures, same certificate, both ABI native alignment and source-hash readback pass | Immutable export `toolchain/runtime-tv-host-focus-r12`; no device result. Subsequent metadata/response recovery changes are not covered by this artifact |
| R13 subsequent metadata/response source | 64 of 66 focused tests pass, including 7 metadata and 5 response-validator cases | Two new Discovery test setups stopped before target behavior because they sent Left from header Back instead of Down into the toolbar; corrected key sequences require a rerun |
| R16 metadata / response / recovery source | All 67 focused tests pass, including full synopsis, both horizontal links, characters, related-title return and full focused bounds; all 9 delayed/error recovery cases pass | Host runtime with fixture data; no live AniList or Android TV result |
| R17 aggregate | 364 of 365 JVM tests pass; playlist image screen fails before image rendering | Lint/APK/static screenshot tasks were not reached. R18 reproduces an initial Compose list-index exception; R19 proves a feature coroutine resumes after HTTP on an OkHttp thread under the host's unconfined context |
| R20 after explicit Main dispatch | All 80 focused cases pass, including all 13 artwork/action-thread tests plus the 67 metadata/navigation/data cases | R21 below supplies the aggregate result. The correction preserves scope cancellation and executes feature actions on Android Main; it does not establish a cause for historical device ANRs |
| R21 / commit `43c2f02e`, source manifest `78773b3eacace25a4a4396819a8535e1fa0be2da91875cc529cfbfc6aadd8bc4` | All 366 JVM tests across 64 classes, lint, both ABI apps/test APK and 34 screenshot validations pass; signatures/alignment/contracts and immutable export verified | Current source/build evidence only. The preserved-AVD attempt below stopped before installation |
| Live unchanged Go AniList query, 05:56 | One request, HTTP 403; no catalog or cover data | Blocked external service. No Bloom playback claim |
| Current revised APK runtime | Uninstalled and unrun | The fresh framework gate failed before installation; no current app startup, screenshot or instrumentation pass |

R12 APK SHA-256 values are ARM64
`24ca0278fcee20ae3fa846096a2b6e0aa1d3e0dd5c798ac311d594d803277946`,
x86_64 `963d395b8bd846289ca788e868de5eb2873ee547966c54881ec0bac5e02129ca`,
and instrumentation
`eeadac9dba09772199e7c0e8b1ceb551a4f93ed5a200c0653c8c7180e7b983e0`.
The standard native bind was rerun. `libgojni.so` differs only in validated
Go/GNU build IDs and generated temporary-directory paths; executable/data
sections match. Historical Go runtime evidence is still not promoted. The
other retained native engine/shader bytes remain identical.

Robolectric uses SDK 34 at 960 × 540 dp with a television/D-pad configuration,
including a 130% font Settings case. Navigation tests dispatch arrows, Center and
Back, then inspect actual focus and full bounds. The thumbnail-only route setup
uses semantic activation where necessary; those image tests are not reported as
D-pad navigation tests. IME Search callback tests do not prove physical TV IME
window positioning or key handling.

Reproducible static screenshot commands and limitations are in
[`src/screenshotTest/README.md`](../app/src/screenshotTest/README.md).
The host test classes are `NativeTvHostFocusTest`,
`NativeTvSecondaryRouteFocusTest`, `NativeTvLoadingFocusTest`,
`NativeMarketplaceDefaultsTest`, and `NativeArtworkHostTest`. Normal Gradle
`testDebugUnitTest` runs these alongside the existing suite. Host SDK artifacts
may be provisioned with Robolectric's documented local dependency directory
when an executor's default home is read-only; no system home or security
settings need to change.

## Remaining acceptance

### Final bounded emulator attempt

One ordinary saved-AVD attempt began at 06:52:32 UTC. Official API 36 Android TV
x86_64 used software emulation with the retained data, keys and caches. No
replacement VM, data reset, lock removal, security change or proxy was used.

- Boot completed after approximately 201 seconds. Existing accepted-key
  authentication passed and `ro.adb.secure` stayed `1`.
- The installed historical e639 app/test pair matched its exact known hashes.
  The retained database hash matched and both recovery files stayed absent.
- The preinstall framework observation covered 66 seconds and recorded four
  fresh ANRs: TV Launcher, TV Recommendations, persistent Google Play services,
  and Play Games. `system_server` stayed PID 651; package lookup took 4.79 seconds.
  The ANRs failed the gate, so no fresh installation or app action was sent.
- R21 installation, signed normal Go readiness, current startup/screenshots,
  remote navigation, the optional four strict IME/source methods, and Bloom
  playback all remain **unrun**.
- Ordinary TERM shutdown produced observed emulator exit `-11` (SIGSEGV).
  All six saved-image POSIX/OFD locks were released and checked again. This is a
  known terminated process, but clean guest shutdown and post-exit database
  integrity are not proven. No force kill or automatic restart followed.

All reproduced host UI defects in this correction have passing regressions.
That is not a claim that the app is bug-free: the historical device IME/footer
and source-idling cases remain unverified on R21, as do the device behaviors
below. The runtime failure occurred before this APK was installed and must not
be reported as a reproduced R21 app defect.

### Current feature parity matrix

These rows distinguish implemented production routes from accepted end-to-end
behavior. No row claims full device acceptance on the revised APK. The
components call existing endpoints; missing external evidence is not filled
with stub success or sample catalogs.

| Screen / behavior | Current status and useful evidence | Remaining gate |
| --- | --- | --- |
| Library / list browsing | Native production layout; actual host keys verify grid, search, exact card return, management return, loading and malformed-response recovery | Current APK remote/device suite; actual scan/matching blocked by AniList |
| AniList discovery / metadata | Existing desktop-equivalent routes; strict response mapping; title metadata, long synopsis and related-title navigation pass current host checks | Actual public AniList request returned 403; no returned live anime/covers or live list/account synchronization |
| AniList / MAL accounts | Native platform sign-in and list integrations exist; simulated/list fixture results retain their earlier source scope | Authorized account OAuth/migration and sync; independent MAL list/search/progress remain documented missing APIs |
| Manga / offline reading | Native readers, mapping, queue and local-assets routes exist; current host title-return and image tests pass | Current device reader/PDF/storage suite, real offline restart, USB/provider assets |
| Playlists / continuity | Production route and thumbnail rendering checked; lossless media identities retain JVM coverage | Current playlist-to-player, queue, resume, next/previous and cross-device progress flows |
| Extensions / marketplace | New-source default and saved custom/empty preferences pass actual preference/API tests; catalog consumed from hosted URL | Current install/configure/update flows, and explicit approval before unrecognized extension execution |
| Plugins | Declarative controls, actions, navigation and device equivalents have native implementations | Current integration suite; arbitrary browser HTML/DOM/CSS is partial compatibility, not a backend gap |
| Online / torrent / debrid sources | Native typed source selection; loading Back/provider replacement and retry are covered by focused source/host checks | Fresh device transfer/playback tests, installed authorized providers and any required accounts; no Bloom playback yet |
| Downloads / auto-downloader | Typed management routes exist; host verifies original tab and opener return | Current queue state, storage/export/cancel/retry and real transfer persistence |
| Native player / subtitles / audio | Fresh player UI exists; earlier exact engine evidence and static layout scope remain recorded | Fresh app integration, decoded video/audio, seek/recovery, subtitles, codecs/color/GPU/thermal on TV |
| Nakama | Native peer/session and playback paths exist | Current route/coordinator suite plus two real authorized peers |
| Settings / storage / updates | Current host remote tests cover all Device actions, category return, loading states and 130% font bounds | Physical picker/USB grant lifecycle, real update installation and same-release-key upgrade |
| Logs / reports / exports | Native typed report and profile/export routes exist | Current dialog and system document-provider byte-export tests |

The [exact current device manifest](2026-10-01-tv-device-manifest.json) accounts
for 172 ordinary + 1 guarded + 3 cold = **176 planned device methods, all
unrun**. Four unchanged direct-engine methods retain only their earlier narrow
scope; one real scan/matching method is externally blocked. This accounts for
all 181 methods in the Android instrumentation catalog. The host test counts
above are separate and must not be added as device passes.

The full device matrix remains open on the revised candidate. Preserve the
prior per-source results rather than promoting them to this UI. In particular:

1. Install one exact same-signature app/test pair, preserve user data, verify
   package hashes and signed Go server readiness, and establish a responsive TV
   framework before interpreting app tests.
2. Run remote-only empty/loading/error recovery, populated lists, details,
   discovery, all navigation destinations and dialogs. Recheck complete focused
   bounds and physical IME search/Done/Back behavior.
3. On a normally accessible network, fetch real AniList titles and covers,
   search Bloom Into You (anime ID 101573), choose an authorized installed source,
   and verify episode selection, decoded video, audio, subtitle choice and resume.
   Installing an unrecognized third-party extension requires separate explicit
   approval for that extension; the marketplace default is not installation.
4. Retain the account/provider, physical USB, two-peer Nakama, codec/GPU/thermal,
   minimum API/ARM64/16 KiB runtime and release-signing upgrade gates in the
   [device runbook](2026-10-01-device-acceptance-steps.md).

Arbitrary browser HTML/DOM plugin presentation and independent MAL API limits
retain their precise boundaries from the earlier contract audit. Neither is
silently counted as complete native parity.
