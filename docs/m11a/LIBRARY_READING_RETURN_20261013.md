# Library ordinary reading-page return — 2026-10-13

## Requirement and scope
Original UI01_ESV_UI_FRAMEWORK_DESIGN.md line 38 requires each root destination's lightweight page/filter/stable-selection/list-anchor context. The previous ordinary foreground path unconditionally cleared Library's read cursor: page 2 → another root → Library returned to page 1. The dedicated directory-picker return lease had already been repaired in a separate slice and did not change ordinary resume behavior.

This slice preserves the same live runtime's read query/cursor, then runs the existing repository query. It does not persist live objects, restore selected command targets, restore an export grant, or claim complete scroll-anchor/detail-context restoration.

## Implementation
Only AndroidLibraryRuntime.setForeground's non-busy/non-picker branch changes from `load(state.query.copy(cursor = null), retryPending = true)` to `load(state.query, retryPending = true)` with original-requirement/strict-revalidation comments.

Preserved behavior:
- foreground changes invalidate old read generations and clear selected/editor/deletion/move; batch movement is cancelled on leaving
- ordinary duplicate foreground effects do not initiate another read
- existing repository STALE_PAGE falls back to the first page; missing folder falls back to root/first page
- explicit Refresh/search/sort/direction/folder navigation and mutation completion retain their previous first-page behavior
- busy return does not add a concurrent repository read; accepted writes complete/reconcile through the original lane and refresh policy
- current directory-picker WAITING/result lease lifecycle retains priority, one revalidation and strict query/ref/identity checks
- pure LibraryBatchSelection values may match again after successful read revalidation; this is not authorization. Actual export confirmation/lifecycle/lease remain retired after cancellation/route exit and cannot copy again

The current MainActivity foreground effect already calls the runtime for real Library visibility. Its actual destination listener and onPause export clearing remain unchanged. Existing LibraryScreen clears multi-selection by query/foreground. Host tests establish these source contracts, not observed Compose timing on a device.

## Regression strategy
13 added tests exercise the actual AndroidLibraryRuntime with its existing isolated repository/work/main-dispatch harness. The new paging fixture uses production LibraryQueryPolicy.page with 125 records in each root/folder set and a mutable catalogue version; it does not manufacture success from a non-null cursor.

Covered: full second-page query preserved across repeated off/on; duplicate true; old success/error callbacks after newer page and while newer read remains loading; explicit search/sort/direction/folder/refresh/next/previous semantics; real catalogue-version rejection and first-page fallback; pending-next-page cancellation/reload; missing folder; failed/rejected reads and bounded retry behavior; cleared read/delete/rename/move consent; accepted busy writes; background-completed writes; retired picker confirmation after equal query return. Existing Picker29 tests remain and execute with the entire App suite.

One legacy expectation intentionally changes: runtimeReplacementAndUnrelatedResumeCannotRestoreAnOldPageLease previously expected cursor=null on the NEW runtime's unrelated ordinary resume after it had explicitly reached page 2. It now expects that runtime's own cursor to survive, while preserving and strengthening old lease/lifecycle/confirmation rejection. Original-requirement rationale and complete before/after diff are retained. Historical frozen reports are not modified.

## Evidence and publication
First full Gradle attempt passed in 3m 13s: 75 actionable tasks, 19 executed, 56 up-to-date. All ten requested targets actually executed (`--rerun` per target): App JVM, Debug/Release Kotlin, Debug/Release lint analyze/report/aggregate, runtime admission. Full App 2,263 tests (13 added), 242 XML suites, 0 failures/errors/skips. LibraryRuntimeTest has 56 tests (43 prior + 13 new). Debug lint 54 warnings/8 hints; Release 55 warnings/8 hints; 0 errors/fatal. Runtime exact 90 and seven source/ABI/license boundaries passed. Independent source review passed; final evidence review accompanies the freeze.

Input manifest SHA256: `3a07037079eeedb6abb592836280cf2b5b1c6926671b00fea09daf2575ea0135`. 1,453 pinned inputs, 2 changed; 1,451 other inputs remain identical. No production/build/dependency changes occurred during verification. Evidence: `artifacts/ui/library-reading-return-20261013/`; COMPLETE_APP_EVIDENCE.zip contains all 242 App XMLs, 2 lint XMLs,build log and internal manifest (246 entries), SHA256 `f1fd45571f512853e93e767eb05d595d7d78d91bd7d133c9325247855e55974f`. Previous 13-module evidence remains tied to its older Picker input; it is not claimed to validate current production. Save 120 was executed in the direct predecessor, not rerun here. Source prerequisite: tested UnadmittedSave48 after Picker29 → Resume29 → ExportRecovery37. Latest received verified published ancestor is dbff3f4e5452fa3470de77ae1eb974f43a221d90; no pending group's publication SHA is invented. Final publication must wait for the complete preceding source chain or explicitly publish a reviewed union.

No APK, device, emulator, website fetch or upload. Existing c794ef code8 APK remains local-only and predates this work. Actual navigation/Compose/SAF timing, scroll restoration, phone/tablet/200%/TalkBack, native media/network/storage/OEM and PPT appearance remain NOT_RUN. Prior OPPO edit STORAGE_UNAVAILABLE/share INITIALIZATION_FAILED/ANR/second-recording and Windows setup/symlink/guard issues remain open. This slice does not represent a new 13-module or complete project acceptance run.
