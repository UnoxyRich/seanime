# Native Seanime TV: device acceptance steps

Prepared 2026-10-01. This is a runbook for the remaining gates, **not a record of
passes**. It makes no device, account, installation or release changes itself.

## Revised UI candidate

The current source is `43c2f02ea9ceb7b5b856f57fee8a00380fc1438b`. Use its
[current hashes and evidence](2026-10-01-tv-layout-and-live-data.md) and
[reconciled exact device manifest](2026-10-01-tv-device-manifest.json).
It has 172 ordinary methods in 30 bounded batches, one guarded recovery method
and three isolated cold methods: **176 planned device methods**, all unrun
when this manifest was frozen. Four byte/source-unchanged direct shader/libass
methods retain only their named historical scope; one scan/matching method is
separately blocked by the live AniList 403. Together these account for all 181
methods in the current Android instrumentation catalog.

The first four strict IME/source methods below remain the first planned device
batch. The new UI adds eight device methods covering compact navigation,
discovery, source replacement/retry, readable long dialogs and Settings. Host
tests cover real keys and rendering but do not replace these device methods.
The preserved cloud AVD's bounded ordinary attempt stopped before installation
after four fresh system ANRs. Its exact outcome is in the current correction
report. No proxy or network-security change was part of that attempt.

The source/hash inventory in section 1 below is the **historical 5a90 handoff**.
Do not substitute those APKs for the revised pair. The device safety, ordinary
Gradle invocation, isolated-fixture precautions and physical acceptance steps
remain applicable with the current manifest and exact current APK identities.

## 1. Use the frozen execution candidate and a stable test target

The execution candidate is source `5a90fdfe4490417ca2d267086775e1b6119eb6fb`, tree
`0cb959805ae2df03321a24119fe3c6d294c98164`. Later documentation commits do not
change those APKs. The immutable bundle is `toolchain/runtime-host-retirement/`;
`toolchain/` evidence paths are relative to the task workspace, outside the repo.

| File | SHA-256 |
| --- | --- |
| `app-arm64-v8a-debug.apk` | `646bea24cce81f0c057a9bff086e5292be81f2ec4334b9bd89a54959f9aabd72` |
| `app-x86_64-debug.apk` | `7b6233cd5da07ed291144c9c43fb9be4b82c4ece8607f660d8c3ff393f2df3e2` |
| `app-debug-androidTest.apk` | `71ade9668f6b440312aef30690fc69fced762441afbe9754781fd57673a1aab4` |

Already established in 3m41s: 315 JVM tests with no failures/errors/skips, lint, both ABI
builds, test packaging, signatures, 16 KiB ELF/ZIP alignment, 145 literal API
contracts and unchanged Go. These are **debug-signed with the existing task
certificate**, not the production release key. **No installation or runtime
verification has occurred on 5a90.**

All **164 ordinary + 1 guarded + 3 cold = 168 planned device methods remain
unrun on this pair**. Host retirement fixes the source ordering that could start
an old Activity owner's host after its stop when initialization was blocked.
Four JVM regressions pass; Android host-queue acceptance is no longer reusable
from its old result and is included in batch 22.

Historical evidence only: e639's exact installed x86 app/test hashes, retained
database/recovery state and cold signed normal Go HTTP 200 passed. Its own 167
planned methods stayed unrun. The Android 36 TCG environment hit framework/SystemUI ANRs
and repeated executor resets; its latest stable-framework gate stopped the queue
before instrumentation. The historical c5 result of 44/47 and 82 result of 1/4
do not apply to either e639 or 5a90. The hidden-keyboard Down-to-Cancel fix first
present in e639 remains unverified on device. Earlier multiline initial-IME
timeout and Debrid Compose-idle failures are inconclusive under that unstable
environment; no application cause for those runtime symptoms has been proven.
The separately found host-ordering bug does not explain those symptoms by
itself. See the [current acceptance report](2026-09-30-native-compose.md)
and [source-idling diagnosis](2026-10-01-source-idle-diagnosis.md).

