# Adopted canonical read owner and controlled Direct binding

Date: 2026-10-14 UTC. Design only; source inspected read-only.

## Decision and scope

The next smallest slice is an **inactive adopted-canonical read owner**, with a nonforgeable lexical local-permit capability and deterministic binding/ownership tests. It does not enable MOV, change discovery/MIME admission, wire AndroidSaveRuntime, perform normalization, or publish a file. Coordinator orchestration and the real execution/native-batch bridge remain a separate slice and explicit production prerequisites.

Use the accepted `DirectNormalizedOutputOwner` / `DirectNormalizedOutputLease` interfaces unchanged. The storage implementation opens the adopted primary once with a real `RandomAccessFile(file, "r")`. It does not implement this port by adapting the existing `Os` remux handle, by supplying a fake RAF, by reopening `verificationFile`, or by relaxing the legacy ledger fence.

A local permit proves only that an exact lexical gate invocation is live. It cannot prove that the caller owns the actual Save execution claim or the app's ordinary-native batch. Those authorities have distinct issuers, lifetimes, and tests. In the first inactive slice, production creation of the complete binding remains unavailable; a test-only controlled authority exercises the storage state machine. Missing complete production binding always fails closed.

No user decision is needed for these technical choices. No production code, build, APK, emulator, Git state, or runtime switch was changed for this document.

## 1. Inspected boundaries

Inspected checkout: `/workspace/shared/esv-cloud-handoff-20fddc9/source`; read its `AGENTS.md`. No `.git` was initialized. Parent-supplied persistent-adapter checkpoint is `7c9aa8b`; that remote identity was not independently reverified here. Native source was under a concurrent independent review during inspection. The parent subsequently reported typed-native checkpoint `a243a48e` and 4,261 PASS plus two SKIP; that result was not rerun or independently verified by this design worker. This document is an integration design, not new test evidence.

Relevant source and existing contracts:

- `save/.../DirectLocalProcessingGate.kt`: one semaphore, `withPermit(owner: Any, context, block)`, internal active identity, lexical retirement. No exported token or production caller.
- `save/.../SaveExecutionAdmission.kt`: exact identity `Claim`, prepare/enter/exit, runtime and operation binding, memory-only recovery hooks. `owns(claim)` alone does not prove that an exact execution is currently running.
- `save/.../SaveCoordinator.kt`: private Runtime, `admission.enter` followed by accepted runtime identity checks, separate `admission.exit`; typed normalized orchestration is not wired. Current verifier/evidence/commit calls still use the transferred path.
- `app/.../AndroidSaveRuntime.kt`: actual Direct claim and batch ownership. `claimDirectBatch` owns an internal candidate only after the process-native gate accepts it. The current `directBatchOwner != null` / `isOccupied` observations are not transferable authority.
- `app/.../library/thumbnail/OrdinaryMediaNativeResources.kt`: current public facade accepts `Any` for claim/release and exposes occupancy/restart, not an exact typed batch capability.
- `app/.../DirectAndroidOutputInspection.kt` and `MOV_NORMALIZED_OWNERSHIP_PORT_20261014.md`: accepted app-only RAF lease port, strict preflight and reference anti-substitution check, memory-only native uncertainty latch.
- `AndroidLocalMp4Verifier.kt`, `NativeVerificationLease.kt`, `DirectNormalizedAndroidVerifier.kt`: owner acquisition occurs inside the existing native worker after `start`; queued revocation prevents acquisition. Native cleanup is structurally inside owner close and the outer native-lease finally.
- `AndroidSaveStorage.kt`: one directory lifetime/`fileLock`, durable legacy exclusion, SQL/catalog authority, shutdown accounting. Generic `verificationFile` and generic commit intentionally reject every PRESENT normalization ledger.
- `NormalizationAttemptOwner.kt`: fresh NORMALIZING and ADOPTING sessions; it is not a VERIFYING canonical read owner.
- `AndroidNormalizationFiles.kt`: positive release accounting for `Os` handles; temporary hashing/fsync/enumeration/close still has the documented global-lock blocker.
- `NormalizationWorkspaceProtocol.kt`: ADOPTED layout is P=canonical, R=raw, O absent; cleanup requires terminal authority and confirmed resource release.

The existing 11 `DirectLocalProcessingGateTest` cases must retain their semantics. The current default-null native owner and fake owner tests are not evidence that any of these storage/runtime obligations have been met.

## 2. Authority chain: separate issuers, no invented execution permission

### 2.1 Additive lexical capability in the platform-neutral save module

Add a named API rather than changing or ambiguously overloading the existing callback:

```kotlin
// Proposed API shape; not code implemented by this design.
class DirectLocalProcessingGate {
    class LocalPermit private constructor(/* gate-owned live invocation cell */) {
        // Read-only, memory-only assertion. No acquire, release, IO or external callback.
        fun requireLive(context: OperationContext)
    }

    fun <T> withBoundPermit(
        owner: Any,
        context: OperationContext,
        block: (LocalPermit) -> T,
    ): T

    // Memory-only issuer/lifetime assertion; no lane acquire or recovery callback.
    fun assertIssuedHere(permit: LocalPermit, registeredContext: OperationContext)

    // Existing withPermit(owner, context, () -> T) remains source/behavior compatible.
}
```

