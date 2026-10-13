# Batch directory confirmation and Library exit recovery — 2026-10-13

## Scope and lineage
This bounded slice fixes directory-confirmation replay and applies the existing batch cancellation policy consistently to actual Library exits. Its direct code predecessor is the tested, immutable Editor Back 40-file freeze, manifest SHA256 c425c1e0a4ef58073e5366a77dd8e11e1bc1f4b109f3c165c72d64fb5feb4e09. That predecessor's publication SHA is pending; 24bc is an older ancestor, not this slice's direct parent. Publication must wait for the predecessor's verified head. No APK or device run.

## Existing behavior retained
One ACTION_OPEN_DOCUMENT_TREE for the batch, followed by one explicit confirmation, independent per-item results, durable journal identities and uncertainty-aware recovery. Completed copies remain; stopping does not promise rollback. Single-file export, save/record services, grant retention policy, media originals and wire/storage formats are outside this change.

## Findings
1. A second confirmation callback previously found the lifecycle already CONFIRMED and incorrectly called cancel()/STALE. UI method references also lacked a captured confirmation identity.
2. navigate() previously cancelled active batches on leaving Library, while a successful backTo()/popBackStack() bypassed that cancellation. Actual route ownership must be consistent across these paths.

## Implementation and verification
- DirectorySelection owns one opaque in-memory DirectoryBatchConfirmation. LibraryExportStatus captures it in the displayed action. The production consume helper checks exact identity and pending lifecycle before evaluating current proof. Replays cannot cancel accepted work or affect a newer request. Still-pending, current ownership whose proof becomes invalid retains the existing stale/cancel result.
- Actual NavController destination observation dispatches the existing batch cancellation only for Library -> another destination. Successful Back pop is covered; same-Library/dialog changes and failed pop without a destination transition do not cancel. Explicit route navigation uses this same observed exit. Existing expected tree-picker pause behavior is unchanged.
- No copy-engine/journal/storage/permission policy changes. The current copy engines are exercised by partial-result tests to verify completed copies remain and later items are cancelled-before-start.
- Independent source review passed before the first build. All 1,435 source inputs were pinned and unchanged through build; 5 modified and 5 added, with 1,425 predecessor inputs unchanged.
- First build passed: App 2,100 tests, zero failures/errors/skips; 23 newly added (9 confirmation, 8 route/copy-engine, 6 source wiring). The existing 8 picker-flow tests were updated to use the production consume helper and are not counted as new.
- All ten requested tasks actually executed: App testDebugUnitTest, both Kotlin compilers, each variant's lint analysis/report/aggregate, and verifyRuntimeAdmission. Build finished in 3m36s, 75 tasks: 20 executed, 55 up-to-date. This is App-only unit validation, not another full 13-module regression.
- Debug and Release lint each report 60 warnings, 8 hints, zero errors. Issue ID/severity/message multisets equal the tested Editor Back baseline; shifted source locations are not treated as byte-identical reports.
- Exact 90 reviewed runtime artifacts and seven unchanged source/bytecode/ABI boundaries passed. Initial build failures: none in this slice; historical failure records remain unchanged.
- Evidence directory: artifacts/ui/batch-recovery-20261013. Complete archive contains 233 App JUnit XML files, both lint XML files, build log and payload manifest (237 entries total). XML hostname/path redaction is disclosed; these are sanitized copies, not unredacted byte-equality claims.
- Executable JVM proof covers production confirmation and exit helpers and existing directory/gallery copy engines. Queue rejection/host lifecycle/nav-stack harnesses are models supplemented by source-wiring assertions. Actual Android runtime, Compose click scheduling, NavController and SAF behavior were not executed.
- Existing cloud candidate APK remains the old c794ef code8 artifact, excludes this slice and its Editor Back predecessor, and was not rebuilt or installed.

## Acceptance checklist (all platform items NOT_RUN)
- Rapid directory-confirm double tap and a retired action after cancel/reselect neither stop nor start another batch.
- Library Back/root switch stops unstarted batch items; dismissing Library detail or failed Back without exit does not.
- Picker cancel/expected pause/result/explicit confirmation, rotation and process recreation retain ownership boundaries.
- Partial copy and provider uncertainty preserve completed outputs and never silently recreate an uncertain document.

## Explicit remaining gates
The separately audited page-2+ query refresh on folder-picker return is unchanged. Same-item delete confirmation structural equality is not changed; no practical stale UI delivery path was established. Actual SAF providers, Android/Compose navigation/lifecycle timing, copied-media integrity, receiver grants, rotation/process recreation, accessibility and PPT visual checks remain NOT_RUN. Existing OPPO editing STORAGE_UNAVAILABLE, sharing INITIALIZATION_FAILED, historical ANR/second-recording and Windows setup/symlink/guard observations remain open.
