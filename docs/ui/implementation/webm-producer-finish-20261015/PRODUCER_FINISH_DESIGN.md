# Conversion producer finish: smallest connected slice

2026-10-15 UTC. Frozen design v2 for review, read-only rebased onto Stage B3a production freeze. Design only; no source edits, builds, tests, devices, network or GitHub actions by this task.

## Outcome and boundaries

Consume the existing attempt's private successful SEALED actual run once; finish the existing WRITING workspace as OUTPUT_READY; return one storage-issued, conversion-typed completion; persist its proof and clear the raw checkpoint in one exact Save CAS. That record must remain readable/PRESENT, classify REVIEW, support ordinary stop/control revisions, and reach exact unpublished FAILED/CANCELLED cleanup. Ship these pieces together so producer finish does not create an otherwise unmanageable durable record.

Reuse Save v9, evidence v5, workspace v2, current DirectConversionProof/expectation/settings/completion DTOs, the same owner, maps, token and inode plan. No new owner, durable run DTO, schema/version, dependency, parser or decoding policy. MOV paths and historical audio policy 1 remain unchanged. No nullable MOV/conversion completion union.

This is currently genuinely silent VP8 staging. Detected Opus stays intact and rejected by the current run gate; expectedAudio=true cannot receive a silent proof. Optional Opus remains required product work. No fabricated codec-delay/preroll/padding or audio terminal evidence. Adoption, independent complete converted AVC/AAC decode, converted commit/publication and public WebM admission remain the next layer. Neither producer proof nor host tests establish Android output playability.

Inputs: gap map SHA-256 `9ef52c54a9ac6e0139899b573156d945dda160f24c7ef13ba9da3808d446423c`; approved owned-run design `5cc3a97b4bf1c8d854569be57729a65e8ba54eb90f3b3a1225f7ede1bdfa61cb`. `SOURCE_BASELINE_SHA256.json` records every source file in the gap map's exact list, original source hashes, and the three-file B3a rebase. Stage B3a production is frozen and integrated; its additional latch tests and compilation are still pending. Prior dualcompile154 and host directcallbacks30/paired42/output10 are parent-reported evidence for the earlier Stage B baseline, not B3a verification or checks run here. No successful native Android run is established.

B3a hashes were read from source and matched the parent-provided freeze exactly:
- TransformerEngine.kt: `c87a81879837f512691ba944d2c3dc0a0d9613b13f23e7ab42d6d7d653696213`
- AndroidNormalizationFiles.kt: `5c94f12f6faacecfd766830d14ec94ea674792bbdd50adab009bbf2c670f0e1f`
- OwnedDirectTranscodeAttempt.kt: `8a979d03383485d24fecd7ecb918f8cc1e690e32e0e942295274887f92da320b`

Only those three files were rebased in this follow-up; the manifest preserves when the other baseline files were last checked. B3a's low-level original-error capture precedes invalidation/poison, while `terminalRunFailure` freezes the already-selected actual listener/worker primary. Finish preserves both mechanisms and their independent uncertainty fence.

## Exact small production touch map

Paths are relative to `/workspace/shared/esv-cloud-handoff-20fddc9/source`; `app/…` means `app/src/main/kotlin/com/easysavevideo/app/`.

