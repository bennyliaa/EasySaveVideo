# Discovery save evaluation metrics (2026-10-18)

Update: [Joint and independent recording modes](SAVE_EVALUATION_MODES_20261018.md) adds explicit JOINT and INDEPENDENT_RECORD modes and JSON schema version 2. The original gated behavior described below remains the legacy FALLBACK default only; JOINT allows ③ before either primary result, and INDEPENDENT_RECORD explicitly skips ①/②.

Scope: a pure, bounded reporting component, not a new save engine, UI entry point, recorder, live measurement collector, or recovered historical feature. Derived from the verified SearXNG instance-list reconstruction (6,822 passing tests, two skipped). No network, APK, device, SSH, or deployment is part of this slice.

## Existing implementation and gap

PendingCandidatePolicy already canonicalizes candidate URLs and assigns stable page IDs. PendingCandidatePool already merges duplicate URL provenance and preserves verification state. ResultPool additionally merges verified identity aliases. None of these facts proves download success. Existing Discovery quality controls concern filtering/history, not save-method evaluation. DiscoverySaveEvaluation reuses pending candidate identity and adds reporting only. It does not merge distinct page URLs just because they may describe one media item: the requested unit is the tested page.

## Input contract

Submit one explicit evaluation run (maximum 500 rows) of CandidateSaveEvaluation observations. testStarted defaults false: discovering, verifying or blocking a candidate does not start a save test. A caller must record the explicit actual save-test start; started pages with no terminal result remain in the denominator. Non-NOT_TESTED outcomes require a start. Candidate identity, canonical URL and 1–32 provider IDs are checked. No URLs/titles are printed by observation/report diagnostics.

The caller supplies method outcomes from actual save attempts. SUCCEEDED requires actual completed save evidence, never a search hit, detected video, playable URL or source title. This calculator cannot authenticate evidence or establish runtime behavior. OBSERVED_SESSION is an input label, not certification. Synthetic fixture and observed-session labels cannot mix in a report. All tests here use synthetic fixtures; no real success percentage has been measured.

Repeated rows for one canonical page merge source membership and complementary method observations. Identical evidence is idempotent. Conflicting outcomes (including blocked versus succeeded) fail rather than selecting favorable results; retries belong in a separately defined run. Method3 observations are accepted only when both method1 and method2 explicitly FAILED. Unknown, blocked, or untested primaries are not eligible. The actual method3 attempt must occur AFTER both primary failures; this temporal ordering is the caller's evidence contract. Inputs carry no timestamps, so the calculator checks reported states only and cannot verify that runtime ordering.

## Denominators and partial coverage

- N_actual: unique pages explicitly entering this save-test run, regardless of eventual outcome. Unstarted candidates stay separately visible as notStartedPages, never in N_actual.
- Main shares: save1Share = method1 successful pages/N_actual; save2Share = method2 successful pages/N_actual; fallbackShare = eligible method3 successful pages/N_actual.
- Method1 and method2 overlap, and shares need not sum to 100%. Provider reports use the same formulas for that provider's unique pages. Shared pages count once overall and once for every associated provider; provider totals must not be added together.
- Each method also reports succeeded, failed, blocked, notTested and tested=succeeded+failed. Its secondary successRate uses only that method's completed tests. It must not replace the main common-denominator share.
- Method3 counts cover only eligible pages. fallbackTestCoverage measures completed fallback tests/eligible pages. fallbackRescueRate remains unknown until all eligible pages have completed fallback tests, avoiding treating untested fallback pages as failures.
- partial is true if any candidate has not started, either primary method is incomplete, or an eligible fallback is incomplete. Blocked and untested states never become failures. Main shares on a partial report are observed success shares so far, not final effectiveness estimates. Zero denominators produce null (unknown), never fabricated 0%.

## Integration boundary and verification

The component is callable from an explicit evaluation harness with real observations, but no automatic runtime collection or business UI is wired in this slice. In particular, setting an evidence enum does not make fixture results real. Live provider availability, complete save outcomes, throughput and emulator behavior remain untested. See sibling evidence receipts for actual offline test commands and counts.