`LocalPermit` is final, not a data class or an implementable public interface. Only the gate's private issuer constructs it. A private live-invocation cell and exact issuer registration distinguish tokens; equality of values, possession of `owner`, and copied context do not. The token is one-use in the sense of one lexical lifetime; it cannot be resurrected. There is no `release`, `reactivate`, `copy`, public constructor, or caller-supplied live boolean.

The `Any` argument remains the legacy reentrancy identity only. Supplying any arbitrary `Any` can obtain a genuine local lane; that never grants execution or storage authority. Name and document this limitation in the capability API itself.

`withBoundPermit` shares the existing semaphore and lexical acquire/finally implementation with `withPermit`; it must not acquire the gate by nesting one API inside another with a second lane acquisition. The token becomes visible only after post-acquire stop/recovery checks and active-owner installation. Finally invalidates this exact token before releasing its lexical permit. Only that invocation can do so. Same-owner reentry still rejects BUSY, ordinary exceptions retire the lexical permit, existing priority of stop reasons remains, and there is no new post-success cancellation check.

`requireLive` checks only the token's atomic live cell and immutable context binding; it does not call `resourcesUnreleased`. Initial gate entry continues using the existing real recovery probe. Under `fileLock`, capability checks must never call the gate's configurable recovery callback or wait for its semaphore.

The binding registry is constructed with the exact expected `DirectLocalProcessingGate`
instance. It must call that instance's `assertIssuedHere(permit, registeredContext)`
at registration, consumption and every live-binding revalidation. This method checks
that the token's private issuer is this exact gate and its private live invocation
is still the issued invocation, then checks the immutable context binding. A live
permit from another gate is rejected. `permit.requireLive` alone is not sufficient
for registry acceptance. No public caller-supplied issuer value exists. The assertion
is memory-only: it does not invoke `resourcesUnreleased`, acquire a lane, perform IO,
or call external code, including when used under `fileLock`.

This is an additive **save-module** API change. No Android type enters `save`, `core-model`, or `core-domain`; no core-model schema/API change is needed.

### 2.2 Reuse actual Claim/accepted Runtime/batch ownership in one later bridge

Do not introduce three new public execution/batch/epoch authority systems for this slice. Reuse the actual `SaveExecutionAdmission.Claim`, the Coordinator's already accepted Runtime handoff, and AndroidSaveRuntime's existing exact Direct batch candidate. Only the lexical gate needs a new opaque capability now.

The later composition bridge issues one private, identity-registered `BoundAdoptedInvocation` after checking all existing owners:

- the exact configured admission instance still owns that Claim as DIRECT and has entered the exact task/operation;
- Coordinator has accepted the same Runtime and it remains its active execution, with the current attempt/context;
- the actual app Direct batch candidate is still the exact owner of the ordinary-native gate;
- the local permit is live and the verification call uses its registered context/descriptor.

`owns(claim)` alone is insufficient: a reserved Claim has not necessarily entered execution. `isOccupied` is insufficient: a thumbnail or preview can own the native gate. If current interfaces cannot expose these exact checks safely, add the smallest **read-only exact-owner check** or controlled handoff to the existing owners in the later bridge slice. Do not accept a caller-supplied `isExecuting=true`, `ownsBatch=true`, arbitrary Runtime `Any`, or copied proof as a substitute.

The actual Runtime remains private to Coordinator; the handoff must originate there after its existing accepted-runtime checks. The actual batch candidate remains private to AndroidSaveRuntime. An exact-owner query may internally compare that existing candidate's identity; passing an arbitrary `Any` from an untrusted caller is not the issuance path. This is reuse of the current owner, not creation of a second batch or another native lane.

The complete binding registry entry has a private per-invocation identity and monotonic revoked flag. Real cancel, attempt change, execution exit/replacement and batch retirement revoke it. This single registration identity prevents ABA/stale-worker effects; there is no separately persisted epoch or additional public token hierarchy. Revoke never releases the actual Claim/batch. Existing actual-release/retirement gates continue to own their lifetime.

Any admission/native-gate owner queries run before/after registering the binding **outside** Coordinator/storage monitors. A post-registration exact-owner recheck handles changes during registration. The bridge publishes only a memory-only immutable binding plus revocation cell to the owner; storage never calls admission/Coordinator/native-gate queries under `fileLock`. If the exact owner becomes unverifiable, registration fails or is revoked. The required lifecycle hooks and race tests are part of the later bridge implementation, not an assumption that a snapshot remains authoritative forever.

The first inactive owner slice has no real composition bridge and no way to issue a complete production binding. Missing binding therefore refuses acquisition. This remains an explicit hard prerequisite to live use; synthetic binding tests do not prove it.

### 2.3 Storage binding registry and minimal test seam

