# M7-B independent ordinary-provider source review

Date: 2026-10-07 UTC. Baseline: M7-A `bccfc3ba644b4e6b86a52bf4474f240dd1971cee`.

## Status

**COMPLETE for the scoped ordinary-provider source/host review.** No unresolved blocking
source defect remains after the time-sampling correction and explicit-rollback semantic
alignment described below. The final frozen-source host run passed **211/211 tests**:
**177 ordinary Commons tests + 34 independent reviewer tests**. All 48 compiled input
hashes remained unchanged.

The reviewed source/test/script/resource/scope manifest is
`evidence/independent-final-source-manifest.json`, containing **65 entries**, SHA-256:
`00a6b932db556aba4dae5992e33df2cd606c9f3b36e2f5b52d99aa3b642539cb`.
It was frozen at **2026-10-07 00:55:04 UTC** and independently rechecked with every
extent/hash matching at **2026-10-07 00:55:44 UTC**. This binds the review to exact bytes,
not merely a moving branch or the earlier WIP commit.

This is not full M7 completion, release approval, actual Android/device acceptance,
production deployment or a trusted-time/full-filesystem anti-rollback certification.

The scope is the existing compiled Wikimedia Commons provider and its independent signed repository policy. No additional provider, endpoint, executable rule, deployment, production trust identity, new dependency or key-root rotation is in this slice. The reviewer modifies only this report and its independent evidence. Gradle is exclusively owned by the implementation owner. Vault remains disabled, untouched and excluded. No Vault review was attempted or retried.

## Specification and boundary gates

`docs/reference/03_Development_Milestones_update1.md` places provider maintainability at M7 and supersedes the older milestone ordering. The original architecture section 6 requires signed rollback permission with unchanged revocation/security floors; technical requirements 3.4 require repository policy to intersect user/build admission; fault cases FI-18–FI-21 require bounded interrupted-update handling and prohibit old-signature replay from bypassing security floors. `AGENTS.md` retains API31 minimum and zero-provider/browser independence.

The independent review verified the following boundaries:

1. Closed canonical, bounded, independently signed repository-policy bytes, with fixed built-in provider target and no executable/network/permission fields. Package and policy trust roles cannot substitute for each other; production trust roots remain empty.
2. Strictly increasing repository-policy version; nondecreasing minimum package version and accumulating package-version/key-ID revocations. Replay, malformed policy, removed revocation and lower security floor cannot replace established authority.
3. Global policy disable applies uniformly to install, effective snapshots, candidate health promotion, last-known-good recovery and rollback. Expired policy cannot become absent authority or re-enable bundled defaults.
4. Every rollback below the accepted package high-water requires the current signed repository policy to name the exact target version and SHA-256 of the complete signed package envelope. Permission cannot override global disable, the security floor, revocation or target expiry.
5. Policy and package publication preserve signed high-water evidence, atomic intent framing, cancellation fences and fail-closed recovery. Old format-1 state is rejected without clearing or silently migrating it to a fresh enabled store.
6. The observed time lower bound protects in-process observations and persisted mutations/restarts within that evidence. It does not claim protection against coherent rollback of the complete app-private directory, nor supply trusted external time.
7. Live request and result fencing survives a repository-policy authority change; generation changes do not bypass physical request slots, local disable, absent build contact or pre-existing budget limits.

## Findings and semantic alignment

### R1 — High: an unrecorded secondary clock sample could undo an observed expiry

The first implementation recorded only the entry `clockError()` sample. `securityError`,
`timeError` and verifier calls separately read the clock without raising that observation
bound. A snapshot could first see a pre-expiry time, then return an expiry error after a
later sample crossed expiration, yet a subsequent lower pre-expiry sample would still pass
the stored bound and re-enable authority. This contradicted the promised in-process
observation guarantee.

