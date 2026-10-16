# Vault auto-lock settings — final independent execution review

Date: 2026-10-16 UTC. Scope: final frozen source manifest, aggregate JUnit XML, build log and both lint reports. Review-only; this reviewer did not run a build, Android preferences, device, credentials or authentication.

## Final result

**The reviewed auto-lock settings source and central compile/JVM/lint checkpoint pass. No remaining scoped blocker.** The four-value configuration is connected to the existing runtime and settings store behind the disabled production entry. Real Android rendering, preference persistence/lifecycle and device timing acceptance remain pending.

Earlier source and focused reports remain unchanged:
- `VAULT_AUTO_LOCK_SETTINGS_RUNTIME_STORAGE_SOURCE_REVIEW_20261016.md`, SHA-256 `a926d57d2fdd5eb34f1135f8415ffeb1b516560a83bdf85ef0c4d34b98c6ba38`.
- `VAULT_AUTO_LOCK_SETTINGS_FOCUSED_EXECUTION_ADDENDUM_20261016.md`, SHA-256 `51543ab9d77b618e0321ad45c4d06809152c13bcaebd0ed966fbcae44c10a556`.

## Exact final bytes

Actual target: `esv-runtime-copy-20261015/auto-lock-build-source`.

- `FINAL_SOURCE_MANIFEST.json`: `d8faf40ed5e59cfcbd118ff2379b711e9f2add4f53d4c0b23154e8312cfa57ce`. **20/20** recorded sizes and SHA-256 values independently match: original 17 targets, two reviewed test-only adaptations and one documentation update.
- `LOCAL_SOURCE_MANIFEST.json`: `2dbe8658a780759134f5a08963f47a41821f30b31fe702b25c403f55f0b72681`. **2772/2772** files independently rehashed and matched.
- These target bytes did not change after the successful focused checkpoint. Production entry remains literal false. No extra schema/auth/provider owner was introduced.

## Independently recalculated aggregate evidence

Frozen evidence: `esv-runtime-copy-20261015/evidence/vault-auto-lock-settings/full-final-xml`, `full-final-lint`, `full-final.log`, `.exit` and `-TOTALS.json`.

Every XML suite was parsed, including a check that its actual testcase count equals its reported count:

**583 suites, 6477 tests = 6475 PASS + 2 SKIP; 0 failures, 0 errors.**

Module counts agree with the central totals: core-model 10; core-domain 120; ui-theme 17; discovery 338; save 467; recording 127; library 289; hls 40; commons-provider 416; searxng-provider 25; verification-client 44; verification-local 34 including two skips; app 4550.

The only skipped cases are the same pre-existing `FrozenRecoveryComponentTest.authored80MutationsActualJsoupComponent` and `FrozenCorpusHostTest.same100FrozenBodiesActualStaticB`. No auto-lock test was skipped.

All 13 module test tasks actually executed in this final run. Both Kotlin compilation tasks were up-to-date against the unchanged source already compiled successfully in the first focused run. This distinction is preserved rather than counting another double compile. Final log: **BUILD SUCCESSFUL in 8m28s**, exit **0**, 101 tasks with 42 executed and 59 up-to-date.

The 35 additive auto-lock tests and the original runtime/lifecycle/reducer and settings-protection regressions remain included. Runtime execution uses the production runtime with fixed fake ports. Writer and admission tests use the production pure boundary/helper plus source contracts; they do not establish Android SharedPreferences durability or mounted Compose behavior.

## Lint and preserved failures

Both debug and release SARIF independently resolve to **86 warnings + 10 notes, 0 errors** (using rule default severity when the result omits it). Both lint variants executed. Compared by rule/message/path with the previously successful playback checkpoint, there are **no added or removed findings**. Existing visible warnings were not suppressed.

The first focused run's four obsolete textual-shape assertion failures remain in its frozen XML/log. The two-test delta retained the original network/concurrency admission, save-owner merge order and checked-commit/warning protections; successful second focused and final aggregate runs establish their updated source contracts. No failed run has been relabeled successful.

## Remaining boundary

The result closes this approved source/host-JVM checkpoint. It does not enable Vault or validate real authentication, Android preference storage, physical interruptions, rendered settings interactions, lifecycle timing on supported devices, or the playback/native security gates. Documentation keeps these distinctions explicit. No device or real user-data action occurred in this review.

## Evidence hashes

- full-final.log: `86688f5c911bb3e4f60c66e0001f3bb8c21632b1cf7227ae4d2ca65715f54276`
- full-final.exit: `9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa`
- full-final-TOTALS.json: `91e16673a3f1e35c16374524b5e7b1e68501de8e1e4e359a3016a4bdfa98a288`
- debug SARIF: `a57ee273dd66c69a034d3c37b43e72111a673e410ee1f0bc8201a631b5839fd2`
- release SARIF: `a57ee273dd66c69a034d3c37b43e72111a673e410ee1f0bc8201a631b5839fd2`
