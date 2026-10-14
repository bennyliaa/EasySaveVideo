# Persistent MOV files: smallest owned adapter and protocol integration

Date: 2026-10-14 UTC  
Layer-2 baseline checkpoint supplied by the parent: `3a244997` (no independent Git lookup performed).  
Status: design only. No production code changed, no Gradle/device/dependency/network operation performed. Production MOV remains disabled.

## Decision

Implement the next slice as an **inactive, storage-owned file adapter** for the existing synchronous `MovRemuxEngine`, exercised with owned files and injected failures. Do not enable MOV discovery, QuickTime transport, coordinator normalization, Android decoding, or a new execution lane in this slice.

The existing storage protocol is a suitable state-machine foundation. Its `OwnedNormalizationFiles` class is explicitly a fixture/reference adapter, not the production descriptor implementation. In particular, production must not use `FileOutputStream(path, true)` to reopen O for syncing: that constructor can create a missing file. Nor may it use `RandomAccessFile(path, "rw")`, a path-based muxer factory, `openStaging(append=false)`, or `restartStaging` for normalization.

Three integration boundaries require explicit treatment:

1. A durable WRITING row is not permission to open a writer. `begin()` returns WRITING for both a newly prepared attempt and an old attempt, including partial O. Mint a one-shot in-process write capability only while this invocation completes RAW_COMPLETE → WRITING. Recovered WRITING, even with empty or absent O, gets no write capability.
2. O must be a previously created exact journal-owned inode. Opening, syncing, hashing, reading, moving and deleting use exact generated roles and validated descriptors; no open is allowed to create, replace or truncate an existing role.
3. A crash after OUTPUT_READY but before the Save proof CAS leaves only a proof digest in the workspace. A digest cannot recreate conservation evidence. Recovery needs a bounded read-only re-proof operation or must remain recovery/terminal-cleanup only. Re-running remux is not a recovery shortcut.

The layer-2 engine currently accepts **silent AVC only**: it rejects a probed audio track. The broader existing profile enum and future design's optional AAC do not mean that AAC is implemented.

## Inspected baseline and scope

Read `AGENTS.md` in `/workspace/shared/esv-cloud-handoff-20fddc9/source`. No `.git` was created or used.

Relevant existing implementation:

- `app/src/main/kotlin/com/easysavevideo/app/save/storage/NormalizationWorkspaceProtocol.kt`: typed ledger lookup, exact role/layout transitions, closed-handle preconditions, no-replace adoption, durable terminal cleanup plans, and fixture/reference file ops.
- `NormalizationWorkspaceCodec.kt`: immutable owner/generation/raw/source binding, bounded fingerprints and cleanup plan, transition validation.
- `NormalizationWorkspaceSchema.kt`: database-v9 independent workspace ledger and immutable/terminal guards.
- `AndroidSaveStorage.kt`: one private directory owner, one `fileLock`, normalization SQL ledger and authority; generic staging/commit/cleanup remain separate.
- `app/src/main/kotlin/com/easysavevideo/app/save/normalization/MovRemuxFiles.kt`: exact-capability input/output interfaces and explicit release evidence.
- `MovRemuxEngine.kt`: synchronous engine, independently closes source/sink/output reader, output scanner and extractor re-checks, ordinary I/O versus unconfirmed-release distinction.
- `save/src/main/kotlin/com/easysavevideo/save/DirectNormalizationContracts.kt`: source-transfer tuple digest, normalization proof, and `ArtifactOutputDescriptor`.
- `SaveExecutionAdmission.kt`, `SaveCoordinator.kt`, `AndroidSaveRuntime.kt`: existing Direct claims, memory-only admission hooks, native batch owner, publication and retirement fences.
- `save/.../DirectLocalProcessingGate.kt`: the pure cancellable gate already exists, but no production caller is wired to it.
- `app/src/main/kotlin/com/easysavevideo/app/storage/OwnedFileStreams.kt`: existing stream/channel reentrant-close discipline, which must not regress.

The two design documents remain context, not evidence of implementation:
`docs/ui/implementation/BASIC_NORMALIZATION_DESIGN_20261014.md` and
`MOV_CLOCK_AND_CANONICAL_CONTRACT_20261014.md`.

The earlier basic design's table allowed explicit WRITING restart. **The implemented protocol and this next slice do not.** Keep that narrower rule.

## 1. Exact source and output identities

Avoid using “R” ambiguously for both logical raw input and a pathname:

- Logical source S is the complete raw HTTP download.
- P is `staging/<artifactId>.part`. Before adoption P contains S.
- D is `staging/<artifactId>.normalization/<normalizationId>/`.
- R is D/`raw.bin`. It is absent during remux and is created only by the no-replace P → R rename.
- O is D/`output.mp4`. It is the new canonical candidate, never a backup of S.
- After adoption P contains canonical output C, R contains S, and O is absent.

`MovRemuxFiles.openSource()` must read **P-as-S**, not attempt to create or open R. `openFinalizedOutput()` reads O, never P or a guessed final media file.

Durable bindings:

- Owner = exact taskId/artifactId/commitId; generation = exact normalizationId.
- Completed raw transfer = the authoritative `SaveRecord.transfer`. Its artifactId, byte count, response MIME and expected HTTP byte count remain unchanged.
- Raw fingerprint = full closed-file size/SHA-256, matching transfer bytes and any declared HTTP total.
- Source tuple = `sourceTransferFingerprint(transfer)`; additionally bind sourceIdentity from the durable request when constructing `DirectNormalizationProof`.
- Output fingerprint = finalized O's separate size/SHA-256.
- Semantic proof = the engine's conservation result plus the exact owner/source/raw/output tuple, not an HTTP transfer fabricated from O.
- `ArtifactOutputDescriptor.NormalizedDirect(sourceTransfer, proof)` is selected only after the Save proof is acknowledged and the output is actually adopted for verification. The raw descriptor remains source transfer evidence.

