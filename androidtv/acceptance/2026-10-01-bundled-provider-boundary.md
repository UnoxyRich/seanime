# Bundled English providers and Android request boundaries

The Android distribution includes these exact, independently sourced provider
versions from [Seanime-contributions/Seanime-Providers](https://github.com/Seanime-contributions/Seanime-Providers/tree/641b9d842e5502d34fb923dac959129d3da9fc1a):

| Provider | Version | Scope |
| --- | --- | --- |
| AnimeHeaven | 1.1.4 | English subtitled anime |
| AniDB (anidb.app) | 1.0.1 | English subtitled/dubbed anime |
| Atsumaru | 1.1.2 | English manga |

Full MIT notice, original manifest/payload bytes, Git blob identities and SHA-256
checksums are included in `app/src/main/assets/providers`. The selected Bas1874
marketplace remains a configurable hosted URL; its catalog is not mirrored.
These JavaScript files are Go provider payloads, not presentation code. The
native APK contract permits only these three exact payload hashes and continues
to reject React runtimes and every other JavaScript/UI bundle.

## Installation and user choices

Android stages these providers before the embedded server starts, inside the
existing serialized host queue. Installed manifests contain the exact inline
payload, an empty `payloadURI` and a commit-pinned `manifestURI`. The real IDs
and English language declarations are preserved. They remain removable external
providers; they are not assigned Go's special `builtin` lifecycle identity.

Existing same-ID files, disabled state and configuration are preserved.
A durable ledger preserves deliberate removals across restart and upgrade.
Pending staged files distinguish a failed publication that can be retried from
a possibly completed publication followed by removal. Files and directories
are synchronized before the next publication step. Ambiguous/malformed data
fails closed and leaves existing files untouched.

Automatic seeding is limited to the ordinary app-private extension directory.
A configured custom/shared path or symlink redirection receives a startup
warning with instructions to install through Extensions; no fallback directory
is populated. This avoids claiming no-clobber atomic publication on storage
where Android API23 lacks the required public rename primitive.

## Android request scope

Provider-origin stream, subtitle, manga-page and extension artwork URLs carry
explicit origin context. They accept absolute public HTTP(S) addresses only.
Local schemes, URL credentials, ambiguous numeric addresses, local hostnames
and private/reserved IP ranges are rejected. DNS resolution must return only
public addresses before connection. Redirects are checked before following.

Media3 captures authority per MediaItem, covering child playlists, keys,
segments and subtitles as well as the selected master URL. Only exact inline
subtitle files created for that item receive a local-file exception. Old online
recovery records are classified by their existing source metadata; absence of a
new flag does not grant trusted-local access.

Provider headers stay bound to their original exact origin. Server credentials
are never attached merely because a provider destination matches the configured
API host. Reader/cover caches retain the trust context. Blocked artwork displays
the native unavailable-image state without making a request. Trusted server,
downloaded manga, local files, content URIs and explicitly configured LAN-server
flows retain their existing behavior.

Configured proxies are not bypassed. Provider requests fail closed with an
explicit error if the selected proxy's destination resolution cannot be
validated by this Android policy. No system proxy, VPN, DNS, credential or
security setting is changed.

## Boundaries not changed

This is an Android-origin request policy, not a sandbox for the unchanged Go
extension runtime. In particular, Android cannot enforce the server's later
DNS/redirect choices for:

- Manga downloads in `internal/manga/download.go` and
  `internal/manga/downloader/chapter_downloader.go`
- Server-side double-page image sizing in
  `internal/manga/chapter_page_container.go`
- Requested server-side media conversion and its input fetches

Provider code execution, live upstream compatibility and these server-owned
operations remain separate acceptance concerns. Go core, API routes, payloads,
modules and schemas have not been modified.

## Evidence and remaining checks

Thirty direct JVM checks passed for the bootstrap, directory resolution, exact
asset/license identities and existing host queue. They do not execute provider
JavaScript. The real-server startup/restart test now waits for all three loaded
versions through passive `/api/v1/extensions/all` inventory after asynchronous
loading. Full Android build and device results are recorded separately in the
[device review](2026-10-01-remote-ui-review.md).

Focused transport tests cover numeric/DNS rejection, proxy preservation,
redirects, header isolation, source-switch/recovery context and per-open Media3
guarding. The artwork pixel fixture uses a public-shaped hostname with an
explicit test-only DNS seam; the production resolver remains strict. This is
fixture request-to-pixel evidence, not a successful live provider fetch.

Reader instrumentation that asserts server credentials uses the actual
downloaded-manga response shape and generated owned pages. It does not stand in
for live external-provider image transport. Live anime/manga, actual provider
images and physical-device acceptance must still be established explicitly.

Known remaining origin-propagation work: custom-source catalog images are
classified explicitly, but shared detail/library artwork after opening a custom
title still needs that classification carried through. This candidate is not
release-final; the three bundled providers do not use the custom-source type.