The reviewer reported this before final source freeze. The owner replaced every direct
repository clock read with one `sampleTime()` route, recorded under a short sampling lock.
Each snapshot uses one returned sample for package time, repository-policy time and rollback
eligibility. Mutations use the same route; bad-clock failures return technical errors rather
than being mislabeled as storage corruption. Independent scripted-clock cases cover both
package and repository-policy expiry and reject reactivation after the clock moves backward.
The source has exactly one direct `clock.nowMillis()` call, inside this helper.

### R2 — Specification alignment: explicit disabled-target rollback also requires a grant

The initial gate allowed an explicit rollback to an older disabled package without a grant,
because that target granted no execution authority. The owner raised this distinction and
the reviewer recommended the stricter literal contract: every explicit `rollback()` needs
the current signed target version and complete-envelope digest, even when its target remains
disabled. `canRollback` now uses the same gate. Candidate rejection may still restore a
disabled LKG without a grant because it only preserves disabled authority. An independent
case tests both routes. This was a contract tightening, not a demonstrated enable bypass.

## Independent source observations

- ESVS repository-policy signatures authenticate the complete canonical header and payload;
  ESVP package bytes cannot substitute for the independent signature domain even with the
  same injected RSA key. Empty/default roots do not infer trust from package bytes or HTTPS.
- Arrays, RSA parameters, collections and rollback digests are detached from caller mutation.
  Revocation list counts are bounded before allocation; entries must be distinct and strictly
  ordered. Unknown fields, schemas, targets, formats and non-0/1 flags are rejected.
- `securityError` is the shared effective-authority gate. Package installation and health
  promotion consult it; rejection may persist the historical fallback but the resulting
  snapshot remains disabled unless current signed policy and package evidence permit it.
  Explicit rollback requires the same gate. A revoked or expired object can remain as
  historical anti-replay evidence but cannot thereby authorize current operation.
- The policy version never moves with the package active pointer. New policies must preserve
  previous revocations and the minimum package version. A newer policy may withdraw its
  temporary rollback grant. This also disables a package already running under the old grant.
- A signed disabled package establishes a separately persisted signed disable barrier. An
  exact rollback grant cannot make an older enabled package cross it. A later admissible
  higher package is needed. Independent global policy disable remains an additional gate.
- Format-2 state binds the policy, package high-water, disable barrier and package history in
  one bounded transaction. Restored unconfirmed candidates remain recovery-required even
  after a policy update. Format-1 state is rejected without rewriting/deleting it or silently
  returning to bundled defaults.
- The explicit policy update kind reuses the existing source worker and no-I/O publication
  gate. It adds no transport, endpoint, algorithm negotiation or production job. Runtime
  cancellation signals before waiting on repository work; a blocked worker retains its slot.
- The unchanged application registration still uses empty package roots and the new default
  empty policy roots. Operator build contact and user enable remain necessary. Repository
  initialization stays on discovery's I/O lane. Android classes are source-reviewed only.

## Independent host verification

The reviewer-owned `evidence/run-independent-provider-host.sh` invokes the already-cached
Kotlin/JVM 2.2.10 compiler and JUnit 4.13.2 directly. It neither invokes Gradle nor fetches
artifacts. It compiles core-model, discovery, Commons production/test sources and two
reviewer fixtures, recording input/toolchain hashes and checking inputs after execution.

The M7-A reviewer fixture retains all 14 regression tests. Only the two tests whose old
expected outcome was unsigned bundled recovery were adapted to M7-B's explicit signed
rollback/fail-closed fallback requirements; their replay/restart and recovery checks remain.
The new reviewer fixture independently encodes policy wire bytes and uses its own ephemeral
in-memory RSA key. Its 20 adversarial tests cover:

- Every envelope truncation and single-byte mutation; correctly signed malformed or unknown
  fields; canonical ordering/count/digest boundaries; empty roots and signature-role separation.
- Policy replay, lower security floor or removed revocations before/after restart; expired
  signed policy retaining its historical high-water and restriction evidence.
