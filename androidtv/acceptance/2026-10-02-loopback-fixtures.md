# Local HTTP fixture isolation — October 2, 2026

R36's Mac diagnostics distinguish fixture routing from image lifecycle cleanup.
The lifecycle DNS latch fails before its injected resolver because the provider
policy rejects the inherited proxy. Two provider-image cases report that same
terminal policy rejection. The custom-detail API case selects HTTP and ends
with EOF before response headers. These observations justify explicit routing
for local test servers; they do not justify changing application routing.

The internal `NativeImageTransport` constructor now accepts an optional,
default-null selector. An override is installed before the existing provider
policy wraps the client. All production callers omit it. Lifecycle and artwork
fixtures use a per-client DIRECT selector restricted to their explicit loopback
aliases, with a resolver restricted to the same names and fixed loopback bytes.
There is no global selector, environment, system proxy, VPN or DNS change.

Two new real-client regressions verify the fixture reaches only its own server,
the omitted override preserves inherited selection, and HTTP/SOCKS overrides
still fail with the exact terminal policy exception before DNS or socket
creation. Unknown fixture aliases are rejected. Existing lifecycle, header,
pixel, main-thread and timeout assertions remain intact.

The immutable working snapshot based on local `1d332cf0` (tree
`ae5b4960fbac9a26bb73c4a35afcb138d6545288`, identical to published `f5f70fae`)
passes **32 tests: 14 lifecycle, five image transport, ten provider policy and
three redirect boundary**, with no failures or skips. Kotlin 2.2.10/JVM 17 on
Java 21 reuses the previously checksum-verified Maven closure. Compilation takes
9.326 seconds. NativeArtworkHostTest is inspected but is not compiled or run by
this standalone harness. Its Android/Compose verification remains required.

The first fixture attempt compiled but omitted MockWebServer's `localhost`
alias, causing ten existing lifecycle failures. That evidence is retained
separately. The corrected snapshot includes the known alias and passes all 32.

The separate Main-callback timeout has a source-backed host synchronization
correction. In the pinned [Compose ui-test 1.8.1 sources](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.8.1/ui-test-android-1.8.1-sources.jar),
`waitUntil` advances only the Compose coroutine scheduler and sleeps; it does
not drain the Android Handler queue. Semantics queries instead call
`waitForIdle`, whose Robolectric strategy explicitly drains that queue.
[Coroutines 1.10.2 Main.immediate](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.2/ui/kotlinx-coroutines-android/src/HandlerDispatcher.kt)
posts an off-thread HTTP continuation to the main Handler, and
[Robolectric 4.15.1 PAUSED](https://raw.githubusercontent.com/robolectric/robolectric/robolectric-4.15.1/shadows/framework/src/main/java/org/robolectric/shadows/ShadowPausedLooper.java)
requires that Handler work to be explicitly idled. Thus a response arriving
after `setContent`'s initial idle can finish HTTP while an atomic-only wait
leaves its Main continuation queued.

The host test now gates its fixture response until `show` returns, then pumps
only due Android Main messages inside the same 10-second completion wait.
The server gate has a bounded 10-second wait and is always released in `finally`.
Existing effect/error diagnostics and before/after Main-thread assertions stay
intact; production `FeatureAction`, dispatchers and routing are unchanged.
The gate prevents a fast fixture response from hiding the timing gap.

R36's completed HTTP request and zero network failures are consistent with this
mechanism, but its unavailable action-state/queue capture means attribution of
that historical Mac timeout remains unproven. This later host-test correction
has not yet been compiled or run on Android/Compose; no Mac pass is claimed.

At 06:09 UTC, R36 CI run 36970506566 had passed its host-test/layout/lint/APK and
alignment gates and was running device instrumentation. This is not a result
for this later fixture patch. At that checkpoint, the selected Linux runtime
verification was awaiting SDK agreement acceptance, and the ordinary public
AniList probe had returned Cloudflare HTTP 403.
Real anime playback, manga page rendering and the cold continuity/teardown
journey remain open. Public release remains held.
