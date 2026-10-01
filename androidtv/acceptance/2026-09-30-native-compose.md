# Native Compose Android TV acceptance — September 30, 2026

Base revision: `63200d6a850a6843b52f90ff7775d940416a534d`.
This report evaluates the native migration in the working tree, not the older
React/WebView APK. Final artifact hashes and test results must be recorded below
before treating a build as a tested deliverable.

## Scope and interpretation

- `MainActivity` is an Android `ComponentActivity` hosting Compose and TV
  Material controls. It does not create a UI WebView or mount React.
- The Android build no longer builds/copies the shared React frontend.
  `mobile/web/.gitkeep` remains solely to satisfy the unchanged Go embed pattern;
  no web UI assets are embedded. Go implementation, API and module files are
  unchanged relative to the base revision.
- The embedded Go server, real gomobile bridge, Media3 player, bundled
  FFmpeg/ffprobe and existing SAF adapter remain in use. No server substitute or
  placeholder native library is part of the build.
- The restricted `AuthWebViewActivity` is an OAuth-only browser for AniList/MAL,
  not the application UI. Provider sign-in is a separate, unverified gate.
- Having a native route, an API method, or a compiling screen does **not** prove
  complete desktop feature parity. The matrix below explicitly separates the
  implemented source path from missing surface area and runtime evidence.

## UI provenance and revised acceptance boundary

The user's latest requirement applies to **all** presentation, including the
Android player: rewrite the UI for TV rather than reuse its previous controls.
The earlier native checkpoints retained the old Android widget/player-controller
presentation. They are therefore not final presentation acceptance, even where
an underlying playback engine test succeeds.

- Main application routes, onboarding, navigation, feature dialogs and settings
  use newly authored Compose/TV presentation rather than copied React UI.
- Player controls, error panels, track/settings dialogs and translated-caption
  presentation now use clean-slate TV Compose in NativeTvPlayerPresentation.
  The source passes the new provenance gate; exact APK compilation and
  remote-input/device tests remain separate pending requirements.
- Existing Go/backend, Media3 playback engine, original shader-network weights,
  Android video/ASS surfaces and SAF adapters are functional infrastructure,
  not reuse of the previous UI controls or layouts.
- The new scanner requires a Compose player host, explicitly disables Media3's
  built-in controller and rejects retained widget controls, Android AlertDialog
  and TrackSelectionDialogBuilder. A separate guard requires fresh Compose OAuth
  chrome/platform prompts and restricts provider pages to the HTTPS AniList/MAL
  host policy; OAuth authentication itself remains untested. This checks structural boundaries; it does
  not prove visual originality or usable focus behavior by itself.
- Existing libass, PDF and Media3 engine checkpoints must retain their own APK
  hashes and cannot be promoted to acceptance of the rewritten player controls.

## Screen, behavior and API parity matrix

Current committed source is `5a90fdfe4490417ca2d267086775e1b6119eb6fb`, tree
`0cb959805ae2df03321a24119fe3c6d294c98164`, exported as
`toolchain/runtime-host-retirement`. All315 JVM tests (zero failures/errors/skips),
lint, both ABI/test builds, signatures,16KiB alignment,145 API/provenance checks
and unchanged-Go validation pass; the aggregate took3m41s. Immutable x86 app
SHA-256 is `7b6233cd5da07ed291144c9c43fb9be4b82c4ece8607f660d8c3ff393f2df3e2`;
ARM64 app is `646bea24cce81f0c057a9bff086e5292be81f2ec4334b9bd89a54959f9aabd72`;
test APK is `71ade9668f6b440312aef30690fc69fced762441afbe9754781fd57673a1aab4`.
The test APK is byte-identical to e639; both app APKs changed. This source
retires finished native host owners immediately,
rejects delayed/cancelled readiness work and updates NativeHostQueue,
MainActivity and Application, with four new JVM regressions. The new artifact
has not been installed or instrumented. Its installed hashes, ART mode,
retained-state, signed-normal and framework-readiness gates remain unrun.

The historical one-method Android host-queue pass is no longer reusable because
that implementation changed. Its existing main-looper responsiveness method is
included in batch22, giving **164 ordinary methods in29 batches, one guarded
method and three cold methods:168 current device methods, all unrun**. All19
native-engine/shader ZIP entries compare equal and direct shader/libass source
checks remain unchanged. Only that direct evidence is reused; the exporter
records the host source delta and withdraws the host-queue device pass.
Identical native engine entries cannot validate host lifecycle behavior.
The new frozen manifest and per-method ledger are under
`toolchain/evidence/native-host-retirement/`.
The e639 frozen manifest and167-method evidence capsule remain unchanged.

Prior frozen source is `e639525dcabaf18e2c6082f3ec7ce1459f2bb7fc`, tree
`839421be0702e3b60e74b75732d70690ae5669de`, exported as
`toolchain/runtime-footer-focus`. All311 JVM tests, lint, both ABI/test builds,
signatures,16KiB alignment,145 route/provenance checks, unchanged Go and retained
engine comparison pass; the aggregate took3m37s. x86 app SHA-256 is
`ee0d11a72b76ae47d474eba6611d4e7e65927f78366a265b8e47abf847df1d49`;
test APK SHA-256 is
`71ade9668f6b440312aef30690fc69fced762441afbe9754781fd57673a1aab4`.
Hidden-IME physical Down now targets Cancel; helper height is revised and the
existing two IME tests add bounded visibility timeline diagnostics without
changing assertion deadlines. No method names/counts changed:163 ordinary
methods in29 batches plus one guarded and three cold methods. The last installed
pair readback at 03:23:30 UTC matches both exact hashes and the original database
SHA-256/both absent recovery files. Actual ART filters after installation were
`verify` for both packages. The following cold signed normal status passed
HTTP200/version3.10.3 and exact `/data/user/0/app.seanime.tv/files/seanime/data`.
Instrumentation has not started. The earlier framework settling gate failed: system_server
PID3314 stayed unchanged over89s and package service answered, but a new SystemUI
ANR occurred in that interval. Signed Go readiness is not framework readiness.

The first ordinary saved-AVD launch ended before boot with the vendor
snapshot-operation timeout. Same-owner inspection proved QEMU17 had exited;
vendor PID lock files still named17. One ordinary retry after fresh POSIX/OFD
image-lock checks booted QEMU51 in permanent owner19733, without editing locks,
resetting data or changing keys/HOME/security/network settings. Boot completed
in199.336s according to the emulator. The old82 installed hashes, database and
recovery absence were verified before replacement.

The first replacement installed the new app, then failed installing its test
package with package-service broken pipe/exit224. Preserved system/crash/events
logs show a system_server watchdog kill for an86-second android.io handler stall,
preceded by SystemUI/Gboard/launcher and other system-app ANRs. A read-only probe
confirmed new app/old test hashes, unchanged database/recovery, and framework
restart to PID3314. Point-in-time guest/host memory and free disk were recorded;
they do not exclude earlier pressure or establish the underlying stall cause.
One supported test-only retry succeeded, followed by exact pair/actual ART and
signed normal-state gates. No mixed-pair instrumentation was run. Evidence is in
`toolchain/evidence/native-footer-focus/restart/`; raw retry output is
`toolchain/logs/native-footer-focus-retry-test-install-r1.log`.

The authorized transfer sent SIGTERM only to owned QEMU51 after all client jobs
had ended. Wait returned139; this is terminal abnormal exit, not a clean shutdown.
Same-owner process/port checks and POSIX/OFD queries established QEMU absent and
all six writable images unlocked. Fourteen checkpoint/status/PNG/boot artifacts
are retained byte-for-byte with a hash manifest in `restart/pre-root-transfer/`.
A root-owned serial supervisor was then launched with fixed operations and a
required60-second stable-framework gate. Its first start job stopped before
vendor version/start because this Python lacks `fcntl.F_OFD_GETLK`; the job is
recorded `aborted_unknown` and fenced for review. No root-VM boot or new-pair
instrumentation is claimed from that attempt.

The separately reviewed Linux OFD compatibility correction was verified against
real POSIX/OFD locks and the host queue tests. Root run03 then passed ordinary
startup, existing-key boot and the exact installed pair/retained state/signed
normal checkpoint. Its required framework-settle job failed: system_server
PID675 remained stable and package service answered, but two new SystemUI
service ANRs (20.666s and20.089s) appeared during the71-second interval/readback.
The queue fenced further work. Batch01 was not published; all163 ordinary,
one guarded and three cold methods are **unrun on e639**. This gate is environment
evidence, not a Seanime assertion failure. Exact job result is
`toolchain/durable-runtime/queue/jobs/footer-root03-04-framework-settle/result.json`;
health evidence is `toolchain/evidence/native-footer-focus/restart/settle-end-r3.json`.

At the 03:47 UTC closeout, the root confirmed that owner session47888 returned
`Unknown process id`; its last durable heartbeat is 03:33:38 UTC. All six queue
requests already have terminal results, but none is a root03 stop/shutdown
request. Root QEMU19's terminal exit code and clean shutdown are therefore
unknown. Read-only kernel POSIX/OFD queries at 03:48:06 UTC found all six saved
writable image files unlocked. This establishes released image locks, not a
verified clean shutdown or a fresh guest-data checkpoint. No new job, client or
VM was started after ownership was lost. Saved AVD/data/cache files, immutable
APKs and existing evidence remain preserved; runtime work is held. The retained
evidence package and per-method readiness ledger are under
`toolchain/evidence/native-footer-focus/root03-owner-loss-20261001/`.

Previous frozen source is `82b48c7e6715774c6519608a270cae145bb0d64b`, tree
`acdd49369b50a026e113368bd163d7b7c2f580aa`, exported as
`toolchain/runtime-bounded-editor`. Its311 JVM tests (zero failures/errors/skips),
lint, all APK builds, V1/V2 signatures,16KiB alignment,145 route/provenance
checks and unchanged-Go guard pass in3m47s. The immutable x86 APK SHA-256 is
`1c3841e0239cdb3b940449ae6682dba85a72ca8b1f1b239e860112dd6006371e`;
the test APK is
`638607b706a9e9d314f93cba64573832ac39d0226b418ecab3396dac43488fe2`.
This source removes the competing outer editor scroller/explicit bring-into-view
request, bounds the helper/editor regions, and adds overflow-only physical
remote scrolling to long helper text. The same two IME methods now require a
12-line draft and20-line helper, numeric Done followed by physical
Down/Right/Center Save, and preserved first-Back behavior. Both installed hashes and actual ART `verify` filters pass. Retained database
SHA-256 and absence of both recovery files match the baseline; cold signed
HTTP200/version3.10.3/exact normal dataDir passes. The full four-method
IME/source runtime batch finished **1 PASS / 3 FAIL in803.976s**, with no
incomplete, skipped or unrun methods inside that batch. It started01:53:32UTC;
all exports completed and the finite runner stopped for review at02:08:15UTC.
The complete torrent source Cancel/destination/server-failure/retry/focus flow
passed. Multiline failed its10-second initial IME-visibility wait after replacing
the12-line draft. Its subsequent failure capture shows Gboard visible aty600,
so this timeout does not prove that the keyboard never appeared. The strict
geometry, first-Back and long-helper paging assertions were not reached.
Numeric geometry and Done-without-save assertions passed, but physical Down
left the editor focused instead of Cancel; physical Right/Center Save was not
reached. The Debrid case opened its release destination dialog in24.366s, then
failed Compose idling while waiting for configured destination choices
(`source-download-root-1`); its exact download request was not reached.
Later159 ordinary methods, one guarded method and three cold methods remain
unrun on this pair. The earlier e9 Debrid pass is separate historical evidence.

The raw log SHA-256 is
`06e9c69e70ab31d50bb4e50e18996c04f2d043f74a5af08fd6d51cf45b7c3012`
(`toolchain/logs/native-bounded-editor-20261001-01-ime-source-repairs.log`),
verified against `01-ime-source-repairs/result.json`. Screenshot, diagnostic
and logcat exports all returned0. Nine PNG/JSON captures have timestamps within
this batch. In particular, multiline failure is01:55:48.596UTC, numeric
shown/full-footer are01:57:09.368/01:57:17.183UTC, numeric failure is
01:57:31.788UTC, and completed torrent download is02:07:36.534UTC. The numeric
shown/full-footer pixels confirm the field and footer fit above the floating
keyboard; the failure pixels retain8.5 and editor focus after keyboard dismissal.
The cache export also includes older captures: `text-entry-ime-multiline-shown`,
`source-provider-startup-debrid-failure` and `source-debrid-download-requested`
are not fresh evidence for this pair. Current Debrid stage/thread diagnostics
are in `01-ime-source-repairs/diagnostics/source-debrid-download-steps.txt`.

Owner19486 became
unavailable after terminal e9 tests; this proves lost control, not that QEMU179
died. Read-only POSIX/OFD queries found no writable-image locks. A normal vendor
preflight and ordinary saved-AVD launch succeeded in permanent owner37921 with
independent QEMU17 PGID/SID17. Existing-key boot and prior-pair/data verification passed before installation. No manual lock edit, reset, data/cache deletion,
HOME/key/network/security change or force flag was used. New evidence is under
`toolchain/evidence/native-bounded-editor/`.

At02:27UTC owner37921 was unavailable (`Unknown process id`). The terminal
exports above are intact; the last saved data readback is the01:50:43UTC
post-install checkpoint, not a post-batch verification. That checkpoint retains
database SHA-256
`ae4d76e3dc223dc4d5cf81b8a228a0e60f8663764bed8bd01fb804065168c5a9`
and both normal recovery files absent, matching the pre-install baseline.
Read-only kernel POSIX/OFD queries at02:28UTC found all six saved writable
`.img`/`.qcow2` files unlocked. The prior emulator log ends with a graceful
shutdown notice. These observations do not substitute for a new boot/data
readback. Restart and guest work are held until the coordinator finishes the
next candidate build; the saved AVD, caches and immutable artifacts are retained.

Previous frozen source is `e9ffacc0ceed289e0f05f62e9adbcda33f1810d6`, tree
`46babbdc89377a06836fcd3a05abfcd62cca4ff6`, exported as
`toolchain/runtime-floating-ime`. Its311 JVM tests, lint, all APK builds,
signatures,16KiB alignment,145 route contracts and unchanged-Go guard pass.
Both installed hashes are exact: app
`4fd44d445003b15e92e42b842d7b4004baf9005901caf9d4233d12ba0cceb43f`, test
`795f7005eae77be753053e459c365e9ffa94860d05a38e8e786cf5db29d6b954`.
Both actual ART filters are `verify`. Retained database/recovery checks and cold
signed HTTP200/version3.10.3/exact normal dataDir pass. The adaptive floating-IME
layout and stronger existing IME tests are new; the Debrid change adds bounded
app-owned diagnostics only. The cold Debrid-only repeat passed1/1 in121.595s;
this repeat does not add a unique manifest method. The complete IME/source batch
then finished2PASS/2FAIL in216.254s: both complete source workflows passed, but
both IME methods failed with `IllegalStateException: uncancelled requests present`
in Compose's BringIntoView request queue. Fresh failure diagnostics show the
initial9.5 value, no visible IME and full-height viewport, so the new geometry,
long-draft and Done assertions were not reached. Debrid watchdog steps all
finished below15s in both passing executions; this does not establish the cause
of the earlier be88 idling failure. All exports succeeded. Later159 ordinary,
one guarded and three cold methods remain unrun while this initial-focus
regression is repaired. Exact evidence is under
`toolchain/evidence/native-floating-ime/`; older results below retain their named
artifact boundaries.

