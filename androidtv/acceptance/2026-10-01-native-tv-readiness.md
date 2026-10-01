# Native Seanime TV review build — 2026-10-01

**Historical artifact checkpoint.** The subsequent TV layout, focus, thumbnail
and live-data corrections are recorded in the
[current per-source device review](2026-10-01-remote-ui-review.md). The APKs and
315-test results below belong only to `5a90fdfe` and are not the revised UI.

**The native rewrite is ready for source/build review. Full device acceptance is
blocked and is not complete.** The current APKs have not been installed or run.
All 168 planned device methods require a fresh result on this candidate.

## Exact candidate

- Branch: `feat/native-compose-tv`; intended PR base: `UnoxyRich/seanime:codex/android-tv`
- Base commit: `63200d6a850a6843b52f90ff7775d940416a534d`
- APK source: `5a90fdfe4490417ca2d267086775e1b6119eb6fb`
- Source tree: `0cb959805ae2df03321a24119fe3c6d294c98164`
- Export: `toolchain/runtime-host-retirement/` in the build workspace
- Signing: existing task debug certificate; no production release-signing claim
- Nothing has been pushed or published to GitHub

| APK | SHA-256 |
| --- | --- |
| ARM64 `app-arm64-v8a-debug.apk` | `646bea24cce81f0c057a9bff086e5292be81f2ec4334b9bd89a54959f9aabd72` |
| x86_64 `app-x86_64-debug.apk` | `7b6233cd5da07ed291144c9c43fb9be4b82c4ece8607f660d8c3ff393f2df3e2` |
| Instrumentation `app-debug-androidTest.apk` | `71ade9668f6b440312aef30690fc69fced762441afbe9754781fd57673a1aab4` |

The complete aggregate passed in **3m41s**: **315 JVM tests, zero failures,
errors or skips**, lint, both production ABI APKs and test packaging. V1/V2
signatures, the existing certificate, seven native libraries per ABI with
16 KiB ELF/ZIP alignment, 145 literal API contracts and presentation/package
boundary checks passed. The Go core, routes, payload definitions and schema are
unchanged from the base. Static contract checks do not prove server behavior.

## What changed

The presentation is a clean-slate native Compose TV implementation, including
player controls, menus, dialogs, search, navigation and account/platform chrome.
React and earlier Android presentation code are behavior references only; they
are not embedded or reused. Media3's video surface and the native media/Go
engines remain integrations. Provider authentication is the restricted browser
exception.

The implementation includes typed native workflows for collections and list
editing, library files/bulk operations, discovery/custom sources, manga and
offline reading, playlists, torrent/debrid/online source selection, downloads,
extensions and supported plugin controls, Nakama, settings and reports. Media
identities remain lossless through JavaScript-safe integer limits, including
navigation, persistence, queue/events and player recovery. See the detailed
[screen/behavior/API evidence](2026-09-30-native-compose.md) and
[scope closeout](2026-09-30-native-closeout.md).

The final correction closes a proven lifecycle race: finishing an Activity
during blocked runtime initialization could queue shutdown and then admit late
startup for the same owner. Shutdown now retires that owner's authority
immediately. Four deterministic JVM regressions cover late, queued and running
startup plus replacement ownership. Existing protected external playback still
defers shutdown. This does **not** establish the cause of the observed Android
ANR, for which no app stack was captured.

## Feature status

“Implemented” below describes source and build readiness, not current-device
acceptance. The current candidate has no passing device methods to promote.

| Area | Current status | Remaining evidence or boundary |
| --- | --- | --- |
| Library, personal lists and discovery | Implemented | Native remote flows, large IDs, bulk/rename/match and exact API requests need the frozen device suite |
| AniList / local collection migration | Implemented | Live OAuth, named-account migration and cross-device synchronization require an authorized account |
| Independent MAL account/list operations | Backend capability limit | Missing/unregistered capabilities are recorded with server evidence; client screens cannot create those APIs |
| Manga, PDF and offline access | Implemented | Current reader/queue/identity tests and real USB/provider persistence remain open |
| Playlists and playback continuity | Implemented | Current production entry, queue/recovery, real-Go and external-player lifecycle tests remain open |
| Online, torrent, debrid and downloads | Implemented | Source-selection/transfer fixtures need rerun; real provider transfers require accounts and owned media |
| Extensions / declarative plugin controls | Implemented for supported contracts | Marketplace, navigation/action ownership and current payload tests remain open |
| Arbitrary plugin HTML/DOM/CSS presentation | Partial native compatibility | No embedded browser UI; see the [contract audit](2026-09-30-native-plugin-contract.md). This is not a missing backend API |
| Player, audio, seeking and subtitles | Implemented | Current integration tests and physical codec/color/subtitle checks remain open |
| Anime4K and direct libass engines | Implemented; narrowly retained engine evidence | Unchanged shader/libass source and packaged bytes permit only the recorded direct-engine results; current player integration and physical HD/4K behavior remain unverified |
| Nakama | Implemented | Routing/coordinator fixtures and two real peers still require acceptance |
| Settings, storage, updates, logs/reports | Implemented | Current dialog/export/storage tests, physical USB and same-release-key upgrade remain open |

