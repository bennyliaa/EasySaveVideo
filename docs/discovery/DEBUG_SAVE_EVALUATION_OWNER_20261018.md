# Explicit debug evaluation page owner (slice 04)

## Operator path and scope

The Discovery screen in debug builds has Begin evaluation run, Refresh local preview, and End run controls. After beginning, choose a pending candidate's separate **Open as an evaluation page** action. Ordinary card opens, address navigation and ordinary saves do not register candidates. Demo mode creates SYNTHETIC_FIXTURE evidence; non-demo creates OBSERVED_SESSION evidence, and switching modes cannot mix rows into the existing run. No providers are enabled by this feature.

The page is registered only after a real host navigation has acquired its exact request and view. N_actual remains zero until the operator explicitly clicks the existing Save inspection action on that loaded evaluated document. A no-media, unsupported, cancelled or blocked inspection still leaves an actual started page with untested outcomes; it is not a fabricated method failure. End seals the run even if partial and clears browser attribution and remaining task links. Activity destruction seals and clears the ephemeral run; no session/report is persisted or restored.

This slice offers a local in-memory preview only, not a file-export action. It displays overall and up to 32 local provider rows, each with the common actual-page denominator and independent ①/② success fractions, blocked and untested counts. Provider rows overlap and cannot be added. The session report retains all bounded provider statistics. ③ is explicitly unsupported/untested. Existing pure JSON generation remains available but no SAF picker, disk write, share, upload or clipboard operation is added.

## Exact authority and revocation

The dedicated candidate callback carries its rendered DiscoveryControlTarget, candidate and lifecycle generation. The activity rechecks current candidate membership, route, foreground and overlays immediately before navigation. No candidate lookup by URL occurs. BrowserInputPolicy still validates the requested address, but the address does not establish source ownership.

The browser attaches an evaluation navigation to the exact BrowserRequest and WebView **before** loadUrl, allowing synchronous callbacks. Only that request/view's first accepted start and accepted finish may bind its document revision. Initial redirects are conservatively unsupported: an explicit redirect observation or second start revokes pending attribution. A missed, stale or out-of-order callback never guesses success. Real redirect behavior requires device validation.

The document lease is resolved using exact request, WebView and document revision, on top of the existing SaveInspectionPage context/epoch/interaction/document checks. It expires after 120 seconds using monotonic time. Navigation, same-document history changes, tab/mode interaction, pause, view release and browser close revoke it. Unrelated/retired view callbacks cannot consume the active evaluated page. Reopening the same explicitly selected candidate creates a new lease, allows another still-untested method and keeps the unique-page denominator unchanged; provider attribution merges normally.

The captured evaluation lease belongs to the exact PageSaveSelection returned by that scan. Existing candidate reference-membership checks still select the specific Direct or Stream action. HLS preparation retains that same selection. After source/request/variant preparation, the exact destination admission is armed with the lease; fresh coordinator acceptance rechecks the pure-memory lease (never WebView from the worker). Rejected queues, early returns, preflight/submission failure and absent handoff abandon their pending handle. Only accepted worker bindings persist after browser navigation and report against their original session/task/commit/operation. Session finish fences late callbacks.

## Release and ordinary behavior

Release compiles an empty panel implementation. UI activation, dedicated browser opening and runtime arming additionally require BuildConfig.DEBUG. The owner is constructed disabled in release. Existing ordinary navigation and save control flow is retained; no classification outcome controls Save submission, execution, recovery, cleanup or success UI.

Only ①/② use the previous verified-final-commit classifier. Cancel/pause/recovery/preflight remain BLOCKED or UNKNOWN/untested, never automatically FAILED; no path unlocks or collects recording ③. Unknown completion remains partial.

## Verification and remaining work

Pure JVM tests cover default/release-off, evidence separation, accepted request/view/document identity, initial redirect/second start, same-URL-style ABA, stale callbacks, view removal, navigation/pause/close, monotonic expiry, repeated starts, same-page provider merging, end/new-session races, invalidation before acceptance and retained attribution after accepted handoff. Source contracts check actual browser/runtime/UI call sites, but do not substitute for WebView execution or visual UI acceptance. Debug and release compilation and existing app/Save/Discovery regression suites are checked separately in slice-04 evidence.

Next bounded gap: explicit local SAF JSON export of a sealed report with picker-generation/lifecycle fencing, safe local provider mapping and cancellation handling. Device-only acceptance still includes initial navigation callback ordering/redirects, repeated selection/HLS dialogs, accessibility/layout, and real ①/② final commits. Recording③ requires its own exact owner and completeness evidence, not prefix publication.
