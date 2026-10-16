# Shared batch API freeze proposal

Revision 4. These declarations are for the shared-contract author to implement in its own overlay after parent approval; this design task changes no Kotlin source. Existing streaming/read APIs remain identical. Symbols stay `internal` in the existing app Vault package; Android Uri/Intent never enter these types.

## Data records

- `VaultCopyBatchLimits`: MAX_ITEMS=8; MAX_URI_UTF8_BYTES=1024; MAX_RUN_STARTS=8; RUN_MILLIS=VaultMediaLimits.OPERATION_MILLIS; MAX_DIRECTORY_ENTRIES=FolderCreateIdentity.MAX_CHILDREN (1000); MAX_DIRECTORY_ID_BYTES=1048576; PROFILE=1. No dependency from core modules to app/Android.
- `VaultCopyBatchItem(operationId: String, identity: VaultMediaObjectIdentity, activatedSequence: Long?, outcome: VaultCopyBatchOutcome, failure: VaultFailure?, plaintextEvidence: ExportEvidence?, target: VaultMediaTargetRecord?)`. Plan operations/identities are immutable; metadata/outcomes are not capabilities. No display name or source path.
- `VaultCopyBatchState(batchId: String, selectedCatalogRevision: Long, profile: Int, planDigest: String, items: List<VaultCopyBatchItem>, tree: VaultPhoneTreeRecord?, activeIndex: Int, runCount: Int, stop: VaultCopyBatchStop, status: VaultCopyBatchStatus)`. Immutable defensive list copies. planDigest is 32 raw bytes on wire (64 lowercase hex in memory) and must match canonical immutable batch/selection/item identities; it is not caller-supplied authority.
- `VaultPhoneTreeRecord(batchId: String, grantId: String, nonce: String, uri: String, flags: Int)`. Fixed generated IDs are 32 ASCII hex; exact canonical URI UTF-8 <=1024; flags is observed READ|WRITE. Original record remains immutable through fresh same-tree recovery authorization.
- `VaultCopyBatchOutcome`: WAITING, ACTIVE, VERIFIED_COPY, FAILED_NO_OUTPUT, FAILED_CLEANED, CANCELLED_BEFORE_START, CANCELLED_CLEANED, PAUSED_NOT_STARTED, CLEANUP_REQUIRED, UNRESOLVED_RETAINED, FINAL_DECISION_PENDING. Persist stable explicit wire numbers, never depend on future reorder.
- `VaultCopyBatchStop`: NONE, USER_CANCEL, LIFECYCLE_PAUSE, TARGET_PAUSE, BUDGET_EXHAUSTED. `VaultCopyBatchStatus`: PREPARING, AWAITING_CONFIRMATION, RUNNING, PAUSED, FINISHED, BUDGET_EXHAUSTED. Run count is charged by exact CAS before a new execution run.
- Add final optional parameter `batch: VaultCopyBatchState? = null` to existing VaultMediaJournal. Append CHILD_CREATE_INTENT and OUTPUT_RETAINED after existing VaultMediaPhase members; preserve old ordinals and legacy v1 encoding/decoding. v2 supports batches or the new explicit retained-single terminal outcome. v1 must reject the new phases. Batch successor rules enforce the strict new transition map; single OUTPUT_RETAINED is reachable only from known pre-final COPY_OUT after explicit keep action and true resource drain, is non-success, and permits a later new operation. PUBLISH_INTENT cannot transition to it.

## Same phone-files owner

Keep `VaultPhoneFileRequest` as the sole opaque request interface and existing `VaultPhoneFileTarget` for a single destination document. Add opaque `VaultPhoneDirectoryTarget`. Adapter-private Request has a sealed SINGLE_CREATE, SINGLE_RECOVERY, BATCH_TREE, BATCH_TREE_RECOVERY purpose; one outstanding request across all purposes.

Add a sealed result value `VaultPhoneSelection.File(target: VaultPhoneFileTarget)` / `VaultPhoneSelection.Directory(target: VaultPhoneDirectoryTarget)`. It is routing only; object identity must be in the adapter registry. AndroidVaultPhoneFiles.acceptResult returns this result (or null); MainActivity forwards the exact request/result to the existing VaultRuntime. Single item runtime wrappers may preserve old call shapes for tests, but cannot consume a callback twice.

Proposed VaultPhoneFilesPort additions:
- `prepareDirectorySelection(batchId: String): VaultPhoneFileRequest`
- `prepareDirectoryRecoverySelection(record: VaultPhoneTreeRecord): VaultPhoneFileRequest`
- `directoryRecord(target: VaultPhoneDirectoryTarget, batchId: String): VaultPhoneTreeRecord`
- `validateDirectory(target: VaultPhoneDirectoryTarget): Unit`
- `prepareBatchChild(target: VaultPhoneDirectoryTarget, operation: VaultMediaOperation): VaultMediaTargetRecord`
- `createBatchChild(directory: VaultPhoneDirectoryTarget, reservation: VaultMediaTargetRecord, operation: VaultMediaOperation): VaultPhoneFileTarget`
- `retirePhoneTarget(target: VaultPhoneFileTarget, operationId: String, batchId: String? = null): Boolean`