Keep the native owner port unchanged by registering a scoped binding before the serial/native call. One app-owned registry is shared by the bound caller and the concrete owner instance:

```kotlin
// Future production caller shape, once the real bridge exists.
registry.withBinding(
    invocation = bridgeIssuedInvocation,
    localPermit = permit,
    artifact = exactArtifact,
    output = exactSelectedOutput,
    context = exactVerificationContext,
    expectedRecordRevision = verifyingRecord.task.revision,
) {
    serialVerifier.verify(exactArtifact, exactSelectedOutput, exactVerificationContext)
}
```

The registry stores actual binding objects, not a map where a caller-supplied task/operation string itself grants permission. The accepted native `acquire(artifact, output, context)` consumes the one registered invocation matching the exact context and descriptor instances, then independently checks full values against durable authority. Identity matching prevents accidental cross-delivery; durable equality and real binding provide authorization.

The registry has only small atomic state transitions; no synchronized block encloses IO, native work, a gate acquire, or a caller callback. `withBinding` finally revokes new acquisition/evidence. It never closes a worker's RAF and never removes a still-acquiring/active/uncertain ownership reservation. A previously consumed slot cannot be reused by a second worker.

For the inactive slice, factor the state engine behind an **internal test seam** with controlled issue/revoke/start/complete transitions. Test helpers are in test sources, not a shipped permissive owner or `allow=true` fallback. Tests exercise live and revoked registrations, exact owner identities and a real gate permit; they do not claim that their synthetic execution/batch registration came from AndroidSaveRuntime. Production factory remains unbound/disabled until the real bridge is reviewed. Do not put a default-true binding provider or an externally constructible execution token in production to make fixtures convenient.

A shortened verification context is permitted only as an explicitly registered derivative: same operation, cancellation-token identity, attempt, retry budget and resource budget; deadline no later than its executing context. Registration captures that exact derivative instance. Neither a fresh cancellation token nor a larger/renewed budget/deadline is accepted. The existing shorter verification deadline is therefore compatible without treating data-class equality as authority.

## 3. Dedicated adopted storage owner

Add an internal `AdoptedCanonicalOutputOwner` implementing the accepted app interface, separate from `NormalizationAttemptOwner`. A storage-controlled factory supplies only:

- the exact shared registry/binding authority;
- typed read-only record/ledger/catalog callbacks;
- the shared mutation-reservation registry and `fileLock`;
- private root/directory ownership, exact generated path resolver and RAF/metadata seam;
- memory-only stopped/recovery views.

No public pathname, File, URI, FD, `released=true`, arbitrary task owner or unverified proof authorizes acquisition. The existing `verificationFile` callback remains inaccessible on this path.

### Exact authoritative snapshot

At acquisition, record a snapshot containing the complete record or its relevant complete immutable values **and exact task revision**, exact workspace row **and revision**, catalog row, operation/attempt, normalization generation, current binding registration identity, and captured filesystem identities. Reject if any relevant identity is ambiguous or unreadable.

All of the following are mandatory:

1. Storage is open and has its exact private-directory lifetime lease. No process poison, previous unresolved canonical reservation, remux session/temporary descriptor, transfer writer, native writer or conflicting tracked reader exists for this artifact. Unknown legacy reader provenance is not silently assumed released.
2. Authoritative Save task is exactly RUNNING, method DIRECT, phase VERIFYING, and its operation/attempt match the exact accepted execution represented by the binding and registered verification context. Reject every other phase and every terminal state including SUCCEEDED. New reads cannot revive WAITING_USER/INTERRUPTED either.
3. Record's artifact and commit match the incoming artifact; the catalog row is exactly STAGING with that task/artifact/commit. No COMMIT_PENDING/INDEXED/DISCARDED exception.
4. Original complete raw `TransferResult` is unchanged. It has positive complete bytes, expected-total agreement, no Stream/Record expectation, and exact source-transfer digest. Preserve response MIME and network-byte accounting.
5. Authoritative `record.selectedOutputDescriptor()` is `NormalizedDirect` and completely equals the incoming descriptor, including the original source transfer, full normalization proof/conservation and all revision/profile fields. `proof.matchesSource` must agree with record's actual source identity and exact task/artifact/commit. A digest alone or `output ===` is insufficient.
6. Typed ledger is PRESENT with exactly that owner/generation, lifecycle **ADOPTED**, disposition CONTINUE, no cleanup plan; exact raw fingerprint/source-transfer digest/output fingerprint/proof digest agree with the full authoritative proof. Capture and later require the exact workspace revision, not merely `revision >= old`.
7. The generated layout is canonical P present, exact workspace/generation present, raw R present, O absent, bounded allowed children, regular single-link files, no aliasing, stable parents and same filesystem. P's extent equals proof output bytes; R's extent equals ledger raw bytes. No adoption, repair, fsync, rename, writer opening or cleanup occurs as a side effect.

