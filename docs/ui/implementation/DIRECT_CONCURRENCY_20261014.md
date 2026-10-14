# Direct-only bounded concurrency implementation

Date: 2026-10-14 UTC. Base development commit:
`46186e79888c10b421531cb4b0f6a4ad99c9f098`.
Original PR-20 requires a save concurrency setting without specifying its maximum.
The independently reviewed design is `DIRECT_CONCURRENCY_DESIGN_20261014.md`.
This slice uses an explicit conservative engineering bound: Direct only, 1 or 2,
default 1. It does not claim the original document specified 2 or that all save
methods now run concurrently.

## Actual execution and settings

The app's existing saving settings now persist the maximum simultaneous Direct
operations. A second operation uses a distinct fixed SystemHttpsDirectTransfer
instance through the real coordinator. The app worker has two threads, but shared
admission—not thread count—bounds all reserved, accepted and running operations.
The third request gets an explicit busy result. There is no deferred FIFO, automatic
retry of rejected commands, new source selection, credential renewal or network
replay after restart. Lowering 2 to 1 preserves already-admitted operations, even
if a worker has not entered yet; it only limits future admission.

HLS preflight retains an exclusive claim through its selected session and hands
that same claim to its task. Recording preparation/consent, recording, segment/
region work, editing and preview remain exclusive. Direct completion never
clears a different task's exclusive claim or discards an HLS session. Exact
AtomicReference retirement prevents an old exclusive callback from clearing a
new owner. HLS, Direct and Resume admission check foreground generations so an
old queued command cannot revive after background/foreground return.

The five previously existing settings groups and original frozen APK remain
separate source checkpoints. No APK build/install or device operation was done.
No dependency, SDK/Kotlin version, SkinKit asset, billing/Vault gate, permission
or native executor configuration changed.

## Identity, execution and resource evidence

Runtime and Coordinator share SaveExecutionAdmission. Opaque claims attach once
to the actual Coordinator Runtime, task and operation. External claims remain
owned by the outer accepted worker or a definitely unaccepted compensation path.
Legacy execute callers retain a compatible self-managed claim. The coordinator's
per-task progress/checkpoint/retry-byte accounting, cancellation, commit identity
and publication CAS remain independent. Idempotent existing submissions do not
inherit another claim or dispatch another worker.

FixedDirectTransferPool owns exactly two permanent transport instances. A Runtime
keeps the same transport across its retry loop, independent of the copied retry
OperationContext. Binding locks never surround transfer work. Every permanent
transport cleanupUncertain flag remains globally blocking; no replacement instance
is created to restore capacity. Core also rechecks global recovery at attempt,
transfer and publication boundaries, including cancellation during retry delay.

SerialArtifactVerifier permits only one delegate call. Waiting uses the original
absolute deadline and cancellation token and does not submit another native
Future. AndroidLocalMp4Verifier's native executor, queue and NativeVerificationLease
are unchanged. Temporary native draining is not converted into a permanent restart
requirement: a completed claim and the exact Direct batch native owner stay held
until the original native finally confirms actual release. SaveExecutionRetirement
then retires that exact claim without starting another task. Permanent transport
poison or an explicit native requiresRestart remains blocking. Ordinary A failure/
cancellation is isolated from B; unconfirmed global release intentionally blocks
B publication and preserves existing recovery handling.

New-task dispatch uses a one-shot accepted/entered/suppressed handoff. Duplicate
Runnable entry is suppressed. If a submission exception is outcome-ambiguous,
entry winning keeps worker ownership; suppression winning permits only release
and deferral. It never grants Resume journal-repair authority. The existing
SaveResumeHandoff acceptance/compensation boundary remains in use and its original
production tests remain intact.

## Conservative disk admission and preference ownership

A second Direct requires a reliable storage-available snapshot at least equal
to both operations' full artifact allowance: 4 GiB. Already-written bytes are not
subtracted. The existing availableBytes port already deducts a 1 MiB journal
reserve. Negative, unreadable, overflowed or insufficient values refuse the second
claim before a new journal row or credential lease. The probe runs on the control
worker outside the admission monitor, followed by a version/capacity recheck; UI
callbacks never wait for storage hashing under that monitor. Core mutations also
run outside the admission monitor with preparing/executing markers protecting
claim lifetime.

