# M7-B: offline signed repository security policy

Milestone order follows `docs/reference/03_Development_Milestones_update1.md` M7.
This extends the M7-A single compiled Commons parameter package; it is not full M7.
Original requirements: Architecture section 6, Technical Requirements section 3.4,
Decision Log D-18/E-14 and fault matrix FI-19/20/21. No copied external code or new
library is used. Exact74 runtime admission, API31 minimum, SkinKit, source-free
browser, privacy/error logging and debug-only fake entitlement remain unchanged.

## Implemented slice

- Separate ESVS signed policy domain and independently injected roots, empty by default.
- Monotonic policyVersion independent of packageVersion high-water.
- Nondecreasing minimumSafePackageVersion and cumulative revoked package versions/key IDs.
- Policy enable/disable, expiration, and a single exact rollback target version plus SHA-256
  of the complete signed package. It cannot authorize bundled version zero.
- Current candidate, health promotion, LKG rejection and explicit rollback share a security
  gate. An enabled package behind package high-water needs the latest exact signed grant.
  Explicit rollback also requires that grant for a disabled target; rejecting a candidate back
  to a disabled LKG merely preserves safe shutdown and does not require an enabling grant.
- Bundled is usable only before any signed package or policy has been admitted. No reset
  or implicit unsigned recovery follows policy expiry, revocation or a rejected candidate.
- Every accepted disabled package creates a retained signed disable barrier. Even a later
  signed rollback grant cannot activate an enabled package at/below that version. Re-enable
  requires a higher signed package, a current enabled policy and all local/build admission.
- Policy updates can immediately disable/revoke a trial candidate. Recovered pending state
  stays disabled after a policy update and cannot be promoted until explicitly rejected.
- Explicit update kind reuses the bounded injected source worker for policy bytes. No wire
  sniffing, URL, HTTP implementation, automatic job or production connection is added.

## Deliberately conservative operational semantics

A higher signed policy may re-enable or remove a temporary rollback grant. It cannot lower
an accepted floor or remove a revocation. An operator's mistaken revocation therefore needs
an already-authorized safe new package/new key path or a separately designed recovery/app
upgrade; silently deleting local state is not a repair. This protocol's bounded lists permit
64 revoked versions and 32 package key IDs. Capacity exhaustion needs a separately reviewed
protocol/application change, never list truncation or clearing. Revocation addresses package
signing keys only: policy-root rotation/revocation and production trust ownership are absent.

State format 2 retains policy, package high-water, disable barrier, active/history packages
and the pre-transaction observed-clock bound together. Format-1 M7-A state fails closed;
there is no unsafe automatic migration/reset. This is a development-only, undeployed slice.
A future installed-state migration must be designed and reviewed before release.

Every repository safety-decision time sample is recorded under one short sampling lock.
The bound is monotonic within a process and the pre-transaction sample is persisted on each
successful mutation. Negative/read-failed or regressing clocks disable authority. Read-only
observations after the last transaction are not durable across restart. Clock catch-up to the
observed bound allows reevaluation, still subject to policy/package expiry. There is no trusted
external time, secure monotonic counter, or protection against coherent full-directory replay.
Checksums detect damaged framing, not malicious local rewrites. Host fault injection does not
prove Android fsync, power-loss or hostile-OS resistance.

## Sequence and later gaps

This slice closes only the offline single-provider policy contract. Next recommended slice
is M7-C local health/management: fixed-vector self-test, bounded probation and failure counts,
bad-version isolation and an honest status/recovery UI. Do not start it as part of M7-B.
Later separate work includes closed extraction declarations/interpreter, multiple providers,
production root ownership/rotation, trusted clock/rollback evidence, delivery endpoint/service,
monitoring and operator recovery. Android/API31/API36, phone/tablet, process/power-loss and UI
acceptance are deferred. No APK/AAB/install, accounts/payment/publication/deployment occurs.
Vault is disabled, untouched and excluded from this review.