Previous frozen source was `be880d209f480b1f7c87760495bb965b182a0c4d`, tree
`9b877d17f519eeb61379085fcb94673afb5653de`, exported as
`toolchain/runtime-native-closeout-installable`. The aggregate passes 311 JVM
tests, lint, both ABI/test packaging, signatures, seven libraries per ABI at
16 KiB alignment, 145 route contracts and unchanged Go. These are build checks.

At 23:41:42 UTC both installed hashes matched the immutable pair: app
`40588dab2895ac426fde0ea92710a6d4993ecb950346e157dcc3524a9bf35f05`, test
`b287ae499f95bea13a3944a2485ca62d3f76275236973205f9a9f7985376ea31`.
Both ART results report `actualCompilerFilter=verify`; this is not speed AOT.
The retained database hash and absence of both recovery files match the
pre-install checkpoint. Cold normal signed status now passes HTTP200 with version3.10.3 and exact
`/data/user/0/app.seanime.tv/files/seanime/data`; returned proof was kept transient.
A normal Library capture shows legible native dark presentation, fully visible
selected Library focus and the new toolbar; the empty collection is retained real
Go data. The first four-method IME/source attempt was interrupted after its first
method's START: zero passes/failures, one incomplete and three unrun. It has no
terminal instrumentation result and is not an application failure. The original
owner became inaccessible; a new exec has a separate network/PID namespace, so
its loopback refusals or process list do not establish that an old owner died.
Read-only POSIX/OFD queries later found no held locks on the saved writable disk
images. Ordinary vendor locking was retained throughout recovery.

The saved AVD completed boot in interactive owner19486, with QEMU179 in its own
process group/session. All bounded public `adb-shell` commands run inside that
same owner namespace. Fresh existing-key authentication, `ro.adb.secure=1`, both
installed hashes, unchanged retained database and absent recovery files passed.
Signed normal status again returned HTTP200/version3.10.3 and the exact normal
data directory. The immediate post-status capture was still a startup frame.
After the finite batch, the same hash/database/recovery and cold signed-status
checks passed again; settled native Library XML and pixels were verified at
`toolchain/evidence/native-closeout-installable/postbatch-20261001/`.
At00:30:53UTC the
interrupted four-method batch restarted under a fresh `20261001-r2` log label,
in independent test process group514. No HOME/key/security changes,
manual lock removal, data reset, cache deletion or reinstallation was performed.
The SDK system image was made sparse only at existing zero extents; its exact
logical length and SHA-256 were verified unchanged. Detailed recovery evidence
is in `toolchain/evidence/native-closeout-installable/interruption-20261001/`.

The earlier `01b18b2c` test install failed because its receiver manifest used the
invalid process name `:external-player`; Android's stale typed parse result
reported a misleading `ParsedActivityImpl` to `String` cast. The current
`:external_player` declaration installs successfully and preserves separate
process/UID semantics. That failed pair was never instrumented. The `503a7e63`
export was never installed. Both artifacts and failure evidence remain intact.

The fresh **be880d20 IME/source batch completed four methods: two passes and two
failures in780.842s**, with no incomplete/error/ignored results. Multiline physical
Back and the full torrent destination Cancel/failure/retry/focus workflow passed.
The real numeric IME is visible with a zero bottom inset: its window begins aty602,
while the editor ends aty640 and the footer spansy672–752. The named failure PNG
confirms overlap; numeric Done was not reached. Debrid progressed through native
provider/mode focus and successful settings/directory responses, then failed
Compose idling while awaiting `source-download-root-1`. Its cause is not established
by that exception. The finite runner stopped after the batch;159 ordinary methods,
one guarded recovery method and three cold Go methods remain unrun on this pair.
Exact raw results, PNG/JSON and diagnostic exports are in
`toolchain/evidence/native-closeout-installable/01-ime-source-repairs/`.

The earlier broader **c5b3a096 checkpoint had47 methods:44 passes and three
failures**. Its results retain that older artifact boundary, including the Library
exact-label matcher repair still awaiting current-pair execution. The refreshed finite
manifest has **164 ordinary methods in 29 bounded batches, one separately guarded
recovery method and three cold isolated Go methods**. The remaining current-pair
runtime gates are pending; there is no full-suite or complete-parity claim.

The earlier e5 raw-media pass covers generated H264, signed range bytes, the
library boundary, native decoding and remote playback/recovery cleanup. It does
not cover scan, matching/import, provider metadata or Go list continuity. That
separate AniList transport gate remains blocked. Changed integrations are
catalogued for the current source; prior failures
remain preserved in the artifact history below.

Earlier artifact-boundary results are cited explicitly in the matrix. Their
historical failure summaries and subsequent fixes remain in the evidence history
below; passing compilation is never substituted for a device result.

| Feature | Native source implementation | Missing/partial scope | Fresh runtime gate |
| --- | --- | --- | --- |
| Startup / navigation | Compose startup/error/retry, first-run app/SAF storage, D-pad rail, native Back/exit, saved destination | d6 had clipped focus edges; f66 semantic bounds and grid/Logs visuals pass. Known-file recovery now passes; content/provider recovery and full physical safe-area coverage remain | 5a90fdfe NOT RUN; historical: PASS c5 corrected signed recreation/status/ping-pong, route/query and background focus. PASS e5 eight other startup checks, including two real Go starts/native Library, remote search/navigation and platform policies. Prior d6/f66 lower-rail/safe-bounds evidence remains |
| Library / discovery | Poster grid and personal status/sort/search collections; anime/manga discovery and airing detail/page return; native matching/retry, selected/directory bulk actions, indexed-file rename/delete and Explorer; Long-safe identities | Large real libraries, actual public/provider metadata and complete artwork/scan workflows remain unverified; unchanged backend country-of-origin gap remains | 5a90fdfe NOT RUN; historical: PASS d6 real empty Library + fixture search/error/retry/detail focus; f66 artwork4/picker4 and grid/rail bounds; 8a33 discovery entry/search/pagination/detail return; 80b74403 full filter/editor request/focus case |
| AniList / MAL | OAuth entry/return; native list status/progress/score/date/delete with sparse payloads and named-account local-list migration; Long-safe media IDs | Sign-in/list-management and metadata refresh remain unverified. Discovery countryOfOrigin is sent by Go list.go but unused by ListAnimeDocument; MUSIC is intentionally excluded. No backend edit made | 5a90fdfe NOT RUN; historical: NOT RUN with live accounts: sign-in/callback/persist/update/logout. Native title-pickers pass fixtures; 8a33 discovery entry/search/page and80b74403 filter/editor tests pass; c5 list14 passes numeric, sparse payload, date, removal guard and Cancel/retry/focus cases; live score/delete synchronization remains unverified |
| Manga | Collection/search/discovery/downloaded screens, providers/chapters, native reader, page/spread/RTL controls, zoom/pan, progress and chapter downloads; local/SAF PDF through Android PdfRenderer | Every desktop bookmark/reader preference and arbitrary local archive case not established | 5a90fdfe NOT RUN; historical: PASS c5 full Reader8, including controls/keyboard-Back/focus, retry/progress and wide/Cover-alone grouping. PASS c5 PDF3 actual renderer/metadata/cache/cleanup. PASS c5 manga preference/entry/refresh/fallback fixtures7. Live provider, actual PDF/SAF entry and arbitrary archive workflows remain |
| Offline anime / metadata | Offline state, tracked titles, metadata download, local progress upload/reconciliation actions | Complete disconnected metadata/artwork and conflict resolution not established | 5a90fdfe NOT RUN; historical: PASS c5 asynchronous metadata acknowledgement/progress/finish and retry fixtures3 with manga queue guard; prior80 account/toggle/sync cases pass. Actual disconnect/reconnect and real offline downloaded entry remain |
| Playlists / continuity | Create/rename/delete, add/remove/reorder episodes, playback, next/previous and continuity coordinator | Full player/plugin equivalence, automatic next and actual-Go scan/play/resume remain unverified | 5a90fdfe NOT RUN; historical: PASS d6 actual Go/SQLite Cancel/create/rename/reorder/complete/delete with readback/cleanup. PASS80b74403 fixture player-return refresh and event-before-database-save completion refresh |
| Extensions / plugins | Native marketplace/filter/review/install retry and long catalog focus return; custom sources/catalog/detail paging; extension config/permissions/repositories/playground; native plugin tray/commands/actions/tabs, global viewport broadcast and Long-safe routes | Arbitrary HTML/CSS/script plugin rendering is intentionally absent; every supported declarative component and event contract requires verification | 5a90fdfe NOT RUN; historical: 309bc341 global navigation/Back/reload PASS;8a33 plugin actions/tabs3 and typed-target/main-thread2 pass. 80b74403 global tray/command2 also pass. Live extension updates/config and arbitrary components remain |
| Streaming | Local/raw Go playback and provider episode/source selection; corrected wide/narrow torrent/debrid source chooser; special-episode IDs and Long-safe source/coordinator/recovery routes | Advanced source-ranking/filtering and every provider-specific edge case not established | 5a90fdfe NOT RUN; historical: e5d7d33f actual Go raw-file range/native playback PASS. Scan/import/matching, authorized live providers, expiring URLs and source refresh remain |
| Native playback | Real Media3 surface/coordinator, audio/subtitles/libass/translation, speed/media keys/recovery and conversion; native More external-player entry with source-bound hosting/grants and cancellable preflight; source/generation-bound termination and stale-checkpoint rejection |e5d7d33f post-Skip focus repair and strict lifecycle case pass. Live translated captions, broad codecs and content/provider recovery remain unverified | 5a90fdfe NOT RUN; historical: PASS d6 fresh HUD; f66 HLS/ASS/PGS2, player7/8 and direct saved-file recovery;8a33 repaired autoplay;80b74403 matching-source/caption-disable command case. e5d7d33f strict Skip/caption wire, paused seek and Play-focus restoration case passes |
| Advanced player | Native controls, libass, event-fed HLS ASS/PGS, translation adapter and twelve multipass Anime4K CNN/GAN presets | Physical GPU, HD/4K memory/throughput and live-provider fidelity remain unverified | 5a90fdfe NOT RUN; historical: PASS f66 all12 supported presets at8×8 and HLS/event2; older pair Medium reference/reconfigure and memory/HDR passthrough. Valid manual Picture dialog onf66 is distinct from its raced automated capture. No physical performance claim |
| Transcoding | Existing source conversion APIs; signed FFmpeg source and pinned x264 rebuilt for both ABIs | Physical hardware encoder support/throughput and thermal behavior unverified | 5a90fdfe NOT RUN; historical: PASS e5: two actual installed FFmpeg/ffprobe encode/probe/decode/frame/seek cases. Physical 1080p/4K hardware remains |
| Downloads / torrents | Native torrent details/peers/trackers/file priorities/session limits, rename/move review, debrid folder selection; manga queue; auto-rule batch review/partial retry and failed-ID cleanup; owned metadata import/export previews | Actual provider/client queues, real completed files/SAF copies, advanced rules and complete per-provider behavior remain unverified | 5a90fdfe NOT RUN; historical82b48c7e PARTIAL: torrent source Cancel/exact-folder/server-failure retry/focus PASS; Debrid FAIL at destination-dialog Compose idling, after provider/mode focus and directory200. Fresh narrow provider and scrolled Back captures show bounded selected focus on dark native presentation. Remaining download methods unrun. Historical c5 SourceDownload2 and e5 ExistingDebrid/ActionRow passes keep their original artifact boundaries |
| Nakama | Native connection/settings, room/watch-party controls, chat, player event/command coordinator | Participant/host edge cases and all plugin synchronization behavior not established | 5a90fdfe NOT RUN; historical: 80b74403 native initial/refresh/reconnect advertisement fixture PASS. Two authorized peers, play/pause/seek, reconnect, leave and source transfer remain |
| Settings | Labeled typed scalar/array/enum controls, booleans, folder/account actions; unused legacy settings hidden | Uncommon structures still use optional JSON; every field/password IME and full roundtrip surface require coverage | 5a90fdfe NOT RUN; historical: PASS d6 actual Go typed save, separate-client read, unrelated-field preservation, invalid-write rejection and rollback. PASS80b74403 account-action/confirmation3; live sign-in remains unrun |
| Logs / reports | Native log filtering, issue-report draft/retry/acknowledgement, metadata exact-byte export/import preview/confirm and diagnostic profile export callbacks | All profiling/download formats and very large report memory behavior not established | 5a90fdfe NOT RUN; historical: PASS c5 native category/description/draft/retry and acknowledged export callback. Actual real-report creation and document-provider bytes remain unrun |
| Android integrations | SAF persistence/journal/regrant; native external-player chooser entry and foreground source-bound stream hosting; exact temporary document grants, OAuth and managed APK cache/update installer | Real USB, separate-player lifecycle/range delivery and release-signing upgrade require runtime gates; third-party players remain unverified | 5a90fdfe NOT RUN; historical: PASS c5 real signed Go directory-selector/SAF readback and external adapter delivery/immediate fixture return. PASS e5 four owned-provider SAF/journal cases. Native external-player entry/sustained streaming, physical USB and same-key release update remain |
## Fresh evidence

