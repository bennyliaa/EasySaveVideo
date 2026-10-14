# Direct-only bounded concurrency: implementation design for review

Status: reviewed design retained as implementation rationale. Actual code/test
results are in `DIRECT_CONCURRENCY_20261014.md`. Base source/dev commit
`46186e79888c10b421531cb4b0f6a4ad99c9f098` (browser preferences frozen).
Original PR-20 requires a configurable save concurrency count but does not give a
numeric maximum. This slice selects a conservative engineering bound of 1/2,
default 1. Discovery provider concurrency is unrelated.

## Scope and admission

Only Direct saves may have two admitted executions. The third command receives
an explicit busy result; this slice does not add a deferred FIFO or automatically
start a previously rejected command. At most two accepted workers exist, including
those accepted but not yet entered. No pending queue extends source/Cookie leases.
Changing 2 to 1 does not stop existing tasks; it prevents another admission until
the current count is below the new limit. Recovery still requires an explicit
user command; no journal replay starts network or recording.

HLS preflight and its live selection/session, basic/segment/region recording
(including consent preparation and finalization), editing and preview retain
exclusive operation. MP4 verification remains one native lane. The setting says
“simultaneous Direct downloads”, not concurrent recording or all save methods.

## Ownership architecture

1. Add a platform-neutral bounded execution reservation owner. A Direct claim is
   an opaque object bound to task/operation identity; release is idempotent and
   only removes that exact claim. Exclusive claims and Direct claims use one
   synchronization domain. Count includes reserved, worker-accepted and running
   claims, not merely journal RUNNING rows. No global boolean is cleared by a
   Direct completion. Media-activity and all other-media idle checks aggregate
   every claim. Claims are acquired before submitting a durable new task or
   changing a resumed WAITING_USER task, and precisely unwound on rejection.
   Runtime and Coordinator share the same admission instance. Successful submit/
   resume attaches the claim exactly once to the actual Runtime object; execute
   checks runtime.claim identity and live ownership, not strings alone. A claim
   accepted at limit 2 remains valid after a change to 1 even if its worker has
   not entered yet. Core finally removes only its executing marker; the external
   runtime outer completion owns claim release. Legacy execute(taskId) obtains
   and releases its own claim for backward-compatible callers. An idempotent
   Accepted(existing=true) releases the new claim/lease and never attaches it to
   the old Runtime or dispatches another worker. Exclusive preparation claims may
   bind their task/operation once only; their opaque identity never changes.
2. The Android runtime's legacy exclusive paths must participate in the same
   admission fence. Direct task finalizers can never clear an exclusive owner.
   HLS session cleanup remains bound to the HLS path; remove the unconditional
   stream.discardIdle from Direct finalizers. Prepared HLS selection continues
   to block Direct even when its preflight worker has returned. Existing native
   owner lifecycle is retained, not inferred from Future cancellation.
3. Pin a Direct transfer owner for the duration of one coordinator execution,
   including retries. Use a fixed pool of two SystemHttpsDirectTransfer instances;
   each keeps its own active CAS and cleanupUncertain. Binding uses the task's
   Runtime/operation claim, never ThreadLocal, current activeTaskId or URL. Do not
   recreate a poisoned instance. Aggregate uncertain release blocks new admission
   and preserves existing recovery-required behavior. Finalized local recovery
   need not acquire a network transport.
4. SaveCoordinator replaces its single activeTaskId with identity-checked active
   executions: each owns a valid admitted Direct claim (temporarily above a newly
   lowered limit is allowed), or exactly one non-Direct. The
   default remains 1 for existing callers/tests. Duplicate execute of the same
   Runtime is rejected without touching another execution. Existing observer,
   cancellation, progress, checkpoint, retry budget, commitId and publication CAS
   remain per-task and under their existing monitor. No network/native work runs
   under that monitor. Runtime admission and coordinator admission must agree so
   an accepted worker cannot silently return because another task exists. New
   Direct worker submission uses a one-shot accepted/entered/suppressed handoff.
   A duplicate Runnable is suppressed. On an ambiguous submit exception, entry
   winning retains worker ownership; suppression winning permits only release
   and deferral, with no Resume repair authority. A duplicate execute rejection
   cannot release the running owner's claim. Fixed-pool locks cover binding only,
   never transfer work; pool recovery checks scan all fixed instances, including
   idle ones. Retry's copied OperationContext is not a fresh owner identity.
