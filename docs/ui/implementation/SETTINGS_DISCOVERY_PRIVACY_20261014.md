# Central Discovery privacy controls

Date: 2026-10-14 UTC. Base development commit
`c09345535b95c5ae8a3acc89cc7ee9dd300fb96e`.
Original scope: PR-20 privacy/settings and original UI framework Settings section,
which requires separate history/progress/cache controls. Existing ordinary browser
history and webpage-cache controls remain independent.

## Three actual scopes, no invented keyword-list deletion

The existing Privacy category now includes:
- Current keyword/filter/language profile Seen event records.
- All profile Seen event records in the active local store.
- Continuation/end progress of one explicitly selected source in the captured
  current profile.

Seen means display/open/play/save event records, not a list of previously typed
keywords. Each action has its own explicit confirmation, scope and irreversible
consequence. Current and all-profile Seen clearing preserve source progress.
Provider reset preserves Seen, other providers and other profiles. Existing pages,
browser history, cookies, webpage cache, bookmarks, saved media and Vault are not
part of these commands. No combined clear-all action or database reset is added.
Development did not invoke these actions against real user data.

The page shows local counts and whether a provider has a saved continuation. Hidden
Discovery may be SUSPENDED; the UI does not turn that into a claim of source
completion or lost progress. No query text, profile identifier, URL, cursor or
media title is displayed in central privacy. Isolated demo store scope is visible.
Without a usable current controlTarget, the UI is unavailable; it does not create
a profile, enable a provider, initialize a source, search or request a page.

## Shared owner, precise confirmation and honest completion

Both the old Discovery options panel and central Privacy detail now use the same
view-lifetime DiscoveryPrivacyActions prompt adapter. A prompt captures the exact
DiscoveryControlRequest, including profileId, session stamp, viewRevision,
controllerGeneration and optional provider ID. Live lifecycle/route/foreground,
readiness/storage, current target and provider membership are checked before
opening and again before confirmation. Confirmation consumes the exact prompt
before calling the controller, preventing repeat/reentrant and stale callbacks.
Cancel and disposal affect only the prompt, never accepted storage work.

AndroidDiscoveryController still uses its existing serialized executor and the
existing DiscoveryPresentationPolicy.executeControl scoped mutations. Generic
submit is unchanged. Only local-control submission gets a narrow controller-owned
single-flight owner. Busy and typed results survive leaving and reopening the
settings detail. Both entry points share this owner, including provider reset,
whose target revision does not change merely because it committed.

Each accepted command has an operation identity independent of the target revision.
A legitimate history clear changes the target; its COMMITTED result is still
reported by operation identity. Duplicate worker/delivery callbacks and stale result
dismissals cannot clear a newer owner/receipt. Queue rejection resolves FAILED;
closed/missing-coordinator/stale work resolves STALE without mutation. A controller
closing after mutation reports the actual outcome, not assumed cancellation.

Mutation outcome is recorded before local statistics/read-only refresh. A later
read failure does not convert a committed mutation to FAILED. Typed results are
shown in both entry points; success is never inferred from enqueueing, changed
counts, an absent dialog or an unrelated notice. Completion uses a separate narrow
main Handler rather than coalescing DiscoveryLatestDelivery or the Handler whose
callbacks close() removes. No automatic retry or new persistence layer is added.

Statistics use refreshHistoryStatistics(target) and refresh(readOnly = true).
SETTINGS does not make Discovery visible or fulfill pending source demand. Browser
history/cache action code remains untouched; its resource scope was clarified as
browser-specific now that the separate Discovery section appears below it.

## Tests and independent review

Focused execution: app 156 PASS, Discovery 312 PASS, zero failures/errors/skips.
New coverage consists of:
- 20 production local-control owner tests with deterministic queues/ports.
- 4 explicitly source-only Controller wiring contracts.
- 28 production prompt-adapter tests.
- 8 central UI/resource/source contracts.
- 3 real VideoDiscoveryCoordinator tests with in-memory store and fake providers,
  running hidden local counts/current clear/all clear/provider reset while proving
  zero additional provider calls, stable page order and preserved independent data.

Coverage includes target component/provider changes, route/background/disposal,
old prompt versus new prompt, synchronous reentry, delayed delivery, queue rejection,
work exceptions, close before execution, close after commit, commit then statistics
failure, operation identity after target revision change, and exact result dismissal.
Independent read-only review inspected production paths and these fresh XML results
and found no blocking issue. It did not run Gradle or claim Android execution.

Final full regression: all 13 module test tasks freshly executed, 4,030 registered,
4,028 PASS plus two pre-existing corpus SKIP, zero failures/errors; app 2,652 PASS.
Both Kotlin variants, dual lint and runtime inventory pass. The first full run
also passed but introduced one English plural-count lint warning; the count row
was corrected to label-first formatting, then the full suite reran successfully.
Final lint matches the prior baseline: Debug 60 warnings/8 hints, Release 61
warnings/8 hints, zero errors. Exact 90-artifact runtime admission, source boundaries,
original license bytes and seven-getter ABI checks pass. Final build exit 0,
2m 12s, 102 actionable tasks. Sanitized summaries and manifest are in adjacent
`discovery-privacy-20261014/`. No APK build/install, actual SQLite/WebView/device gestures,
real providers, actual user-data cleanup, or emulator/phone acceptance was performed.
The original frozen APK, dependencies, SDK/Kotlin, SkinKit, permissions and disabled
Vault/dynamic-interpreter/billing gates are unchanged.

## Continuation

Next: design the original free basic-output MOV/WebM-to-MP4 compatibility gap
using admitted media components, with bounded source/destination staging, native
exclusion and final verification; do not promise all formats or unknown codecs.
In parallel, reconcile original PR-25 Incognito and per-site-permission architecture
before implementation. Existing context flags and fail-closed site permission handling do
not constitute isolated Incognito or a complete permission manager. Do not add a
misleading switch or expand permissions/dependencies without their explicit gate.