1. `app/…/save/storage/OwnedDirectTranscodeAttempt.kt`: one-shot SEALED consumption and memory-only proof construction from private retained slots; monotonic FINISHING/HANDED_OFF states; finish-resource predicates; atomic close-claim cooperation with its current owner. Keep all active run/input/output methods sealed out.
2. `app/…/save/storage/NormalizationAttemptOwner.kt`: `finishOwnedTranscode(attempt)`, phase-specific snapshot check, one concrete conversion completion identity set, exact completion validation/close, and serialized handoff versus attempt-close. Reuse transcodes/inputBindings/inputSessions/retiredTranscodes and the same reservation.
3. `app/…/save/storage/NormalizationIoReservations.kt`: only add TRANSCODE → FINISH to the existing transition relation. Its only production caller is the new owner finish path. Keep requireExact/live/uncertain, zero calls and zero obligations unchanged; FINISH → PROOF_HANDOFF already exists.
4. `app/…/save/storage/NormalizationWorkspaceProtocol.kt`: conversion overloads of finishOutput/requireProof; preserve fresh released-file/sync/hash/layout checks and existing persist/readback.
5. `save/src/main/kotlin/com/easysavevideo/save/ControlledDirectPipeline.kt`: distinct `DirectConversionProofCompletion : AutoCloseable { val proof: DirectConversionProof }`; `ControlledDirectJournal.compareAndSetConversionProof(expectedRevision, next, completion)` default-fails. No new admission flag or generic normalize return type.
6. `app/…/save/storage/AndroidSaveStorage.kt`: exact conversion completion CAS and conversion-aware normalizationAuthority proof binding/digest selection. Keep MOV policy behavior and mutually exclusive proof types. Do not admit conversion ADOPT_INTENT/ADOPTED in NormalizationJournal.
7. `app/…/save/storage/AndroidControlledDirectStorage.kt`: retain REVIEW for conversion; validate current producer binding/inspection in a bounded conversion branch before MOV-only proofMatches/descriptor code. Never return ADOPT/VERIFY/COMMIT for conversion in this slice.
8. `app/…/save/storage/NormalizedCommitProtocol.kt`: narrowly admit the unpublished conversion terminal-cleanup predicate below, including exact pending/cleaned continuation. Active commit/publication validation stays MOV-only.
9. `app/…/save/storage/AndroidNormalizedCommitJournal.kt`: the same narrow binding/terminal predicate and plan constraints in its SQL cleanup path. Active/writeIntent/writeIndex/publish remain closed to conversion.

No production semantic change is required in SaveRecord/models/codecs, NormalizationDurableMutationPolicy, AndroidNormalizationFiles, TransformerEngine/observer/muxer/session, or AndroidDirectExecutionBridge. Tests may be added beside their existing coverage. SaveCoordinator and public ControlledDirectStorage conversion dispatch remain unwired until a safe converted continuation exists; the new internal owner and typed journal seam are the connected producer slice. Do not send conversion through MOV normalize or rely on movAdmissionEnabled.

## API and one-way ownership sequence

### 1. Claim SEALED once, without accepting caller facts

`NormalizationAttemptOwner.finishOwnedTranscode(attempt): NormalizationConversionProofCompletion` takes only the exact existing attempt. It accepts no context, outcome, source observation, proof, target, session, function or candidate. A private/internal attempt method can construct and return the existing DirectConversionProof DTO; that DTO alone confers no authority. Do not introduce a reusable completed-run wrapper, public setter or test-only production injection seam.

Under the existing ownerLock, before any transition:

- available storage and exact `transcodes[artifactId] === attempt`; exact key/owner and the original registered input pair/session identities;
- exact storage-issued Scope (`requireTranscodeScope`), `scope.context === attempt.context`, `reservation.scope === scope`, original execution/permit/native batch still live, stop/deadline checked through the original context;
- `reservations.requireExact(token, owner, key, TRANSCODE)`; not revoked, retired, uncertain or process-poisoned;
- `active(owner, context, NORMALIZING) == attempt.expectedRecord`, including exact revision and original transfer/request; no directNormalization/directConversion, verification or receipt;
- exact ledger row equals `attempt.writingRow`, lifecycle WRITING, CONTINUE, no failure/cleanup/output/proof, same source-transfer fingerprint and raw extent; reject revision overflow before the successor is formed; no outstanding file leases (check through the existing owner-side files predicate);
- same original source identity/network expectation, transfer artifact/size and captured output/resource budget. Do not mint a new context, deadline, attempt, retry allowance, Scope, permit or native batch.

