# Vault pure state and recovery contract

Date: 2026-10-16 UTC. Initial scope: eight new files, platform-neutral metadata and deterministic algorithms only. The later lifecycle connection is documented in `VAULT_LIFECYCLE_POLICY_CONTRACT.md`.

## Status and limits

This slice implements an isolated event/state reducer and transaction-reconciliation planner. It does not implement usable Vault storage or authentication. Production Vault remains disabled. No existing source, module dependency, feature gate, UI, manifest, database, probe registration, or runtime wiring is changed.

There is no I/O, system clock, random source, password/PIN/recovery-code input, cryptography, key generation, credential persistence, real unlock handle, platform authentication, actual retry store, plaintext reader, deletion, external publication, or effect executor. Effects and recovery steps are descriptive data. Even `SESSION_OPEN`, `AVAILABLE_IN_CURRENT_SESSION`, and `READY` cannot authorize decryption. A future reviewed adapter must hold actual scoped authority, enforce execution preconditions, and arbitrate revocation and publication atomically. This slice does not access or revive any older Vault candidate or its review/stress/probe actions.

The four auto-lock durations are exactly 0, 60,000, 300,000, and 900,000 milliseconds. The user confirmed the route/background trigger on 2026-10-16: leaving the Vault route or entering background starts a window, and both conditions must return before expiry to cancel it. `LifecycleObserved` connects this pure policy inside the existing reducer, as specified in `VAULT_LIFECYCLE_POLICY_CONTRACT.md`. Before that explicit lifecycle activation, `AutoLockWindowStarted` / `AutoLockWindowCancelled` and background/foreground retain their original low-level semantics for compatibility. No production adapter is wired.

## Requirements traced

- `docs/requirement and design/01_Product_Requirements.md`, PR-18: authentication, retry restraint, four durations, restart lock, recent-task protection, free Vault, and no universal recovery promise.
- `04_Technical_Requirements.md`, TR-11/12: private directories/internal identifiers, journal and durability, integrity checking, actual scoped authentication, plaintext cleanup, distinct recovery causes, and independence from Store/Provider.
- `02_Architecture_and_Data_Model.md`: scoped unlock handles belong to the future real capability layer; prepare/execute/verify/commit/cleanup, commit identity, private index, and non-destructive recovery.
- `05_Test_and_Acceptance_Strategy.md`: interruption, background/restart, key invalidation, incomplete commit and cleanup.
- `03_Development_Milestones_update1.md`: current MediaLibrary/Vault milestone is M5; old M6 wording does not change milestone ordering.
- Existing `RecoveryDisposition` is reused. Existing `MediaReadRef` identity dimensions are preserved as pure `VaultSourceRef(itemId, revision, representationGeneration)`, without a core-to-library dependency.
- User decision: password setup must offer an optional offline recovery code for the user to keep. This slice records only the choice and abstract material availability; it never creates a code.

## Session contract

`VaultSessionReducer.start(processInstance, now, ledgerLoad)` creates a new locked, covered state with required cleanup. It never restores an open session. `reduce(state, event, now)` returns a new snapshot, immutable copied effect list, and bounded transition outcome. The owner must serialize calls or commit snapshots with CAS; stale ordinary intents must be retried against current state, while protection events must be applied to the latest snapshot.

The only time source is an injected same-process monotonic `VaultTick`. Every valid call advances `lastObservedTick`, including ignored callbacks. Negative, foreign-process, or backward time does not replace the prior observation; it fails closed. Deadline comparison uses `now >= deadline`. Addition and correlation-counter exhaustion fail closed without wrapping. Every event checks existing deadlines first, so a late timer cannot grant an extra frame. `permitsPresentation(now, authEpoch, presentationEpoch)` also checks the supplied current tick and lock deadline. It is only metadata eligibility, never a substitute for actual session authority or owner-side atomic publication fencing.