| Check | Result | Exact scope / artifact boundary |
| --- | --- | --- |
| 82b48c7e build / installed pair | PASS build, installation and pre-batch normal-state gates | 311 JVM tests, lint, all APKs, signatures,16KiB alignment,145 contracts and unchanged Go. Both installed hashes exact; actual ART verify. DB hash/recovery absence and signed normal dataDir passed before the batch; no post-batch state readback is claimed |
| 82b48c7e complete IME/source batch | PARTIAL,1 PASS/3 FAIL803.976s | Complete torrent workflow passes. Multiline IME wait times out although later failure capture shows Gboard. Numeric geometry/Done pass, then physical Down fails Cancel focus. Debrid fails Compose idling at destination choices. All exports succeed; later159 ordinary+1 guarded+3 cold methods remain unrun |
| e9ffacc0 build / installed pair | PASS build, installation and normal-state gates | 311 JVM tests, lint, all APKs, same-key signatures,16KiB alignment/145 contracts/unchanged Go. Both installed hashes exact and ART verify. Retained DB/recovery unchanged; cold signed HTTP200/version3.10.3/exact normal dataDir |
| e9ffacc0 Debrid-only repeat | PASS1/121.595s; not extra unique coverage | Complete fixture destination and exact request/focus path; all watchdog steps below15s, no15/30s snapshot fired. Production destination dialog unchanged |
| e9ffacc0 complete IME/source batch | PARTIAL,2 PASS/2 FAIL216.254s | Both source flows pass. Both IME methods fail BringIntoView queue cancellation before input-method visibility/geometry; stronger IME acceptance remains open. Fresh failure PNG/JSON and logs exported; cached be88 shown-state images excluded |
| be880d20 build / installed pair | PASS build, installation and normal-state recovery | 311 JVM tests, lint, signatures/ABI alignment/145 contracts. Both installed APK hashes verified23:41:42UTC and again after same-namespace recovery; actual ART verify. Retained DB hash/recovery absence unchanged. Cold signed HTTP200/version3.10.3/exact normal dataDir pass. Feature results are separate below |
| be880d20 fresh IME/source | PARTIAL,2 PASS/2 FAIL780.842s | Physical first Back and full torrent destination/Cancel/retry/focus pass. Numeric floating-IME overlap is reproduced in geometry and pixels; Debrid destination fails Compose idling after directory200. All exports succeed; later159 ordinary+1 guarded+3 cold methods remain unrun. No live provider/account or hardware performance claim |
| 01b18b2c build / install | BUILD PASS; TEST APK INSTALL BLOCKED | 310 JVM tests, lint, signatures/ABI alignment/contracts pass. App SHA verified after successful install; test manifest parse ClassCastException stops paired execution. Existing-key transport and retained DB/recovery preflight pass; no runtime test result yet |
| c5b3a096 provider source downloads | PASS,2 methods169.966s | Callback-on and callback-off provider layouts both contain selected row; torrent Cancel/failure/retry and Debrid chosen-folder exact requests pass. Generated HTTP responses; screenshot is not clean app-theme/full-focus proof |
| c5b3a096 native list editors | PASS,14 methods566.94s | All four prior ImeAction Default failures pass; exact sparse payloads, Cancel/return focus, offline guards, date validation/retry/partial preservation and simulated date clearing. Main/rating/date screenshots reviewed; no live-account mutation |
| c5b3a096 repaired integrations | PASS,3 methods156.576s | Real Go signed status and same-client ping/pong through recreation, native route/query/background focus; exact external intent/immediate return; signed directory-selector and persisted SAF readback |
| c5b3a096 complete manga reader | PASS,8 methods306.638s | Real generated PNGs, remote controls/IME-Back/focus, retry/progress guards and portrait/wide/Cover-alone grouping. Current PDF, provider/offline/SAF entry remain separate |
| c5b3a096 manga preferences / entry | PASS,7 methods310.301s | Sparse per-provider language/scanlator edits, failure/retry, matching event, offline independence, downloaded fallback and explicit refresh/confirmed mapping reset. Generated HTTP fixtures; two modal screenshots reviewed |
| c5b3a096 transfer / configuration / report | PASS,5 methods256.82s | Queue terminal-only clearing, metadata acknowledgement/event/retry, config load/save draft retry and report category/description/acknowledged export callback. Actual document export bytes and live providers remain separate |
| c5b3a096 Library / local-list confirmation | PARTIAL,2 PASS/1 FAIL163.284s | Deletion confirmation/failure/retry/focus and named-account upload confirmation pass. Matching stops at exact text matcher: expected9 vs actualEpisode number:9; later retry assertions not reached. All fixture-backed; no retained file deletion or live account upload |
| c5b3a096 real IME checks | FAIL,2 methods81.428s | Both stop waiting for initial imeTop()!=null; OS logs keyboard onShown for both. No Back/Done/footer assertion reached; visibility/geometry probe diagnosis pending |
| e5d7d33f raw Go/native media | PASS,1 method110.509s | Actual Go signed206 range and library-boundary404, real H264/native frame, remote seek/play/pause and owned recovery cleanup; strict cold normal-state restoration verified. Excludes scan/import/provider metadata/continuity |
| e5d7d33f reader / Skip / row bounds | PASS,6 methods | Complete reader1, same-frame+strict Skip2, ActionRow+Debrid3. Reviewed full focused edge pills and dark Debrid image |
| e5d7d33f storage / media tools | PASS,6 methods | Four owned-provider SAF/journal/regrant methods and two real bundled FFmpeg/ffprobe encode/probe/native-frame/seek cases. Physical USB/performance remain unrun; old unsigned selector helper held |
| e5d7d33f build / installed pair | PASS,175 JVM tests,lint,ABIs,test APK | Same-key signatures,seven libraries/ABI16KiB,129 routes,unchanged Go; both installed hashes verified19:56:02; ART verify only |
| 80b74403 aggregate / installed pair | PASS,168 JVM tests,lint,both ABI packaging | 129 contracts,unchanged Go,seven libraries/ABI16KiB ELF/ZIP,same debug signature. Both installed hashes verified19:25:06; ART verify only |
| 80b74403 account / offline / refresh / playlist | PASS,9 methods235.157s | Named/confirmed account actions; local/offline guards and fresh-status recheck; explorer invalidation-before-read; playlist return/event refresh. All fixture-backed; live sign-in/sync remain unrun |
| 80b74403 global plugin presentation | PASS,2 methods127.788s | Global tray isolation/Back restoration and command owner/unrelated-Close behavior; native dark tray screenshot reviewed |
| 80b74403 playback routing | PARTIAL:2 PASS/1 FAIL | Nakama advertisement/reconnect and matching-source commands pass; Skip/caption wire and paused seek pass before post-Skip Play focus fails at line368 |
| 80b74403 filter / editor regression | PASS,1 method267.256s | Real remote Year opener; Year/Score/custom-tag Save/Cancel; exact combined request; preserved draft and restored focus; actual modal screenshot. Closes the prior year-input failure without timeout/display relaxation |
| 80b74403 downloads/provider UI | PARTIAL: ExistingDebrid2 + capability1 PASS; SourceDownload2 FAIL | Existing transfer folder/Cancel/retry/removed-item and capability recheck assertions pass. Source tests cannot find initial selected-provider label before any mutation. No live provider/download-byte claim |

| 309bc341 / production39366704 aggregate | PASS build/JVM/lint/alignment; focused runtime result recorded below | 166 JVM tests, zero failures/errors;129 literal routes; unchanged Go; both ABIs/seven libraries16KiB ELF/ZIP and same-certificate signature checks pass. Source boundary is exact; no runtime completion claim |
| 8a33 real-Go scan/import/play/resume | BLOCKED: public DNS transport | Isolated server/setup succeeds; graphql.anilist.co lookup fails before media generation. All fixture evidence retained; process stopped and signed normal dataDir verified. No media-flow pass |
| 8a33 aggregate / contracts | PASS, 138 JVM tests, lint and both ABI builds; 125 literal routes | Source `8a33adf7`; no Go changes; signatures and seven libraries/ABI 16KiB ELF/ZIP checks pass. Device gates below remain separate |
| f66 native contract scanner | PASS, 15 checks / 124 literal routes | Native presentation boundary, identity/origin headers, loopback policy and unchanged Go; no end-to-end parity inference |
| Scanner self-tests | PASS, 7 tests | Scanner behavior and rejection of retained old presentation |
| Real gomobile / FFmpeg runtime build | PASS, build evidence | Real ARM64+x86_64 AAR, signed FFmpeg 8.1.3 + pinned x264, Android compatibility binding; no core/API/schema changes |
| FFmpeg signature negative checks | PASS | Authentic archive accepted; corrupted archive and wrong fingerprint rejected |
| f66 build / JVM / lint | PASS, 127 JVM tests, zero failures/errors | Aggregate 3m7s; final HLS-fixture packaging 34s; compilation is not device completion |
| f66 APK signatures / native contents / 16KiB alignment | PASS, both ABIs | Seven native libraries per ABI; exact hashes in candidate section below |
| Real setup / Library / server restart | PASS, 1 test on d6 | Clean-fixture state after ordinary player dismissal; POST /start 200, two Go starts and stops, native Compose/no-WebView/search/rail assertions |
| Native Library / platform dialogs / fresh HUD | PASS, 8 tests on d6 | Empty/search/error/retry/detail focus; four platform-dialog cases; fresh HUD, selected/reopened Audio dialog, media keys and Back restoration; six fresh screenshots |
| Real playlist / typed-settings integration | PASS, 3 tests on d6 | Actual Go/SQLite, native dialog Cancel/create, playlist CRUD/reorder/completion and typed settings with unrelated-field preservation, invalid-write rejection and verified cleanup/rollback |
| Local-file playback recovery | PASS, direct manual scenario on f66 | Exact originally failing HLS snapshot/file restored through MainActivity; native player opens, fresh checkpoint remains1100ms/paused across3 reads, stable UI0:01/0:04, normal Back cleanup restores prior absence. Earlier d6 failure retained below |
| Focused-item visual bounds | f66 semantic bounds and grid/Logs visuals PASS | Focus8 asserts lower-row/footer and many-track/dialog bounds. Fresh grid and manual final Logs captures show full rounded focus borders; Logs bounds [96,865]–[432,961] remain above footer y974 |
| Direct native libass | PASS, 1 test on older dd77/a87 pair | Real animated ASS glyphs, seek and lifecycle; the separate f66 HLS/event result is recorded below |
| Native PDF engine | PASS,c5 updated3 tests54.456s | Real PdfRenderer pixels/page metadata, serialized ten-page work, bounded raster cache/revisit, invalid/closed-session cleanup. No actual PDF-picker/native-reader UI claim; older dd77/a87 evidence retained in history |
| Anime4K GPU engine | PASS all 12 presets on f66 at 8×8; older pair reference/fallback passes retained | All supported non-Off graphs compile/render opaque nonempty pixels at correct sizes; GAN 3×/4× CPU-reference checks pass. Older dd77/a87 Medium-reference/reconfigure and memory/HDR tests pass. Physical/HD/4K throughput and allocation remain unverified |
| Android host queue | PASS, 1 test on d6 | Main looper dispatch remains responsive during deliberately blocked runtime initialization; dependent startup stays ordered |
| f66 HLS/event subtitles | PASS, 2 tests | Strict zero-TEXT master fixture; authored ASS movement/seek, PGS/crop, lifecycle/recreation, Anime4K selection, oversized-header handling and queued-generation invalidation. Subtitle planes captured; picture-dialog capture raced and is not visual dialog proof |

| f66 artwork / title-picker fixtures | PASS,8 methods | Artwork fallback3, offline-route HTTP→Coil pixel1, title-picker4; fixture scopes are distinct from actual disconnected/provider artwork |
| Full native instrumentation / minimum API / physical TV | NOT COMPLETE | Focused batches do not replace full suite, API 23/both ABI/16KiB device, USB and physical decode/performance gates |
| OAuth / live providers / Nakama peers / release upgrade | NOT RUN | Requires authorized accounts/peers, hardware where relevant and persistent release signing |

The source contract scanner checks literal method/path compatibility, not JSON
payload correctness, dynamic API actions, generated Compose semantics, remote
focus behavior, or provider functionality. A passing row must not be promoted
to end-to-end feature parity.

### 503a7e63 immutable closeout candidate — superseded, never installed

Export `toolchain/runtime-native-closeout`, source
`503a7e63bdbe76d268dfd9dd3ae5897dd97c0a03`, tree
`01cf4a7f6ec0d593ed074b9d7b5efc1b7f53975c`.

- x86_64 app: `4c289c639a70ce90302a61ca7fc19d99b4a94e3915b21eebeb4d3d3226660edd`
- instrumentation: `c3e0fb4f58758e7861f7fb79d40ad5f0e37f0ace699cafad15cdc6cb30fc4183`
- ARM64 app: `e580fc608abe3ad390ba53c231e56c81c2129188a230e404d6172acab6d9b6f3`

The aggregate passed300 JVM tests with zero failures/errors/skips, lint,145
contracts and unchanged-Go checks. V1/V2 signatures use the same certificate;
seven native libraries per ABI pass16KiB ELF/ZIP alignment. The finite frozen
plan is [the next-candidate manifest](2026-09-30-next-native-test-manifest.md):
160 ordinary methods in28 bounded batches and three separate cold isolated kinds.
None is counted as a runtime pass merely from source/compilation.

At22:46 UTC one ordinary saved-AVD launch passed normal ownership checks in a new
supervised owner session. Original display/data options were retained; no locks
were removed. ADB verification then aborted before connecting because the host
could not create `/home/agent/.android` on its read-only filesystem. The verifier's
`BOOT_TRANSPORT_TIMEOUT` line follows that host config error; it is not evidence
of a guest boot timeout. Existing task-local key metadata is present, with no key
content read or replacement generated. No HOME override, outside-root link, tool
patch, APK install or new runtime test has occurred. Supported host configuration
must be restored before saved-state/hash checks and the immutable pair install.

### c5b3a096 immutable native-workflow candidate — completed selected batches

Export `toolchain/runtime-native-workflows`, source
`c5b3a096edde4bdda86fa83ef169d9f90d88916f`, tree
`97f259b6acb128b6379b48c6e8d0edb286e62390`.

- x86_64 app: `192d3d5f3a5d8747e6c43504a652e2af3680332fcaf6884e2214cb23747e418a`
- instrumentation: `39b0ebe6fba2ed205b84f4b29a3e2ed51508f9fb6bc1d9120be64103b15eb18d`
- ARM64 app (build checks only): `6c4ba2af62d2ead836fbd18f6d6fe412cbcb2cb38d1c35c4443fee593840f861`

The aggregate executed 226 JVM tests with zero failures/errors in attempt r2;
final r3 reused those results and passed lint, both ABI and instrumentation APK
packaging in 1m32s. Earlier compile/import and test-only API annotation failures
were corrected; exact attempts are retained in the export's build-validation.json.
V1/V2 same-certificate verification, seven native libraries per ABI, 16KiB ELF/ZIP
alignment and 136 source contracts pass with unchanged Go. These are build gates,
not runtime acceptance. No-streaming update started 20:48:15 UTC on the retained AVD; both installed
hashes matched before targeted testing began at 20:50 UTC. ART requested and
actually used `verify`, with app dex2oat wall time 20.350s; no speed AOT claim.
SourceDownload2 passed in 169.966s. Callback-off Debrid and callback-on torrent
both report seven lazy items including selected key `fixture` at 1920×80. Torrent
records loaded count1, interval key, item body and size; strict initial visibility,
Cancel, failed write/retry, chosen folder and exact payload assertions all pass.
Evidence is `toolchain/evidence/native-workflows/source-download/` and
`toolchain/logs/native-workflows-provider.log`. The source screenshot shows the
provider and download-request status but uses a white isolated fixture background
and clips the focused Download release pill at its left edge. It is not clean
app-theme or full focused-outline acceptance.