In-process identity adds, without changing the ledger schema:

- Captured directory device/inode identities for the private root, staging, workspace and generation.
- Captured file device/inode/type/link count, extent and nanosecond mtime/ctime.
- Read roles require `S_ISREG`, exactly one hard link, fixed positive extent ≤ the applicable cap, stable metadata and path-to-descriptor agreement.
- O's active writer allows only its own expected extent/metadata evolution; its inode, device, owner generation and parent identities cannot change.
- Track and compare P/O/R identities when multiple roles exist; unexpected aliasing is a conflict even if content hashes agree.

After process restart, inode observations are new observations, not durable authority. The ledger-owned role, exact generation, permissible layout, authoritative Save facts and fresh complete hashes establish recovery authority. Do not add a claim that SHA equality alone proves ownership.

## 2. Proposed smallest production API

Proposed signatures below are implementation contracts, not existing APIs and not compiled code.

Keep `MovRemuxEngine.remux(files, context, limits)` and the existing three `MovRemuxFiles` opens unchanged. Add internal storage-owned entry points, with no caller path/URI/File/FD:

```kotlin
internal fun AndroidSaveStorage.prepareMovRemux(
    owner: NormalizationOwner,
    context: OperationContext,
    limits: SaveLimits,
): OwnedMovRemuxAttempt

internal fun AndroidSaveStorage.finishMovRemux(
    attempt: OwnedMovRemuxAttempt,
    evidence: MovRemuxEvidence,
    context: OperationContext,
): DirectNormalizationProof

internal fun AndroidSaveStorage.inspectNormalization(
    owner: NormalizationOwner,
): NormalizationInspection

internal fun AndroidSaveStorage.recordNormalizationAdoptionIntent(
    key: NormalizationKey,
    proof: DirectNormalizationProof,
    context: OperationContext,
): NormalizationWorkspace

internal fun AndroidSaveStorage.adoptNormalization(
    key: NormalizationKey,
    context: OperationContext,
): NormalizationWorkspace

internal fun AndroidSaveStorage.cleanupNormalization(
    key: NormalizationKey,
    expectedRevision: Long,
): NormalizationWorkspace

internal fun AndroidSaveStorage.normalizationReleaseUnconfirmed(): Boolean

internal class OwnedMovRemuxAttempt internal constructor(/* storage-only capability */)
    : MovRemuxFiles, AutoCloseable {
    val key: NormalizationKey
    override fun openSource(): MovRemuxInput
    override fun openEmptyOutput(): MovRemuxOutput
    override fun openFinalizedOutput(): MovRemuxInput
    override fun availableBytes(): Long
    override fun close()
}
```

Do not expose `fileLock`, root paths, the descriptor registry, the underlying FileDescriptor or a reusable writer grant to the caller. Existing `NormalizationOwner` validates identifiers; durable records still must match them.

Recommended file separation:

1. New `app/.../save/storage/AndroidNormalizationFiles.kt`: production `NormalizationFileOps`, safe role derivation, Android descriptor operations and syscall seam.
2. New `app/.../save/storage/OwnedMovRemuxAttempt.kt`: one-shot session, exact source/output handles and write/read bounds. It implements the existing normalization interfaces.
3. Small `AndroidSaveStorage.kt` integration: shared owner lock, exact authority/factory methods, descriptor/session registry, generic-operation fences and shutdown accounting.
4. Keep `NormalizationWorkspaceProtocol.kt` semantics and ledger v9 unchanged if the wrapper uses the pre/post state check below. Add only narrowly needed internal access/fault seams, not a second file lifecycle.
5. Keep `PrivateCommitProtocol.kt`, `MovRemuxEngine.kt`, codecs and admitted dependencies unchanged for the fresh-attempt adapter slice.

If splitting helpers improves tests, one small internal `NormalizationDescriptorIo` interface may wrap Android syscalls. It is a production seam backed by Android `Os`, not a replacement Media3 shim or a new dependency.

### Factory and write-grant rules

Under the same storage `fileLock`:

1. Validate under fileLock: exact task/artifact/commit owner; task method DIRECT; task state RUNNING; `SaveRecord.phase == SavePhase.NORMALIZING`; `SaveRecord.operationId == context.operationId`; STAGING catalog row; complete raw transfer; no already tracked artifact reader/transfer writer/native writer; and no normalization session. Capture operationId in the one-shot attempt and recheck it on every stage boundary. Reject PREPARING/EXECUTING/VERIFYING/COMMITTING and all non-RUNNING states at this factory. Cancellation/deadline checks are memory-only. NORMALIZING phase and an empty registry are necessary conditions, not proof that a previously escaped File/FD was released; see the explicit limitation below.
2. Inspect the typed ledger. UNREADABLE is failure. A historical CLEANED row is not a fresh-writer grant. ABSENT may generate a new UUID; existing RAW_COMPLETE may continue only the exact generation and raw binding.
3. Reject existing WRITING/OUTPUT_READY/ADOPT_INTENT/ADOPTED/CLEANUP_PENDING/RECOVERY_REQUIRED as a request for a new writer.
4. Reserve a per-artifact in-process operation owner so unrelated storage APIs cannot change P, O, R or the directories during preparation and remux.
5. With the download writer already positively released, open exact P-as-S for the needed sync/hash checks, compute/validate its exact fingerprint, then positively close that temporary descriptor before invoking the existing protocol's `begin`. It performs the full fresh output-space check and durable creation.
6. Only if this invocation positively returns WRITING from an ABSENT/RAW_COMPLETE pre-state, capture the exact empty O identity and mint a non-copyable, single-use write grant bound to the returned revision and session owner.
7. An exception/lost return after the durable WRITING transition produces **no recovered grant**. A later invocation sees WRITING and must not write. Keep closed material for explicit terminal cleanup/new task.

