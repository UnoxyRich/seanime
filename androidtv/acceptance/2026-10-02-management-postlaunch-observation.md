# Management post-launch observation

R41 public source `2a1841111abf519833c1ecfaac3b8332b824f74b`, tree
`67af7c7dcbc20305ff12fb3224efd461d1efd605`, completed GitHub Actions run
`37003601587` with one remaining failing method: isolated library management.
The ordinary invocation has 180 passes, zero failures and five skips across 185
unique methods. Owned playback journey, raw-media playback and the standalone
external receiver each passed once, yielding 183 unique passes overall. All five
installed app/test pairs and all 210 retained file hashes were verified. Both
HTTP/1.1 and HTTP/2 platform TLS retirement regressions passed, as did the WAV
live-status/relative-seek method that had failed during R40 preparation.

R41 management launch completed in 3,124 ms on CI and 933 ms on the Mac. Neither
run reached cleanup. Both hit the 900-second command deadline; targeted app stop
succeeded, Gradle exited, and the runner retained evidence with exit 124. Its
manifest still shows only the fresh/imported index readbacks. The Mac recording
shows Library tools and the first owned file, so Manage was activated. This does
not prove its click helper's trailing idle wait returned.

The next candidate changes diagnostics only. It observes fixed post-launch actions
with MAIN/INSTRUMENTATION thread snapshots, preserving the existing calls,
assertions, dispatcher and timeouts. Launch and close retain their existing clock
driving. A short focused observation should identify the blocked statement before
another full management acceptance attempt. Automatic broad CI is intentionally
not requested for this diagnostic commit; it is not an acceptance or release build.
The driver compiles against real Android 35 and Compose test 1.8.1 APIs. All 105
collector tests pass. Mechanically removing the observation wrappers and rejoining
the three split scroll/click expressions reproduces the prior fixture byte-for-byte.
This establishes unchanged test operations, not successful Android management.

The source-supported hypothesis is a queued scroll task: Foundation 1.8.1's
`ScrollBy` semantics launches a coroutine, while Compose test's scrolling helpers
can repeatedly inspect unchanged bounds without advancing the queued test clock.
Compose's idling checks do not necessarily consider an arbitrary queued scroll
task as pending frame/recomposition work. The first Select control is below the
recorded viewport. This is a plausible mechanism, not a captured blocked stack.
Manage's trailing instrumentation idle wait and the readiness semantics queries
remain alternative locations until the fixed-stage observation establishes one.

Sources: [Foundation 1.8.1](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.8.1/foundation-android-1.8.1-sources.jar)
(`Scrollable.kt`), [UI 1.8.1](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.8.1/ui-android-1.8.1-sources.jar)
(`Modifier.kt`, `Wrapper.android.kt`), and [UI test 1.8.1](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.8.1/ui-test-android-1.8.1-sources.jar)
(`Actions.kt`, `ComposeIdlingResource.android.kt`).

R41 CI evidence ZIP SHA-256:
`721acd02a337ea9ef05dc5c40553edb6e6f4467037e184e0038dd21140bdc3e2`.
Mac targeted evidence ZIP SHA-256:
`801f7e65dc906e9d76dadef96990a7b6c5296e95f8518b3413a0cfaa45019eac`.
Mac TLS XML contains two passes; its very short invocation missed the collector's
automatic installed-pair snapshot. CI captured and verified the matching pair for
those same methods. Mac management's installed pair was captured and verified.

Actual provider anime motion and manga page pixels remain unverified. No network
policy, provider route, Go code, production UI, or public Release changes are part
of this diagnostic candidate.
