# Player HTTP retirement and bounded management validation

This is a follow-up candidate to public source `55b38a23121757c80068c7884813102c6c521559`
(tree `c2efba1a816035b47470bc6dd4c0a2c699238ec5`). It is not a release acceptance report.
Actual provider anime playback and manga page rendering remain open.

## Production correction

The previous player release path called `ConnectionPool.evictAll()` directly from
Activity lifecycle callbacks. OkHttp 4.12 closes an idle TLS socket synchronously
during eviction. Android's TLS close can write a close-notify record and therefore
hit `NetworkOnMainThreadException`. This is independent of the earlier artwork
transport retirement correction.

`NativePlayerHttpClients` gives each player generation its own pools and dispatchers.
Retirement synchronously rejects future calls and detaches ownership, then cancels
captured calls, evicts pools, and shuts down their dispatchers on shared IO. A response
body released by Media3 after initial retirement schedules another eviction of only
its old pool. Player, surface, and subtitle lifecycle work remains on Android Main.
The next player generation retains independent clients. Request headers, provider
destination checks, proxy policy, timeout values, and Go code are unchanged.

Five focused JVM tests pass against OkHttp 4.12 and real loopback TLS: a legacy
SSLSocket-close negative control; nonblocking retirement during a blocked TLS close;
replacement ownership; immediate/idempotent rejection of saved and cloned calls;
late HTTP/2 response release; and cancellation while DNS is in flight. These five
methods cover the listed subcases. Android regressions additionally require actual
platform TLS and StrictMode behavior for HTTP/1.1 and HTTP/2. Their device result is
pending; the JVM result alone does not establish Android or provider acceptance.
The production owner and both Android TLS test methods compile against the real
official Android 35 API and pinned AndroidX test artifacts with Kotlin 2.2.10/JVM
17, excluding host JDK API stubs. The management clock/ownership helpers also
compile against real Android 35 and Compose test 1.8.1. These narrow checks do not
compile the complete Activity/app, package APKs, or execute Android behavior.

## R40 retained results

GitHub Actions run `36996694821` passed host tests, layout checks, lint, APK builds,
and alignment. Its ordinary Android invocation has 177 passes, one failure, and five
skips across 183 unique methods. The new failure is the initial local WAV preparation
in `NativePlayerLifecycleTest.serverStatusAndRelativeSeekReadLivePositionInsteadOfCachedProgress`,
before that method's live status/seek assertions. Its exact Media3 cause is pending
terminal logs. The separate owned library playback journey passed once.

The isolated management invocation stalled. On the Mac, the same R40 source retained
`existing-import-endpoint-verified`, outcome `running`, and no later UI checkpoints.
After 1,048 seconds the target process was stopped on its fresh task AVD. Its one
JUnit failure has no retained assertion type/message. This establishes a launch
stall, not a failed library action. The three separate Mac settings, optional-clear,
and folder-match methods each passed once. Settings recorded six phases with no
whole-config mismatch. All four installed APK pairs and twelve retained file hashes
were independently verified.

Mac evidence ZIP SHA-256:
`a8b2ec8dcccd70cde1ed0587462e4c7546b35721358e5d8d5468de92a0f41009`.
Mac application SHA-256:
`1b9ee2fdf0aeef85b9a7f24b2fac106750df8457599afa269f8c97e14d67d0f8`.
Mac instrumentation SHA-256:
`17fda35b02d54d48e1f4b8e6c5b2264fdf0c5437fcbbf6d3059f6c96e7bc63b1`.
These Mac bytes are distinct from GitHub's builds; results must remain paired with
their recorded source and installed artifacts.

## Test-harness correction under validation

The R39 management failure identified Android Dialog disposal off Main under the
default unconfined Compose test dispatcher. R40 changed this one fixture to a
StandardTestDispatcher. That queued scheduler needs clock advancement during
ActivityScenario startup, while synchronous launch may itself wait for Android
idleness. Pinned ActivityScenario 1.6.1 and Android API 36 sources confirm that the
initial idle/launch waits precede the scenario's 45-second lifecycle timer and can
wait without a timeout. Interrupting a Future does not reliably cancel them.

The test-only experiment keeps queued Main-thread effects, explicitly owns late
launch results on one worker, and drives Compose's clock during the 45-second
launch and close waits. Its original 30-second navigation budget, UI/API assertions,
and action sequence remain intact. Six focused JVM ownership tests pass, including
25 publication/close races and cleanup after abandonment. This is not Android
launch acceptance. Clock advancement can itself wait for Main, so the external
process deadline remains necessary.

Fixed-role MAIN/INSTRUMENTATION/LAUNCH stack observations at 15 and 30 seconds omit
thread names and request data. Exact owned-fixture invocations receive a 15-minute
process deadline; retained successful runs took 58–123 seconds. A deadline breach
stops only the target app on the selected emulator, allows a bounded Gradle exit,
and preserves available evidence with failure status even if Gradle later exits
zero. It never clears app data or kills an emulator/Gradle daemon. Ordinary-suite
timing remains unchanged. A passing Android result is still required before this
management flow can be accepted on the changed harness.

All 100 Python collector tests pass, including process exit races, failed target
stop, client-only termination, deadline failure despite later zero exit, schema and
freshness rejection, and preservation of XML, fixture data, screenshots, and the
sanitized watchdog JSON. Raw watchdog text is not included in the evidence archive.
Missing or malformed watchdog observations do not silently discard other evidence
or turn a failed invocation into a pass.

Pinned source references:

- [ActivityScenario 1.6.1](https://dl.google.com/dl/android/maven2/androidx/test/core/1.6.1/core-1.6.1-sources.jar),
  `ActivityScenario.java` launch idle waits and lifecycle-state timer;
  `InstrumentationActivityInvoker.java` API 28+ two-argument launch
- [Android 16 Instrumentation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r1/core/java/android/app/Instrumentation.java),
  two-argument `startActivitySync` and idle wait interruption behavior
- [Compose test 1.8.1](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.8.1/ui-test-android-1.8.1-sources.jar),
  queued recomposer startup, `waitUntil`, and Main-thread clock advancement

No real-provider request, network-policy change, or public Release is part of this
candidate. Physical TV/remote, USB/storage, real provider media, and account-specific
gates remain separate from owned fixture results.
