# Editor Back confirmation and publication feedback — 2026-10-13

## Scope and source baseline

Baseline is the published editor destination acknowledgement group, `24bc104a51e1b87dc2752c01bd616be29b44dc08`, with 42 exact blob readbacks. This slice follows original UI01 section2 Back priority, UI03 section8 explicit safe commands and UI04 T-20 editing cancellation. The previous attempt acknowledgement fix remains intact. No return-to-editor navigation stack, new media task, payment, import, Vault or interpreter is introduced.

## Observed gaps

EditorScreen's system Back and header Return previously called MainActivity's cancel-and-pop callback immediately. Active processing/verification could be cancelled without confirmation; final publication could reject cancellation while the page immediately hid that feedback. The PUBLISHING UI phase starts before final publication admission, so it cannot prove that cancellation is already too late.

The existing delayed-stop callback also used a generic stop/native-release waiting message even when the cancellation token had rejected stopping after publication admission. This slice makes that feedback reflect the actual current token without changing timer scheduling, publication or native-resource ownership.

## Implementation

- A closed phase policy sends PROCESSING/VERIFYING Back to a stop confirmation and PUBLISHING Back to a status-only dialog. Both system and header Back use the same Screen dispatcher. Dialog dismissal only retires that dialog; confirmation requests a stop but never calls the legacy navigation callback.
- MainActivity scopes the page to its exact current navigation entry, host foreground/navigation generation and current editor runtime. The runtime holds only opaque page/dialog identities and the exact active cancellation/operation pair, never an Activity, navigation entry or navigation callback. Page disposal/background revokes commands; stale page closure cannot revoke a successor.
- The bounded gate keeps one page and one pending confirmation. It consumes before any cancellation or destination-attempt retirement. Repeated confirmation, a replaced dialog, an old operation and background/resume/re-entry cannot affect a fresh owner. A valid new status/legacy request revokes a superseded dialog; phase advancement alone does not, so the same operation can reach publication before confirmation.
- Confirmation delegates to the existing cancellation primitive. ACCEPTED/ALREADY_CANCELLED retain stopping feedback; TOO_LATE reports actual publication/result checking. No cached phase decides the atomic outcome. Legacy Return validates its current page and phase again before preserving the existing cancel/pop behavior.
- The existing delayed-stop timer passes its already-fenced identity. Runtime additionally verifies that identity against the current active token and selects publication checking if publication is admitted, otherwise the original waiting-for-release feedback. Timers, deadlines and system-background stops keep their existing schedules and ownership.
- A presentation-only stop-feedback enum is resolved using current Compose resources and valid phase combinations. Phase changes clear the ephemeral feedback and terminal states cannot render an old stop claim. Other existing editor diagnostic strings remain outside this bounded translation.
- Ten paired EN/ZH resources cover the two dialogs and stop/check/wait messages. Dialogs use the existing elevated/sheet theme roles, scrollable content and explicit 48dp button minimums. An opaque command is not a user permission or a persisted task capability.

Independent pre-build review identified a superseded-dialog edge: replacing a stop dialog with a new status/legacy request must retire the prior confirmation, while natural phase advancement of an unchanged dialog must not. The gate and regression tests explicitly distinguish those sequences.

Idle drafts, crop-dialog-first Back, explicit Safe Stop and the shell's existing busy-disabled navigation remain. The new initial Back decisions are limited to PROCESSING, VERIFYING and PUBLISHING. After a requested stop, a fresh Back during CANCELLING/WAITING_NATIVE_RELEASE retains the existing exit behavior; there is no claim that users are forced to stay until termination. Background and deadline safety cancellation remain unconditional as before.

No worker callback navigates. Only a new explicit user action can leave; publication status is not a media result. The existing verified published-reference preview remains the only successful result entry. Uncertain publication retains its existing blocked/recovery path. Nothing in the new UI deletes an original, manufactures completion, retries creation or republishes output.

## Tests and evidence

The first and only build passed: App **2,077 tests actually executed**, including **27 new tests** (12 gate/atomic/lifetime, 3 delayed-feedback, 3 localized feedback/resource, 9 source contracts), with zero failures/errors/skips across 230 fresh JUnit suites. All ten requested Gradle tasks actually executed: full App unit tests, Debug/Release Kotlin compilers, both lint analyses/reports/entry tasks and runtime admission. Each lint retains **60 warnings/8 hints/0 errors**; the full issue-ID/severity/message multisets match the published 24bc104a baseline. Runtime admission verifies **90 exact artifacts**, and the unchanged boundary checker passes seven checks.

All **1,430 pinned inputs remain unchanged**, including the exact 14 reviewed changed inputs. Six existing inputs were modified, eight added, and all 1,416 other baseline inputs retain identical bytes. The previous destination acknowledgement production/tests remain intact except for the explicitly reviewed surrounding Editor/Main interfaces needed by this slice; none of its test files was changed. RecordingProcessingWatchdog is byte-identical and its existing callback ignores the newly passed timer identity, preserving its behavior. Other modules' unit tests were not rerun; no full thirteen-module regression is claimed.

The original first-failure and semantic-contract correction evidence from the preceding destination-acknowledgement group remains unchanged in that group's frozen/published reports. This slice had no failed build or test attempt. The pre-build superseded-dialog finding and correction are documented in the independent review, separately from executed results.

Evidence: `artifacts/ui/editor-back-20261013/`. The complete evidence archive carries every final App JUnit XML, both lint reports, full build log and per-payload hashes. XML is hostname-redacted/re-serialized and machine paths are sanitized; payload hashes bind those preserved report bytes rather than unredacted output identity. Exact input hashes and the independent review bind this slice. Earlier App and full thirteen-module results are separately dated, not summed into this result.

## Native acceptance not performed

Actual system/predictive Back ordering, modal accessibility, rotation, background/foreground, native codec/publication timing, files, device/OEM behavior, 200% text/RTL/TalkBack and PPT comparison remain NOT_RUN. No APK was built or installed. The earlier c794ef code8 candidate stays local-only, unchanged and outside the whitelist. Known OPPO editing STORAGE_UNAVAILABLE, sharing INITIALIZATION_FAILED, ANR/second-recording and Windows setup limitations remain open. Publication of this new slice is pending at freeze.
