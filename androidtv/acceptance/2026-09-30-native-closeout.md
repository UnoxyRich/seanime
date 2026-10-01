# Native TV closeout — remaining work

The subsequent UI-feedback correction is tracked in the
[TV layout and live-data report](2026-10-01-tv-layout-and-live-data.md), including
real host key/focus and image evidence and the blocked live AniList request.
The handoff below is historical; its APK identities are not the revised UI.

## Current review handoff — 2026-10-01

The current built source is `5a90fdfe4490417ca2d267086775e1b6119eb6fb`.
Its aggregate passes 315 JVM tests with zero failures/errors/skips, lint, both
ABI APKs, signatures, 16 KiB native alignment and 145 literal API contracts.
It fixes the confirmed finish-during-initialization owner race, but has **no
installation or device result**. All **168** current device methods remain
unrun, including the host-queue test whose historical reuse is withdrawn.

The concise [review readiness report](2026-10-01-native-tv-readiness.md) contains
the exact current APK identities, per-source results and limitations. Use the
[device runbook](2026-10-01-device-acceptance-steps.md) and reconciled
[finite manifest](2026-09-30-next-native-test-manifest.md) for the remaining
acceptance. The following dated sections retain the audit and prior build
history; they are not passes for the new artifact. No GitHub push was made.

Updated 2026-10-01 02:37 UTC. This is the consolidated implementation and
production-reachability list, not a compilation acceptance claim. The last
completed selected feature run is `c5b3a096` (44 passes, three failures across47
methods); later source edits are not covered by those results. Candidate
`503a7e63` is preserved but never installed. Reviewed source `01b18b2c` passes its aggregate
build and has a separate immutable export; its test APK failed installation.
The bounded repair candidate `be880d20` passes its aggregate, corrected pair
installation/hash readback and signed normal server check. Its first completed
batch has two passes and two failures. A bounded keyboard repair and test-only
Debrid diagnostic were committed in `bec3bfdc`/`e9ffacc0`. The latter passed both
source workflows but failed initial editor focus. A finite-layout repair and
stronger physical remote traversal checks are now committed in `82b48c7e`. Its
aggregate passes. Its four-method runtime gate ended with one pass and three
failures: torrent download passed, editor IME timing and footer navigation failed,
and Debrid intermittently failed Compose idle. Source `e639525d` adds explicit
hidden-keyboard Down-to-Cancel navigation, more helper space and bounded timing
diagnostics without weakening assertions; its aggregate and immutable export pass.
Earlier artifact results are not promoted to that source.
Nothing has been pushed.

## Acceptance work, in priority order

The findings below describe the audited starting gaps. Source implementation is
now written for Long identities, external handoff/hosting, IME/source focus,
personal collections, library bulk/rename, metadata/profile exports, torrent
move/rename, marketplace/custom sources and native plugin device equivalents.
All listed source changes, including advanced discovery, auto-downloader
batch/cleanup, built-in torrent details and the isolated real-Go index fixture,
are now frozen and pass the aggregate build: 311 JVM tests, zero failures/errors/
skips, lint, both ABI APKs and instrumentation packaging. Current-candidate
runtime gates remain pending.
The later contract/lifecycle review found and repaired valid omitted/null Go
empty arrays, omitted zero-progress sort values, RFC3339Nano watch-history
ordering, exact external document-grant release, termination after Activity
reclamation, and stopped preparation callbacks. The new regression cases are
part of the passing `01b18b2c` aggregate; their device acceptance remains pending.
The browser-specific plugin boundary remains explicitly partial
after its documented
feasibility audit; it is not a missing API.