The wrapper's before/after ledger checks and grant minting must be one owner-lock operation. Do not perform a lookup, unlock, call `begin`, then infer freshness from an empty file. Alternatively a typed `beginFresh` result can be added to the protocol, but it must make the same distinction and cannot mint grants for old WRITING.

The session is thread-confined to the existing synchronous worker. Its state and release publication use the existing storage lock/atomic fields; do not add an inverse-order session monitor.

### Open state machine

- `openSource`: once only; no writable source descriptor. Verify current bound WRITING row/authority, exact captured operationId, RUNNING/NORMALIZING state and captured P identity, then open P read-only with its immutable extent.
- `openEmptyOutput`: once only, after source exists, before any output reader, and only by consuming the fresh grant. Recheck O's captured identity, extent zero and logical position zero. Failure consumes the attempted grant; do not silently retry/reopen.
- `openFinalizedOutput`: once only, after a sink was actually opened and positively released. O's current identity must be the same inode the sink owned. Capture its final immutable extent; no lazy re-open on seek.
- `availableBytes`: use the storage owner's existing reserve-adjusted free-space computation. Do not acquire an admission monitor.
- `close`: revoke further operations immediately, attempt every owned close independently, and retire the session only after all opens, in-flight calls and descriptors are conclusively released. It does not delete files, rewind ledger state, recreate O, or make the result publishable.
- A finalized-output reader or source failure must not prevent closing the other descriptors. Failed opens close every not-returned descriptor themselves and retain a storage failure fence if that close is uncertain.

`finishMovRemux` consumes the exact attempt, checks all descriptors have released, validates evidence against captured raw/source/output identity and the same authoritative RUNNING/NORMALIZING operationId, constructs the proof, and calls `finishOutput`. Convert the active session reservation into this sealed completion operation under `fileLock` so another mutator cannot slip between release validation and the durable finish. A failed/aborted attempt cannot subsequently invoke finish with retained evidence.

The caller always closes the attempt in `finally`, including engine construction failure. Closing a successfully consumed attempt is idempotent. No public method accepts a hand-constructed “released=true” flag.

## 3. Concrete descriptor implementation

Available public Android API was inspected from the already-present Android SDK jar using `javap`, without installing or downloading anything. It exposes `Os.open/lstat/fstat/pread/pwrite/fsync/ftruncate/close`, the relevant open flags, and `StructStat` device/inode/link/size/mtime/ctime fields with `StructTimespec` nanoseconds. This is API availability evidence, not an Android execution test.

Use one directly owned `FileDescriptor` per open, avoiding FileInputStream/FileOutputStream/FileChannel dual ownership and recursive close callbacks. Keep Java stream release helpers unchanged for existing users.

### Safe opening and namespace assumptions

- Create O only inside `createEmptyOutput`, with `O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC | O_NOFOLLOW` and private mode. A collision is never accepted as a newly created output.
- All source/output reader reopens use `O_RDONLY | O_CLOEXEC | O_NOFOLLOW`.
- The mux sink reopens existing O with `O_RDWR | O_CLOEXEC | O_NOFOLLOW`, **without CREATE, TRUNCATE or APPEND**.
- Sync reopens also omit CREATE/TRUNCATE/APPEND and validate the same exact inode; missing O stays missing and becomes recovery.
- Consider `O_NONBLOCK` on opens before `fstat` so a substituted FIFO cannot hang the opening thread. Reject every nonregular file before any data operation.
- `lstat` each generated parent/leaf, open, then `fstat` and repeat parent/leaf checks before returning a capability. Require same filesystem and captured identities.
- Validate bounded allowed children exactly as the existing protocol: the workspace contains only this generation; the generation contains only `raw.bin`/`output.mp4`. No recursive cleanup.
- No `/proc/self/fd` path, pathname fallback, copy fallback or publicly writable scratch directory is needed.

`O_NOFOLLOW` protects the leaf only. The directory checks plus private UID directory, lifetime `PrivateDirectoryLease` and shared lock protect against cooperating ESV writers. Do not claim these public path APIs are a general defense against a malicious same-UID process that ignores the ownership protocol. That stronger threat model would require separate review, not guessed openat/JNI behavior.

### Source and finalized-output reads

- `length` is the captured extent, never a live `File.length` or an EOF-derived estimate.
- `readAt(offset, target, targetOffset, length)` checks signed/overflow bounds by subtraction before IO: offset in 0..extent, requested length ≤ extent-offset, valid target slice.
- Zero requested bytes may return immediately. Positive requests loop `pread` until the entire requested range is filled, with each syscall request ≤64 KiB and cancellation/deadline checks before and after every chunk. Short positive reads advance by exactly that count; premature EOF, zero progress or impossible counts fail. Never pad with zeros.
- On EINTR or InterruptedIOException, recheck cancellation/deadline and otherwise fail with a bounded typed I/O result in this first adapter; do not retry forever or guess an exception's partial progress. A partially progressed exceptional read/write is not a completed request. Always close/release through the normal ownership path.
- Do not use shared seek position for reads. Internal extractor RESULT_SEEK reuses this same descriptor and fixed extent.
- `validateIdentity` checks open/session authority, regular single-link inode, parent/path binding, exact extent and stable timestamps; full before/after hashes remain necessary for same-size mutation.
- Reading changes atime; do not use atime in the immutable identity.
- After close all reads, identity checks and positional operations reject; no method reopens transparently.

### Mux sink writes/seeks/truncates

