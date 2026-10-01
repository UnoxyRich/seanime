# Native TV player: presentation provenance and playback bridge

Date: 2026-09-30. Local `feat/native-compose-tv` changes; no publication implied.

## Clean-slate presentation

The player's app-owned presentation was rewritten after the explicit instruction
“dont use ui code from before. rewrite the entire ui optimized for tv”.
`NativeTvPlayerPresentation.kt` is newly authored Compose UI, not a wrapper around
the old controls. It provides an overscan-inset title, timeline, transport row,
options row, focus-aware choices, bounded error recovery, transient notices and
translated captions. Large focus fills/borders and an initial Play/Pause target
support remote use. Back closes a dialog, then hides the HUD, then leaves playback.
Media keys are routed independently in both the Activity and the dialog window.

The following former `NativePlayerActivity` presentation blocks were deleted:

- `useController=true`, `controllerAutoShow=true`, Media3 controller callbacks
- The FrameLayout of `trackButton` Android Buttons and its positioning/margins
- The LinearLayout error panel, TextViews, retry/return buttons and focus IDs
- `trackButton()`, Android AlertDialog and Media3 TrackSelectionDialogBuilder
- The translated-caption TextView and all player/coordinator Toast feedback

The Activity is now a ComponentActivity with a ComposeView. Its remaining
FrameLayout/PlayerView are engine surfaces only: `useController=false`,
`isFocusable=false`. Existing decoding, native subtitle rendering, header policy,
source checkpoints and lifecycle mechanics remain. Their reuse does not reuse
the old presentation. Subtitle style application is rendering configuration,
not a reused settings/control view.

Primary source files:

- `app/src/main/java/app/seanime/tv/NativePlayerActivity.kt`
- `app/src/main/java/app/seanime/tv/platform/NativeTvPlayerPresentation.kt`
- `app/src/androidTest/java/app/seanime/tv/NativePlayerLifecycleTest.kt`

## Converted-HLS original subtitles

The existing backend conversion intentionally emits video/audio HLS without a
subtitle stream. The new `NativeEventSubtitleOverlay` consumes the unchanged
original MKV subtitle event protocol, independently of Media3 text-track discovery.
It feeds authored codec-private styles and ASS chunks into native libass and draws
PGS PNG fragments at their original crop/canvas coordinates. It does not relabel
plain translated text as original styled subtitles.

Timing is milliseconds in `mkvparser.go` despite an older contrary comment.
The source conversion API explicitly guarantees the original VOD/TS timeline
with `timeOffset=0`; the native coordinator rejects a nonzero offset. There is no
extra resume offset. Playback IDs and increasing seek generations reject obsolete
sources/packets. The bridge is paused/rebuilt around player release, and saves the
original subtitle identity/selection for Activity recreation. Original embedded
font bytes are fetched through the existing authenticated attachments endpoint
and reloaded after recreation.

Preservation and bounds:

- Authored ASS layer/style/name/margins/effect/override text stay intact
- Real lowercase backend keys (`readorder`, `marginl`, `marginr`, `marginv`) are used
- Omitted/repeated ReadOrder values receive unique native packet IDs so independent
  UTF8-to-ASS lines are not discarded by libass
- Source headers are capped at 1 MiB before parsing; event queues at 4,000 entries
  and approximately 16 MiB; individual font data at 16 MiB and combined fonts at 48 MiB
- PGS dimensions are inspected before pixel allocation; the 32 MiB live allocation
  budget includes bitmaps retained by displayed/queued frames, not just cache keys
- Worker failures disable that source plane once, clear it and report via Compose;
  video continues rather than repeatedly throwing every frame
- Zero/nonpositive durations are rejected. The unchanged parser resolves PGS
  duration when a clear/next image arrives; this client cannot reconstruct an event
  the backend never emits

Implementation files: `NativeSubtitleProtocol.kt`, `NativeEventSubtitleOverlay.kt`,
`NativePlaybackCoordinator.kt`. Full original embedded ASS uses
`io.github.peerless2012:ass-media:0.5.1` plus its native dependency, integrated by
`NativeAssSession.kt`. `NativeFontConfiguration.kt` supplies an app-writable
fontconfig cache rather than upstream build-machine paths. For Anime4K source,
license, precision and resource boundaries, see `2026-09-30-native-anime4k.md`.

## Verification, without overstating runtime coverage