| Priority | Audit finding | Production entry / evidence | Required closure |
| --- | --- | --- | --- |
| P0 | Media identity truncates custom-source IDs throughout the Android client | `MediaCard.id` and many media ID parameters use Int; Go `internal/customsource/customsource.go:27–40,165–170` uses normalized IDs starting at 2^31 and supports JavaScript-safe integers through 2^53−1 | Migrate media identities to Long across parsing, API/event payloads, maps/comparisons, navigation/saved state, recovery, native player, plugin, offline and download paths. Preserve Int episode/page/database IDs. Prove 2^31, above 2^32 and 2^53−1 round trips and distinctness |
| P0 | External player has an adapter but no native production entry | `NativePlatformActions.openExternalUrl` is exposed by an internal MainActivity facade; only the test calls it. The Go desktop-player enum in Settings does not invoke Android handoff | Add an explicit native player action for supported current sources, preserve narrow media authorization and host lifetime, and test the actual action with an owned receiver that reads the owned stream while Main is backgrounded. Do not count the inherited immediate-return fixture as this workflow |
| P0 | Keyboard visibility/layout and Done still lack valid geometry proof | c5 List14 passes, including numeric Save/Cancel/retry. The separate IME2 probe fails despite Android `onShown`. The keyboard controller was read in the parent composition rather than inside Dialog | Move the controller read to the Dialog owner; observe real input-method window bounds; prove field/footer remain above the visible keyboard, Done hides without submitting, and first Back hides a multiline keyboard while retaining the draft |
| P1 | Source chooser can clip scaled focus and its mode row lacks narrow-screen scrolling | c5 SourceDownload2 passes exact request/Cancel/retry flows. Its isolated screenshot has left-edge focus clipping and a white test background. Full-width controls have no inner scale allowance | Add proportional inner focus padding and a scrolling mode row; use a dark safe fixture; verify provider, restored Download and Back bounds on both wide and narrow layouts, keeping full request assertions |
| P1 | Personal anime/manga collection status selection and sorting are absent | Web `lists/_containers/anilist-collection-lists.tsx:228,310`; native `TvApp.kt:236–292` and `MangaReader.kt:60–115` show flat collections, and anime Search currently calls catalog search | Add typed personal-list status/sort controls and local collection search, preserve independent Discover search, and verify empty/loading/Back focus and actual displayed ordering |
| P1 | Library batch and directory actions are absent | Web `_features/library-explorer/library-explorer.tsx:199–237,688–725,879`; native `LibraryTools.kt` exposes only individual file rows | Reachable native selection sets and folder actions with match/unmatch/lock/unlock/ignore/unignore through existing `PATCH /library/local-files`, fresh selected paths, exact payload and error/retry/cancel proof |
| P1 | File rename and built-in torrent move/rename are absent | Web `library-explorer-super-update-drawer.tsx:629–644` and `torrent-client/page.tsx:780–816`; native Files only edit/play/delete and torrent actions only hash/action | Native rename preview and bounded editor; torrent rename and server-folder picker for the built-in client; existing super-update/torrent-action routes, fresh identity, confirmation and focus/error recovery. General library file moving was not found as a web workflow |
| P1 | Built-in torrent detail controls are absent | `torrent-client/page.tsx:395–482,577–636,925–1002` exposes queue/force-start/recheck/reannounce/sequential/limits and file priorities/trackers/peers; native Downloads has only basic actions and the newly written move/rename | Add native details, typed file/trackers/peer views and actual built-in controls, preserve hash/file identity and limits units, and distinguish asynchronous recheck acceptance from completion. All actions must be production-reachable and use existing routes |
| P1 | Library metadata index import/export is absent | Active `anime-library-settings.tsx:153` mounts `data-settings.tsx:30–43,62–102`; no native caller for import/dump | Select a server-readable JSON file, explain and confirm active-index replacement, call existing import route; export dump through Android create-document, with bounded download and cancellation/retry |
| P1 | Native extension marketplace browsing is absent | `NativePluginNavigation` rejects `/extensions?tab=marketplace` although `extensions/page.tsx` mounts a marketplace and the server registers `GET /extensions/marketplace` | Add native repository source, title/type/language filters, manifest preview and individual installation; retain source trust/permissions and precise installed/update state |
| P1 | Custom-source browsing is absent | Web `custom-sources/page.tsx` lists provider-supported anime/manga through existing `/custom-source/provider/list/{anime,manga}`; native accepts the provider type but has no caller | Add a native provider/type/search/page screen with actual detail/playback/reader paths, retain the normalized Long ID and full selected-page focus through Back, and cover both media types plus errors/empty/cancel |
| P1 | Advanced manga discovery and recent-airing discovery are absent | `NativePluginNavigation` explicitly rejects advanced manga `/search` parameters and `/discover?type=schedule`; registered `/manga/anilist/list` and `/anilist/list-recent-anime` exist | Audit current caller payloads, implement supported typed filters and recent-airing native list/pagination, connect production and plugin navigation, and verify exact requests and return focus |
| P2 | Diagnostic profile exports are absent | Web `logs-settings.tsx:179,375–425` exposes heap/allocations/goroutine/CPU; native Logs only logs and issue reports | Reachable native profile choices using existing memory routes, correct byte/file types, progress/error/cancel and platform document output proof |
| P2 | Auto-downloader multi-series creation and no-longer-airing cleanup conveniences are absent | Web `autodownloader-page.tsx:175–207`; native `AutoDownloaderScreen` supports ordinary rules/profiles CRUD and per-rule/all simulation | Implement native batch-series selection and a reviewed cleanup list where existing list/status data is sufficient; preserve ordinary CRUD and simulator acceptance |
| P1 | Arbitrary browser-specific plugin presentation remains unsupported | `NativePluginState.receive` explicitly rejects `webview:iframe` / `dom:*`; declarative controls, typed navigation, relative links, nine action families, episode tabs, trays and commands have native implementations | Complete a finite contract-level feasibility audit for native alternatives and identify precisely which operations can be mapped to native controls. Do not call this a missing backend API or claim arbitrary HTML/DOM/CSS parity; no embedded/reused React UI is permitted |

