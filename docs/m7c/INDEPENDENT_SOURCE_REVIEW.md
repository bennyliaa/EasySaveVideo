# M7-C independent ordinary Provider health and management review

Review date: 2026-10-07 UTC. Baseline:
`13ab2b09e1ba33b1e8312b52ba42f78939071c3d` (M7-B).

## Status

**COMPLETE for the scoped ordinary-Provider source/host review. No unresolved
blocking source defect remains after the corrections below.** Final frozen-source
host verification passed **327/327 tests**: 223 ordinary Commons tests, 37 ordinary
management/owner app tests, 34 retained M7-B independent cases and 33 new independent
M7-C cases. These overlap ordinary aggregate tests and are not added to its total.
All **61 compiled source/fixture inputs** remained unchanged.

The **95-entry** reviewed manifest is
`evidence/independent-final-source-manifest.json`, SHA-256:
`5467d271820eae5da4253e6722942cbd2eeb9e49005f47764af0ae8ad5b3a265`.
It was frozen at **2026-10-07T01:26:25.101547Z** and every byte extent/hash
independently matched at **2026-10-07T01:27:01.927327Z**, then matched
again at **2026-10-07T01:27:43.844336Z**. All eight pinned
compiler/runtime/JUnit artifact hashes are identical to the M7-B host toolchain.
Earlier moving-tree runs and the superseded 324-test run are diagnostic evidence,
not the final acceptance record.

This review is limited to the already-compiled Commons parameter provider,
bounded local probation/isolation, repository persistence and the actual local
management wiring/controller/presentation. It is not full M7, Android/device
acceptance, production signing/delivery, or release approval. No additional
provider, external source/library, execution rule, production identity, account,
live HTTP update or deployment is admitted. Vault is excluded, remains disabled
and untouched; no blocked Vault review was attempted or retried.

The reviewer writes only this report and its evidence/fixtures. Production and
ordinary test edits, Gradle, integration and commits belong to the implementation
owner. The reviewer did not invoke Gradle, build/install an APK/AAB, contact a real
service, modify trust or use a production key.

## Reviewed invariants

- A fixed compiled synthetic parser exercise runs before enabling a signed
  candidate. Its tiny positive and negative vectors cannot create network,
  executable, account, endpoint, permission or media-saving authority. Failed
  self-tests preserve the existing authority and retain a bounded isolation entry.
- An opaque one-use observation is process-local, with a single outstanding slot.
  Publication checks the exact full signed-package digest/version, signed-policy
  digest/version and generation under the repository lock. Tokens cannot cross
  repositories, survive restart, be reused, or be completed against a newer policy.
- Three consecutive fresh successes promote. Three consecutive recognized API
  semantic failures isolate. Inconclusive results break both streaks, retain total
  exposure, and never directly become a bad-package verdict. Cache, local cooldown,
  configuration/filter/busy, cancellation and deadline paths do not manufacture
  fresh evidence. Only completed HTTP 200 JSON semantic parsing can label a
  structure failure; malformed JSON, headers/body problems, HTTP/service errors
  and challenge responses are inconclusive.
- A candidate has at most 12 conclusive / 20 total completed observations and a
  24-hour window narrowed by package/policy expiry. Exhaustion suspends rather than
  isolates. Policy changes clear consecutive streaks and invalidate tokens without
  extending time or resetting cumulative exposure. These are local engineering
  bounds, not statistical compatibility or malicious-code detection guarantees.
- Counters, isolation records and signed anchors share bounded atomic persistence.
  Pending state remains disabled/RECOVERY_REQUIRED after restart. Isolation is
  bounded at 32 entries and cannot be silently evicted or reset. A full isolation
  store rejects new enabling candidates while still permitting admissible signed
  disabling packages.
- Automatic fallback remains intersected with the current signed exact-envelope
  rollback grant, package/policy time, revocations, minimum safe version and signed
  disable barrier. High-water and repository-policy authority never move backward
  with the active pointer. No unsigned bundled fallback or reset is introduced.
