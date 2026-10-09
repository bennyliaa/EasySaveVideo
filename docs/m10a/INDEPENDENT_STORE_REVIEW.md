# Independent ordinary store-boundary review

Reviewed on 2026-10-07 against checkpoint `8374733da4db64ba1457c21bb1423537fee4542a`.

## Result

No ordinary-correctness blocker was found in the reviewed purchase-preparation boundary.
The implementation is a fail-closed, unconfigured app-side contract. This conclusion does
not establish real-store behavior or completion of M10 Real IAP.

## Scope and method

Read-only source and contract inspection of:

- `app/src/main/kotlin/com/easysavevideo/app/entitlements/StorePurchaseBoundary.kt`
- The debug and release `entitlements/BuildEntitlements.kt` source-set implementations
- `app/src/test/kotlin/com/easysavevideo/app/entitlements/StorePurchaseBoundaryTest.kt`
- `docs/m10a/STORE_BOUNDARY.md`, repository constraints, and the referenced TR-13,
  T-E01–T-E06, and updated M10 requirements
- Existing Core snapshot, `StoreAdapter`, `OperationContext`, `EntitlementManager`, and
  `FeatureGate` definitions, plus app dependency/source-set configuration and source consumers

The reviewer did not run Gradle or builds. The parent build owner retains serialized
verification ownership. No application source was edited for this review.

## Checked behavior

1. **App/Core separation.** Product inputs and the product set are app-side strings.
   Core `StoreAdapter` remains snapshot-only, with `refresh` and `restore`; the new
   operation-status type is not referenced by Core. The new source has no billing SDK,
   network, persistence, account, receipt, token, raw error, or logging path. No dependency
   or custom source-set change accompanies this boundary.

2. **No grant from completion.** `StoreOperationStatus` contains fixed enum values without
   entitlement payloads. `VERIFICATION_REQUIRED` is explicitly unverified and cannot
   itself grant access. The unconfigured implementation never returns it or `PENDING`.
   There is no store-result-to-grant or status-to-gate path in this implementation.

3. **Immutable empty default.** `products` is a `val` backed by Kotlin `emptySet()`,
   and `canPurchase` is always false. The single stored unavailable snapshot has revision
   zero, an immutable empty grant list, and no validation timestamps. There is no grant
   mutation path or synthesized validated state.

4. **Stable snapshot semantics.** `snapshot`, Core `refresh`, and Core `restore` return
   the same snapshot instance for active, cancelled, and expired operations. App purchase
   and restore calls do not mutate this snapshot or advance its revision. This matches
   the documented distinction between a source snapshot and an operation-local outcome.

5. **Cancellation and deadline precedence.** Both app operations use the injected clock
   and the existing `OperationContext.stopReason`. Cancellation wins even at the deadline;
   `now >= deadline` maps to `TIMED_OUT`; otherwise the adapter returns `UNCONFIGURED`.
   The supplied operation is not replaced and its deadline is not extended. The
   `RETRY_EXHAUSTED` branch maps to `FAILED`, with the accurate caveat that the current
   `stopReason` implementation cannot produce that value.

6. **Release and debug wiring.** Release `BuildEntitlements.source` directly aliases
   `BuildEntitlements.store`, an `UnconfiguredStoreAdapter`. Existing main-source
   entitlement consumers use that `source`. `FakeEntitlementSource` and its controls
   remain confined to the debug source set; release's development panel is empty.
   The subsequently inspected standalone host evidence also exercises the same assembly
   test against the actual compiled release classes and release `BuildConfig.DEBUG=false`.
   This is source/host verification, not APK or release-distribution analysis.

7. **Gate and milestone claims.** The adapter supplies no paid grants. Existing free
   entitlement requirements remain unchanged, and platform/build-policy checks still run
   before entitlement allowance. Tests using all-capable fixture platforms establish
   gate semantics only; they do not demonstrate implementation of platform capabilities.
   Documentation clearly limits this work to preparation, defers real verification and
   business policy, and identifies updated M10 as Real IAP.

## Test evidence and limits

The reviewed test class contains eight deterministic JVM tests covering the default,
active purchase/restore, cancellation, exact/past deadlines, cancellation priority,
repeated snapshot identity/revision, free/paid gates, and build assembly. Its release
source-alias assertion runs only when `BuildConfig.DEBUG` is false, so debug execution
alone does not verify that assertion.

At initial review time, the existing Gradle debug XML reported eight tests, zero failures,
zero errors, and zero skipped, timestamped `2026-10-07T10:31:11.237Z`. These eight belong
to `StorePurchaseBoundaryTest`, not the whole Gradle aggregate.

On follow-up review, the build owner reported that the current AGP task graph has no
`testReleaseUnitTest` task. No build configuration was changed to add one. The reviewer
inspected `evidence/run-release-store-host.sh` and its generated evidence:

- `evidence/release-store-host.log` and `evidence/release-store/independent-results.log`
  report `OK (8 tests)` from standalone JUnit.
- The script compiles the unchanged `StorePurchaseBoundaryTest.kt` against actual
  `compileReleaseKotlin` classes, actual release Java classes, and the Core model/domain
  jars. It does not substitute a fake `BuildEntitlements` or generated test `BuildConfig`.
- `evidence/release-store/release-build-config.txt` identifies `BUILD_TYPE = "release"`
  and `DEBUG = false`; the script asserts the latter before compiling the test. Therefore
  the test's release-only source-alias assertion is exercised by this host run.
- `evidence/release-store/independent-toolchain-match.log` reports eight exact admitted
  artifact hashes matched. The actual release input manifest includes the entitlement
  classes, release `BuildConfig.class`, and Core jars; all entries pass the recorded
  before/after unchanged check. The compiled release entitlement class list contains no
  `FakeEntitlementSource` class.
- The recorded source check passes for the unchanged test, host script, and toolchain
  reference, and the recorded frozen-source check reports 539 files unchanged.

These are inspected build-owner results, not reviewer-run tests. The standalone release
eight must be reported separately from the Gradle debug count; they are not a Gradle
release unit-test task or Android instrumentation run. The final complete main-tree
aggregate results have now been inspected: `evidence/aggregate-summary.log` reports
1,822 Gradle JVM tests with zero failures, errors, or skipped tests and passing offline
compile/admission checks. The current debug XML reports this class's eight passing tests
at `2026-10-07T10:39:03.944Z`; `evidence/release-store-host.log` separately confirms the
final standalone actual-release run passed eight tests. The latter eight are not added
to or represented as part of the 1,822 Gradle count. Product and test source remained
unchanged; no reviewer rerun was performed.

There was no real purchase, real restore, pending callback, callback deduplication,
receipt verification, refund/revocation, device, APK/AAB, or distribution acceptance.
No broader adversarial or unrelated subsystem review was performed.
