# Native keyboard readiness and retained failure evidence

## R38 results and remaining failure

CI run 36985440482 for `1607d1d250688038d2414798f0280b1bf0009ab1`
passed host/layout/lint/APK and alignment checks. Ordinary device instrumentation
reported 183 unique methods: 177 passed, one failed, and five explicit opt-in
skips. The four separately invoked fresh-AVD flows each passed once without
skips, yielding 181 unique CI passes. All five installed app/test pairs matched
the build hashes, and all 54 retained evidence file hashes verified.

The remaining failure was
`AndroidTvStartupTest.remoteLetterKeysEnterNativeSearchWithoutStealingCursorFocus`
at the assertion expecting `ab` after real A and B key events. The test had not
yet sent Left. Its actual entered text was not retained. All five previously
failing R37 cases passed this run; that observation alone does not prove they
are reliable across repetitions.

The ordinary screenshot archive failed tar parsing despite a successful app
access probe. Its bytes were discarded, so the cause cannot be established
from R38 evidence. The four independent flows retained their screenshots.

## Test-only readiness correction

Compose semantic focus and idleness do not establish that Android routes keys
to a newly opened dialog window, nor that its expected text has been delivered.
The test now reuses the existing attached/laid-out/window-focus wait before
sending each letter, then waits within the existing ten-second budget for exact
editable text `ab`. Each key is sent exactly once. No semantic text replacement,
typing retry, forced focus, or production behavior change is introduced. The
original empty-editor, text, Left, and cursor-focus checks remain; the window is
also observed after Left.

This is a source-backed missing test readiness boundary. Without R38's actual
text/window state it is not proof of that historical failure's cause.

Up to six fixed keyboard observations and one failure snapshot record key
count, node count, text length and exact-fixture comparison booleans, semantic
focus, attachment/layout/window focus, and IME visibility. No raw editor text,
exception message, arbitrary window details or semantics dump is retained. A
failure reuses the last observed snapshot instead of issuing another potentially
blocking Compose query. The collector reads only the fixed file, validates its
schema, stage order, counts, consistency and invocation freshness, and retains
it independently of screenshots. JUnit remains authoritative for test outcome.

## Screenshot transport diagnostics

On a tar ReadError the collector retains only bounded archive byte count and
one fixed first-header classification. It retains no raw archive, header names,
remote shell output or exception message. Existing filename allowlist, symlink,
size, tar member, PNG and metadata checks remain. No quoting, adb authentication,
network route, or transport behavior is changed speculatively.

## Verification status

All 76 Python collector tests passed in 10.860 seconds, including corruption,
privacy, schema-consistency and independent-retention regressions. Diff checks
pass. Android compilation and a device run of the updated keyboard test remain pending at
publication. The previous tree's Mac cold generated-video scan/import/resume
pass is a separate result; it does not validate this keyboard change. Real
provider anime and manga page rendering remain unverified. No Release is ready.
