# Normalized-only owner capability port

2026-10-14 UTC. Narrow implementation clarification to APPROVED_DESIGN.md. Production MOV admission remains off. No storage, schema, journal or generation protocol implementation is added here.

## Exact app-only API

`AndroidLocalMp4Verifier(stagingFile, timeoutMillis, normalizedOutputOwner: DirectNormalizedOutputOwner? = null)` preserves the legacy constructor and callback. Missing normalized owner is `RECOVERY_REQUIRED`, before worker submission/native allocation. A normalized descriptor never uses the legacy staging pathname callback.

`DirectNormalizedOutputOwner` has `requiresProcessRecovery(): Boolean` and `acquire(artifact: StagingArtifact, output: ArtifactOutputDescriptor.NormalizedDirect, context: OperationContext): DirectNormalizedOutputLease`.

`DirectNormalizedOutputLease` has immutable `artifact`, `output`, `readOnlyInput: RandomAccessFile`; `revalidate()`; one-way `retainOnNativeReleaseUncertainty()`; `releaseConfirmed: Boolean`; and `close()`.

## Mandatory owner obligations

1. Acquire is called only in the existing native worker after `NativeVerificationLease.start()` succeeds. Queued revocation cannot open an FD or acquire an owner fence. The caller already holds the exact Direct execution claim and local permit and passes their controlled binding to the owner. Acquire validates that precondition, exact owner and authoritative active operation/task; it never acquires or waits for a Direct, serial, admission or native lane. The order remains outer Direct permit → serial verifier → native worker → storage capability. It cannot invent a task identity by copying the proof.
2. Acquire checks the durable record's `selectedOutputDescriptor()` by complete value: full original transfer, full normalization proof/task/source/artifact/commit bindings, active operation and exact ADOPTED generation. The lease returns the original incoming descriptor reference, but reference equality is only a verifier anti-substitution check, never authorization. All terminal task states, including SUCCEEDED, reject without mutating records.
3. Acquire retains an exclusive mutation/replacement/publication fence and opens the owned generation once in `r` mode. Artifact, descriptor, RAF and FD remain immutable for the lease. The verifier never reopens a path, invokes the legacy callback, or closes the RAF itself. Descriptor identity/extent checks and hashes do not establish exclusive ownership.
4. Acquire failure independently closes every acquired resource. If release is unknown, the owner itself strongly retains the generation/fence and reports recovery through `requiresProcessRecovery()`, including when no lease was returned. An implementation cannot drop this responsibility into an unreachable partial lease.
5. Revalidate checks the authoritative active operation/task, the exact ADOPTED generation/proof and its owning directory/FD binding; it may not replace the FD, repair a record, adopt a new output, publish, or revive a terminal task. Verifier invokes it before work and again after the same-FD final hash and completed native release.
6. `retainOnNativeReleaseUncertainty()` is nonthrowing, one-way, idempotent, memory-only and takes no reverse locks. It cannot be cleared by close, retry, cancellation or success. Each uncertain codec/extractor/retriever release latches it before owner close. The native cleanup calls remain independent; a stop failure followed by confirmed release does not itself latch it.
7. Close always attempts the owned descriptor close without a cancellation checkpoint. Owner fence retirement is forbidden before all native release calls actually finish and the FD close is confirmed. `releaseConfirmed` means every lease release obligation was confirmed and the owner fence can retire, not merely that RAF close returned. A prior native-release latch keeps it false even after FD close succeeds. A thrown close or a false releaseConfirmed yields `RESOURCE_RELEASE_UNCONFIRMED` and a retained owner fence. A caller cancellation/deadline remains its returned stop reason while the independent recovery gate blocks publication/new work.

## Verifier checks and limits

Visible proof/budget validation precedes allocation. One existing lane and original absolute deadline are retained. Normalized worker obtains the capability, checks original reference/value binding and owner revalidation, captures dev/inode/size on its one FD, bounds positional reads to that fixed extent, independently raw-scans and native-walks all samples, decodes genuine range frames and complete terminal GOP/output EOS, releases native resources, rehashes the same FD and revalidates ownership, then closes the owner lease. The outer worker releases its NativeVerificationLease only after all nested cleanup finishes. Future cancellation is never evidence of resource release.

No real owner is wired in AndroidSaveRuntime. Host fake ports can test these adapter obligations and failure gates; they do not prove a durable generation protocol or Android/device release behavior. Real storage/coordinator integration and emulator-5556 acceptance remain separate blockers.