Protection masks private presentation, stops private readers/playback, clears private memory, and emits a new exact cleanup request. A full lock advances authentication and presentation epochs; temporary cover/background protection may preserve the authentication epoch while revoking presentation. Background sets `foreground=false`; foreground returns covered and never resumes playback. Cleanup acknowledgement requires the complete current binding: cleanupId, process instance, authentication epoch, presentation epoch, and deadline. Only a pending round accepts its first terminal result. Old success/failure and opposite duplicate terminals cannot change a new or already-finished round. A fresh retry requires a new cleanup identity. Cleanup timeout/failure keeps content unavailable.

### Retry reservation and reset

`VaultRetryPolicy` is injected: a finite, nonnegative, nondecreasing bounded delay ladder plus authentication, persistence, and cleanup timeouts. Test values are fixtures, not approved production security parameters. No entitlement or Store state is an input.

Before external authentication can begin, a reservation proposes `reservedAttemptCount + 1`, a capped tier, a unique outstanding reservation ID, and ledger revision +1. The confirmed ledger stays unchanged until the exact acknowledgement arrives. Duplicate unlock requests cannot start a second attempt. Each accepted attempt consumes its reservation even if rejected, cancelled, or platform-throttled. Platform delay and existing cooldown are retained whenever stricter than the local delay; lock/background events cannot shorten them.

On current successful authentication the reducer proposes a separate reset. Until its exact CAS acknowledgement arrives, the old confirmed count/tier/revision and pending write remain, presentation stays covered, and new attempts are blocked. Failure, timeout, or unknown write outcome preserves the pending write for trusted reconciliation. A matching late acknowledgement may confirm the ledger after locking, but cannot reopen an invalidated attempt/epoch. A trusted restart with reserved count >0 waits the full local ladder delay again from the new process tick. Missing/corrupt ledger that is not trusted first use is unavailable rather than zeroed.

`VaultRetryWrite.matchesStoredRevision` is the pure CAS precondition: stored revision must equal expected revision, and proposed revision must be expected+1 without overflow. The future store must atomically compare-and-replace, perform zero writes on conflict, and only then acknowledge. Ignoring a stale acknowledgement is insufficient if the underlying store unconditionally overwrote a newer reservation. The tests demonstrate this condition with an in-memory fixture; there is no real persistence/durability/rollback-resistance acceptance here.

Authentication outcomes distinguish rejected credentials, cancellation, temporary platform limits, key invalidation, and timeout. Recovery reasons also distinguish forgotten password, biometric change, app removal, and device change. `OPTED_IN` alone is insufficient for an offline recovery route: material must also be `CONFIRMED_AVAILABLE`. Routes express what a user could try, never guarantee recovery.

## Recovery contract

`VaultRecoveryPlanner.planRecovery(snapshot)` returns the existing recovery disposition and ordered descriptive steps. Inputs contain internal identifiers and classified observations, not paths/URIs, names, content, credentials, recovery codes, or actual capabilities. The owner supplying observations remains a trust boundary; a DTO cannot establish physical durability, cryptographic integrity, actual current authentication, or deletion.

The model binds operation, commit, and target media identity. Primary ciphertext and encrypted metadata each have an exact object identity, independent current observation revision, durability observation, and matching Vault-format verification evidence. An ordinary media commit receipt and journal phases `VERIFIED`/`COMPLETE` do not establish either object's Vault integrity. Commit/target or source item/revision/generation conflicts isolate the operation. Invalid schema, path-like/oversized IDs, invalid roles, or malformed input return a bounded rejection with no target action; illegal operation/commit strings are not echoed in the plan.

Only proven operation-owned, unreferenced plaintext temps distinct from source, primary ciphertext, and metadata can receive cleanup steps, and these precede ordinary waits. Unknown/foreign/referenced/aliased items are never cleanup targets. Temporary cleanup still needs an external executor to recheck ownership and references; no directory-wide cleanup is described.

Known missing primary/metadata objects behind a private pointer/index or `COMPLETE` journal are isolated even while locked or recovering an export. Invalid integrity and identity conflicts never become authentication-only waits. A current complete durable primary plus metadata, matching current session metadata, exact observations, and completed plaintext cleanup are required before private pointer/index reconciliation. An outstanding reconciliation step never also deletes the ordinary source in the same plan. Source cleanup requires pointer and index already privately durable, exact source identity, explicit move authority, zero other references, and current target evidence. A remaining ordinary copy is not reported as encrypted-only. `READY` requires consistent observations, not a journal label alone.

