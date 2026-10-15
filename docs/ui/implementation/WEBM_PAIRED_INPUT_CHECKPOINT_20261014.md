# WebM paired input checkpoint — 2026-10-14
Status: final full/lint rerun succeeded (exit 0, 3m56s). Final pinned annotation-byte rerun passed 37/0, exit 0. Boundary checks passed; local packages are generated and verified separately.

Implemented input-only slice:
- Registered existing storage Scope/reservation owns the fixed PRIMARY descriptor, exact path/parent/alias validation, concurrent read/close lifecycle and release-only cleanup.
- Four live or closing cursors, eight cumulative opens, shared atomic bounded request/physical/event budgets.
- Exact single Transformer session and actual worker-entry lifecycle; cleaning closes new I/O without blocking release-only.
- Media3 public progressive extractor adapter retains the same guarded input across sniff/resetPeek. Exact per-read receipts bridge the pinned StatsDataSource chain; quiet-close failure remains sticky.

Independent reviews: storage eleven-file freeze 57391eb8… and loader fifteen-file c6fe88dc… passed. The only final lint correction is a local UnstableApi OptIn on WebmSequentialInput; reviewed as behavior-identical. FINAL_OPTIN_FREEZE.sha256 records final loader bytes.

Executed focused evidence:
- Dual-variant compile and 204 JVM tests, zero failures/errors/skips.
- Actual pinned Media3 paired host: 37 cases, zero failures, exact guard rejection codes, CodeSource checks, background drain and no uncaught failures.
- Current pinned preflight: 90 cases passed.
- Current pinned MOV owned-file regression: 7 cases passed.
- Initial full run found an obsolete test contract for historical session release acknowledgement. Updated test now checks this worker result and retirement ordering with explicit index existence.
- Subsequent full tests: 5174 passed, 2 existing skips; lint reported one missing local OptIn. Preserved as a failed lint run; final corrected run is separate and successful: 472 suites, 5174 passed + 2 existing skips, zero failures/errors. Both Kotlin variants and lint tasks completed. SARIF: debug 72 warnings/8 notes, release 73 warnings/8 notes, zero errors. The one additional warning per variant is UseKtx suggesting String.toUri in OwnedWebmInputBinding; no lint suppression was added.

Limits:
Direct remains Unissued. No output writer, final same-FD hash, conversion completion/proof/adoption/commit is issued by this slice. Host syscall and SQL seams are fake. Pinned library input tests do not execute Android ProgressiveMediaPeriod/Transformer/native codec/GL. Android/device acceptance and production conversion admission remain unverified.

Next priority is the user's confirmed continuous BASIC screen-recording behavior: page interaction/navigation/popups do not stop capture; Stop saves, Discard cancels unpublished publication. Android 12 thumbnail risk was accepted. Whole-page input locking is superseded. Implementations remain external until this checkpoint freezes.

Remote synchronization is paused; no GitHub upload, device operation or new APK delivery.
