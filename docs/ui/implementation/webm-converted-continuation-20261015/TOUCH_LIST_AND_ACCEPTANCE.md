# Candidate touch list and review tests

Read-only recommendation, 2026-10-15 UTC. Paths below are relative to the inspected source. `app/…` abbreviates `app/src/main/kotlin/com/easysavevideo/app/`. This list is deliberately one connected implementation scope; it is not authorization to edit the frozen source.

## Production candidates

1. `save/src/main/kotlin/com/easysavevideo/save/ControlledDirectPipeline.kt`: candidate raw kind, closed private execution switch, separate conversion producer method, ConvertedDirect overloads returning existing verification/commit handles. Public MIME acceptance unchanged.
2. `save/src/main/kotlin/com/easysavevideo/save/SaveCoordinator.kt`: private full branch and current converted recovery; exact producer/evidence CAS, phase ordering, common publication and cleanup. Keep default rejection before writer/adoption when gate closed.
3. `app/…/save/storage/DirectRawSelectionPolicy.kt`: bounded candidate branch detection; reuse raw-history provenance and no-HTTP-retry policy. Full WebM validation remains in existing paired owner run.
4. `app/…/save/storage/AndroidControlledDirectStorage.kt`: exact selection→run→finish caller, converted adoption/recovery, actual binding/release→verification completion, existing commit registries and converted overloads. No second pipeline/owner.
5. `app/…/save/storage/NormalizationAttemptOwner.kt`: conversion adoption-intent overload and typed proof branch for adoption; existing producer run/finish reused without a new outcome seam.
6. `app/…/save/storage/NormalizationWorkspaceProtocol.kt`: conversion adoption-intent overload; reuse existing role moves, fsync and reconciliation.
7. `app/…/save/storage/NormalizationDurableMutationPolicy.kt`: exact conversion phase progression, preserving generic publication/proof/evidence bans and immutable controls.
8. `app/…/save/storage/AndroidSaveStorage.kt`: conversion-aware phase/catalog/evidence authority and ledger adoption binding; reused verification CAS with explicit revision-overflow check; same controlled components.
9. `app/…/save/storage/AdoptedVerificationBindings.kt`: existing-descriptor request overload/branch, exact converted binding and positive read-release witness in the existing in-memory Binding. Preserve production-vs-synthetic separation.
10. `app/…/save/storage/AdoptedCanonicalOutputOwner.kt`: ConvertedDirect acquire/authority branch, same lease/reservation/IO; retain uncertain inspection object in existing poisoned owner if necessary. Existing stop-only mutation policy remains sufficient.
11. `app/…/save/DirectAndroidOutputInspection.kt`: converted acquire/preflight overload on existing port, shared existing-descriptor lease, optional opaque resource-retention overload. MOV parser/conservation remains untouched.
12. `app/…/save/AndroidLocalMp4Verifier.kt`: converted dispatch on the same native lane, owner/process-recovery checks on both owned variants, no fallback.
13. `app/…/save/DirectConvertedAndroidVerifier.kt` (candidate new **adapter**, not owner/parser): small mapping/lease/borrowed-inspection/evidence/cleanup body. It could live beside the existing normalized verifier instead if repository conventions prefer; no separate architecture is needed.
14. `app/…/media/verification/BorrowedAvcAacInspection.kt`: one measured fixed-origin maximum field on existing observations, explicit at every constructor.
15. `app/…/media/verification/AndroidAvcAacOutputInspection.kt`: measured maximum over complete observed video PTS; return only after full matching decode/EOS. Optional pure calculation helper in existing `AvcAacVerificationPolicy.kt` for host tests. No new parser or acceptance changes for Editor.
16. `app/…/save/storage/NormalizedCommitProtocol.kt`: typed converted active branch, shared descriptor internally, all newly reachable terminal-cleanup states; same handle/token/file ordering.
17. `app/…/save/storage/AndroidNormalizedCommitJournal.kt`: independently mirrored converted active/binding/terminal/plan predicates; existing same-database writes and Library publication.

`AndroidSaveRuntime.kt` is a guard anchor: at most an explicit false constructor argument/comment. No UI setting or true runtime flag. `SystemHttpsDirectTransfer.kt` is unchanged. The closed private path can be exercised with owned fixtures; this is not public MIME admission.

## Reuse with no planned semantic edits

