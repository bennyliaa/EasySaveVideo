# Library recovery connectivity audit — read only, 2026-10-13

Scope: current source includes the frozen Editor Back group awaiting publication (published parent 24bc104a51e1b87dc2752c01bd616be29b44dc08). Inspected Library selection/dialog/runtime, MainActivity routing, directory picker lifecycle/host, export runtime, directory journal/copy and existing tests. No source edits, Gradle, APK or device execution. READ_INPUTS.json binds principal inspected source bytes.

## Recommended bounded next slice: batch export confirmation and route-exit ownership

### A. Duplicate confirmation cancels an accepted copy
- app/.../library/export/AndroidExportRuntime.kt:349–358: first confirm changes DirectoryPickerLifecycle RECEIVED -> CONFIRMED. Repeated confirm calls canConfirmBatchFolder(), now false, and invokes cancel()/STALE instead of ignoring an already consumed command.
- app/.../library/LibraryExportStatus.kt:51–52 passes the unbound exports::confirmBatchFolderCopy callback. There is no captured expected selection identity. A retained old callback can also refer to a different current selection.
- app/.../library/destination/DirectoryPickerLifecycle.kt:19 rejects repeated confirmation correctly, but the caller cancels before reaching this safe false return.
- Existing DirectoryPickerLifecycleTest checks duplicate gate rejection only; it does not prove runtime dispatch is a no-op.
- Minimal remedy: opaque, in-memory confirmation identity bound to the exact DirectorySelection; UI captures it. Validate identity and consume once before submission. Mismatched/retired/already-confirmed callbacks do nothing, without clearing a newer request or cancelling accepted work. A still-pending current request whose source/foreground proof has actually become stale may keep the existing explicit stale/cancel outcome. Do not expose URI as identity or persist the token.

### B. Successful Back pop bypasses the intended leaving-Library cancellation
- MainActivity.kt:84–88 invokes cancelBatchForLeavingLibrary only in navigate().
- MainActivity.kt:107–108 backTo() first popBackStack(); a successful pop never enters navigate(). Library onBack:381 uses backTo().
- OnDestinationChangedListener:203–205 updates active route, not export owner. Library foreground effect:320–321 only changes AndroidLibraryRuntime, not exports.
- AndroidExportRuntime.kt:320 explicitly implements leaving-Library batch cancellation. Directory and gallery copy loops (:247 / :367) obey their cancellation flag, not current route.
- Thus directory/gallery batches can continue starting subsequent items after successful Library Back while root navigation requests cancellation. This is inconsistent with the existing explicit leaving policy, not an assumption that every closed sheet cancels all work.
- Minimal remedy: centralize the existing batch-exit cancellation around an actual Library -> other destination transition, including successful pop, preserving same-Library navigation and picker pause exceptions. Do not change single-file export, ordinary save/record jobs, completed-copy cleanup, or force rollback.

### Focused tests before full stage validation
- Current confirmation consumes once; second callback does not cancel/copy again or change notice; delayed old identity after cancel/new request cannot confirm or cancel the new batch.
- Current source becomes stale before first confirmation; background/cancel/host close; zero work until explicit confirmation; rejected lane submission terminally releases state.
- Library -> other route via successful Back pop, failed-pop fallback, root switch, edit route; same Library/no-op, detail dismissal, picker expected pause. Assert actual exit signals existing cancellation once.
- Completed first item survives cancellation; not-started items remain cancelled-before-start; late worker results belong to their original selection. No per-item directory chooser, no replay/automatic resume.
- Preserve journal and single-file/share contracts. If selected for implementation: independent review, forced App unit suite and both compile/lint stages under the existing lock; exact inputs/evidence freeze. Platform lifecycle/SAF remains NOT_RUN.

## Additional finding requiring scoped handling, not a reason to weaken ownership
- MainActivity:320–321 + AndroidLibraryRuntime:82–94: foreground return refreshes with cursor=null. LibraryBatchSelection:20–21 requires exact original query and refs in current page. An admitted batch from page 2+ can therefore become stale on normal picker return even if files did not change; confirmation disables at LibraryExportStatus:51–52.
- This follows when the Compose foreground effect observes picker pause/resume; actual Android/Compose ordering has not been run. Preserve or explicitly restore only the expected picker's admitted query/page under current ownership, or give an honest reselection path. Do not loosen selection validation globally or pretend page-2 acceptance was tested.

## Verified existing connectivity — avoid reimplementation
- Exactly one ACTION_OPEN_DOCUMENT_TREE per batch, AndroidExportRuntime:327–346; one DirectoryBatchPlan with multiple requests, DirectoryBatchCopy:19–21. No per-item picker loop.
- Picker cancel/non-OK calls cancel(), clears pre-confirmation selection/busy and allows a new explicit attempt (:343–346, :407–419).
- Copy results remain per item, including stale/unavailable/not-started/uncertain. directory_completed text explicitly says attempts ended, not all copies verified.
- retry():432–442 stops interrupted batches and recovers existing journal operations; it does not silently repeat all successful outputs. New copies require new user selection.
- Delete dialog and edit/export/preview buttons are wired. takeSelectedReadRef consumes exact current READY selection; busy guards prevent repeated accepted mutations. Delete scope requires current item+revision, nonempty-folder deletion is blocked, accepted durable mutation completes across background.
- Share uses captured selection/current predicate and explicit temporary grant revocation; no placeholder success claims identified here.

## Separate smaller runtime-boundary hardening candidate
LibraryDeleteSelection is a data class (:56), confirmDelete checks structural equality (:331). Cancel/reopen unchanged item creates a new value-equal confirmation, so confirmDelete(old) can use the reopened consent. Existing tests cover different item/revision/background without reopening, not cancel/reopen same target. No delayed delete callback is stored in current UI; practical Android reproduction is unproven. Prefer the concrete batch slice first; do not claim observed deletion or broaden it into the same patch.

## Requirements and limits
Original03 UI-R13 and §8 (line79) require recoverable exits/idempotent explicit commands; 04 T-16/T-21/T-25 cover cancellation, selected identity and target failures; 05 §10 lists ChooseTarget/CancelExport/DeleteWithConfirmation. User's once-directory requirement is already implemented and must be retained.
No import/Vault/payment/history decisions or dependencies required for A/B. Real SAF providers, OEM lifecycle, configuration recreation, receiver grants, actual copied-file integrity, native readers, OPPO/Windows issues and PPT acceptance remain device/external gates. This audit adds no execution PASS count.

## Implemented scope override
Only recommendations A and B are implemented. Page-2 refresh and delete identity hardening remain read-only audit findings.