The former missing-provider-row failure is **not currently reproduced**: c5
SourceDownload2 passes both the normal callback-off debrid path and the diagnostic
torrent path. Logs show one provider, its real item body and a 1920×80 layout.
The old zero-height cause was not proven. Retain the prior failure and verify the
ordinary callback-free route after the focus-only patch; do not invent a cause.

A final read-only identity pass found one dormant Int-only branch in
`SettingsFieldModels.settingListValue("hostUnsharedAnimeIds")`, with an obsolete
unit assertion rejecting `2147483648`. Current Settings dispatch uses
`HiddenTitlesDialog` instead, whose IDs and JSON values are Long; this is not a
current production truncation. `be880d20` migrates the dormant branch through
the shared range-checked Long parser and adds a three-boundary JSON roundtrip
regression so future callers cannot reintroduce the old limit.
The rest of the audited active route, key, queue, plugin and recovery boundaries
use Long. The preceding immutable artifacts remain unchanged.

## Implemented fixes awaiting their complete production-path acceptance

These are no longer known missing-code entries, but they remain unaccepted until
the named screen-to-action flow passes on its immutable candidate.

| Workflow | Production path | Remaining evidence |
| --- | --- | --- |
| Anime/manga list edit, dates and removal | Browse detail / Manga title → shared native list dialog → existing list mutation routes | c5 List14 now passes all methods, including sparse values, dates, retries, removal and focus. Live AniList synchronization remains a separate account gate |
| Local collection upload | Accounts in Browse/Settings → MainActivity `upload-local-anilist` → named-account confirmation → `/local/sync-simulated-to-anilist` | c5 native named-account Cancel/confirm fixture passed; actual account upload cannot be inferred from that fixture |
| Library file editing/deletion | Library tools Files/Explorer → Edit match/Delete file → fresh index read → existing PATCH/DELETE → refresh and restored focus | c5 delete confirmation/retry/focus passed. Match test stopped before PATCH because it asserted exact text `9` against the correctly retained `Episode number: 9`; both matchers are corrected in source. Real indexed import/bulk/rename/delete on owned files is being added separately |
| Manga cached/online/offline source behavior | Manga title → Refresh / source match/reset / downloaded fallback → chapters → reader | c5 complete Reader8 passed306.638s, updated real PdfRenderer3 passed54.456s; manga entry workflow3 passed in the combined7-method batch. Long-ID changes require fresh integration checks |
| Manga filters/preferences | Manga title → Language/Scanlators → sparse per-provider preference PATCH; reader settings stored per title | c5 four preference flows passed, including provider switch, failure/retry, relevant event and offline independence; combined preferences/workflows7 passed310.301s |
| Online identity and episode navigation | Source chooser resolves actual provider row → coordinator → native HUD and next/previous | Provider number must remain separate from canonical episode identity; absent metadata must not fabricate progress. Verify UI source selection, standalone disabled actions and source-bound/global navigation |
| Plugin configuration/links/reports | Extensions → Configure/typed links; Logs → report category/description → prepare → Export → platform save | c5 configuration/report2 passed exact category, true acknowledgements and retry/draft retention in the transfer/report5 batch256.82s. Actual system document-provider byte export is a separate gate |
| Queue/offline status | Downloads and Offline → existing actions plus actual WebSocket state | c5 transfer3 passed cancellation wording, terminal-only clear guard and metadata acknowledgement/processing/finish distinctions. Long-ID queue keys require the new candidate |
| Platform/lifecycle corrections | Native app recreation, external adapter, SAF folder access, player recovery | c5 repaired batch PASS3/3 in156.576s: real signed status and same-client ping/pong through recreation, route/query/background focus; exact BROWSABLE adapter delivery/immediate fixture return; signed real-Go directory-selector/SAF readback. The separate production external entry/sustained streaming and remaining recovery/form/settings/theme checks stay open |

