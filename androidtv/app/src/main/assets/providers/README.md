# Bundled English providers

This Android distribution includes only these explicitly approved versions from
Seanime-contributions/Seanime-Providers at commit
`641b9d842e5502d34fb923dac959129d3da9fc1a`:

- AnimeHeaven 1.1.4, ID `animeheaven`, English subtitled anime
- AniDB 1.0.1, ID `anidb`, English sub/dub anime, using **anidb.app**
- Atsumaru 1.1.2, ID `atsumaru`, English manga

These are third-party extensions, not Seanime built-ins. Their source is licensed
under MIT, Copyright (c) 2025 Pal. The complete notice is in [LICENSE](LICENSE).
`provenance.json` records the immutable source paths, original manifest URLs, Git
blob hashes, SHA-256 checksums, byte sizes and license source. Manifest and
JavaScript assets are exact upstream bytes; no other marketplace is mirrored.

The bootstrap runs on NativeHostQueue before `Mobile.startServer`. It verifies
the recorded hashes and IDs, then writes an ordinary external extension manifest
with the exact JavaScript in `payload`, an empty `payloadURI`, a commit-pinned
real `manifestURI`, `lang=en` and `isDevelopment=false`. Seeding needs no network
and does not evaluate JavaScript. Once the existing Go server loads the installed
extension, that server can evaluate it and the provider can contact its upstream
service. Go's existing background update checks may also fetch upstream manifests
and payloads; this bootstrap neither suppresses those checks nor applies updates.
Bundling alone does not establish that upstream service availability,
episode lookup, playback, chapter lookup or page loading works.

Existing IDs are discovered recursively, independently of filename, version,
validity of provider code or disabled state. Their files, configuration and
selection remain untouched. Unparseable extension JSON causes bootstrap to stop
with a warning rather than potentially shadow an unidentified user extension.
The durable `.android-bundled-provider-ledger` is outside the extension scan and
prevents later bootstrap upgrades, replacement or reinstallation after removal.

Publication uses a fsynced non-JSON stage, a durable pending ledger record, a
same-directory rename and a completed ledger record. A still-present pending
stage can retry a failed first publication; a missing stage means publication
may already have happened, so an absent provider is treated as a removal. A
crash after publication does not cause a later user removal to be reversed.
Changed pending stages and corrupt/unknown ledger formats are left untouched
with warnings. Automatic seeding never repairs or deletes user files.

Automatic publication is limited to the default app-private
`files/seanime/data/extensions` directory. API 23 does not offer a no-clobber
atomic rename through its public Java API, so a custom/shared `extensions.dir`
or a symlinked default directory is deliberately skipped. A startup warning
directs the user to install providers through Extensions; the bootstrap does
not fall back to the default, move existing providers, or change config.toml.
Changing the directory after installation does not repopulate the new path.

The config reader recognizes ordinary `[extensions]`/`dir` and dotted/quoted
key forms with absolute paths and known environment expansion. Ambiguous,
multiline or otherwise unsupported TOML is reported and leaves everything
untouched. These warnings are recorded under the `SeanimeTV` Android log tag.