Under the attempt lock, memory-only, require all of:

- runPhase == SEALED; the actual private `actualRunOutcome is TransformerOutcome.Completed`; runCompletedTransport/runOutput/runTimeline/resourceObservations and the original pair/session/engine exist and agree;
- `firstOriginalFailure == null && terminalRunFailure == null`; no failed, closeRequested, outputAbortRequested, outputContentFailed, finalizationFailed or outputResourceUncertain; sealed=true, sealingStarted=true, sealing=false; never accept resource sealing alone. If a terminal primary has already been selected, preserve that exact primary through finish rejection and cleanup; do not reselect or replace it from a later original-failure slot or derivative liveness error;
- actual session reports no content/cleanup failure or abandonment and positive releaseAcknowledged; source/writer native retentions are zero and both positive-release flags true;
- calls/outputCalls == 0, readers empty, no anchor/output open or writer-retain in progress, writer exists and is finalizing/drained, both retained descriptors positively closed; do not call back into the owner or files while holding only the attempt lock;
- sealed RAW equals the captured RAW, WRITING.raw and transfer extent; OUTPUT is the captured inode/parents, bounded and equal to actual bytesWritten; actual video samples equal actual displayed count equal the fixed expectedVideoFrames; silent output, transport and observation contain no audio, actual audioSamples == 0; reject expectedAudio=true;
- active-captured complete transport equals the preflight transport and fixed timeline. Never call pair/observer getters after retirement.

Consume the state irreversibly as FINISHING, derive the proof only from these retained values, then advance TRANSCODE → FINISH under ownerLock. The reservation itself independently enforces zero in-flight calls and zero retained obligations. Failure does not restore SEALED or permit rerun/refinish. Keep short owner→attempt memory lock ordering; no I/O, native calls, waits or callback execution while either lock is held.

### 2. Finish using FINISH checks, not retired active APIs

Create an exact FINISH check capturing the same attempt/key/token/Scope/context/expectedRecord. It repeats storage availability, original Scope and stop checks, exact registration and FINISH token, exact Save NORMALIZING snapshot, and expected ledger snapshot at each short boundary. Do not invoke checkTranscode/requireRegistered (TRANSCODE-only and sealing-rejecting) or requireEntered/pair/observer methods here. A fresh memory-only finish predicate replaces none of their active gates.

Off-lock, use the existing files.forOperation(check, token): requireReleased; validate PRIMARY against sealed RAW identity and OUTPUT against sealed OUTPUT identity; require original parents; revalidate fingerprint/size ceilings. `finishOutput(key, conversionProof)` follows MOV's existing order:

1. Read the exact WRITING row and continuing authority; validate proof IDs/raw/transfer against it; require all file leases released.
2. fsync OUTPUT then generation directory; get fresh bounded physical snapshot/hashes. Require PRIMARY=raw, RAW absent, OUTPUT=proof output, expected workspace/generation structure and no identity replacement. Repeat exact identity validation around the finish I/O, not only content-hash equality.
3. Under ownerLock recheck original Save/Scope/FINISH liveness and exact WRITING; persist exactly revision+1, OUTPUT_READY, output fingerprint and proof digest. Existing persist handles write-then-throw by exact successor readback. Overflow rejects.

The finish check must understand the single WRITING→the exact proposed OUTPUT_READY successor during that protocol call; it must not keep demanding WRITING after a successful row CAS. Do not broadly accept any current row or any row with a matching digest. Register the exact returned row only after final identity/liveness validation.

### 3. Transfer the same token atomically; close race must be resolved

Under ownerLock recheck exact returned OUTPUT_READY row, unchanged expected Save, same live Scope/context/token, consumed attempt, no leases/calls/obligations/uncertainty. Then advance FINISH → PROOF_HANDOFF, register one concrete NormalizationConversionProofCompletion (proof, expectedRecord, exact row, same token, original Scope/context), mark the attempt handed off, remove its transcodes/inputBindings/inputSessions entries, and add that same attempt identity to the existing retiredTranscodes set. No new reservation is allocated.