IME2 failed in 81.428s. Both methods time out waiting for `imeTop()!=null`, at
NativeTextEntryImeTest lines52/32, before Back, Done, footer bounds or Save checks.
Android logs multiline `onShown` at20:54:05.449 and numeric `onShown` at20:54:44.473,
with numeric DONE EditorInfo. This supports investigating the test's visibility/
geometry probe rather than claiming the keyboard never opened. Exact timeline is
`toolchain/logs/native-workflows-ime-timeline.txt`; full logcat is preserved.
The later manual screenshot/dumpsys request occurred after teardown and cannot
prove the active dialog layout. List14 subsequently passed in 566.94s, closing all four e5 semantic-IME-action
failures for the tested flows. It also verifies exact sparse progress/rating/date
payloads, no-op and Cancel non-mutation, draft retention on failure/retry, each
opener's focus, offline removal guards, full calendar validation, preservation of
partial dates and explicit simulated date clearing. The three fresh main/rating/
partial-date PNGs are in `toolchain/evidence/native-workflows/list-dialogs/`.
Main Status and date Year now have full focus outlines and visible footers;
rating is captured before keyboard appearance and retains that visual limit.
This is generated HTTP fixture evidence, not live AniList synchronization.
The repaired integration batch passed all three methods in 156.576s. Recreation
retains ID/origin/authentication, obtains a renewed proof, reaches actual signed
Go status, completes the same-client socket ping/pong, and then passes every
route/query/background/focus assertion. Corrected BROWSABLE fixture delivery and
immediate native return pass. The real SeanimeApiClient signed status→directory-
selector POST passes exact path/existence/child and persisted SAF adapter checks.
This closes the old e5 proof-equality, external-filter and unsigned-helper gates.
The instant same-process external receiver does not prove a native user entry
point, sustained streaming or a real external application's background lifecycle.

The first four stages and exports completed: 19 passes/two initial IME-probe
failures across 21 methods, no incomplete/error/ignored results. Exact per-method
runner accounting and hashes are in
`toolchain/evidence/native-workflows/focused-test-results.json`. Only the preserved
emulator remained running while device jobs were held at21:09 UTC. With builds
explicitly deferred, full Reader8 and PDF3 resumed at21:18 UTC on the same c5 pair.

The queued priorities are SourceDownload2 with layout/state diagnostics, real
IME2, list14, signed recreation, external-player fixture and signed SAF selector.
Remaining new workflows and separately guarded raw-Go/PDF checks follow. Earlier
e5 outcomes remain historical evidence and are not promoted to this artifact.

The complete changed Reader8 class then passed in 306.638s, with all exports
complete before the separate PDF stage. Its control flow, keyboard-first Back,
jump restoration, list/image retry, automatic/final-image progress guards and
portrait/wide/Cover-alone grouping pass. Fresh two-page and wide-page images with
artifact metadata are under `toolchain/evidence/native-workflows/reader/`.
PDF3 then passed in 54.456s, including current page metadata/double-page assertions,
real renderer pixels, serialized work/cache bounds and invalid/closed-session
cleanup. Logs are `toolchain/logs/native-workflows-pdf.log` and its full logcat.
Current aggregate accounting is 30 passes/two initial IME-probe failures across
32 completed methods. These results do not close live-provider, actual PDF/SAF
entry or arbitrary archive workflows. With builds still deferred, independent
manga preferences/workflows, transfer/report and Library/migration batches began
21:26 UTC on the same c5 pair.

Manga preferences/workflows then passed all seven methods in 310.301s. Four
preference cases cover sparse language changes across providers, failed scanlator
Save/retry, relevant preference-event reload and offline independence. Three
entry cases cover offline downloaded chapters without online lookup, unavailable-
provider fallback and explicit refresh/confirmed mapping reset. The language and
retry/error modals are visually reviewed in `evidence/native-workflows/manga-preferences/`;
the underlying isolated root is not app-theme proof. This brings completed selected
accounting to 37 passes/two IME-probe failures across 39 methods. Transfer/report
and Library/migration are still running against the same immutable c5 APKs.

Transfer/configuration/report then passed all five methods in 256.82s. The
three transfer cases retain fresh queue-state guards, rejected/status-failed
retry and acknowledgement versus finish-event distinctions. Extension config
retains its draft through load/save failures. Report category/description/retry
and the export callback after acknowledgement pass; actual system document-
provider byte export is not tested by that callback. Four dark-screen captures
are reviewed in `evidence/native-workflows/transfer-report/`; the extension-card
image has no clear focus indicator and cannot prove post-save focus restoration.
The final Library/migration batch completed in 163.284s with two passes and one
failure. Deletion requires explicit confirmation, retains the failed state for
retry and restores Refresh focus; local-list upload names its account and needs
its own confirmation. These fixture passes do not delete retained user files or
upload an actual account. The matching test stops at line36 because
`assertTextContains("9")` uses whole-string matching by default while the focused
node contains `Episode number: 9`. The draft is visibly retained at that point;
later server-failure/retry assertions are not reached. A precise full-label test
fix exists in later source and requires a new immutable APK.

Final c5 accounting is **44 passes/three failures across47 executed methods**,
with no incomplete/error/ignored results. Raw statuses, exact hashes and all
terminal logs are consolidated in `evidence/native-workflows/focused-test-results.json`.
Library/migration exported its TAR/logcat successfully; its failing method never
reached the fresh screenshot call, so no new capture is claimed.

At22:14 UTC, resuming the sole owner PTY returned `Unknown process id 1689`.
The new isolated command namespace has no visible QEMU/ADB or reachable emulator
ports; that cannot establish the old namespace process's death. Saved AVD disk
files remain present, with the userdata image last written21:41 UTC. No reset,
cache deletion, lock removal, restart or install was performed during diagnosis.
Live guest health and installed hashes are unverified until an ordinary emulator
launch after the next aggregate, honoring its normal ownership-lock checks.

### e5d7d33f immutable list/raw-media candidate — completed selected batches

Export `toolchain/runtime-list-raw-media`, source
`e5d7d33f135d14e4d56afe2bc4aefcf06f2c7e50`, tree
`280790d3b4aa403ababf85e185d1eef4ed147f4f`.

- x86_64 app: `b66e6018868b4049594e98b17a93da597f9eafe6deb5c07d9b2f28bbcbbbbe2d`
- instrumentation: `9a00aa4d793dc2457b7a37416b50e4a44ac542d9d0e5a9b2eee2b0b726bda396`
- ARM64 app (build checks only): `8c222ca3a94b5b759ec838e7c4ef72d1770be267c6ce94a603db410807058ba1`

Aggregate passed 175 JVM tests, zero failures/errors, lint and both ABI/test APK
packaging in3m19s. Signatures/same debug certificate, seven libraries/ABI,16KiB
ELF/ZIP and129 API contracts passed with unchanged Go. No-streaming install and
both installed-hash readbacks completed19:56:02. Actual ART filter was `verify`,
21.218s app/8.618s test. Original1920×1080/density320 AVD data was retained.

**Actual unchanged-Go raw-media flow: PASS,110.509s.** The one opt-in method ran
alone in a cold process with `isolatedNativeGoRawMediaFixture=true` and
`freshInstrumentationProcess=true`. Anime4K was already Off; both recovery files
were absent and that prior state was recorded. Verified manifest kind
`native-isolated-go-raw-media-v1`, root
`files/native-go-fixture-c0d46447-ad0a-46d7-9ab4-65d4cf7f772b`, and exact owned
`library/Generated owned raw video.mp4`. The real bundled FFmpeg generated H264;
real Go GET `/api/v1/mediastream/file` returned206 with the exact first256 bytes
and Content-Range, while an owned file outside the configured library returned404.
Real MainActivity's signed identity reached the media header provider and
coordinator; Media3 rendered160×90, exercised remote timeline seek/play/pause,
and wrote the owned recovery source paused at15671ms. Normal Back cleared that
recovery. No scanned/matched library records were injected.

Afterward the test process was force-stopped before cleanup/restoration. All
fixture files were retained and exported; the prior absence of both recovery
files was verified. Cold normal MainActivity returned signed status with proof
present, version3.10.3 and exactly
`/data/user/0/app.seanime.tv/files/seanime/data` at19:58:52. Identity values were
not logged; verification used only the local ADB-forwarded socket. Both
`SAFE_NORMAL_STATE_VERIFIED` and `RAW_MEDIA_ASSERTIONS_PASS` were recorded19:59:03.
Evidence/manifest/protocol ledger and a reviewed native HUD image are under
`toolchain/evidence/native-list-raw-media/isolated-raw-go/`. Scope deliberately
excludes scan/import/matching, public metadata and Go continuity; no retry or
workaround of the blocked AniList flow occurred.

**Focused UI/player queue:** SourceDownload2 failed in75.064s. New diagnostics
show provider/settings HTTP200 bodies complete by about3s, then an empty provider
row area in the semantic tree (not an offscreen selected row). Failure images,
HTTP completion logs and full trees are under `source-download/`; production
versus fixture/response handling remains under diagnosis. No same-pair retry is
being used to hide this failure.

The complete reader controls method passed151.77s, including direction/spreads,
zoom/pan, jump, keyboard-first Back, dialog-second Back, restored page focus,
mark-read payload and final dismissal. Both Skip tests passed59.556s: immediate
Play focus before a same-frame interval change, plus the unchanged strict
coordinator/HUD/caption wire test. These close the previous reader selector and
Skip focus gates for the tested flows.

List entry dialogs finished6/10 in326.443s. The four failing methods are
`failedMangaRatingSaveRetainsTheExactDraftForRetry`,
`numericEditorSavesOnlyDraftUntilTheMainEditorIsSavedOrCancelled`,
`cancellingNumericEditorsRestoresEachOpenerAndUnchangedSaveWritesNothing`, and
`numericEditorCommitsExactProgressAndRatingWithUntouchedFieldsOmitted`.
All fail at `editNumber:153 / performImeAction` because the focused editable node
reports `ImeAction=Default`; later Save/Cancel/restore assertions in those methods
are not established. Main and numeric-dialog screenshots were
captured read-only and labeled visual-only in `list-dialogs/`. The numeric keyboard
overlaps lower actions; that image is not simultaneous unobscured-action proof.

ActionRow plus existing-Debrid passed3/3 in210.341s. First/last/automatically
scrolled focus bounds and pixels pass; reviewed first/last screenshots show full
rounded focused pills. Debrid's corrected dark fixture image shows its full
Download files focus, closing the old clipping concern for those tested rows.
Images are under `action-bounds/`. The first focused queue therefore totals
13 passes/six failures across19 cases. Broad startup finished8/9 in252.991s. Real native/no-WebView Library with two
Go starts/stops, D-pad destination/exit, hardware letters/cursor focus, launcher
metadata, OAuth resize, canonical callback origin, cleartext policy and managed
update-cache rejection pass. Recreation stops at the old proof byte-equality
assertion; the same client ID survives. Read-only diagnosis confirms expected
renewal: every response issues a new HMAC proof with Unix iat/exp
(`routes.go:109 → client_identity.go:50 → util/hmac_auth.go:48–53`). The same ID
reconnects at20:23:34 with no Go reinitialization during recreation; shutdown at
20:23:50 is test cleanup after its failed assertion. No production authentication
change is justified by this. The replacement test will check proof presence,
live signed status and same-client socket ping/pong while retaining every UI
restoration assertion. Those later UI assertions remain unverified on this run.

The four valid SAF adapter methods passed in10.362s against the owned test
provider: reads/writes/list/removal, revoked/regranted access, abandoned writes,
and journal retention until a partial copy can be removed. This is not physical
USB evidence. The old directory-selector helper was intentionally held because
it omits native Origin/identity headers; its signed-client replacement is unbuilt.
MediaTools passed both methods in 55.674s: installed native executable paths,
actual FFmpeg encoding/ffprobe output, Media3 rendered frame, decoding and seeking.
This is software-emulator functionality, not hardware throughput evidence.

External-player delivery failed in 43.172s at
`AndroidExternalPlayerTest#configuredPlayerSchemeLaunchesAndReturnsToNativeCompose`
with “Android did not deliver the configured player link.” Exact logcat at
20:32:50.136 UTC shows the production ACTION_VIEW/CATEGORY_BROWSABLE request for
`seanime-test://play/...` returning Android result −91, with no debug observer
Activity start. The debug filter declares only DEFAULT, so it cannot resolve that
intent. A fixture-only BROWSABLE declaration and exact resolve precondition are
pending; production routing is unchanged. Return-to-native assertions were not
reached. Full logcat is `toolchain/logs/native-list-external-player-logcat.txt`.

All selected e5 batches and exports are terminal. Raw runner status blocks yield
**27 passes/eight failures across 35 executed methods**, zero incomplete/error/
ignored. The held SAF helper is not counted. Per-method outcomes, exact hashes,
terminal summaries and logs are in
`toolchain/evidence/native-list-raw-media/focused-test-results.json`.
The fresh real-Go Library image captured 20:24:26 UTC is in `startup/` under that
evidence directory; its full Library focus, legible dark UI and connected status
were visually reviewed. Generic forms and remaining new workflows wait for the
next immutable shared-editor build. Device jobs were held at 20:38 UTC while the
same warm AVD and all retained data/caches remained intact.

### 80b74403 immutable native-editor candidate — prior device run

Export `toolchain/runtime-native-editors`, source
`80b744039d45e79034dabf65ca31222698e0da2b`, tree
`c86de6230af076c38750c8170a5ae64bde0d3d8a`.

- x86_64 app: `becb4a5d7fec44e7d19227b9d564853c9c30831e3cec5afe930f786c4004aef7`
- instrumentation: `5d43babccfc5c4c5d029505407f8247fd815d380de9f1b1073bb85c538bbd1ba`
- ARM64 app (build checks only): `34afe51a351bc6163046ebe34f4bdcd455060a56b4a0655e3b05a49a93dacef2`

Aggregate: 168 JVM tests, zero failures/errors, lint and app/test packaging passed
in 3m07s. Both ABI signatures/same debug certificate, seven native libraries/ABI,
16KiB ELF/ZIP alignment and 129 literal API contracts passed, with unchanged Go.
The emulator stayed alive while device jobs were held during this build.
No-streaming replacement installation began 19:23:34 and installed-hash readback
plus ART `verify` finished 19:25:06. ART app/test times were 17.823s/5.947s;
no speed AOT claim. Original display and retained data remain unchanged.