Recovery authorization metadata binds current and operation process/epoch. Future execution must recheck that binding and actual scoped authority, plus journal, pointer, index, primary, metadata, ownership, and reference observations. The steps include the expected journal/observation/pointer/index revisions independently of identity. These preconditions do not establish a persistent authorization grant.

Every step has structured semantic identity `(operationId, commitId, kind, exactTargetIdentity)`. Targets include object role/ID/identity-generation, complete source identity, or an explicit operation target. The step ID excludes journal phase/revision and observation revision. Replanning the same target as revisions advance keeps the same ID; different temp IDs or generations differ. No delimiter-concatenated IDs, second target media allocation, or implicit duplicate publication exists.

Export interruption only emits needs-user/resume-wait metadata, never decryption or republishing. A completed observed external copy is a reported fact and cannot be revoked by Vault. Internal ciphertext is preserved. Stop/deadline/budget inputs defer bounded work; they do not reset budget or start scans. The planner does not register with `RecoveryManager`.

## Verification and matrix

Focused tests use only in-memory IDs, enums, classified observations, and virtual monotonic ticks. No passwords, mock keys, recovery codes, encrypted files, platform accounts, device tests, real journal/ledger stores, or historical candidate code are involved. The build owner alone may run the central Gradle build; this implementation was checked independently using cached Kotlin 2.2.10/JVM17 and JUnit 4.13.2 without new dependencies.

The three suites test requirement scenarios rather than aiming for one test per matrix row:

| Design matrix | Executable coverage |
|---|---|
| S01, S14, S17 | Locked startup, cleanup gate, trusted-first-use/restored/unknown/corrupt ledger, reserve-before-auth, unknown writes |
| S02–S08, S11–S12 | Four exact durations, deadline−1/deadline, explicit windows, no implicit background anchor/foreground cancellation, duplicate windows, original policy anchor, manual/device lock |
| S09–S10, S24 | No wall-clock API; monotonic watermark through ignored events, backward/foreign/negative ticks, overflow and counter failure |
| S13, S15–S18 | Single attempt, distinct results, durable-reservation metadata, repeated failure/cancel tier cap, restart delay, stricter platform cooldown, pending reset, stale CAS zero-write fixture |
| S19–S23 | Stale process/attempt/epoch callbacks, both lock/success orders, revoked presentation, two cleanup rounds/terminal ordering, bounded missing callbacks |
| S25–S26 | Choice × material recovery matrix; no paid/store/provider inputs or dependencies |
| R01–R04 | Exact source/commit/target identity, finite malformed rejection, ordinary receipt rejection, primary and metadata evidence, incomplete/invalid candidates |
| R05–R08 | Existing-target reconciliation, separate source cleanup, current authorization, reference/explicit-move checks |
| R09–R12 | Temp ownership/reference/alias rejection, cleanup order, key/stale-session handling, missing target isolated while locked/exporting |
| R13–R17 | Stable IDs across phase/revisions, distinct temps/generations, stale session, no auto-export, external-copy fact, budget/stop and cleanup failure |
| I01–I04 | Defensive copies, 512 three-event sequence combinations, deterministic plans, bounded/redacted outputs and static dependency/scope audit |

Initial focused execution: 39 JUnit tests passed. After independent review's missing-object classification correction, one additional parameterized-loop regression was added. Final focused execution passed all 40 JUnit tests with compile exit 0 and test exit 0. Review fixes also explicitly cover preserving a stricter platform cooldown and malformed IDs producing no target steps.

These results accept only pure deterministic contract behavior. They do not complete T-L03/T-L05/T-L06/T-L07/FI-22/FI-23 file/device/crypto/recent-task/crash-recovery acceptance, prove secure plaintext erasure, establish persistent retry anti-rollback, or make Vault production-ready. The pure trigger policy is now connected as documented separately; its real Android lifecycle adapter, real credentials/recovery material lifecycle, platform adapters, crypto format and integrity, actual publication fencing, and durable storage remain separate reviewed work.
