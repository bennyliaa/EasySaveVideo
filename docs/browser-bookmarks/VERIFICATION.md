# Combined bookmark source verification

The complete `scripts/verify-bookmarks-workspace.sh` run passed on 2026-10-07 UTC.
The final main freeze has **4,317 inputs**, SHA-256
`ec8b4adb5be000b19783b6d80e0959acdde64ac1b1f34463234281394009656a`, all unchanged before and after acceptance.
The scope baseline is reviewed hotfix source
`0c178320cd78391bfedd5f9846e3ee873f0f5fce`; that side-branch source is retained
alongside its identical cherry-pick and the original bookmark checkpoint.

## Results

- **2,035 Gradle JVM cases**, zero failures/errors/skips, including all 1,959
  hotfix-inclusive baseline cases and 76 new bookmark cases. App JVM execution
  uses the debug unit-test variant. Both actual app variants compile and lint.
- Strict offline verification and nonincremental compilation, **87/87 aggregate
  tasks executed**; no packaging or device operations.
- Debug/release lint each: **zero errors, 17 warnings, seven hints**. The seventh
  hint recommends an unboxed Compose long state in the added browser revision;
  no warning-free or native-performance claim is made.
- Exact **74 admitted external runtime artifacts**, unchanged minimum API31 and
  target/compile API36, core platform boundaries and SkinKit ABI/default contract.
- **37 independent host tests**, **23 source assertions** and a separate
  reproducible integration checker pass. All 14 approved hotfix paths and patch
  bytes/modes exactly match their source commit; all bookmark source/test bytes
  and the three integration deltas exactly match the reviewed checkpoint.
- **30 verification-infrastructure tests** independently exercise positive and
  negative scope/freeze/archive controls. These are not added to the JVM total.
- All **seven distribution identities**, each with real debug/release compile
  and **15 host cases per variant** (210 separate selections), pass. Five invalid
  build values fail closed. Final default/payment are unconfigured; no Commons
  contact or runtime source access was embedded/enabled by this source run.
- Every preserved inherited admission/SQLite/host gate in the driver passes:
  ordinary destination/folder/library/export/editor, HLS/media/recording,
  provider/repository/health, Discovery history/origin/media priority/power/
  filters/OS signals, reliability/process-reopen, entitlement and previously
  authorized excluded-module isolation. This does not expand their original
  host-only scope or reopen blocked feature reviews.
- All **4,070 baseline files** are byte/mode protected except the three explicit
  bookmark integration files. The final freeze also protects every new source,
  review input and 203 archived partial-run output files plus their manifest.

See `evidence/aggregate-summary.log`, `junit-summary.json`, `lint-summary.json`,
`bookmark-admission.json`, `channel-matrix/matrix-summary.json`, per-suite logs,
`frozen-inputs-before.log`, `frozen-inputs-after.log`, and the independent report.
Cold restoration and private backup receipts are completed separately after the
source commit; main-worktree evidence does not stand in for those checks.

## Preserved interruption and limitations

The first 2,020-case source-only run was deliberately interrupted at the parent's
request to prioritize an existing-preview media shutdown defect. Its main phase,
pre-matrix hosts and Google Play checks passed; Huawei compilation stopped with
exit130. Those results are preserved under `evidence/interrupted-2020/`, whose
exact files/modes/hash manifest and original checkpoint blobs are now gated. The
new combined aggregate restarted from strict Gradle and completed every step.

Native bookmark UI, keyboard/phone/tablet, accessibility, Activity rotation,
process kill, WebView callback ordering and actual AtomicFile/power-loss behavior
are **NOT RUN**. The parent's later limited code2 media-close retest is recorded
separately in `CURRENT_MILESTONE_STATUS.md`; the source checker's earlier pending
label is its review-time boundary, not an updated device-status feed.
No bookmark APK was built, no overall PR-25/product completion is claimed, and
any future bookmark APK must increment versionCode beyond 2.

Raw inherited/generated logs retain their original whitespace. Cosmetic checks
exclude those intentionally exact evidence bytes; no historical output was
reformatted to manufacture a clean all-files whitespace report.