The first queue selects the repaired filter method and five new download/provider
cases (`NativeSourceDownloadTest`2, `NativeExistingDebridDownloadTest`2,
`NativeDownloadCapabilitiesTest`1). The planned reader-controls method is explicitly
not invoked: both its title and underlying opener have Dialog ancestors because
the reader itself is a Dialog. The next test source excludes the opener tag while
preserving every IME/Back/focus/progress assertion. This is a known test-selector
gate, not a runtime reader failure or ignored-test pass. `native-editors-reader-controls.skip`
records the held invocation. The repaired filter passed at 19:29:49 UTC, `OK (1 test)`, 267.256s. Its real
Down/Center Year opener, initial editor focus, typing/Save, reopen/Cancel and draft
preservation all pass, as do custom-tag/Score editors, exact Apply request fields,
reset/Cancel non-mutation and restored focus. No timeout or display relaxation
was used. The final diagnostics and visually reviewed modal screenshot are in
`evidence/native-editors/`; the screenshot visibly shows Highest rated, Airing,
Spring, 2026, TV series and Action with full focused Sort bounds.

At 19:30:00 the reader invocation recorded its planned hold without starting
instrumentation, and the five download/provider cases began. Both SourceDownload
methods time out waiting for `✓ Fixture provider` before Find sources or any
mutation (lines26/60→awaitText:80). Their initial data/selection/visibility cause
is not yet established. The batch finished at19:34:00 UTC in223.041s: three passes and two failures.
Both existing-Debrid folder/Cancel/retry/removed-transfer cases and the
client-specific capability refresh/recheck case passed. Only the two
SourceDownload initial-provider waits failed.

The subsequent routing batch passed two of three cases in87.131s: Nakama initial/
refresh/reconnect advertisement and matching-source native commands, including
caption disable/source isolation, pass. Skip/caption exact wire assertions and
paused intro skip to3000ms pass, then the strict Play-focus assertion fails after
seeking to7000ms and showing Skip ending (LifecycleTest:368). The next source
transfers focus before seeking to cover a coalesced interval update; it is not
part of80b74403.

Global plugin presentation passed both methods in127.788s at19:38:58: tray-only
content and Back restore the opener; global commands retain their owner and
ignore unrelated Close. Its dark native tray screenshot is visually usable.
The existing-Debrid screenshot uses a white Activity background in an isolated
FeatureScreen fixture and is not valid app-theme proof; its first focused action
appears clipped on the left. Source review identified a future action-row padding
change. The original image is preserved with a review sidecar, not edited.

Accounts/offline/explorer/playlist passed all nine methods in235.157s at19:44:40.
The three named/confirmed account-action guards, local/offline restrictions,
fresh-status recheck after disconnection, connected toggle/sync, explorer cache
invalidation-before-read, player-return playlist refresh and event-before-DB-save
completion refresh all pass. These are generated HTTP/event fixtures, not live
account/provider synchronization. All device jobs then stopped before the next
aggregate. Current accounting is17 pass/3 fail/1 held before execution across21
selected cases; no full-suite result is claimed.

### 309bc341 immutable workflow-repair candidate — prior device run

Export directory `toolchain/runtime-workflow-repairs`; app source
`393667047ae5531811bed09ca3a6081bdf98ca2d`, instrumentation/combined source
`309bc34194f7f835cd48a09dd6cde41b57648ed2`, tree
`e57fa2fb62a0dcc2eef52ae70e96618c8f70b537`.

- x86_64 app: `6fec66a2541125fbcadd8d7b8d940ba7f44c2ad3f79ba4249e350924225d2ae9`
- instrumentation: `7cdfde67fc04df931f41b00f1063829e9b75f120390f5f4abf8639fea8e48cfa`
- ARM64 app (build checks only): `459e75fe771efe566b5e026e10e5c76a2064abcbcef59af2cf66497632efe21c`

The main aggregate passed 166 JVM tests, lint and both ABI builds in 4m24s; the
additional test-only diagnostics packaged with lint in 1m03s. Signature/same debug
certificate, seven native libraries/ABI and 16KiB ELF/ZIP checks passed. The source
scanner found 129 literal routes and no Go/core/API/schema changes.

After all builds stopped, the same saved-data AVD restarted at18:54 UTC with
unchanged 1920×1080/density 320,2048MiB,noKVM/SwiftShader settings. No cache/data reset
was performed. At18:58:48 signed normal status confirmed version3.10.3 and
`/data/user/0/app.seanime.tv/files/seanime/data`. Direct file inspection confirms
both recovery files remain absent. The exact verification logs and diagnostic
qualification are in `evidence/native-workflow-repairs/restart/` and
`native-workflow-repairs-recovery-presence.txt`.

No-streaming replacement installation began18:58:49 and both installed hashes
were read back. App ART compilation explicitly used `verify` and took 36.040s;
test ART verification completed in 18.841s. Installed-hash verification finished
at19:03:18 UTC and the diagnostic filter method started then. This is not speed AOT.
The bounded queue then runs the filter method with an in-process15s/30s step
watchdog, all seven corrected reader methods, and the global plugin-navigation
method alone with distinct connection/initial-announcement diagnostics. Private
`cache/native-test-diagnostics` exports accompany screenshot TARs and logcat.
The filter run completed at19:08:18 UTC: one failure in 263.576s, with
`ComposeNotIdleException` / pending recompositions. Its step watchdog separates
successful scroll-to-year (108ms) from the stalled `Enter year input`, which failed
after 45.997s. Two bounded snapshots at 15.605s/31.361s capture the test waiting
inside `performTextReplacement` after focus and main actively laying out the
LazyList/focused text field under auto-advanced Compose test frames. The exact
final file is `evidence/native-workflow-repairs/discovery-filter-steps-final.txt`.
This identifies the failed phase; a source/test cause still needs diagnosis.
A requested screenshot happened after teardown and is not valid year/IME proof.
Reader7 then completed in 251.696s with six passes and one test-selector failure.
The full controls method reached the first physical Back hiding the keyboard,
then `onNodeWithText("Go to page")` matched both the still-open dialog title and
the underlying control at line 99. No later mark-read/back assertion is claimed
from that method. All four progress-safety cases and the two page/image retry
cases passed. The fresh RTL two-page screenshot was exported and visually checked;
it shows generated page images, safe margins and the complete focused control.

Global plugin navigation ran alone from19:13:00 to19:14:52, passing in 95.118s.
Its diagnostic records the socket connected in 780ms; the original total 10s
startup deadline and spontaneous initial announcement assertion were retained.
The method then passed subpage navigation, manual Back reporting and actual-screen
reload. This supersedes the prior isolated8a33 startup timeout for this method;
it does not establish arbitrary plugin component or live-extension behavior.
Nine current-pair methods therefore total seven passes and two failures; exact
per-method records are in `evidence/native-workflow-repairs/focused-test-results.json`.
All device jobs ended before the next aggregate; the saved AVD remains warm.

### 8a33 immutable plugin/reader candidate — prior device run

Installed from `toolchain/runtime-plugin-reader` at 18:19–18:20 UTC. Source commit
`8a33adf745dec3cd16d82e11b0be9732e9be2565`, tree
`b7135586e9583d2b20bc2fae4ffdceff299e4ac9`. APK hashes:

- x86_64 app: `b278bea0e6555f9c9d1d77150a26dc1406d90ed36dfb7d1f3cf33be7ea7eb1ad`
- instrumentation: `d9328adf8bd4c341f4f041702f4950a96dc9fc60323996bdf96092e1d9b5c567`
- ARM64 app (build verification only): `c43ee9d8bac44fbd86f1552a7ce7e71d8b28d42cceb1eba9e3a9f7fe389c39e4`

The install wrapper used `adb install --no-streaming -r -t` and read both installed
hashes back. Explicit ART `verify` completed in 21.407s/5.656s; this is not a speed
AOT claim. The original 1920×1080 TV display and retained app data remain intact.
Install log: `toolchain/logs/native-plugin-reader-install.log`.

`native-plugin-reader-navigation` selected `NativeAnimeDiscoveryTest` and
`NativePluginScreenNavigationTest` (six methods), with an 1800s outer timeout.
`emptySearchAndRetryKeepRemoteControlsUsableAndBlankSearchReturnsTrending` passed.
The next method, `effectiveFiltersApplyTogetherAndCancelDoesNotChangeTheRequest`,
was interrupted at 18:25:46 UTC (guest 03:25:46) by a five-second focus-loss event
ANR in PID 12587 / `androidx.activity.ComponentActivity`. The harness had launched
EmptyActivity during teardown after repeated Compose-Espresso non-idle reports
near year-field entry. The runner returned `shortMsg=keyDispatchingTimedOut`,
instrumentation code 0 and shell status 0, with no `OK` summary. Therefore only one
method passed, one is incomplete/failed, and four were not run. Shell zero is not
an assertion pass. The exported `native-plugin-reader-navigation-anr.txt` contains
only older startup ANRs, no trace for PID 12587. The exact current cause remains
unresolved. A targeted retry began at18:37:33 UTC on the same APKs and original
display. PID14150 again reported Compose-Espresso non-idle. `debuggerd -j` was
refused with “root is required”; no root escalation or Java stack was obtained.
A contemporaneous screenshot shows unchanged filter defaults with Release status
focused, earlier than the first run’s inferred year-entry phase. The isolated run then completed at18:41:14 UTC with `ComposeNotIdleException`:
pending recompositions, `hadRecomposerChanges=true`, `hadSnapshotChanges=false`,
`hadAwaitersOnMainClock=false`. Its terminal stack identifies
`performTextReplacement → enterFilter:146 → test:77`, the year-entry operation.
The early screenshot captured a prior temporary non-idle phase. This supplies a
specific source/test phase for diagnosis; it does not prove the underlying cause.
The separate remaining navigation batch (pagination plus plugin3) ran from
18:41:31 to18:44:51 UTC, 183.827s, with three passes and one failure. Pagination,
poster bounds and detail-return focus passed; its fresh `discovery-page-two-restored-card-focus.png`
shows the full focused Discovery46 card on Page2/2. Real WebSocket main-thread
navigation/reload callbacks and typed discovery/manga/unsupported-native targets
also passed. Global navigation timed out before its body at fixture:134 waiting
for both an open socket and initial `screen:changed`; no measure/layout crash
recurred. The shared condition does not distinguish which prerequisite was absent.
The forms/settings/theme7 batch began at18:45:13 and passed confirmation Cancel
and optional-value clearing. Blank-name validation started but never returned a
result. At18:48:09 instrumentation exited255 and screenshot export reported
`device offline`; four methods never started. Owner shell1689 remained alive,
but its job table reported QEMU PID68 killed, the PID was absent, and ADB listed
no device at18:49:10. Thus this is two passes, one incomplete, four unrun, not a
seven-test assertion result. This overlaps the concurrent single-worker/nice10
aggregate starting around18:45. No crash stack or exposed cgroup OOM counters
were available, so the kill cause remains unproven. Original AVD data/cache files
are retained; no reset or replacement device was created. Restart of the same
saved-data AVD is held until the aggregate ends. Diagnostic logs are
`native-owner-offline-inspection.txt` and `native-owner-memory-after-kill.txt`.

The separate `native-plugin-reader-workflows` batch began at 18:26:14 UTC, selecting
three plugin action/tab methods and three native manga reader methods. It finished at18:32:02 UTC in328.223s with five passes and one failure. All three
plugin action/tab methods and both reader retry/image methods passed. The reader
controls method completed direction, spread, zoom, page jump and cancel assertions
through line90, then timed out at `NativeMangaReaderTest.kt:93` waiting10s for the
mark-read progress request. Its fresh spread screenshot is valid partial evidence,
not a complete workflow pass. The separate `native-plugin-reader-autoplay` run
passed in57.85s at18:33:34 UTC with the original12s fixture. Its exact-URI READY
listener observes autoplay before ActivityScenario idle synchronization, then
pauses/seeks to the4s checkpoint; later lifecycle assertions remain intact.
No full native-suite result is claimed.

The separately opt-in real-Go scan/import/play/resume case **failed its public
metadata transport gate**, 33.661s, at18:35:19 UTC. Exact isolated root:
`files/native-go-fixture-29896ef5-4659-420c-95d0-1871207c72ea`. Verified manifest kind
`native-isolated-go-media-v1`, outcome `blocked-public-metadata`, no `mediaPath`.
The retained fixture/TAR and prior-state record are in
`toolchain/evidence/native-plugin-reader/isolated-go/`. It reached the isolated
simulated account and normal setup, then the unchanged Go public metadata client
reported `Post https://graphql.anilist.co: dial tcp: lookup graphql.anilist.co:
no such host` at18:35:06/16. The local API mapped that to “media not found on
AniList.” This is DNS failure before a provider HTTP/TLS response, not an account
requirement or successful scan/play/resume.

Read-only network inventory found guest Ethernet10.0.2.15, DNS10.0.2.3,
`http_proxy=null`; the retained emulator launch has no explicit proxy/DNS override.
The host task has configured HTTP(S) and SOCKS proxies. A separate read-only public GraphQL request through the already-provisioned
owner-shell HTTP(S) task proxy then returned HTTP 403 with the AniList Cloudflare
block page. Default TLS verification stayed enabled. Its public response is
`native-public-anilist-task-proxy.json`; no alternate routing or guest proxy was
configured. No network/security setting was changed. Both recovery files were absent before the run and remained absent.
Anime4K was already Off. The isolated process was force-stopped before any normal
launch; all fixture files were retained. Cold normal MainActivity then returned
signed follow-up `/api/v1/status` with proof present, version3.10.3 and exactly
`/data/user/0/app.seanime.tv/files/seanime/data` at18:35:39 UTC. Identity credentials
were held only in process memory and not logged. `SAFE_NORMAL_STATE_VERIFIED`
was recorded at18:36:06 UTC. Commands and exact hashes are in
`native-plugin-reader-isolated-go-protocol.log` and the separate instrumentation
log. The test ran alone with both `isolatedNativeGoFixture=true` and
`freshInstrumentationProcess=true`; no DB matching injection, transport weakening,
retained-data reset or cleanup outside the verified fixture was performed.

### Commands

From the repository root:

```sh
python3 scripts/check-native-tv-contracts.py --baseline 63200d6a850a6843b52f90ff7775d940416a534d
python3 scripts/check-native-tv-contracts.py --self-test
```

