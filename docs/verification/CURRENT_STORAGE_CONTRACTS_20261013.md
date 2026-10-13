# Current storage, checkpoint, and reentrant-owner contracts

Date: 2026-10-13. Source baseline: `93a67ba5a4e07cb6c9b1b7d186c6145570db19e0`.
This change adds tests and a current-version host gate only. No production code,
dependency, schema, codec, runtime permission, original fixture, or historical gate
is changed. It does not claim that the older gates passed.

## Why a separate gate

The broad cloud regression found 12 historical SQLite scripts whose source anchors
expect Save DB v5/v6/v7, an earlier inline editor getter, or checkpoint readers
limited to v1..v3. The library admission script likewise expects the old unconditional
`onUnconfirmed()` source spelling. These failures are retained as evidence; changing
version strings or deleting the old assertions would not establish current behavior.

The exact distinction matters:

- Save storage is DB v8; Discovery storage is still DB v3.
- Discovery checkpoint readers accept v1..v4. Writers retain v3 for a null source
  configuration key with query authority true, and use v4 for the other three
  combinations. A v4 encoding of null/true is readable and canonicalizes to v3.
- Save record codec remains v6. Edit request versions remain 1/2, expectation 3,
  evidence 2; these are not the checkpoint v4 change.
- The lazy editor accessor delegates to `initializeEditStorage`, which still uses
  the same `fileLock` and only publishes the initialized instance after construction.
- File stream close uses per-thread recursion depth. A nested platform callback may
  return with the descriptor still open so that the outer native close can finish.
  Ownership remains retained until actual descriptor-closed evidence; only an outer
  returned close with no such evidence reports unconfirmed release.

## New checks and reproduction

1. `python3 scripts/verify-current-storage-sqlite.py`
   - 14 host tests and 344 transaction fault-injection locations.
   - Production-extracted DDL, exact current top-level and nested dispatch binding,
     foreign-key rejection binding, fresh schema equivalence, every supported
     v1..v7 upgrade, rollback, disk reopen, and preservation of seeded row identities,
     opaque bytes, sources, exports, destination links, and settings.
   - v8 directory batch constraints: active real grant references, immutable source/
     destination/operation identities, unique ordinals and child grants, 100-item
     ordinal bound, retained replay history, monotonic start/result state, stop fences,
     atomic failed child admission, and extracted interruption-recovery SQL.
   - Lazy editor ownership wiring is checked statically, not executed as Android code.
2. `./gradlew --offline :app:testDebugUnitTest --rerun`
   - `DiscoveryCheckpointCurrentContractTest`: 8 tests. Hash-pinned frozen v1/v2
     bytes, a separately constructed v3/v4 wire oracle, complete pool/pages/rights,
     all authority/sort combinations, strict Boolean bytes, malformed keys/lengths/
     UTF-8, every truncated authority suffix, appended bytes, unsupported v5, and
     backwards-compatible defaults.
   - `OwnedFileStreamReentryContractTest`: 6 tests. Two nested late-FD-close stages,
     outer-only failure reporting, original exception preservation/depth reset,
     retry, per-thread recursion isolation, and lock availability during platform close.
   - Existing real-JVM file/channel close tests remain in the full app suite.

Use the environment's admitted JDK/Android toolchain and serialized Gradle owner.
The checked cloud run used the pre-existing offline wrapper; no artifacts or dependencies
were downloaded. Host SQL emits its input SHA-256 values and explicit evidence limits.

## Historical fixture provenance

| Starting version | Actual input to this new host gate |
| --- | --- |
| 1 | Existing frozen `LEGACY` literal in `verify-library-sqlite.py`, read with AST literal evaluation only |
| 2 | Explicit synthetic pre-export prefix of the frozen v3 DDL |
| 3 | Existing frozen `V3_DDL` literal in `verify-editor-sqlite.py`, read as data only |
| 4 | Explicit synthetic v3 plus current edit job/library DDL and the independently frozen v4 source-table SQL |
| 5 | Existing `destination/frozen-v5-schema.sql`, labeled as frozen from a3a028d |
| 6 | Explicit synthetic v5 plus production v6 destination DDL |
| 7 | Explicit synthetic v6 plus production v7 folder DDL |

None is represented as a recovered user database. Historical modules are not imported,
patched, or run with their assertions suppressed. The tests seed synthetic rows only.
Production dispatch is independently pinned so Python cannot silently supply a missing
Kotlin `createSources` call or substitute for removed Kotlin FK rejection logic.

## Verified result

- New host gate: 14/14 passed, including 344 host-model rollback locations.
- Full app JVM task actually reran: 1,761 passed, zero failed, errored, or skipped;
  this includes all 14 new methods. The predecessor's 13-module 3,002-case run is
  separate evidence and is not described as rerun by this change.
- Debug and Release lint analysis/report tasks actually reran: each has zero errors,
  60 warnings and eight hints, unchanged from the predecessor. The boundary gate passed.
- Independent review found a false-positive risk in the first host model's nested
  dispatch binding. It was corrected; removal of either the production delegated
  table creation or FK rejection now fails the new gate. Five additional in-memory
  mutation checks detect missing item immutability, stopped-start, retained-history,
  active-grant, or FK-validation guards. No mutation was made to production files.

Evidence is under `artifacts/verification/current-storage-contracts-20261013/`.

## Limits that remain open

This is not Android SQLiteOpenHelper, Android/Windows native file-descriptor, device,
provider, permission, media-playback, or installed-APK acceptance. Host SQLite version
and platform behavior can differ from Android. Source binding is fail-closed static
checking plus explicit transaction models, not Kotlin/Android execution.

The v1 gate executes only empty/non-published backfill and the real bounded selector.
It refuses a published-success candidate rather than faking Kotlin proof decoding or
media validation. Existing JVM legacy codec/publication tests are separate; an actual
v1 database containing published media still needs native migration acceptance.

Foreign-key checks are modeled before commit; quick_check is performed after commit
and on reopen, matching the distinction in helper lifecycle. Fault-injection locations
are host-model locations, not proof of actual Android crash behavior. Seed rows cover
representative identities and states, not every historical state or large database.
Directory recovery checks execute stopping/selection SQL only; coordinator cancellation
and provider I/O remain separate. Old historical anchor failures and missing Git/
snapshot evidence are not erased or converted into passes by this new gate.