- Maintain an internal logical position and known extent for exactly O. No method accepts a new FileDescriptor or pathname.
- Require operation context and byte caps at every call, including final mux close callbacks. The output maximum is bounded by `min(limits.maxArtifactBytes, operationBudget - rawLength, DirectNormalizationLimits.MAX_BYTES)`, matching the engine.
- `setPosition` permits only bounded positions; a sparse seek is not allocation or permission to exceed the extent cap.
- `write(ByteBuffer)` checks position + remaining with subtraction, then itself loops `pwrite` until **all originally requested bytes** have been written or it throws. Each syscall request is ≤64 KiB. Check cancellation/deadline, identity, output cap and reserve-adjusted free space (including any sparse gap/growth) before each chunk and check progress afterward. Advance ByteBuffer position and logical file position by exactly each confirmed short positive count; zero, negative or excessive progress fails. Return the original requested count only on complete success (zero only for an initially empty buffer). Do not rely on the pinned muxer to finish a short write. Cancellation/error after partial progress leaves a nonpublishable WRITING O; it creates no retry/reopen grant.
- Check current size/inode before and after writes; do not trust only the wrapper's counters. A foreign extension, replacement or shrink fails.
- `truncate(size)` is allowed only on this already opened, freshly granted O, as a muxer channel operation. It is **not** a path-level restart or cleanup capability. Reject negative/over-cap sizes; match the pinned SeekableMuxerOutput channel semantics and never enlarge on a truncate request. Check cancellation/free-space before any real growth elsewhere.
- Keep the engine's `BoundedOutput` checks. The storage adapter independently owns its hard cap and file identity; engine wrapping is not ownership authority.
- Never subtract bytes already written from the full 2 GiB admission check before a new attempt. Per-write capacity checks are additional, not a durable reservation.

### Positive close evidence

For the new directly owned descriptor:

1. Revoke new calls and ensure the thread-confined IO operation has actually ended.
2. Invoke the one underlying `Os.close` exactly once in the close owner, without a coordinator/admission/extra session lock. Do not expose or duplicate its fd.
3. Publish `releaseConfirmed=true` only after that close returns successfully and the owned descriptor is invalid. A boolean `isOpen=false`, close invocation, Future completion, thread interruption or cancellation is insufficient.
4. Atomically retire the exact registered descriptor under `fileLock`; session cleanup runs only after all registrations retire.
5. A thrown or hung close leaves release unconfirmed even if a wrapper flag changed. Strongly retain the descriptor/session/storage owner; block publication, mutation and storage close. Do not blindly retry a raw integer fd after a close error, because fd reuse can make that unsafe.
6. Repeated close after positively confirmed release is a no-op. Repeated close of an uncertain descriptor must not manufacture confirmation.

Temporary descriptors used by file ops for hashes/fsync/directory fsync need the same tracked close evidence; a temporary descriptor leak must not escape because it was not returned through MovRemuxFiles. A syscall seam should make close-returned-but-still-valid, close-threw, blocked close and late actual return independently testable.

The engine already treats an actual close exception as release uncertainty. A mux finalization/write/fsync error followed by confirmed descriptor closes is an ordinary failure, not permanent native poison. Do not poison solely because O is partial or unlink failed.

## 4. Fsync and journal sequence

Keep SQLite `journal_mode=DELETE` and `synchronous=FULL` and the existing publication transaction. No database/filesystem atomicity is assumed.

Required normal sequence:

1. Transfer closes and syncs P; its complete original TransferResult is acknowledged in Save. Re-read after any uncertain CAS.
2. Fresh source fingerprint from the exact closed P descriptor, matching transfer completeness. Raw same-size content mutation invalidates evidence.
3. Protocol records RAW_COMPLETE before any workspace/generation/O creation.
4. Create each exact directory and O exclusively. Sync O, generation, workspace and staging as the protocol requires, then durably record WRITING.
5. Mint the one-shot writer grant only for the live invocation above. Remux using exact P and O descriptors. Independent close every descriptor.
6. Engine returns complete source/output/conservation evidence only after its output scan/extractor checks and release handling. Storage independently compares raw hash/extent with the ledger and output evidence with O.
7. `finishOutput`: confirmed release → fsync exact O → sync generation → fresh full P/O fingerprints → recheck active authority → OUTPUT_READY with immutable output fingerprint/proof digest.
8. Coordinator later persists the full Save proof by ordinary CAS, clears the raw download checkpoint as required by `SaveRecord`, and re-reads acknowledgement. Source transfer and lifetime network charges are unchanged.
9. ADOPT_INTENT is durable only when that exact Save proof digest agrees.
10. No-replace P → R; sync generation and staging. Reclassify actual files.
11. No-replace O → P, requiring P absent; sync generation and staging. Reclassify actual files. ADOPTED acknowledgement follows.
12. Later production integration performs independent strict native MP4/conservation/tail verification on P with the normalized descriptor, then existing private commit/inspect and SUCCEEDED CAS.
13. The first normal cleanup attempt removes only exact authorized temporary roles, syncing parents before CLEANED. Cleanup-only failure never changes a successful receipt.

After uncertain rename/fsync/ledger return, read the authoritative row and exact layout. Repeat a missing durability barrier only where the layout permits it; never repeat an already-observed move, assume failure means absence, overwrite P/R, or copy raw.

## 5. Cancellation, failed work and recovery

Cancellation signal is immediate and memory-only. Do not have a control thread close a descriptor concurrently with its synchronous engine syscall. The actual worker unwinds and closes; a blocked syscall retains its exact owner until it really ends.

Terminal-first rule remains: authoritative FAILED/CANCELLED prohibits all continuation even if a failed ledger update leaves CONTINUE. Exact cleanup writes DISCARD_ONLY with its durable deletion plan. A cancellation that loses to SUCCEEDED cannot acquire discard authority.