5. Wrap the unchanged AndroidLocalMp4Verifier in a cancellable, deadline-bounded
   SerialArtifactVerifier. Only one delegate.verify call enters; waiting tasks do
   not enqueue native Futures. Recheck original cancellation/deadline and native
   recovery state on acquisition. Application-lock release is not proof of native
   release; retain requiresProcessRecovery/isDraining checks. Temporary abandoned
   native draining may clear only on the original actual-finally release evidence;
   do not turn it into a permanent restart requirement. A completed claim/batch
   remains pending retirement until that evidence clears, then may retire. Fixed
   transport cleanupUncertain and explicit requiresRestart remain permanent poison. Do not increase the
   native executor thread count or queue size.
6. Direct reservations must also prevent editor/preview/thumbnail native claims.
   The implementation must use the existing OrdinaryMediaNativeResources owner
   as one exact batch claim from first Direct admission through final confirmed
   release, or demonstrate an equivalent existing atomic owner fence in review.
   It may ignore only its own batch claim when checking occupied state, never all
   native occupied state merely because a download exists. A poisoned native gate
   remains globally blocking. Batch release requires the final operation's
   actual transport/native release confirmation; a worker finally alone is not
   sufficient. Poison retains the batch owner. HLS/recording must not double-claim
   their own gates.

## Settings and persistence

Add SaveSettings.maxConcurrentDirectTasks, default 1, valid only 1 or 2. Missing,
wrong-type or out-of-range persisted values fall back to 1, never clamp an invalid
large number upward into enabling 2. No schema/journal migration is needed.

Concurrency edits are disabled until successful settings load. A wrong-type concurrency key is caught locally and falls back to
1 without failing the whole settings load. Wi-Fi edits retain
the existing separately confirmed behavior. Startup merges loaded concurrency
independently of an early Wi-Fi edit; a concurrency edit never confirms unknown
Wi-Fi=false or feeds that default to the network policy. Runtime's application
owner must retain a newly accepted setting across Activity recreation rather
than let stale disk loading roll it back. Save failure uses existing visible
warnings and does not claim persistence. Other settings edits preserve both save
fields. UI uses field-level guarded intents and existing central Settings layout.

Copy explains: 1/2 applies only to Direct; stream processing, recording and editing
are exclusive; media verification is sequential; lowering the count does not
cancel already admitted work. EN/ZH and accessible minimum-size controls required.

## Budget and safety invariants

OperationContext.ResourceBudget.maxConcurrentWork remains 1 per operation. Each
Direct retains maxArtifactBytes 2 GiB and cumulative transfer budget 4 GiB; two
active Direct operations therefore have a conservative combined bound of 4 GiB
active artifact payload and 8 GiB cumulative charged allowance, not exact wire
bytes or total disk use. Existing library/WAITING_USER artifacts, DB/WAL and
other files are not included in the payload sum.

To admit a second Direct, the same admission fence must read a reliable current
storage-available snapshot and require at least the sum of both operations' full
maxArtifactBytes (4 GiB). Do not subtract already-written bytes; this deliberately
retains a conservative worst-case bound. The storage port must preserve the
existing journal reserve. Negative/overflowed/unknown/unreadable/insufficient space refuses the second
claim, without creating a journal row or acquiring credentials. The disk probe
and atomic claim run on the control worker, never synchronously on the UI thread.
Lock ordering is admission then Coordinator then storage where nesting is needed;
Coordinator/storage locked callbacks must not call back into admission. No network,
native work or long hashing is executed while the admission lock is held. Where
storage may wait on an existing long fileLock operation, use a versioned two-phase
free snapshot and admission recheck rather than blocking admission/foreground
callbacks; revalidate capacity/owner state and reject ambiguity. Every later
second-slot admission repeats this check. Existing availableBytes already deducts
the 1 MiB journal reserve; do not describe that deducted reserve as new free space. First-task behavior
remains unchanged. No extra pending task is admitted on refusal. This is admission
headroom, not a disk reservation against external processes/export writes; original
per-write availableBytes/error/checkpoint behavior remains mandatory. No new quota
library or unlocked check-then-write guarantee is claimed. Tests cover two competing
second-admission attempts, unknown lengths, low space and snapshot failure.

