# Explicit Direct-to-Stream method reassessment

Date: 2026-10-14 UTC. Base development commit
`31327e58512a85e2c7bfe744bf231d31a00dac38`.
Scope: original PR-12 / TR-07 and product section 7.1, suitable-failure method
reassessment. This is the supported Direct-to-Stream suggestion/confirmation
contract, not completion of the entire Direct→Stream→Record requirement.

## Actual behavior

The existing failed-task return/reinspect action now carries a short-lived reviewed
intent only for FAILED Direct + UNSUPPORTED_MEDIA or METHOD_UNSUPPORTED. All other
errors/methods/states retain their existing general guidance. No new navigation or
Record button is added. The next explicit inspection consumes the intent, then
binds it to one exact current selection. A missing, expired or invalid fresh
selection cannot become a proposal or silently turn into an ordinary save.

The existing HLS preparation, Stream entitlement checks and exclusive Claim remain
in charge of actual resource access. Only successful preparation is passed to the
Coordinator for an opaque, process-local READY_TO_CONFIRM_ATTEMPT proposal. It binds
the original exact failed identity, inspection generation, candidate/source/lease,
operation/cancellation/deadline and bounded expiry. Preparing a source does not
prove final download success or playable output.

The existing variant and destination selection is the explicit confirmation. The
final immutable request uses that destination admission's new creationId/key and
the actual stream.select result. Both new ID and key must differ from both the old
Direct task ID and its key. Coordinator checks the unchanged old row and fresh
request before reusing the original submit insertion body and original HLS Claim.
The first confirmation consumes the proposal; all repeats return ALREADY_USED.
There is no task-chain schema, proposal registry, result cache, replay or retry ID.
The old Direct record is never changed by this flow.

Direct choice remains ordinary Direct. Independent Stream has no proposal and
retains its original path. Independent Record has no new method authority. No
HTTP error, DOM flag, entitlement or platform capture availability becomes a
recordability claim; Stream-to-Record remains UNKNOWN and unimplemented here.

## Uncertain persistence and resource retirement

JOURNAL_UNAVAILABLE is OUTCOME_UNKNOWN, not proof that task creation failed. The
fixed new task ID is retained for result verification; both Browser and Tasks show
an explicit uncertain-result message telling the user not to resubmit and to
reopen for existing task recovery. Failed record reads preserve uncertainty.
No worker is dispatched on this branch. Dismissing the message only changes UI;
it never cancels/deletes a possibly persisted task or retries submission.

The untransferred HLS preparation and credential lease can be retired because no
worker handoff occurred, with the existing actual-release/exact-claim fence. This
is not a claim that no row exists. Existing startup recovery converts a persisted
unstarted row into WAITING_USER/RECOVERY without starting network work. No new
repair protocol or global recover call was introduced. Unknown persistence does
not permanently occupy an exclusive resource proven unstarted and released.

## Lifetime and review corrections

SaveAlternativeSelection is a pure main-thread owner for pending intent, one-shot
inspection ticket and exact selection association. It cannot command tasks, own
credentials or prove capability. Runtime retains existing preparationGeneration,
foreground generation and resource ownership. Background, dismiss, new selection,
expiry and invalid delivery retire only current UI/proposal authority; a late old
callback cannot clear a new owner. Once the final fixed selection is handed off,
later dialog callbacks cannot cancel an accepted worker.

Independent design review required distinct new IDs and a genuine unknown-result
branch. Both were implemented. Code review found a real boundary case: inspection
started just before intent expiry could finish after expiry, fail association and
incorrectly expose an ordinary Stream selection. The production delivery helper
now explicitly rejects any carried ticket that cannot bind; the Runtime does not
assign that selection. A separate pre-inspection expiry guard also prevents an
expired pending intent from silently becoming an ordinary inspection. Production
helper tests cover expired and cleared tickets, ordinary/current delivery, duplicate
delivery and a late old result preserving a new owner.

## Validation status

Core focused: 204 tests passed, including 25 new policy/real-Coordinator harness
tests. They cover all error/state/method policy combinations; fresh resource/context
binding; foreign/revoked/expired/duplicate proposal; old/new ID aliases; stale
failed row; wrong/lost Claim; global recovery; admission-monitor freedom; unchanged
ordinary submission; write-then-throw/read failure uncertainty and existing startup
recovery without media transfer. Original save tests were not changed. The shared
submit body extraction was mechanically compared with the base and preserves its
previous body/order after return-label normalization.

App initial focused: 71 passed. After review correction, final focused: 76 PASS,
including the 30 production selection/delivery tests and eight source/resource
contracts. Final full regression freshly executed all 13 module test tasks:
3,967 registered, 3,965 PASS and two pre-existing corpus SKIP, zero failures/errors;
app 2,592 PASS. Debug and Release Kotlin compilation, both lints and runtime
inventory passed. Lint matches the existing baseline: Debug 60 warnings/8 hints,
Release 61 warnings/8 hints, zero errors. Exact 90-artifact runtime admission,
source boundaries, original license bytes and seven-getter ABI pass. Final build
exit 0, 3m 4s, 102 actionable tasks. Sanitized summaries/manifests are in adjacent
`method-alternative-20261014/`.
JVM logic/fake ports and source/resource contracts are not Android execution.
No APK/device install, real HLS network, real credential use, MediaProjection,
physical/native media or device UI acceptance was performed. Frozen prior APKs,
SDK/Kotlin versions, dependencies, SkinKit, permissions and disabled gates stay
unchanged.

## Remaining original work

PR-12 Stream-to-Record needs trusted current capture capability evidence plus its
existing explicit fresh consent/entitlement/destination flow; this slice cannot
claim that capability. Native acceptance also remains open. Continue another
unblocked original UI requirement after this source checkpoint, without treating
backup or packaging as full product completion.
