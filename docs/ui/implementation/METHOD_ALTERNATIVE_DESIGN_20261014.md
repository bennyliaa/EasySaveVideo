# Explicit Direct-to-Stream reassessment: design for review

Date: 2026-10-14 UTC. Base development commit
`31327e58512a85e2c7bfe744bf231d31a00dac38`. Status: independently reviewed design; implementation in progress.

## Original scope and deliberately incomplete capability

PR-12 and section 7.1 require the Coordinator to reassess suitable failures and
keep methods visible/user-controlled; TR-07 permits fallback only for suitable
errors. Existing general failure guidance, return-to-browser, fresh inspection,
HLS selection and independent Record already exist. This slice connects those
existing actions to an explicit Coordinator-reviewed Direct-to-Stream attempt.
It does not add another navigation button, automatic retries or Record advice.
Record capability remains UNKNOWN: HTTP failure, unsupported media, DOM flags,
MediaProjection availability or an entitlement cannot prove capture suitability.
The full Stream-to-Record requirement remains open behind trusted capability and
native acceptance evidence.

## User-visible sequence

1. A failed Direct task with UNSUPPORTED_MEDIA or METHOD_UNSUPPORTED can use its
   existing return/reinspect action. Other errors retain existing general guidance;
   protected/authentication/TLS/storage/recovery/cancel/unknown errors do not gain
   a method alternative.
2. A short-lived process-local reassessment intent references only the exact
   failed task identity. The existing reminder explains that a fresh supported
   Stream source is required; no available/success claim is made.
3. The next explicit inspection binds that intent to one fresh page selection.
   No fresh HLS candidate means no proposal. Selecting Direct retires the intent
   and follows its ordinary path. New inspection replaces the prior association;
   closing/background/page invalidity retires it. An unrelated later inspection
   must never inherit it.
4. Existing Stream selection obtains its normal entitlement, fresh credential
   lease and exclusive preparation Claim. Only successful fresh HLS preparation
   may create a Coordinator-issued READY_TO_CONFIRM_ATTEMPT proposal. This means
   permission to attempt, not known downloadable/playable media.
5. Existing variant and destination selection is the explicit confirmation.
   Paired EN/ZH copy says this is a new Stream attempt and the failed task remains
   unchanged. There is no second redundant modal. Final submission rechecks the
   proposal and uses the same HLS exclusive Claim and original submit logic.

## Core contract

A new platform-neutral SaveMethodAlternatives contract contains immutable failed
identity (taskId/revision/state/method/error), review outcomes, opaque proposal and
confirmation outcomes. It carries no old URL, cookies, credential or capture
permission. Exact old identity must still match the current journal at proposal
creation and confirmation. Review of observed facts is pure; journal reads run on
existing worker/control paths, never main-thread UI callbacks.

The first positive policy is an explicit allowlist: FAILED DIRECT with
UNSUPPORTED_MEDIA or METHOD_UNSUPPORTED. Every other state/method/error fails
closed. NEEDS_FRESH_STREAM_SOURCE is not STREAM_AVAILABLE. No public recordable
boolean or Record proposal is introduced.

Proposal identity binds the current inspected selection generation, candidate and
source identity, prepared operationId, STREAM method, expiry and owning Coordinator.
The adapter must provide the actual successfully prepared source/operation, never
DOM claims as admission. Existing PageSaveSelection and AndroidHlsStreamPort remain
the authority for page/current source and preparation ownership. Proposal creation
also checks live Stream availability and recovery exclusion. A proposal is only a
method decision, not transferable resource authority.

At actual confirmation, consume one opaque proposal with one fixed new requestId
and idempotency key, both distinct from the old Direct task and key. The final
variant and destination admission creationId are fixed before confirmation; the
full request is immutable across the handoff. Verify source/candidate/operation identity, live deadline/cancel,
current failed row and fresh STREAM network request. Reuse admission.prepare on
the existing HLS Claim, then perform the proposal check and existing submit under
the Coordinator monitor. Never acquire admission from inside the Coordinator
monitor. This is a small shared internal submission helper, not a second writer.
A consumed proposal is rejected on every repeated confirmation, including an
identical request. No result replay/cache or additional retry protocol is added.
A different request, owner, operation or revoked proposal is also rejected.
A JOURNAL_UNAVAILABLE submission remains OUTCOME_UNKNOWN with the proposal already
consumed and its fixed new task ID retained for result verification. It cannot
authorize a new request ID or a second task. This uses a distinct UI/control result,
not the ordinary rejected/no-task message. No worker is dispatched. Since worker
handoff has definitely not occurred, the untransferred preparation/lease may be
retired with the existing actual-release fence; this says nothing about whether
the journal row exists. UI dismissal revokes proposal authority only, never cancels
or deletes a possibly persisted task. Existing startup recover performs the
QUEUED→INTERRUPTED→WAITING_USER/RECOVERY transition after reopening; no global
recover call, custom repair/replay protocol or automatic network retry is added.
Read failure remains visibly uncertain. Tests inject insert-write-then-throw and
read failures, then prove existing recovery preserves the new row and old Direct
without transferring media. Unknown persistence must not permanently occupy an
exclusive resource already proven unstarted and actually released. No confirmation method calls execute, reacquires credentials,
automatically retries or starts recording. Old task journal and runtime are not
mutated by reassessment, rejection or successful new task creation.

## Lifetime and Android connection

Use a small independently tested app selection owner, not a second scheduler.
The failure-navigation command installs at most one pending intent after accepted
navigation; it is bound to the next successful current inspection only. It can be
retired without touching an already accepted ordinary task. A prepared alternative
holds exact selection/preparation/proposal identities. Closing, new selection,
background, expiry, stale page and failed delivery revoke its UI intent/proposal.
A late callback cannot replace a newer owner. The final selected request is fixed
before handing it to control; accepted workers retain their existing independent
lifetime. A stale alternative fails visibly rather than silently submitting an
ordinary Stream task with its old context. Ordinary independent Stream has no
proposal and remains unchanged.

FeatureGate remains checked at Stream preparation, delivery, variant selection and
final control submission. Proposal authority cannot replace FeatureGate, the
fresh resource lease, destination admission, task capacity, foreground generation
or shared execution Claim. The failed Direct claim is never transferred. If a
Direct task is still active, the existing HLS exclusive gate continues to reject.
No DB schema, persistent task chain, dependency, permission or native policy change.

## Files and test acceptance

Core: new SaveMethodAlternatives.kt and production tests; SaveCoordinator.kt and
real Coordinator harness tests for assessment/proposal/confirmation.
App: small SaveAlternativeSelection owner/tests; AndroidSaveRuntime wiring;
existing SaveFailureGuidanceUi reminder and SaveTaskScreen HLS dialog; paired
save_method_alternative.xml resources; build source-input registration/contracts.
Do not change the broad general failure error classifier merely to fit this slice.

Required tests: all error/state/method rejection matrix; allowed new Stream attempt
preserves old task byte-for-byte; fresh source/operation/candidate/expiry binding;
foreign/old/revoked/double proposal; one fixed request and ambiguous journal write;
wrong/lost exclusive claim; global release uncertainty; admission lock order; no
Record proposal; app owner new inspection/page/background/dismiss/late callback;
explicit confirmation only; existing Direct/Stream/Record and concurrency tests.
Honest evidence distinguishes production JVM logic/fake ports from source wiring.
Run all 13 module tests, both Kotlin variants/lints, exact runtime/ABI/boundary gates.
No emulator, real network, real capture or native acceptance is implied.