Buffer and worker/transport counts remain bounded. All TLS/origin/redirect/request-
context policies and exact dependency gates remain unchanged.

Cookie/UA lease TTL stays 60 seconds. Admission or worker delay does not renew it,
reacquire credentials or repeat a user authorization. SaveResumeHandoff's accepted
versus unaccepted repair boundary is preserved: acceptance before entry is still
acceptance, including an ambiguous worker-submit exception. Background/cancel
memory fences remain immediate. A/B cancellation, error, progress, bytes and
final receipts must not cross. Cleanup uncertainty cannot free capacity or create
replacement native/transport lanes.

## Planned file scope

Core/model: Settings.kt; SavePorts.kt; SaveCoordinator.kt; new bounded reservation,
Direct transfer-owner and serial verifier implementations in save module.
Android: AndroidSaveRuntime.kt; new fixed Direct owner pool; a narrowly scoped save
preference owner if needed for application-lifetime merging; SaveResumeHandoff and
SaveTaskCommandGate only as required to carry exact reservations. Existing media
activity producer may gain active counts but retains its external policy.
A read-only storage-free admission port may be added to AndroidSaveStorage/its
existing private commit protocol wrapper; no write protocol is weakened.
Settings: AndroidStores.kt, BrowserPersistence.kt, SettingsNavigation.kt,
EsvSettingsScreen.kt, MainActivity.kt, paired save_concurrency.xml.
Tests: core reservation/coordinator/transport/verifier/settings unit tests plus
runtime integration adapters and honest source contracts. Existing native gates,
SystemHttpsDirectTransfer internals, AndroidLocalMp4Verifier nativeLane, HLS engine,
journal encoding, final artifact protocol, dependencies and SkinKit stay unchanged.
Any necessary expansion is reviewed explicitly before implementation.

## Required evidence before source completion

- Latch/barrier proof that two distinct Direct ports execute simultaneously at 2,
  never at 1; third command explicitly rejected; exclusive operations cannot enter.
- A cancellation/retry/failure/checkpoint/budget change affects only A; B continues
  to its own validated receipt for ordinary failures. A global release-unconfirmed
  poison is different: it blocks B publication and requires existing recovery
  handling, not an assertion that B must succeed. Duplicate execute/release and stale token cannot
  remove a later claim. Lowering count does not preempt or over-admit.
- Fixed pool retains poison; no replacement instance; URL equality does not merge
  task ownership. Each request-context lease and redirect chain stays isolated.
- Verifier delegate max concurrency exactly 1; waiting cancel/deadline never enters;
  delayed native release is still blocked and late evidence is fenced.
- New/resume admission rejection and accepted-before-entry paths keep exact prior
  recovery authority. Task A completion leaves B blocking native work. Direct
  completion never discards another method's prepared session.
- Loaded/missing/invalid settings, early Wi-Fi edits, Activity recreation, write
  failure, background/route retirement and other-category edits all covered.
- Full existing 13-module fresh regression, Debug/Release compilation, both lint,
  runtime/license/core/ABI boundaries, independent review and private dev backup.
- No claims of actual Android/SAF/network throughput/device resource acceptance.

## Review stopping condition

Independent reviews accepted the design subject to the clarifications recorded
above (runtime-attached claims, accepted-worker handoff, conservative second-slot
disk admission, lock ordering, native poison and field-specific settings).
Implementation must preserve these conditions and undergo a separate code review.

Do not implement until independent design review confirms exact owner/release,
exclusive-resource and settings-merge semantics. If a fundamental native ownership
assumption cannot be supported, report that concrete blocker; do not substitute a
concurrency number over a single execution lane or weaken existing safety gates.