Current attempt.close() first calls requireTranscodeReleaseOwner under ownerLock, then revokes outside the lock. Merely adding retiredTranscodes is insufficient for a concurrent close that already passed that check. Replace that gap with a close-claim operation on the same owner: under ownerLock, either recognize retired/handed-off and return false, or verify the still-registered attempt, mark closeRequested and revoke its token atomically. Drain/FD closing stays outside the lock. Handoff uses this same lock. Thus close wins and finish cannot hand off, or handoff wins and close cannot revoke/retire the completion's token.

Sequential outer `attempt.use { run; finishOwnedTranscode(attempt) }` must return a live completion even though use closes the old attempt. Repeated old close is release-only/no-op. Conversely, closing the original Scope/selection before proof CAS still invalidates issuance; keep Scope/permit/native batch alive through the completion's CAS/readback window. A completion never repairs an exited Scope or creates another one.

On pre-handoff failure, preserve the original stop/storage/native failure; mark finish irreversibly failed, revoke/close the exact attempt and let existing retirement drain it. Never delete durable files in completion/attempt close. If OUTPUT_READY was already persisted, leave it for REVIEW/discard. RESOURCE_RELEASE_UNCONFIRMED/process recovery remains independent and sticky; cleanup success cannot convert a failed run/finish into success.

### 4. Distinct completion and one exact proof+checkpoint CAS

The concrete completion is accepted only by its creating NormalizationAttemptOwner's identity set, with not-closed state and exact original Scope/context/token at PROOF_HANDOFF. `requireConversionCompletion` checks exact expected Save, OUTPUT_READY row, CONTINUE/no cleanup/failure, all owner/raw/source/output fields and digest. It must not accept an implementation of the core interface, copied DTO, foreign owner/storage, stale revision or already-closed completion.

Inside AndroidSaveStorage's existing same-database transaction:

- prior == completion.expectedRecord; prior.revision == expectedRevision and < Long.MAX_VALUE;
- original NORMALIZING/running execution is live, Scope exact, both prior proof fields null, verification/receipt absent; exact registered completion and row;
- only accept `next == prior.copy(task = prior.task.copy(revision = expectedRevision + 1), directConversion = completion.proof, checkpoint = null)`;
- all request/transfer/progress/bytesTransferred/phase/operation/budget/deadline/attempt/error/verification/receipt fields remain unchanged. No intermediate proof-bearing SaveRecord containing the raw checkpoint is ever constructed;
- write with task-id/revision CAS; no generic compareAndSet path acquires producer authority.

The caller holds completion and original Scope through transaction acknowledgement or exact whole-SaveRecord/revision readback, following existing persistExact. A false return/exception is uncertain until readback; equality only of proof digest, task state or output hash is insufficient. If exact proposal is present, acknowledge that durable result even after write-then-throw. If prior/unrelated/unreadable is present, fail closed, retain durable evidence and classify REVIEW; do not retry encoding or reconstruct producer proof. A later stop can be applied as an ordinary control revision after reconciliation; it cannot be overwritten by replaying an old proposal.

Completion close retires its token and unregisters it, with no file deletion or durable mutation; this path must not require the old expected Save still be current because successful CAS deliberately changed it. Zero obligations/calls remain invariant. Closure after a failed or uncertain CAS is allowed release cleanup and does not declare the row absent or the CAS unsuccessful. Do not call pre-CAS requireOwnedBy again to confirm a successful write; exact journal readback supplies that acknowledgement.

## Fixed proof-field sources

