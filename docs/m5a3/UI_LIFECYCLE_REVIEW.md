# M5-A3 focused UI/lifecycle source review

Reviewed 2026-10-06 at base `f7f41c15cab772e391be6be6de87b78e383e7bf2`, with adapter changes still in progress. Read-only production review; no Gradle, Android/device, real-media, export, picker, or share execution. Share proxy lock/thread/expiry implementation was excluded because another reviewer owns it.

## Findings

### U1 — Cleanup confirmation lacks a user-recognizable exact target (medium)

- Locations: `app/.../library/LibraryExportStatus.kt:25–40`; `app/.../library/export/AndroidExportRuntime.kt:45,217–222`.
- `finish()` retains the old `unverifiedOperation` when a later, different operation succeeds. The latest notice then describes the newer successful export, while the cleanup button still targets the earlier unverified output. The dialog calls it “本次文件导出” and displays no operation/source label.
- Repro: A ends COPIED_UNVERIFIED; B succeeds; select cleanup. The real target remains A, despite the surrounding latest operation context being B. Runtime expected-ID checks are correct, but the user cannot inspect that binding.
- Minimum fix: display a stable, safe operation/source descriptor beside the cleanup action and in the confirmation; bind the confirmed descriptor and ID together. Avoid calling an older result “this operation.” Do not expose a raw grant/URI or relax the exact-ID check.

### U2 — Older and restarted unverified outputs disappear from UI (medium)

- Locations: `AndroidExportRuntime.kt:45,49–57,162–169,217–222`; `library/.../export/ExportContracts.kt:20–23`; `AndroidExportJournal.kt:43–51` and terminal-excluding recovery SQL.
- Only one in-memory unverified operation is retained. A second COPIED_UNVERIFIED result replaces the first. After process restart it is null. Recovery enumerates only unfinished records; COPIED_UNVERIFIED is terminal, so none of these results are restored to the UI.
- Repro: create one or two unverified SAF copies, then restart. There is no remaining warning/cleanup target, and an explicit recovery check can say “没有待处理的导出操作.” This obscures unchecked external copies, though it does not fabricate SUCCEEDED in the ledger.
- Minimum fix: a bounded, durable list/query for unverified results, with per-operation identifiable UI. Lost SAF permission must remain a manual-check/cleanup result, not an automatic delete/re-export. If only latest-session cleanup is deliberately supported, make that limitation explicit and preserve truthful warnings after restart.

### U3 — Thumbnail BUSY after preview close becomes sticky (medium)

- Locations: `MainActivity.kt:142–151`; `save/VerifiedMediaPreview.kt:241–258`; `thumbnail/AndroidLibraryThumbnails.kt:106–121`; `library/.../thumbnail/BoundedThumbnailSession.kt:119–123,156–160`.
- Returning from preview creates a new thumbnail session while player close runs asynchronously. If native release has not completed when the refreshed page requests frames, the process gate rejects admission. The session records BUSY for those refs, and further requests refuse refs already in `states`; native release does not notify/retry the thumbnail session.
- Repro with a slow-but-successful player release: close preview, let the library query finish before release, then let release finish. Visible frames can remain “缩略图暂不可用” until manual Refresh or page-state change.
- Minimum fix: delay new thumbnail admission until actual native release, or add a bounded, release-triggered retry specifically for BUSY. Preserve the existing no-replacement/hung-native safety fence.

### U4 — Pending count and capacity notices are not precise (low)

- Locations: `AndroidExportRuntime.kt:217–232`; `LibraryExportStatus.kt:21–23`; `AndroidExportJournal.kt:25`; `ExportContracts.kt:11–15`.
- A new unresolved result only sets `maxOf(1, previousCount)`; a terminal finish never decrements it. Two unresolved operations may show “1 个”, and resolved cleanup may leave an obsolete positive count until retry.
- Capacity errors (1,000 retained records / 32 active) are swallowed into a generic unfinished/retry notice. Retry cannot solve a permanently full retained journal, but UI gives no capacity-specific explanation.
- Minimum fix: compute a bounded durable count, or clearly label the value as a lower-bound/incomplete estimate. Preserve typed capacity failure in UI and disclose the relevant limit/recovery option instead of implying an ordinary retry resolves it.

## Paths reviewed without a new blocking defect

