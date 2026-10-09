# M8-B3 independent ordinary power review

Status: PASS for the corrected frozen ordinary pure/host and source integration scope,
verified on 2026-10-07. No unresolved serious finding remains within that reviewed scope.
This is an ordinary injected pure/host and source integration review, not an Android-device,
OS-signal, energy-measurement, Provider-admission, or Vault review.

## Scope and ownership

Reviewed `DiscoveryPowerPolicy`, scheduler/coordinator request accounting and cancellation,
`AndroidDiscoveryController` policy publication and pending page demand, both
`BuildDiscoveryPower` variants, and power presentation/controls. Independent deliverables are
`evidence/independent-power-review.kt` and `evidence/independent-power-run.sh`.
The implementation owner alone edits product code and runs Gradle.

## Findings reported before freeze

1. Initialization could consume a newer immediate power fence with a stale snapshot. The new
   coordinator was published before the initial `desiredPower` read and default-generation
   `setPowerSignals` call. A later UI scenario could fence that coordinator, after which the
   stale initialization call would acknowledge its generation and permit a launch under old
   signals. Reported to the owner, who changed initialization to use explicit generation zero.
   The corrected explicit generation-zero host regression and static integration assertion
   passed in the frozen run.
2. The existing shared lifecycle latch also allowed a serialized search/filter initialization
   to clear a newer immediate Save/Record suspension by calling `setVisibility(true, true)`
   before the queued task-priority update. Reported to the owner with a recommendation for
   a separate shared immediate priority veto that stale lifecycle state cannot clear.
   The owner instead implemented dedicated lifecycle generations and a controller helper that
   captures the generation before reading all owner/visibility flags, applies priority, then
   acknowledges that captured visibility generation. Independent stale-search, initialization,
   acknowledgement-order host tests and static helper-order assertions passed in both variants.
   This is host/source verification, not Android looper or media-device execution.
3. The new admission script initially omitted the intentionally adjusted existing
   `DiscoveryCorrelatedEventTest.kt` from its scoped file allowlist. Reported to the owner and
   corrected before freeze. The parent owns execution of the full admission aggregate.

## Independent evidence design

- Full enum cross-product policy intersection against three caller-limit configurations:
  1,152 combinations per variant, including all disabled/unknown/adverse states.
- Foreground/visibility/Save/Record gates, no automatic demand after unknown or changing
  observations, unchanged-snapshot behavior and explicit-generation ordering.
- Lifecycle churn retains spent accounting; conservative retries count attempts and retain
  deadline/resource bounds; deliberately leaked cancelled retry callbacks are harmless.
- Backward/forward injected clock movement and Retry-After/deadline checks.
- Synchronous cancellation callbacks, late success/failure, and a real host thread blocked
  inside a store transaction while the immediate power fence triggers atomic rollback.
- Effective watermarks/page demand, query/view/cursor restore, nonpersistent simulation,
  preserved alias/event facts, zero-source operation, and both debug/release scenario contracts.
- Android controller/Compose wiring is inspected and statically checked, never represented as
  a real Android execution. Existing B1/B2 regression runners remain separate evidence.

The runner requires every compiled/reviewed input and itself in the shared frozen manifest,
verifies that manifest before/after execution, and checks the exact eight standalone Kotlin,
runtime, JUnit and Hamcrest artifact SHA-256 values against the historical M7-C admission.
It separately compiles/runs the debug and release policy variants without Gradle or network.

## Verified results

Command: `bash docs/m8b3/evidence/independent-power-run.sh` with output directory
`docs/m8b3/evidence/independent-power-review-run`. Exit status: 0.

- Debug standalone compilation: PASS; JUnit: 24 tests, zero failures.
- Release standalone compilation: PASS; JUnit: the same 24 tests, zero failures.
- Both compile logs are empty. These are 24 distinct review cases executed in two variants,
  not 48 independently different cases.
- Static controller/UI integration assertions: PASS, recorded separately as source-only.
- Exact eight previously admitted compiler/runtime/JUnit artifact hashes: PASS.
- All reviewed/compiled inputs and runner present in the shared manifest: PASS.
- All 504 frozen input files and the shared manifest itself unchanged before/after: PASS.
- Supplemental input SHA-256 checks: PASS.

Frozen manifest SHA-256:
`cd97d5a2f347489b1b138fd1dae065e81ab6c6d5ac9e4d7ab255e4883d143d4c`.
Independent test SHA-256:
`40b91a0fd8ba3e1c02f27db56abed4e263bcc71a6c2164deb67d9d13e58e67f2`.
Independent runner SHA-256:
`e28cd9cef6459cf33f42706d1460af39118bb44198136cb5f29c01a98b21e0f1`.

Evidence files under the output directory:

- `independent-power-debug-results.log` and `independent-power-release-results.log`
- `independent-power-debug-compile.log` and `independent-power-release-compile.log`
- `independent-power-source-review.json`
- `independent-power-toolchain-match.log` and `independent-power-toolchain-sha256.txt`
- `independent-power-frozen-before.json` and `independent-power-frozen-after.log`
- `independent-power-input-sha256.txt` and `independent-power-input-unchanged.log`

The shared manifest includes more source than this reviewer manually examined. Its integrity
check does not imply manual review of all 504 files. The parent's strict Gradle/JVM/lint, SQL,
DOM/media/Provider hosts, prior B1/B2 independent hosts, and cold-restored aggregates are
separate verification and are not claimed as this reviewer's execution.

## Boundaries

Production Android battery/thermal/network signals remain NOT_CONNECTED. Debug scenarios
are explicit process-local synthetic inputs. No measured energy saving is asserted.
Native video SAVED/PLAYED remain UNKNOWN; page provenance is not video identity. B1 alias,
revision, monotonic-history semantics and B2 media ownership are preserved as review targets.
No M7-D candidate/runtime/probe or substitute, Vault, new source/library, production service,
real account, device, APK/AAB, SkinKit, OS reader or network work was performed.
