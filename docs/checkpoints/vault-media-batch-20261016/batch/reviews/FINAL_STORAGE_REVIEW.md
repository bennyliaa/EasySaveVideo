# Vault batch storage final verification addendum

2026-10-16. Independent read-only verification of the final bounded source delta and frozen central execution evidence.

## Final scope conclusion

The pending document-ID allocation bound is closed in source and host source-contract execution. The selected-folder English label is quantity-neutral. No open must-fix remains within this storage/lifecycle/batch SAF review scope. The final host checkpoint is **6364 PASS + 2 unchanged SKIP**, with production still disabled and Android device acceptance still outstanding.

### Exact small delta

Manifest: `final-small-delta/MANIFEST.json`, SHA-256 `29df9352cd75fe8f037167ec2d509115a1bbbf86a6b7af2c0294c185f58dd292`.

- AndroidVaultPhoneFiles.kt: `eae7733cea68a02c84027c84dd00721a43fa29ecbe9182e4f5f52cdb832fce0c`.
- English vault_runtime.xml: `3755c77fb9ca8fe37dfe40d0398130327450d8f41927594b5601f97e2fd60f2e`.
- VaultCredentialUiContractTest.kt: `28ed204cf131e3e0378da81d9fecf38658f0f59150ec7b159086e50bb073ea02`.

All three were independently compared with their immutable overlay and central target bytes. Source order is now UTF-16 length necessary bound, one UTF-8 size computation, single-ID byte limit, accumulated-size increment, then total byte limit. This prevents additional unbounded encoding allocation and avoids duplicate encoding without changing the accepted byte policy. The cursor's own String allocation is outside this guarantee.

The two new tests check source ordering/single allocation and English/Chinese formatted labels. They do not execute a real Android cursor. The existing ten assertions in their class remain present.

## Focused third run

Independent XML totals: **475 suites / 4,858 PASS, zero failures/errors/skips**. app has 4,442 tests; the 416 non-app tests retain the same-byte reuse label. Both app variants compile and app tests actually execute; exit 0 / BUILD SUCCESSFUL in 3m53s. The two new tests occur by exact name in VaultCredentialUiContractTest's 12/12 passing XML.

- focused-third-TOTALS.json: `7dd8aba9c6afed5e58d8742b9c1a8086a0284ee0faecf6f6ffa67a390d8fcaef`.
- focused-third.log: `7145521f096c1242a2833e4400dfc55d09863b8a8da95b3f6d1c4b7fc81396b5`.

## Full final run

Independent XML recomputation: **572 suites / 6,366 tests = 6,364 PASS + 2 SKIP**, zero failures/errors. All 13 module test tasks and both lint tasks actually execute in the final log. Exit 0 / BUILD SUCCESSFUL in 6m55s.

The two skips remain the original verification-local FrozenRecoveryComponentTest.authored80MutationsActualJsoupComponent and FrozenCorpusHostTest.same100FrozenBodiesActualStaticB. They are not counted as passes.

Both lint variants contain **85 warnings + 10 notes, zero errors**. Compared with full-first, exactly the English “items” PluralsCandidate warning disappears; no new warning is added. Seven UseKtx style suggestions and the existing progress-label plural candidate remain, with no suppression and no storage/security blocker.

## Final evidence identity

Root: `esv-runtime-copy-20261015/evidence/vault-batch-export`.

- FINAL_SOURCE_MANIFEST.json and FOCUSED_THIRD_MANIFEST.json have identical SHA-256 `3275a20ab4c83cd334db2b89567ad02242ac99e4b9e6946ff1def7f9f746d140`.
- FINAL_TOTALS.json and full-final-TOTALS.json have identical SHA-256 `bf0addab38b0fb28f675090c36063f8093261d289799714086b8678dccc2287b`.
- full-final.log: `3576f3e20892b719c26c502a197ff8ab93cc8295a33712f99f9701291eb0a74f`.
- Each full-final lint SARIF: `b75f79714c4f8167e07caa6d827593c66c2004f1618246b117de2775a70a5959`.
- LOCAL_SOURCE_MANIFEST.json: `37bf85b425e9bb2c53ebf9a7d2d3a215c12ed24d4a6e50618439a87bb44bd68e`.

The final local manifest has 2,746 unique paths. All **21 changed target hashes** match both the final manifest and independently recomputed current source bytes. This reviewer did not independently rehash all historical 2,746 file bodies. Earlier full-first SOURCE/LOCAL manifest copies remain separately archived; earlier reports and failed/OOM evidence are unchanged.

The conclusions remain bounded: fixed fake/pure host tests and actual central compilation/lint are established. Real SAF/provider permissions and failures, Android lifecycle/process-death, native close behavior, actual SQLite/power-loss durability, and device acceptance are not established by these runs. Absence-only cleanup deliberately retains present uncertain output; no native file-incarnation witness or universal concurrent external-replacement detection is claimed. No production-enable flag is changed.