| Ledger and files | Allowed next action in this design |
|---|---|
| ABSENT, ordinary complete raw | Fresh attempt only through full authority/hash/space checks. No workspace is inferred from filenames. |
| RAW_COMPLETE, P=S, O absent/empty, no R | Explicit caller may finish the interrupted creation barriers; one successful transition may mint one fresh grant. Unknown children/collision/changed source fail. |
| WRITING, P=S, O absent/empty/partial, no R | Inspect only while active. No reopen writer, truncate, restart, delete/recreate O, re-remux, or network retry. FAILED/CANCELLED plus released handles may record and execute exact terminal cleanup. |
| OUTPUT_READY, P=S, O=C, no R, Save proof present | Explicit local continuation may match all bindings and record adoption intent. No writer. |
| OUTPUT_READY, Save proof missing | Read-only re-proof is required before saving a reconstructed full proof. Until implemented, recovery/terminal cleanup only. Never reconstruct from proofSha256. |
| ADOPT_INTENT, P=S/O=C/R absent | Explicit continuation may perform the two authorized no-replace moves. |
| ADOPT_INTENT, P absent/R=S/O=C | Explicit continuation may perform only O → P. |
| ADOPT_INTENT or ADOPTED, P=C/R=S/O absent | Recheck, acknowledge ADOPTED if needed, and later independently verify. No remux. |
| COMMIT_PENDING or matching provisional media | Existing commit reconciliation owns canonical media. Normalization must not recreate P or delete promoted media. |
| SUCCEEDED with matching historical published receipt | Cleanup exact R and empty owned directories only. No Save terminal mutation, republish, native verification, or active-commit reopen. |
| FAILED/CANCELLED, known STAGING/DISCARDED layout | Freshly hash existing bounded partial O; persist exact cleanup plan plus DISCARD_ONLY before deletion; resume only that plan. |
| Any unreadable/mixed/duplicated/unexpected identity, alias or directory child | Preserve material, report bounded recovery category. No guessing, no generic cleanup and no network fallback. |

For WRITING partial O, existing `NormalizationCleanupPlan` already supports a bounded fingerprint different from a completed output fingerprint when `row.output == null`. It also supports size-zero O. This is why no schema change or restart flag is needed. Hash the observed closed partial file, durably bind that exact plan, then require exact fresh fingerprint/identity before deletion. If O changes after the plan, stop. Unknown bytes do not become publishable evidence.

A missing completed source transfer with any workspace state is inconsistency, not permission to download/restart. Inspect the workspace **before** the future coordinator's `transfer == null` transport branch.

### Generic cleanup interception

`AndroidSaveStorage.cleanup` currently calls HLS cleanup and `PrivateCommitProtocol.cleanup`. `SaveCoordinator.finishFailure` can call it after terminalizing a task. Once normalization is wired, this path must first perform typed normalization lookup:

- ABSENT: legacy cleanup unchanged.
- PRESENT: exact normalization cleanup/recovery path owns P/R/O; do not first run generic primary cleanup or HLS helpers.
- UNREADABLE: preserve all material and report recovery; it is never “no workspace”.

Do not rely on `phase != COMMITTING` alone to protect adoption. The phase does not identify P-as-S versus P-as-C or half-adoption.

### Durable fence on every conflicting legacy entry point

The live session registry is only the in-process part of the fence. **It is insufficient after attempt.close(), process death or storage reconstruction.** A durable WRITING/OUTPUT_READY/adoption row still owns the artifact when no descriptor is open. Every conflicting legacy entry point must perform a typed workspace lookup under the same fileLock, deriving exact task/artifact/commit ownership from the durable catalog before returning a path or mutating anything.

Required dispatch, including after restart:

- **ABSENT:** use the existing legacy behavior and existing checks.
- **PRESENT:** use only the lifecycle-specific normalization operation. In this inactive slice, every legacy writer/source-reset/HLS/verification/commit operation on this artifact fails closed. Later, an explicitly proof-bound ADOPTED handoff may permit canonical verification/commit/inspect through the existing private commit protocol; mere presence of bytes, a Save proof, or an empty registry is not that handoff.
- **UNREADABLE/CONFLICT:** preserve all material. Do not return a legacy pathname, create/restart/sync through an append stream, promote, clean or infer absence.
- **PRESENT(CLEANED):** it remains a historical row, not ABSENT. Only a read-only check of exact terminal authority and absence of P/R/O/workspace is allowed. It never authorizes a new writer or recreation for the old task. A new save uses a fresh task/artifact/commit and its own normal admission.

Actual AndroidSaveStorage entry points requiring this durable dispatch before their current private-protocol call:

1. `prepare(taskId, commitId, context)`: for an existing owned task, check before PrivateCommitProtocol.prepare can recreate a missing P; do not bypass by looking only for a currently present staging file. New ownership must have no conflicting task/commit normalization binding.
2. `stagingFile(artifact)`, `openStaging(artifact, append)`, `truncateStaging(artifact, length)`, and `syncStaging(artifact)`: no caller receives P, restarts it, truncates it, or invokes an append-mode sync that might create it while normalization owns the role.
3. `streamDirectory(artifact)`, `adoptStreamOutput(artifact, output, context)`, and `cleanupStream(artifact)`: normalization cannot enter the HLS namespace or replacement/cleanup logic.
4. `verificationFile(artifact)`, `commit(artifact, context)`, and `inspectCommitted(commitId, context)`: these can expose/promote/reconcile canonical bytes. They need the future controlled ADOPTED/proof-bound handoff, and fail closed for PRESENT in this unconnected slice. `inspectCommitted` is not assumed read-only; its protocol may promote or index.
5. `cleanup(artifact, committed)`: dispatch to exact terminal normalization cleanup, as above, before generic HLS/primary cleanup.
6. `retainNativeWriter(artifact)`: cannot piggyback on a normalization sink or an old writer marker.
7. `availableBytes(artifact)`: do not use its existing `protocol.stagingFile` check as a backdoor for a normalization artifact. The session's private reserve-adjusted free-space method and global read-only `availableBytesForSaveAdmission` remain separate and expose no file path.

