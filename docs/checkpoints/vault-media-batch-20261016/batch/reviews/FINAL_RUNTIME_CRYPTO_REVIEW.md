# Vault batch export: final crypto/runtime review addendum

## Decision

The reviewed batch-export source and final local execution evidence are acceptable for this source-level scope. No outstanding crypto/runtime must-fix remains from this review. Production remains disabled. This conclusion does not replace the independent storage review or pending real-device/provider/cryptographic acceptance.

## Exact final source

- Central: `esv-runtime-copy-20261015/source`.
- Evidence: `esv-runtime-copy-20261015/evidence/vault-batch-export`.
- `FINAL_SOURCE_MANIFEST.json` / `FOCUSED_THIRD_MANIFEST.json`: SHA-256 `3275a20ab4c83cd334db2b89567ad02242ac99e4b9e6946ff1def7f9f746d140`. Independently recomputed all 21 current file hashes and byte lengths; all match.
- Final `LOCAL_SOURCE_MANIFEST.json`: SHA-256 `37bf85b425e9bb2c53ebf9a7d2d3a215c12ed24d4a6e50618439a87bb44bd68e`. Independently recomputed all 2746 listed current files; all match.
- The three-file final delta is separately reviewed in `VAULT_BATCH_FINAL_SMALL_DELTA_CRYPTO_RUNTIME_REVIEW_20261016.md` (SHA-256 `d53e73a8129e0394dcb9f517caeef639d064a0f63d2c5d8d3a31749778eeb12c`). Its before hashes matched the earlier first-full checkpoint; the final targets match the current source.

## Post-delta focused execution

Independently recounted the frozen `focused-third-xml`: 475 suites, 4858 tests, zero failures/errors/skips. The app's 4442 tests actually executed, including both added source/resource tests in the 12-test `VaultCredentialUiContractTest`. The other 416 core-model/core-domain/library tests were reused UP-TO-DATE. Both debug/release Kotlin compilation tasks actually executed successfully. The log records exit 0, `BUILD SUCCESSFUL in 3m 53s`, 18 executed and 46 up-to-date tasks.

- `focused-third-TOTALS.json`: `7dd8aba9c6afed5e58d8742b9c1a8086a0284ee0faecf6f6ffa67a390d8fcaef`.
- `focused-third.log`: `7145521f096c1242a2833e4400dfc55d09863b8a8da95b3f6d1c4b7fc81396b5`.

## Final full execution

Independently parsed the frozen `full-final-xml`: **572 suites, 6366 tests = 6364 passed + 2 skipped, zero failures/errors**. All 13 module test tasks actually executed in this run. Counts: core-model 7; core-domain 120; ui-theme 17; discovery 338; save 467; recording 127; library 289; hls 40; commons-provider 416; searxng-provider 25; verification-client 44; verification-local 34; app 4442.

The two unchanged skips are `FrozenRecoveryComponentTest.authored80MutationsActualJsoupComponent` and `FrozenCorpusHostTest.same100FrozenBodiesActualStaticB`. They are not passed tests.

The log records exit 0, `BUILD SUCCESSFUL in 6m 55s`, 26 executed and 75 up-to-date tasks. Debug/release production Kotlin compilation was UP-TO-DATE in this final full run, backed by actual successful post-delta compilation in focused-third.

- `full-final-TOTALS.json` and `FINAL_TOTALS.json`: `bf0addab38b0fb28f675090c36063f8093261d289799714086b8678dccc2287b`.
- `full-final.log`: `3576f3e20892b719c26c502a197ff8ab93cc8295a33712f99f9701291eb0a74f`.
- `full-final.exit`: `9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa`.

## Lint changes investigated

Both frozen final SARIF files independently resolve to **85 warnings, 10 notes, zero errors**. Severity is resolved from each rule's default configuration when a result omits its own level. Both files have SHA-256 `b75f79714c4f8167e07caa6d827593c66c2004f1618246b117de2775a70a5959`.

Compared with batch full-first by rule/severity/message/file, ignoring line shifts, there are no additions and exactly one removal: the `PluralsCandidate` concerning the former English “items” label. The final quantity-neutral wording resolves that warning without suppression. Relative to the completed single-item baseline, the remaining batch additions are seven `UseKtx` style suggestions and one quantity-neutral progress-label `PluralsCandidate`; these were read in context and do not establish new crypto or authorization defects. The provider ID allocation-before-bound issue found during that reading was concretely corrected in the final delta. This is not a claim that all pre-existing lint findings have been eliminated.

## Preserved boundaries

- Earlier first-run heap OOM, earlier first-full success, and their exact source checkpoints remain historical evidence; they are not relabeled as final-byte results.
- Batch scope retains the same owners, scalar COPY_OUT engine, immutable bounded plan, same-lineage proofs, durable run budgets, final-decision rules, original-media retention, and actual close/drain obligations reviewed previously.
- Pure/fake/source-contract successes do not prove actual Streaming AEAD positive/tamper behavior, actual root-AEAD envelope measurement, Android playback, SAF-provider behavior, or device acceptance. Those remain NOT_RUN where previously specified.
- The old dependency-only gate was not changed or executed by this reviewer. No key, credential, device, build, or test execution was performed by this independent review; only frozen evidence was read/recomputed.
