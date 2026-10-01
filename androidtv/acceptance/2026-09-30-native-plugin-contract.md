# Native plugin presentation contract

This is an implementation audit, not a declaration of complete plugin parity.
All native presentation below is newly authored Compose TV. No React presentation,
CSS layout, JavaScript page, iframe or Android UI WebView is embedded.

## Working native contract and the new device equivalents

The unchanged Go protocol (`internal/plugin/ui/events.go`) offers several distinct
families. The client already renders declarative tray/command/form controls,
permission prompts, typed screen navigation, nine action families and episode
tabs. Existing runtime evidence records exactly which of those flows passed;
unexercised declarative component combinations remain unverified.

The browser-oriented event namespace also contains operations with direct native
equivalents. `NativePluginDeviceProtocol` and the globally mounted
`NativePluginScreenBridge` now handle these even when no tray is open:

| Request | Native behavior | Existing reply |
| --- | --- | --- |
| `dom:get-viewport-size` / resize subscription | Measured Android root dimensions in logical pixels; root-layout listener broadcasts actual size changes, and plugin load sends the current size | Targeted reply or broadcast `dom:viewport-size`, width/height |
| `dom:clipboard:write` | Bounded text review dialog; Cancel has initial remote focus; Copy uses Android ClipboardManager; unload/disconnect closes stale request | The existing protocol defines no clipboard acknowledgement; none is invented |
| `dom:query` / `dom:query-one` | Native Compose contains no CSS-selectable browser elements | Request-scoped empty array / null result, plus one explanatory notice per plugin |
| `dom:observe` / `dom:observe-in-view` | There are no browser elements to observe | Observer-scoped empty array; no browser listener allocation |
| `dom:stop-observe` | No allocated native browser observer to stop | No invented success event |

The empty query replies are truthful negative results, not simulated browser
support. This also lets the Go query promises resolve: `dom.go:144–173,187–218`
matches request IDs and resolves an empty list or null. Viewport querying waits
up to two seconds for `dom:viewport-size` (`dom.go:1797–1816`). Clipboard write is
fire-and-forget (`dom.go:1820–1830`). Three JVM tests and one actual global
WebSocket/native-dialog fixture were added; they require the next immutable APK
run and are not yet runtime acceptance.

## Exact residual browser runtime boundary

`webview.go:124–156` executes the plugin's content function and sends its returned
HTML string as `webview:iframe`. The earlier declarative `webview:updated`
renderer at `webview.go:65–121` is commented out. The current web client injects a
JavaScript bridge for `window.webview.send/on`, state subscriptions, resizing and
container-width messages (`plugin-webviews.tsx:76–170`). HTML can contain script,
DOM nodes, style rules and page-specific layout. Merely displaying its text would
drop actions and state subscriptions; that is not a working replacement.

`dom.go` exposes creation, CSS selectors, element attributes/properties, arbitrary
HTML/text, insertion/removal, event listeners, focus, scroll, animation and
geometry. Those operations refer to the old browser document and its selectors,
not a declarative native widget tree. A virtual element table cannot truthfully
return the old page layout or execute its scripts. Mapping a known plugin's
specific behavior to native controls is feasible, but the contract supplies no
generic semantic mapping from arbitrary HTML/CSS/JavaScript to TV controls.

Accordingly, arbitrary `webview:iframe`, `dom:create` and `dom:manipulate` remain
explicitly unsupported. The client does not announce a fabricated loaded webview,
created element or successful DOM mutation. This is a native implementation and
compatibility boundary, **not a missing Go endpoint**. It must remain a partial
parity row. Plugins that use existing declarative controls/actions and typed
native screen routes work through those native adapters. Full arbitrary browser
content would require a browser-compatible runtime or a plugin-specific native
adapter, which is outside the current no-embedded-browser presentation design.

There is also no protocol rejection result for an unsupported create call:
`dom.go:518–543` resolves its create promise only for a returned element map.
Sending null would unregister the listener without resolving the promise. The
native client therefore gives an explicit user notice instead of manufacturing
an element or claiming successful rejection. No Go protocol change was made.

## New catalog routes

The native extension Marketplace and custom-source catalog now have explicit
production entry points from Extensions. Marketplace metadata uses the existing
server route, with title/type/language filters, fresh manifest review, install
confirmation and installed-state readback. A changed manifest must be reviewed
again; an acknowledged install followed by a failed list refresh retries only
verification. The existing install endpoint refetches the URL, so there is no
immutable manifest hash/precondition to eliminate the final review/install race.

Custom sources use the declared anime/manga capabilities, exact existing provider
list payload, normalized Long IDs, pagination and local native detail routes.
Back retains the selected page and title focus. These source changes and new
large-ID tests await the next aggregate build/runtime candidate.
