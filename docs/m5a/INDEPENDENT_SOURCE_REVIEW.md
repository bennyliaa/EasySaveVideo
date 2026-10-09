# M5-A ordinary-library independent source review

Review date: 2026-10-06 UTC. Base: `2764311c1b1454b7217b2e7304ffa469f2fea517`.
Reviewed scope: A1 and the explicitly labeled A2 ordinary-library foundation. A3 export/share, thumbnails, capture-destination settings, bulk folder deletion and Vault are outside this implemented slice.

## Recommendation

The reviewed source is suitable for an **A1/A2 foundation development checkpoint**, conditional on the build owner's final aggregate passing against the accompanying production SHA-256 manifest. No unresolved critical/high finding was identified in this source review. This is **not** a full M5/A2/A3 completion statement, Android/device acceptance, independent cryptographic acceptance, production release approval, or security proof.

This reviewer read the requirements, implementation and test sources, made no repository edits, ran no Gradle task, and performed no APK/AAB assembly, device operation, ContentResolver operation, actual recording/export or real-media deletion. Review artifacts alone were written outside the repository. Reported build/test pass counts belong to the build owner; this review does not claim to have independently rerun them.

## Findings and disposition

### R1 — Oversized legacy blobs could enter CursorWindow before the bound check (medium; source fixed)

Earlier `AndroidSaveStorage.queryRecord`, `records`, and `Catalog.findOwned` projected the full blob and only checked its `length` after advancing the cursor. A corrupt oversized row could overflow CursorWindow before the Kotlin guard ran.

Current source uses length/row-id-only preflights and bounded secondary reads. `readRecordRow` also compares decoded task/idempotency/revision/state/commit identities with SQL columns. Migration projects bounded identifiers via CASE and bounds record/evidence fetches. New catalog strings are bounded by DDL plus validated domain values. Relevant code: `AndroidSaveStorage.queryRecord/readRecordRow/records/Catalog.findOwned`; `LibrarySchema.backfill/requireVerifiedPublication`.

Test-source evidence: host SQLite oversized-record/evidence rollback fixtures; actual bounded codec tests. Actual Android CursorWindow behavior remains NOT_RUN.

### R2 — Failed navigation exposed an old snapshot under a new query/destination (medium; source fixed)

Earlier `load` changed query identity while retaining the old folder/items. A later read failure cleared loading but restored interactive controls over obsolete content, including a move destination that had not loaded successfully.

Current `load` clears incompatible snapshots, failures/rejections clear READY and visible rows, `LibraryScreen` requires READY for interaction, and `confirmMove` requires a successfully loaded destination. `takeSelectedReadRef` additionally consumes only an exact active foreground selection. Relevant code: `AndroidLibraryRuntime.load`, `confirmMove`, `takeSelectedReadRef`; `LibraryScreen` interaction and preview callbacks.

Test-source evidence: failed folder navigation, failed page, failed move-destination, late query completion, background consent invalidation, single-use playback selection and repeated confirmation regressions in `LibraryRuntimeTest`.

### R3 — Fixed first-100 recovery order could starve later delete intents (medium; source fixed)

Earlier each recovery pass selected the same first 100 operations. If they remained blocked/failed, later intents were never attempted, including across restarts.

Current recovery persists `library_state.delete_cursor` before attempting IO, selects the next bounded range and wraps at the end. It does not mark deletion completed simply because the cursor advanced. Relevant code: `AndroidLibraryRepository.processPendingDeletes`, `DELETE_BATCH_SQL`, and the `library_state` DDL.

Test-source evidence: 205 failed synthetic intents processed through three bounded passes and reopened SQLite connections, using the production range selector. No full catalog Kotlin adapter or real filesystem durability claim is inferred.

### R4 — Terminal task history still blocked all new Save/Record work (medium; source fixed)

Storage was changed to cap unfinished work, but the Direct/HLS/Record UI paths still gated on the size of the recent-100 history snapshot. After 100 completed/failed tasks, new work stayed blocked. Recent-only history could also hide older unfinished tasks.

All three entry points now use separately queried `activeTaskCount`. `readTaskSnapshot` merges bounded unfinished tasks first with recent history and removes duplicate IDs. The final authority remains the transactional storage insertion count; the UI count is an admission hint. Relevant code: `AndroidSaveRuntime` Direct/HLS/Record admission, `readTaskSnapshot`; `SaveTaskCapacity`.