The read-only menu/caller audit confirmed the additional rows above. It also
confirmed that auto-downloader CRUD/simulation and issue-report platform export
are production-reachable; these must not be called missing merely because a
helper has few direct references. The metadata Settings panel is active even
though an older standalone Data tab is commented out. Registered routes alone
do not establish a missing user workflow or its implementation.

## Existing backend limits and external gates

Exact source evidence is in `app/src/main/java/app/seanime/tv/data/README.md`.
These must not be mislabeled as completed workflows or hidden behind success UI:

- Manga Stop does not join a worker; queue rows alone cannot prove cancellation
  finished. The native clear guard is limited to freshly confirmed errored rows
- Offline metadata completion is a server-wide signal without request IDs or
  per-title results; empty queue snapshots are not completion receipts
- Local-list migration discards per-title update errors, so HTTP success cannot
  establish successful upload of every title
- Independent MAL search/list/progress routes are absent or unregistered;
  country-of-origin filtering and MUSIC discovery are limited by the existing query
- Live partial-date clearing semantics remain unverified; complete date edits and
  preservation are implemented, with local nullable clearing supported
- Converted HLS suspends after the application backgrounds. No existing native
  external handoff reaches it; adding arbitrary HLS external handoff requires an
  explicit supported ownership design, not suppressing all background suspension
- Browser-specific plugin presentation is a native implementation boundary,
  separately tracked above, not a missing API
- Real scan/matching remains gated by public AniList DNS and the provisioned
  proxy's explicit 403. The passing isolated raw-Go video test does not replace it
- Live OAuth/provider/debrid transfers, two real Nakama peers, physical TV codecs
  and GPU/thermal behavior, real USB, API 23/ARM64/16KiB runtime and a persistent-key
  release upgrade retain explicit external acceptance steps

## Acceptance rule

### Footer-focus candidate `e639525d`

Export: `toolchain/runtime-footer-focus`. Source
`e639525dcabaf18e2c6082f3ec7ce1459f2bb7fc`, tree
`839421be0702e3b60e74b75732d70690ae5669de`.

- ARM64 SHA-256: `fb7a97a6982326eee9adf735cb7dfc901cd4c67820d6c0fbfda7da77984863fb`
- x86_64 SHA-256: `ee0d11a72b76ae47d474eba6611d4e7e65927f78366a265b8e47abf847df1d49`
- Test SHA-256: `71ade9668f6b440312aef30690fc69fced762441afbe9754781fd57673a1aab4`

The full aggregate passes in 3m37s: 311 JVM tests with zero failures/errors/skips,
lint and all APKs. V1/V2 signatures, existing debug certificate, both ABI 16 KiB
ELF/ZIP alignment, 145 literal API contracts, unchanged-Go and fresh-presentation
checks pass. The 19 retained engine entries are byte-identical and their direct
source paths remain unchanged. These results do not cover current UI runtime.

The editor now explicitly routes hidden-keyboard Down to Cancel before the
TextField consumes it for cursor motion. Helper allocation reserves a labelled
input row while allowing ordinary guidance more space. The same two strict IME
methods record monotonic probe timing without logging draft contents or changing
the 10-second visibility deadline. No production source-dialog code changed.
The sole runtime owner is recovering the saved AVD before new package/data/status
gates and the full four-method retry. Later finite feature batches remain open.

### Bounded-editor candidate `82b48c7e`

