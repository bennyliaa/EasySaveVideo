# PR-25 / TR-18 ordinary browsing history foundation

## Scope and status

This is an unconnected implementation foundation, not a completed browser history feature.
Base supplied by the release owner: `29b7fedc0431fcd53b788dae7999495675e5d3be` on
`development/cloud-followup-20261013`. This reconstructed source directory has no local Git
history. No upload, APK assembly, device installation or emulator run is part of this checkpoint.

Added independent ordinary-history policy, bounded codec, serial controller, Android private
atomic storage and synthetic JVM tests. There is no `SystemBrowserHost`, Activity or UI runtime
wiring yet, and therefore no new collection or automatic expiration is enabled by this change.

The product retention choice is pending: 200 recent distinct addresses with a 30-day lifetime,
or 200 recent distinct addresses without time-based expiry. Neither is selected by default.
`HistoryRetentionPolicy` requires both arguments explicitly. Its 200-record ceiling and the
2 MiB input ceiling are defensive resource bounds, not a claim of user-approved retention.

## Original specification evidence

- `01_Product_Requirements.md`, PR-25 and section 5.1: history, bookmarks and incognito remain
  browser requirements; untrusted page content does not confer system authority.
- `04_Technical_Requirements.md`, TR-18 / section 4: incognito does not write ordinary browsing
  history or Seen databases; sessions and permission boundaries remain distinct.
- Technical section 10: history, URLs, titles and credentials must never enter diagnostics or
  ordinary telemetry; local functional storage is not an exception to the logging prohibition.
- `02_Architecture_and_Data_Model.md`, section 5.4: a damaged long-term history store must not
  be silently overwritten with an empty store under the label of cache repair.
- No ordinary-browser retention duration, record limit or sensitive-query-name filter was found
  in these specifications. Current `BrowserSessionCodec` retains approved full addresses;
  `BookmarkPolicy` explicitly preserves path, query and fragment semantics. The new foundation
  uses that same address boundary rather than silently deleting parameters or inventing a filter.

## Boundaries

`BrowserHistoryItem` holds a stable UUID, canonical approved HTTP(S) address, sanitized title and
last accepted completion time. Recency is accepted event order, not wall-clock sorting. A repeat
address keeps its ID and moves to the front. Expired/evicted/deleted addresses receive a new ID
if subsequently visited. There are no counts, screenshots, thumbnails, referrers or cookies.
Full approved queries/fragments may contain personal information: this is private functional
data, not a safe-to-export record. The feature does not claim to detect all sensitive URLs.

The new file is `browser-history.bin` under the application's no-backup directory. Existing
manifest/data-extraction rules also prohibit backup and device transfer. It is not exposed by a
content provider, diagnostic export or server API. SHA-256 detects corruption; it is not encryption.
The file has its own magic/version, bounded fields, strict UTF-8 and a checksum. Unknown versions,
truncation, duplicate identities/addresses and corrupt input are rejected without partial salvage.
Only definite first-use absence is empty. An unreadable file remains unchanged until the user
explicitly confirms clearing ordinary browsing history. Atomic writes are synced and re-read
before a committed result is published; failures disable further opens/writes until a fresh read.

This store is separate from WebView back/forward `Bundle`s, tab restore, bookmarks, Discovery
search history, Seen, progress, cookies, cache, media and task data. None is imported or cleared.
Incognito completion is rejected, without pretending that full incognito WebView isolation exists.

## Event, concurrency and action contracts

- Mint a single-use permit at actual navigation start, bound to tab/session, epoch and exact
  WebView identity. A completed event additionally requires selected context, active ownership,
  current Entry, READY state, exact admitted/current/tab URL and an accepted core completion.
- The Android adapter must obtain `acceptedFinish` from the actual
  `BrowserSessionController.acceptPageFinished` result after checking `NavigationAdmission`.
  This foundation is not wired to callbacks yet. History API, fragment updates, page starts,
  failed/expired/cancelled callbacks and restored tab addresses do not independently grant visits.
