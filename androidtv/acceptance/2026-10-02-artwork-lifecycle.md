# Artwork lifecycle correction — October 2, 2026

This is a source candidate, not a passing device result. R34's focused cloud JVM
validation passes 49 tests using the unchanged production classes. The Mac
compiles that same tree but its 35-test host selection has 30 passes and five
failures, described below. Full Android/device acceptance remains open. The
workflow now requires JVM tests, lint and existing screenshot comparisons before
its APK/device gate. No new screenshot baseline is recorded.

The recovered cloud checkout passes 65 collector tests, seven alignment-verifier
tests, all 15 static native/API boundary checks and `git diff --check`.
The Go-compatibility test command cannot run its toolchain-dependent cases because
the replacement executor has no Go language toolchain/module cache (five setup
errors); these are unverified here, not successful compatibility results.

## R34 focused verification and remaining host failures

Local source `41e051fffa759724a2713fb730a214b878beb144`, tree
`14bdafc651fc50ac96b2da936cc3f22444f85985`, passes 12 lifecycle, five transport,
nine provider-policy, three redirect-boundary and 20 API-client tests: **49 unique
passes, no failures or skips**. Thirteen actual production files compile unchanged
with Kotlin 2.2.10, JVM target 17 and Java 21. The separate harness uses 17 official
Maven artifacts (66,366,330 bytes), checked against published checksums. It uses
no substitute Android/app classes. This does not validate Android packaging or
Compose behavior.

The Mac independently applies the exact tree as local commit
`4aa17af07d385cb6f12802ce0cb5a498ce3c32cd`. Its focused Gradle selection contains
35 cases, with **30 passes and five failures**:

- `NativeImageTransportLifecycleTest.retirement during DNS prevents a request from connecting after cleanup finishes` times out at the injected-DNS entry latch, before cleanup assertions
- `NativeArtworkHostTest.customDetailMetadataAndRelatedCoversDecodeAtApiOriginWithoutItsCredentials` times out awaiting an image description
- `NativeArtworkHostTest.productionCustomSourceUsesItsExistingCoverImageContract` times out awaiting an image description
- `NativeArtworkHostTest.productionMangaProviderMatchPassesImageHeadersAndDecodesItsCover` times out awaiting an image description
- `NativeArtworkHostTest.featureActionsResumeHttpWorkOnAndroidMainThread` times out awaiting its completion flag

The original failure evidence lacks callback exceptions and worker route state.
A post-run JDK proxy snapshot does not establish which routes those calls used.
No timeout, pixel, header, focus or payload assertion is weakened. The diagnostics
delta retains original timeout objects and adds bounded phase, API callback,
selected proxy type, DNS-entry and main-callback observations. Artwork errors use
the existing debug-only redacted logger; URLs, headers and exception messages
are not logged. These Android-dependent additions still require Mac validation.

## Independently reproduced terminal-rejection bug

The provider policy's intentional proxy rejection originally threw a generic
`IOException` during OkHttp route-selector construction. OkHttp 4.12 retries
that failure before DNS; a controlled per-client probe made 11,063 selections
in 250 ms before its safety timeout. This is independently reproducible and is
not proof that the Mac selected a proxy.

R35 changes only this intentional rejection to `ProtocolException`, preserving
the same rejected routes and fixed message. A new HTTP/SOCKS regression requires
callback completion, that exact exception/message, one selection, zero DNS and
zero sockets. Its timeout is a failure, never an accepted result. The regression
fails against the old source with a timeout and passes against the correction;
the complete focused JVM selection is **50/50 passed**. No proxy/system/network
setting or accepted destination changes.

## Observed production failure

The Mac cold scan test of published source
`83be8b3ce4b5ceb3dcaf9a7b9fb8466cb1f2875a` runs one case, fails once and skips
nothing. Public AniList metadata, automatic file matching, scan export/import,
the exact indexed-path Go stream and generated-video pause/seek checks pass.
Returning from playback destroys the activity's Compose artwork owner, where
`NativeImageTransport.close()` calls `ConnectionPool.evictAll()` on the main
thread. The retained JUnit reports `NetworkOnMainThreadException` at line 81.
Continuity readback and resumed playback never complete.