Export: `toolchain/runtime-bounded-editor`. Source
`82b48c7e6715774c6519608a270cae145bb0d64b`, tree
`acdd49369b50a026e113368bd163d7b7c2f580aa`.

- ARM64 SHA-256: `e5065f481b08eaa386d355ac8fbc74bd5370e0017405ca666ffdb70c32931bdb`
- x86_64 SHA-256: `1c3841e0239cdb3b940449ae6682dba85a72ca8b1f1b239e860112dd6006371e`
- Test SHA-256: `638607b706a9e9d314f93cba64573832ac39d0226b418ecab3396dac43488fe2`

The complete aggregate in `toolchain/logs/native-bounded-editor-aggregate-r1.log`
passes in3m47s: 311 JVM tests with zero failures/errors/skips, lint and all APKs.
V1/V2 signatures, retained debug certificate, both ABI16KiB ELF/ZIP alignment,
145 literal API routes, unchanged-Go and fresh-presentation/package checks pass.
The same two IME methods now cover full helper access and actual remote footer
navigation; the manifest still has163 ordinary unique methods, one guarded
recovery case and three cold Go cases. Installation, exact package/data checks
and signed normal server status passed before the four-method batch below.

The fresh four-method batch completed in 803.976s with one pass and three
failures. Torrent destination selection, cancellation, retry and exact requests
passed. Numeric IME geometry and Done passed, but physical Down left focus in the
editor. Multiline timed out at its first 10-second IME wait; its later failure
capture shows the real keyboard and bounded controls, so it does not establish
that the keyboard never opened. Debrid failed waiting for destination choices;
its first watchdog stall preceded the opening click. The accompanying tiny
localhost responses and preceding screen actions also took much longer than in
the prior passing run. The cause is unproven; no production source-dialog change
or weaker timeout was introduced. See the separate source-idle diagnosis.

Exact accounting and fresh-vs-cached image metadata are in
`toolchain/evidence/native-bounded-editor/01-ime-source-repairs/result.json`;
raw-log SHA-256 is
`06e9c69e70ab31d50bb4e50e18996c04f2d043f74a5af08fd6d51cf45b7c3012`.
The old owner command session disappeared after exports; no new runtime claim
is made until retained data, recovery files, packages and signed normal status
are checked again. `e639525d` is the next repair candidate.

### Floating-keyboard repair candidate `e9ffacc0`

Export: `toolchain/runtime-floating-ime`. Source
`e9ffacc0ceed289e0f05f62e9adbcda33f1810d6`, tree
`46babbdc89377a06836fcd3a05abfcd62cca4ff6`.

- ARM64 SHA-256: `d9106802413e32057eefa752ea8ca75645b25a8a1a5cfe1e9753b3a26ebdd060`
- x86_64 SHA-256: `4fd44d445003b15e92e42b842d7b4004baf9005901caf9d4233d12ba0cceb43f`
- Test SHA-256: `795f7005eae77be753053e459c365e9ffa94860d05a38e8e786cf5db29d6b954`

`toolchain/logs/native-floating-ime-aggregate-r1.log` records the complete build:
311 JVM tests, zero failures/errors/skips, lint and all APKs pass in3m45s. V1/V2
signatures share the retained debug certificate; both ABIs' seven native libraries
pass16KiB ELF/ZIP checks. The scanner passes145 literal routes, unchanged Go and
fresh native presentation/package guards. All19 compared shader/native-library
entries are byte-identical to the earlier f66 engine artifact; narrowly retained
engine/queue tests keep their original scope. Detailed unit/lint/build/signature/
contract/hash reports are preserved with this export.

The next device order is exact installed-pair/data/status verification, isolated
Debrid reprobe with its new bounded stack diagnostics, then the full IME/source
batch. The reprobe repeats an existing method and must not inflate unique coverage.
Numeric and long-draft geometry, actual Done/Back and unchanged mutation/focus
assertions remain required; compilation is not their result.

Device results: the isolated Debrid repeat passed in121.595s, and the complete
four-method batch finished in216.254s with both source workflows passing and
both IME methods failing. Both failures are `uncancelled requests present` from
Compose's `BringIntoViewRequestPriorityQueue` during initial focus, before the
keyboard appears or the initial9.5 value is replaced. The explicit request plus
the editor's scrolling ancestor is not accepted. Fresh failure PNGs are distinct
from older cached `*-shown` images. The Debrid watchdog captured no stalled steps
in either passing run; no production Debrid change is justified by these results.

