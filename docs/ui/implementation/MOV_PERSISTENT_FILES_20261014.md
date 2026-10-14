# MOV normalization: inactive persistent-file adapter

Date: 2026-10-14 UTC. Status: inactive source layer implemented, host/JVM regression and independent narrow review passed.

## Delivered scope

The storage owner now mints a one-shot fresh WRITING attempt for exact-owned raw and
output files, without reopening recovered WRITING work. The adapter uses directly
owned descriptors, fixed extents/identities, bounded positional IO, durable ledger
transitions and explicit release tracking. Finish produces OUTPUT_READY; adoption
requires a separately persisted Save proof and ADOPTING authority. No runtime caller
or MOV admission switch is added.

Every conflicting legacy entry point has a durable typed ledger fence: only ABSENT
permits the old path. PRESENT, including CLEANED, and UNREADABLE preserve material.
The fence is retained when the in-memory registry is empty. Previously escaped
legacy File/FD capabilities are not retroactively revoked; production activation
must prove the outer execution/local gate and release ordering.

## Evidence boundaries

Final forced 13-module regression completed successfully: 4,186 PASS and two
existing SKIP, zero failures/errors (4,188 registered). App: 2,768 PASS. Debug and
release Kotlin compilation and lint passed. Fresh SARIF reports have zero errors,
60/61 warnings and eight notes per variant. Runtime admission remains exactly 90
reviewed artifacts; original license payloads 99 and seven-getter ABI checks passed.
Host SQLite schema/migration suite passed 20 tests with 411 rollback fault boundaries;
this remains separate from actual Android SQLite acceptance. Final focused run passed 101 tests
with no failures, errors or skips (2026-10-14 11:26:02–03 UTC). The real pinned
Media3 integration runner passed six tests and exited 0, including explicit
assertions that the short-read and short-write paths actually ran. Independent
read-only review passed this inactive slice against 13 matching implementation,
test and runner hashes (review manifest SHA-256
`d1d686f9eeec82e1cddf45ff01062d97f78137259d7d1cae7c2152de1ea52485`). The focused tests exercise production file,
attempt, protocol and guard logic with fake syscalls and ledger ports. Source-wiring
contracts are static checks of AndroidSaveStorage integration. The pinned host
runner executes the admitted real Media3 muxer/extractor through production adapter
code, with deterministic fake file/SQL ports and Android host shims. None substitutes
for actual Android descriptor, fsync, SQLite, decode or release acceptance.

## Explicit activation blockers

Temporary descriptors and directory-enumeration cleanup can still hold the global
storage file lock while hash/fsync/open/close blocks. Only returned long-lived
source/sink/finalized handles have their bottom-level close outside that lock.
This can indefinitely block durable cancellation/recovery and unrelated storage.
Before activation, blocked-call tests must prove memory-only cancel/release checks
remain responsive, execution owners/claims/workers are not prematurely replaced,
and late returns retire exactly once after actual release. UI must never wait on
these operations synchronously. See MOV_PERSISTENT_FILES_DESIGN_20261014.md.

Typed final Android verification, proof-bound adopted handoff, Coordinator/runtime
wiring, crash recovery and real device/native tests remain open. Audio remains
rejected as an entire file until its independent timing/decode proof exists.

## Next executable work

Implement the independently approved typed native verification layer and exact
Direct tail/EOS helpers, still without enabling production MOV. Continue original
requirement coverage rather than treating this checkpoint or backup as completion.

## Validation commands and artifacts

- Shared locked wrapper, offline: all 13 module tests forced through
  `force-tests.init.gradle`, `:app:compileReleaseKotlin`, `:app:lintDebug`,
  `:app:lintRelease`, `:app:verifyRuntimeAdmission`. Debug compilation is an app-test
  prerequisite. Final process exit 0, BUILD SUCCESSFUL.
- Same shared build lock: `app/src/testSupport/mov-owned-files-host/run.sh` with
  the exact admitted cached artifacts, final process exit 0.
- `scripts/check-boundaries.py` and `scripts/verify-current-storage-sqlite.py`, both
  exit 0. No APK assembly or device operations.
- Sanitized per-module totals, task outcomes, current SARIF counts, host scope,
  reviewer hashes and source manifest: `normalization-persistent-files-20261014/`.

Source backup uses a normal incremental private development-branch commit. Its
verified commit identity is reported separately after expected-head update; this
report does not claim an unobserved GitHub CI result.