Mac app SHA-256: `dbef3b1f2ec4470cb807545b9fe41df259c2531645686cd5fcd19434ea0b61c4`.
Mac test SHA-256: `394066fe0390c0851edb7321ebed170844bed3bfb44eb82405bdd6d617c3588f`.
Both use signer `71f1988113a28d72e8e3aaa5ab60c8cca067c1d1c5b69286de43c80816408c02`.
Evidence ZIP SHA-256: `0007da7763fab199014e3cc9466ea9df2f338dc29cb8b44dc0a2bf13178f023a`.
These are Mac rebuild identities, not binary-identical cloud APKs.

The fixture intentionally produces uniform neutral grayscale frames. The HUD
darkens the retained 10/30-second screenshot. Its appearance alone does not
establish either successful visible video or a black-surface defect. The original
listener/buffer counter proves decoder progress only; the revised cold test
adds timestamp-derived compositor pixels and a HUD-hidden capture.

## Lifecycle correction

Transport retirement becomes immediate and idempotent. Finite cleanup runs on
shared IO after disposal, cancels active requests and open response bodies,
shuts down the owned dispatchers and evicts pooled connections. Requests saved
or cloned before retirement cannot start network work afterward. A late released
connection is evicted off main as well.

Coil 2.7.0 also invokes `Call.cancel()` directly from a disposing coroutine.
Transport-owned call wrappers therefore publish cancellation immediately and
schedule the socket cancellation on IO, retaining callback identity and separate
clone state. The transport registers each delivered response body before handing
it to Coil, and releases it on cancellation even if prompt coroutine cancellation
discards that response before its consumer runs. Calling OkHttp `cancel()` alone
does not finish a delivered response body. Cleanup interrupts its socket before
serializing delegate closure against reads, without clearing the decoder's outer
buffer on another thread. Both Compose owners retire their transport before shutting down
their Coil loader. Provider DNS/redirect policy, credentials and offline-asset
boundaries remain unchanged. There are no Go core/API/schema changes.

Twelve focused regressions exercise real TCP sockets with deliberately blocked closure,
idle eviction, pending requests, held bodies, saved calls, cloned calls,
retirement during DNS and repeated owner replacement. Abandoned execute/callback
responses and the canceled-coroutine handoff have explicit resource-release
checks. An HTTP/2 prior-knowledge fixture checks allocation release, reuse of the
same TCP connection, then off-main retirement; production protocols are unchanged.
Source review is not a
substitute for executing these tests and repeating the actual activity teardown.

## Separate R33 CI result

[Run 36937126498](https://github.com/UnoxyRich/seanime/actions/runs/36937126498)
is terminal **failure**. Its ordinary suite has 177 passes, one failure and five
explicit opt-in skips among 183 unique methods. The failed plugin method stops
in fixture startup before its tray/focus assertions. The exact missing startup
signal was logged only to logcat and is absent from retained evidence; no cause
or production plugin fix is inferred. Fixed-schema per-method diagnostics are
added to the collector without changing the ten-second wait or assertions.

Each of the four separate owned-media/management/external-handoff invocations
passes once without skips. Across these runs there are 181 unique passed methods;
the fifth cold metadata-scan case remains independently failed on the Mac.
All five CI installed app/test pairs match their build hashes. The final
artifact's 211 retained file hashes were verified, including sanitized JUnit.
Artifact 11199932612 SHA-256:
`a39cdee28712c8e902e838cdc47b9b2c4bd5980c0195b0d21bdac9c4ef37916f`.

CI x86_64 app: `facd0a40d1324b0135ac49d7c7de7fd73817fd3d2dad84da5bd0d3a1bdea51a3`.
CI ARM64 app: `27a8f99f6054a6dd835e504d42334327b074bcdf444f5a0376672c45b0301ccd`.
CI test APK: `910738b07c453df89554888270e44523de0a461be4abcabec4ec11c35c248dd8`.

## Still required

- Execute the corrected host/collector/layout/lint/build checks and verify exact APK identities
- Repeat the cold scan/import/visible-playback/continuity/resume case and actual Compose teardown on the Mac
- Resolve or precisely diagnose plugin startup on the next unchanged-assertion device run
- Verify real Bloom playback and Atsumaru page pixels on an owner-authorized network configuration; current provider DNS answers are rejected before connecting to that destination
- Retain the existing physical TV/USB, device codec/GPU/HDR, account/provider, independent external-player and multi-peer gates

Public release remains held. Prior passing results do not automatically apply to
the modified transport or later APKs.
