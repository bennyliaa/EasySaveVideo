# M5-A review and integration fixes

Independent source review: `INDEPENDENT_SOURCE_REVIEW.md`; exact 21-file production manifest under `evidence/reviewed-production-sha256.json`. Android execution remains outside this review.

- R1: Existing v5 record and owned-evidence reads preflight lengths before bounded second BLOB retrieval. Upgrade/read failures retain existing data rather than loading an unbounded CursorWindow first.
- R2: Failed navigation/page/destination reads clear obsolete interactive snapshots and readiness. A move requires a successfully loaded current destination. Tests cover failed folder/page navigation and destination reads, and single-use foreground preview selection.
- R3: Persistent rotating recovery cursor prevents the first 100 failing delete intents from starving later operations. Host tests exercise 205 pending intents across process/database reopen.
- R4: All Direct/HLS/Record admission hints use the separately read unfinished count. The task center merges bounded unfinished tasks before recent history so old interrupted work remains accessible after 100 successes. Storage is final admission authority; tests cover terminal-history capacity and unfinished visibility.
- R5: Live-row non-null/size/hash checks, paired dimensions and UTF-8/ID bounds reject malformed SQL states. Nullable FK-ID bounds also hold in fixtures that bypass FK enforcement.
- R6: Both failed initialization and ordinary close release the SQLite helper before directory ownership. Any throwing DB/owner close retains one strong failed-owner fence until process restart; repeated close remains unconfirmed. Four pure ordering/failure tests cover the close policy.

Integration review also preserved already-successful historical HLS/Record proof semantics during migration while rejecting active/VERIFYING/COMMITTING records and incomplete new publication. New publication still requires current strict verification, and all original core proof/codec sources remain unchanged.

The first aggregate overlapped the UI worker's last source/test edits, producing two stale-snapshot regression failures. Its diagnostic log is retained. The frozen UI rerun and subsequent full aggregates resolved them; that intermediate log is not acceptance evidence.