`82b48c7e` removes explicit bring-into-view and the editor's outer scroller. Helper
and editor are measured together in finite space; the editor scrolls its own
draft, while overflowing help has a separate visible focus target and D-pad
scroll/return controls. The same two tests retain strict IME geometry and now
cover12 draft lines, all20 help lines, physical help navigation and physical
Down→Cancel→Right→Save after Done. No assertion timeout was changed. At01:31 the
owner session became inaccessible again after all test exports had completed;
there is no newly interrupted test. Read-only queries found all six writable
saved disk images unlocked. Ordinary same-shell recovery waits for the new build.

### Previous selected runtime candidate `be880d20`

Export: `toolchain/runtime-native-closeout-installable`. This directory name is
the intended candidate purpose, not a passed installation claim. Exact source
revision `be880d209f480b1f7c87760495bb965b182a0c4d`, tree
`9b877d17f519eeb61379085fcb94673afb5653de`.

- ARM64 APK SHA-256: `6028f3d6baa268ed765267c97a20fbc0a1d4bdab90a699e4c6d57ba567696fca`
- x86_64 APK SHA-256: `40588dab2895ac426fde0ea92710a6d4993ecb950346e157dcc3524a9bf35f05`
- Test APK SHA-256: `b287ae499f95bea13a3944a2485ca62d3f76275236973205f9a9f7985376ea31`

`toolchain/logs/native-closeout-aggregate-r6.log`: 311 JVM tests with zero
failures/errors/skips, lint and all APK builds pass in3m10s (81 tasks,21 executed,
60 up-to-date). The added regression covers the dormant settings serializer at
all three high-ID boundaries. Both production APKs and the test APK pass V1/V2
signature checks with the existing certificate. Seven native libraries per ABI
pass16KiB ELF/ZIP alignment; the source/package scanner passes145 literal route
checks, the unchanged-Go guard and presentation boundaries. Exact reports and
unit XMLs are preserved with the immutable export.

Both APKs installed successfully and the installed app/test hashes match the
export above. Actual ART compilation filter is `verify` for both packages;
this is not AOT/performance evidence. A cold normal launch returned signed
HTTP200, version3.10.3 and exactly
`/data/user/0/app.seanime.tv/files/seanime/data`. The retained database SHA-256
and absence of both recovery files match their pre-install values. Authentication
proof remained transient inside the guest. The first four IME/source methods
started at23:44 UTC. At00:06 UTC, recovery found their owner session unavailable;
the844-byte log ends at the first method's START with no assertion or terminal
result. Later diagnosis established that exec calls have separate network/PID
namespaces, so refused loopback connections and missing PIDs in a new call did
not prove the old VM died. Lost control and the incomplete batch are confirmed;
it is not a test failure or pass. Saved AVD data and locks were not manually
changed; a new sole owner is performing ordinary lock-respecting recovery.
No feature-suite result is implied by the earlier startup checkpoint.

The first ordinary restart refused to start because free disk space was1.9GiB;
Android documents a5GB startup prerequisite. No cache/artifact was deleted and
the gate was not bypassed. The task-owned SDK `system.img` contained6.9GB of
verified zero extents. With no active image user, standard `fallocate --dig-holes`
preserved its exact8,601,468,928-byte logical length and SHA-256
`417a905fab476f2fdd860eec7fdfb903685c37d71a0f139a7d3df742f6cdcb0e`,
while allocated bytes fell from8,601,473,024 to1,692,393,472. Available space became
8,917,610,496 bytes. Before/after verification is preserved in
`toolchain/evidence/native-closeout-installable/interruption-20261001/`.
The saved AVD was not reset or manually edited. Keys, cache contents and immutable
APKs were not altered by the SDK sparsification.

The normal emulator launch next hit a PID-file lock. Official emulator locking
checks PID existence, and Linux also recognizes thread IDs for `kill(id,0)`;
new namespace IDs can collide with a stale recorded ID. A real emulator-version
preflight followed by ordinary launch let the vendor remove its stale lock and
start QEMU17 without any manual lock edit or force flag. That VM booted, but a
separate exec's client could not reach its isolated loopback. The replacement
control topology keeps QEMU in an independent background process and runs bounded
client/test commands through one permanent interactive shell in the same
namespace, without changing network or security configuration.

