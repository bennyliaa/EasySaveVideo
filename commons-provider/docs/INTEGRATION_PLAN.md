# Historical integration plan after the M3-B checkpoint

This is the preserved pre-integration proposal. It is not the current status: see `../../docs/m2-commons/IMPLEMENTATION_AND_ACCEPTANCE.md` for the implemented chain, selected per-row BLOB design, verification and remaining device gates.

## Existing touchpoints inspected, read-only

- `discovery/DiscoveryModels.kt`: `RawVideoResult` and `VideoSearchResult` currently have no rights/author/license fields.
- `discovery/ResultNormalizer.kt`: validates titles up to 512 characters and normalizes result identity independently of displayed metadata.
- `discovery/ResultPool.kt`: merges metadata from duplicate identities; naïvely merging new rights fields individually could combine an author from one observation with a license from another.
- `discovery/DiscoveryStore.kt`: transactions atomically cover checkpoints, progress and Seen; detached snapshots currently copy only the existing collections.
- `app/storage/AndroidDiscoveryStore.kt`: database filename `discovery_v1.db`, SQLite schema version 1, one row per result, independent pages/progress/Seen/viewport tables. Unknown upgrades/downgrades deliberately fail and preserve the database.
- `app/storage/DiscoveryCheckpointCodec.kt`: bounded binary format version 1. The SQLite checkpoint header encodes empty result/page lists; actual results/pages are stored row by row. The codec also supports full snapshots outside that header path.
- `app/discovery/AndroidDiscoveryController.kt`, `app/ui/DiscoveryScreen.kt`: no live source registration or complete attribution rendering exists today.

Do not rename the database to get a fresh schema, destroy old data, replace per-result storage with one giant serialized pool, or change `identityVersion` merely because attribution is added.

## 1. Generic rights model and deterministic merge

Recommendation for review: add a bounded optional collection of coherent source-attribution snapshots to generic discovery results, defaulting to empty for old/demo results. Keep Commons classes outside core. Suggested generic payload:

- `schemaVersion = 1`
- provider/source ID and display name; source namespace/item ID
- canonical source page, optional source revision ID, retrieved-at time
- nullable author, credit, custom attribution, license name/link, usage terms
- nullable copyright/attribution-required/non-free booleans
- restrictions, deletion/review warning, completeness state
- metadata-terms reference and per-field provenance (field name, reported origin, structured/unstructured class, text-conversion and truncation flags)

Keep a **combined per-result attribution payload cap**, proposed 32 KiB, and an explicit small snapshot-count limit (for example four). Do not multiply a large per-snapshot allowance by 500 pool entries without retaining the existing whole-checkpoint limit. Validate well-formed Unicode before UTF-8 encoding, source links before rendering, strict tri-state values and bounded lists/strings. The Commons parser currently permits file titles up to 1024, while generic Discovery allows 512: choose an explicit policy at the adapter boundary, such as bounded display-title truncation with the source page preserved, rather than accidentally dropping candidates or changing their identity.

A complete snapshot is the unit of merge. Never combine one snapshot's author with another snapshot's license, let an old permissive label erase a newer warning, or keep a thumbnail while silently losing the rights that belonged with it. For the same source item, use revision/observation ordering to select the current complete observation and preserve conflicts as review-needed. For different provenances of a deduplicated identity, keep distinct bounded snapshots or explicitly report an unresolved conflict. Missing fields stay unknown; no download-clearance inference is added. Copy nested collections in detached snapshots.

## 2. SQLite version 1 → 2 and per-row persistence

Preferred minimal migration: keep the same database filename and existing tables; add a versioned attribution sidecar keyed by `(session_id, result_id, attribution_key)`, with deterministic bounded encoding per coherent snapshot. An alternative nullable versioned payload column on each result is acceptable if it keeps the same row-level update semantics and combined size cap. Choose one approach before implementation.

For a sidecar, store schema version, source identity, source page/revision and observation time as inspectable columns, with bounded deterministic encoding for the remaining nested details. Establish parent-result referential integrity via foreign keys enabled at database configuration time or explicit transactional orphan cleanup. Do not mix the two approaches silently. Existing rows initially have no attribution; do not fabricate CC0/public-domain/Commons metadata for them.

Continue using changed-row-only upserts so an unchanged checkpoint does not rewrite rights or grow WAL unnecessarily. Insert/update the matching result and attribution rows, remove stale sidecars for evicted results, update pages, then advance progress and Seen in the **same transaction**. A failed rights write must roll back cursor advancement and Seen changes too. `removeCheckpoint` must delete its sidecars; resetting source progress must not delete Seen.

Read sidecars in a bounded query (or bounded join) for the 500-result maximum, not an uncontrolled network lookup or unbounded per-row fetch. A row with unknown/corrupt rights schema cannot be treated as rights-free ordinary content. Preserve original data, surface the storage/review state, and avoid rendering its media as cleared.

## 3. Checkpoint format and rollback

Introduce a distinct binary checkpoint format version 2 only if result serialization changes. Decode version 1 as the previous fields with empty rights; encode version 2 with bounded rights. Header-only SQLite checkpoint payloads must still be accepted. Reject unknown future versions explicitly and retain original bytes. Identity schema version and cursor schema version remain separate concepts.

