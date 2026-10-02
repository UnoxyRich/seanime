# Clock-driven management scroll candidate

R42 source `8180c2ee36b4d29cc45fa03074edb6883b561d7d` located the management
stall at `SCROLL_FIRST_FILE`. Both the 15-second and 30-second instrumentation
snapshots show `ActionsKt.scrollToMatchingDescendantOrReturnScrollable:286`, the
semantics refetch after `ScrollBy`, through `performScrollToNode` and `scrollMain`.
Launch, navigation, activity access, Manage, library readiness, and first-file
lookup had all completed. The test was deliberately interrupted after those
observations; it did not complete its management assertions.

The fixed observation JSON SHA-256 is
`d75c76432778b358e4b77fd383765f44856f587b997c6beebcc3a334eacf54d6`;
the retained diagnostic archive SHA-256 is
`567d8d6a23d96a24f62edcb65701843377e46d8c15f4c9f5b792b5bdabf7a40e`.
The exact installed Mac pair was verified: application
`e08f82757d145be1303358f6fecd43eef14d255d956e237925f8f7d7a8fbe994`,
instrumentation `53f8a853aa57080ef9478ca9363d624f0a35b9bacebd6e5d451d8751d73220fd`.

The candidate changes only test synchronization. Each existing `scrollMain` chain
and the direct bulk-dialog scroll still executes on the instrumentation thread,
using the same selectors and Compose scrolling APIs. A temporary owned worker
advances the public Compose main clock so queued StandardTestDispatcher scroll
work can run on Android Main. The pump stops and is joined before the wrapper
returns or rethrows, preserving the order of later D-pad actions and rule teardown.
No independent scrolling worker can remain active during ActivityScenario cleanup.

The 45-second budget limits further pumping; it cannot forcibly interrupt a
framework call or a public clock call blocked on Android Main. The original
isolated-process boundary remains the final fallback. This is not a promise that
every framework operation has become cancellable. The original test assertions,
scroll targets, dispatcher, and subsequent input actions are retained.

Seven focused JVM tests pass for caller-thread preservation, pump completion before
success/failure/interrupt teardown, deadline behavior, and exception precedence.
The helper and driver compile against real Android 35 and Compose test 1.8.1 APIs.
An inverse source comparison recovers the prior fixture's original operations
exactly. These checks do not substitute for the focused Android run below.

Focused Android management validation is required before treating this as a fix.
Automatic broad CI is deferred for this focused candidate. If an action remains
blocked, retain its two fixed-stage snapshots and stop only the task's target
process, rather than wait through another blind 15-minute run. If the workflow
progresses, let all original management/readback assertions finish.

Production Android and Go code are unchanged. The latest broad R41 run has 183
unique Android passes and the one management timeout; real provider anime motion,
manga page pixels, and the previously recorded external/device gates remain open.