The known intermediate supervisor accepted an interrupt and exited130; no QEMU
graceful-shutdown message was emitted, so clean shutdown is unproven. Subsequent
read-only `F_GETLK` and `F_OFD_GETLK` checks on all writable saved userdata/cache/
encryption IMG and QCOW files returned unlocked. Exact diagnostics remain in
the interruption evidence directory. An ordinary launch and fresh installed-
state/data checks were required before retry; no reset or reinstall was performed.

At00:31 UTC the permanent interactive owner19486/QEMU179 topology passed the
same-namespace recovery gate: existing key, `ro.adb.secure=1`, exact be88 app/test
hashes, unchanged retained database SHA-256, both recovery files absent, and
signed HTTP200/version3.10.3/exact normal data directory. QEMU has its own
PGID/SID179; bounded runner514 is separately addressable. The four-method retry
uses a fresh r2 log and preserves the incomplete original. An immediate screenshot
still showed startup, so it is not a new settled-home claim; the23:43 Library
image remains its own valid checkpoint for this same immutable source.

The retry completed all four methods in780.842s: **two passes, two failures,
zero incomplete/error/skipped results**. Multiline physical Back preserved its
draft; torrent download passed Cancel/reopen, configured/subfolder selection,
server-error retry, exact payload and restored full-outline focus. The numeric
IME test failed actual geometry: keyboard top602, editor bottom640 and footer
bottom752. Both app windows reported IME visibility with zero bottom inset, so
this is a real floating-keyboard layout gap. Source `bec3bfdc` reserves an upper
editing area only for that public visibility/zero-inset case, bounds the editor
frame above pinned actions and preserves hidden/docked layouts. Its expanded
12-line draft and existing numeric Done/Save regressions remain strict.

Debrid reached successful search/settings/directory-selector responses, then
failed Compose idle while waiting for configured-root choices. Static review
found no repeated request/effect loop; its shared destination dialog is unchanged
from c5, and the torrent counterpart passed. No speculative production source
change was made. `e9ffacc0` adds only the existing bounded 15s/30s app-owned Java
stack watchdog around test steps. An isolated Debrid execution on the next pair,
followed by the complete four-method batch, is required. The root-required
`debuggerd` attempt was stopped at that restriction; no privilege/security
change was made.

Exact terminal results, raw-log hash, diagnostics and timestamped PNGs are at
`toolchain/evidence/native-closeout-installable/01-ime-source-repairs/result.json`.
The cache export also contains old images; only matching scenario/timestamps
within this run are fresh evidence. Postbatch exact APK/data/recovery and signed
normal status checks passed, followed by a settled native Library capture at
`toolchain/evidence/native-closeout-installable/postbatch-20261001/normal-library.png`.
All later finite-manifest batches remain unrun; QEMU is held idle for the build.

### Reviewed built candidate `01b18b2c`

Export: `toolchain/runtime-native-closeout-reviewed`. Exact source revision
`01b18b2cfabcff59f9bfe495cc2d87a697e3186e`, tree
`7e8807c52642e7efb02a50bc636f1d290a9e3191`.

- ARM64 APK SHA-256: `7890b8ec193b073d92dc7a7b10461050747db69e6029aee281c7b9d8c8d2a174`
- x86_64 APK SHA-256: `7144406be8d9c3e577f655046a80bb25d52828919014a00510464de59d785b38`
- Test APK SHA-256: `b2d318d27db7455e3043ba26c316a1264cd63c1014fdfd57e1f86a61eece64e1`

`toolchain/logs/native-closeout-aggregate-r5.log` records the fresh aggregate:
310 JVM tests with zero failures/errors/skips, lint and all three APK builds pass
in4m1s (81 tasks,22 executed,59 up-to-date). The export includes the exact XML
unit results, lint report, aggregate log, source/tree and hashes. V1/V2 signatures
share the existing debug certificate; both production APKs contain seven native
libraries with16KiB ELF/ZIP alignment. The source/package scanner passes all
checks, including145 literal API contracts, unchanged Go core/API/schema and no
React/legacy presentation assets. The source checker does not establish runtime
behavior or complete feature parity.

