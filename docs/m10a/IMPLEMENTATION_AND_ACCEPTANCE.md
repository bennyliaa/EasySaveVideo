# M10-A — Channel-neutral preparation, not real purchase acceptance

Baseline: 7731c32da8ee821087605c5b9f9097a659b71ab5 (M9-A).

## Corrected immutable value boundary

EntitlementSnapshot previously kept its caller's List directly. A MutableList
could change grants without changing revision, bypassing the manager's same/older
revision filter. ProductMapping similarly retained external map and nested sets.

Snapshot construction now copies into a private ArrayList, validates that copy,
and exposes an unmodifiable List. Its grants are themselves immutable value objects.
The same protection applies to copy and component2/destructuring. Explicit equals,
hashCode, copy, components and constructor defaults preserve existing call sites and
value semantics. The class is no longer declared a Kotlin data class; reflection's
isData flag is deliberately not preserved. No serialization or binary-client ABI
promise is added. The manager's revision/high-watermark and FeatureGate policies
are unchanged; immutable snapshot outputs make its cached value safe to share.

ProductMapping snapshots the outer map and each nested entitlement set, then
exposes unmodifiable result sets. Unknown product IDs still yield no grants.
No SKU, pricing, entitlement bundle or free Provider allowance is chosen.

Twelve new core tests cover constructor/copy/return/component aliases, stable
manager revisions, same/old revision rejection after revocation, expiry, free
capabilities, value semantics, original validation and source failure isolation.
Four app tests cover outer/nested mapping changes and immutable/unknown lookups.
Before-fix snapshot tests reproduced five failures. These diagnostic failures are
retained separately from passing after-fix and final aggregate evidence.

## App-only store preparation

StorePurchaseBoundary extends the existing Core snapshot port. Purchase and restore
operations return a fixed StoreOperationStatus, carrying no snapshot, receipt,
transaction token, raw error or account data. States distinguish UNCONFIGURED,
UNAVAILABLE, CANCELLED, TIMED_OUT, PENDING, FAILED and VERIFICATION_REQUIRED. The last
state is explicitly unverified and cannot grant paid access. Status definitions
are preparation, not implementations of real pending purchases or verification.

The default UnconfiguredStoreAdapter has no products and canPurchase=false. All
Core snapshot/refresh/restore paths return the same unavailable revision-0 snapshot
with no grants or validation timestamps. App purchase/restore distinguishes a
stopped operation before returning UNCONFIGURED; cancellation takes precedence at
the deadline, matching OperationContext. No persistence, retries, grants, SDK,
account, network request or side effect is introduced.

Release BuildEntitlements explicitly uses this adapter as its EntitlementSource.
Debug retains its separately compiled fake source, with a separate unconfigured
store property. Existing purchase-unavailable notices stay unchanged. There is no
new purchase button, empty purchase screen, fake success or production unlock path.
Eight deterministic app tests cover the default, typed stop results, repeated
operations, free/paid gates and build assembly. They are run in both debug and
release; the release-only branch is not inferred from debug execution. The Android
plugin exposes no release unit-test task. The release selection therefore uses the
actual compiled release Kotlin/Java classes and DEBUG=false BuildConfig in an
exact8-toolchain standalone host, with compiled-input hashes checked before/after.
Those eight host tests are reported separately from the Gradle JVM total.

## Verification boundaries

The full inherited ordinary verification remains required: strict offline
nonincremental JVM/compile/lint/exact74, SQLite/DOM/media/Provider and all M8/M9 host
suites. Historical evidence stays unchanged. Current-slice copies of inherited
harnesses bind to the new freeze. Three inherited media-source gates exclude only
the three edited entitlement source paths; their remaining media guards and all
other protected bytes remain enforced. The M10-A source allowlist and new tests
cover those edited paths independently.

Ordinary independent review checks collection views and value behavior with four
additional deterministic tests; a separate source review checks store statuses and
variant assembly. Standalone host tests overlap/recheck source contracts and are
not added to the Gradle JVM count.

Final main-worktree results and exact final archive/restoration proof are appended
only after execution. No Android UI, physical phone/tablet, native GPU/codec,
process-death, OS signal/energy, real store purchase/refund/restore, receipt
verification, production backend or market acceptance is claimed.

## Preserved release facts and remaining decisions

- API31 minimum; phone/tablet Compose target; SkinKit contract/default and all
  exact74 admitted runtime artifacts unchanged.
- Release has no fake entitlement implementation or controls; default store has
  no products, no purchase capability and no grants. Free capability gates remain
  independent of store availability; this does not implement disabled Vault.
- No new SDK, permission, endpoint, account, key, service or dependency. No ESV
  APK/AAB/installation and no SkinKit repository work.
- Real M10 remains pending the ESV store-market choice and explicit product,
  verification, account/artifact and backend policy decisions. SkinKit's WeChat
  choice is not an ESV choice. Offline grace, refund/acknowledgment and commercial
  terms are not silently decided by this slice.
- Remaining Android/device and release work needs its own authorization. No such
  work, blocked M7-D/Vault work, or substitute review/probe is started here.

## Final main-worktree results — 2026-10-07 UTC

- Strict offline/dependency verification, nonincremental full rerun: **87/87 Gradle
  tasks executed, PASS**, including both app compile and lint variants.
- **1,822 Gradle JVM tests**, zero failures/errors/skips: **24 new** relative to M9-A
  (12 immutable snapshot, 4 mapping, 8 app store cases). Core-domain now 44 and app
  debug 871; all other module totals unchanged.
- Both lint variants remain **0 errors, 14 warnings, 6 hints**. Existing metrics/SDK
  and deprecation notices remain; this is not a warning-free build.
- Exactly **74** admitted runtime artifacts per variant, **1,046** protected inputs
  byte-identical to M9-A and fixed Commons main JAR **127** classes.
- All nine current source/admission gates, six inherited SQLite suites, DOM **11**,
  HLS EOF **81**, mux conservation **15**, local-reader/editor-release hosts pass.
- Provider host **342**, Discovery **12**, origin/history **34**, media **47**,
  power **24 per debug/release variant**, filters **29**, inherited independent
  reliability **49**, ordinary isolation **6**, and SQLite close/reopen across
  **four fresh processes** all pass on current source.
- New independent entitlement selection **4/4** and actual release-bytecode store
  selection **8/8** pass. The latter verifies generated DEBUG=false plus exact
  release input hashes; it is a standalone host, not an unavailable Gradle release
  unit-test task. All standalone selections are separate from the 1,822 JVM total.
- All **539** frozen source/resource/script inputs remained unchanged. Manifest SHA:
  `296f86cf251509b539dc3671819ecb6c6c3a15831358e877e993dc10aaf9fb8d`.
  Standalone compiler/runtime/JUnit exact8 hashes match their admitted reference.
- Final commit/tag, archive hashes, tracked bytes/modes/refs, and the two full
  cold-restored aggregate outcomes are recorded in the external restore receipt
  after execution. Main-worktree success alone is not a cold-restore claim.

**渠道无关准备，非真实 M10 购买验收。** No real payment/channel/backend or new release
work is started by these results.
