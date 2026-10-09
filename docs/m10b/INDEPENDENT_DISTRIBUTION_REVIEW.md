# M10-B independent distribution-configuration review

Date: 2026-10-07 UTC. Scope: ordinary source correctness of the new app-only
distribution identity, build-value validation, and the two entitlement composition
aliases. This review does not investigate any blocked candidate, native Android
behavior, store policy, SDK integration, or payment business model.

## Source result

No blocking issue found in the inspected production change:

- Gradle accepts only seven fixed ASCII values before emitting a Java string
  literal. An omitted property selects `unconfigured`; an explicit invalid or blank
  value is rejected. No untrusted value is interpolated before validation.
- The generated field is read once into an app-only immutable configuration.
  Runtime parsing is exact and unknown values map to `UNCONFIGURED`. There is no
  runtime setter, account/installer probe, network request or location inference.
- Every channel constructs the existing `UnconfiguredStoreAdapter`. The change
  adds no operation state, grant, product mapping, SDK or persistence behavior.
- Both build source sets use the same configuration/store composition. Release
  still aliases its entitlement source to the unavailable store; the debug fake
  source remains confined to its existing source set.
- Core, product mapping, the existing store implementation, SkinKit, manifests,
  dependencies and platform capabilities are outside the three-file edit surface.
  Channel identity does not select paid-download versus in-app purchasing and is
  not evidence of an installed store or successful store acceptance.

The new scope gate verifies the exact permitted build/composition deltas and protects
the other baseline tracked files and executable modes. It also checks both recorded
runtime inventories for the nine expected internal project coordinates and the exact
set of 74 admitted external coordinate/artifact/hash rows, rejecting extra or missing
rows. Identical repeated inventory lines are deduplicated. The existing
`verifyRuntimeAdmission` task independently enforces the exact admitted set against
Gradle's resolved external components and artifact bytes.

## Complementary independent tests

`evidence/IndependentDistributionReviewTest.kt` adds three standalone JUnit cases:

1. Compare an externally supplied, required channel and debug/release expectation
   with the generated BuildConfig and actual composition. The expected enum is
   selected independently of the production parser, avoiding a self-consistent
   parser/code-generation mismatch.
2. Construct all explicit profiles and verify that their store instances cannot
   rebind the process-selected configuration, store or entitlement source. Their
   snapshots remain empty and unavailable.
3. Inspect actual compiled class availability: the debug fake source exists and
   owns only the debug entitlement source; the release classpath lacks that class
   and its source still aliases the unavailable store.

The first and third tests require the external `esv.expected.channel` and/or
`esv.expected.variant` properties and fail when their required expectations are
missing or unsupported. The host harness supplies these while loading the actual
compiled debug/release outputs. These tests do not synthesize a BuildConfig or
recompile production sources as substitutes for the app outputs.

## Verified main-worktree execution evidence

The sole build owner ran the aggregate and actual-compiled-output matrix. The reviewer
then read the execution logs, generated BuildConfig records, per-run unchanged-input
records and summaries. No additional Gradle process was started by the reviewer.

- `evidence/strict-offline-aggregate.log`: successful build with all 87 actionable
  tasks executed. `evidence/aggregate-summary.log`: 1,826 Gradle JVM tests with zero
  failures, errors or skips; debug and release lint each have zero errors, 14 warnings
  and six hints. These are the main aggregate results, not a warning-free claim.
- `evidence/channel-matrix/{unconfigured,google_play,huawei,xiaomi,oppo,vivo,tencent_myapp}/`
  contains actual debug and release BuildConfig/compiled-input records and a passing
  15-test log for each variant. Each selection consists of eight existing store tests,
  four implementation tests and the three independent cases above. All 14 matrix
  executions pass. The initial `evidence/distribution-default/` debug and release
  selections also pass 15 tests each. Repeated standalone selections are not added
  to the 1,826 Gradle JVM total.
- Every inspected host directory records successful unchanged test-source and
  production-input SHA-256 checks and the same 559-input frozen manifest. Generated
  channel strings match the externally expected identity; release records explicitly
  show `DEBUG = false`. The independent test-source SHA-256 remains
  `453260525035c60f90476115ac0a4ae9e938dcf27826f2ce49005669069ff895`.
- `evidence/channel-matrix/rejected-{0,1,2,3,4}.log` each records a failed build with
  the expected invalid-channel configuration error. The final matrix selection omits
  the property and records the restored `unconfigured` default.
- `evidence/distribution-channel-admission.json` records 2,717 protected baseline
  files, the exact three allowed edits and unchanged exact74 dependencies.
  `evidence/frozen-inputs-after.log` records all 559 frozen inputs unchanged. The
  frozen manifest SHA-256 is
  `19f17eb4495f4ac795764169979cef51b2b3a2f327373f6815ebca895cf6f602`.

This evidence establishes the bounded source/composition behavior tested above.
Final commit/archive and cold-restore results are separate acceptance records and
were still pending when this main-worktree review was completed.

## Limits

This is distribution-identity preparation, not per-store SDK isolation, payment
acceptance, Android/device validation or release approval. No APK/AAB, account,
credentials, server, signing, publication, or blocked review action was performed.
The intended channel list is implemented without deciding the user's deferred
payment strategy. No permission or readiness is inferred from a selected label.
