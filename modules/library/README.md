# Ordinary MediaLibrary contracts

Platform-neutral Kotlin/JVM module. It has no Android, storage-path, network, Store, Provider, Save,
Vault, or runtime-library dependency. The Android adapter is responsible for authentic publication,
SQL atomicity, filesystem ownership, persisted operation replay, and deleting only app-owned files.

## Values and bounds

- IDs are opaque `[A-Za-z0-9_-]{1,128}` tokens, never paths or display names. Root folder is `null`.
- Display names are NFC, at most 255 encoded UTF-8 bytes. `LibraryNames.validate` returns the NFC
  spelling; stored models require that spelling. Sibling keys are NFC of `Locale.ROOT` lowercase.
  This is deterministic normalization, not full Unicode linguistic case folding.
- Names reject separators, controls/format characters, unpaired surrogates, Unicode noncharacters,
  line/paragraph separators, `.`/`..`, blank names, and leading/trailing whitespace.
- Search is a bounded (512 UTF-8 byte) literal local-name substring. SQL adapters must bind arguments
  and escape SQL wildcard characters; query text is never SQL syntax.
- Query pages contain at most 100 READY items. Continuations bind folder, canonical search, sort,
  direction, page size, exact catalog version, and offset. Every catalog mutation invalidates old
  cursors. Opaque item ID ascending is the stable secondary sort. Unknown durations sort last.
- Ordinary folders are bounded to 1,000 total and 16 levels. Move validation includes the entire
  subtree, cycles, target ancestry, and duplicate sibling keys. Folder deletion is empty-only.
- `MediaDateKind.IMPORTED` denotes an import/migration observation, not a verified capture date.
  Unavailable duration/resolution stay nullable. `MediaReadRef` binds item revision and representation
  generation. It contains no path and is not itself proof of access.

## Ownership and deletion

`LibraryRepository` is synchronous and intended for a background storage executor. All mutations
have operation IDs, and updates/deletes bind expected item revisions. The storage implementation must
atomically validate authority/revision, persist operation state, and update catalog state. A delete
request requires explicit confirmation of its exact read reference. It first tombstones visibility;
physical removal waits for a durable intent and exclusive writer ownership. Pending/failed deletion
must not be reported as completed. `processPendingDeletes` resumes bounded durable work.

`MediaLeaseRegistry` is per-item, in-memory exclusion. Many readers may coexist. Queued writers are
FIFO; a blocked attempt retains its intent and prevents new readers from overtaking it. A caller
retries with the same operation ID or explicitly cancels the queued intent. A writer cannot coexist
with readers or another writer. Each lease closes once, including under concurrent duplicate close.
No timeout forcibly revokes a stalled native reader. A release hook runs outside the registry monitor;
a scheduling failure cannot reverse release, and durable adapter recovery remains necessary.

The adapter must validate current lifecycle, exact revision, and generation under the same owner lock
as lease acquisition and local representation resolution. It must not expose the registry directly to
untrusted consumers or mistake a registry entry for a durable operation journal. Native decoder and
export ownership must retain the same reader lease until resource release is confirmed.

## Local verification

The build owner runs `:library:test` with the repository's offline/strict-locking build procedure.
Tests cover bounds, Unicode/NFC, redacted diagnostics, cursor binding, ordering, subtree policies,
read-reference rejection, explicit confirmation, queued intent fairness, duplicate close, callback
reentrancy, concurrent readers, and reader/writer stress. JVM tests do not prove Android filesystem,
SQLite migration, MediaPlayer teardown, device durability, or any Vault security property.
