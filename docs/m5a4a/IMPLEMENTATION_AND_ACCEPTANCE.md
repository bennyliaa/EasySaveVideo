# M5-A4a ordinary-media destination implementation

## Design

- The existing application Save owner installs one `AndroidOrdinaryDestinationRuntime`. It snapshots preferences, presents ASK, fences callbacks by a one-use ticket and source/version predicate, and durably admits a fresh creation before calling any creation runtime.
- Admission/preference writes run on a bounded control lane. Copy routing has a separate bounded lane, so a slow external provider cannot prevent a new ordinary-media admission.
- A v6 additive SQLite migration adds the destination preference, immutable creation rows and immutable publication links to the same database/lock. Existing Save/Edit/recording rows are not backfilled. SaveRequest and its codec remain byte-for-byte unchanged.
- Direct/HLS/basic recording use the admitted creation ID as their task/session ID. Editor uses it as operation ID. Advanced recordings retain their session ID across processing retries; each transient edit operation is immutably linked before processing begins. Legacy retained recordings have no link authority.
- Save SUCCEEDED and Editor publication transactions bind the exact initial `MediaReadRef` with no external IO. Advanced recordings converge on the existing editor publication seam. A process crash after private publication still leaves a durable routing entry; no Compose effect scans completion or mints copy IDs.
- The router rechecks exact admission and export request identities, routes only bound album choices, reuses the existing application-wide ExportCoordinator, and never replaces an uncertain or terminal operation. Batch failures do not starve later rows. Existing export reconciliation owns ambiguous outcomes.
- Separate UI facts show private publication and additional album-copy status. A renamed, moved or deleted exact source is never silently rebound to its newest revision. The failure concerns only the additional copy; original-media publication is not rolled back due to ordinary external-copy failures.

## Verification scope

Current-scope wrappers preserve historical test bodies and update only their frozen-manifest/self paths. The copied current SQL tests change only target-helper version assertions; their historical v3/v4 fixtures and test bodies remain unchanged. `scripts/check-destination-admission.py` protects every M10-B baseline file except the explicit integration allowlist and permits only new A4a files. Old admission gates and evidence remain unchanged.

Final aggregate, independent review, source hashes and restore evidence are recorded separately. Android ContentResolver/MediaStore visibility, actual Compose lifecycle/rendering, capture hardware, device permissions and OEM behavior have not been executed. Host tests and source checks are not Android acceptance.

## Device acceptance still required

1. Default App Library, ASK cancellation/stale input, and explicit album preference on each ordinary creation path.
2. Existing accepted jobs keep their chosen destination when settings change.
3. Activity recreation, process death around private commit/copy creation, duplicate callbacks and manual recovery produce no duplicate album output.
4. Source rename/move/delete before copy fails closed without changing the original.
5. MediaStore full/readback failure/cancellation/permission or provider failure preserves private original and truthful separate status.
6. Advanced recording processing retries keep the original session admission, including both proportional and authorized-frame modes; old retained sessions remain library-only.
7. A blocked album provider does not prevent preference changes or another independent admission; bounded resource and native ownership still apply.

## Verified main-worktree result

The frozen final driver `scripts/verify-destination-workspace.sh` completed successfully:

- 1,875 Gradle JVM cases, zero failures/errors/skips (baseline 1,826 plus 49 new destination cases). All 87 actionable aggregate tasks were rerun offline with strict dependency verification and nonincremental Kotlin compilation.
- Debug and release compilation/lint pass. Each lint variant has zero errors, 14 warnings and six hints; this is not a warning-free claim.
- Exact 74 admitted external runtime artifacts are unchanged. All prior source/dependency/native/permission/disabled-module boundaries pass under the new scope.
- Seven distribution identities, including the safe unconfigured default, each pass the 15-case actual-debug-output and actual-release-output host selection. Five invalid build selections are rejected; final state is restored to unconfigured. These standalone selections are not added to the Gradle JVM total.
- New production tests cover admission/defaults/strict decoding, immutable exact-source binding, replay/restart/crash phases, changed settings, stale/cancelled ticket callbacks, mismatches, bounded batching and starvation resistance.
- Destination host SQLite: 14 implementation cases. Independent destination review: six freshly compiled JVM cases plus four SQLite cases covering 108 injected migration rollback boundaries. These counts remain separate from the Gradle JVM count.
- All inherited applicable SQLite/DOM/media/provider/discovery/origin/power/filter/reliability/entitlement/isolation host checks pass.
- All 595 frozen inputs remained unchanged. Manifest SHA-256: `fed8bf6ee042aa18c40335772b162910f8f3d666247cc042c45996baa9a8c014`.

Detailed results are in `evidence/aggregate-summary.log`, `junit-summary.json`, `lint-summary.json`, `channel-matrix/matrix-summary.json`, and the per-suite logs. The scope gate protects 2,968 baseline files and executable modes, with ten explicit app integration edits.

Milestone Git/archive identities, full cold-restore execution and private Library backup identities are captured in the separate restore receipt. Main-worktree verification is not a substitute for those recovery checks or the deferred Android/device acceptance.