Test-source evidence: 100 terminal rows do not consume active capacity; 100 unfinished do; older unfinished tasks remain reachable past 100 recent terminal rows; duplicate recent-active entries collapse. Direct capacity copy now accurately says unfinished tasks.

### R5 — Nullable SQL CHECK expressions weakened live-state invariants (low robustness; source fixed)

SQLite CHECK permits NULL. The prior live-item creation-time and live-representation size/hash checks could accept NULL instead of rejecting an incomplete READY row.

Current DDL explicitly requires the live values to be non-null and requires width/height nullness to match. Test-source evidence covers NULL creation time, missing width, NULL size/hash, malformed hash and other state/length constraints. App writers already supplied these fields; this closes a schema integrity gap rather than a demonstrated normal-flow data-loss bug.

### R6 — Database/directory ownership release needed failure fencing (low robustness; source fixed)

The storage worker reported, and source inspection confirmed, that an exception after the helper opens but during initial delete recovery would release the directory lease without first closing the helper. A subsequent review also identified the unchanged normal-close `finally` path releasing directory ownership even when DB close threw. The failed-initialization object is not published and SQL transactions unwind, so the initial issue was a resource-lifetime robustness defect rather than a demonstrated concurrent-write exploit.

Final source uses the pure `StorageOwnerRelease` policy in both failed initialization and ordinary close. It closes the database first. A throwing database close prevents attempting directory release; either database-close or directory-close failure retains one strong database/directory owner pair and throws RESOURCE_RELEASE_UNCONFIRMED. New storage initialization checks the failure fence. Normal close sets the storage closed before releasing owners, so subsequent reads/writes fail; repeating a failed close checks its retained owner identity and keeps reporting uncertainty rather than falsely returning success. Existing active-media reader/writer guards still run before normal release.

Four pure JVM test sources cover successful order, database-close failure, directory-close failure and a failure before database construction. Host static guards verify both adapter call sites and the repeated-close fence. These are source/pure-policy checks; actual Android helper-close and OS file-lock exception behavior remains NOT_RUN. The final hardening is in `fdbbce3` and `b17d6b3`; it supersedes the earlier normal-shutdown caveat.

## Review of lifecycle and transaction boundaries

### Migration and rollback

- The v2 schema is additive to the existing Save database and uses the existing directory owner; it does not introduce a second database/publication window.
- `onUpgrade` only accepts v1→v2 and relies on SQLiteOpenHelper's enclosing version transaction. Backfill is row-id paged, length bounded and restricted to visible successful tasks; active and failed records are not republished.
- `LibraryPublicationEvidence` preserves legacy successful HLS/Record proof exactly, including historically unavailable fields. Strict new publication still requires current evidence. It does not fabricate an EOF or resume proof for an old success.
- Migration cross-checks durable ownership/index/receipt/evidence and read-only local existence/size/locator identity. Migrated time is `IMPORTED`, not a fabricated capture time. Old Save codec bytes remain unchanged.
- Missing/wrong-size/symlinked legacy media fails the upgrade rather than manufacturing READY metadata. A bad migration rolls back v1 metadata; successful v2 installation must not be called safely downgradeable to the old M4 reader.
- `verify-library-sqlite.py` extracts production DDL/selectors but uses an independent Python model for transaction sequencing, codec stand-ins and publication writes. Its rollback results are useful host SQLite contracts, not execution of Android SQLiteOpenHelper or the full Kotlin adapter.

### Atomic publication and non-resurrection

- `AndroidSaveStorage.compareAndSet(SUCCEEDED)` validates the owned provisional receipt, publishes `save_media.visible`, adds the ordinary catalog/representation, and changes the Save task in one SQL transaction. Failed task CAS or catalog insertion rolls the publication back.
- Read authority is current catalog lifecycle + exact item metadata revision + representation generation. Raw paths, names, source URLs and old receipt filenames do not become open authority.
- Old Save task previews resolve their historic receipt through the current catalog. Deleted/pending items cannot become READY just because old Save history still says SUCCEEDED.
- Terminal commit/verification/inspect-reconcile paths are fenced. Restoring an old staging file does not authorize the old completed task to recreate deleted media.
- The retained ordinary Save history still contains provenance/receipt details. Ordinary deletion is not a secure database erasure claim. Private provenance migration and SQLite remanence work remain explicitly deferred to Vault.