This dedicated acquisition is not `NormalizationWorkspaceProtocol.inspect`: that helper fingerprints paths and can reopen P. The canonical bytes are fully hashed on the one returned P descriptor by the native verifier before/after native work. Structural checking of R is not a claim that this owner re-proved raw-backup content or completed crash recovery. Recovery that needs a fresh R hash/full protocol layout attestation remains a separate, explicit, tracked read-only recovery operation before a new execution binding is issued. An invalid/missing R or suspicious layout still rejects; no lost proof is reconstructed from a digest.

Save revision policy is deliberately strict: any intervening revision invalidates the in-flight verification result, even an apparently harmless progress revision. Future callers must avoid updating progress in the middle of this phase or request a separately reviewed policy. This gives a small, testable publication fence rather than guessing which unseen record changes are benign.

## 4. One real RAF, two-phase reservation, and failure ownership

### Acquisition sequence

1. Native worker first wins `NativeVerificationLease.start()`. The worker checks its original absolute deadline/stop state. A queued revoked native job never calls the owner.
2. Atomically consume the one matching registered binding. A revoked/non-live/wrong-context/wrong-registration/already-consumed binding fails without storage or file acquisition. Marking a binding CONSUMING is not yet a file owner fence.
3. Under `fileLock`, re-read exact durable authority and conflict state; install a distinct per-artifact **ACQUIRING reservation before any potentially blocking file open or metadata/enumeration work**. Check storage-closed again. The reservation itself participates in shutdown and mutation exclusion. No Direct/local/serial/native lane is acquired here.
4. Release `fileLock`. Under that reservation, perform bounded parent/leaf/layout checks and open P exactly once with `RandomAccessFile(derivedP, "r")`. Immediately retain the returned RAF in the registered acquisition object before any `fd`, fstat, length or post-open validation can throw. The same RAF object and FD object remain immutable until close. Never use `rw`, a path fallback, `/proc/self/fd`, a dup to manufacture a different RAF, or reopening on seek.
5. Capture/check lstat and fstat identity, regular type, link count, extent, nanosecond mtime/ctime and complete required parents. Repeat path-to-FD checks after open; atime is not immutable. All registered aliases/role replacements reject. Native verifier's own fixed-FD scan/hash remains required.
6. Re-enter `fileLock` and revalidate exact record revision, row revision, catalog, binding and reservation identity; reject if cancellation/revocation/terminal change happened during IO. Publish ACTIVE only after all checks. Return the original incoming output reference as required by the native anti-substitution contract.
7. On any failure, revoke evidence/new calls and independently close every actually acquired temporary resource and RAF, without a cancellation checkpoint. Never remove ACQUIRING until all started opens/metadata scopes and release obligations actually finish. A late open after revocation is immediately closed by that same worker and never returned.

The registry's pending binding before native start does not pin an artifact or open a descriptor. Storage may close in that interval; subsequent acquisition then fails closed. Once the ACQUIRING reservation is installed, storage close must return BUSY/recovery instead of releasing the database/directory owner.

### RAF-specific limitations and test seam

A real RAF `"r"` is the accepted port's owned handle; the existing `NormalizationDescriptorIo.Handle` tests cannot prove its close semantics. Add a small `AdoptedCanonicalRafIo` seam for constructor, fstat/lstat, FD validity and close of that same RAF. Production opens the real RAF, tests can block/fail boundary operations while retaining a genuine temporary-file RAF.

Public `RandomAccessFile(File, "r")` does not expose the existing `Os.open` NOFOLLOW/NONBLOCK flags. Do not claim those flag guarantees survived the adapter conversion. Reject symlink/nonregular leaves and changed parents through pre/post checks plus the private UID namespace, lifetime directory owner and cooperating mutation reservation. This design does not defend against a malicious same-UID writer ignoring the protocol. If that stronger threat model is required, the accepted RAF port needs a separate reconsideration; do not invent openat/JNI/descriptor-adoption APIs or dependencies.

A constructor/open that is still in progress owns a reservation even before a RAF object is returned. Do not manufacture a `not opened` result from absence of a returned lease. Known pre-open validation rejection has no open obligation; after entering the actual constructor, an unclassified exception with no confirmed resource outcome conservatively retains an unresolved-opening fence. Once a RAF is returned, register it before any other fallible step. This explicit conservative case prevents a partial acquisition from disappearing into an unreachable local variable. Tests distinguish known no-open failure, returned-handle validation failure, blocked open, and unresolved constructor outcome.

A genuine successful RAF close plus invalid original descriptor is positive FD release evidence. Close-returned-but-still-valid, a thrown close, an unresolved constructor, or a permanently hung close is not. Do not retry an uncertain raw descriptor or a RAF whose prior close outcome is unknown; a reused FD could identify another file. Retain the owner/acquisition record and original resources strongly through a process-retained failure root.

## 5. Revalidation, close, cleanup and shutdown exclusion

Use `fileLock` only for short registry/authority decisions. No additional owner monitor can be held while acquiring it. New filesystem enumeration/stat/open/hash/close and all native calls occur outside it under the installed reservation. Binding/stop/uncertainty checks are memory-only. The native worker never calls back into Coordinator/admission or waits for any processing lane.

`revalidate()`:

1. On its acquiring native worker, atomically/under `fileLock` claim an in-flight validation slot only if ACTIVE and no close requested; check exact authority snapshot and live binding.
2. Outside `fileLock`, check the same open FD and full required path/parent/layout identities. Do not reopen or replace it, hash via another FD, repair a ledger, or adopt a newer generation.
3. Under `fileLock`, compare exact revisions/authority again and finish the validation slot. Any change invalidates evidence. Revocation can occur throughout and wins over returning a usable result.

The verifier invokes it before scanning and after completed native releases plus final same-FD hash. Read/seek operations remain confined to that same worker. Background cancel/revoke code never directly closes a RAF being used by native code.

`close()`:

- Seals new operations immediately and is cancellation-independent. Only the owning native worker performs the bottom-level close after its nested native cleanup has actually returned; do not add a timeout thread that closes underneath a blocked codec/extractor.
- Close and validation cannot run together. A cross-thread close request can only mark closing/revoked and retain the owner until the actual worker/validation scope drains; it cannot report success or perform a competing close. The supported native path calls close on the owner worker in its finally.
- The worker takes the exact-once close slot under the short lock, drops the lock, calls RAF close, then publishes the result under the lock. A second close while the first is pending does not wait under `fileLock`, try another raw close, or pretend release.
- Normal positive release requires all started IO scopes complete, all native cleanup calls actually returned with confirmed releases, confirmed RAF close, no one-way native uncertainty, and no unreturned temporary resource. Then the exact reservation can retire. Otherwise retain it.
- `releaseConfirmed` means the entire release contract, not just `!fd.valid()` or Future completion. It can never become true after native uncertainty was latched.

`retainOnNativeReleaseUncertainty()` is a one-way nonthrowing atomic latch, idempotent and memory-only. It retains/publishes the owner failure root without taking `fileLock`, the gate, admission or Coordinator locks. The supported verifier calls it synchronously on the owning worker when native release is uncertain, before RAF close; sequential worker ownership prevents a late competing latch from racing ordinary retirement. External cancellation cannot invoke a competing successful-close path. Even if RAF close subsequently succeeds, the latch, generation fence, recovery gate, Claim/batch retention and `releaseConfirmed=false` remain. Stop failure followed by positively completed release alone is not native uncertainty.

### Shared registry participation

Add canonical reservations to the existing storage conflict view. Keep the current remux sessions/descriptor registries as their owners; do not replace or rewrite them with a second normalization lifecycle. The combined helper queries both owners under their existing shared storage lock, and the memory-only recovery view combines their atomic states. Required consumers are:

- `hasAnyLeases`, per-artifact active-owner checks and `ensureNoWriter` or a more accurately named conflict helper;
- `NormalizationAttemptOwner.noSession`, `recordAdoptionIntent`, `adopt`, `cleanup` and `inspect`, checked before any protocol snapshot/hash or mutation; prepare/finish must also remain mutually exclusive with canonical reservations;
- generic prepare/open/restart/truncate/sync/commit/cleanup and future typed commit;
- storage close and directory-owner release;
- memory-only combined normalization recovery/draining probe.

The existing `ensureNoExistingHandles` callback can carry the new canonical-conflict check into `noSession` and `inspect`; retain the old remux reservation implementation. This explicit check is mandatory even though generic legacy APIs are already fenced: the dedicated terminal `cleanup` path can otherwise delete canonical P. Do not make two owner instances each believe the other has no handles. A cleanup call must check the shared reservation before protocol inspection/hashing, not discover the conflict only just before unlink. Storage close returns BUSY while a known active or pending owner exists; uncertainty has RESOURCE_RELEASE_UNCONFIRMED priority. It neither force-closes the owner nor releases the database/directory lease under it.

The owner must block mutation/replacement/publication while allowing cancellation authority to be recorded. In `AndroidSaveStorage.compareAndSet`, a live canonical reservation rejects changes to operation, generation, proof, transfer, artifact, verification-to-commit handoff or SUCCEEDED publication. A legitimate stop-only update to CANCELLING/WAITING_USER/FAILED/CANCELLED can proceed without changing the selected data; it revokes evidence and changes the strict revision. Such an update does not delete material or retire the read owner. Existing terminal-row no-revival rules remain. Later terminal cleanup still waits for actual release.

## 6. State transitions and race outcomes

| Binding/storage state | Acquisition/evidence | Mutation/storage close | Exit condition |
|---|---|---|---|
| UNBOUND | Reject; no fake default binding | Existing rules | Real issuance required |
| BOUND, native queued | One consumable authorization; no FD/fence yet | Storage may close; worker must recheck | Consume or revoke |
| REVOKED before consume | No acquire; no evidence | No resource to retain | Remove only this unconsumed registration |
| CONSUMING, no reservation yet | Recheck live authorities; no IO yet | Closed storage makes acquisition fail | Install reservation or end with known no resources |
| ACQUIRING | No returned lease/evidence | Exact artifact and storage lifetime retained | All IO completes; publish ACTIVE or close failure resources |
| ACTIVE | Same worker/FD, live binding, exact revisions only | No mutation/cleanup/close/publication | Native cleanup then owner close |
| REVOKED_ACTIVE / REVOKED_ACQUIRING | No new evidence/acquisition, no resurrection | Keep reservation, Claim/batch/native drain | Actual late release or sticky uncertainty |
| CLOSING | No read/revalidate/new open | Keep exact owner; memory drain visible | Actual one close finishes |
| RELEASED | Never usable again | Reservation may retire; future work requires new binding | Terminal for that lease |
| UNCERTAIN | Never usable again | Process-retained fence, no cleanup/publication/shutdown | Real process restart and explicit durable recovery only |

