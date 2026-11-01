# Joint and independent recording evaluation modes (2026-10-18)

This update implements the clarified distinction: method ③ can be tested independently; testing methods ①, ② and ③ in one run is a joint evaluation. It adds reporting and explicit session transitions only. Offline fixtures do not establish Wikimedia availability, actual recording behavior, device acceptance or a live success percentage.

## Modes and source compatibility

- `INDEPENDENT_RECORD`: `beginPage` explicitly skips ① and ②. Their states become `SKIPPED`, never `FAILED`. Only ③ can issue an attempt ticket. All recording counts are in `independentRecord`; the legacy `save3` and `fallbackSave3` buckets stay empty.
- `JOINT`: ①, ② and ③ may start in any order after the explicit page start. In particular, ③ does not require either primary result and may finish first. `save3` contains ③ outcomes for every started page. A run is incomplete while any of its three methods is untested or blocked.
- `FALLBACK`: retained as the default for existing constructor and evaluator calls, preserving the original protocol. Only this legacy mode requires both primary methods to finish `FAILED` before ③ can start. New UI callers select one of the two explicit modes above.

`DiscoverySaveEvaluationSession(evidence, mode = FALLBACK)` exposes an immutable `mode`. `DiscoverySaveEvaluation.summarize(rows, mode = FALLBACK)` uses one mode for the entire input run. Session snapshots and sealed reports retain that mode. No mode setter or in-run relabeling exists. Existing call sites omitting mode retain their previous behavior.

A raw independent-mode evaluation input must explicitly supply `SKIPPED` for both primaries on each started page. It cannot relabel actual primary failures, successes or unknown results as independent tests. Registration alone does not skip or start anything. `finishMethod` still accepts only `SUCCEEDED`, `FAILED` or `BLOCKED`; `SKIPPED` is a deliberate non-attempt, not a result reported for an issued attempt.

## Counts and denominators

`N_actual` is the number of unique pages for which the caller explicitly started the page test. A started page remains in this common denominator even if an attempt never starts, is blocked or never finishes. Discovered but unstarted pages remain visible as `notStartedPages` and make the report partial.

- Joint ① and ② shares remain their successes divided by `N_actual`.
- `jointRecordShare` is all joint-mode ③ successes divided by `N_actual`. `jointRecordTestCoverage` is completed ③ tests divided by `N_actual`.
- `fallbackSave3` contains only the subset whose ① and ② outcomes are both `FAILED`. `fallbackShare` is successes in this subset divided by `N_actual`. In joint mode this is a derived “only ③ succeeded” statistic, not a prerequisite or a claim that ③ ran after the failures.
- `fallbackEligiblePages`, `fallbackTestCoverage` and `fallbackRescueRate` retain their denominators over the both-failed subset. The rescue rate remains unknown until each eligible page has completed ③. In joint mode these subset counts can grow when primary results arrive after a completed recording.
- `independentRecordShare` is independent-mode ③ successes divided by `N_actual`. `independentRecordTestCoverage` is independent completed ③ tests divided by `N_actual`.
- Each method bucket includes `succeeded`, `failed`, `blocked`, `notTested`, `skipped`, `tested = succeeded + failed`, and a secondary `successRate = succeeded / tested`. A blocked, skipped or untested state never becomes a failure or a completed test.
- Independent results never enter joint or fallback numerators. Independent primary/fallback shares are null because those methods were not evaluated. Joint/independent shares and coverage are null in other modes. Zero denominators also produce null.

In independent mode, deliberate primary skips do not themselves make an otherwise completed recording run partial. Unstarted candidates and unfinished or blocked independent recording outcomes still do. Joint mode requires all three method tests; legacy fallback mode retains its original completion rules. All success shares in a partial report are observations so far, not final effectiveness estimates.

The existing normalization, 500-row/unique-page limit, 32-provider-per-page limit, provenance deduplication and conflicting-observation rejection remain. A shared page counts once overall and once for each associated provider. Provider subtotals are not additive. Synthetic and observed evidence labels cannot mix. `OBSERVED_SESSION` remains a caller label, not proof of a device operation.

## Export version 2

`DiscoverySaveEvaluationJson.SCHEMA_VERSION` is 2. The fixed-ASCII JSON adds a top-level mode, per-method skipped counts, the separate `fallbackSave3` and `independentRecord` buckets, and explicit joint/independent share and coverage fields. Consumers must inspect the version and mode instead of applying version-1 `save3` assumptions to a joint report.

The exporter retains its 1,048,576-byte hard bound, redacted provider aliases, no raw URL/title/query/provider/page identifier, and rejection rather than truncation when oversized. Exporting is still a pure string operation with no filesystem or network action. Only synthetic tests write parser fixtures to the module build directory; observed-label unit tests do not create observed-result artifacts.

## Verification contract

The new pure tests cover all 125 combinations of three five-state outcomes in joint mode, strict both-failed-only fallback derivation, independent skip isolation, explicit-start denominators, mode-specific incomplete coverage, unknown rates, duplicate provenance, evidence-kind separation, snapshot immutability, forged/cross-mode ticket rejection, terminal-result validation, legacy fallback execution gating, redaction and export bounds. Existing tests continue to cover lifecycle, immutable provider maps, limits and finish fences.

Test execution and resulting counts are recorded by the serialized build owner. This document does not claim a passing command or emulator result before that evidence exists.
