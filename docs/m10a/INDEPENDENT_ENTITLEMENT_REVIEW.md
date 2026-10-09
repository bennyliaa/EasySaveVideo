# M10-A independent entitlement value review

## Result and scope

**PASS for the bounded reviewed scope.** Source review found no correctness blocker in the
immutability changes relative to baseline `7731c32`. The parent build owner executed the
independent regression selection against frozen sources: **4/4 tests passed**. This reviewer
read the resulting logs and rechecked the reviewed-source/test hashes; execution was performed
by the build owner. The broader main-tree aggregate also passed as separately recorded below;
this review's correctness conclusion remains limited to the assigned entitlement/mapping scope.

Reviewed `AGENTS.md`, Technical Requirements TR-13 (lines 241–257), acceptance T-E01–T-E06
(lines 176–181), and these files:

- `core-model/src/main/kotlin/com/easysavevideo/core/model/Entitlements.kt`
- `core-domain/src/main/kotlin/com/easysavevideo/core/domain/EntitlementManager.kt`
- `app/src/main/kotlin/com/easysavevideo/app/entitlements/ProductMapping.kt`
- `core-domain/src/test/kotlin/com/easysavevideo/core/domain/EntitlementImmutabilityTest.kt`
- `app/src/test/kotlin/com/easysavevideo/app/entitlements/ProductMappingImmutabilityTest.kt`
- Existing `EntitlementsTest.kt` for complementary gate coverage

Only this report and `evidence/IndependentEntitlementReviewTest.kt` were authored by this reviewer.
No product changes, Gradle execution, SDK/account/network work, APK/device work, or excluded
Vault/M7D investigation was performed.

## Findings

1. **Snapshot ownership is correct.** `Entitlements.kt:20–33` copies caller input into a new
   `ArrayList` and wraps it with `Collections.unmodifiableList` before uniqueness validation.
   The retained elements are final immutable `EntitlementGrant` values (`:9–14`), so ordinary
   input-list edits cannot change an observed revision. The Java wrapper also protects returned
   iterators, list iterators, and sublist views; no mutable backing-list reference is exposed.
2. **Copy, components, equality and hash behavior are preserved at the value API.**
   `Entitlements.kt:36–65` routes copy construction through the same defensive constructor,
   preserves the five component positions and named/default copy parameters, and implements
   the baseline data-class field order for equality, hash and text. Removal of the `data`
   modifier changes Kotlin reflection metadata (`isData`); no full reflection equivalence is
   claimed. The independent selection compares value behavior with a generated baseline-shaped
   data class, including high bits of `Long` revisions, null defaults, every equality field,
   explicit-null copy arguments, and hash-set stability after caller-list mutation.
3. **Product mappings cannot change through ordinary aliases.** `ProductMapping.kt:7–14`
   creates a new outer map and a new `LinkedHashSet` for each nested mapping, then wraps both.
   Returning the frozen set therefore preserves future lookups even after original map/set
   mutation or output iterator removal attempts. Unknown product IDs still produce no grants.
4. **Revision and failure handling remain fail-closed for the reviewed paths.**
   `EntitlementManager.kt:9–17` accepts only the first snapshot or a strictly newer revision.
   With immutable snapshots, same/lower revision active grants cannot replace observed revocation.
   A source exception returns an empty, unavailable copy without changing the revision
   high-watermark. This is a temporary failure overlay, not a persisted revocation: a subsequent
   successful source read may return the prior accepted snapshot. The independent test checks
   that a previously accepted revocation still survives failure followed by stale/same-revision
   grants.
5. **Existing gate ordering is unchanged.** `EntitlementManager.kt:29–44,47–57` preserves
   platform/build-policy checks and free entitlement gates; paid checks require trusted,
   non-future validation, usable cache validity, active grant state, and strictly unexpired
   grants. Boundary comparisons use `now < expiry`, so equality with expiry is denied. The
   implementation suites cover expiry, revocation, unknown source, offline validity, advanced
   recording dependencies and source exceptions. These are entitlement-layer checks only;
   they do not establish availability of an unimplemented platform feature or media behavior.

## Independent bounded regression selection

`docs/m10a/evidence/IndependentEntitlementReviewTest.kt` defines four JUnit 4 tests under
`com.easysavevideo.m10a.review.IndependentEntitlementReviewTest`:

1. Snapshot copy/component sublists and list iterators reject structural mutation.
2. Explicit value methods and copy defaults match the baseline-shaped generated data class.
3. Product output iterators and known-empty/unknown mappings cannot affect later lookups.
4. Revocation high-watermark survives a source exception and stale/same-revision grants;
   a newer active grant expires at the exact boundary while free entitlement gates survive.

The selection is deterministic, local, finite, and complementary to the implementation tests.
The parent compiled it with the final core-model classes, `EntitlementManager`, `ProductMapping`,
and JUnit 4, and ran the above fully qualified test class.

### Verified execution evidence

- `evidence/independent-entitlement-host.log`: JUnit 4.13.2, `OK (4 tests)`; frozen source check
  passed for 539 inputs.
- `evidence/independent-entitlement/independent-toolchain-match.log`: all eight toolchain
  artifacts match the recorded reference by artifact basename and exact SHA-256.
- `evidence/independent-entitlement/independent-source-unchanged.log`: compiled-source, test,
  runner and toolchain-reference inputs passed their SHA-256 checks.
- `evidence/independent-entitlement/independent-entitlement-frozen-after.log`: all 539 frozen
  inputs were unchanged before/after execution.
- `evidence/independent-entitlement/independent-compiled-source-sha256.txt`: the reviewed
  `Entitlements.kt`, `EntitlementManager.kt`, `ProductMapping.kt` and independent test hashes
  match the exact files independently rechecked after execution.

The final main-tree aggregate was executed by the build owner and **passed**. This reviewer
read `evidence/aggregate-summary.log`, `evidence/junit-summary.json`, and
`evidence/strict-offline-aggregate.log`: 1,822 JVM tests, zero failures/errors/skips, and 87/87
strict-offline Gradle tasks executed successfully. Debug/release lint each recorded zero errors,
14 warnings, and six hints. `evidence/frozen-inputs-after.log` confirms 539 frozen inputs. The
build owner also reported all inherited host checks passing; the independent four-test results
above were directly rechecked. Device verification remains not run. This report update did not
rerun checks or change production source, tests, or runners.

## Acceptance limit

This review supports the immutable entitlement/mapping portion of M10-A contract preparation.
It does not claim full T-E01–T-E06 acceptance, real purchase/restore verification, callback
deduplication, refund processing, release-distribution acceptance, or device behavior. No
new store product, pricing, bundle, account, cross-store, offline-grace, or payment policy is set.
