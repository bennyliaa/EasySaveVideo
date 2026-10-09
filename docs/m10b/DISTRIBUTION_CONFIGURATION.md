# M10-B — Explicit distribution identities, payment still unconfigured

Baseline: `4a8f02e7d515b17e478abdce2957fd9d3401ca34` (M11-A factual documentation;
last prior runtime verification at M10-A `bdf3138`). This is a bounded preparation
slice for the user's selected six-market direction: Google Play, Huawei, Xiaomi,
OPPO, vivo, and Tencent MyApp. Payment strategy remains undecided.

## Build contract and app composition

The single Gradle property `esv.distribution.channel` accepts exactly:

- `unconfigured` (default when omitted)
- `google_play`
- `huawei`
- `xiaomi`
- `oppo`
- `vivo`
- `tencent_myapp`

Example, compilation only:

```
scripts/gradle-workspace.sh --offline --dependency-verification strict \
  -Pesv.distribution.channel=huawei :app:compileDebugKotlin :app:compileReleaseKotlin
```

Unknown, blank, differently cased, and whitespace-padded explicit values fail during
Gradle configuration. Values are validated against the fixed whitelist before being
placed in generated Java source. No environment-variable fallback, installer/store
probe, network lookup, region inference, user preference, or runtime setter selects
or changes identity. The property can also be supplied through standard Gradle
project-property mechanisms; it is build metadata, not a runtime security assertion.

`DistributionChannel` is an app-only immutable enum. Its runtime parser maps any
unrecognized string to `UNCONFIGURED` defensively. `BuildDistribution.configuration`
reads the actual generated `BuildConfig.DISTRIBUTION_CHANNEL` once. Both debug and
release `BuildEntitlements` compose that configuration and use its store directly.
Every identity constructs precisely the existing `UnconfiguredStoreAdapter`, exposing
no products, no purchase capability, and the same unavailable empty snapshot behavior.
Release `source` still aliases `store`; debug-only fake entitlements remain debug-only.
The enum does not enter Core. An enum channel is not proof of an installer or account.

This deliberately uses the existing debug/release variants rather than introducing
product flavors, channel SDKs, extra source sets, new dependencies, channel-specific
adapters, app IDs, signatures or distribution artifacts. All six labels name the
Android application direction; Huawei is not a HarmonyOS NEXT native implementation.

## Scope preservation

Exactly three previous files change: the six-line build-identity block in
`app/build.gradle.kts`, and the import/configuration/store-alias lines in each
`BuildEntitlements` source set. A new admission script proves those exact deltas and
protects all other baseline tracked bytes and executable modes. New production and
implementation tests live only under the app's distribution package.

The original M10-A and earlier checks stay unchanged as historical-scope tools. New
M10-B copies of the inherited media gates exclude the newly edited build file only
from their old exact-byte comparison; the new gate independently checks that file
against the complete baseline plus the exact channel block. All remaining media
invariants and protected files are retained. The inherited purchase gate retains its
ordinary assertions with only the release assembly assertion adapted to the fixed
configuration-store alias. The new whole-baseline gate replaces its prior M9-A scope
allowlist, without relabeling historical verification as current.

## Verification plan and evidence boundaries

- Full strict-offline/nonincremental rerun of module JVM tests, both app compilation
  and lint variants, generated Java compilation, exact74 dependency admission,
  original Core/SkinKit/variant gates, inherited SQLite/DOM/media/Provider/Discovery/
  power/filter/reliability/entitlement host selections, and frozen input checks.
- Four implementation tests cover exact identities, invalid-runtime fail-closed
  behavior, all identities' unchanged store/snapshot semantics, and app composition.
- Independent bounded tests and source review cover only this benign distribution
  configuration and its wiring. No blocked candidate is investigated or reviewed.
- Seven actual generated identities × debug/release compiled classpaths run the
  bounded distribution/store host selection, with expected channel and variant
  supplied independently, exact compiler/runtime hashes, and production input hashes
  checked before/after. These are host checks, not Gradle release unit tests or
  Android/device checks. Repeated host invocations are separate from the JVM total.
- Five invalid explicit build-value cases must fail at Gradle configuration, and the
  matrix ends with an omitted property to restore and prove the safe default.
- Final source and full-Git-bundle backups are restored independently, checking tracked
  bytes/modes, refs/fsck and cold verification. Results will be recorded only after run.

No ESV APK/AAB, emulator/device, real store SDK/account, receipt/backend validation,
paid-download or IAP decision, real purchase/refund/restore, signing, upload or
publication is included. This does not establish store-rule acceptance, market SDK
isolation, entitlement portability, commercial terms, or completion of M10/M11.
MinSdk31/target36, all 74 admitted artifacts, existing SkinKit integration, and
unimplemented/disabled Vault remain as before. The blocked M7-D candidate stays isolated.

Channel readiness research is a separate dated checklist with uncertain account and
submission status explicitly identified; it does not grant approval or authorize actions.

## Final main-worktree evidence — 2026-10-07 UTC

- Strict offline, dependency-strict, nonincremental full rerun: **87/87 Gradle tasks
  executed**, **1,826 JVM tests**, zero failures/errors/skips. Four new app JVM cases
  are added to the prior 1,822. Both compile/lint variants pass; each lint variant
  still has **14 warnings and 6 hints**, with zero errors.
- Exact **74** external runtime artifacts remain unchanged in both variants.
  The new scope gate protects **2,717** prior tracked files and executable modes;
  only the three enumerated exact build/composition deltas are permitted.
- All ten current admission gates, six SQLite suites, DOM 11, HLS EOF 81, mux 15,
  local-reader/editor-release hosts, Provider 342, Discovery 12, origin/history 34,
  media 47, power 24 per variant, filters 29, independent reliability 49, isolation
  6, four fresh SQLite processes, and independent entitlement 4 pass.
- Every one of **seven identities × two actual compiled variants** passes the
  15-case distribution/store host selection (8 existing store, 4 implementation,
  3 independent). The initial default selection also passes 15 per variant. These
  repeated standalone selections are **not added to the 1,826 JVM total**.
- Five invalid explicit Gradle values fail configuration as expected. Switching
  through all six selected destinations and then omitting the property regenerates
  and proves the `unconfigured` default in both variants.
- All **559** frozen source/resource/runner inputs remain unchanged. Manifest SHA-256:
  `19f17eb4495f4ac795764169979cef51b2b3a2f327373f6815ebca895cf6f602`.
  Host toolchain hashes match the eight admitted reference artifacts; per-variant
  production input hashes remain unchanged before/after each selection.
- The independent ordinary review found no source correctness blocker in this
  bounded configuration. It does not establish store SDK isolation or real payment.

Final commit/tag, source/full-bundle SHA-256 hashes, complete tracked bytes/modes/refs,
and both independent cold-restored full aggregate plus complete channel matrix
outcomes are recorded in the external restore receipt **after execution**. Main-tree
passes above are not themselves cold-restore or Android/device claims.
