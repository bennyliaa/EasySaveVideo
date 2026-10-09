# Host SQLite evidence boundary

Run from the repository root:

- `python3 scripts/test-discovery-sqlite.py`
- `python3 scripts/test-discovery-history-sqlite.py`

The retained historical suite pins the complete six-table v1/v2 schemas independently
of the current adapter, executes the source-extracted rights ALTER, and preserves all
prior BLOB/null/no-op-upsert, corruption/size/type projection, transaction rollback,
row independence, cleanup, wide-row and disk-reopen checks. Its historical v1→v2
runner is deliberately separate from the current adapter's v1/v2→v3 migration.

The history suite executes the target's eight CREATE TABLE statements, seven ALTER
statements, Seen-group index, migration metadata SQL and real host SQLite transaction
operations. Old v1/v2 input DDL never derives from target v3 DDL. Stored header test
bytes retain the unchanged prefix of each frozen genuine old-codec fixture, replacing
only its full result/page pool with zero counts, as the historical SQL adapter stores
headers separately from rows. The script pins each original binary SHA-256. These are
independently derived host headers, not a current checkpoint with its version patched.

Coverage includes both upgrades and fresh/upgraded schema equality; 12 v1 and 11 v2
injected migration failure locations with reopen; 17 corrupt/unsupported-header,
rights/alias and protected-capacity refusal cases; unsupported database versions;
atomic migration pruning; deterministic opposite-insertion-order retention; full
transitive alias-group eviction; current-profile/all-profile Seen clear and revision
barriers; one-provider/query progress reset; local statistics with no writes; event
column monotonicity; protected profile/checkpoint/alias/query capacity; cancellation
and faults after pruning before commit; mutation sequence exhaustion; and recovery.
A seeded 1,000-operation host model commits 990 bounded mutations and rejects 10
impossible protected-group mutations without changing committed state. Automatic
pruning preserves surviving profile recency and the global mutation sequence.

Limits: migration loops, header decoding/encoding, event merging, controls and the
retention algorithm are explicit Python models with relevant Kotlin source guards.
The SQL engine and its transaction/reopen behavior run for real. Neither script
executes AndroidDiscoveryStore, SQLiteOpenHelper, the Kotlin planner/codec, Android
coroutines, CursorWindow or an Android device. Exact Kotlin tests are separate.
A synthetic media sentinel plus source separation checks are not device-media or
Vault verification. No provider or network request occurs. Closing/reopening a host
database is not Android process-death, disk failure, or power-loss acceptance.

`host-historical-rights.log` and `host-history-sqlite.log` record this run. The latter
includes the adapter and planner source SHA-256 values at execution. A later source
change requires rerunning both scripts; broader compile/lint/review evidence is owned
by the aggregate verification workflow.
