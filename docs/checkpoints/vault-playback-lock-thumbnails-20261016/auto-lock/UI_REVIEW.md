# Vault auto-lock settings: independent UI/source review

Reviewed 2026-10-16 09:52 UTC. Scope: Main's existing Privacy slot, exact callback admission/disposal, four-option UI, resource semantics, read-only runtime lookup, disabled production entry and source-test input registration. Runtime transition and serialized persistence correctness are covered by the separate storage/runtime review. This reviewer did not change source or run builds/tests/devices.

## Decision

No source-review blocker remains in this scope. Integrated Kotlin compilation, focused JVM checks, final full-module tests and both lint variants have completed within the evidence limits below. This does not claim real Compose rendering, Android lifecycle/input acceptance or production readiness. The production gate remains literal false.

## Exact reviewed inputs

- Design `work/vault-auto-lock-settings-design/DESIGN.md`: SHA-256 `1c1a114db0b09a5e6004b81e84fd9f0b5d51ae60f73f01893585807c17127ea2`.
- API `work/vault-auto-lock-settings-implementation/API_FREEZE.md`: SHA-256 `8334373eccd5499cf691bb5cad6194523f93e2a2e50091272d31f98fc3379473`.
- `IMPLEMENTATION_FREEZE.json`: SHA-256 `b74f929a6556fdbfe3811c9f13fea0c211ec91a56a95d003a4d5e7aec701e689`; all seven evidence records match.
- `TARGET_MANIFEST.json`: SHA-256 `ae5c89bee5624aae1f73ac5dad4eb222b326489501bc57a100296d69e5af61a6`; all 17 source/test/build/resource records match. The manifest contains all exact per-file hashes.
- MainActivity SHA-256 `efe3245c7bb235e3c84d932032e8321a1b21d348d3afc4a4d8cbb79e0cfb2fe2`; VaultAutoLockSettingsDetail SHA-256 `ec593eb60c0bbcd76e1616d1c9635ec23ab6b1607406d02711f2eee04485bbc3`; AndroidVaultRuntimeEntry SHA-256 `07f49d580ae31f5eafb3c6bb2b1524f75c5b2ff24fe3ded49a37a4b92da9600f`.

Manifest/evidence paths without a prefix are under `work/vault-auto-lock-settings-implementation/`.

## Verified source properties

- Main creates CapturedPrivateActions inside the composed Privacy slot and keys it to captured private mode, root publication, input capability, exact route occurrence, input epoch, input generation and retained runtime binding. Dispatch rechecks exact binding against both the Activity and read-only app lookup, exact route/root/epoch, current Privacy predicate, successful settings load, foreground/alive Activity and enabled gate. No latest-owner bridge or construction call is present.
- The existing Settings screen only composes `privacy()` for the active Privacy detail. Category removal, notices, compact category navigation and route removal dispose the section; its DisposableEffect closes the callback helper. Revisited sections cannot revive the old closed callback.
- Selection passes only the enum. The retained runtime is updated first, then its accepted value is merged into the latest AppSettings and existing persistence is queued. The UI does not retain a whole stale settings snapshot or claim that queued persistence has succeeded.
- Four existing enum options are rendered as radio rows with selectable-group semantics, 48dp minimum height, SkinKit text styles, no nested scroll, and child RadioButton onClick null. Availability and admission both disable interaction. Existing unavailable copy plus section-specific waiting/read-failure copy remain honest while the gate is false.
- English and Chinese files each contain the same eight nonempty keys. Text preserves confirmed leave-Vault/background anchoring, foreground return before expiry, and always-locked restart. No fifth option, playback exception, new timer, authentication, reveal or device operation is introduced.
- Source/resource contract inputs cover the new detail, writer boundary, core-model files and both locale files. Existing registrations cover Main, CapturedPrivateActions, Settings screen, settings persistence resources and all Vault Kotlin files, including the new read-only lookup.
- No VaultProtectedHost or playback-platform file is modified in this overlay. The lookup preserves the single disabled construction gate and cannot construct a runtime/store.

## Review correction and evidence limits

The draft source-order contract could pass when `changePolicy` was missing because `indexOf` returned -1. The frozen test now asserts both dispatch markers exist before comparing order, and similarly asserts the write-boundary resolver marker exists. Final source-contract test SHA-256 is `456fd7b67f810083cb81945c4780a4c4ab32d3fbc06c0e4e640662e14a1b7379`.