### Leases, release and deletion

- Shared `fileLock` serializes catalog mutations, representation resolution and acquiring read authority. Storage `this` monitor callers acquire `fileLock` in one direction; the new library callbacks do not acquire the storage `this` monitor in reverse.
- Registry callbacks run outside the registry monitor. Synchronous item-availability callbacks can reacquire the storage lock, and a per-item `deleting` guard prevents recursive delete completion on writer release.
- A committed DELETE_PENDING intent hides new queries/opens before any unlink. Existing read leases remain valid and prevent acquiring the destructive writer lease. Repeated operation IDs are fingerprint checked.
- Only generated canonical UUID locators in the owned ordinary media directory can be deleted. Symlinks, directory replacements, arbitrary display names and recursive cleanup are rejected.
- Unlink and directory sync precede the terminal catalog transaction. A crash after unlink but before DB completion retries the absent-file directory fence; unlink/fsync/SQL failure leaves a durable pending outcome. Completion clears the locator and ordinary item metadata, retaining lifecycle/idempotency tombstones.
- The preview keeps the storage input open until `MediaPlayer.release` actually returns. A throwing/hung player release retains the descriptor/read ownership; the timeout reports uncertainty and disables unsafe reuse rather than fabricating release. Native duplicate-descriptor semantics remain a device acceptance item.

### Bounded queries, mutations and UI

- Names/search are UTF-8 bounded and normalized with NFC plus locale-independent lowercase keys. Controls, invalid Unicode, separators, dot names and ambiguous sibling names are rejected. The implementation does not claim full Unicode linguistic case folding.
- SQL binds search as literal `instr` input. Queries return a bounded page, sort with stable ID tie-breaking, and bind continuation to canonical query plus catalog revision. Folder snapshots are globally bounded; cycles, dangling ancestry, subtree depth and stale revisions fail closed.
- Display names/folder moves are metadata operations. Opaque disk names do not change. Publication also checks collisions across the combined root folder/media name namespace and selects a bounded alternate generated name if needed.
- Empty-folder deletion rechecks revision and current contents transactionally. Nonempty folders are explained with counts and never silently recursively deleted.
- UI storage work stays off main on one bounded lane. New reads cancel queued predecessors; late running reads cannot publish into a newer generation. Mutations block repeat taps, preserve one accepted durable action through background/disposal, and do not close the shared storage owner.
- Delete consent binds the exact selected object including media representation generation. Navigation/background clears stale consent. Pending/failed deletes are labeled incomplete and recover through bounded retry; success is not inferred from a missing tombstone.
- Playback is local, read-only and user-started, with pause/restart/seek; foreground/surface exit closes the owner. Read-reference routing is validated again in storage. MainActivity stores only the ordinary route flag in saved state, not a live read capability.
- No new sensitive logging, network dependency, public provider or broad storage permission was introduced in the reviewed slice. Controls use the existing SkinKit visual adapter. Library/Vault gating remains free, with explicit unavailable Vault/export/thumbnail copy.

## Acceptance exclusions

All actual Android SQLiteOpenHelper upgrade/rollback/low-space behavior, process death and power-loss filesystem durability, ContentResolver/provider/grant behavior, MediaPlayer FD/native-release behavior, phone/tablet accessibility/layout, rotation/Home/Recents/surface timing and real large-media behavior remain **NOT_RUN**.

Export/share, thumbnails, configurable destinations, bulk folder deletion and all Vault authentication/crypto/private metadata/private playback are **NOT_IMPLEMENTED in this slice**. No APK/AAB was assembled or installed by this reviewer. No device/account/payment/publication/deployment or real-user media was used.

## Snapshot and evidence

- `reviewed-production-sha256.json` and `.txt`: the 21 changed production Kotlin source files reviewed in this slice, relative to the repository root, captured after R6 at production source commit `b17d6b34f398ee0285822780ad6d43eee591f8c0`.
- `reviewed-context-sha256.txt`: supporting old codec/protocol, build, static/host-test scripts and requirements/docs read by this reviewer. Documentation may subsequently change to record final results without changing production source.
- App/library and domain test sources were inspected for relevance. Exact executed JVM/compiler/lint/admission counts and logs must come from the build owner's final run against these production hashes.