- Management uses the real process-owned repository and its legal action flags.
  Background work uses a single non-queuing physical worker; hidden/closed sessions
  invalidate all old callbacks. Reopening does not free a hung worker or reset
  storage. Local completion is distinct from enabled authority and site health.
  Rollback carries the reviewed generation, so a newly legal target cannot replace
  what the user confirmed. Missing contact, roots and endpoint remain visible.

## Findings and corrections before final verification

### R1 — Late health persistence could erase an expired probation window

The first success-promotion path checked the 24-hour window before writing, then
prepared a stable record with no health window. With the default callback, a slow
write crossing the deadline could publish that record and lose the expiry gate.
The owner added repository-owned commit validation immediately before publication,
independently of the metadata caller. Deadline/authority failure after a transaction
begins preserves uncertainty evidence and disables the repository. Independent
fixtures advance time at each of the five exposed durability stages and reopen.

### R2 — Malformed success markers could manufacture positive health

The parser previously accepted `batchcomplete:null`, `batchcomplete:false` and
`query:{}` as empty successful pages. Such pages could increment success counters.
The owner now requires a present batchcomplete marker to be true and a present
query to contain an array of pages. The independent fixture repeats each malformed
response through the real metadata/repository bridge and rejects healthy promotion.
This is a stricter success criterion, not a claim that every empty result is bad.

### R3 — Kotlin numeric fallback rejected every no-policy observation

The policy-version equality used an Elvis fallback `0`. Kotlin 2.2.10 compiled the
fallback as boxed Integer, while the observation version was Long. Bytecode checks
therefore rejected zero-policy observations as STALE_OBSERVATION. This affected
real token completion, not merely the test-only legacy helper. The reviewer
reproduced it with direct tokens and inspected the generated bytecode. Long version
fallbacks are now explicitly `0L`, while byte sizes remain Int. Diagnostic bytecode
and the failing run are preserved; final fixtures exercise absent-policy promotion.

### R4 — Local cooldown retries could consume the remote exposure budget

The initial failure hook also observed RATE_LIMITED before any new transport call.
Repeated local cooldown requests could exhaust 20 observations from one response.
The hook is now gated on a fresh execute attempt. Configuration, filter, busy,
cached and local cooldown rejection paths abandon their tokens. Cancellation and
expired completion remain excluded. Ordinary bridge tests explicitly cover these
routes; independent repository/cache tests verify no duplicate success promotion.

### R5 — Management confirmation and presentation needed tighter truthfulness

The reviewer requested a generation-bound rollback because a stale displayed
snapshot could otherwise act on a newer legal target. The repository now checks
that generation under its mutation lock. LKG wording was corrected because a
signed disabling package becomes stable without passing successful probation.
The UI owner also identified unreadable-storage default zeros as misleading;
those states now show version/health/isolation as unconfirmed and disable actions.
The reviewer independently checks lifecycle/repeated actions and presentation.

### R6 — Caller callback reentrancy could overlap repository transactions

The repository owner identified that JVM synchronized blocks are reentrant: a
caller checkpoint could invoke another mutator before the outer record publishes.
A nested newer policy/high-water could then be overwritten by the older outer
record. The reviewer recommended a uniform mutation-in-progress BUSY gate and
independently tests policy/install reentrancy. Read-only snapshots remain usable;
ordinary competing threads still serialize normally. Closing during a caller checkpoint
also invalidates publication. Ownership is retained until the active transaction
leaves its uncertainty marker, preventing a second owner from observing the
unpublished healthy bytes. Independent late-close cases cover all five stages.

## Independent host method and evidence

`evidence/run-independent-provider-host.sh` uses the previously cached Kotlin/JVM
2.2.10 compiler, JUnit 4.13.2 and existing JDK21 launcher directly. It fetches no
artifacts and invokes no Gradle. It compiles every Commons production/test source,
core-model/discovery production inputs, the pure management controller/presentation
and their ordinary tests, the process-owned repository wrapper and its tests,
the unchanged 34 M7-B reviewer cases, and this reviewer's
M7-C cases. Full toolchain and input SHA-256 hashes are recorded and rechecked.