Required race resolution:

- Queued native cancel: native lease `start` loses; binding revoked; zero owner acquire and zero RAF open. Do not poison because a queued job never started.
- Start versus revoke: both state machines have exact atomic transitions. If native start wins but binding consumption loses, no RAF is opened. If consumption/reservation wins, revoke prevents evidence but retains all started operations until their actual exit.
- Revocation while constructor blocks: caller may finish with CANCELLED/deadline; old worker remains sole owner of pending open. A late RAF is closed, never returned. No replacement worker or new generation is authorized while it is outstanding.
- Timeout during native call: `Future.cancel(true)` and a released lexical/serial semaphore do not retire native/FD/storage ownership. Existing NativeVerificationLease abandonment is published before the outer verifier returns; combined memory gate keeps new work and Claim/batch retirement blocked.
- Native release blocks: RAF is not closed concurrently by another thread; the same worker closes it only after release actually returns. If release throws, latch uncertainty first, still attempt every remaining native cleanup and RAF close independently.
- RAF close blocks and later succeeds: the retained reservation drains exactly once after actual return if no sticky native/FD uncertainty. This does not rehabilitate evidence already discarded for timeout/cancel.
- RAF close throws: never retry that handle, even if `fd.valid()` now happens to be false. Retain sticky fence. Native latch plus successful RAF close also remains sticky.
- Record revision, task terminal, operation replacement or generation changes during validation: reject all evidence, keep ownership until real cleanup; never adopt newer facts into an old lease.
- Stale worker's finally: removes only its own identity-registered record. It cannot revoke a new binding, decrement another worker's drain, release another Claim, clear another batch, or remove a replacement generation reservation.
- New caller enters the lexical gate after an old timeout: the real combined recovery probe rejects while old native/storage work drains. It must not deadlock trying to reacquire the same local gate from the old native worker.

Normal active ownership is not itself permanent process poison. Maintain a memory-only view distinguishing ordinary active, revoked/draining and sticky uncertain. The owner port's recovery getter is true for unresolved failed acquisition, revoked/in-progress drain or uncertainty as appropriate, without SQL/fileLock. It must not reject its own normal healthy ACTIVE lease simply because that lease exists. Unknown probe errors fail closed.

## 7. Existing production blockers that this slice does not erase

### Temporary IO still holds global fileLock

The existing persistent-file adapter's temporary hash/fsync/open/close and directory enumeration cleanup can still block under `fileLock`. Adding an off-lock canonical RAF does **not** fix that code. No activation approval may omit this blocker or claim a local-permit token resolves it.

Before production enablement, refactor those existing temporary paths into reservation → off-lock IO/close → exact identity/revision completion, with every temporary open and enumeration resource represented before blocking work starts. Preserve positive-release and independent-cleanup semantics. Test blocked hash/fsync/open/enumeration/close, memory-only cancellation/recovery responsiveness, retained execution/Claim/batch ownership, late return and exact-once retirement. SQL's shared-lock behavior also remains an explicit blocking boundary; UI must never call these storage paths synchronously, and memory cancellation cannot depend on durable cancellation acquiring that lock.

### Previously escaped legacy File/FD

The durable legacy fence only prevents **new** generic acquisitions. It does not revoke an already returned File, stream, FD, or native duplicate. An empty current writer map is not proof that an old capability was released. Real Coordinator/execution/local sequencing must establish that all earlier transfer, verifier and publisher owners are actually drained before issuing this binding. A merely populated ledger cannot retroactively establish that ordering. If provenance cannot be established, require recovery/fail closed.

### Port trust boundary

The verifier receives a RAF and Android native APIs can duplicate its FD. Storage cannot revoke those native duplicates through RAF close. The one-way native uncertainty latch and reviewed worker-finally structure are essential. No host `Os` fixture or native fake establishes Android duplicate-FD release behavior.

## 8. Concrete touch map

### Small inactive implementation slice