Run the database migration inside the existing SQLite upgrade transaction. Inject failure after DDL and after a sample attribution insert to prove old tables, rows and `user_version` are unchanged on rollback. Never catch migration errors and recreate the database. Browser/startup must continue under the existing storage-unavailable policy.

Feature rollback means disabling Commons registration/network work while retaining the compatible v2 reader and existing v2 database. It does **not** mean silently downgrading the database or reinstalling an old binary whose `onDowngrade` rejects it. If a binary rollback is necessary, prepare a separately reviewed compatibility reader or an explicitly approved recovery process. No destructive reset is part of this proposal.

## 4. SPI adapter, filters and throttling

The future adapter should expose `wikimedia-commons` at an admitted provider version and map one `CommonsMetadataPage` into a rights-preserving `ProviderPage`. Map source item ID to the Commons page ID, source namespace to a stable Commons namespace, page URL to the file page, and duration nullability unchanged. Apply existing duration/source filters without manufacturing metadata. Original video URLs are not part of this bridge.

Add a generic unsupported-filter outcome/capability and explicit retry-at value where needed. `safeSearch=true` must result in a visible unsupported-source state with **zero HTTP calls**, not automatic retry or exhaustion. Existing default safe search remains true. When the user explicitly disables it after a source-specific warning, create the corresponding new query identity/session epoch and cancel stale work. Do not carry an incompatible cursor across the filter change.

HTTP 429/503/maxlag outcomes carry a server-respecting not-before time. Discovery scheduling must honor it even if its ordinary retry delay is shorter. Unsupported configuration/filter outcomes are not transient retries. Cache should not leak across different query/filter/language identities. One admitted provider instance owns one physical HTTP call; cancelled-but-stuck work must retain that slot.

## 5. UI contract

Use SkinKit's existing semantic typography, spacing, colors and controls. Label the service as an open-media library and show “Wikimedia Commons”; do not imply whole-web video search or hide Wikimedia behind a private source name.

Cards/detail view must retain:

- source name and file/details link
- author, or a clear unknown-author state
- license name and license link, or a clear incomplete-license state
- custom attribution when supplied, with credit and usage/restriction detail available
- source observation/revision provenance in details

Render all metadata as inert text. Do not interpret stripped output as HTML, Markdown, JavaScript, clickable author URLs, or code. Only explicitly validated page/license actions become links. No download-success or rights-cleared badge is created.

Suggested unsupported-filter message: “Wikimedia Commons 暂不支持安全搜索。保持安全搜索时，不会向该来源发送查询。” Disabling the filter must be an explicit user action after explaining that the source does not guarantee filtered results. Restore/back/rotation must not silently flip it.

Missing/incomplete/flagged attribution needs a visible review state. Do not fetch/render thumbnails as if complete attribution existed. Thumbnail loading is a separate admitted component, not an excuse for direct original-video loading or passing the approved API contact to another host. Keep zero-provider and source-disabled browsing usable.

## 6. Actual endpoint adapter acceptance

Implement the platform port after the source review, without an unreviewed network dependency. The three public Python probes establish API shape only. Acceptance requires tests of the actual adapter:

- anonymous GET only to exact HTTPS Commons API host/path; reject userinfo, ports, malformed Unicode, unexpected destination changes and all redirects
- system CA trust and verified server hostname; no trust override
- all DNS answers checked, selected public address pinned, no second resolution/rebinding into private/link-local/reserved destinations
- no `CookieHandler`, `Authenticator`, WebView cookie jar, stored credentials, proxy fallback, automatic authentication, hidden retry, decompression or redirected contact header
- bounded headers/body including missing or false Content-Length, chunked transport, gzip despite `identity`, truncated streams and HTTP 200 API errors
- cancellation actually closes connect/TLS/header/body I/O, returns promptly, and never blocks the UI/timer; timeout covers DNS through parsing within the supplied original budget
- Retry-After delta/date/overflow; refusal does not trigger another identity, endpoint or bypass route
- actual build-version/contact injection without hardcoded private contact or fabricated support URL

## 7. Integration verification order

1. Candidate unit suite and independent review.
2. Generic normalizer/validation/merge/detachment round-trip tests, including conflicting rights and stale revisions.
3. SQLite v1 upgrade, idempotence, interrupted migration, rollback, per-row round-trip, no-op writes, eviction/orphan cleanup, corruption/unknown schema, and separate progress/Seen reset tests.
4. Checkpoint v1/v2 compatibility and malformed UTF-8/UTF-16, lengths/counts and whole-payload caps.
5. Adapter adversarial tests plus a small fixed anonymous metadata smoke test with the approved contact. Do not download media to verify search.
6. UI/presentation tests for full attribution, unknown duration/rights, safe-search refusal, cancel/stale callback, rotation/background, zero providers and disabled source.
7. Build owner serializes offline compile/lint/JVM/ABI checks. No APK assembly, install, publishing or deployment is implied.
8. Admit production registration only after the complete chain passes. “Independent provider candidate works” is not “M2 live source integrated.”
