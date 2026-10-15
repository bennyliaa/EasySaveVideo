# Library export recovery retry availability — 2026-10-13

## Defect and original requirement
AndroidExportRuntime startup recovery catches failure by showing a notice, but recoverable and unverifiedCount retain default zero. LibraryExportStatus previously rendered Retry only for positive recoverable count, leaving a failed initial recovery check without a Library retry route. Original UI03 UI-R13 and §8 require explicit recoverable exits; UI04 T-16 and T-25 cover interruption recovery and failed/cancelled export destinations.

## Approved bounded behavior
Expose explicit recovery knowledge separately from real record counts and ordinary transient notice. Unknown/failed checking must not fabricate unfinished records. A complete successful recovery cycle can mark the check complete even when real unfinished records remain; the existing retry route must remain available for that backlog. A failed review/count refresh must not be presented as success or cleared by unrelated notice dismissal.

This uses the existing app-scoped recovery contract, not a new export operation: stopInterrupted, recoverPending, finished grant release, review refresh. The parent clarified that recover(PREPARED) may continue the original accepted request and perform its first output copy. Non-PREPARED output is reconciled/cleaned according to the original durable journal. “No re-export” means no second request, duplicate output or expanded authority, not a promise of a read-only check. Tests use isolated fixtures, not actual user files.

Existing bounded cleanup and releaseFinishedGrant remain part of recovery. No domain/storage/wire or grant semantics are added. An accepted recovery may complete after Library departure or background as before; route re-entry/foreground must not auto-enqueue it, and stale UI callbacks must not newly admit it.

## Lineage
Direct code predecessor is immutable Save Resume replay29, manifest f3f87a7bdacaf3df43d406707f219c20e8b297117d4b25ec611fdacf1c61a955. That group itself requires Picker page return29, manifest 461ef7ecfae37b7267feb06fb1069431b4837578f483b9871a27b22468eaad0a. Latest verified published ancestor is dbff3f4e5452fa3470de77ae1eb974f43a221d90 (A/B34), preceded by c26df3f338ed6b183438960ea1beb7b5d6dad300 (Editor Back40). Publication parent remains unknown until direct predecessor is actually published; the chain must not skip a tested slice.

## Validation
First full Gradle attempt passed in 3m28s (75 actionable tasks, 33 executed and 42 up-to-date). All ten requested App test/Debug+Release compiler/lint/runtime tasks actually executed. Actual XML confirms 2201 App tests, zero failures/errors/skips, across 240 suites; the 45 new tests are already included in that total. Both lint variants have 60 warnings and 8 hints, zero errors, with semantic issues identical to the Resume predecessor ignoring line shifts. Exact runtime90 and seven boundary checks passed. All 1447 pinned inputs remain byte/hash identical. Independent source and build-evidence review passed; the hash-bound review records accompany the freeze. The previous App2156 Resume result is only the baseline. Previous thirteen-module3383 (3381 pass/2 private-corpus skip) and current SQL/JS results bind their older input chain, not this future patch.

No APK, installation, emulator, device, actual Android Handler/Compose lifecycle, actual provider recovery or copied output validation. Previous OPPO, Windows, private corpus, stale historical gates and PPT limitations remain open; no acceptance count replaces those checks.

## Implementation and review decisions
A production ExportRecoveryCheckController owns one attempt until its terminal main-thread publication. Repeated worker/main callbacks and rejected enqueue cannot start a second pass or release a different attempt. Closing the runtime fences later ports/publication; an in-progress blocking port call may finish. Recovery knowledge invalidation does not alter another command's busy/cancel ownership. A known share failure with valid counts/reviews does not falsely mark recovery failed.

A complete pass publishes the review snapshot and CHECKED together. Ordinary refresh, notice dismissal and page changes do not clear a failure. Because recoverPending is bounded (maximum 32), CHECKED does not assert zero backlog; positive actual unfinished counts keep Retry available.

The Library action checks current Activity foreground, Library route, no media/segment preview overlay, exact export runtime and current availability at invocation. Its disposed-page token cannot revive. These are source-level wiring and JVM model assertions, not an Android lifecycle run. The shared runtime retry deliberately remains usable by the existing AndroidOrdinaryDestinationRuntime.reconcile → exports.retry → refresh route; Library-only count and page eligibility do not restrict that caller.

Prepared tests: 29 production controller/action cases, 7 using the unchanged real ExportCoordinator and isolated in-memory journal/streams, and 9 source/resource contracts. Existing batch recovery source-contract suite remains 6 cases, updated to validate equivalent shared ports instead of their former inline location. The new tests assert same request/source/target/grant identity, first PREPARED continuation once, uncertain or unrelated output not recreated, cleanup failure, interrupted cancellation, stale receipts, duplicate clicks, failed retry and success. No production domain behavior was changed to satisfy them.

## Device acceptance checklist (NOT_RUN)
- Start with recovery review unavailable and count unknown; check that failure remains visible and Retry is available after the failure.
- Retry repeatedly, then restore storage/provider availability; confirm a complete successful pass updates real counts and removes the failure, without duplicating an accepted request.
- Leave Library, open previews and background while a stale button callback is pending; no new recovery should be admitted from the stale UI. A previously accepted app-scoped pass may finish under the existing policy.
- Reenter Library and verify outstanding recovery knowledge survives without automatic requeue. Check current English/Chinese configuration and long-text/font accessibility.
- Validate actual SAF/provider behavior, journal-driven cleanup and grant release on a test device with owned fixtures before claiming platform acceptance.

## Evidence
artifacts/ui/export-recovery-retry-20261013 contains the complete 240-suite App XML ZIP, both lint XMLs, full build log, actual task status, exact 1447-input manifest, twelve-file scope, real-count census, source diff, review and hash manifests. Newly added files are complete in the freeze whitelist, not omitted from the modified-file patch. No failed test attempt occurred in this slice; pre-build review corrections are recorded separately, not described as executed tests.