| File / area | Required change | Must stay out |
|---|---|---|
| `save/.../DirectLocalProcessingGate.kt` | Final private-issued LocalPermit; named additive withBoundPermit sharing existing lane/lifetime | No execution-claim inference from Any; no new lane; no change to existing withPermit semantics/probe |
| New `save/.../DirectLocalProcessingGateCapabilityTest.kt` | Mint/lifetime/context/reentry/race tests; keep old 11 tests unchanged | No claim that token proves app batch ownership |
| New `app/.../save/storage/AdoptedVerificationBindings.kt` | Identity-scoped binding registry/state engine; no default issuer; memory-only revoke/consume | No production fake or Boolean/Any authorization shortcut |
| New `app/.../save/storage/AdoptedCanonicalOutputOwner.kt` | Accepted owner port, exact snapshot, registered pending acquire, readonly lease, latch/close/revalidate state | No adoption, remux, recovery, publication or generic path fallback |
| New `app/.../save/storage/AdoptedCanonicalRafIo.kt` | Real RAF r-only construction and metadata/close seam; one canonical open | No fake RAF implementation in production; no Os-to-RAF conversion or nofollow claims |
| `AndroidSaveStorage.kt` | Internal inactive factory/typed read callbacks; shared reservation conflicts; shutdown/recovery accounting; CAS publication exclusion | Runtime still has no owner instance wired; legacy ledger fence unchanged |
| `NormalizationAttemptOwner.kt` / existing `ensureNoExistingHandles` integration | `noSession`, `recordAdoptionIntent`, `adopt`, `cleanup`, `inspect` check canonical reservation before protocol hashes or mutations; test each route | Keep current remux sessions/descriptor reservations; no VERIFYING writer owner and no read-protected P reopen |
| App tests | Real temporary-file RAF + fake bounded authority/metadata/native cleanup schedules; source-wiring regressions | No device/native acceptance claim |
| Implementation docs | State inactive scope, locks, fake-binding boundary and explicit remaining bridge/Coordinator blockers | No declaration of MOV completion |

No schema migration, dependency addition, license/runtime-admission graph change, normalizer algorithm change, or production `AndroidSaveRuntime` behavior is required here. If isolated changes to private helpers are needed, they must preserve the existing protocol transitions and row constraints.

### Subsequent real bridge / Coordinator slice

- `SaveExecutionAdmission` + `SaveCoordinator`: reuse exact Claim/accepted Runtime handoff with binding issuance/revocation, no admission/Coordinator monitor nesting; selected-output normalization path and stable VERIFYING snapshot.
- `AndroidSaveRuntime` + `OrdinaryMediaNativeResources` / native gate if necessary: reuse exact existing batch candidate, add only an exact-owner read check if required, register complete binding, keep claims and ordinary native batch until combined actual release.
- Runtime recovery aggregation: include normalization temporary and canonical failed-acquire/drain/uncertainty views; current `executionReleaseUnconfirmed` does not include the new storage owner.
- Dedicated typed normalization pipeline/commit handoff ports in `save`/app adapters; no Android data types in core.
- Existing temporary IO refactor and tests; explicit recovery/reconciliation and allowed cleanup scheduling.
- Actual emulator-5556 acceptance, preserved data, only within the existing user authorization and serialized build/device ownership. This design did not run it.

## 9. Test and review gates

All listed tests are acceptance requirements, **not executed results** from this design pass.

### A. Local capability compatibility

- All existing 11 gate tests still pass unchanged, including late-cancel-after-publication behavior and recovery-probe semantics.
- Capability minted only after actual lane acquire, no token for cancelled/expired/interrupted waiters, token revoked on normal/error exit, stale token cannot pass.
- Bound/unbound calls serialize on the same lane; same-owner nested call fails BUSY without releasing outer owner. A worker consuming a capability performs zero extra lane acquires.
- A genuine token from a foreign gate, wrong lexical invocation/context/attempt or expired scope is refused by the registry. Equality-copy alone cannot substitute for registered binding identity.
- Obtaining a local permit with arbitrary Any does not allow canonical storage acquisition without the separate complete binding.

### B. Exact storage authority matrix

- Correct RUNNING/VERIFYING, STAGING, exact ADOPTED row/proof/record revisions opens one real RAF in r mode and returns the identical incoming descriptor.
- Independently mutate task/artifact/commit/source identity, transfer MIME/total/bytes/digest, proof/conservation/revision, operation/attempt/context, record revision, ledger revision/generation/disposition, catalog state and expectedAudio-relevant caller contract; all invalid cases fail closed.
- Reject ABSENT/UNREADABLE/RAW_COMPLETE/WRITING/OUTPUT_READY/ADOPT_INTENT/CLEANUP_PENDING/CLEANED/RECOVERY_REQUIRED and every non-RUNNING/VERIFYING task, especially SUCCEEDED. No writer/recovery/adoption action called.
- Bad P/R/O layout, symlink/nonregular/link count, parent replacement, alias, inode/device/size/mtime/ctime change, missing generation/raw or unexpected bounded child rejects.
- Generic verification/commit/cleanup remains fenced for all PRESENT rows, including ADOPTED and CLEANED, even after the in-memory reader registry empties.
- P hash and native walk use the same once-opened RAF/FD. Attempts to write/truncate through the real r-only handle fail. No path reopen after acquisition/seek/revalidation.

### C. Failed acquisition and resource accounting