- `DirectConversionContracts.kt`, `SaveModels.kt`, `DirectNormalizationContracts.kt`, `SaveRecordCodec.kt`, workspace model/codec/schema: current durable types/versions already express the full chain.
- `OwnedDirectTranscodeAttempt.kt`, paired WebM probe/loader, Transformer engine/session/observer/muxer: current same-attempt producer reused, not rewritten.
- `AndroidDirectExecutionBridge.kt`, native lane, local gate and reservation stages: reuse actual scopes, claims, batch and existing ADOPT/VERIFY/COMMIT/PUBLICATION stages; no new lane or owner.
- `AvcAacMp4Probe.kt` and existing AVC decode ledger: use complete output validation; do not create another MP4 parser. Editor policy/mapping remains unchanged.
- `AndroidNormalizationFiles.kt`, `AndroidNormalizedCommitFiles.kt`, adopted RAF IO: physical roles already work for conversion; verify tests rather than duplicate.
- `LibraryPublicationEvidence.kt` / `LibrarySchema.kt`: already select ConvertedDirect and validate its independent evidence; preserve existing transaction.

## Required targeted evidence

1. **Closed gate regression:** public runtime/MIME/UI stay closed; selected candidate reaches no writer, native run, adoption or publication. MP4/MOV behavior unchanged. Production cannot accept synthetic binding/run success.
2. **Private connected control flow:** core orchestration drives distinct conversion producer→proof CAS→adopt→full converted verification→verification CAS→typed commit→cleanup, with raw transfer/checkpoint semantics preserved and zero legacy/MOV/generic-commit calls. Clearly mark synthetic ports. Later instrumentation must traverse this exact production chain with a real output.
3. **Adoption/recovery:** both moves and all three layouts; throw-after-move and throw-after-CAS readback; wrong inode/parent/hash/generation/revision; missing proof/orphan output/WRITING review; no re-transcode or HTTP after durable proof. Test default-gate rejection separately from private continuation.
4. **Independent facts:** whole-file truncation, wrong sample inventory, corrupt middle/tail, no real output EOS, missing/duplicate decoded frame, wrong dimension/rate, extra audio, expectedAudio=true, fixed-origin maximum above 1,000 µs despite acceptable endpoints. Every observation field is actual measurement; no producer/copied/default scalar. Include a source with start offset plus relative drift that Editor may retain but converted policy rejects. Preserve Editor golden/policy tests.
5. **Owned release and races:** unconsumed/foreign/copied binding or descriptor; forged evidence without a real binding/read; same FD and hash before/after native release; worker/caller cancellation, blocked acquire/close/release, stop during final hash, native and RAF uncertainty, retained actual inspection object, no repeated native release and no second lane. No completion/CAS/publication from delayed results.
6. **Typed CAS/commit:** copied/closed completion, wrong storage or Scope, stale exact snapshot, revision overflow, producer-only or MOV evidence on converted output, changed control during release; write-then-throw; cancellation at intent/promotion/index/publication; final inode replacement; raw+output budget; no visible index/Library/destination until atomic success.
7. **All terminal layouts:** OUTPUT_READY producer regression; ADOPT_INTENT/ADOPTED and invisible INTENT/INDEXED cancel cleanup; exact planned deletion resumes; final canonical retained on success; cleanup after later Library deletion does not republish; historical no-identity plan not upgraded; every conversion state remains readable/PRESENT with control revisions.

Extend nearby existing test suites (`ControlledDirectPipelineTest`, `AndroidControlledDirectStorageIntegrationTest`, adopted binding/owner/race tests, workspace/durable-mutation tests, borrowed inspection/wiring tests, converted codec/contracts and normalized commit/journal tests). Add only focused converted adapter/lifecycle tests where a new adapter genuinely needs them. Source-string wiring assertions are supplemental, not substitutes for behavior. Full build/aggregate gates must run after the implementation freeze under the single build owner; none were run for this proposal.

## Baseline evidence

`INSPECTED_SOURCE_SHA256.json` lists exact inspected source hashes. The producer design matched SHA-256 `b315df2206ccd8a492c4551bf536fa301eaf309c845b35f2cb06833537ac3e4c`; final producer manifest matched `966635930f89407a584c83573a3019ff5331491c3ca9e383ecc6f08dfca136c3`. All seven producer production targets and all three independent cleanup-overlay targets matched the integrated source. `BASELINE_VERIFICATION.json` preserves those comparisons without relying on the cleanup overlay's historical “not integrated” label.
