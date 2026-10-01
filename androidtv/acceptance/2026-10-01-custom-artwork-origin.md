# Custom-source artwork origin correction

This Android-only correction follows the immutable R27 source/APK checkpoint. R27's APKs and 34 screenshot references were not rebuilt or replaced during the focused work. The subsequent R28 aggregate passed430 JVM tests,34 unchanged visual comparisons, lint and both ABI builds; exact new-APK device acceptance remains pending. R27 device results do not validate R28.

## Behavior and scope

- `MediaCard` carries native artwork authority separately from unchanged raw server JSON. The exact `Long` custom-source boundary is `1L shl 31`, matching `customsource.IsExtensionId`; `2147483648`, `4294967297`, and `9007199254740991` remain exact.
- Custom catalog origin survives navigation into shared detail routes. Shared library/discovery/picker/poster/manga artwork uses the model's authority. Anime cover/banner, episode thumbnails and cover fallbacks, characters, relations/recommendations, offline rows and playlist thumbnails retain the source's image policy.
- Related AniList IDs returned inside custom-source metadata do not turn those provider-supplied image fields into trusted server artwork.
- Public custom artwork uses the existing public-URL/DNS/redirect policy, including when its hostname equals the configured API origin. It receives no Seanime session credentials. Provider local URLs and forged local markers remain rejected in direct custom catalog/detail/metadata contexts; a public URL aimed at the API cannot acquire API authority.
- Explicit Offline and downloaded views may load an own-title local image through a narrow static-asset capability, including while the server is online. Active offline platform entries may grant the same capability after an authoritative status read. Stored playlist markers are compared with a freshly loaded active offline entry before receiving it.
- The local capability accepts only a canonical `GET /offline-assets/{exactMediaId}/{single safe image filename}` on the configured server, with no body, query, fragment, encoded separators, traversal, credentials, or cross-origin destination. Its separate image client drops all caller headers, adds no server credentials, and never follows redirects. Its memory-cache identity is separate from ordinary trusted and provider images. An image shown in the Offline view cannot make a later direct-provider marker valid through a cache hit.
- Ordinary noncustom server-relative, LAN and server-origin artwork retains its existing behavior. No Go core/API/schema, React/legacy UI, provider configuration, proxy, or security settings changed.

## Existing Go route evidence

`internal/core/echo.go:97` registers `/offline-assets` as an Echo static directory rooted at `Config.Offline.AssetDir`. `internal/handlers/local_security.go:63–64` limits the trusted-local request boundary to `/api/` and `/events`. `internal/handlers/routes.go:123–127` applies optional password authentication to the `/api/v1` group, not the static asset route. Consequently a native static image request needs neither the server password token nor signed client identity.

The pinned Echo `v4.15.4` `echo_fs.go:65–98` cleans the static wildcard path and may redirect directories to a trailing slash. The Android capability therefore forbids any path structure beyond the exact title directory and a single safe image filename, verifies the canonical encoded path again in the transport, and disables all redirects. Server image-downloader output is one generated image filename under the title directory (`internal/local/sync_helpers.go:82–125`, `internal/util/image_downloader/image_downloader.go:220–244`); no query or nested path is needed.

`/local/track` prefers stored local entries but may fall back to live collection entries using the identical JSON shape (`internal/local/manager.go:466–514`). `/manga/downloads` uses active collection media unchanged (`internal/manga/download.go:348–386`). Neither route establishes general provider URL trust. They are used only to authorize the confined credential-free static-asset lookup. A provider's forged marker never grants server/API request authority.

## Focused validation

On 2026-10-01, the focused r3 command passed in 37 seconds with **60 tests, zero failures, zero errors, zero skips**. It ran `:app:testDebugUnitTest` for `MediaArtworkOriginTest`, `NativeArtworkHostTest`, `NativeAnimeMetadataModelsTest`, `NativeMediaResponseTest`, `NativeCustomSourcesTest`, `NativePersonalCollectionTest`, `NativeImageTransportTest`, and `NativeArtworkUrlTest`.

The command used the existing API-34 Robolectric init, a fresh 2 GiB single-use Gradle daemon, `--max-workers=1 --no-parallel`, the existing offline Go module cache, and a separate artwork evidence directory. It did not run assemble, screenshot validation, or screenshot reference updates. Log: `toolchain/logs/native-custom-artwork-origin-focused-r3.log` in the task workspace.

Production Compose fixtures use loopback-only MockWebServer image bytes and a test-only DNS mapping for public fixture hostnames. They prove decoded pixels and actual request headers for same-API-origin provider covers, metadata banners, characters and low-ID related cards; blocked local detail/metadata paths; offline custom cover/episode assets; and custom tracked/downloaded covers online and offline. They also prove that returning to direct-provider context cannot reuse local asset pixels. Transport tests reject API paths, other-title paths, traversal/encoded separators, nonimage filenames, query/fragment, POST, cross-origin targets, and redirect expansion. Existing ordinary server/LAN/offline-marker artwork tests still pass.

The first focused attempt stopped at a test-fixture `Dns` construction compile error. The second ran 60 tests with one overstrict request-count assertion: Coil requested a second decode when a cover changed display size. The third corrected the assertion to check the actual security requirement (no new request or trusted pixels in direct-provider context) and passed. No production fallback was weakened to make a test pass.

These fixtures do not establish live-provider availability, hardware rendering, real account behavior, or acceptance of a rebuilt APK.
