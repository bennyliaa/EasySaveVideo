# Full cloud regression after the UI display slices

Source: `b0c5647e6759dd212423647742e4923c8d0dce93` on `development/cloud-followup-20261013`. Publication verified 30/30 changed blobs;
all thirty frozen files match this source snapshot. Main remains `ea87963dee631a498774f648c9a1dabd9f665b90`.
Whole remote-tree equality is not claimed; historical missing snapshot inputs remain missing.
No workflow/check/run/status entries exist for this commit; empty pending is not passing CI.

## Current executable result

All 13 enabled module test tasks were forced with task-scoped `--rerun`, together with both
Kotlin compilers, both primary lint analysis/report/entry tasks and exact runtime admission.
The unchanged serial wrapper/flock completed with exit 0 in 3m16s, 101 tasks (30 executed,
71 dependency tasks up-to-date). Each requested test/compile/lint task actually executed.

307 fresh XML suites contain **3,149 registered cases: 3,147 passed, zero failures/errors,
and two skipped**. App has 1,886 passed cases; ui-theme has 17 newly executed passed cases.
The two skips are the existing `FrozenCorpusHostTest.same100FrozenBodiesActualStaticB` and
`FrozenRecoveryComponentTest.authored80MutationsActualJsoupComponent`: ESV_OWNED_CORPUS is
absent. Their private 100/80 corpus acceptance is not passed or replaced by synthetic fixtures.

| Module | Cases | Skipped |
|---|---:|---:|
| core-model | 6 | 0 |
| core-domain | 44 | 0 |
| ui-theme | 17 | 0 |
| discovery | 307 | 0 |
| save | 95 | 0 |
| recording | 127 | 0 |
| library | 277 | 0 |
| hls | 40 | 0 |
| commons-provider | 247 | 0 |
| searxng-provider | 25 | 0 |
| verification-client | 44 | 0 |
| verification-local | 34 | 2 |
| app | 1886 | 0 |

Both lint reports contain 60 warnings / 8 hints / zero errors. Runtime admission verifies all
90 exact approved artifacts. The final compiled boundary gate passes after compilation.
All 1391 captured code/test/script/Gradle/third-party inputs are byte-identical
before and after; the independent host pass also checks 1,839 non-build inputs unchanged
during its run. Evidence and ledger documents are intentionally written afterwards; they are
not production inputs or a claim that every post-run document remains unchanged.
No production correction or weakened gate was needed.

## Host contracts and preserved incomplete gates

Current storage contracts pass 14 tests and 344 rollback fault boundaries on host SQLite.
They use production-extracted SQL and an explicit helper/transaction model; v2/v4/v6/v7
intermediate baselines are synthetic. This is not Android SQLiteOpenHelper execution or
real v1 published-media codec/media backfill acceptance. Existing JVM codec/ownership tests
remain part of the actually executed App suite, with their own bounded fixtures.

Synthetic-DOM video-presence checks pass 22 checks; the unchanged save-detector script passes
11 checks with byte-identical inputs in an isolated output workspace. Its missing-output-directory
failure is separately reproduced in scratch, not hidden or reclassified as production success.

21 historical admission scripts remain: 2 scoped passes, 16 blocked by missing Git history,
2 blocked by missing candidate evidence, and 1 old library source-anchor failure. Fourteen
historical SQLite scripts remain: 2 scoped passes and 12 old source-anchor failures. All old
script bytes are unchanged and match the earlier audit. These are not unconditional passes.
The historical library failure expects an old release predicate, while current source adds the
reentrant priorDepth guard. No old assertion was weakened to produce green output.

## Remaining acceptance

APK assembly/signing/install, device/emulator, real WebView/native media, live locale/layout/
TalkBack and PPT comparison are NOT_RUN. Original 00–08 Markdown is available, but the PPT
binary is not. The UI device checklist is prepared separately and entirely NOT_RUN.
OPPO edit STORAGE_UNAVAILABLE/share INITIALIZATION_FAILED and earlier ANR/second-recording
acceptance gaps remain open. Windows symlink-fixture setup/guard evidence is unchanged by
Linux cloud success. History TTL, payments, deferred import/Vault/rules and other user choices
are not decided by this regression. Conditional nav-diagnostics was not enabled; no release
unit-test task was invented. These results are not full product/visual/release acceptance.

## Evidence

`artifacts/regression/full-ui-20261013/VERIFICATION.json` records exact commands, execution
and boundaries. `JVM_CENSUS.json` holds every suite count/time; `TASK_STATUS.json` preserves
all task observations. `COMPLETE_EVIDENCE.zip` contains all 307 sanitized JUnit XMLs, lint XMLs,
complete Gradle and host logs/results, input manifests and publication receipt. Each archive payload
entry has a hash in `ARCHIVE_MANIFEST.json` and the external `ARCHIVE_SHA256.tsv`.
Historical failure details are in HOST_GATES.md and HOST_GATE_SUMMARY.json. Independent
review and final evidence hashes accompany the package. No source change is included in this
evidence-only publication.