## Results belong to their exact source

| Source / scope | Recorded result | What it does not establish |
| --- | --- | --- |
| **5a90fdfe current build** | 315 JVM + lint/build/signing/alignment/contracts pass | No install, startup or device test result on these APKs; **164 ordinary + 1 guarded + 3 cold = 168 methods unrun** |
| e639525d | 311 JVM aggregate; exact installed x86/test hashes, original data/recovery and cold signed Go HTTP 200 passed | All 167 then-planned device methods unrun; framework gate stopped execution |
| 82b48c7e, IME/source batch | 1 pass, 3 failures; 803.976s | Torrent workflow passed; numeric footer navigation, initial multiline IME wait and Debrid idle failed |
| e9ffacc0, IME/source batch | 2 passes, 2 failures; 216.254s; separate Debrid repeat also passed | Both source flows passed; the former competing-scroll editor regression failed. A repeated method is not additional unique coverage |
| c5b3a096, selected feature batches | 44 passes, 3 failures across 47 methods | Earlier-source evidence only; later identity, lifecycle and presentation changes require rerun |

The former Android host-queue pass is explicitly withdrawn as reusable evidence
because this candidate changes that implementation. Its existing main-looper
test is now in batch 22. The full
[finite manifest](2026-09-30-next-native-test-manifest.md) preserves every current
method and distinguishes ordinary, guarded recovery and isolated cold tests.

## Unresolved runtime cases and environment limit

- The earlier numeric Down-to-Cancel failure has a source fix, but device
  validation of that fix is still pending.
- The multiline initial-IME timeout and intermittent Debrid Compose-idle
  failure remain inconclusive. Later visibility in a screenshot is not a pass
  for the earlier timing assertion. No timeout or idling requirement was weakened.
- Android 36 software emulation produced SystemUI, launcher, Gboard/GMS and
  media-process ANRs, a system-server watchdog restart, and an app focus-dispatch
  ANR event. Broad framework failure does not by itself prove an app symptom's cause.
- Both worker-owned and root-owned execution sessions disappeared despite the
  parent remaining active. The last QEMU terminal exit is unknown. All six
  saved writable images were kernel-unlocked at 03:48:06 UTC; that is not proof
  of a clean shutdown. Data, caches and historical evidence were retained.
- No supported lighter 64-bit TV image was established on this cloud host.
  Older TV images lack x86_64; the ordinary launcher rejects API ≥28 ARM64
  guests on x86_64. See the [verified runtime alternatives](2026-10-01-runtime-alternatives.md).

## How to finish acceptance

Use the supplied, same-signature APK pair on an authorized stable TV target, or
rebuild **both** app and test APKs together on a supported executor. Do not mix a
newly signed local test APK with these prebuilt app APKs. No private signing or
ADB key is needed or included in the handoff.

Follow the [device acceptance runbook](2026-10-01-device-acceptance-steps.md).
First rerun the four strict editor/source methods, then the finite manifest.
Physical TV/USB, HEVC10/color/GPU/HD4K, API23/ARM64/16-KiB runtime, live
OAuth/provider accounts, two Nakama peers and same-release-key upgrade each
retain their named steps. Public scan/matching also remains blocked by the
observed AniList DNS/provisioned-proxy 403; owned raw-media tests do not replace it.

The review handoff contains APKs, checksums, build reports, sanitized acceptance
metadata and a Git bundle of coherent local commits. It excludes private keys,
credentials, emulator user data and raw device logs. Runtime acceptance remains
blocked; this package is not a claim of complete parity or release readiness.