- `takeSelectedReadRef` requires exact current foreground selection, current page equality, readiness, and non-busy/non-loading state; consuming the selection makes repeated taps single-use.
- Export choice rechecks READY `MediaReadRef` equality, not a bare media ID or a path. It consumes the chosen dialog reference before dispatch. Activity pause clears an unaccepted export dialog.
- Delayed document/chooser launch closures recheck the actual captured Activity's current foreground/library/preview/destruction flags. Rotation causes the old closure to fail closed, not launch from a destroyed Activity. These are current-state guards, not navigation-generation guards: leave-and-return before a delayed launch can still allow the old explicit action; decide whether that is intended product behavior.
- An already launched picker remains app-runtime-bound to one pending exact request. Busy admission blocks a second picker while waiting. Unknown/process-recreated result is rejected with an explicit possible-empty-document warning. The initialization queue forwards a received result after export runtime appears.
- Cleanup runtime checks expected operation ID again after the UI check. Confirmation is removed on foreground loss / target change; double submit hits busy admission.
- Preview waits on `OrdinaryMediaNativeResources` until actual release, and renders a fenced/restart state on unconfirmed release. It does not treat a timeout as native completion. Disposal cancels thumbnail work before preview ownership admission; the gate remains retained across a hung close.
- Share UI exposes single/aggregate copy limits, TTL, maximum opens, revocation limitations, recipient-compatibility limitation, and does not equate opening a chooser with delivery. SAF unverified notices distinguish write completion from verified export success. Originals remain separate from export results.

## Validation gaps

The selection unit tests cover pure exact-reference checks, but they do not execute MainActivity lifecycle, ActivityResult delivery, Compose disposal ordering, delayed old-Activity callbacks, native-release admission, or confirmation targeting. Those remain Android acceptance tests, NOT_RUN. A fake-owner test for U3 and UI-state reducer tests for U1/U2/U4 would add deterministic coverage without claiming device acceptance.

## Follow-up review, 20:48 UTC

The in-progress source changes address U1/U2: the UI presents separate durable review records and confirms the full operation ID plus source ID/revision; runtime binds cleanup to the selected exact source record; bounded paging includes older unverified/uncertain SAF results and does not recreate lost permission. U3's screen-level admission wait now keeps a new thumbnail session disabled until the prior native owner actually releases, with explicit fenced/waiting text. U4's regular result updates now query durable unfinished counts; typed active-capacity/share-capacity notices are present, and terminal-history lifetime cap was removed by the adapter work.

One follow-up wording issue remains in the inspected runtime: `retry()` derives its global “没有待处理的导出操作” notice only from the current rotating recovery batch. A batch may be empty/terminal while unverified results remain or another batch still contains active records. Derive the summary from fresh total counts, or explicitly state that only this automatic recovery batch ended and refer the user to the remaining review list. This was reported to the parent. No new production edits or test execution by this reviewer.

## Final source re-review, 20:52 UTC

U1–U4 and the follow-up global-recovery-summary wording issue are addressed in the final source inspected. No remaining blocking finding within this focused UI/lifecycle scope.

- Separate review rows now include source label, safe provider label, and durable record number; confirmation shows the captured descriptor and full operation/source IDs. The runtime rechecks exact source binding and cleanup eligibility. Label lookup does not reopen documents or rebuild grants.
- Review enumeration includes all retained unverified/uncertain SAF records using 32-row bounded pages and Long counts/offsets; loss of process-local grants stays explicitly manual. Cleanup completion refreshes durable counts and the list.
- Recovery's completion summary is explicitly about the current batch and refers to the remaining review list; it no longer claims globally that no work remains.
- Preview-to-library now waits for actual native owner release before enabling thumbnail requests; the reciprocal library-to-preview wait and sticky unconfirmed-release fences remain intact.
- Added tests inspect exact descriptor/source changes, background/busy rejection, lost-grant rejection, action-scoped notices, and capacity guidance. These tests were source-reviewed only; the build owner must report execution results separately.
- `git diff --check` passed in this review. SHA-256 capture of 13 reviewed production/context files and 2 test files is saved at `/workspace/shared/esv-m5a3-ui-review-sha256.txt`.

This is source-review completion, not Android acceptance. Actual phone/tablet layout, picker delivery through rotation/process death, slow/hung native release, real provider cleanup, and recipient compatibility remain NOT_RUN.