This is conservative admission headroom, not a guarantee against external disk
writers. Existing per-write available-space/error/checkpoint checks stay in force.
The 4 GiB bound is only active artifact payload, not existing library, waiting
artifacts or DB/WAL. Each operation retains a 4 GiB cumulative charged transfer
allowance; two operations have an 8 GiB allowance sum, not an exact wire-byte
promise. OperationContext.maxConcurrentWork remains 1 per operation.

SaveSettingsOwner merges Wi-Fi and concurrency independently across early edits
and Activity recreation. Concurrency edits require successfully loaded active
settings, never confirm an unknown Wi-Fi preference, and never change the network
policy. Missing/invalid/wrong-type persisted concurrency falls back locally to 1.
Write failures use the existing visible warning; the UI does not claim disk
persistence succeeded merely because the live owner changed. Paired EN/ZH copy
explains Direct-only execution, exclusive other methods, sequential verification
and non-preemptive lowering.

## Tests and independent review

Core focused: 179 tests in 10 suites passed, including 59 new production admission,
coordinator, fixed-pool and serial-verifier tests. Deterministic latches prove two
distinct transfer ports overlap, cap 1 blocks a second, the third is refused,
already-admitted work survives lowering, old/duplicate claims cannot release a
new owner, per-task cancellation/retry/budget/receipt behavior stays isolated,
transport poison is retained, waiting verifier calls are cancellable and actual
native-release evidence distinguishes transient draining from permanent poison.
All original save tests remain byte-identical, including SaveCoordinator 31/31
and UnadmittedResumeRecovery 25/25. Independent Core review found and verified
fixes for retry/global recovery, accepted-before-entry recovery, legacy exceptional
claim/transport references and external native gate publication checks.

App focused final: 162 tests in 17 suites passed. New production tests cover the
worker handoff (8), evidence-driven claim retirement (9), settings owner/edits/
parsing and integration contracts (24), plus runtime wiring contracts (8). The
retirement test explicitly interleaves old completion with a newly acquired
exclusive owner. New core-model validation also passes. Runtime source contracts
inspect Android wiring, not actual Android execution. The initial app run compiled
successfully but four old Resume source contracts asserted the former single-lane
implementation/signatures; they were updated to exact-claim/two-stage wiring while
retaining immediate Cancel, accepted-worker/no-repair and cleanup constraints.
No production workaround was made to satisfy those obsolete assertions.

Independent runtime review identified two integration issues before acceptance:
HLS selected-to-control work needed the same foreground-generation fence, and
exclusive retirement needed atomic compareAndSet. Both were corrected and their
final tests/source inspected. Settings review confirmed no null-result write or
implicit Wi-Fi confirmation. Android runtime review passed the bounded scope.

Final full regression: all 13 module test tasks freshly executed, 3,904 registered,
3,902 PASS and two pre-existing corpus SKIP, zero failures/errors; app 2,554 PASS.
Debug and Release Kotlin compilation, dual lint and runtime inventory succeeded.
Lint remains at its prior baseline: Debug 60 warnings/8 hints, Release 61 warnings/
8 hints, zero errors. Exact 90-artifact runtime admission, source boundaries,
original license bytes and seven-getter ABI checks pass. Sanitized evidence is in
adjacent `direct-concurrency-20261014/`.

The first full run found six more outdated source assertions across four existing
contract classes: authentication binding now occurs only inside !existing, all-task
busy checks use shared admission, foreground retirement also increments execution
generation, and HLS failure releases its exact claim. Two additional typed busy
emissions account for the notice-count change. Only those contracts were updated;
no production semantics were weakened to make them pass. The final full run
completed with exit code 0 (3m 1s, 102 actionable tasks).
Android WebView/SAF, actual network throughput, real disk pressure, real native
capture/verification concurrency, emulator and phone acceptance remain NOT_RUN.
JVM/model/fake-port and source-contract results are not device acceptance.

## Next original requirement

Reconcile and design the remaining PR-12 Coordinator method-suggestion contract.
Existing reinspection/Direct-or-Stream selection and independent explicit Record
entry already work; another jump-to-record button would duplicate them. A failed
HTTP request or DOM mediaKeys=false is not capture capability evidence. Retain
UNKNOWN for unsupported capture capability, fresh resource admission and explicit
system consent; no automatic three-method loop or new saving authorization.