- normalizationId/taskId/artifactId/commitId: original exact WRITING key/owner; sourceIdentity: captured request network metadata, rechecked against the same expected Save.
- rawSizeBytes/rawSha256: sealed retained RAW fingerprint; must equal captured RAW and WRITING.raw, with size equal completed transfer. sourceTransferSha256: fingerprint of that exact captured transfer, equal WRITING.sourceTransferSha256; never substitute a hash-only identity check.
- outputSizeBytes/outputSha256: sealed retained OUTPUT fingerprint, same inode/parents, size equal Completed.value.bytesWritten and in 1..captured outputMaximum and current existing DTO ceiling. Fresh finish snapshot must match.
- expectation.video.width/height: captured runOutput, previously verified against guarded VP8/actual format. frameRate/sourceDurationUs: Completed.value.sourceObservation.video.frameRate/sourceDurationUs, equal runOutput and runTimeline. No recomputation from compressed sample counts.
- displayedFrameCount=video.displayedFrames; firstDisplayedTimeUs=video.firstLocalPtsUs; lastDisplayedTimeUs=video.lastLocalPtsUs; maximumFrameDeviationUs=video.maximumFrameDeviationUs. Copy the full observer result, never endpoint-only deviation or sourceSampleCallbacks. Require displayed count equals actual bounded muxer videoSamples and fixed runOutput.expectedVideoFrames.
- expectation.audio=null, settings.audioBitrate=null for the current genuinely silent run. Captured runOutput.audio=null and audioBitrate=0 must agree; zero does not enter the nullable durable audio bitrate field. settings.videoBitrate=runOutput.videoBitrate and exact DirectConversionEncodingSettings.forExpectation agreement.
- rawExtentEof=true only as the consequence of successful active-captured runCompletedTransport from the same guarded actual loader, including true extent EOF/transport equality and logical DataSource close acknowledgement. Probe facts alone cannot set it.
- videoDecoderInputEos/videoDecoderOutputEnded/videoPendingBufferAbsent=true only because the privately owned actual successful engine result was produced after real observer.finish terminal checks, before worker retirement. Caller-constructed observation is not authority.
- muxerFinalized=true only because that same engine result came from successful bounded muxer.evidence after actual release/no content failure, with the checked sample counts. onCompleted alone is insufficient.
- nativeResourcesReleased=true only from actual runner Completed plus no content/cleanup failure or abandonment, source/writer positive retention releases, both positive retained-FD closes, zero calls/leases/obligations and no uncertainty. Historical session.finish/release booleans alone cannot authorize it.
- all six audio terminal fields=null. Future audio requires the real S/E observations plus actual codec-delay/seek-preroll/final-padding mapping already specified in the approved owned-run design; this slice does not manufacture them or rewrite optional Opus into a permanent unsupported requirement.
- Existing schema/profile/engine/policy = 1 / WEBM_VP8_OPUS_TO_AVC_AAC_V1 / 1 / 1. Existing 4096-byte evidence and 32-KiB Save bounds, selected descriptor semantics and raw bytesTransferred remain intact.

## PRESENT/REVIEW and generic mutation fences

normalizationAuthority selects exactly one proof type. For conversion require current policy, full matchesSource(task/source/artifact/transfer), absent MOV proof, source/size budgets and expected-audio consistency; return its digest. Do not turn historical MOV audio policy 1 into an unreadable record by adding a blanket current-policy MOV requirement. Existing orphan/conflicting lookup remains UNREADABLE. SQL presence still reports PRESENT for an owned row even when playback/continuation is unsupported.

The conversion recovery branch uses the normal issued inspection Scope, exact Save/workspace/catalog, no active handles, and owner.inspect's fresh released-file snapshot. A matching current-policy proof + OUTPUT_READY/CONTINUE + STAGING/no evidence + READY layout is preserved PRESENT but returns REVIEW. Mismatch, unknown layout, wrong source/hash/identity or later unsupported state is fenced REVIEW/UNREADABLE; never ABSENT and never legacy fallback. WRITING, or OUTPUT_READY before proof CAS, is REVIEW/discard-only. No run/finish on restart, no proof reconstruction from a digest, no MOV adoption or independent verification claim.

