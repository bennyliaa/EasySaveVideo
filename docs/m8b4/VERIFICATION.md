# M8-B4 source verification

The final complete `scripts/verify-os-power-workspace.sh` run passed on 2026-10-07.
This is an ordinary Discovery development milestone, not Android or energy acceptance.

## Final main-worktree evidence

- **1,944 Gradle JVM cases**, with zero failures, errors or skips. This includes **39 new app cases** (30 lifecycle/freshness bridge, six latest-delivery, three presentation) and the full existing regression set. App JVM execution uses the debug unit-test variant; both actual application variants compile and lint.
- Strict offline dependency verification, nonincremental Kotlin compilation, and **87 of 87 actionable aggregate tasks executed**. No APK/AAB assembly was requested or performed.
- Debug and release lint each report **zero errors, 17 warnings and six hints**. This is not a warning-free result. Existing migration/resource/deprecation/toolchain notices remain distinct from correctness failures.
- Exact **74 admitted external runtime artifacts** remain unchanged. Platform-neutral core, SkinKit ABI, native/media, provider, ordinary destination, history, event, filter, reliability, entitlement and excluded-module isolation regressions passed in their applicable existing scope.
- All **seven distribution identities**, each with actual debug and release compilation, passed **15 host cases per variant**. Five invalid build values were rejected. Final default and payment remain unconfigured. These 210 host selections are separate from the 1,944 Gradle cases.
- The independent OS-signal review and the aggregate's repetition each pass **43 freshly compiled host/API-double/core-integration cases**. Source-wiring checks also pass. The actual Android controller/Compose integration is inspected and compiled, not executed on Android by those doubles. See `INDEPENDENT_OS_SIGNAL_REVIEW.md` for the final independent evidence identity.
- Inherited ordinary folder review remains **40 host cases** plus **26 independent SQLite cases / 271 on-disk rollback boundaries**. The existing implementation SQL verifier remains **33 cases** with 26 migration DDL statements / 27 transaction-fault positions. Existing A4a and other host suites also pass. These are separately reported, not added to Gradle totals.
- All **684 frozen inputs** retain exact bytes and Git executable modes before and after the full aggregate. Final manifest SHA-256: `b4e94cdeb78c9ad2b393ac9cb66abec3e7e542ed249c40d806b2921ed3b422a7`.
- The new scope gate protects all **3,603 baseline files**. Only five explicitly listed production integration files change; every prior test body, gate, script, evidence file, manifest and dependency remains byte/mode exact. New bridge/tests/current-scope wrappers are independently listed and frozen.

## Corrected findings and superseded attempts

The independent lifecycle stress test reproduced stack overflow in the first recursive registration reconciliation pump. The implementation was changed to an iterative pump before the source freeze; the same 10,000-open test passes. Its failed exploratory evidence is retained and clearly superseded.

Two early aggregate attempts passed their strict Gradle phases but stopped at inherited folder-review **wrapper metadata** guards: first the old A4a manifest baseline, then the old A4a/folder-only full input-membership set. Only the current M8-B4 wrapper's declared baseline/membership adaptation and its exact transformation verifier changed. Full exact membership equality and every historical SQL case body remain enforced. The corrected SQLite and independent-folder host preflights passed, and the **entire** final aggregate restarted from strict Gradle and completed. Superseded evidence remains in `evidence/superseded-aggregate-1/` and `evidence/superseded-aggregate-2/`.

## Evidence locations and remaining limits

See `evidence/aggregate-summary.log`, `junit-summary.json`, `lint-summary.json`, `os-power-admission.json`, `channel-matrix/matrix-summary.json`, per-suite logs, and the independent report. Git, source/bundle cold restores and private Library roundtrip identities belong to the separately finalized recovery receipt; this main run does not substitute for them.

Actual OS delivery/cleanup, Binder/main-thread responsiveness, OEM battery/thermal semantics, process lifecycle, device permissions, native UI, calibrated thresholds and energy measurements remain **NOT RUN / NOT VERIFIED**. No saving percentage is claimed. Both blocked feature reviews remain untouched, isolated and disabled. This milestone does not establish overall M8/M11 or product completion.

Raw Gradle/JUnit stdout retains its original trailing spaces and final blank lines, and the inherited capture-admission wrapper retains its byte-exact historical whitespace. The staged source/document whitespace check excludes these intentionally unchanged/raw evidence bytes; they were not reformatted to make an all-files cosmetic check pass.
