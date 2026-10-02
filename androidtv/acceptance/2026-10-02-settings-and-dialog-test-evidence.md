# Settings preservation and dialog test evidence

## R39 results

CI run 36990705710 for `8c23401efe871b1cf0ebe17094d2a636628ec116`
passed host/layout/lint/APK and alignment checks. The ordinary suite reported
175 passes, three failures and five explicit opt-in skips of 183 unique methods.
The owned playback journey, raw media route and external receiver each passed
once without skips; isolated management failed. The combined CI result is 178
unique passes and four failed methods. All five installed pairs and all 210
retained file hashes verified. Ordinary screenshots were retained in this run,
and the keyboard case's six typed observations passed. That does not establish
the cause of the prior screenshot transport failure.

The separate exact R39 Mac keyboard case passed with A, B and Left injected once
each. Its six observations retained exact `ab`, editor focus and actual Android
window focus. This is instrumented input on a Mac-hosted emulator, not physical
TV/remote acceptance or provider streaming.

## Narrow readiness checks

Two ordinary UI failures lacked a completed readiness boundary:

- Optional-setting clear asserted a local callback result, without distinguishing
  no callback from an incorrect value. The test now observes natural editor and
  window focus before one clear, asserts the exact empty draft, sends one real
  Save, then requires one callback and the empty value within ten seconds
- Folder matching asserted title visibility immediately after replacing the
  picker with a confirmation dialog. The test now requires picker removal and
  the existing active-window Cancel focus before the original exact-title,
  screenshot, Apply and payload assertions. It does not scroll the title into
  view or substitute a semantic click

Production UI is unchanged. These additional observations do not prove the
historical failures' causes.

## Isolated management test dispatcher

The isolated failure's exact message was `Method removeObserver must be called
on the main thread`. Its stack traversed Compose dialog disposal and both
`ApplyingContinuationInterceptor` frames after websocket delivery.

Pinned [Compose test 1.8.1 source](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.8.1/ui-test-android-1.8.1-sources.jar)
selects `UnconfinedTestDispatcher` for an empty test context. Its continuation
interceptors can send snapshot apply notifications inline on the emitter
thread. Production instead uses the lifecycle-aware window recomposer and its
owning Android UI Handler, as defined in
[Compose UI 1.8.1 source](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.8.1/ui-android-1.8.1-sources.jar).

Only the isolated management fixture now uses the supported
`createEmptyComposeRule(effectContext = StandardTestDispatcher())` overload.
It queues test effects through the controlled scheduler. The existing Compose
test dependency already exposes this dispatcher; no dependency or global Main
change is required. Every test method body, action, assertion and timeout is
unchanged. Websocket transport and production event delivery are unchanged.
A fresh device run must still validate this correction.

## Settings failure diagnosis

The backend test failed its full-configuration comparison after the second,
numeric PATCH. No differing value was retained. Android Double, Go float64 and
the existing numeric canonicalization represent the fixture's 0.625/0.375 exactly;
there is no evidence justifying a numeric or production fix.

The original serial PATCH order, strict comparisons, invalid-write rejection and
reverse rollback remain. A test-only helper now records the comparison phases,
fixed section equality flags, equality/type enums for seven known fields, and
separate audit-time equality. It retains no setting values, arbitrary input
keys, paths, credentials, value lengths or hashes. The collector independently
validates and retains this fixed file; normal save timestamps are not reported
as mismatches. Rejected-write timestamps remain part of the strict assertion.

Existing Go passes cached Discord settings into an asynchronous module that
resets two deprecated flags. This is a candidate mutation mechanism, not a
confirmed cause of R39: cleanup apparently restored equality. Also, a separate
HTTP reader still uses Go's `CurrSettings` cache, so that check establishes API
readback rather than an independent SQLite reload. Go remains unchanged.

## Validation limits

The pure settings helper compiled with Kotlin 2.2.10/JVM17 and passed seven
focused JUnit tests, including private input exclusion and exact mismatch/type
observations. All 88 collector regressions passed in 16.123 seconds, covering fixed schemas,
ordering, freshness, size/link limits and independent retention when screenshots
fail. Exact named settings/keyboard invocations also retain their own diagnostic
automatically; other selectors do not broaden collection. Android compilation and the four focused device cases remain pending at publication.

The prior Mac R38 cold generated-video scan/import/play/resume test is verified
for its exact pair, including all four timestamp-derived compositor pixel
checks and 10.265-second saved position reopening at 10.328 seconds. Its empty
history filepath remains an existing backend limitation. Real provider anime
and manga page rendering remain unverified; no public Release is ready.