Author-produced `isolated-checks.log`, SHA-256 `d2fb635f98140d764e03c63e563b6278e3ed949049aa0ba8f70c521317744900`, reports 86 behavioral tests and six source/resource contracts passing. Admission tests execute the existing production disposable helper with fixed predicates; source contracts bind the checked identity conditions to Main. They do not execute the Main Compose tree, real category disposal timing, touch input, SharedPreferences or Android lifecycle. Integrated compilation, XML and lint results remain pending at this revision.

## Follow-on source-contract compatibility and status documentation

Reviewed 2026-10-16 10:02 UTC. The separate two-file test-only delta updates older SaveConcurrency/SaveWifi source strings for the added fourth field-permission argument, the additive startup settings-copy field and the relocated checked-commit boundary. Independent network/concurrency flags remain asserted. Startup owner merge still precedes save and ready publication, now with marker-existence assertions. The checked-write test verifies the real prefs edit chain returns `.commit()`, rejects `.apply()`, and verifies the shared boundary checks the returned Boolean. No production source change or weakening of these protections was found.

- `work/vault-auto-lock-settings-contract-delta/DELTA_FREEZE.json`: SHA-256 `5fffa0171363ef499e7c344375b29a6f18c41a27d75e6c266f40942253b11c15`; all six evidence records match.
- Its `TARGET_MANIFEST.json`: SHA-256 `6d7a362e97616667a2ec9ff9a0fe295c8dfc8e5d105a6e66c36440215fe39737`; both test files match.
- Its `isolated-checks.log`: SHA-256 `f683c428b8c276e5a57524e8139399ded3a0e2ca38ffb3ab405ca5bfa6b974df`; 13/13 pass. The initial isolated run lacked an existing recording-module class; the author supplied that classpath dependency without changing tests to bypass it.

The one-file docs-only status correction also has no review blocker after a successful central terminal result. `work/vault-auto-lock-docs/MANIFEST.json` SHA-256 is `46d03b3d1ee05df36aa43b7ddd79a62644ab4863cec928176a3f6981dc9ef01e`; target current-status document SHA-256 is `9fd1933fd698139a1c149742a546519096fc9cd506ab6b06370a3ce89e637ec0`. It updates only auto-lock source connectivity and the remaining-code list, preserving disabled entry and unexecuted real Settings rendering, Android preferences lifecycle and device-timing boundaries.

## Central focused verification, 2026-10-16 10:05 UTC

Independent XML recount confirms 486 suites / 4,969 tests with zero failures, errors or skips. The second focused run finished exit 0, `BUILD SUCCESSFUL in 1m 32s` (three tasks executed, 61 up-to-date). The first focused log shows actual Main/Compose Debug and Release Kotlin compilation; it subsequently failed four legacy source-string checks. The second run correctly reused unchanged production compilation and actually compiled/reran unit tests after the reviewed two-file test-only correction. It is not described as a new execution of both production compilers.

Final `esv-runtime-copy-20261015/evidence/vault-auto-lock-settings/FINAL_SOURCE_MANIFEST.json` SHA-256 is `d8faf40ed5e59cfcbd118ff2379b711e9f2add4f53d4c0b23154e8312cfa57ce`. All 20 records match actual `auto-lock-build-source` bytes: 17 implementation targets, two test-only compatibility files and the reviewed documentation update. A superseded manifest hash reflected an incorrect inherited source-root metadata path; the corrected manifest names the actual integrated root without changing target bytes.

The following evidence paths are relative to `esv-runtime-copy-20261015/evidence/vault-auto-lock-settings/`:

- `focused-first.log`: SHA-256 `e514036b9a576ca79ebf54282e106fdb17a9d8b6cf639d894f2a4e24b4340510`.
- `focused-second.log`: SHA-256 `ffa97dab85995acfe8c6d61e4965a9ec8f0e7c0a5d1cd7f290b70a24ceb19543`.
- `focused-second.exit`: SHA-256 `9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa`.
- `focused-second-TOTALS.json`: SHA-256 `533743d2c6aa35d9a1add828aab6a2a62968897bad34c8b381b61ef132ee936f`.
- `focused-second-xml/app/TEST-com.easysavevideo.app.ui.shell.VaultAutoLockSettingsActionsTest.xml`: five tests, SHA-256 `85d2c748611caa2a151a4e3007ab86a3cadda587849d3ff6cf1cc17175132c77`.
- `focused-second-xml/app/TEST-com.easysavevideo.app.ui.shell.VaultAutoLockSettingsContractTest.xml`: six tests, SHA-256 `158c5c6a051849be8c728a8c2b337d4fb3084c6dfd7380a2605de5cf8d3c6b15`.
- `focused-second-xml/app/TEST-com.easysavevideo.app.save.SaveConcurrencyWiringContractTest.xml`: seven tests, SHA-256 `5742ac5c5c0b806fdf7d7e2e6f24bccd57cbb5b066070933c2175e144d26d8b4`.
- `focused-second-xml/app/TEST-com.easysavevideo.app.save.SaveWifiWiringContractTest.xml`: six tests, SHA-256 `2a60bc6bb05b821cf27aaa47a698d7e7f4570b1c39701b1f8cb41c9d098dae42`.

The broader full-module/lint run is pending. Real Compose/Android rendering, preferences lifecycle, device timing and native/security acceptance remain NOT_RUN, and production remains disabled.

## Final full-run evidence verified, 2026-10-16 10:13 UTC

The final full run completed exit 0, `BUILD SUCCESSFUL in 8m 28s` (101 actionable tasks: 42 executed, 59 up-to-date). App unit tests and both lint variants executed. Production Debug/Release Kotlin compilation was up-to-date from the actual focused compilation already documented; no code delta was introduced afterward. All 20 final manifest records still match the tested build tree.

The frozen aggregate records 583 suites / 6,477 total tests: 6,475 passed, two pre-existing skips, zero failures and zero errors. The separate storage reviewer owns the independent aggregate recount. This review independently parsed its four scoped final XML suites: Actions five, AutoLock source/resource contracts six, SaveConcurrency wiring seven and SaveWifi wiring six, all passing without skip. Both SARIF files independently resolve to zero errors, 86 warnings and ten notes each, including rule-default severity handling.

Paths below are relative to `esv-runtime-copy-20261015/evidence/vault-auto-lock-settings/`:

- `full-final.log`: SHA-256 `86688f5c911bb3e4f60c66e0001f3bb8c21632b1cf7227ae4d2ca65715f54276`.
- `full-final.exit`: SHA-256 `9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa`.
- `full-final-TOTALS.json`: SHA-256 `91e16673a3f1e35c16374524b5e7b1e68501de8e1e4e359a3016a4bdfa98a288`.
- `full-final-lint/lint-results-debug.sarif` and `full-final-lint/lint-results-release.sarif`: each SHA-256 `a57ee273dd66c69a034d3c37b43e72111a673e410ee1f0bc8201a631b5839fd2`.
- `full-final-xml/app/TEST-com.easysavevideo.app.ui.shell.VaultAutoLockSettingsActionsTest.xml`: SHA-256 `2e19e8d4723b8cf78b0c04c492e7b4d1b0ac4c3328f40168f88b104d7a9e3ad3`.
- `full-final-xml/app/TEST-com.easysavevideo.app.ui.shell.VaultAutoLockSettingsContractTest.xml`: SHA-256 `72ae0e368527003a439d5d75f7c6444afff907e820fe5dce6da2bb5ecde3ccd2`.
- `full-final-xml/app/TEST-com.easysavevideo.app.save.SaveConcurrencyWiringContractTest.xml`: SHA-256 `c69b26cb669cf3b321f552dfa720cb2880b83b54a626dcb8750c99cf2eb4f105`.
- `full-final-xml/app/TEST-com.easysavevideo.app.save.SaveWifiWiringContractTest.xml`: SHA-256 `5af73eb19beccad44fdd02e03e183a5c4bc422fee149f06b6137d641b2b917a4`.

Final source manifest remains SHA-256 `d8faf40ed5e59cfcbd118ff2379b711e9f2add4f53d4c0b23154e8312cfa57ce`. Earlier pending statements in this chronological report are closed by this result. Real Compose/Android rendering, preferences lifecycle, device timing and native/security acceptance remain NOT_RUN. Production is still disabled.