The common guard should be centralized so later entry points do not forget it. Normalization-owned methods call the exact internal role implementation with their validated capability; they must not bypass their own checks by routing through a public legacy staging API. Do not weaken the common guard to get normalization working.

### Existing escaped File/FD limitation

Legacy `stagingFile` and `verificationFile` return a File. The new registry cannot retroactively revoke that object, discover an untracked RandomAccessFile or prove a previously issued native reader has closed. A new durable guard stops subsequent calls only. **Do not claim the owned-file adapter fences every historical reader.**

The inactive first slice is valid only in controlled owned-fixture tests that do not pre-hold an escaped legacy path/reader, with the factory's exact RUNNING/NORMALIZING operation precondition above. This layer need not redesign old native/verifier signatures, wrap every historical File or add broad unrelated refactoring.

Before production activation, the caller must hold the exact existing Direct execution claim and outer Direct local permit, prove actual transfer/native-reader/native-writer release, and establish a call-order invariant: no legacy verificationFile/stagingFile reader is issued for the same artifact before switching into NORMALIZING and opening this attempt. A phase change alone cannot establish that invariant. Test stale workers, abandoned verifier/native calls, a previous VERIFYING/COMMITTING operation and an intentionally pre-held escaped reader; they must never be admitted as a valid fresh normalization attempt. If that ordering/release proof is unavailable, production remains disabled rather than trusting an empty registry.

The in-process session/descriptor registry additionally blocks these operations during a live attempt and blocks storage shutdown until actual release. `ensureNoWriter` alone is insufficient because the raw reader also pins P. Global library reads with existing published-media authority, unrelated artifacts and global read-only free-space admission remain independent where safe; historical normalization residue must not make a valid published item unreadable.

Bounded startup inspection remains passive. Explicit cleanup uses the already-defined exact key/revision/terminal-publication authority; stale confirmation cannot follow a different generation. Pending discovery reads the indexed ledger including terminal rows, not arbitrary directory enumeration. CLEANED does not grant a new attempt on an old terminal task.

## 6. Locks, execution claims and native gate

For the next inactive storage adapter:

- Use the exact existing `AndroidSaveStorage.fileLock` for workspace protocol, SQL authority, file role mutations and registry state.
- Keep the lifetime `PrivateDirectoryLease`. No second storage owner/root, recursive synchronized instance monitor, or new OS lock.
- `checkOperation` may read the passed cancel token, time/deadline and thread interrupt only. It must not call Coordinator, admission, observers, UI or a runtime callback.
- Authority/ledger callbacks run storage-internal code and may reenter the same fileLock. They never acquire Coordinator/admission monitors.
- File IO/hashing is never under admission/Coordinator locks. The existing protocol may serialize bounded disk work under fileLock; do not call it from a coordinator-held cancellation/observer callback. Refactoring that storage critical section for latency is separate from this correctness slice.
- Synchronous engine IO and external descriptor closes run without storage/admission/Coordinator monitors. Registry reservation protects the paths while these operations execute; after close, briefly reacquire fileLock to publish actual release.
- FileOps' internal exact descriptor operations must not use Java close callbacks or any extra monitor that calls back into storage in reverse order.
- Expose release state through an atomic/volatile memory-only flag. Do not expose a getter that acquires fileLock or performs filesystem/database IO to an admission hook.

When the later coordinator integration is implemented, ordering is:

1. Retain the already-acquired exact Direct execution claim and existing batch native owner.
2. Obtain the shared cancellable Direct local permit on the existing worker, outside all admission/Coordinator/storage monitors.
3. Perform inspection/remux/adoption; then serial verifier → native verifier; then commit/inspect/publication outcome and first cleanup attempt, all within this outer local permit.
4. Release the permit and exact claim normally after ordinary failure and confirmed release. Retained disk residues do not keep a native owner alive forever.
5. On actual release uncertainty, aggregate the memory-only normalization flag into Coordinator `resourcesUnreleased`, runtime `executionReleaseUnconfirmed`, admission and exact retirement checks. Retain the batch owner. Never obtain an exclusive Save claim from inside a Direct claim and never start a replacement native worker.

Do not call `OrdinaryMediaNativeResources.claim` from the file adapter. It performs Java extractor/muxer IO, not a new native media reservation. Do not change HLS, Record, editor or preview resource ownership.

The production activation still needs wiring of the **existing** outer Direct local gate; the current serial verifier alone does not serialize remux/adoption/cleanup. This is not solved by adding a synchronized block to MovRemuxEngine.

## 7. OUTPUT_READY re-proof contract

This is a small **subsequent** read-only extension required for complete continuation coverage, not permission to enlarge the fresh-writer adapter.

Proposed engine boundary:

```kotlin
internal interface MovRemuxCandidateFiles {
    fun openSource(): MovRemuxInput
    fun openOutput(): MovRemuxInput
}
internal fun MovRemuxEngine.proveExistingCandidate(
    files: MovRemuxCandidateFiles,
    context: OperationContext,
    limits: SaveLimits,
): MovRemuxEvidence
```

A storage-created candidate capability binds exact OUTPUT_READY P/O and opens both read-only. It has no writer method, no grant and no create/truncate operation. Reuse the same bounded raw preflight, canonical output scan, actual extractor callback validation, fixed-extent input, hashes and independent close rules. Require equality to ledger raw/output and reconstruct the same complete proof. Its digest must equal the already durable proofSha256 before the missing Save proof CAS. A different engine/policy/schema version or unmatched conservation cannot be silently upgraded.

