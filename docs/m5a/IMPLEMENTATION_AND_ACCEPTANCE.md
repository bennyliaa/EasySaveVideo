# M5-A ordinary-library foundation development checkpoint

Base: `2764311c1b1454b7217b2e7304ffa469f2fea517` / `m4-record-dev-2026-10-06`.

Status: implemented and locally verified development foundation. Final development tag: `m5a-library-dev-2026-10-06`. It is not full ordinary-library completion, M5 completion, device acceptance, or a release. Recoverable WIP: `7725265fda62722677c86b8fdfa8d4f66846a74a`.

## Scope

- A1: platform-neutral catalog contracts, bounded naming/query/folder policies, per-item read/write ownership, ordered Save DB migration, and atomic Save-success/catalog publication in the existing database and storage owner.
- A2 foundation: SkinKit ordinary-library entry points, bounded list/search/sort, logical folders and media rename/move, details, manually started read-only playback with pause/restart/seek, and explicit irreversible local-delete confirmation followed by durable deferred unlink.
- Read authority uses an opaque item ID, metadata revision and representation generation. Disk names remain generated IDs. Renaming and intra-library folder moves change logical metadata only.
- Historical successful Save records remain truthful provenance, not permanent claims that the original representation still exists. Ordinary deletion clears library display metadata and representation location; v5 Save provenance and operation replay records remain, so this is not secure metadata erasure. The confirmation explicitly discloses retained task history. Published reads consult current catalog lifecycle. Deleting an item must never replay its Save task or revive an old staging file.
- Legacy media is labeled imported/discovered, with no fabricated capture date. New publications receive their actual publication time.
- No new external dependency is admitted. Android 12/API31 minimum, phone/tablet, closed-source and all seven SkinKit categories remain unchanged.

## Explicit remaining ordinary-library work

This slice does not enable thumbnails, public MediaStore/SAF export, system sharing, bulk folder deletion, configurable capture/save destinations, or a recycle bin. The UI must state unavailable functionality honestly. There is no button that reports a simulated successful export or a plaintext file as private.

Thumbnail generation needs a bounded in-memory cache keyed by exact item/generation, an independent bounded native-resource owner, exact published read leases retained until native release, deletion/session invalidation, and failure behavior that cannot stall the library command lane. No arbitrary-path or remote thumbnail fallback is permitted.

### A3 export lifecycle handoff

The existing `MediaReadRef` and storage-owned read stream are the source boundary. A3 must add a durable export operation journal, independently from Save success, before creating external output. The journal needs an opaque operation ID and source item/generation, explicit user-authorized destination/grant identity, bounded phase/outcome, output ownership, cleanup status, and idempotent retries. It must not log display names, paths, URLs or query strings.

- MediaStore: insert app-owned `IS_PENDING=1`, copy bounded bytes from one exact read lease, flush/close and verify count, then publish `IS_PENDING=0`. On cancellation/failure remove only the operation's owned pending row. Never modify the original or unrelated public media. Recovery must distinguish pending output from published output.
- SAF: launch a user-selected create-document flow; a returned `content:` grant is a capability, not arbitrary-path authority. The provider has no general atomic/pending guarantee. Report output or partial-output cleanup failure honestly; retain the original. Do not persist grants or overwrite unrelated documents without the relevant user choice.
- Share: create a bounded ordinary export copy in a narrowly scoped provider namespace. Use one-use/expiring operation identity, MIME/type metadata, explicit read grants and grant cleanup; no `file:` URI or broad FileProvider root. Grant revocation/TTL denies future opens, but cannot prove a recipient's already-open descriptor is closed. A lease-aware descriptor owner and verified cleanup strategy are required; disclose that recipients may retain a copy.
- All three must preserve the original on failure, avoid broad storage permissions, separate cancellation from success and cleanup pending, prevent duplicate output from repeated taps for the same operation, and allow a new intentional export operation.
- JVM fake-port tests and actual ContentResolver/SAF/provider/device tests are separate. The latter remain NOT_RUN until explicitly authorized.

## Vault remains a later slice