prepareBatchChild is no-create reservation only: generate exact fresh operation/grant/nonce record with null URI and flags matching the tree. Runtime durably writes CHILD_CREATE_INTENT with this scalar target, then calls createBatchChild under the existing publication-gate IO permit. Adapter validates the fixed store's exact tree/create fact before provider IO. The only allowed reservation→binding change is null URI to the exact returned fresh URI; all other fields are unchanged. Runtime binds TARGET_BOUND by the existing authenticated journal/CAS before openWrite.

createBatchChild captures returned target first in a candidate-only ledger, then upgrades to cleanup-owned only after independent local/direct-child/fresh-ID checks. Existing IDs are never writable/deletable. Null/throw or invalid return retains uncertainty; registry candidates are bounded by the one active create and must never disappear while real IO is uncertain. A captured-but-unvalidated URI is not cleanup authority.

Same-tree recovery makes a fresh request/selection witness against the immutable old tree record. It verifies exact canonical URI/authority/treeDocumentId, actual result flags/current access, local profile, and current host before admitting new untouched-child work. It does not reconstruct old child capabilities or resurrect old read contexts.

Existing openWrite/openReadback/publish/cleanup/resourcesReleased/recoverTarget functions continue to serve both single and batch active document targets. Shared cleanup must not delete a present document on URI/token/grant evidence alone; independent current incarnation evidence is absent in this slice, so confirm absence or retain/manual. A live token is not a native identity witness and a fresh recovery grant never adopts a replacement as old output. No framework is added. retain/release counts remain the same owner. The adapter uses same AndroidExportHandles. No persistent grants or ordinary-folder registry.

## Same store owner

Proposed VaultStorePort additions (bounded registry validation; no caller proofs):
- `validateBoundPhoneTree(record: VaultPhoneTreeRecord): Boolean`
- `validateBatchChildCreate(tree: VaultPhoneTreeRecord, reservation: VaultMediaTargetRecord, operation: VaultMediaOperation): Boolean`
- `validatePhoneTargetRetirement(operationId: String, target: VaultMediaTargetRecord, batchId: String? = null): Boolean`

Each returns true only for this fixed owner's accepted actual durable exact snapshot/journal facts with no pending/unknown mutation. Creation requires active row/operation, matching immutable tree/reservation, CHILD_CREATE_INTENT and no stop/cancel. Existing target-use validation sees the active scalar only. Retirement validates terminal batch row/outcome/target or exact single OUTPUT_RETAINED journal target, forbids unknown final decision, and adapter independently checks actual handle drain; then permanently revokes this target's write/delete/publish authority. Archive retention is evidence only.

No new crypto port is required: authenticateMediaJournal/sealMediaJournal/validateMediaMutation use the extended record and v2 binding. No new snapshot field or SQLite table is required. Store/source credential reseal invariants keep the entire encrypted journal.

## Same runtime / presentation

- `VaultRuntime.copyMediaBatchToPhone(refs: List<VaultMediaReadRef>)`, `acceptPhoneSelection(request: VaultPhoneFileRequest, selection: VaultPhoneSelection?)`, `confirmBatchCopy()`, `continueBatchCopy()`, `keepCurrentBatchOutputAndContinue()`, `keepCurrentSingleOutput()`. Host methods retain existing captureAction checks; actual confirmation additionally consumes an exact process-local pending-action token internally.
- Existing single `copyMediaToPhone` remains one CREATE_DOCUMENT path. Both paths call one private transfer routine after active exact target binding.
- Extend VaultMediaView with bounded batch progress/result view, never Uri or grant text. Detailed object labels appear only under existing protected catalog presentation and are cleared on cover; durable records keep opaque identities.
- Per-active-operation decision record replaces launch-global decisionAccepted for batch scheduling. Handover under the same gate requires previous terminal CAS, real drain and appropriate capability retirement, then resets decision flags without replacing the run context/deadline.
- An item abort uses scalar CANCELLED+cancelRequested, while parent user-stop remains a separate field. Explicit skip only after known pre-final CAS and real drain; unknown database/final decisions and unknown resource release always block. Locked/drained credential recovery remains available.

This freeze is intentionally one owner plus finite scheduling/metadata. If implementation requires a second export engine, new persistence table, larger envelope, generic provider registry, different credential owner, or source/crypto algorithm changes, report that specific need before expanding scope.
