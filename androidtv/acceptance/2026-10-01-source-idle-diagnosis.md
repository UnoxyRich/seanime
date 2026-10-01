# Native source destination idling diagnosis — 2026-10-01

## Result and scope

The `82b48c7e` Debrid failure is **not fixed**. The evidence supports broad
software-emulator execution/scheduling slowness as a serious hypothesis, but does
not establish its cause or rule out an intermittent focus/layout feedback loop.
There is insufficient evidence for a production source-dialog change.

This was a read-only review of code, retained logs, dependency source and official
ART documentation. No build, device operation, runtime setting change or test
weakening was performed. Only this report was added. The coordinator's next
`e639525d` aggregate was already in progress; this report makes no claim about its
runtime result. Go behavior is unchanged.

## Immutable run comparisons

| Revision / run | Result | Duration |
| --- | --- | --- |
| `c5b3a096`, source-download batch | Both source methods passed; earlier presentation/coverage | 169.966 s |
| `e9ffacc0`, Debrid-only repeat | Debrid passed, every watchdog step below 15 s | 121.595 s |
| `e9ffacc0`, complete IME/source batch | Both source methods passed; both IME methods failed | 216.254 s |
| `82b48c7e`, complete IME/source batch, ended 02:07:52 UTC | Torrent passed; Debrid and both IME methods failed | 803.976 s |

`git diff e9ffacc0..82b48c7e` is empty for `SourceScreen.kt`,
`SourceDownloadDialog.kt`, `NativeDownloadDestinationDialog.kt`,
`TvInitialFocus.kt` and `NativeSourceDownloadTest.kt`. The source workflow and
narrow callback-off Debrid fixture are therefore unchanged between the recent
passing and failing runs. This is evidence against attributing the failure to a
new source-dialog edit; it does not prove that the unchanged code is correct.

The fixture HTTP records give the following wall-clock comparisons. HTTP duration
means client call start through completed response body, including scheduling and
the local MockWebServer; it is not a measurement of external network latency.

| Debrid interval | e9 isolated | e9 full batch | 82 full batch |
| --- | ---: | ---: | ---: |
| Selected provider visible → search call starts | 55.977 s | 41.165 s | 156.260 s |
| Search HTTP, 122-byte body | 0.280 s | 0.220 s | 2.770 s |
| Dialog settings HTTP, 89-byte body | 0.167 s | 0.169 s | 2.249 s |
| Settings body complete → directory call starts | 3.561 s | 3.266 s | 11.572 s |
| First directory HTTP, 161-byte body | 0.198 s | 0.196 s | 1.904 s |
| Open release destination dialog step | 5.153 s | 4.536 s | 24.366 s |
| Wait for configured destination choices step | 7.869 s | 6.986 s | Failed at 31.112 s |

The pre-search interval includes focus/viewport checks, screenshot captures,
scrolling and remote activation; it cannot isolate any one of those costs. Its
3.80× slowdown versus e9 full batch, together with 9.7–13.3× slower small
localhost calls and the 3.72× overall batch duration, shows that slowness predates
the destination dialog and extends beyond one Compose operation. It does not
prove TCG, interpreter execution, host contention or any other single cause.

## What the failure actually establishes

- At the first snapshot, 16.828 s into opening the release dialog, the test is in
  `performTvClick` → `assertIsFocused`, before `KEYCODE_DPAD_CENTER`. The dialog
  settings fetch and dialog Cancel initial-focus request have not caused this
  particular stall. The step eventually completes at 24.366 s.
- The later destination-choice wait throws `ComposeNotIdleException` at
  `NativeSourceDownloadTest.kt:93`; it is not a missing-row assertion. Fetching
  semantics itself waits for Compose to idle, so the enclosing 10 s `waitUntil`
  does not bound that inner idle wait.
- Search, settings and directory response bodies complete successfully, at
  179.312 s, 221.920 s and 235.396 s from fixture creation. There is no demonstrated
  blocked HTTP request at the final failure.
- Main-thread snapshots are RUNNABLE in modifier application, snapshot
  application and graphics-layer updates. They demonstrate Compose work, not an
  identified infinite loop or a network deadlock.
- TV Material 1.0.0's ordinary focus animation updates `graphicsLayer` through
  `animateFloatAsState`; its focus/unfocus scale durations are 300/500 ms of
  animation time. It also animates z-index. Thus the observed stacks are
  compatible with normal focus animations being slow to execute. They do not
  identify which animation or state is changing.
- Before the click, the source download opener has no `initialTvFocus` modifier;
  that modifier is added when `downloadOpener` is set by the click. Its granted
  flag is initially true. Other source initial-focus flags are also true in this
  fixture. The helper's guarded, placement/window-driven request does not contain
  a retry loop. After dialog creation, Cancel legitimately requests focus once.