Do not synthesize this by calling `remux` with a fake no-op sink, by trusting a cached prior engine result after restart, or by storing sample arrays in the journal. If no matching supported re-proof exists, leave local recovery explicit and offer only lawful terminal cleanup/new task.

## 8. Required tests and precise source touch map

### Fresh adapter implementation files

| File | Smallest intended change |
|---|---|
| New `app/src/main/kotlin/com/easysavevideo/app/save/storage/AndroidNormalizationFiles.kt` | Production no-follow exact-role descriptor file ops; creation/sync/hash/move/delete behavior; injected syscall seam. |
| New `app/src/main/kotlin/com/easysavevideo/app/save/storage/OwnedMovRemuxAttempt.kt` | One-shot attempt, input/output adapters, fixed bounds, positive release and all-handle cleanup. |
| `.../storage/AndroidSaveStorage.kt` | Internal factories, owner-lock integration, resource registry, centralized persistent-workspace guard on every conflicting legacy entry point, storage-close accounting and memory-only uncertain-release flag. |
| `.../storage/NormalizationWorkspaceProtocol.kt` | Prefer unchanged semantics. If a typed fresh-begin result is chosen, preserve old WRITING inspection and never return a reconstructed grant. |
| `app/build.gradle.kts` | Add only source-contract input bookkeeping if new tests read these files; no dependencies/tasks/devices/admission changes. |

The v9 schema, workspace codec v1, Save v7/evidence v4, HLS namespace, private commit/publication algorithm, manifest, API minimum and Media3 pins need no change for this adapter.

### New owned-file unit/integration tests

Proposed `app/src/test/kotlin/com/easysavevideo/app/save/storage/OwnedMovRemuxAttemptTest.kt` and `AndroidNormalizationFilesContractTest.kt`, using a deterministic descriptor/syscall seam plus real temporary fixture files where host-safe:

- Owner/task/artifact/commit/source-transfer/generation/operationId mismatch; wrong phase (including VERIFYING/COMMITTING), non-RUNNING or non-Direct task, absent/unreadable ledger, mixed live rows and stale revision. Successful fixture factory calls use exact RUNNING/NORMALIZING state without a pre-held escaped legacy File/reader.
- Fresh ABSENT/RAW_COMPLETE can issue one grant. Existing WRITING with absent, zero-byte or nonempty O issues none. A durable transition followed by thrown/lost return issues none on retry.
- openSource/openEmptyOutput/openFinalizedOutput ordering; duplicate opens; wrong attempt; closed/aborted/consumed attempt reuse; no writer from a returned row alone.
- Negative/overflowed offsets and lengths, exact endpoint reads, short preads, zero progress, premature EOF, seek past extent, same-size raw mutation and changed output after finalization. Assert ≤64 KiB syscall requests, EINTR/InterruptedIOException bounded failure, and cancellation/deadline between every positive partial-progress step.
- Symlink/hardlink/FIFO/socket/directory leaves, parent symlink/replacement, cross-device identity, changed inode with equal bytes, same-inode alias across roles, unknown generation child.
- Missing O on sync/open remains missing; no implicit create. No truncate/restart API reached for recovery. Only exclusive creation can introduce O.
- Writable output opens only at exact extent/position zero. Full 2 GiB admission check including reserve, budget-minus-raw cap, free-space failure, sparse seek growth, partial ByteBuffer writes and buffer accounting (adapter fills the complete original write itself), partial-progress-then-exception, finalization writes and truncate semantics.
- Close every successful and partially failed open; source and output independently; constructor/open failure plus close failure; returned close with valid fd; close exception; repeated confirmed close; no accidental raw-fd retry.
- A blocked close keeps the exact registry/session/storage owner live. Cancellation alone does not retire it. Normal storage shutdown refuses outstanding descriptors.
- Finalization failure with positive closes is ordinary failure; only uncertain release sets the memory-only recovery gate. Cleanup/fsync failure does not set it.
- Same artifact transfer restart/truncate/commit/generic cleanup blocked during session; unrelated artifact behavior remains allowed. Hooks never acquire Coordinator/admission locks from fileLock.
- Repeat every listed conflicting legacy entry point after `attempt.close()` and after reconstructing storage with an empty registry, for RAW_COMPLETE, WRITING (absent/empty/partial O), OUTPUT_READY, each adoption layout, CLEANUP_PENDING, CLEANED and unreadable lookup. No returned legacy path, implicit create, append sync, reset/truncate, HLS action, verifier access, promotion or indexing is allowed. ABSENT preserves legacy behavior. CLEANED verifies terminal/absence read-only and never recreates P or grants a writer. Later ADOPTED handoff tests must reject stale/wrong proof, task, generation, disposition or publication authority.

Extend existing `NormalizationWorkspaceProtocolTest.kt` with production adapter fault seams:

- Before/after every open, create, close, fsync, ledger write, rename, unlink and directory barrier.
- Finished engine evidence cannot finish stale/wrong attempt, altered raw or changed O.
- Full engine → storage finish → Save proof authority → intent → two renames, using original MOV fixtures; output proof still is not native decode success.
- WRITING partial output after cancellation: exact cleanup plan durable before unlink; after-unlink exception resumes only fsync/remaining roles. Changed partial O or stale cleanup revision preserves files.
- Generic cleanup routes PRESENT/UNREADABLE to fail-closed normalization handling, and ABSENT preserves existing behavior.
- Terminal success raw cleanup survives a later Library logical deletion without republishing; failure/cancellation never deletes canonical published media.
- Existing `OwnedFileStreamsTest` and reentrant-close review tests remain unchanged/pass; do not solve new descriptors by weakening old stream release evidence.

Later Android instrumented `app/src/androidTest/kotlin/com/easysavevideo/app/save/storage/AndroidNormalizationFilesTest.kt` must validate actual Android syscall behavior, private-root ownership, fsync/close evidence and real SQL ledger bindings. Host fake syscalls do not establish Android FD semantics. A future authorized device run must use emulator-5556 only and preserve data; none was run for this note.

