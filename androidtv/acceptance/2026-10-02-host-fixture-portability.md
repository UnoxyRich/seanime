# Host fixture portability follow-up

The Mac R37 full host run reported six failures: one custom-artwork provider
proxy rejection, two redirect tests that did not reach the expected origin, and
three provider-directory string comparisons. The R37 focused 67-case run passed.
These failures do not establish changes to production routing or a device pass.

The directory fixtures now compare canonical paths and cover real symlink
aliases. The original three positive cases failed under an aliased temporary
root before this correction; all original TOML inputs and fail-closed assertions
remain. Production path handling is unchanged.

The artwork and redirect fixtures now explicitly use per-client DIRECT routing
and loopback-only DNS for four allowlisted fixture aliases. No process-global
selector, OS proxy, VPN, or production default is changed. One controlled HTTP
proxy is rejected by the provider policy with exactly one selection and zero
origin requests. Another local mock proxy closes before response headers and
must produce the observed EOF cause, rather than pass through a timeout. The
corrected cases then complete their real source requests and retain all original
redirect, private-target, credential and capability assertions.

The final five-suite closure passed 37 unique tests under each of normal and
aliased temporary roots, for 74 successful executions, zero failures and zero
skips: MediaArtworkOriginTest 5, ProviderRedirectBoundaryTest 3,
ProviderUrlPolicyTest 10, NativeImageTransportLifecycleTest 14, and
ProviderExtensionDirectoryTest 5. Kotlin 2.2.10/JVM17 and the existing verified
Maven closure were reused without downloads. Compilation exited zero in
13.928 seconds.

Final changed HTTP-fixture SHA-256 values:

- MediaArtworkOriginTest.kt: `b0df4147110cc4c79ec94e97e250ed6ddf5c4a575929c5a224f452693dc666c7`
- ProviderRedirectBoundaryTest.kt: `2134e84a6989f9e1549854f857593c02e7e9ddcf66b89e441451b4d6edce918f`

The Linux closure does not include the separate cold Android instrumentation
edit. A complete Mac host run and the corrected cold device flow remain pending.
The 28 Mac screenshot mismatches are unresolved; no image baseline was replaced.
R37's five ordinary device failures also remain unresolved and are not erased by
these host-only results. No real provider anime or manga page success is claimed.
