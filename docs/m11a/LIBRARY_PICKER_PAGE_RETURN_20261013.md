# Directory-picker return preserves the selected Library page — 2026-10-13

## Scope and lineage
This slice addresses the deterministic cursor-reset defect when a pending directory picker causes Library foreground false→true, while also supporting a coalesced false effect. It follows the tested immutable batch-recovery freeze (manifest 5681bb206c08caa6c58760783d0c694ef41027e7cf7dea401d89cabccf0f9e9d), which follows the tested Editor Back freeze (c425c1e0a4ef58073e5366a77dd8e11e1bc1f4b109f3c165c72d64fb5feb4e09). Both verified publication heads remain prerequisites. Historical24bc is not the direct parent. No APK/device work.

## Required safety invariants
The return allowance is process-local UI continuity, not directory permission or copy authority. Keep original full query/cursor and exact refs; current host/runtime/request/explicit user-intent epoch must still match. Reuse Library's bounded load and strict batch selection check, with one result-return revalidation and explicit confirmation before any copying. Ordinary resume behavior remains unchanged without an owned lease. WAITING resumes do not consume the eventual result allowance. STALE_PAGE still returns to page one and invalidates the old selection without guessing replacement refs/cursors.

## Implementation
Seven changed inputs: app/build.gradle.kts; MainActivity; AndroidLibraryRuntime; AndroidExportRuntime; new LibraryPickerPageReturn; extended LibraryRuntimeTest; new LibraryPickerPageReturnSourceContractTest. No strict selection predicate, journal, copy engine, URI permission, wire, preference or dependency change.

- The actual Library runtime captures the original LibraryBatchSelection, a separate irreversible user-intent epoch, and the same picker lifecycle. The export runtime's related predicate binds exact DirectorySelection, attached host, gate ticket, open owner and cancellation state. This allowance carries no grant or permission to copy.
- MainActivity increments an in-memory resume sequence and includes it and the staged confirmation token in the existing foreground effect. The result is still correlated without demanding foreground; it can stage before resume. Coalesced pause and defensively late result delivery therefore still trigger the owned return read.
- WAITING resumes retain the page and do not spend the future matching-result allowance. RECEIVED return invokes the existing bounded load with the exact full query once. After the result, strict current query/ref/READY/foreground/busy checks and the lease's revalidated state are all required for explicit copy confirmation.
- Explicit search/sort/page/folder/refresh and selection-changing actions retire the allowance before acting, including a change away and back to the same value. Old read completion cannot publish into a newer generation/lease. Cancelling, actual route exit, host replacement/disposal, source changes or a failed read invalidate ownership without replacing selected refs.
- STALE_PAGE still invokes the existing first-page fallback, retires the old request and shows the existing stale/reselect state. No repeated page restoration, automatic confirmation or copy occurs.
- Pending-delete polling is suspended during the unrevalidated round trip, resumes after revalidation or retirement, and subsequent catalog changes still invalidate pending selection. The same existing polling/load infrastructure is used.

## Executed verification
Independent source review passed before the first build. All 1,437 input files matched their pins after execution; 5 modified, 2 new and 1,430 predecessor files unchanged.

First build passed in 3m29s: 75 tasks, 19 executed, 56 up-to-date. All ten requested tasks actually executed: App testDebugUnitTest, Debug/Release Kotlin compilers, both lint analysis/report/aggregate triplets and verifyRuntimeAdmission. App: 2,120 tests across 234 XML suites, zero failures/errors/skips, +20 from the A/B predecessor. There was no failed build attempt in this slice; earlier immutable failure records remain unchanged.

Fifteen added tests invoke the actual AndroidLibraryRuntime through its existing fake repository/UI/work-lane harness, the new production lease and relevant production picker/confirmation/route policies. They cover page1/page2 and observed/coalesced pause, both model result orderings, repeated WAITING resumes, production picker-bridge result staging/replay, catalog cursor failure, changed revision/generation/deleted/missing source, irreversible query/selection intent, late old read completion after new ownership, rejected/failed reads, cancellation/route/background/host/runtime replacement and pending-delete polling. Five added source contracts bind Android/Compose host wiring. Repository/provider data and scheduling are test doubles, not Android device or actual file access. The engineering-external audit model is not counted as execution evidence.

Each lint variant has 60 warnings and 8 hints, zero errors/fatals. Its issue ID/severity/message multiset equals the frozen A/B baseline; line locations may differ. Exact 90 reviewed runtime artifacts and seven unchanged source/bytecode/ABI boundaries passed. This slice reran App tests only; the historical full 13-module regression is not claimed again.

Evidence: artifacts/ui/picker-page-return-20261013. Complete ZIP contains 234 App XML reports, both lint XML reports, the build log and payload manifest (238 entries). JUnit hostname and local paths are sanitized and serialization is disclosed; these are not claimed to be raw unredacted bytes. Final independent evidence review and manifest bind the precise files.

## Platform acceptance checklist (NOT_RUN)
- Real directory picker from page2, cancel, slow selection, temporarily return without result, then explicit confirmation.
- Actual Compose paused-value coalescing, result-before-resume, rotation/recreation and host replacement.
- User changes page/query/selection during return revalidation, late results, and actual provider/catalog/file mutations.
- One directory choice per batch, actual copied-file integrity, and no automatic copying or broader URI grants.

## Remaining gates
Actual Compose effect scheduling, Android Activity result/resume order, SAF provider/permission behavior, rotation/process death and real copied-file integrity remain NOT_RUN. Existing OPPO and Windows observations and PPT/visual acceptance remain unresolved. No imports, Vault, interpreter, payment or history policy are introduced.