JUnit suites are enumerated from test-source files rather than every class named
`*Test`: the production `ProviderFixedSelfTest` object is not a JUnit fixture. The
initial inherited runner reported it as an initialization error; correcting suite
enumeration does not omit an actual test class. Every invocation clears only its
own non-symlinked output classes directory before compilation, so stale bytecode
cannot satisfy references or survive same-directory aggregate reruns.

The reviewed manifest includes the prior ordinary-provider source scope, all
Commons source/tests/resources, core-model/discovery compilation inputs, affected
app navigation/UI/registration and discovery tests, scope document, host runner,
manifest tool, static admission and aggregate scripts. The report and generated
logs/manifests are excluded to avoid circular self-hashing. A separate manifest
verification rereads every extent and hash after the final host run.

M7-C's static health guard replaces the M7-B policy guard whose app-byte-identity
and format-2 assumptions no longer apply. It keeps package/policy/runtime protocol
files byte-identical to M7-B, checks retained security gates and adds format-3,
fixed-vector, token/digest, empty-root, no-endpoint and main-JAR boundaries. There
remain ten aggregate static guards; this is an explicit replacement, not an
additional eleventh guard. Aggregate execution is the build owner's evidence.


Final retained evidence:

- `evidence/independent-final-host-console.log` and
  `evidence/independent-final-host-results.log`: compiler/JUnit result, 327/327.
- `evidence/independent-final-source-sha256.txt` and
  `evidence/independent-final-source-unchanged.log`: all 61 compiled inputs and
  successful post-run SHA-256 checks; `independent-final-test-classes.txt` lists
  every selected JUnit class.
- `evidence/independent-final-toolchain-sha256.txt` and
  `evidence/independent-final-toolchain-match.log`: eight existing artifact hashes
  and exact match against the M7-B toolchain.
- `evidence/independent-final-source-manifest.json` and
  `evidence/independent-final-manifest-verification.json`: all 95 reviewed entries,
  complete post-run extent/digest verification, and no mismatches.
- `evidence/INDEPENDENT_COMMANDS.md`: reproducible commands and preserved diagnostic
  history, including the corrected reviewer expectation for a non-session backend
  cancellation. ACTION_REJECTED/CANCELLED is a truthful error, not a success; the
  initial reviewer test unnecessarily required LOCAL_UNAVAILABLE.

## Limitations and NOT_RUN

- Compose rendering, actual interaction on API31/current Android phones/tablets,
  activity/process termination, Android storage/provider/filesystem behavior and
  actual power loss: **NOT_RUN** by this reviewer. Pure controller/presentation
  tests and source inspection do not substitute for device acceptance.
- Real access-denied metadata lookups and a filesystem rejecting every subsequent
  recovery-marker write: **NOT_RUN**. Atomic host fault hooks do not establish
  physical torn-write durability under every device/filesystem failure.
- No hostile-filesystem freshness is claimed. Checksums detect framing damage;
  signed content does not prevent coherent rollback of an entire app-private
  directory. Committed pre-write clock evidence and in-process observations do not
  provide trusted time or persist every later read-only observation.
- Format 3 deliberately rejects undeployed development formats 1/2 without reset.
  Installed-release migration, isolation/revocation-capacity recovery and operational
  root rotation remain separately reviewed work.
- Fixed synthetic vectors and observation classification cannot establish live-site
  compatibility or prove that a parameter package is defective or malicious.
  Stable versions have no newly implemented long-term health monitoring.
- APK/AAB, installation, real service traffic/accounts, operational signing roots,
  update service/endpoint, publishing and deployment: **NOT_RUN / outside scope**.
  Ordinary media regressions remain the owner's existing host tests, with no new
  hardware/media-quality acceptance claim.
