# First-layer SearXNG verification infrastructure

This is a new verification family for baseline `9e9dea537c5f6e269adfb6791b47829eb4c440de`. Historical scripts, tests, fixtures, and recorded evidence are immutable. No historical driver or frozen manifest is rewritten or presented as acceptance of new source.

## Acceptance boundary

A successful final run means current-source/host development acceptance for the first-layer SearXNG increment. It does not mean Android-device, live-endpoint, native media/UI/lifecycle, energy calibration, M0, or complete two-layer discovery acceptance. No APK/AAB assembly, installation, account/payment, publication, or deployment is performed. Blocked implementation candidates are not executed, reviewed, retried, substituted, or integrated. Baseline integrity hashing is not candidate review.

## Exact scope

`scope_support.py` pins all 4,644 baseline regular Git files. Every baseline file is checked for exact bytes and Git executable mode. Only the 12 named ordinary production/Gradle integration paths may differ in content; their Git modes cannot change. Historical test/evidence paths are not exceptions. The final freeze additionally fixes the exact current bytes of all 12 permitted edits.

`scope-allowlist.json` has a sorted explicit new-source list and repeats the exact integration-edit set. Verifiers never infer new permissions from a worktree diff. Unexpected or missing source fails closed. Final source additions must be individually reviewed and added to this list before final freeze.

Every file under `docs/searxng-discovery/`, regardless of suffix, is included in the frozen input membership, except the exact frozen-manifest path and the explicitly reserved generated-output directory `evidence/runs/`. Executable files and ordinary executable-review source suffixes are prohibited in that output directory. Review scripts, test fixtures, reports intended as inputs, and documentation therefore belong outside `runs/`; generated logs/results belong inside it. No general `.log`, `.json`, or unknown-suffix exclusion hides a surprising input.

All baseline files remain frozen, including historical evidence. The manifest is deliberately not its own input. Its SHA-256 is recorded before the aggregate and compared after it, so replacing the manifest mid-run is rejected.

## Identity-only wrapper adaptations

`verify-inherited-wrappers.py` compares 24 new wrappers against their immutable ordinary historical originals using only declared transformations. `inherited-wrapper-mapping.json` records original/adapted SHA-256 values and executable-mode preservation; the verifier checks that record against recomputed mappings. The historical M8-B4 and bookmark wrapper-integrity checks also run unchanged.

Preserved without modification: inherited behavioral test cases, SQL/DOM/media fixtures, source-selection expressions, standalone compiler invocations, compiler/runtime/JUnit exact-eight-artifact checks, static source assertions, distribution-channel cases, and test-count assertions.

Declared identity-only changes:

1. Current manifest, wrapper self-references, nested-wrapper destinations, and output paths point to the new verification family.
2. Folder SQLite retains its exact membership check and existing delegated membership function; its baseline identity points to the current baseline.
3. The inherited OS report identifies the new complete scope guard. Every OS wiring, permission, compiled-class, and no-simulation assertion stays unchanged.
4. Three inherited media admission guards formerly required `settings.gradle.kts` to remain equal to older milestones. They now retain their full protected-path comparison from the historical milestone to the approved baseline and add the complete current byte/mode scope guard. The protected list and behavioral/source assertions are unchanged. This explicitly permits only the reviewed first-layer Gradle integrations, whose final bytes are frozen.
5. The combined bookmark/hotfix source-identity verifier still checks original history, patch identities, reviewed hotfix/bookmark content, and bookmark integration scope. For the explicit current integration paths only, its historical identity comparison terminates at the approved baseline. Every current file is separately checked by the new scope guard and final freeze. This is reported as baseline/current-scope identity, rather than pretending changed integration bytes equal the old snapshot.

A behavioral or source assertion that no longer holds remains a failure. No static assertion is silently relaxed to accommodate production changes.

### Explicit additive checkpoint-payload v4 adaptation

The first frozen aggregate exposed an obsolete literal codec-version guard in `scripts/test-discovery-history-sqlite.py`: it required `require(version in 1..3)` even though current checkpoint payloads add version 4. The original script remains byte/mode unchanged. Production was not restructured to satisfy an old text search.

`inherited-discovery-history-sqlite.py` is a mapped current copy with these exact declared transformations: its repository-root depth changes for its new location; the supported checkpoint-payload tuple adds 4; the source guard requires the actual `1..4` range plus ten explicit v4 encoder/decoder/tail-validation source fragments; the empty-header model consumes a bounded optional configuration key and strict authorization Boolean before retaining the existing exact-EOF check; and the original main adds the new test invocation/report. Every legacy `test_*` function body is identical, with per-function hashes recorded in the mapping.

`checkpoint_v4_sqlite_cases.py` adds 42 checks covering present/absent configuration keys, true/false authorization, malformed/truncated/oversized/non-hex/invalid-UTF8 keys, invalid Boolean bytes, missing/trailing tail bytes, future payload versions, unchanged genuine legacy v1/v2 and synthetic v3 headers, v4 revision-only mutations, exact tail preservation, rollback, and disk reopen. These are independent Python header models and real host SQLite transactions. Kotlin codec execution remains in the separately required JVM tests.