- Clearing/removing invalidates all older permits and undelivered visits. A completion cannot
  repopulate history after its pre-clear navigation. New post-clear navigation may be recorded.
- One IO operation runs at a time. While busy, automatic visits coalesce by address in a bounded
  200-address pending map. Latest completions are retained when that bound is exceeded. A failed
  write drops queued events and invalidates outstanding history authority; navigation is separate.
- `close()` fences delivery but does not cancel a disk operation already submitted. Runtime
  integration must be process-owned and keep the same serial worker across Activity/controller
  lifetimes, so an old write cannot overtake a newly confirmed clear. The main-owner delivery
  dispatcher must not silently drop completion callbacks. UI detach must not create a second
  concurrent store owner.
- A row token belongs to one controller and one list revision. After any read/mutation starts,
  an old token cannot open or remove an item even if identical data is loaded again. The final
  open also revalidates through `BrowserInputPolicy`; eventual UI opening must use `browser.navigate`.
- Single-item and whole-history clearing require a separately obtained, single-use current
  confirmation token. Cancel has no storage effect. Failure does not show success or enable stale
  rows; an uncertain successful commit can be reconciled by a later explicit read.

## Remaining work and acceptance

Retention selection, Android runtime/host integration and accessible localized SkinKit list and
confirmation UI remain required. If time-based
expiry is selected, runtime expiry scheduling and foreground/list refresh must be specified rather
than assuming that a one-time read enforces a lifetime while the app stays open.

Tests cover both explicit policies, completed-event freshness, duplicate callbacks, incognito,
old sessions/views/epochs, approved query/fragment preservation, stable IDs, capacity, codec
corruption, bounded pending work, confirmed/cancelled/failed clears, stale list replay and restart.
Initial focused JVM validation passed 43 tests with zero failures/errors/skips. Following the
independent review, URL-admission tests were strengthened to reach the actual canonical-address
boundary. Final aggregate validation succeeded on 2026-10-12 using the serialized official entry:

```
/workspace/shared/esv-cloud-toolchain/run-gradle.sh --offline \
  :app:testDebugUnitTest :core-domain:test :core-model:test \
  :app:compileDebugKotlin :app:compileReleaseKotlin :app:lintDebug :app:lintRelease
```

Result: BUILD SUCCESSFUL in 2m 23s, 79 actionable tasks (16 executed, 63 up-to-date).
App: 1,553 tests including 43 history tests; core-domain: 44; core-model: 3. All have zero
failures, errors and skips. Core tasks were up-to-date; no core production files changed.
Debug and release Kotlin compilation and both lint tasks passed. Each lint report contains
60 warnings and 8 hints, with no error or location in the added history package; passing lint
does not mean the wider project has no warnings. Toolchain warnings remain
for missing platform-tools, unwritable Android analytics settings and Gradle deprecations;
no environment/security settings were changed to hide them.

## Independent read-only review

The independent reviewer reported no confirmed production P1/P2 issue for this unconnected
foundation. The reviewer inspected all four production files and four test files and verified
their frozen hashes, including the strengthened `HistoryNavigationGateTest.kt` hash
`968440ab0969caa98135befba332f613a05ae6b0e3752abf334d524c14c55756`.
The reviewer did not run Gradle. Focused and aggregate commands/results above were verified by
the implementation worker; App's 1,553 tests executed, while the 47 core tests were UP-TO-DATE.

Two review suggestions were incorporated: unsafe-address tests now distinguish core construction
rejection from actual gate canonical-address rejection; the runtime contract explicitly requires
process ownership and one serial writer across Activity lifetimes. Approval is limited to an
unconnected foundation. Real callback provenance and runtime/UI integration require another
review when implemented; no statement here completes PR-25/TR-18 as a whole.
Android filesystem fault injection, WebView callbacks, accessibility/rotation UI and device runs
are NOT RUN. External nine-part visual specifications were not available; no pixel-perfect
conformance is claimed. Any UI will reuse the admitted SkinKit-backed project components.
