# M9-A independent ordinary correctness review

## Verdict

**PASS for the reviewed fixed-schema1 ordinary reliability slice.** No additional
concrete source correctness defect was found. The independently executed frozen
suite passes **49/49 tests**, including **10 separately written coordinator
regressions**. The separate four-process host SQLite close/reopen check also
passes. This is not full M9, Android-device, security, or media acceptance.

Review date: 2026-10-07 UTC. Baseline:
`dfdddbbba2ea5fc3f810f02fa2652e55bb2ecba0`. Product/test WIP supplied by the build
owner: `a500be6`. The authoritative reviewed bytes are the 520-input frozen
manifest, SHA-256:
`fbb8b718ec059cba45274dd58767546d8528cbd559966b568cfd9d5b1d37222a`.

## Source review

Only `VideoDiscoveryCoordinator.kt` changes production behavior relative to the
baseline in this slice:

1. `launch` captures the launched `DiscoveryOperation`; callback completion and
   handle installation require that exact attempt object still owns the active
   request. Retry copies retain cancellation, operation ID and the original
   absolute deadline. A callback from a retired attempt is rejected before slot
   release, source-health mutation, retry scheduling, result admission or cursor
   publication. The existing synchronous-callback/exception path is also fenced.
2. The transaction's final `beforeCommit` guard now checks the original operation
   deadline as well as lifecycle/cancellation. A page that was received in time
   cannot publish after persistence work has consumed its remaining budget.
   Rejection rolls back checkpoint/pool, cursor, Seen facts and linked aliases
   together. Expiry is inclusive: equality fails, one millisecond before expiry
   succeeds. Cancellation remains distinct from a source-health failure.

The independent fixtures exercise both changes through public coordinator and
transaction interfaces, without copying the author’s fixture helpers. They check
three bounded attempts, late rate-limit callbacks, both retired successes during
the third attempt, a synchronous success followed by a runtime exception, the
current retry's cancellation handle, preservation of prior cursor/Seen facts at
the final deadline, the last valid millisecond, rollback on a final storage
exception with explicit restore, old-session callbacks, and compatible/expired
cursor boundaries.

## Executed frozen evidence

Command:

```sh
bash docs/m9a/evidence/independent-reliability-run.sh docs/m9a/evidence/independent-review-run
```

- Separate reviewer coordinator fixtures: 10 passed
- Author coordinator reliability matrix: 11 passed
- Author fixed Commons transport/cache recovery matrix: 15 passed
- Author Browser/Save isolation and codec disk reopen: 6 passed
- Explicitly selected ordinary transport methods: 7 passed
- Total: 49 passed, 0 failed; `OK (49 tests)`

The method-selecting runner does not execute unrelated legacy test methods.
Compilation is standalone Kotlin/JVM target 17 with the already admitted exact8
compiler/runtime/JUnit artifacts. No Gradle was run by this reviewer. The exact8
jar identities and SHA-256 values match the M7-C admission both before and after
execution. All 520 frozen entries pass before/after checks, and all 72 direct
runner source/resource/script inputs are a verified subset of the freeze and
remain unchanged.

Evidence:

- `evidence/independent-review-run/results.json` and `results.log`
- `evidence/independent-review-run/input-manifest.json`
- `evidence/independent-review-run/toolchain-before.json` and `toolchain-after.json`
- `evidence/independent-review-run/frozen-before.log` and `frozen-after.log`
- Independent fixture and runner sources under `evidence/IndependentReliability*.kt`
- `evidence/independent-reliability-run.sh`

## Ordinary fault and isolation coverage

The reviewed author tests are meaningful bounded fixtures: unreachable DNS and
refused connection remain single exchanges; partial status/headers/body/trailers,
ordinary malformed JSON and declared-length mismatch do not become success;
normal configured byte limits, a small operation allowance and one zero-progress
read are enforced; slow reads/deadline signals and cancellation release resources
without an internal retry. Fresh requests recover after these failures. Cache
expiry, no-store responses, failed unrelated queries and smaller later budgets
are checked without using stale success or destroying a still-valid entry.
Rate-limit retry boundaries are tested at one millisecond before and at equality.
The transport's bounded slow-header/body test also checks physical slot retention
until cleanup and its monotonic deadline despite a wall-clock correction.

Coordinator tests cover retry limits, original deadlines, pending-retry close,
stale callbacks, storage failure/rollback/recovery, cursor schema incompatibility,
expiry, exhaustion, one failed source with a surviving sibling, and zero sources.

Browser/Save tests assert navigation and explicit Save remain independently
available with zero providers, ordinary provider failures, and failed Discovery
storage. A Save/Record activity owner suspends Discovery; late Discovery failure
does not change the Save outcome. Their transfer, verification and publication
ports are test actors, so their successful Save state proves host coordination
only, not a real downloaded, decoded or published media artifact.

## Persistence and inherited boundaries

The reviewer separately executed `verify-process-reopen.py`, surrounded by frozen
manifest checks. Seed, rollback, commit and reopen each pass in a fresh Python
process using a temporary SQLite database. See
`evidence/independent-review-process-reopen.log`. The harness checks scoped alias
facts, revision barriers, rights retention, cursor/progress isolation and read-only
statistics after reopen. It uses source-guarded v3 DDL/history host helpers and
independently modelled checkpoint headers, not Android `SQLiteOpenHelper` or the
Kotlin persistence adapter at runtime. The Kotlin suite separately checks actual
Browser/Save codec bytes through host disk close/reopen; Browser restores a fresh
identity, excludes incognito state, and rejects old callbacks.

`evidence/independent-review-protected.json` records 17 explicitly checked
production files identical to the M8-B3 baseline. Together with the narrow
coordinator diff and ordinary tests, the review preserves:

- B1 alias facts, revision fencing and atomic transaction rules
- B2 the `UNKNOWN` native media identity bridge and real Save/Record activity
  owner priority; no newly inferred SAVED/PLAYED authority
- B3 release synthetic-selection refusal and existing disconnected production
  power-signal boundary; debug scenarios remain debug-only

## Limits

No product source was changed by this reviewer. No new dependency, Provider,
production trust root/service/account, SDK, SkinKit or permission change is
accepted here. No live network, real account, device, APK/AAB, full Android process
death/power-loss or physical energy test was performed. The prohibited dynamic
candidate, blocked stress probe and Vault review were not examined or substituted
for. No security/DoS stress was performed. Full JVM/dual-variant compile/lint,
exact74 and cold-restored aggregate receipts belong to the build owner’s separate
final verification; this report does not claim those checks as reviewer execution.