Keep NormalizationDurableMutationPolicy unchanged: same-phase control-only revisions can preserve proof/transfer/output while recording WAITING_USER/INTERRUPTED/FAILED/CANCELLED under the existing state machine. They cannot change proof/checkpoint/verification/receipt, publish SUCCEEDED, mutate progress or advance conversion phase. Resume can change invocation fields only by the existing explicit WAITING_USER/INTERRUPTED→RUNNING rule with no live owner; that grants no finish/run capability and still returns REVIEW. WAITING_USER→WAITING_USER cannot alter/extend UserWait. Do not add ADOPTING/VERIFYING/COMMITTING/CLEANUP phase escape hatches for conversion.

## Narrow unpublished terminal cleanup, in both validators

Both NormalizedCommitProtocol.requireTerminal and AndroidNormalizedCommitJournal's binding/terminal/cleanupProposal must enforce the same conversion-only predicate; neither may rely solely on the other's call site. Preserve the entire existing MOV/proofless path. Branch into this predicate only for record.directConversion != null; keep active commit/publication MOV-only.

Common conversion predicate:

- exact record/workspace/catalog owner tuple, task/request DIRECT, terminal task state FAILED or CANCELLED, phase still NORMALIZING, no MOV proof;
- current conversion policy and full source/transfer binding; raw fingerprint == row.raw; output fingerprint == row.output; normalizationId and proof digest exact; expectedAudio consistency; checkpoint=null;
- record.verification=null, record.receipt=null, owned.evidence=null, indexed=null, indexVisible=false, libraryLinked=false, including no Library association/tombstone; no commit intent/final publication authority;
- row failure absent; exact original source-transfer fingerprint and raw extent remain unchanged.

Initial plan only: row OUTPUT_READY/CONTINUE, cleanup=null, catalog STAGING. The live cleanup reservation can be issued only with all original producer handles/leases released, original invocation permit identity and a separate bounded cleanup IO context, as today. The physical snapshot must be READY: PRIMARY=raw, RAW absent, OUTPUT=proof output, FINAL absent; same valid owned parents. Construct the existing v2 exact inode plan with primary/raw/output/commitOutput fingerprints = raw/null/proof-output/null and matching primary/output identities, no raw/final identity. Do not use the workspace-only legacy cleanup shortcut.

Existing same-database planCleanup transaction must atomically persist only revision+1 CLEANUP_PENDING + DISCARD_ONLY + that immutable plan and catalog DISCARDED, retaining the same Save record/proof. Exact whole-state readback resolves write-then-throw before deleting anything.

Continuation only: lifecycle CLEANUP_PENDING or CLEANED, disposition DISCARD_ONLY, catalog DISCARDED, same exact v2 plan/proof/raw/output/source bindings and no publication evidence. Validate the plan shape again, including identities present and raw/final roles absent; never rebuild a plan from the remaining filesystem. Partial deletion may leave any recorded role absent; a surviving role must match BOTH recorded fingerprint and exact inode. Any replacement or extra final/raw role blocks cleanup and retains files. CLEANED may acknowledge only the already-empty exact layout.

Reuse existing per-role delete/fsync, no-replace ownership, parent checks, directory removal, repeated durability barriers after unlink/rmdir write-then-throw, and exact coherent-state CAS/readback for CLEANED. No converted success cleanup, INTENT/INDEXED catalog, ADOPT_INTENT/ADOPTED acceptance, verification, index publication, final-file deletion or legacy fallback is enabled. Proofless failed WRITING/OUTPUT_READY keeps its existing cleanup behavior.

## Minimum focused tests and acceptance claims