SQLite's database schema remains 3. The original unsupported SQLite schema 0/4/99 rejection cases are unchanged. Checkpoint payload version 4 must not be confused with SQLite schema version 4.

`inherited-process-reopen.py` redirects only the historical history-model import to the current mapped wrapper. Its four fresh-process seed/rollback/commit/reopen stages and every function body remain unchanged and hash-checked. Old provider identity fixtures continue to exercise the fixed Commons schema1 regression, without restricting separately admitted current providers.

The first interrupted aggregate's outputs are preserved. Additive-model preflight outputs are under `runs/preflight-r3/`. This wrapper/model change invalidates the prior final freeze and requires a new explicit freeze plus a complete aggregate rerun.

## Environment and execution

Only the serialized build owner runs Gradle. Infrastructure preparation and selftests do not run it.

Required existing local tools: Bash, Python 3.9+ with standard-library SQLite, Git, Node, the previously recovered JDK 21 launchers, Android SDK platform 36, and the already admitted Gradle/Kotlin/runtime/JUnit cache. The JDK, SDK, cache, and build-home defaults match `scripts/verify-bookmarks-workspace.sh`; use `ESV_TOOLCHAIN_ROOT`, `JAVA_HOME`, `GRADLE_USER_HOME`, `ANDROID_HOME`, `ANDROID_USER_HOME`, and `ESV_BUILD_HOME` to select the already authorized toolchain. Standalone review wrappers also honor `ESV_REVIEW_REPO`, `ESV_REVIEW_CACHE`, and `ESV_REVIEW_JAVA`. Export `PYTHONDONTWRITEBYTECODE=1` to avoid generated Python cache files among frozen review inputs; the aggregate exports it itself.

Preparation after source/review inputs settle:

1. Review and explicitly update the sorted `new_source_files` entries in `scope-allowlist.json`. Do not generate an acceptance allowlist blindly from all files in the worktree.
2. Run `PYTHONDONTWRITEBYTECODE=1 python3 docs/searxng-discovery/evidence/test-verification-infrastructure.py`.
3. Run `PYTHONDONTWRITEBYTECODE=1 python3 docs/searxng-discovery/evidence/verify-inherited-wrappers.py` and `PYTHONDONTWRITEBYTECODE=1 python3 docs/searxng-discovery/evidence/check-searxng-admission.py`.
4. After the build owner establishes the expected total from the development run, explicitly freeze with `PYTHONDONTWRITEBYTECODE=1 python3 docs/searxng-discovery/evidence/freeze-inputs.py --write --expected-jvm-tests N`. The old baseline has 2,035 JVM tests; N must be greater and must include the new module/app/discovery tests. This writes a count expectation and final input manifest. No expected final total is guessed by the runner.
5. Run `scripts/verify-searxng-workspace.sh`. Default output is `docs/searxng-discovery/evidence/runs/final/`; `ESV_EVIDENCE_DIR` may select another dedicated output location.

The final runner refuses missing freeze/count records, stale bytes/modes/membership, missing module XML or zero-case modules (except the exact documented core-model NO-SOURCE task), mismatched total, failures/errors/skips, or lint errors. A final source, fixture, wrapper, or documentation change requires an explicit new freeze and rerun. Review output must not overwrite a frozen input.

## Full inherited acceptance workload

The immutable core-model module has no test sources and its historical test task is NO-SOURCE with zero results. Only that exact module may remain zero: the current run must explicitly report `> Task :core-model:test NO-SOURCE`, have no test sources, and have no stale XML. All other ten modules must have nonempty XML and positive test counts.

The aggregate executes strict offline dependency verification with rerun tasks and Kotlin incremental compilation disabled. It runs all 11 JVM test modules including `searxng-provider`, debug/release Kotlin compilation and lint, release Java compilation, runtime inventory, and runtime admission. Exact 74-entry admitted external runtime closure is checked independently of the additional local project module; both variants must satisfy Gradle's exact-byte admission.

It retains all ordinary inherited SQL, DOM detector, HLS EOF/mux/reader, editor mux release, admitted provider, discovery, origin, media, power, filter, reopen, isolation, reliability, entitlement, bookmark, OS-signal, and folder/destination host regressions. The new independent SearXNG integration review runs inside the full freeze interval as well. The unchanged seven-channel matrix compiles debug/release Kotlin and Java for `google_play`, `huawei`, `xiaomi`, `oppo`, `vivo`, `tencent_myapp`, and the safe `unconfigured` default; each runs runtime admission and compiled distribution host checks. All five invalid channel values must be rejected. Final build state returns to `unconfigured`.

## Infrastructure negative controls

`test-verification-infrastructure.py` creates isolated temporary Git repositories only. Its 42 cases cover historical bytes, Git modes, deletion, symlinks, wrong baseline count, undeclared/missing sources, allowlist expansion, unknown review suffixes, hidden executable inputs, missing freeze/count records, stale allowed edits, changed new-source mode, membership expansion, count tampering, duplicate rows, mid-aggregate refreeze, attempted overwrite of a frozen input, the exact core-model zero exception, missing/empty other module results, total mismatch, failed/error/skipped cases, negative counts, and unexpected XML roots. These controls are infrastructure checks, not production tests or device acceptance.
