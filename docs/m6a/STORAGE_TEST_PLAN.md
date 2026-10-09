# M6-A synthetic SQLite and storage contract checks

## Boundary of the evidence

Run `python3 scripts/verify-editor-sqlite.py` from the project root. The suite uses Python's host SQLite and temporary synthetic databases/files only. Production `EditSchema` SQL literals and production selector literals are extracted rather than retyped. The complete pre-M6 v3 schema is frozen independently in the fixture.

These checks do **not** execute Android `SQLiteOpenHelper`, Android cursors, the Kotlin storage adapter/codec, Media3, or actual media validation. Python file/protocol models are contract doubles, not Kotlin or Android integration results. Synthetic bytes and proof blobs are not evidence of playable MP4. Real filesystem durability, process termination, OEM SQLite versions, native resource release, decoding, audio/video synchronization, crop accuracy, and real-device behavior remain outside this host evidence.

## Migration safety

A legacy v1–v3 connection disables FK enforcement in `onConfigure`, before the helper's version transaction. The upgrade creates typed editor tables, copies all existing item columns into a replacement table, drops the old parent, renames the replacement, restores indexes, and checks the full FK graph before the transaction can commit. `onOpen` restores FK enforcement, verifies it is enabled, and checks the graph again. Fresh empty creation keeps FK enforcement enabled.

Using only `PRAGMA defer_foreign_keys=ON` is insufficient: on the tested host engine, dropping and recreating a referenced parent leaves a deferred violation counter even after `foreign_key_check` becomes empty, and COMMIT fails. The suite retains an executable counterexample. Production does not clear counters to suppress checks.

Migration fault injection covers transaction start, every extracted create/rebuild statement, the completed FK check, and user-version update. Each failure must restore the exact v3 schema, rows, version, references, and synthetic source bytes, including after reopening. Ready, delete-pending, and deleted sources; nested folders; library operations; and export journal/target/state rows are seeded. A preexisting orphan must prevent version advancement.

## Editor durability and ownership contracts

- Exactly one typed item origin: Save commit or edit operation, with durable FK linkage
- Bounded job phases, request/expectation/evidence lengths, cancellation bits, ordered source ordinals and trim ranges
- Original source identity/revision/generation and ordered recipe bytes survive catalog changes and reopening
- New edit jobs and successful outputs do not manufacture or rewrite Save records
- Independent-verification gate, cancellation fence, and atomic item/representation/job-success publication
- SQL publication failure leaves no visible partial output; already promoted candidates remain owned and recoverable
- Recovery never interprets leftover bytes or a journal phase as fresh verification and never auto-publishes unverified output
- Cleanup addresses only durable, exact, unpublished candidate names and never scans a directory or deletes input media
- Current source capabilities reject stale revision/generation and hidden/deleted representations

The test names and final console output describe which contracts actually ran. Existing library/export migration and publication suites remain separate regressions; this file does not replace them.

## Recorded host run

2026-10-06: 31 tests passed using host SQLite 3.53.1. The run executed 3 extracted editor-create statements and 6 rebuild statements, with 12 migration rollback checkpoints, 4 preparation rollback checkpoints, and 10 pre-commit publication failure checkpoints. Additional cases cover after-commit restart, bounded record/source/recovery selectors, current source size/hash/ref checks, typed origin constraints, cancellation, unknown or colliding paths, proof-bound final cleanup retries, and unchanged input bytes. This result is synthetic host-contract evidence only; Android/Kotlin storage integration remains NOT_RUN by this suite.

## Trim execution snapshot extension

A later 2026-10-06 run passed 37 tests. The six added cases execute the updated production DDL and extracted `EXECUTION_SQL` plus start CAS predicates. They verify all-or-none nullable execution fields, inward requested-range bounds, positive bounded frame counts, all five atomic start failure windows, source/job CAS failure rollback, ordered execution durations/frame totals after reopen, and rejection of swapped, missing, null, or tampered execution data. Original requested trim boundaries and recipe bytes remain unchanged. Source anchors also require the one shared `fileLock` and prohibit `@Synchronized` on the storage owner to avoid a second monitor. Expectation decoding and transaction orchestration in these additions are Python contract doubles, not Kotlin codec or Android execution.
