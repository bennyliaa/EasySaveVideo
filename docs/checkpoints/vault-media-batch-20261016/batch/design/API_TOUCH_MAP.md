# Minimal API and file touch map

Revision 4, tied to CONTRACT.md. No coding has been done.

## Shared types / exact APIs

Extend VaultMediaContracts.kt:
- Add `VaultCopyBatchState`, immutable `VaultCopyBatchItem`, `VaultCopyBatchOutcome`, `VaultPhoneTreeRecord`, and optional `batch` in `VaultMediaJournal`. Tree record contains batch/grant/nonce identities, exact URI and observed read/write bits; it confers no IO authority.
- Add opaque `VaultPhoneTreeRequest`/`VaultPhoneTreeTarget` within the existing picker owner, or a sealed request/target purpose while preserving current single-item signatures. No raw URI parameter at runtime/UI boundary.
- Extend `VaultPhoneFilesPort` narrowly: `prepareDirectorySelection(batchIdentity)`, `directoryRecord(tree)`, `createBatchChild(tree, activeOperation, nonce)`, `validateDirectory(tree)`, and `retirePhoneTarget(target, operationId, batchId?)`. See API_FREEZE.md for exact proposed shared declarations. Exact owner registration, current-context creation admission, and store bound-phase validation are mandatory. The shared single-item OUTPUT_RETAINED correction also uses this path. Retirement calls the fixed store validator for exact persisted batch/row/target/outcome and rejects pending CAS; caller outcome values confer no authority.
- Add a store validation use for child CREATE and batch tree identity. Existing WRITE/READBACK/PUBLISH/CLEANUP must validate exact current active item and reject every archived result. Cleanup of a retained/verified prior row is impossible through batch authority.
- The returned child is registered before validation/bind failure can lose it; only independently validated fresh child ownership allows cleanup. Unknown create is represented explicitly and cannot be retried.

VaultMediaRuntime.kt remains the workflow owner:
- `beginBatchCopy(refs)`, `confirmBatchCopy(requestIdentity)`, `continueBatch(requestIdentity)`, `skipRetainedCurrent(requestIdentity)`, existing `cancel`/`recover` extended by batch state.
- Private extract of current confirmCopy's transfer steps, driven by already bound exact target and active scalar journal; both old single-item and new batch use it.
- Sequential scheduling occurs only after durable row outcome + actual resource drain. It retains the common run context/deadline. Per-item final-decision flags are exact-operation-bound and reset atomically at verified handover; batch stop is always set before checking them. User action is validated again after UI scheduling and before source IO/create/final CAS.
- `newOperation` must reject an unfinished batch, even when the current scalar phase is COMPLETE/CANCELLED. Dedicated handover validates the same batch, prior result and next untouched row.
- Preserve pending picker ownership through lock; continue requires fresh root/presentation. Keep separate user-stop, lifecycle-pause, and irreversible final-decision states. Locked and actually drained credential authentication/reset remains usable; preserve batch ciphertext and revalidate original object/generation against current catalog on explicit continuation.

## Planned write ownership and minimal production files

A. Shared/runtime/UI worker owns:
1. app/.../vault/VaultMediaContracts.kt: bounded batch records and opaque tree ports.
2. app/.../vault/VaultRuntimePorts.kt: store tree/create validation only if needed; no second store or broad new crypto API.
3. app/.../vault/VaultMediaRuntime.kt: single-engine extraction, queue/result transitions, recovery/skip/retry guard, total run budget.
4. app/.../vault/VaultRuntime.kt, VaultProtectedHost.kt: same-owner methods and host action validation.
5. app/.../vault/VaultRuntimeUi.kt, AndroidVaultPresentationOwner.kt: <=8 selection, explicit ordinary-copy explanation, one directory picker state, progress/results, exact confirm/cancel/continue/skip.
6. app/.../MainActivity.kt: existing one outstanding Vault picker handoff accepts tree purpose; same Activity-result identity/recreation safety.
7. app/src/main/res/{values,values-zh-rCN}/vault_runtime.xml: bounded truthful status/copy/privacy text.

B. Crypto/journal worker owns:
8. app/.../vault/crypto/VaultMediaJournalWire.kt: version-1 compatibility, bounded version-2 batch/OUTPUT_RETAINED payload/binding, canonical encodings, successor/handover/terminal invariants, worst-shape sizing.
9. app/.../vault/crypto/AndroidVaultAuthentication.kt: consume new codec/binding while preserving same root registry, exact snapshot/context mutation proofs and unchanged streaming methods. No new algorithm/key format.
10. docs/vault/VAULT_RUNTIME_SOURCE_SCOPE.json and existing static source-gate list only where exact changed files require admission updates.

C. Storage/SAF worker owns:
11. app/.../vault/storage/AndroidVaultPhoneFiles.kt: single owned picker with finite tree mode, actual grants/profile, bounded pre/post direct-child validation, exact new child owner, same AndroidExportHandles, target capability retirement without hiding handle uncertainty; present-target deletion fails closed for single and batch when no independent incarnation evidence exists.
12. app/.../vault/storage/VaultPhoneFilesProfile.kt: fixed directory/child predicates sharing same provider verification. No configurable provider framework.
13. app/.../vault/storage/AndroidVaultStore.kt: tree/create/active-child facts and whole-CAS batch validation in existing owner; protect archived success/retained targets; no plaintext batch table.

Expected unchanged: AndroidVaultDatabase.kt, VaultSnapshotCodec.kt (same one encrypted envelope field/caps), VaultMediaFiles.kt, streaming keyset/reader implementation, ordinary AndroidExportPort/AndroidExportJournal/DirectoryBatchCopy/FolderGrantRegistry, Library schema/source bridge, dependencies and AndroidManifest. If implementation needs these changed, stop and identify the exact need for review first. Existing pure `FolderCreateIdentity` may be reused for fresh-ID validation without broad ordinary-export changes; it alone does not establish target ownership or recoverability.

## Worker merge discipline

Freeze shared types first. Runtime worker alone edits shared contracts/ports and integrates signature requests from B/C. Each worker writes a separate overlay; parent integrates. Build owner serializes Gradle. No build, source change or device operation is part of this design task.