From `androidtv`, with Go 1.27.1, a JDK supporting Java target 17, Android SDK 36,
Build Tools 36 and NDK 27.2.12479018 on the path:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug --no-daemon --max-workers=1
./gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=1
```

After both APKs exist:

```sh
python3 scripts/verify_android_native_alignment.py androidtv/app/build/outputs/apk/debug/*.apk
python3 scripts/check-native-tv-contracts.py --apk androidtv/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk --apk androidtv/app/build/outputs/apk/debug/app-x86_64-debug.apk
apksigner verify --verbose androidtv/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
apksigner verify --verbose androidtv/app/build/outputs/apk/debug/app-x86_64-debug.apk
sha256sum androidtv/app/build/outputs/apk/debug/*.apk
```

## Instrumentation changes

The obsolete startup tests that injected JavaScript into the React/WebView UI
have been replaced. New tests target real native behavior:

- Actual `ComposeView` present and no `WebView` anywhere in the main view tree
- Initial setup, embedded Go readiness, stop/restart and native rail/search
- D-pad movement from Library to AniList, select, Back-to-rail, exit dialog and
  dismissal without closing the Activity
- Destination/search preservation after Activity recreation and background return
- Hardware letter-key input and arrow-key cursor focus in native search
- Leanback launcher metadata, callback-origin rejection and OAuth keyboard manifest
- Device-resolved legacy HTTP resource and canonical loopback/remote-cleartext policy
- Rejection of APKs outside the managed update cache
- External-player intent handoff and restoration of a native Compose host

Other existing tests still exercise the real Media3 player, its lifecycle and
missing-source recovery, opaque playback snapshots, SAF adapter and bundled
media executables. They must compile and run against this new APK before their
older results can be cited for the native migration.

## Build environment and recovered blockers

The fresh Linux environment initially had a Java 21 runtime but no Java compiler,
Go compiler, SDK/NDK, NASM or Android cache. Official tools were downloaded into
an isolated task-owned directory: Temurin JDK 21.0.12.1, Go 1.27.1, Gradle 8.11.1,
SDK 36/Build Tools 36, NDK 27.2.12479018, NASM 3.02 and Debian's gpgv 2.4.7.
Google/Temurin/Gradle archive checksums were verified. No system installation or
server-source modification was needed.

- Writable Android/XDG user directories fixed Gradle's read-only default home
- Build-only `GOFLAGS=-buildvcs=false` fixed VCS stamping of gomobile's generated
  temporary module; it does not change the server implementation
- Public-key `gpg --dearmor` plus `gpgv` replaced `gpg --import` because the latter
  attempted a denied agent socket. Verification remains fail-closed and checks
  `FCF986EA15E6E293A5644F10B4322F04D67658D8` exactly
- Official API 36 Android TV x86_64 image/emulator installed and software boot
  completed at 12:47:39 UTC (`sys.boot_completed=1`), about 13 minutes 40 seconds after launch.
  `/dev/kvm` is absent. This cannot establish physical decoder or 4K performance
- Separate execution shells use different process/network namespaces. The
  emulator, ADB and instrumentation must run in the same persistent shell
- Only checksum-verified, already-extracted installer archives were removed for
  disk headroom. Installed tools and Gradle/Go caches were retained

## Android x86_64 syscall compatibility investigation

The failed host checkpoint app SHA-256 was
`6ed9a8a0d9348c36b4475dbb36502281a7bbd599da8bad9466d0b12d81491303`.
It was installed with earlier test APK
`29bd955eaefe236a21fb55fcbcd9f7312503bf9f128f950aaca3190420f0a447`.
The successful install and a zero shell exit code were not treated as passing
instrumentation: the process was killed before assertions on the first attempt.

A subsequent foreground launch reached real Go startup and failed with
`SIGSYS / SYS_SECCOMP`, syscall 6 (`lstat`). The exact resume PC in the shipped
`libgojni.so`, `0x1e9410e`, maps to `internal/runtime/syscall/linux.Syscall6`.
The library's `modernc.org/libc.Xlstat64` loads syscall 6 and calls x/sys.Syscall;
its pinned source is `modernc.org/libc v1.41.0`, `libc_linux_amd64.go:108`.
The tombstone had only one frame; this does not claim a complete dynamic Go
caller stack. Go shared-library symbolization used the actual `runtime.text`
relocation, which was128 bytes beyond ELF `.text`.

The build compatibility fix never relaxes Android seccomp. It copies
the exact pinned dependency into ignored Android build output, checks source
SHA-256 `bf17852be812991fc8eb227f7e53a6ad24eba5e3baa61a466770efbf13f9c886`,
and translates legacy entry points to permitted `*at`, `pipe2`, `dup3`,
`clock_gettime` and `setitimer` calls. Existing C-wrapper errno handling remains.
The helper's non-Android branch calls the original syscalls unchanged. ARM64
source is not patched. The generated driver retains the original dependency
versions and torrent replacement, references the unchanged root module, and
propagates its staged libc replacement through gomobile's generated modules.
No repository `go.mod`, `go.sum`, core implementation, API or schema is edited.

Go 1.27 rejects direct overlays beneath GOMODCACHE, so the build uses a real
staged copy and generated driver, without global `-modfile`, custom GOWORK,
module-cache modification or symlink tricks. Original module licenses are copied.

Scope limits are explicit: timestamp inputs require valid C storage, as with
Android libc's user-space conversions; arbitrary invalid-address `EFAULT`
equivalence is not claimed. `setitimer` reports microseconds, so a remaining
1–999ns timer can round differently from Linux's raw alarm syscall. SQLite does
not use Xalarm. 25 Python tests pass, including9 Go behavioral tests in each host/forced-Android
branch (18 executions). They cover actual filesystem operations/errno, relative
paths, pin drift, immutable inputs, cold-cache errors, and driver preservation.
Actual Android/amd64 and Linux libc compilation passed without an overlay flag.
An Android device startup with the compatibility APK subsequently reached real database/account initialization without SIGSYS. Complete native setup/restart acceptance still requires the device assertions described below.

The isolated renderer/focus checkpoint used app SHA-256
`8851c975fbc78aa0d3037a8440bcc5a6d182e469a943e7fd34045787d29ac2b8`
and test SHA-256
`3a3b2bf6065a2aab1a3611b7b8a03473ed9b63d835f456c141f0755a0b9501a9`.
Its tests intentionally avoid starting the server, so their results cannot
stand in for backend startup/SQLite acceptance.

## Native device checkpoints and recovered UI defects

The compatibility/font-cache/IPv4-fixture checkpoint used app SHA-256
`83d7300e9dca5a1683e763406ac173433b34cf5884cb3ea1e276a25407d40fb2`
and test SHA-256
`47e7a0bbe20c7b3d66b22e615618faeed23450d190ae13afe617ddb0b646d582`.
On API 36 x86_64, the app reached actual Go database/account initialization,
reported server readiness and served status HTTP 200. The prior lstat SIGSYS
was absent. The startup instrumentation still failed waiting for the native
Library rail after first-run setup; this is not counted as a passing test.

The visible screen identified two additional defects: a stale playback fixture
snapshot with empty metadata opened an error modal over setup, and default TV
text was black on the dark root. The failed ASS fixture had removed its temporary
MKV but left its recovery snapshot. The production guard now rejects invalid or
missing-file native checkpoints, the fixture preserves/restores prior recovery,
and the root provides both Material3 and TV content colors. The stale snapshot
was preserved for the next APK's first launch to verify automatic recovery; no
app data was erased. A separate theme instrumentation test checks at least
4.5:1 default body-text contrast in both content-color scopes.

The recovery/contrast checkpoint is immutable: app SHA-256
`964e2298567a71bb1dd5c313829a6a8acd6776c735ce9f0c5b25e629b9f1aaed`,
test SHA-256
`b8b7c7c3dbb44c0fa79386fe5719921f1d4fa9282c39a1bf93162972f3d29998`.
It includes the production recovery/theme fixes, but predates the additional
contrast assertion. Its first instrumentation process hit a startup ANR before tests began. The
new main-thread stack was inside `Mobile.setAndroidStorageAdapter` during
Application creation, at native PC `0x1f1bb9a` (`runtime.rtsigprocmask`). A
normal foreground launch then succeeded in 19 seconds and showed readable
native startup UI. The warm retry ran one test and failed after setup: the
modal was gone and text was readable, but synthesized pointer activation did
not produce POST /start or leave Welcome. The secondary Activity in the log
was the instrumentation teardown harness, not a folder picker. The test now
requests semantic focus and sends physical D-pad Center, retaining all
server/Library assertions. Exact official dependency-source inspection explains
this: TV Material 1.0.0 `Surface.tvClickable` supports D-pad and semantic actions,
but has no pointer click handler; Compose UI test 1.8.1 `performClickImpl` injects
a touch event. This was an invalid test interaction, rather than demonstrated
failure of the TV remote control. Sources: [TV Material 1.0.0 source archive](https://dl.google.com/dl/android/maven2/androidx/tv/tv-material/1.0.0/tv-material-1.0.0-sources.jar)
and [UI test 1.8.1 source archive](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.8.1/ui-test-android-1.8.1-sources.jar). A new APK also moves Go
initialization off the main thread; both changes require a fresh run. Later
source additions must not be attributed to this checkpoint.

### Async host checkpoint

The next immutable app/test pair is respectively
`b9656cce7723a4c855f280701e5f664fe0cfcf5c4d7a31218f6dfa6d1f866cde`
and `deee43f8dbbab6ab32bfb4087023ada505840c3d4b33073b3b3ec37669dad86a`.
It compiled/assembled in 4m52s with 65 JVM tests in ten suites passing. It adds
the first off-main host-init latch, PDF and typed settings, but does not contain
the later owner-bound host queue or HLS/shader integration. DEX inspection
confirmed its startup helper still used the invalid pointer activation; that
startup test was deliberately not rerun. The new theme/PDF classes are present.
The emulator process disappeared once during an execution-tool stall, then a
later foreground shell-wait interruption closed the emulator namespace. Saved
AVD/app data remained intact. No additional runtime passes are claimed from
those interrupted attempts.

### Executor outage and recovered engine attempt

Execution disconnected at 14:32 UTC and the bounded read-only recovery probe
returned `409 environment_offline`. No test result was inferred from that outage.
At 15:19 UTC filesystem access returned; the old owner process was gone. The
same preserved AVD was restarted without clearing app data. Read-only installed
APK hashes confirmed app `b9656cce…` and the older test `b8b7c7c3…`; only the test
APK was updated to `deee43f8…` using official non-streaming ADB installation.

That seven-test ASS/focus/theme/PDF attempt exited before test execution with an
ART class-loading startup ANR. The stack was in DexFileVerifier/OpenFromZipEntry,
before Application/Go initialization; main had 3.69s running and 24.2s waiting for
scheduling. The same software-emulator boot recorded ANRs in SystemUI, launcher,
keyboard, media provider and other system apps, plus Bluetooth crashes. This is
not counted as an assertion failure in the unstarted seven tests. For the next
attempt, only nonessential Play Store, Play Games and recommendations packages
were force-stopped to reduce contention; their data was retained. SystemUI,
launcher, keyboard, GMS and security services were left intact.

## Final clean-slate candidate (runtime verification in progress)

Source revision: `34ea147f` (complete implementation, new presentation files,
shader assets and test helpers committed together; documentation changes are
tracked separately). The corrected full assemble/JVM/lint aggregate passed in
3m13s (81 tasks, 32 executed / 49 reused). Screenshot-helper packaging passed
in 38s. Both ABI APKs passed signing/native-content checks and 16KiB ELF/ZIP
alignment for all seven native libraries per ABI.

| Artifact | SHA-256 |
| --- | --- |
| ARM64 app | `e281ab984612c7b81a936425fa5262dc7a4d3d395b8b3f49b2eb872d25e37185` |
| x86_64 app | `dd77ef69fb0047ae5dbf9d52c3896a6031065fc4c8150d73219e2e1bac6b4294` |
| Instrumentation APK | `a87a8b8c415a66c9a54340c2e4ee9bfcb2149ed88ea1fa8299c009294e6e236c` |

The x86_64 app and instrumentation APK installed successfully at 15:44–15:46 UTC.
Both installed SHA-256 values were read back and matched the immutable candidate.
Official `cmd package compile -m speed -f -v` completed for both packages, but
ART reported **actualCompilerFilter=verify**, not speed AOT. App dex verification
took 34.6s and test dex verification 8.8s. Subsequent results are **preverified
software-emulator execution**, not successful speed AOT or physical cold-start
performance evidence.

The focused startup/restart test executed and failed (one test, one failure):
D-pad setup activation correctly sent POST /api/v1/start, but the unchanged Go
privileged-operation guard returned HTTP 403. Its error prompt then threw
FocusRequester-not-initialized at PlatformDialogs.kt:44, because focus was
requested outside the separate dialog window before attachment. These are two
identified native-client/UI defects, not compilation or infrastructure failures.
The source fixes and a new candidate rerun are pending. The independent player,
ASS/HLS/GL and PDF run on this exact candidate was interrupted when its owner
execution session disappeared. The complete native suite has not yet passed.

Test screenshot helpers capture asserted, remote-focused native states into
private cache PNGs with scenario/device/time JSON metadata. They will be exported
and associated with the exact candidate hashes; no mock-up substitutes for those
actual screen captures.

### Interrupted engine run and preserved evidence

The independent 14-test command began at 15:50:25 UTC. Its durable runner log
(`toolchain/logs/native-final-independent-engines.log`) contains five completed
passes, two assertion failures, one started/incomplete test and six tests that
never started. It contains neither a terminal instrumentation result nor `OK`.
At 16:28 UTC the previous owner session was confirmed unavailable; this is an
interrupted run, not a completed seven-test result or proof of an app crash.

- PASS: missing-source retry retains paused position; remote convert/return;
  matching-source native commands; dismiss does not recreate recovery snapshot;
  direct embedded-ASS animated glyphs/seek/lifecycle
- FAIL: stopped/recreated WAV fixture did not reach READY within its 10s wait;
  fresh HUD Play control was present but not focused at the initial assertion
- INCOMPLETE: HLS authored ASS/PGS test started as test 8; no outcome persisted
- NOT RUN in that attempt: remaining six tests; no shader/PDF conclusion follows

The two exported cache captures are real 1920×1080 API 36 emulator pixels:
`player-error-retry-focus.png` and `player-error-convert-focus.png`, with scenario,
device and time JSON companions. The original TAR is retained at
`toolchain/logs/native-clean-slate-screenshots-partial.tar`; extracted copies are
at `toolchain/evidence/native-clean-slate-partial/`. They show the new error
panel and focused actions. The explanatory body line is visibly ellipsized;
these captures do not establish Library, player HUD or dialog acceptance.

At 16:30 UTC recovery started the same saved TV AVD with 2 virtual cores,
2048 MiB RAM, SwiftShader and acceleration disabled. The first launch hit a
stale-PID multiple-instance check; normal retry succeeded without deleting
locks, caches or app data. Boot completed in 242s. At 16:34 UTC installed
artifact readback again matched app `dd77ef69…` and test `a87a8b8c…`; the
recovered screenshot cache exported successfully and contains the same two
error-panel images. At that point no new APK pair had been installed. The six previously
unstarted shader/PDF tests began separately at 16:35:22 UTC on this old pair;
the CNN CPU-reference/reconfiguration and memory-budget/HDR-bypass assertions
passed. The all-presets test then lost its process to Android's delayed startup
ANR kill at 16:37:35 UTC (`failed to complete startup`, 81,428ms ANR latency).
There is no app shader exception/native signal in that log. PDF tests never
started. The runner reports `Process crashed` even though the shell exits zero;
this six-test batch is not a pass. Exact runner/logcat evidence is retained in
`toolchain/logs/native-resumed-six-engines.log` and
`toolchain/logs/native-resumed-six-engines-logcat.txt`. The exported ANR trace
(`toolchain/logs/native-resumed-app-anr.txt`, latest PID 1625) locates main in
framework StatsLog/ART CheckJNI, with 2.92s CPU time and 27.49s scheduling wait.
The separate PDF retry ran 16:39:20–16:45:46 UTC and **passed all three tests**
(`OK (3 tests)`, runner time 357.823s, shell exit 0), retained in
`toolchain/logs/native-resumed-pdf.log` and `native-resumed-pdf-logcat.txt`.
It verified real PdfRenderer page output/pixel color, serialized ten-page work,
eight-page raster-cache limit and revisit, invalid-file cleanup, and closed-session
cleanup. This is engine evidence on `dd77ef69…` / `a87a8b8c…`, not manga UI or
physical-TV performance acceptance.

## Origin and window-focus candidate (runtime verification in progress)

Source revision: `d6ea476057013766eb8e5dafb2d9073963b4d452`;
immutable files: `toolchain/runtime-origin-focus/`. This candidate includes
canonical-origin/redirect handling and native window/layout-aware focus fixes,
readable player error copy, and stronger focused HUD/subtitle-generation tests.

| Artifact | SHA-256 |
| --- | --- |
| ARM64 app | `1265a75a20e55f22f0d6bc5315eb15fa5dde7acdb1716d57cacd9223e035ce5c` |
| x86_64 app | `a2fa680d441e4788112d79e57b2c720eeb9b5c5c792aabaecb9b0915fa384e1c` |
| Instrumentation APK | `9ce615a0e9cb343f90e4ef58798589286e2a6eb8f264251c789d8cd6b73b63f8` |

Non-streaming replacement installs completed at 16:52–16:54 UTC with app data
preserved. Both installed artifact hashes were read back and matched exactly.
Explicit ART `verify` completed (24.053s app / 4.289s test); this is not speed
AOT or physical-device performance evidence. Exact log:
`toolchain/logs/native-origin-focus-install.log`.

The focused real-server startup/Library test began at 16:54 UTC, using:

```sh
adb -s emulator-5554 shell am instrument -w -r -e class app.seanime.tv.AndroidTvStartupTest#nativeComposeUiStartsAndRestartsTheRealServer app.seanime.tv.test/androidx.test.runner.AndroidJUnitRunner
```

The focused test **failed one assertion** after 76.703s: Library did not appear
within 30s after setup key injection. Real Go/SQLite reached ready and
`GET /api/v1/status` returned 200; no POST `/start` occurred, and no focus crash
was recorded. A normal foreground launch then identified a native notice,
“Invalid server URL”, covering Welcome and owning window focus. IME was hidden.
The real screenshot is `toolchain/logs/native-origin-focus-foreground-ready.png`,
with corresponding UI XML/window dumps and a read-only recovery-snapshot export.
The preserved HLS fixture recovery still points to an existing file URI; the
notice's exact URL validation cause is under investigation. This is not a
Library pass. The independent Library/dialog/HUD batch **passed all eight tests** at
17:03:47 UTC (`OK (8 tests)`, 296.687s). It covers empty Library/zero search,
server-error retry focus, offscreen detail-to-card restoration without search
focus theft, four platform-dialog focus/cancel/replacement cases, and fresh
player HUD initial Play/Audio focus, selected/reopened audio dialog, media keys
and Back restoration. Exact runner: `toolchain/logs/native-origin-focus-ui.log`.

Six fresh PNG/JSON pairs plus an artifact-hash manifest are exported under
`toolchain/evidence/native-origin-focus/`; two older error-panel captures were
excluded based on timestamps. Visual inspection confirms readable Library/HUD
and dialogs, but the enlarged focused Fixture 36 card's bottom edge clips at the
grid boundary. Focus assertion pass does not establish safe-area acceptance.
Manual D-pad navigation reached lower Settings and Logs. Settings is fully
visible (focus bounds `[80,777]–[448,873]`); Logs focus bounds are
`[80,881]–[448,977]`, with footer starting at y994. Both avoid footer overlap,
but Logs' focused bottom curve clips at the rail viewport near y974. Therefore
full focused-item visibility fails for this candidate. Screenshots and XML-derived
bounds are retained as `rail-settings-focus.png`, `rail-logs-focus.png` and
`rail-bounds-evidence.json`.

The passing HUD test's normal Back dismissal removed the active recovery
snapshot, confirmed by readback at 17:04:39 UTC. No manual data/cache deletion
or snapshot move was performed. The original valid HLS fixture snapshot was
preserved separately in `native-origin-focus-recovery.json`. The clean-fixture real startup/restart rerun **passed** at 17:07:44 UTC
(`OK (1 test)`, 80.549s). D-pad setup produced POST `/api/v1/start` HTTP 200,
then both actual Go-server starts displayed native Library and passed
Compose/no-WebView, search, rail focus and stop/restart assertions. Exact log:
`toolchain/logs/native-origin-focus-startup-clean-fixture.log`. It does not
verify the pending local-file recovery fix. The actual setup and real Library
captures are `setup-without-recovery.png` and
`real-go-library-home-rail-focus.png` under the same evidence directory.

The real-backend integration batch **passed all three tests** at 17:15:08 UTC
(`OK (3 tests)`, 152.207s), using `AndroidNativeBackendFlowsTest`. It verified
native playlist-dialog Cancel/create, SQLite playlist create/rename/reorder/
complete/delete across a separate reading client, and typed setting persistence,
unrelated-field preservation and atomic invalid-write rejection. Fixture cleanup
and original-setting rollback are assertion-covered and passed; only UUID-named
fixture playlist IDs were removed. The deliberately invalid boolean PATCH
returned HTTP 500 while the subsequent read remained unchanged. Exact evidence:
`toolchain/logs/native-origin-focus-backend.log` and its `-logcat.txt` companion.

The subsequent `NativeEventSubtitleOverlayTest` batch completed at 17:17:50 UTC
with **one pass and one failure** (39.471s). Queued subtitle frames were correctly
invalidated across seek/pause/resume. The HLS source encoded successfully and
Media3 reached READY, but its `currentTracks` exposed a TEXT group, failing the
fixture's no-text-group precondition at frozen source line 141. The authored
ASS/PGS pixel, seek and recreation assertions never ran, and no HLS screenshot
was produced. Bundled Media3 source explains the fixture discrepancy: opening the child media
playlist directly leaves caption declarations null and exposes a synthetic
CEA-608 format, whereas the actual backend master declares
`CLOSED-CAPTIONS=NONE`. The corrected-master test retains the strict no-text
assertion and needs a new runtime run. No event-rendering pass or production
shader defect follows from this precondition failure. Exact evidence:
`toolchain/logs/native-origin-focus-subtitles.log` and its `-logcat.txt` companion.

`NativeHostQueueAndroidTest` also passed at 17:19:47 UTC (`OK (1 test)`,
0.381s), proving Android main-looper dispatch stays responsive during deliberately
blocked runtime initialization and dependent startup waits. Exact log:
`toolchain/logs/native-origin-focus-host-queue.log`.

Across these focused batches, fourteen tests passed on this exact candidate;
the recovery-blocked startup failure, HLS fixture precondition failure and
visual clipping remain unresolved findings. This is not a full instrumentation-suite pass.

The full native instrumentation suite and the
physical/live-provider gates below have not passed.

## f66 discovery/playback candidate (runtime verification in progress)

Source revision: `f66d183cdd364a24c478f264d9c530bfc35cacc9`;
immutable files: `toolchain/runtime-discovery/`.

| Artifact | SHA-256 |
| --- | --- |
| ARM64 app | `2e2e9b3edfdbc2e2a6ea26455f4d5b937fb8411ed5d7b47ba2aa3d0b811a9e7e` |
| x86_64 app | `5c8588cd41015416dab3bf4c74b6a82aaa019cc8e5ce080174dbce5e40fae6ce` |
| Instrumentation APK | `0c78feafe3bc1be42cddec9839e88562514ae2e25c42e352dd5af4d50ba61598` |

Non-streaming installs and installed-hash readback succeeded at 17:28–17:29 UTC.
Explicit ART verify took 23.054s app and 5.715s tests, not speed AOT. All app data
and cache contents were preserved. Log: `toolchain/logs/native-discovery-install.log`.

`NativeEventSubtitleOverlayTest` **passed both tests** at 17:32:59 UTC
(`OK (2 tests)`, 175.231s). The backend-style master playlist retains strict
zero TEXT tracks; actual authored ASS motion follows seek time, PGS pixels/crop
render, playback/subtitles survive pause/resume/recreation, Anime4K selection
works, oversized headers fail within bounds, and queued frames cannot reappear
after generation changes. This supersedes the prior fixture precondition failure.
Exact runner/logcat: `toolchain/logs/native-discovery-subtitles.log` and its
`-logcat.txt` companion.

Three PNG/JSON pairs are in `toolchain/evidence/native-discovery/` with artifact
hashes in `evidence-manifest.json`. ASS and PGS planes are visible, but those
captures include a transitional Buffering badge. The capture named
`player-fresh-anime4k-dialog.png` raced dialog rendering and actually shows the
HUD with Picture focused; it is explicitly **not accepted as visible dialog
evidence**. No image has been altered to hide these limitations.

The eight player lifecycle/status/skip tests completed at 17:39:42 UTC with
**seven passes and one failure** (372.217s). Live-position/status and relative
seek with stale-cache/source gating, plugin skip/HUD/caption wire schema, fresh
HUD/dialog/Back, matching-source commands, missing-source retry/convert, and
dismissal cleanup passed. The initial autoplay lifecycle test again observed
ENDED after its full fixture had played (`state=4`, position 120031ms, duration
120000ms, playWhenReady=true). The Activity displayed after 23.2s and audio
continued until the remaining 117s finished; simply lengthening the fixture did
not solve test observation/idling. Inspection of ActivityScenario 1.6.1 confirms
`onActivity` waits for UI idle before executing its action; continuous player
frames delayed that first read until audio ended (936,000 frames = 117s).
The approved test-only repair observes READY/autoplay from an Activity lifecycle
listener before idle waits, then pauses and installs the existing 4s checkpoint;
production timeouts stay unchanged. It needs a new test APK/rerun. That
lifecycle's pause/recreate assertions
never ran and remain a gate. Exact runner:
`toolchain/logs/native-discovery-player.log`; diagnostic timeline:
`toolchain/logs/native-discovery-player-progress-logcat.txt`.

 Direct app-start recovery **passed as a manual scenario** at 17:44–17:47 UTC.
The exact previously failing generated HLS file remained present. Prior recovery
state was confirmed absent; only the preserved fixture snapshot was restored,
with SHA-256 `492a9d4ba35ed1155efa08684aa659e53c9e9ea932bdee19ebfe994da57b9b66`
verified before MainActivity launch. NativePlayerActivity opened the exact URI,
without Invalid server URL. Three newly written checkpoints used a fresh process
session and remained at exactly1100ms with playWhenReady=false. Settled UI showed
0:01/0:04 and focused Play. A real D-pad Picture action then opened the fully
visible Anime4K dialog with selected Off; this separate manual screenshot is valid
dialog evidence. Three normal Back presses returned to real Library and cleared
the checkpoint, restoring the prior absent state. No data/cache reset occurred.

Exact observations, JSON assertions, settled screenshots and cleanup evidence
are under `toolchain/evidence/native-discovery/direct-recovery/`. An initial
ADB exec-in write yielded an empty file before verification; a normal shell-stdin
retry wrote the identical approved fixture and verified its hash before launch.
The original snapshot and HLS files remain preserved. This scenario verifies the
file-URI path; content-URI/provider recovery remains a separate gate.

The requested focus/artwork/picker/discovery/plugin/presentation classes began
at 17:48 UTC in bounded batches: focus/presentation8, artwork-fallback/pickers/
discovery/plugin navigation12, plus the separate offline-artwork1. The total
is21; the runner's actual counts govern.

The first focus/presentation batch **passed all8 tests** at 17:54:22 UTC
(309.598s). It covers initial/root/setup window-ready focus, focus rearming,
lower-rail bounds/Back, empty/error/detail-return focus, and the24-track dialog's
selected-index18/20 reopening, remote Close access and full-row/dialog bounds.
The new restored Fixture36 screenshot now shows the entire rounded focus border;
the former grid clipping defect is visually resolved. Many-track captures show
labels19/21 (indices18/20) and Close fully in view. Exact evidence:
`toolchain/logs/native-discovery-focus.log`, and fresh PNG/JSON files plus manifest
under `toolchain/evidence/native-discovery/`. The remaining12+1 outcomes are recorded below. Final manual Logs verification
passed at 18:08:27 UTC: bounds `[96,865]–[432,961]`, footer starts y974, and the
full rounded focused edge is visible. Exact image/XML: `rail-logs-focus.png` and
`rail-logs-ui.xml` in the f66 evidence directory.


The feature batch ended at 18:00:09 UTC with **seven passes, three timeout
failures, one Java process-crash failure and one unstarted test**. All three
artwork-fallback and four native title-picker cases passed, including catalog
select/cancel, offline tracking payload, playlist single append/payload retention,
and hidden-title cancel/selected numeric IDs. All three discovery cases timed
out after clicking `anime-discover`, waiting for `discovery-grid` at frozen
`NativeAnimeDiscoveryTest.kt:121`. The first plugin-navigation case then threw
`IllegalArgumentException: performMeasureAndLayout called during measure layout`
from AndroidComposeView and crashed the process; the second plugin case never
started. This batch has no passing terminal summary. Exact evidence:
`toolchain/logs/native-discovery-features.log` and its `-logcat.txt` companion.
The separate offline-artwork test **passed** at 18:01:36 UTC
(`OK (1 test)`, 50.182s). A generated poster PNG traveled through an HTTP fixture
matching the existing offline asset route, Coil decoded it, and actual native
pixels/focus bounds were asserted. This does not claim a live-provider download
or actual Go offline-asset persistence workflow. The inspected capture is
`offline-poster-native-render-focus.png`; native title-picker search focus is
captured in `native-title-picker-search-focus.png` under the current evidence
directory. Both have metadata and artifact-hash association.

Both plugin-navigation methods were retried in a fresh process on the same
f66 pair at 18:03–18:05 UTC and **both failed** (96.847s). Global navigation
reproduced the measure/layout reentrancy exception, now through
`measureAndLayoutForTest` / `TestMonotonicFrameClock`; it is not explained solely
by prior discovery-test teardown. Filtered navigation hit the known discovery
grid timeout at line60. This isolated retry did complete a terminal two-test
failure summary without a process-crash exit. Exact runner/logcat:
`toolchain/logs/native-discovery-plugin-isolated.log` and its companion.
The full thread dump also confirms the plugin failure's production cause:
an OkHttp emitter thread invoked the plugin protocol, navigatePlugin and
railState.scrollToItem, reaching Compose layout concurrently with main. A
Main.immediate dispatch fix and callback-thread regression are awaiting the next
immutable pair. Discovery's source root cause is confirmed: awaiting scrollToItem while
loading=true prevented the grid from composing, so scrolling awaited layout
indefinitely. A source fix is being prepared; no rerun pass is claimed yet.

Full-suite, physical-device and live-account gates remain open.

### f66 all-presets GPU completion

The outstanding `everySupportedPresetCompilesAndRendersRealPixels` method
**passed** at 18:16:49 UTC (`OK (1 test)`, 214.884s). All 12 supported non-Off
presets compiled and rendered expected-size, opaque nonempty pixels without
fallback; GAN 3×/4× also matched independent CPU references within tolerance.
The 8×8 fixture and software ES2/SwiftShader environment do not establish
HD/4K or physical performance. Exact runner/logcat:
`toolchain/logs/native-discovery-all-presets.log` and its companion. Full shader
provenance and bounds remain in [native Anime4K acceptance](2026-09-30-native-anime4k.md).

## Historical checkpoint summaries

Historical focused candidate: `80b74403`, app `becb4a5d…`, test `5d43babc…`.
Of 21 selected focused cases, 20 executed: **17 passed and three failed**. One
reader-controls case was held before instrumentation because its test title
matcher remains ambiguous inside the full-screen reader Dialog. The precise
matcher and later source repairs require another immutable pair.

Passing current-pair cases cover the repaired filter editors, existing-Debrid
folder/Cancel/retry flows, client capability rechecks, Nakama routing,
matching-source commands, global plugin tray/commands, account-action guards,
offline/current-status guards, explorer invalidation, and playlist refresh/events.
The remaining failures are both SourceDownload initial-provider waits and Play
focus restoration after Skip. Exact per-case accounting is in
`toolchain/evidence/native-editors/focused-test-results.json`. The app's broader
native suite and physical/live-account gates are still incomplete. List-score/
delete UI, raw-Go media coverage and the latest fixes are outside this APK pair.


| Historical checkpoint | Result then | Scope |
| --- | --- | --- |
| 309bc341 focused device checks | PARTIAL:7 PASS/2 FAIL | Reader6/7 pass with one ambiguous dialog-title selector failure; global plugin navigation passes95.118s. Filter year-input pending recompositions remains a failure with bounded thread evidence |
| f66 artwork/title/discovery/plugin-routing UI | PARTIAL: artwork4 + picker4 PASS; discovery3 FAIL; plugin2 isolated FAIL | Discovery grid never appears after entry; plugin navigation throws performMeasureAndLayout during measure/layout. Separate offline-artwork1 passes; no complete discovery/plugin feature pass |

These preserve the results at their original immutable artifact boundaries.
Current superseding results appear in the matrix and candidate sections above.

| Check | Historical result | Scope |
| --- | --- | --- |
| 8a33 discovery / plugin navigation | PARTIAL: discovery2 PASS/1 FAIL; plugin2 PASS/1 FAIL | Pagination/restored-card bounds, empty/search/retry, main-thread WS callback and typed-target navigation pass. Filter year replacement has pending recompositions; global plugin startup lacks its first announcement within10s |
| 8a33 plugin actions / reader / autoplay | PARTIAL: actions/tabs3 PASS; reader2 PASS/1 FAIL; autoplay1 PASS | Reader direction/spreads/zoom/jump/cancel reach mark-read, but no progress request arrives within10s (line93). Autoplay early READY/playWhenReady observation and later lifecycle assertions pass in57.85s |

Previous focused candidate: `309bc341`, app `6fec66a2…`, test `7cdfde67…`.
Production app source is `39366704`; the later commit changes test diagnostics.
Both installed APK hashes have been read back. The filter watchdog run fails with pending recompositions during year input;
its in-process thread snapshots are preserved. Reader6/7 pass, with one ambiguous dialog-title selector failure; the isolated
global-plugin navigation method passes. These nine selected cases total seven
passes and two failures, with later source repairs still unbuilt. Build and installation are not
runtime acceptance. Previous exact-pair results below
remain separate evidence.

Previous focused candidate: `8a33adf7`, app `b278bea0…`, test `d9328adf…`.
Discovery empty/search/retry and pagination/detail-return pass; filters fail at
year text replacement with pending recompositions. Plugin WebSocket callbacks run
on Android main, and typed discovery/manga navigation passes; global-navigation
startup times out waiting for the first screen announcement. All three plugin
action/tab tests and both reader retry/image tests pass. Reader controls reach
mark-read but no progress request arrives within 10 seconds. Repaired autoplay
lifecycle passes in 57.85 seconds with the original 12-second fixture. The isolated
real-Go media flow is blocked by public metadata DNS; the intended host proxy
also receives AniList HTTP 403. Normal retained-server state was verified after
the isolated process ended. The forms/theme batch was interrupted when QEMU was killed: two passed, one
incomplete and four unrun; the AVD is preserved pending restart.
Later working-tree changes require a new immutable pair and device reruns.
Across 21 uniquely selected 8a33 cases, 12 passed, three failed assertions, one
was blocked by public transport, one was interrupted and four were unrun. The
repeated filter attempt is counted once using its final result; see
`toolchain/evidence/native-plugin-reader/focused-test-results.json`. This is not
a full native-suite or full feature-parity result.

Previous candidate: `f66d183c`, app `5c8588cd…`, test `0c78feaf…`.
Its two HLS/event subtitle tests pass; the lifecycle batch has seven passes and
one autoplay-fixture synchronization failure. Direct saved-file recovery and cleanup passed as a manual scenario. The focus/presentation batch passed8 tests, including grid/rail bounds. The
artwork-fallback3 and title-picker4 also pass. Discovery3 time out before their
grid appears; both plugin-navigation cases fail in a fresh isolated retry. The
initial combined batch crashed during the first plugin case, preserved below. Separate offline-artwork HTTP-fixture/Coil pixel verification passed. The
requested total is21 UI methods. Newer working-tree
changes are not part of these APKs.

Across the f66 bounded runs, **26 of 32 uniquely named focused tests passed;
six failed** (one autoplay-test observation issue, three discovery cases and two
plugin-navigation cases). Repeated plugin methods are counted once using their
latest result; exact accounting is in the f66 `focused-test-results.json`.
Direct saved-file recovery and final grid/Logs visuals also passed as observed
manual scenarios. This is not a full native-suite result.

Previous focused checkpoint: `d6ea4760`, app `a2fa680d…`, test `9ce615a0…`.
Fourteen focused tests passed on that pair: real setup/Library and server restart
(1), native Library/dialog/HUD behavior (8), real backend playlist/settings flows
(3), queued-subtitle generation cleanup (1) and Android host-queue responsiveness
(1). That older pair reproduced local-file recovery and clipped
focus-edge failures. Their f66 direct recovery and grid/Logs bounds reruns passed.
Its HLS fixture precondition failure is superseded by f66’s passing corrected
master-playlist scenario. Newer working-tree changes require another immutable
pair and rerun. “Implemented” means a purpose-built source UI/action calling existing
contracts, not a live-provider or complete-parity claim.


## Historical evidence is not native-migration acceptance

[September 29 parity results](2026-09-29-parity-batch.md) record 23 passing
instrumentation tests on the prior API 31 ARM64 React/WebView APK, along with
older Go/frontend results and APK hashes. Those results retain their original
revision, environment and scope. They do not prove the new native Compose UI,
new JSON models, native state/event coordination, focus behavior or new APKs.

## Remaining acceptance gates

These are outstanding checks, not passed results. Use the exact candidate APKs
and record device/API/ABI, APK hashes, test media and before/after state for each.
Build/signature/native-content/16KiB packaging checks retain the artifact
boundaries recorded above and do not replace the device/account checks below.

1. **Current native instrumentation — HELD / 168 METHODS UNRUN.** After a
   separately authorized runtime resumption, finish the
   bounded current-candidate UI, player, backend, media-tool, SAF and external-player
   batches, with explicit pass/fail/skipped/unrun counts. Run the opt-in isolated
   Go media tests in their own cold processes using their preservation protocols.
   The generated raw-route test passed on e5 and has changed assertions awaiting
   a current-pair run. The separate scan/import/matching test remains blocked by
   public AniList DNS and the intended task proxy's HTTP 403; do not substitute a
   DB match or provider fixture for that integration.
   Repeat the required device gates on API 23, current TV API, ARM64, and a real
   16KiB-page device. The present software x86_64 emulator covers none of those
   additional physical/API/ABI combinations.

2. **Physical USB and SAF — NOT RUN on hardware.** Put a small owned video and
   a disposable text/download file in a dedicated test folder on a USB drive.
   In native Settings choose **Additional anime folder**, select only that folder
   in Android's system picker, then scan it from the native Library and open the
   owned video. Seek, pause, return to Library and reopen to check continuity.
   Create/copy/rename/delete only disposable files inside that folder through the
   native file/download controls. Interrupt a disposable write by unplugging the
   drive, replug it and verify the original committed file and unrelated folders
   remain intact. Use **Settings → Manage folder access** to remove that test
   grant, verify reads/writes fail clearly, select the same folder again and
   confirm access resumes. Reboot the TV and check the grant persists. Preserve
   screenshots/logs and restore the previous folder selections afterward.

3. **Live AniList/MAL accounts — NOT RUN.** Record the existing native account
   state and the status/progress/score of one user-selected test list entry.
   Use Settings' explicit AniList/MAL connection control, finish the official
   sign-in flow, and verify return to the native screen with the expected account.
   Open that entry from the native anime list, change one chosen list field,
   verify it on the provider, then restore its recorded value and verify again.
   Recreate/reopen the app to check the connection persists. Exercise Cancel on
   the named disconnect confirmation before testing an authorized disconnect and
   reconnection. Keep credentials and tokens out of screenshots/logs. Public
   metadata transport failure alone is not evidence that an account is required.

4. **Trusted live provider and download — NOT RUN.** With an authorized provider
   and owned/authorized media, open a native anime detail page and its episode
   source chooser, select the intended provider/source, and verify the native
   player opens the selected episode. Exercise play/pause, D-pad seek, audio and
   subtitle selection, Back to details, reopen/resume, and background/foreground
   recovery. Choose **Download**, select the dedicated configured test folder and
   subfolder, cancel once and verify no queue mutation, then start the approved
   test download. Check progress, retry/cancel behavior and the completed file in
   native Library/Files; reopen it without the provider connection. Restore any
   changed provider/preferences and remove only the owned disposable fixture.
   Real source ranking, expiring URLs and every provider-specific edge case remain
   separate unverified coverage.

5. **Two-peer Nakama — NOT RUN.** Use two authorized devices/accounts and record
   their existing Nakama settings. In **Settings → Nakama**, choose host/peer mode
   and username; set the peer's **Host address** and, if required, enter the host
   password through its masked native control. In the host's native Nakama screen,
   select **Create room** and **Create watch party**; on the peer choose
   **Reconnect**, then **Join watch party**. Play an owned shared episode and
   verify both screens' participants/readiness, play/pause/seek propagation,
   source changes, and one agreed test chat message. Interrupt/reconnect the peer
   and check state recovery without duplicate playback. Use **Leave watch party**,
   **End watch party**, and confirmed **Disconnect room**, then restore settings.
   A callback-routing fixture does not close this real peer-synchronization gate.

6. **Physical D-pad, codecs and Anime4K — NOT RUN.** On representative 1080p and 4K
   TVs, navigate Library, discovery, Settings/Logs, long dialogs and the player
   using only the remote. Capture focused first/last rows and verify full safe
   bounds, readable text, keyboard/IME placement, Back restoration and no footer
   overlap. Play owned AVC/HEVC10/HDR samples with ASS/PGS/fonts, seek repeatedly,
   background/recreate/resume, and compare Off versus each supported Anime4K
   preset while recording dropped frames, memory, thermal behavior and fallback.
   Reproduce the reference 1080p HEVC10 green/purple case on Xiaomi TV Box S2 and
   verify cross-device resume. This cloud run has not diagnosed or fixed that
   hardware reference case and does not establish HD/4K GPU performance.

7. **Same-key release upgrade preserving data — NOT RUN.** Use the intended
   persistent release key through the authorized signing workflow; do not expose
   its password/private key in artifacts. On a disposable or backed-up TV profile,
   install the prior same-key release and record a test library entry, settings,
   playlist, playback checkpoint and SAF grant. Install the new release in place
   through the native managed-update flow/system installer without uninstalling
   or clearing data. Verify package/signing identity and version, then reopen
   native Library, Settings, Playlists and the test episode to confirm all recorded
   state survives. Confirm the USB grant and restored playback still work after
   reboot. Current unchanged debug-certificate checks are not release-upgrade
   acceptance.

8. **Native external-player workflow — IMPLEMENTED / CURRENT RUNTIME PENDING.**
   The native More/error controls now open the Android chooser, and the current
   cold fixture targets an owned receiver in the test APK's distinct UID/process.
   The historical c5 adapter fixture returned immediately in Seanime's process;
   its pass does not establish sustained delivery. Run the guarded current cold
   method with an owned generated video and verify zero started Seanime
   activities, foreground hosting across the 900 ms background policy, three
   exact anonymous Go ranges over three seconds, then paused native return,
   More focus and lease/service release. Separately, on a physical TV use an
   authorized installed real player through the native control, play and seek
   while Seanime is backgrounded, return, and verify focus/state/host survival.
   Record direct/raw and any supported converted-source behavior independently;
   the owned receiver cannot prove third-party player compatibility. Preserve
   normal recovery before each guarded fixture and verify signed normal dataDir
   after force-stop/restoration. No physical third-party-player pass is recorded.

9. **Remaining feature scope — PARTIAL.** Resolve the matrix's specific missing
   native workflows, or explicitly retain them as unsupported/unverified with
   user-visible behavior. Generic JSON editing, API-path presence, fixture passes
   and compilation do not establish full desktop feature parity. Preserve the
   distinction between actual unchanged-Go/SQLite checks, generated HTTP/media
   fixtures, authorized live providers, and physical hardware evidence.