- Fail at each boundary before open, during open, immediately after returned RAF registration, fstat/FD access/post-open validation and final authority check.
- Every acquired resource receives an independent close attempt; a first failure does not skip others. No-return partial acquisition remains strongly retained and appears in storage close/recovery gates.
- Known pre-open rejection creates no leaked reservation; unresolved constructor outcome keeps recovery. Late blocked-open success after revoke is closed once and returns no lease/evidence.
- Failed-close and close-returned-valid never retire owner; actual successful close retires only with all other obligations confirmed.

### D. Races, latch and shutdown

Use latches/barriers rather than sleeps to exercise both orders of every race:

- queued cancel versus start, start versus consume, consume versus revoke, acquire versus storage close, revoke during IO, terminal/CAS change during both validation phases;
- revalidate versus close, native release versus cancellation/timeout, blocked close versus duplicate close, cleanup/adopt/generic mutation versus ACQUIRING/ACTIVE/CLOSING/UNCERTAIN;
- native-release uncertainty + successful RAF close retains fence and false releaseConfirmed; stop failure + positive native release + positive RAF close is ordinary failed work, not sticky poison;
- timeout result never becomes success after late releases; drain can clear only on actual completion, and old worker cannot touch replacement registry/Claim/batch counters;
- storage close cannot release DB/private-directory ownership under a live/failed-acquire owner. Terminal cleanup waits rather than force-closing.

### E. Binding truthfulness and future live-bridge acceptance

- Tests are labeled synthetic binding or real-gate-only as applicable. No test fakes `Any`, Boolean, proof or OperationContext as production execution authority.
- The inactive factory refuses an absent production bridge. Native verifier's default-null owner remains fail closed. AndroidSaveRuntime stays unmodified/unwired.
- Later bridge tests must use actual Admission.prepare/enter/exit plus accepted Coordinator runtime; reserved-but-not-entered, wrong Claim/admission/batch, abandoned/old Runtime, attempt change and released native batch all reject.
- Later real-lane tests retain Direct claims and native batch after lexical exit while native or storage release is pending. Memory gate responds while fileLock IO is blocked; no new/replacement worker starts.

### F. Verification levels

The implementer runs focused save/app host suites, applicable aggregate regressions/static boundary checks and independent review against exact final hashes. Host RAF tests establish Java ownership/state behavior; fake metadata and fake bindings establish only the injected decisions. Existing remux Os fixtures remain separate evidence. Actual Android RAF descriptor metadata, duplicate-FD native cleanup, blocked-release behavior, SQLite/directory/fsync and crash-recovery tests are required before activation. No device result is inferred from this document or from the parent checkpoint.

## 10. Remaining Coordinator and typed publication work

The read owner is not a publication capability. Future end-to-end implementation must still:

1. Hold the real Direct execution/native-batch ownership across the full local pipeline; acquire one local permit outside serial/native work. End HTTP writer ownership positively before normalization. Preserve original transfer/network charges.
2. Prepare/remux/finish only under NORMALIZING, persist full proof and acknowledge its exact Save CAS, enter ADOPTING, record exact intent, adopt, then persist VERIFYING and register the complete binding. Handle lost CAS/rename returns by exact reconciliation, never a fresh writer grant.
3. Call the typed verifier with `record.selectedOutputDescriptor()`, not `completedTransfer`, and validate returned evidence against that same authoritative descriptor plus the actual request's expectedAudio. Recheck operation, revision/generation, cancellation and all actual-release gates before accepting evidence.
4. Keep a proof-bound handoff from verified canonical material to a **dedicated typed commit**. Current `VerifiedArtifact(artifact, evidence)` carries no full selected-output/ADOPTED-generation/revision reservation; generic `PrivateMediaStore.commit` is deliberately ledger-fenced. A later typed verified/handoff object must retain or re-establish the exact generation mutation/publication exclusion after read close. Evidence equality or a copied proof is not that handoff.
5. Plan the read-owner → sealed verified handoff → commit reservation transition without an unprotected mutation gap. If the reader fully retires first, commit must acquire a fresh exact reservation and revalidate authoritative rows/file/hash; it cannot reuse stale success as authority. A reservation transfer is preferable but needs its own reviewed typed protocol and release semantics. This slice creates neither path.
6. Make the provisional media/index invisible until the short final cancel-versus-SUCCEEDED transaction proves exact receipt, selected output, normalization verification proof, current task/operation/revisions and release gates. Update Library publication validation accordingly; no generic ADOPTED legacy exception.
7. Provide typed committed inspection and normalized terminal cleanup/recovery that understand P moved into commit ownership and preserve R until allowed durable cleanup. Restart/resume does not reconstruct proof/conservation from only a workspace digest and does not normalize recovered WRITING.
8. Resolve existing blocking temporary IO and previously escaped legacy capability ordering, then perform real Android/device acceptance before changing admission flags.

Completion of this next inactive slice means the owner/capability contracts and their synthetic failure tests are reviewable and pass at their stated level. It does **not** mean the real binding exists, Coordinator uses it, normalized commit is valid, MOV is enabled, or the user's overall requirements are complete.
