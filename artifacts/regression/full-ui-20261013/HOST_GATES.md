# ESV current and historical host gates

Run date: 2026-10-13 UTC. Source: `${SOURCE}`.
All results below were freshly executed; no production code, test assertion, historical script, allowlist, runtime admission, live document/evidence file, or Gradle input was modified by this run. No Gradle task or device operation was run by this worker.

## Current results

- Current Save DB v8 gate: PASS, 14 tests, zero failures/errors, 344 rollback fault boundaries on host SQLite 3.53.1.
- Production video-presence script: PASS, 22 owned synthetic-DOM checks.
- Production save detector: PASS, 11 synthetic bounded-DOM checks with byte-identical production/script files and isolated writable output. The unmodified missing-output-path failure was separately reproduced in scratch.
- Recording admission: PASS, source guards only.
- Historical discovery v1/v2 rights SQL and recorded-segment SQL: PASS in their stated host scopes; recorded segments ran 10 tests.
- Final compiled boundary check: PASS after the separate build owner completed fresh Debug/Release Kotlin and lint and confirmed stable classes. Checks include core import separation, debug-only fake/demo classes, release bytecode references, recovered license payload hashes, original SkinKit JAR hashes, and seven-getter ABI.

The current v8 gate runs extracted production SQL and an explicitly source-checked Python dispatch/transaction model on temporary on-disk SQLite. It covers fresh v8 equivalence after supported upgrades 1–7, fault rollback, foreign keys, reopen/unsupported-version preservation, lazy editor ownership, and directory-batch constraints/interruption SQL. Versions 2/4/6/7 are explicitly composed synthetic baselines; they are not recovered user databases. v1 real published-media backfill, Kotlin codec execution, Android SQLiteOpenHelper/runtime, native media, process death, WebView/real sites, and device behavior are not established by these host checks.

## Historical gates retained as non-passing

All 21 original admission scripts have been rerun unchanged, including the final class-reading boundary after compilation was stable. Of those 21: two scoped passes, 16 missing-Git-history blockers, two missing historical evidence blockers, and one stale-source assertion failure. All 14 older SQLite scripts were rerun unchanged: two scoped passes and 12 stale-source assertion failures. No failure was removed, bypassed, or labeled a pass.

The historical script bytes match the earlier regression audit for every script represented in that audit. Their exit outcomes match that audit, except the intentionally repaired isolated output directory allows the unchanged save-detector script to report its already-tested assertions normally.

### Exact persistent blockers

- `check-stream-admission.py`: missing `third_party/candidates/media3-1.11.1/artifact-inspections/androidx.media3__media3-exoplayer__1.11.1.json`.
- `check-tink-admission.py`: missing `docs/m5-vault-dependencies/evidence/candidate-import-map.json`.
- Sixteen Git-baseline checks cannot run because the source snapshot has no `.git` history. A newly initialized repository or invented historical baseline would not satisfy those gates.
- `check-library-admission.py:45` expects the old `onUnconfirmed()` string; current `OwnedFileStreams.kt` has the outermost-close `priorDepth==0` guard. The historical assertion still fails before its later Git check. This report does not weaken that assertion or substitute static text matching for behavior.
- `test-discovery-history-sqlite.py:397` expects checkpoint versions `1..3`; current source accepts `1..4`.
- Older save/library/export/folder scripts expect database target 5/6/7 and corresponding old upgrade bounds. Current `AndroidSaveStorage.kt:438,473` targets 8 and upgrades 1..7.
- The three historic editor gates additionally expect the former synchronized `edits` getter; current `AndroidSaveStorage.kt:80` delegates to initialization with diagnostics. The separate current-v8 gate tests the new lazy owner-monitor/source contract.

## Save-detector reproduction boundary

The original checkout still lacks `docs/m3a/evidence`. To avoid writing live evidence, this worker did not create that directory or run a successful output-producing invocation against the live checkout. Instead it copied only the unchanged test script and its sole production Kotlin input into `save-detector-isolated/`. SHA256 and direct byte comparison prove both copies match the live files. First invocation, with the output directory absent, reached the final report write and failed with ENOENT (exit 1). Creating only the isolated output directory allowed the second invocation to pass all 11 checks (exit 0). The assertions and production script were untouched.

Both JS suites remain synthetic DOM tests. They do not execute Android WebView, real sites, actual media, network requests, or user data.

## Input preservation and reproduction

1,839 files were SHA256-snapshotted before and after: zero added, removed, or changed within that inventory. Inventory includes source, historical scripts, docs, tests, dependency bytes, and build configuration; excludes path components `build`, `.gradle`, `.kotlin`, `__pycache__`, and `artifacts` because independent builds own their outputs. Python runs set `PYTHONDONTWRITEBYTECODE=1`; admission outputs use `ESV_EVIDENCE_DIR` under this scratch report directory.

`results.json` gives exact command, working directory, exit code, timestamps, script hash, source-scope classification, compact failure anchors, and stdout/stderr paths for each invocation. `input-manifest-before.json`, `input-manifest-after.json`, `input-preservation.json`, `save-detector-copy-proof.json`, and `previous-audit-comparison.json` retain verification detail. `run_host_gates.py` is scratch orchestration only; it does not edit any gate. Reproducing all scratch steps should use a fresh output directory because the first detector invocation deliberately requires the output directory to be absent.

No private owned corpus was supplied or inferred to pass. Windows/OPPO reproduction, Android/emulator execution, historic Git/candidate evidence recovery, and current-source native acceptance remain outstanding. This audit never operates a device and does not close those acceptance items.

## Invocation inventory

| Script/run | Exit | Classification | Cases/checks |
| --- | ---: | --- | ---: |
| verify-current-storage-sqlite.py | 0 | PASS_SCOPED | 14 |
| test-video-presence.mjs | 0 | PASS_SCOPED | 22 |
| test-save-detector.mjs--missing-output | 1 | OUTPUT_DIRECTORY_FAILURE |  |
| test-save-detector.mjs--isolated-output | 0 | PASS_SCOPED | 11 |
| check-capture-preview-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-destination-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-discovery-event-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-discovery-history-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-discovery-power-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-discovery-quality-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-distribution-channel-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-editor-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-fixed-reliability-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-folder-destination-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-library-admission.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR |  |
| check-os-power-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-provider-health-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-provider-maintenance-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-purchase-preparation-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-recording-admission.py | 0 | PASS_SCOPED |  |
| check-region-recording-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-repository-policy-admission.py | 1 | BLOCKED_MISSING_GIT_HISTORY |  |
| check-stream-admission.py | 1 | BLOCKED_MISSING_HISTORICAL_EVIDENCE |  |
| check-tink-admission.py | 1 | BLOCKED_MISSING_HISTORICAL_EVIDENCE |  |
| test-discovery-history-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR |  |
| test-discovery-sqlite.py | 0 | PASS_SCOPED |  |
| verify-a4a-editor-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR | 37 |
| verify-a4a-export-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR | 14 |
| verify-a4a-library-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR |  |
| verify-a4b-editor-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR | 37 |
| verify-a4b-export-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR | 14 |
| verify-a4b-library-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR |  |
| verify-destination-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR | 14 |
| verify-editor-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR | 37 |
| verify-export-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR | 14 |
| verify-folder-destination-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR | 33 |
| verify-library-sqlite.py | 1 | FAIL_HISTORICAL_SOURCE_ANCHOR |  |
| verify-recorded-segments-sqlite.py | 0 | PASS_SCOPED | 10 |
| check-boundaries.py | 0 | PASS_SCOPED | |