Installation on the saved AVD installed the production APK successfully, but
rejected the companion test APK with `INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION`:
`ParsedActivityImpl cannot be cast to java.lang.String`. Hash readback confirmed
the new production APK and retained c5 test APK; no instrumentation was launched
with that mismatch. The saved database hash and recovery-file absence were
recorded before installation. Exact evidence is in
`toolchain/logs/native-closeout-reviewed-install.log`, its package-manager logcat
and `toolchain/evidence/native-closeout-reviewed/restart/`.

The test receiver declared an invalid private process name, `:external-player`.
AOSP [ComponentParseUtils](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/pm/pkg/component/ComponentParseUtils.java)
validates the suffix through
[validateName](https://android.googlesource.com/platform/frameworks/base.git/+/13721788b311beb1233a10db2e90ed6387013aee/core/java/android/content/pm/parsing/FrameworkParsingPackageUtils.java),
which rejects hyphens and accepts underscores after the initial letter. Its
unchecked `getResult()` after the validation error, together with
[ParseTypeImpl.error retaining its old result](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/content/pm/parsing/result/ParseTypeImpl.java),
explains the misleading activity-to-string exception. `be880d20` changes the
test-only name to `:external_player`; the separate UID/private-process design
remains intact. No package cache, saved data, key or security setting was altered.
The corrected installation still requires fresh verification.

### Earlier built candidate `503a7e63` (never installed)

Export: `toolchain/runtime-native-closeout`. Exact source revision
`503a7e63bdbe76d268dfd9dd3ae5897dd97c0a03`, tree
`01cf4a7f6ec0d593ed074b9d7b5efc1b7f53975c`.

- ARM64 APK SHA-256: `e580fc608abe3ad390ba53c231e56c81c2129188a230e404d6172acab6d9b6f3`
- x86_64 APK SHA-256: `4c289c639a70ce90302a61ca7fc19d99b4a94e3915b21eebeb4d3d3226660edd`
- Test APK SHA-256: `c3e0fb4f58758e7861f7fb79d40ad5f0e37f0ace699cafad15cdc6cb30fc4183`

Build attempts are preserved as `toolchain/logs/native-closeout-aggregate-r{1,2,3,4}.log`:
r1 stopped after36s at data-only JSON helper references; r2 ran all300 JVM tests
successfully and stopped after3m9s on two test receiver-registration lint errors;
r3 passed lint and both production APK builds, then stopped after1m18s on four
instrumentation typing/syntax sites; r4 reused the unchanged JVM results and
completed the full aggregate in1m19s. No check was suppressed or bypassed.
V1/V2 signatures share the existing debug certificate; both APKs contain seven
native libraries with16KiB ELF/ZIP alignment. The source scanner checks145
literal API contracts and confirms Go core/API/schema unchanged from the base.

The earlier finite runtime plan is160 ordinary methods and three separately cold,
isolated Go workflows; the runtime owner is reconciling the new review regressions
before execution. The saved AVD started under its ordinary locking rules after
the prior owner command session disappeared. The stock SDK client could not
create `/home/agent/.android` on the read-only filesystem. A reviewed,
non-overwriting symlink attempt returned `EROFS`; it made no change. `HOME`,
keys, permissions and guest security were not changed.

Authenticated device access was recovered through pinned `adb-shell` 0.4.4's
public `AdbDeviceTcp` and explicit existing-key `CryptographySigner` APIs. The
existing key was accepted without enrollment; the callback rejects any enrollment
request before sending a new public key. `ro.adb.secure=1`, boot completion and
the installed c5 app/test hashes were verified. An owned byte-exact push/pull and
streaming shell/remote-exit probe passed. This establishes the tool transport,
not the new application runtime. Signed Go status, new APK installation and the
finite feature batches still gate acceptance. Compilation does not satisfy them.

For each final feature claim, cite a visible native production entry, its action
handler and existing API/platform target, plus the strongest exact runtime result.
Inherited standalone scripts, engine tests and callable adapters establish only
the scope they exercise. The external-player discovery is a concrete example of
why an adapter test alone cannot close a production workflow.
