# Vault controlled playback — final runtime/storage execution review

Date: 2026-10-16 UTC. Reviewer scope: same-owner runtime/read lifetime, host integration, revocation and real-close/unknown accounting. Independent read-only source/evidence verification; this reviewer ran no builds, platform fixtures, device operations, authentication or credentials.

## Result

**The reviewed source scope and central JVM/compile/lint checkpoint pass, with no remaining scoped blocker. Production entry remains disabled.** Real Media3/native/Surface/audio/Android storage/security acceptance remains pending.

This final addendum preserves and follows the prior source, focused-execution and failed-full-run reports. It does not relabel those failed lint runs or convert source/fake-port tests into device evidence.

## Exact tested source and final documentation

Build source is `esv-runtime-copy-20261015/playback-build-source`; the older `esv-runtime-copy-20261015/source` was not the playback build target.

- `EXPERIMENTAL_OPTIN_SOURCE_MANIFEST.json`: SHA-256 `27584d1eefa5dac1baefed35958ff59d7cf6c015a9bb89e5268ea6d6bd78e5d7`. Independently rehashed all **26 tested target files**, with matching bytes and sizes.
- Final `FINAL_SOURCE_MANIFEST.json`: SHA-256 `431f709588b28e89896085b360321bfafd282631d20ca517f8720aa738d22b80`. **28/28** matching files; all 26 tested entries are unchanged. The two additional entries are reviewed post-build documentation only: `docs/vault/CURRENT_IMPLEMENTATION_STATUS.md` and `docs/vault/VAULT_RUNTIME_SOURCE_ADMISSION.md`.
- Final `LOCAL_SOURCE_MANIFEST.json`: SHA-256 `8ab5cd4c2e9f22b043894811ca4e1742a3dddd8af34a725d1187f24c51842de9`. Independently rehashed **2764/2764** current files. The tested source contained 2763 files; final documentation added one file and updated one existing file. No post-test production code change is implied by that count.
- `AndroidVaultRuntimeEntry.kt` retains literal `ENABLED: Boolean = false` and its construction guard.

Runtime remains SHA-256 `13a7fd712e842608b6f7b73fc988b94c1233508c4d626081b40fe596e7fc2b11`; the DataSource target is `9d5fd30590a97b67ffef727b0f67ef60abe31a3514d5b6dbe312c739886512f5`. Its four AndroidX opt-in annotations were independently proven removable to recover the exact previously reviewed DataSource bytes. Platform annotation corrections were covered by the parallel platform reviewer; the final integrated byte checks include that target.

## Independently recalculated execution

Frozen evidence root: `esv-runtime-copy-20261015/evidence/vault-playback`.

From every `full-third-xml` suite, including testcase-count checks:

- **579 suites, 6442 test cases = 6440 PASS + 2 SKIP**, zero failures/errors.
- 13 module totals agree with the frozen central totals: core-model 7; core-domain 120; ui-theme 17; discovery 338; save 467; recording 127; library 289; hls 40; commons-provider 416; searxng-provider 25; verification-client 44; verification-local 34 including 2 skips; app 4518.
- The two unchanged skips are `FrozenRecoveryComponentTest.authored80MutationsActualJsoupComponent` and `FrozenCorpusHostTest.same100FrozenBodiesActualStaticB`. Neither is a new playback skip.
- Actual production-runtime/fake-port coverage: `VaultPlaybackRuntimeTest` **22**, `VaultPlaybackDataSourceTest` **9**; host coverage: `VaultPlaybackWiringTest` **6**, `VaultPlaybackPublicationTest` **5**, `VaultPlaybackHostSourceContractTest` **9**. All **31 + 20** pass.
- The parallel platform suites also appear green: retirement-ledger **21**, platform source-contract **4**. These are JVM/state/source evidence, not real player/renderer/audio retirement.
- The final log shows actual execution of both debug/release Kotlin compiles and all 13 module test tasks. `BUILD SUCCESSFUL in 10m 9s`; exit **0**; 101 tasks, 35 executed and 66 up-to-date. Reused preparation tasks are not represented as rerun tests.

Both frozen `full-third-lint` SARIF files independently yield **86 warnings + 10 notes, zero errors**. Severity is resolved through each SARIF rule's default configuration when absent on the result. Debug and release lint both executed. All 158 original UnstableApi findings and the subsequent one ExperimentalApi finding are absent.

Compared with the preceding batch checkpoint, the one extra warning is `PluralsCandidate` for the non-authoritative playback position string `%1$d / %2$d seconds` in `values/vault_playback.xml:15`. This formatting/localization warning is not a read-authority, cleanup or publication blocker. Existing warnings remain visible; none was suppressed to obtain this result.

## Failed runs remain evidence

`full-first` remains exit 1 with 158 debug opt-in errors and release lint not run. `full-second` remains exit 1 with only the platform prewarming-call ExperimentalApi marker error and release lint not run. This reviewer independently recomputed the second run as 6440 PASS + the same two SKIP and 86 warnings/10 notes/1 error. Only `full-third` establishes the successful final aggregate/lint checkpoint.

## Scope of the conclusion

The reviewed runtime retains the existing root/read owner and gate; admission and final publication are revoked on the exact ticket/context, including independent host revocation. Pre-factory acquisition, actual reads, permits and Main handoff obligations remain tracked. Returned and late resources are adopted; close is one-shot, unknown release remains retained, and optional UI failure cannot suppress mandatory retirement. Read/file cleanup may proceed independently once the authorized read-side drain predicate is satisfied, without falsely declaring unresolved native resources released. Existing host mutual exclusion and ordinary continuous-recording behavior are preserved.

This result does not establish real Android Keystore/SQL/file durability, encrypted playback, Media3 codec/AudioTrack/Surface/Looper retirement, screen capture/privacy behavior, or OEM/physical interruption acceptance. The compiled Android fixed DataSource fixture is not recorded as executed. The design's full evidence matrix is not marked complete from JVM counts. No APK/device installation or production enablement occurred in this checkpoint.

## Artifact hashes

- full-third.log: `9e52fabd5640c1bc920be3a219bd470e686ef159131f7e72e3dc23c5b250934b`
- full-third.exit: `9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa`
- full-third-TOTALS.json: `5eeab0afcc07b4a628384fb90f15aa5c2373ba7f6fcd44232e451bf08defda73` (final documentation count included)
- full-third debug SARIF: `78e4c0cff57f347c1014216dda832d4771247bd49783943a2db44702ad5b3b11`
- full-third release SARIF: `78e4c0cff57f347c1014216dda832d4771247bd49783943a2db44702ad5b3b11`

Earlier reviewer reports are unchanged, including `VAULT_PLAYBACK_RUNTIME_HOST_FREEZE_REVIEW_20261016.md`, `VAULT_PLAYBACK_RUNTIME_HOST_FOCUSED_EXECUTION_ADDENDUM_20261016.md`, and `VAULT_PLAYBACK_RUNTIME_FULL_FIRST_AND_OPTIN_REVIEW_20261016.md`.