1. Use stable, approved hardware outside the current cloud host: a dedicated
   official Android TV AVD on an already VM-accelerated compatible host, or an
   authorized physical ARM64 TV. The installed official 37.1.11 x86_64 launcher
   cannot run ARM64 TV API 28+; no older x86_64 TV image alternative was
   established. Do not try to bypass the launcher or create another unsupported
   cloud AVD. See the [runtime-alternatives evidence](2026-10-01-runtime-alternatives.md).
   On a supported emulator,
   record `emulator -accel-check`, system-image/API/ABI, emulator version and
   actual graphics renderer. Match the image architecture to the host and use a
   supported graphics configuration; an `auto` setting alone does not establish
   actual GPU acceleration. See Android's [TV setup](https://developer.android.com/training/tv/get-started/create)
   and [acceleration guidance](https://developer.android.com/studio/run/emulator-acceleration).
2. Keep the saved failing AVD and its evidence intact. Use a separate test AVD or
   spare TV with a disposable Seanime profile, no personal accounts and only
   owned test files. Do not uninstall an existing app, clear its data or change
   security settings to make this candidate install. A signing mismatch is a
   blocker; the release-upgrade test below uses a separate same-key pair.
3. Verify the bundle's `SHA256SUMS` and certificate reports. Install only the
   target ABI APK and its matching test APK through the normal authorized
   developer flow. Read back both installed APK hashes, package IDs
   `app.seanime.tv` / `app.seanime.tv.test`, version and certificate. Never test a
   mixed pair. Rebuilding creates a new artifact requiring its own hashes.
4. Before testing, capture database/recovery presence and hashes where accessible
   through the app's authorized debug workflow. Record an initially absent file
   as absent. Launch normally, confirm native Library and signed Go status
   HTTP 200/version 3.10.3 with normal data directory
   `/data/user/0/app.seanime.tv/files/seanime/data`. Keep authentication proofs
   transient. Require at least 60 seconds of responsive launcher, keyboard and
   package service with no new framework ANR/restart before the first batch.
   Stop and preserve evidence if that gate fails; do not weaken assertions.

On first use, **Welcome to Seanime TV → Start using Seanime** uses the app's
media directory. **Choose USB / media folder** invokes the system picker. Keep
online/torrent switches at the recorded test settings; a local profile does not
need account credentials. An existing protected server shows **Unlock your
Seanime server**; its owner enters the password without capturing it.

## 2. Run the finite developer suite before claiming device acceptance

Use the reconciled [finite runtime manifest](2026-09-30-next-native-test-manifest.md)
and `toolchain/evidence/native-host-retirement/frozen-test-manifest.json` for
exact methods, order, outer bounds and evidence exports. Do not run the entire
package with all opt-ins. Do not start builds concurrently with the device run.
The 5a90 manifest has 29 ordinary batches and 168 total methods; require its
source/hash identity to match this handoff before any execution. The older e639
frozen JSON and 167-method evidence remain historical and unchanged.

The first batch is exactly these four existing methods, with the unchanged
1,500-second outer bound and unchanged individual assertions:

- `ui.NativeTextEntryImeTest#numericKeyboardLeavesTheFieldAndFooterVisibleAndDoneDoesNotSave`
- `ui.NativeTextEntryImeTest#firstPhysicalBackHidesMultilineKeyboardAndLeavesTheDraftOpen`
- `ui.NativeSourceDownloadTest#remoteSearchDownloadUsesFoldersSupportsCancelAndRetriesServerFailure`
- `ui.NativeSourceDownloadTest#remoteDebridDownloadUsesChosenConfiguredFolder`

On a separately prepared standard developer target, Android's supported
instrumentation entry for those two classes is below. Substitute only the
verified serial; retain the manifest's timeout/export wrapper around this call.
This is not a command to run against the preserved task AVD outside its owner.

```sh
adb -s SERIAL shell am instrument -w -r \
  -e class app.seanime.tv.ui.NativeTextEntryImeTest,app.seanime.tv.ui.NativeSourceDownloadTest \
  app.seanime.tv.test/androidx.test.runner.AndroidJUnitRunner
```

[Android command-line instrumentation reference](https://developer.android.com/studio/test/command-line).
Then execute the remaining 28 ordinary batches in the reconciled order, including identity,
collections, library operations, native plugins, player/reader, accounts,
startup/storage and forms/settings. Record terminal results, not merely shell
exit codes. On API 23, the IME window assertions annotated for API 29+ will skip;
report that limitation and run them on a supported current TV API.

Batch 22 now includes
`app.seanime.tv.platform.NativeHostQueueAndroidTest#androidMainLooperRemainsResponsiveWhileRuntimeInitializationIsBlocked`
and has 11 methods under its unchanged 1,800-second outer bound. The existing
Android method checks main-looper responsiveness; the four new JVM regressions
cover the host-retirement ordering. Neither establishes the complete repaired
startup/recreation behavior on a device until the current batch passes. The
historical Android host-queue pass is withdrawn from reused coverage.

Run **R1** and **C1–C3** separately using their named preservation wrappers and
exact opt-ins in the manifest. Before each, preserve both normal recovery files
byte-for-byte, require the unique fixture UUID and owned data/media paths, and
keep evidence before cleanup. After the cold process ends, restore original
state and verify signed normal status/dataDir. C1 is raw Go media, C2 sustained
external-player handoff and C3 owned unmatched-index library management. None
proves public scan/matching. Do not enable their flags for a whole suite.

Repeat applicable gates on API 23, current TV API and ARM64; separately record
actual 16 KiB-page runtime. The existing 16 KiB packaging pass is insufficient.
For every method report PASS, FAIL, SKIPPED with reason, BLOCKED with reason, or
UNRUN; interrupted methods stay incomplete. Retest any source fix on newly frozen
bytes before attributing a pass to it.

## 3. Physical remote, empty states and editors

Use only D-pad directions, Center, Back and the TV keyboard for this section.
Capture both the selected control and full screen at first/last visible rows.

1. On the empty local profile visit **Library**, **AniList & MAL**, **Manga**,
   **Offline**, **Playlists**, **Extensions**, **Streaming**, **Downloads**,
   **Nakama**, **Settings**, and **Logs & reports**. Each must keep a reachable
   rail/action when empty or loading. In Library enter an unmatched phrase into
   **Search my collection → Search**; verify an empty result, then clear the
   field and search again. Back must recover navigation. At root, **Leave
   Seanime? → Stay** must return usable focus without exiting.
2. On the dedicated populated profile, select **Lists & sort** and change
   **Status:** and **Sort:**; verify visible membership/order, not just the label.
   Search a known title within the collection. Open a card below the first
   screen, then Back: query, scroll and selected card must return. Separately use
   **Discover → Title or keyword → Search**, page forward/back and enter/leave a
   detail. **Airing** has date-range controls, not title search. Repeat Manga's
   **My collection / Search my collection / Lists & sort** and **Discover**.
3. Open an owned test anime → **Update list → Rating (0–10): …**. Enter `8.5`.
   With the numeric keyboard visible, the whole field, **Cancel** and **Save**
   must fit above it. Keyboard Done must hide only the keyboard. From the still
   focused field press Down: **Cancel**; Right: **Save**; Center: save the draft
   into the parent list dialog. Cancel that parent dialog to avoid an account
   write, or explicitly approve its Save on the test entry. Reopen and verify
   the appropriate persisted/unchanged value. Check invalid input and Cancel.
4. **Logs & reports → Create issue report → Edit description**: enter a harmless
   12-line draft. First Back must hide the keyboard while retaining the open
   editor and every line; Down reaches **Cancel** and Center discards it. Return
   to the report and Cancel; no report needs to be prepared or shared. The
   20-line helper overflow, Up/Down scrolling and return-to-editor assertions
   belong to the first batch's exact multiline fixture, not this shorter help
   text. A later screenshot of a keyboard is not proof it met the 10-second
   initial-visibility assertion.
5. Exercise first/last actions in **Settings**, **Library → Manage**, a source
   chooser and long player choices. No selected scale ring, footer, heading or
   text may clip or overlap the keyboard. Reopen the app/background and return;
   verify the same route and useful focus. Capture the actual TV/IME resolution,
   including a narrow viewport, rather than relying only on semantics bounds.

## 4. Local playback, playlists, codecs and Anime4K

Prepare owned short media with known duration, two audio tracks, text/ASS/PGS
subtitles and fonts; include AVC SDR and HEVC 10-bit/HDR samples. Record codecs,
resolution, track names and hashes. Use authorized local indexed files; online
scan/matching is the separate gate below.

1. **Library → title → episode → Device → Play on this TV**, or **Library →
   Manage → Files → Play** for an owned indexed file. Confirm the chosen file and
   episode. In the HUD exercise **Pause/Play**, **−10 sec**, **+10 sec**, held
   Left/Right on the timeline, media keys and repeated seeks near beginning/end.
   The displayed time and picture/audio must agree after buffering settles.
2. Open **Audio** and **Subtitles**, select a nondefault track, then Off and back.
   Verify audible language and actual subtitle pixels, ASS styling/fonts and
   PGS timing. Scroll beyond one screen of tracks, close/reopen, and verify the
   selected track/focus. Seek, pause and reopen playback: no old caption or track
   from a previous source may remain. Record direct and converted streams
   separately; conversion error recovery uses **Convert for this device**.
3. Back closes a choice dialog, then hides the HUD, then returns to Seanime.
   Reopen and confirm the recorded position, pause state and track preferences.
   Repeat Home/return, Activity recreation and an authorized force-stop/relaunch
   on the disposable profile, preserving recovery evidence. On a known standalone
   unmatched file, **Previous/Next** must be disabled instead of inventing an
   episode identity.
4. **Playlists → New playlist → Save → Open → Add anime**: choose a known test
   title with at least two available unwatched owned episodes; verify the added
   episode rows. Use **Move up/down**, **Start playlist**, player **Next/Previous**
   and **More → Auto-next**. Confirm actual episode transitions and completion
   after returning to Playlists/Refresh. Reorder/mark only the test playlist;
   verify no unrelated row is replaced. A CRUD response is not playback proof.
5. **Picture → Anime4K picture enhancement**: compare Off at the same timestamp
   with each of the 12 offered presets. Record input/output size, chosen preset,
   renderer/decoder, visible result or exact fallback notice, dropped frames,
   memory and sustained thermal behavior at representative 1080p/4K workloads.
   HDR is intentionally passed through with a reason; resource-bound fallback
   must retain the original image. Do not count fallback as running that graph.
   The older 8×8 shader passes do not establish physical performance. Exact
   preset names, allocation limits and engine provenance are in the
   [Anime4K report](2026-09-30-native-anime4k.md).
6. On Xiaomi TV Box S2, separately reproduce the reference 1080p HEVC10
   green/purple sample and compare Off/actual decoder/conversion; record correct
   colors or the failure. This hardware case has not been diagnosed or fixed by
   the cloud run. For cross-device resume, record the authorized account/source
   and observed position on both devices; one-device recovery is insufficient.
7. **More → Open in another player**: choose an already authorized installed
   player, play/seek while Seanime is backgrounded, then return. Seanime should
   return paused with **More** focus and release the temporary host/grant when
   finished. C2 additionally proves separate UID/process delivery of three exact
   raw Go ranges across the background policy. A receiver fixture does not prove
   third-party-player compatibility. Converted HLS is a separate unsupported
   external-handoff boundary, not a required success for the raw-source test.

## 5. Real USB, permission recovery and offline files

Use a dedicated USB folder containing only owned originals, disposable copies
and a small downloadable fixture. Record filenames/hashes and existing folder
selections; do not test interrupted writes on irreplaceable media.

1. **Settings → Additional anime folder**: select only the test directory using
   Android's picker. **Library → Manage → Scan library → Confirm → Scan reports**
   must show the actual matching/unmatched result. If public metadata transport
   blocks scanning, retain that failure; an isolated imported index cannot close
   it. On indexed owned files, use **Files → Play**, seek, pause and reopen.
2. **Explorer → Open folder**, **Select file / Selected file actions**, **Edit
   match** and **Rename**: verify the exact indexed path and preview before each
   change; Cancel once, then apply only approved test changes and read back
   metadata/bytes. Test **Delete file** only on an explicitly approved disposable
   copy through its permanent-deletion confirmation. No general library file
   move workflow is implied; built-in torrents have **Downloads → Torrents →
   Move files** separately.
3. Start an approved disposable write/download to this folder, unplug during the
   write, replug and inspect recovery. The app must report failure/recovery
   truthfully, preserve the committed original and unrelated files, and avoid
   showing a partial file as complete. Verify bytes after a successful retry.
4. **Settings → Manage folder access → test folder → Disconnect folder →
   Disconnect** removes only the test grant. Confirm access fails clearly.
   Re-select the same folder with **Additional anime folder** (or use
   **Reconnect folder** while an unavailable root is retained). Reopen files,
   reboot the TV and verify grant persistence. Restore recorded folder choices.
5. With an authorized connected account, **Offline → Track anime / Track manga
   → Save latest metadata**; distinguish request acceptance, active tasks and
   server finish signal. Download a permitted chapter through **Manga → title →
   Select download → Download N selected** or **Download**. Verify completion and
   actual pages, then **Downloaded → Chapters → Read** with connectivity absent
   or in **Use offline mode**. Use **Next**, **Go to page**, **Next chapter**,
   Close/reopen and reader preferences. Local anime must still play. Metadata
   alone does not contain manga pages; a local profile does not gain AniList
   offline-sync controls. Restore online mode afterward if it was changed.
6. **Settings → Library index backup → Export index → Prepare export → Save as…**
   must create readable JSON through the real system provider. Cancel the picker
   once before saving to the test folder. Exercise import preview/confirmation
   only on the disposable profile: it replaces the entire active index. Also
   verify an authorized **Logs & reports** export is a readable ZIP/profile file,
   not merely a successful save callback; review private paths before sharing.

## 6. Authorized live accounts, sources and transfers

This section needs the owner's chosen test accounts/media and approval for the
specific list changes, provider installation/downloads and disconnects. The
operator enters credentials through the official flow; no automatic credential
use or token/password capture is part of acceptance.

1. Record one test title's status/progress/score/dates and the current account.
   **Settings → Connect AniList** (or **Manage accounts → Connect/Reconnect
   AniList**) must open official OAuth and return to native UI with the correct
   named account. If offline, explicitly **Offline → Go online** first. Test
   cancellation before completing sign-in; reopen to verify persistence.
2. **AniList & MAL → title → Update list**: change one approved field, Save and
   verify on AniList independently. Restore its original value and verify again.
   Test **Manage accounts → Disconnect AniList → Cancel**, then an approved
   disconnect/reconnect. Test **Connect MyAnimeList** separately and verify valid
   OAuth return plus the supported MAL tracking effect on a chosen title. Do not
   invent a MAL connected indicator or independent MAL search/list screen.
3. **Upload local collection to AniList** is optional and writes the whole saved
   local collection. Use only an approved disposable collection and named target;
   Cancel first. After approval, inspect every selected test title on AniList:
   the server's success cannot establish per-title success. Offline progress
   upload is the different **Offline → Upload local progress** action.
4. With an approved trusted provider installed/enabled in **Extensions** (use
   **Marketplace** or **Install from URL** and review the actual manifest and
   permissions), open **Streaming** or an anime detail → episode → intended
   source mode/provider. Verify the returned episode/audio/source and **Play …**
   starts that media. Repeat subtitle/audio/seek/return/resume from section 4,
   including an expired/unavailable source and truthful retry behavior.
5. Choose a release → **Download release → Torrent client** or **Debrid**. Select
   **Main library / Library folder → Open [test subfolder] → Cancel**; no queue
   mutation should occur. Reopen, verify the exact destination and approve
   **Download here**. For existing debrid entries use **Downloads → Debrid →
   Download files**. Observe real progress and a bounded error/retry/cancel on
   owned test media, then verify the complete file bytes and local playback
   without the provider. Test torrent and debrid separately; a fixture does not
   validate service availability, URLs, rate limits or transfer completion.
6. Built-in torrent **Details**, priorities, trackers and **Move files / Rename**
   require the selected owned torrent, correct files and fresh readback. Session
   **Speed limits** are request acknowledgements, not persisted settings;
   **Recheck** acknowledgement does not mean verification finished. Keep advanced
   provider-specific behavior blocked/unrun when its prerequisites are absent.

## 7. Two real Nakama peers

Use two authorized devices and record both settings. Agree on an owned shared
episode and one harmless chat message before sending it.

1. On each, **Settings → Nakama**: enable, set username and host/peer mode. On the
   peer, **Nakama → Host address / Host password** uses the actual host details;
   enter any password in the masked control without logging it.
2. Host: **Nakama → Create room → Create watch party**. Peer: **Reconnect → Join
   watch party**. Verify both participants and readiness on both displays.
3. Play the agreed episode and verify real play/pause/seek and source changes on
   both devices, then **Send message** once. Interrupt only the test peer's
   connection, Reconnect and confirm state recovery without duplicate playback
   or stale-source commands.
4. Peer **Leave watch party**; host **End watch party**, then **Disconnect room**
   with confirmation. Restore prior settings. Record role-specific outcomes;
   the host-only disconnect action is not a peer disconnect API.

## 8. True release-key upgrade

Current debug artifacts cannot close this gate. Use the intended persistent
release key through the authorized signing workflow and a prior/new release
pair with matching package/certificate and an accepted upgrade version. Keep
keystore/passwords out of this bundle. The four signing environment inputs are
documented in the [Android README](../README.md).

1. On a disposable or verified backed-up TV, install the prior same-key release.
   Record its version/certificate, one test library entry, settings, playlist,
   paused playback position and USB grant, with readable baseline evidence.
2. Exercise **Settings → Check app update**, review the correct ABI release and
   follow its download/system-installer confirmation. Install in place without
   uninstalling or clearing data. If no matching published update exists, report
   that updater gate blocked; a developer in-place install is separate evidence.
3. Verify installed version/certificate and all recorded state. Reopen playback,
   confirm USB reads, reboot and check again. A same-debug-certificate install,
   unsigned build or new-key fresh install is not release-upgrade acceptance.

## 9. Interpret results without inventing backend support

The unchanged server and native presentation intentionally retain these limits;
record them separately from unfinished verification:

- No independent registered MAL list/search/progress route; playlists play via
  the real WebSocket protocol because legacy playlist-start/next REST handlers
  are no-ops. Nakama live state is event-driven, without `/nakama/status`.
- Manga **Pause queue** requests cancellation without joining the worker;
  **Clear queue** is guarded by fresh terminal-errored rows. Empty queue snapshots
  and offline metadata finish events are not per-request/per-title receipts.
- Local collection upload ignores per-title update failures. Live partial-date
  clearing is unverified and not offered; complete dates and preservation of
  unedited partial dates are distinct. Playlist/file replacement has no atomic
  revision contract; another concurrent writer can still race the final read.
- Anime country filtering and MUSIC are limited by the registered query; manga
  discovery excludes NOVEL. Recent-airing title search is not honored upstream.
- **Extensions → Open plugin** supports native declarative controls, permission
  reviews, typed navigation/actions, episode tabs, trays/commands and bounded
  device alternatives. Arbitrary HTML, DOM scripts, CSS and iframe presentation
  remain explicitly partial. Empty/null DOM responses are intentional; there is
  no embedded React page and no claim of browser-plugin parity. Verify the
  supported native contract with the manifest; use the
  [plugin audit](2026-09-30-native-plugin-contract.md) for exact boundaries.
- Public scan/matching remains separately blocked by the recorded AniList
  DNS/proxy 403 gate until a real authorized network run succeeds. Do not route
  around that denial or substitute a raw-video/import fixture. Converted HLS
  suspends on app backgrounding and lacks a supported native external handoff.

Full route/source rationale: [API contract notes](../app/src/main/java/app/seanime/tv/data/README.md).
Implementation versus acceptance tracking: [native closeout](2026-09-30-native-closeout.md).

For each run retain: UTC time; source/tree and both installed hashes; device,
API/ABI/page size, IME and decoder/GPU; exact method or steps; input media/provider
and approved fixture scope; expected versus observed result; fresh screenshots
and logs; before/after state and cleanup/restoration. Exclude tokens/passwords.
Label environment ANRs, application assertion failures, service/network blocks
and unrun checks separately. Acceptance closes only the specific flow and
device/artifact combination actually observed.