The watchdog's second snapshot can finish emitting after `FAILED` / `END
watchdog`: a scheduled dump already in progress can pass its last active check
before the test closes. Its 32.707 s timestamp must not be treated as proof that
the same test step continued beyond the recorded failure. The final exported
logcat's main buffer has rolled past the Debrid interval; retain the private
watchdog/HTTP files as the primary evidence rather than inferring absent events.

## ART AOT proposal

Both current installation results report `actualCompilerFilter=verify`. That
means DEX verification, not AOT compilation. However, a same-byte debug-APK
`speed` request is not a promising remedy: official ART Service
[`Dexopter.adjustCompilerFilter`](https://android.googlesource.com/platform/art/+/refs/heads/main/libartservice/service/java/com/android/server/art/Dexopter.java)
clamps debuggable packages to the safe-mode filter even for shell requests,
because their compiled code is ignored. This is current upstream source, not a
claim that the installed device's exact ART binary was inspected.

The local acceptance history already records `cmd package compile -m speed -f
-v` for both earlier debug packages returning **verify**, not speed. The current
pair is also built as debug APKs; the retained e9 ART invocation logs explicitly
include `--debuggable` for both packages. Repeating that
request would not establish AOT unless the actual result and usable execution
mode changed. A release/profileable build would be a distinct artifact and test
configuration, not a transparent speedup of the existing immutable pair. No such
configuration change is proposed as a source-bug fix. See the official
[ART configuration](https://source.android.com/docs/core/runtime/configure) and
[ART Service result guidance](https://source.android.com/docs/core/runtime/configure/art-service).

## Bounded next diagnostics if the unchanged-assertion retry fails

Keep all assertions, standard idling behavior, existing time budgets and focus
animations. Add diagnostics only to the instrumentation fixture/watchdog:

1. Split the open step into scroll, RequestFocus, focused assertion, physical
   CENTER dispatch and framework idle wait. Add equivalent bounded steps around
   the earlier provider/mode focus checks and screenshots; the existing watchdog
   starts too late to explain the 156 s pre-search gap.
2. At START, existing 15/30 s snapshots and DONE/FAILED, record elapsed uptime,
   process CPU time, `compose.mainClock.currentTime`, and each public
   `Recomposer.runningRecomposers` entry's identity, `changeCount` and
   `hasPendingWork`. These are observations only: do not advance the test clock,
   request main-thread work from the watchdog or register a perpetual frame
   callback. Confirm safe read access in the actual test-library version before
   implementation; `RecomposerInfo.state` is a Flow, not a StateFlow to cast.
3. If needed, use fixture-only observers to copy focused target, source-list
   scroll/index/offset and target/viewport geometry into an atomic diagnostic
   snapshot on actual changes. Avoid per-frame file writes, state mutations from
   layout callbacks, or enabling the provider callback in the deliberately
   callback-off Debrid variant. Keep a bounded in-memory event tail.
4. Capture the app's filtered log stream from test start so system log volume
   cannot erase the failure interval. Use the allowed application diagnostics;
   do not retry the refused debuggerd route.

Small virtual-time advancement with long wall time would support expensive or
starved finite animation work. Repeated focus/geometry changes over substantial
virtual time would support a feedback loop. Either outcome would narrow the next
fix; a passing retry alone would still not prove the intermittent issue fixed.

## Evidence locations

All `toolchain/` paths below are relative to `/workspace/scratch/228a17a65270/`.

- Failed raw batch: `toolchain/logs/native-bounded-editor-20261001-01-ime-source-repairs.log`
- Fresh failed-run primary diagnostics: `toolchain/evidence/native-bounded-editor/live-debrid/source-debrid-download-steps.txt` and `source-provider-http-debrid.txt`
- Same diagnostics retained under `toolchain/evidence/native-bounded-editor/01-ime-source-repairs/diagnostics/`
- e9 full-batch diagnostics: `toolchain/evidence/native-floating-ime/01-ime-source-repairs/diagnostics/`
- e9 isolated diagnostics: `toolchain/evidence/native-floating-ime/debrid-isolated-repeat/diagnostics/`
- ART results: `toolchain/logs/native-bounded-editor-r1-install.log` and `toolchain/logs/native-floating-ime-install.log`
- Historical speed-request result: `androidtv/acceptance/2026-09-30-native-compose.md`, paragraph beginning “Official `cmd package compile -m speed -f -v`”
- Exact dependency sources: `toolchain/diagnostics/tv-material-1.0.0-sources.jar` (`Surface.kt`, `SurfaceScale.kt`, `tokens/SurfaceTokens.kt`) and `ui-test-android-1.8.1-sources.jar` (`ComposeIdlingResource.android.kt`)