No credential setup, key creation, encryption, recovery/reset semantics, biometric flow, secure private viewer, private thumbnail cache or encrypted metadata migration is enabled. A generic catalog lifecycle is not privacy protection. Vault remains free; no paid entitlement is introduced.

A later privacy migration must retire sensitive copies in `save_tasks.record`, `save_owned.evidence`, `save_media`, ordinary catalog rows, and relevant operation fingerprints, replace terminal provenance with an explicit redacted representation, and address actual SQLite remanence. The old v5 Save codec remains compatible here and is not coerced into an invalid empty-success record. A downgrade must preserve the newer reader/schema; installing the old M4 reader is not a safe rollback.

## Verification

Final strict offline aggregate (`evidence/strict-offline-final.log`):

- Debug and Release compilation pass; strict lint has no errors/fatals. Each variant retains 9 warnings and 4 state-boxing hints, with no detector disabled.
- 805 JVM tests, zero failures/errors/skips: core-domain 32; library 55; recording 71; Save 86; HLS 40; theme 9; discovery 94; Commons 48; app 370. Core-model has no standalone tests.
- The app's new tests cover 23 library presentation/runtime cases, 15 publication/filesystem/ownership-close cases, 4 seek-policy cases and 5 history-capacity cases. These execute pure policies and fake scheduling/ports, not Android native APIs.
- 14 host SQLite contracts execute exact production DDL/selectors and model version transactions, publication rollback, pending recovery and 205-intent fairness. Kotlin codec and production filesystem policies are separately covered by JVM tests. The host SQL runner does not execute SQLiteOpenHelper or CursorWindow.
- Existing 81 HLS EOF fixtures, 15 actual pinned MP4 mux/extract host round-trips, legacy pinned-reader probe, discovery SQLite host contracts and 11 synthetic browser detector checks pass.
- Exactly 72 admitted external runtime artifacts in both variants; no new external dependency and no duplicate runtime classes. New library dependency lock state records existing compiler/JVM/test artifacts only.
- Independent source re-review closes all six findings, including both initialization and normal-shutdown uncertain ownership. All 21 reviewed production source hashes match the final source. Existing Direct/HLS/Record proof and v5/evidence-v3 codec files are unchanged from M4.

Evidence summary: `evidence/verification-summary.json`. Build-log copies normalize trailing whitespace only; original raw logs are retained with local recovery materials. Intermediate failure logs are diagnostic history and not final acceptance evidence. Android/device gates remain NOT_RUN. No APK/AAB assembly, install, real permissions, actual recording, real user-media mutation/export, account, payment, publication or deployment is part of this work.


### Storage and recovery limits

Queries return at most 100 media items (UI page 50), with catalog-version and query-bound continuations. Folder count is bounded at 1,000 and depth at 16. Names are NFC-normalized with locale-independent lowercase sibling keys, maximum 255 UTF-8 bytes; search input is at most 512 UTF-8 bytes. SQL uses bound literal substring search. Fields and BLOB projections are bounded before CursorWindow retrieval where the existing versioned readers are involved.

The same storage lock serializes catalog-version reads, result pages and new Save publication. Upgrading v1 checks exact succeeded task/owned/index identity and original opaque, read-only regular-file identity/size, retaining historical proof semantics. A missing, symlinked or mismatched original blocks the upgrade and rolls it back rather than inventing a playable item. No original media is changed during backfill.

Only nonterminal work counts toward the existing 100-active-task budget. The task center merges all bounded unfinished tasks with the latest 100 history entries, so old interrupted tasks remain reachable after more than 100 successes. Terminal history and idempotency records are retained; a new retention policy is not silently introduced.

Confirmed deletion first persists a tombstone and durable operation. New readers are refused; active read/native owners retain their lease. Physical unlink and directory sync precede completion/metadata clearing. Failure remains pending. Recovery uses a persisted rotating cursor, so a failing first batch cannot indefinitely starve later confirmed operations. Neither timeout nor grant expiration is proof of native descriptor release.


### Frozen recovery materials

The final Git bundle, source archive, SHA-256 list and independent cold-restore receipt are prepared outside this repository after freezing the development tag. A separate cold build verifies those exact restored sources against the existing approved offline toolchain/cache. Its result is recorded in that external receipt; a package, device or release is not implied.