1. Attempt/owner: exact private successful SEALED chain can finish once; resource-seal-only, new/working/released/failed/closed/reconstructed/foreign attempt cannot. Reject stale Save/WRITING/revision/source/transfer/output inode/parent, expectedAudio=true silent, sticky firstOriginalFailure or selected terminalRunFailure (including native unsupported/fallback even if a late completion occurs), late stop, cleanup failure and release uncertainty. Preserve the exact already-selected primary. No active getter is called after retirement.
2. Reservations and locks: TRANSCODE→FINISH and FINISH→PROOF_HANDOFF reject nonzero calls/obligations, wrong token/key/stage/Scope and poisoned state; filesystem work stays outside owner/attempt locks. No budget or execution authority is renewed.
3. Close ownership: outer attempt.use exits while completion remains live; repeated attempt close cannot revoke it. Deterministically race close claim against handoff in both orders. Completion close releases exactly once; exited Scope rejects CAS; no leaked transcodes/input/completion entry or token after normal/error closure.
4. Workspace: conversion finish performs real fixture sync/hash/identity checks, exact WRITING→OUTPUT_READY successor and digest; output/raw/parent replacement rejects. Exercise row CAS before-write failure, write-then-throw with exact successor, unreadable readback, late stop and overflow; only exact successor can be acknowledged, never proof recreation.
5. Typed Save CAS: registered concrete completion only, wrong interface/owner/storage/closed/replayed completion rejected. Only exact revision+1 proof-and-null-checkpoint proposal accepted; unchanged raw/progress/phase/verification/receipt. Wrong proposal/revision/overflow reject. CAS acknowledgement and write-then-throw exact whole-record readback hold the reservation until resolved.
6. Recovery/fences: proofless WRITING, proofless OUTPUT_READY, valid proof-bearing READY, mismatch and unsupported later states remain PRESENT/fenced/REVIEW as applicable; no HTTP/legacy/remux/encoding/adoption/verify/commit calls. Generic control stop/wait/restart preserves proof and no phase/proof/publication mutation becomes permitted. Codec v9/evidence5 and MOV historical audio1 fixtures unchanged.
7. Cleanup: FAILED and CANCELLED from proof-bearing OUTPUT_READY plan/discard/delete/clean correctly; crash after plan, before/after every unlink/sync/directory removal and before/after CLEANED CAS resumes only the exact plan. Reject replaced same-bytes inode, unexpected RAW/FINAL, owner/source/proof/catalog mismatch, no inode plan, verification/receipt/index/Library/tombstone, SUCCEEDED, ADOPT_INTENT/ADOPTED, INTENT/INDEXED. Assert identical policy in orchestration and actual SQL adapter, including write-then-throw coherent readback and no publication effects.

Use existing OwnedDirectTranscodeAttempt/Output, NormalizationIoReservations/WorkspaceProtocol/DurableMutationPolicy, NormalizedCommitProtocol, AndroidControlledDirectStorageIntegration and DirectConversionCodec tests. Add narrowly named conversion-completion/SQL-cleanup tests as needed; test fixtures may stage durable DTOs to test consumers but cannot be represented as actual producer/native success or cause a production outcome injection seam. The real positive producer test must eventually traverse the actual run and listener/cleanup path. Host pass, source inspection, native silent pass, native audible Opus pass and independent converted decode/publication are separate reported gates. This design runs none of them.

## Implementation order and stop line

1. Use the verified B3a production hashes above and preserve their original-reason behavior. B3a latch-test/compile verification remains pending at this design freeze and must be reported separately; this design task does not change source or run it.
2. Implement attempt/owner/transition/workspace/typed CAS and atomic close handoff with focused tests.
3. In the same deliverable, add authority/recovery and both terminal cleanup validators plus tests before making producer finish reachable from any production continuation.
4. Rehash the changed source, run serialized offline checks through the build owner, report exact passed/failed/unrun gates, then stop with admission closed. No automatic adoption or synthetic Android/native success. The next reviewed layer supplies converted adoption, independent full output decode and existing commit/publication integration; required optional-Opus fixtures remain on that acceptance path.