At 15:28 UTC, focused Gradle compilation passed production Kotlin, AndroidTest
Kotlin and all **92 JVM tests**, using the actual existing Go AAR and native media
prerequisites. Log: `toolchain/logs/native-fresh-player-tests.log` in the execution
workspace. Later small Compose notice/focus, dialog media-key and subtitle
recreation changes are delegated to the final aggregate check and are not claimed
covered by that earlier log.

Runtime tests authored/updated:

1. `NativePlayerLifecycleTest`: six tests retaining decoder/settings/source/error
   assertions and adding Compose initial focus, remote error choices, media keys,
   dialog→HUD→player Back, and fresh controller-disabled engine assertions
2. `NativeAssRendererTest`: real authored ASS parsing, animated glyph positions,
   nontransparent raster pixels, seek and lifecycle. A font is attached to the
   fixture, but glyph output alone does **not** prove that attachment rather than
   an identical system font supplied the glyphs
3. `NativeEventSubtitleOverlayTest`: generated tiny video-only HLS, real independent
   ASS pixel motion, PGS pixels, stale-seek rejection, Home/return, Activity recreate,
   Compose CNN Medium selection/persistence/Off and malformed-header containment

These tests must run against the final immutable APK pair. Compilation is not
runtime rendering evidence. The initial older isolated ASS run failed readiness;
the writable-fontconfig correction still requires its final device rerun. The
HLS test uses faithful protocol fixtures, not a claim of an end-to-end authenticated
server conversion run. The shader chooser test complements the separate actual
EGL shader tests; neither implies every real TV GPU or HDR source is supported.

## External-player entry and hosting

The native player's More dialog and error screen offer **Open in another player**.
The action checkpoints and pauses the native player, then uses Android's video
chooser. Native playback remains paused on return. The other app's playback
position, subtitle settings and completion are not synchronized back to Seanime;
authenticated external continuity and real third-party players are not claimed.

`NativeExternalPlaybackPlan` permits the current direct-stream UUID with an
existing Go media-route token. The token scopes `/api/v1/directstream/stream`
for 24 hours; it is not cryptographically bound to the UUID. Go separately
rejects a request whose ID differs from its current stream. The planner rejects
wildcard/client-identity claims and expired tokens, and performs a header-free,
non-redirecting byte-range probe before handing out any local HTTP address.
The raw `/api/v1/mediastream/file` route is permitted only for the current exact
`streamPath` after that probe; a password-protected raw route has no token-mint
API and remains unavailable. The mutable legacy `/mediastream/direct` route
does not have sufficient source identity in this client and is not shared.

Header-free external HTTP(S) media and a selected SAF document are also supported.
SAF resolution uses the existing persisted folder permission, but the outgoing
Intent grants only temporary read access to that one document, with no write,
prefix or persistable grant. Arbitrary file/content addresses, provider sources
requiring private headers, watch parties and active converted HLS are rejected
with a native explanation. Server passwords/hashes and signed client authority
are never added to an outgoing Intent or request.

Loopback HTTP uses a source/server-owner-bound foreground media-host service.
Normal `Mobile.setAppInForeground(false)` still runs after 900 ms without a
started Seanime Activity, so unrelated downloads/scanners remain suspended.
The raw/direct-stream byte routes continue to work in that state. Converted HLS
would return HTTP 503 while suspended and is deliberately unavailable for handoff.
The lease ends on return, cancellation, launch failure, source replacement,
task removal or the notification's Done action. Done releases host protection;
the existing raw route offers no per-stream revocation, so it cannot revoke an
already-open read without stopping the whole embedded server.

`AndroidExternalPlayerTest` retains its limited immediate scheme-dispatch/focus
coverage. The new opt-in
`AndroidIsolatedRawMediaPlaybackTest.nativeMoreHandsOwnedGoVideoToSeparatePlayerAcrossBackgroundAndReturnsPaused`
uses `isolatedNativeGoExternalPlayerFixture=true` and a separate cold process.
It writes manifest kind `native-isolated-go-external-player-v1`, retains the same
owned UUID-root/recovery/restoration safeguards as the raw-media test, and drives
the actual native action into an owned receiver in a distinct test APK UID/process.
It asserts the foreground host, real background transition, three exact anonymous
Go byte ranges over three seconds, paused checkpoint/focus return and lease cleanup.
This source/test addition is awaiting the coordinated build and immutable device
run; test authorship is not runtime proof. No Go/core/API changes or third-party
app/account installation are needed.