### Separate production-activation changes still required

These are blockers to enabling MOV, not prerequisites for landing an inactive owned-file adapter:

- `save/.../SavePorts.kt`: bounded Direct local normalization/recovery port; preserve unsupported default.
- Existing `save/.../DirectLocalProcessingGate.kt`: wire its exact cancellable outer permit into the entire local pipeline; no new worker or native acquisition.
- `SaveCoordinator.kt`: pre-network recovery inspection; local-only continuation; normalization/proof/adoption stage; selected output verification; normalized failure/cleanup routing and release aggregation; clear raw checkpoint with Save proof.
- `SerialArtifactVerifier.kt` and `app/.../save/AndroidLocalMp4Verifier.kt`: explicit normalized descriptor support, independent conservation and decoded terminal output evidence, existing strict MP4 gate unchanged.
- `AndroidSaveRuntime.kt`: gate ownership, credential-free completed-local resume, recovery/cleanup status and memory-only release/retirement aggregation.
- `SystemHttpsDirectTransfer.kt` and `PageSaveDetector.kt`: narrow QuickTime discovery/transport only after all acceptance gates, not in this adapter slice.
- Paired EN/ZH recovery/status presentation and exact revision-bound cleanup action if exposed. No generic deletion control.
- Native verifier, concurrency, cancellation/publication and emulator decode tests. A pinned host remux pass is not playback/native release acceptance.

## 9. True blockers and acceptance boundary

No missing library, new dependency, new privilege, internet access or new schema is needed to implement the inactive fresh-attempt owned-file adapter. Available Android public descriptor APIs are sufficient under the existing private-storage/sole-cooperating-owner threat model.

Work still required before claiming persistent-file correctness:

- Implement and test storage-minted fresh-write capability, actual descriptor ownership/release tracking, no-implicit-create production file ops, exact extent/identity checks, and durable exclusion of every conflicting legacy entry point even after the registry is empty or storage restarts.
- Verify actual Android descriptor/fsync/close behavior and SQL authority integration; JVM fixture success cannot substitute.
- Ensure all new storage-internal temporary descriptors participate in release accounting.

Work still required before enabling production MOV:

- Read-only OUTPUT_READY re-proof or an explicitly documented narrower recovery boundary.
- Direct local lane/coordinator/resume/failure/cleanup wiring without lock inversion, plus actual release/call-order proof covering legacy File/FDs that escaped before the new adapter; phase/registry checks alone do not prove this.
- Independent strict normalized native verification, decoded terminal-frame proof, actual device evidence and release gates.
- Correct bounded disk/admission checks and end-to-end cancellation/commit/publication tests.
- Silent-only support must remain explicit; optional AAC is a separate implementation/acceptance slice.

WRITING remux restart is deliberately **not** a blocker to this narrow design: unsupported restart is an explicit safe behavior. It would require a separate reviewed generation/discard/restart contract if requested later.

Cross-checked against the parent's `COORDINATOR_INTEGRATION_NOTES.md`: both specify an inactive owned-file first slice, P → O remux, one-shot fresh authority, no WRITING restart, no digest-only OUTPUT_READY recovery, and later outer-gate/native activation.

This note does not claim these changes exist, tests passed, or production MOV is ready.

## Review receipt and public close reference

Independent design review passed the frozen design SHA-256
`68dc6419f71bb97e9810e1f8a1424c8367bf8d389d180b175ed84cd7ab7f722a`.
This source copy adds only this receipt and public implementation references.

Official AOSP Linux_close snapshots clear the Java descriptor before attempting the
underlying close. Consequently invalid descriptor state alone is insufficient:
require successful close return and the expected invalid state; any close exception
remains unconfirmed and must not trigger a retry against a possibly reused fd number.
Sources: [AOSP libcore 86b8897](https://android.googlesource.com/platform/libcore/+/86b889742c3491c1d5379e146b175f9325e7c377/luni/src/main/native/libcore_io_Linux.cpp),
[AOSP libcore 0dac6dd](https://android.googlesource.com/platform/libcore/+/0dac6dd2e30993ba45f77a20982928dae33200c8/luni/src/main/native/libcore_io_Linux.cpp).
These implementation snapshots are not a claim about the target emulator's actual
runtime; Android descriptor/fsync/close acceptance remains a separate device gate.


## Inactive implementation lock limitation and mandatory activation gate

The first implementation preserves the existing protocol atomicity under the global
storage file lock. Only the bottom-level close of an already-returned source, sink,
or finalized-output handle runs outside that lock. Temporary descriptors used by
hash/create/fsync, directory-enumeration closes during validation, and failed-open
cleanup may still hold the global lock. A blocked syscall or close can therefore
block durable cancellation, recovery and unrelated storage access indefinitely.
This is a production activation blocker, not merely a throughput limitation.

Before runtime activation, executable blocked hash/fsync/failed-open/directory-close
tests must establish that memory-only cancellation and release-state inspection do
not wait for that lock; that no owner, execution claim or worker is prematurely
retired/replaced; and that a late return permits exactly one real retirement after
all releases are confirmed. UI paths must never synchronously wait for these storage
operations. Existing normal returned-handle close tests do not cover all these paths.
If those guarantees cannot be met by the outer execution owner, implement and review
a bounded per-artifact operation-reservation design before activation. Do not silently
unlock protocol transitions or infer atomicity from a successful host fixture run.

Real Android descriptor/fsync/close behavior and AndroidSaveStorage/SQLite integration
remain unverified. The durable-fence decision unit tests and source-wiring contracts
are not dynamic restart tests of every real Android storage entry point. This layer
has no runtime callers and does not admit production MOV.
