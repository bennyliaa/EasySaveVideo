# Editor destination-selection acknowledgement — 2026-10-13

## Scope and baseline

Published production baseline: `1f25214fcea79113b0278ac24ddc316e6910a9f0`.
This slice repairs the existing editor's destination-choice cancellation/rejection/failure seam, under original UI03 UI-R13/section8 and UI04 T-20. It does not add editor-return confirmation or a new navigation stack. Current overview ledgers separately refresh the already-published anchor, authentication-origin and recording-notification facts; original historical reports remain unchanged.

## Source-observed failure

EditorScreen latched the current submissionRevision before calling AndroidEditRuntime.submit. The revision advanced only inside submitSelected, after destination acceptance. ASK dismissal/Cancel, unready/busy rejection and admission failure cleared or rejected destination state without acknowledging the editor. The still-mounted screen consequently kept its Submit and draft controls disabled until recreation. Existing pure click-guard tests did not cover that cross-owner outcome.

No claim is made that this bug deleted drafts or corrupted media. The idle draft is retained. Editor-return/unsaved-change confirmation is a separately identified issue and remains outside this change.

## Implementation and safety boundaries

- Shared destination selection has a closed Accepted/Cancelled/Rejected/Failed result and one main-thread receipt. It consumes accepted and terminal callbacks before invoking them; terminal runs in finally if the accepted callback throws. Existing public two-argument selection remains unchanged for the five non-editor callers.
- Early unready/busy/source rejection, source-predicate Exception, explicit dialog cancellation, worker/queue admission failure and accepted admission all terminate the matching receipt. An unavailable ASK folder choice remains pending so the user can select another destination; an unavailable automatic folder choice terminates as rejected.
- Pending identity plus the existing selection ticket fence every asynchronous completion and notice. Owner fields and notice are updated before callbacks; no trailing owner writes can overwrite a reentrant fresh selection. Editor cancellation retires only its own receipt, never another recording/save selection.
- The editor owns a single attempt across composition recreation, with a runtime submissionPending flag mirrored through view-state copies. Only finishing that same attempt advances submissionRevision. Duplicate entry cannot replace or acknowledge the active attempt; stale/duplicate terminal callbacks cannot clear a later one.
- Explicit open replacement, safe cancellation and existing background cancellation retire the editor attempt. Draft controls stay disabled while selection is pending. No draft is rewritten by acknowledgement. Restored-checkpoint ownership includes pending submission.
- Accepted creation retains the original checks and native work. The pre-existing missing-geometry branch explicitly permits its metadata probe while its accepted callback still owns the pending latch; ordinary lifecycle probes do not change selection freshness while waiting. Cancellation/rejection completion never initiates probes or media work.

Independent pre-build review caught and closed three edge cases: pending admission initially blocked the existing accepted/missing-geometry probe; pending empty drafts were missing from restore ownership; and a throwing source predicate could strand an attempt. These were corrected before the first build, with regressions added. Review findings are preserved; they are not reported as executed test failures.

The shared destination's durable admit, copy identities, storage writes, worker lanes/main-thread callback timing and source-current gate remain authoritative. MP4/HLS and all three recording selection callers keep their existing two-argument API and accepted-only behavior. No export routing, recording consent, encoding/verification/publication workflow, media deletion, destination preference, wire/schema, dependency, entitlement or device permission changes are included.

## Validation

App **2,050 tests actually executed**, including **24 new tests** (8 receipt/source-check, 7 attempt/view-state, 9 source-wiring), with zero failures/errors/skips across 226 JUnit suites. Both Kotlin compilers and both lint analysis/report/entry tasks were forced and passed; all ten requested Gradle tasks actually executed. Each lint has **60 warnings/8 hints/0 errors** and its complete issue-ID/severity/message multiset matches the published notification baseline. Runtime admission verified **90 exact artifacts**; the unchanged compiled boundary checker passed seven checks. All **1,422 pinned inputs stayed unchanged**; 1,411 baseline inputs outside six modified input files remain identical. Five new inputs were added. Other module unit tests were not rerun in this slice.

The first build actually ran all 2,050 tests with one failed existing source-contract assertion: it assumed 26 textual enum references instead of the same 26 localized kinds reused across 29 call-site references. The correction keeps exhaustive kind-set, resource parity, safety text, null-reset and current-Composition assertions, while explicitly accounting for the three LOCATION_UNAVAILABLE and two REMEMBERED_FOLDER_REQUIRED usages. Existing and new branch contracts separately distinguish failed source checks, recoverable ASK choices and automatic rejection. No production code changed between builds; only that test changed. The first full 226-suite JUnit archive, census, build log and input pin are preserved. Independent review approved the semantic correction before the complete forced rerun.

Evidence: `artifacts/ui/editor-destination-ack-20261013/`. The complete evidence archive includes every fresh App JUnit XML, both lint reports, full build log and per-payload hash manifest. Exact changed-input and total-input hashes bind the independent review and build result. JUnit XML is hostname-redacted and re-serialized, with machine paths sanitized; archive hashes bind those preserved report bytes, not a claim of byte identity with unredacted Gradle XML. The full thirteen-module regression remains the earlier b0c5647 run; counts are not added across stages.

## Acceptance boundary

Source/JVM/compiler/lint checks are not actual Compose/device execution. ASK→Cancel→edit→resubmit, modal Back, Activity recreation/background, actual native editing, final files, OEM/provider behavior, large-font/accessibility and PPT comparison remain NOT_RUN. No APK was built or installed; the old c794ef code8 candidate remains local-only and unchanged. Existing OPPO editing STORAGE_UNAVAILABLE, sharing INITIALIZATION_FAILED, older ANR/second-recording observations and Windows environment issues remain open.

This slice is pending publication at report freeze; the baseline publication does not become pending again.