- Global disable against a pending candidate, health confirmation, LKG rejection, explicit
  rollback and new package installation; policy-only state and expiration cannot enable bundle.
- Exact rollback version and full signed-envelope digest; invalid/different-envelope grants;
  current grant withdrawal; floor, version/key revocation and expiry overriding grants.
- Persisted signed package-disable barriers; policy updates preserving recovery-required
  pending state; bounded 64-version/32-key revocation capacity cannot be evaded by truncation.
- Policy failure/cancellation at all five exposed durability stages, reopening fail-closed;
  corrupt/missing state and an independently encoded valid-checksum legacy format-1 state.
- Persisted mutation clock lower bounds across restart, and both forms of in-process expiry
  observation followed by a regressing clock.
- Existing lease cancellation on policy disable and policy-runtime cancellation while storage
  is blocked, with logical cancellation and continued physical-slot ownership.

The initial diagnostic run passed 208/208 tests with all 48 compiled input hashes unchanged.
Three additional reviewer tests and the explicit disabled-target rollback correction followed.
The definitive run passed **211/211** against the final frozen bytes, with all 48 compiled
inputs and all 65 reviewed manifest entries independently confirmed unchanged.

Evidence:

- `evidence/independent-final-host-console.log` and
  `evidence/independent-final-host-results.log`: successful compiler/JUnit run and count.
- `evidence/independent-final-source-sha256.txt` and
  `evidence/independent-final-source-unchanged.log`: all 48 compiled sources/fixtures and
  successful after-run SHA-256 checks.
- `evidence/independent-final-toolchain-sha256.txt`: exact cached Kotlin compiler/runtime,
  annotations, coroutine, JUnit and Hamcrest artifacts used by the direct host runner.
- `evidence/independent-final-source-manifest.json` and
  `evidence/independent-final-manifest-verification.json`: all 65 reviewed source/test,
  resource, app integration, scope and script extents/hashes, including the new admission
  and aggregate scripts. Those scripts were inspected; the reviewer did not invoke Gradle.

The diagnostic artifacts remain prefixed `independent-diagnostic-1-` and are not substituted
for the final run. The reviewer modified no production source or Gradle script, invoked no
Gradle/Git mutation, and performed no Android build/installation or live network test.

## Explicit limitations and NOT_RUN

- Actual Android installation, API31/current-device execution, process termination, power
  loss, filesystem/provider behavior, app Context initialization and UI acceptance:
  **NOT_RUN** by this reviewer. Host fault hooks do not certify physical torn-write safety.
- Actual access-denied metadata lookup and a filesystem refusing all subsequent recovery
  marker writes: **NOT_RUN**. Exception propagation is reviewed; previously tested dangling
  links/wrong file types are not a substitute for those conditions.
- No hostile-filesystem integrity is claimed. Checksums detect framing damage; signatures
  authenticate included policies/packages, not their filesystem freshness. Coherent full
  directory rollback, malicious local rewrites and trusted external time remain outside scope.
- Only the pre-transaction observed-clock bound is persisted. Later read-only observations
  can be lost on restart. The design does not claim otherwise or durable observation of every
  wall-clock sample. Clock catch-up only permits reevaluation under all remaining gates.
- Revocation lists are bounded at 64 package versions/32 package keys and cannot shrink.
  Exhaustion needs separately reviewed application/protocol work. Production root ownership,
  root rotation/revocation and external operational recovery are not implemented here.
- APK/AAB assembly, install, live update download, production signing/network setup, real
  accounts, release, publication and deployment: **NOT_RUN / out of scope**. Extraction rules,
  extra providers and executable payloads are not implemented or admitted.
- Aggregate builds/lint, Android compilation, dependency evidence and cold restoration are
  owned by the build owner and must be reported separately. This is a scoped ordinary-provider
  source/host review, not full M7 completion or Android security/release acceptance.
