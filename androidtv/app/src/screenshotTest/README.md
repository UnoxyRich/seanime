# Host Compose layout previews

These tests render the production Compose components on the host with Google's
Compose Preview Screenshot plugin. They require no emulator. Static data and
original geometric poster artwork keep the fixtures independent of network
services, accounts, the embedded Go server, and media decoders.

The production shell, collection page, toolbar, grid, discovery content, and
player overlay are called directly. `HostRendererSmokeTest` is intentionally a
pipeline-only fixture, not a production screen or acceptance test.

## Run

From `androidtv`, with the project's Android SDK and JDK configured:

```sh
./gradlew :app:updateDebugScreenshotTest --max-workers=1 --no-parallel
./gradlew :app:validateDebugScreenshotTest --max-workers=1 --no-parallel
```

The first command records reference images under `app/src/screenshotTestDebug/reference`.
Inspect every changed image before accepting that baseline. The second command
renders the same source and compares it to those images. Regenerating a baseline
alone does not prove that the layout is correct.

When the verified generated gomobile AAR and native staging outputs already
exist and only Kotlin layouts are being checked, both commands may append:

```sh
-x :app:bindGoMobile -x :app:buildAndroidFfmpeg -x :app:stageAndroidNdkCppRuntime
```

Those exclusions reuse existing native outputs. They do not validate native
engines, packaging, or a clean build. Never use them to claim those checks passed.

## Viewports and limits

- 960 × 540 dp and 1280 × 720 dp, television night mode, 160 dpi
- Output pixels equal layout dp so clipping can be inspected at actual size
- Standard font scale and selected 1.3-scale cases
- Dot-free preview names: plugin `0.0.1-alpha16` can truncate names at a period,
  causing different functions to overwrite the same reference image

These are static layout and visual-regression checks. Layoutlib does not establish
remote key delivery, acquired focus, focus scrolling, Android lifecycle behavior,
IME/SAF interactions, decoding, or real device behavior. Expanded navigation is
supplied drawer state, not evidence of acquired focus. Focus rings are not
simulated in these fixtures. Keep interaction tests separate.

Render and compare on the same host environment to avoid unrelated font and
renderer differences. Keep the Compose BOM aligned with production. This setup
uses the standalone screenshot plugin because the project remains on AGP 8.10.1;
the newer AGP test-suite migration is a separate build-tool upgrade.

Official setup and limitations:

- https://developer.android.com/studio/preview/compose-screenshot-testing
- https://developer.android.com/studio/preview/compose-screenshot-testing-release-notes
- https://developer.android.com/develop/ui/compose/tooling/previews#limitations
