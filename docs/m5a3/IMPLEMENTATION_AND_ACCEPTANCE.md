# M5-A3 ordinary export, share and thumbnail development checkpoint

Base: `b90194da23c6a6419b4d04d0f16cd5a6a2637fc5` / `m5a-library-dev-2026-10-06`.

Status: implemented and locally verified development checkpoint. Final development tag: `m5a3-exports-dev-2026-10-06-r1`. Earlier recoverable WIP: `f7f41c15cab772e391be6be6de87b78e383e7bf2`; its open findings are historical, not final acceptance.

## Implemented scope

- Explicit ordinary-library Gallery/MediaStore, system Files/SAF and system-share choices. Local Save success, export verification and recipient delivery are independent outcomes.
- Exact current item/revision/representation authority from the single storage owner; bounded source byte count and SHA-256 come from the catalog under its lock, not caller metadata or a stale Save receipt.
- Durable export phases in the same SQLite owner. Additive schema v3 upgrades v1 through validated library backfill and v2 through the new export tables, in one SQLiteOpenHelper version transaction. No old Save codec/proof format is changed.
- Bounded native thumbnails, in-memory cache, stale-result/session checks, foreground invalidation, exact read/native leases, and shared preview/recording exclusion.
- SkinKit controls and accessible placeholders; pending/unverified/recovery outcomes remain visible. No fake successful export, delivered-share claim or decorative privacy feature.

Android 12/API31 minimum, phone/tablet, closed-source and all seven SkinKit categories remain unchanged. No new runtime dependency or broad storage permission is introduced. This is a development checkpoint, not full M5 or a release. Configurable capture/save destinations, bulk folder deletion and Vault encryption/authentication remain later work.

## Export transaction and honest outcomes

Each explicit operation has an opaque ID and destination capability identity. Its source must still resolve as the selected READY ordinary representation. The coordinator copies with a 64 KiB buffer and a 2 GiB source ceiling, verifies exact byte count/SHA-256/full EOF, requires flush/close proof, and independently verifies the complete destination stream when available. Export never deletes the original; a separate user-confirmed library deletion remains independent and may finish after the source read lease closes.

MediaStore inserts an app-owned `IS_PENDING=1` row with generated operation-specific identity. Recovery queries explicitly include pending rows and require exact nonce, relative path, package owner and type; missing/ambiguous ownership is not permission to delete. Only a durable, destination-verified publication decision can clear `IS_PENDING`. An accepted cancellation before that decision prevents publication. After the decision cancellation is too late; an uncertain provider return is reconciled rather than guessed or retried by creating another copy.

SAF journals picker intent before launching `ACTION_CREATE_DOCUMENT`, then atomically adopts the exact returned new-document URI into the existing operation. A failed or lost result may have left an empty document; the app states that uncertainty. It does not persist or reconstruct a document permission from URI text. Providers need not support atomic rename, truncation, complete readback or deletion. Extra/truncated/corrupt readback fails; unsupported readback is a distinct copied-but-unverified result, never verified success. Cleanup acts only on the operation's own selected document and reports unsupported/failed/uncertain removal.

Unverified and unresolved SAF responsibilities remain in a durable bounded-page list, independent of the latest success notice. Each cleanup confirmation identifies the exact source, destination provider/export order and operation. A lost process grant or evicted live authority requires manual checking at the chosen provider; warnings survive restart. No automatic deletion of unverified copies occurs merely because the app restarts or the user opens the list.

Only unfinished operations consume the 32-active-operation limit. Terminal idempotency and responsibility records are retained without a lifetime export-count cutoff. Journal rows are at most 4 KiB; recovery and review pages contain at most 32 records and totals use Long. Fair recovery rotates its persisted cursor. Live SAF capability references are bounded to 32 and completed references are retired. A fresh operation may intentionally export another copy; replaying the same operation never does.

## Temporary read-only sharing

The source is copied to a separate, generated, app-owned noBackup namespace, synced, closed and fully read back for byte/digest/EOF equality before issuance. The original is never exposed through a raw descriptor or broad provider root. The provider is not exported; explicit URI grants authorize only an issued canonical random capability, and all write modes/mutations are rejected. Provider failures expose constant technical errors, not private paths or exception causes.

Recipients receive seekable read-only platform proxy descriptors. Positional reads come from the verified private snapshot, fill the complete requested amount except true EOF, and check expiry/revocation and budgets. Repeated opens allow chooser/receiver probes. The framework open occurs outside the share-owner lock to avoid callback deadlock. Callback and maintenance lanes are process singletons; failed initialization cannot create repeated native threads. Disk cleanup is off the main thread.

Limits: one copy at most 256 MiB; at most four retained copies and 512 MiB total; ten-minute authorization; at most eight opens per copy and two live proxy readers globally. A callback request is at most 128 KiB with 64 KiB underlying reads. Each descriptor permits at most eight times the source length and 65,536 nonempty reads. The process admits at most 512 proxy callbacks because some platform implementations retain released callback objects; payload references are detached on release. The UI displays the copy/count/total/expiry/open limits and directs larger files to MediaStore/SAF. Capacity errors explain revocation, waiting for actual release, or process restart when required.

Expiry/revocation prevents new opens and rejects subsequent app callbacks. Kernel buffers, recipient caches and copied bytes cannot be recalled. Snapshot cleanup waits for actual callback release plus confirmed closure of the app-owned input; timeout is not closure. After process death, old issuance is not restored, and generated leftovers are cleaned under the private directory lease. Unknown files are not adopted or deleted. Real Android proxy/FUSE, detached/duplicated descriptor behavior and recipient compatibility remain NOT_RUN; supported source APIs are not a universal sharing-compatibility claim.

## Thumbnail ownership

A process-wide single worker and native gate admit only one thumbnail/preview owner. Main-thread admission is serialized with recording admission. Frames are requested at 256×144, at most 36,864 pixels/147,456 bytes each; the owned cache retains at most 16 frames/2,359,296 bytes and queues at most 16 requests. No disk cache or Vault thumbnail exists.

Exact page/item revision and session generation are checked before displaying completion. Search/sort/page, rename/move/delete, background and disposal invalidate obsolete requests/results. A three-second watchdog fences the process on a stalled native job without freeing its owner or starting a replacement thread. The read/native lease chain survives until actual retriever, input and catalog-fence release. Both thumbnail→preview and preview→thumbnail navigation wait for the outgoing native owner to release.

Rendered bitmap handles stop drawing after cache removal. Already-submitted hardware Canvas images are dereferenced rather than forcibly recycled; never-drawn discarded frames can be recycled. The memory limit describes app-owned frame/cache allocations, not total decoder/renderer/GC/GPU memory on an OEM device.

## Verification and remaining gates

Final strict-offline aggregate (`evidence/strict-offline-final.log`): both variants compile; both strict lint tasks pass with 11 warnings and 5 state-boxing hints per variant, no errors/fatals or disabled detectors. The new static-context warning is the inspected application-context-only share singleton, not a retained Activity. 929 JVM tests pass with zero failures/errors/skips: core-domain 32; library 130; recording 71; Save 86; HLS 40; theme 9; discovery 94; Commons 48; app 419. Core-model has no separate tests.

Fourteen catalog SQLite contracts and fourteen export SQLite/static contracts pass, including v1/v2 migration rollback, atomic SAF adoption, fair recovery, retained multi-page warnings after an actual temporary-database close/reopen and more than 1,000 terminal exports without active-capacity exhaustion. Existing HLS host 81 EOF fixtures and 15 pinned MP4 round trips, legacy reader, discovery SQL and 11 synthetic DOM checks pass. Both variants retain exactly 72 reviewed external artifacts and zero duplicate runtime classes.

Two independent source reviews close all reported blockers: 32 safety-reviewed production hashes and 15 focused UI/context/test hashes match final source. The final source commit is `ebbca1997d02e4b67d9d0cef838b288a317d46c4`; later documentation/evidence commits do not change those reviewed production bytes. Full measured results are recorded in `evidence/verification-summary.json`. Source review, synthetic JVM/fake-port/filesystem tests, actual pinned Media3 host fixtures and host SQLite contracts are distinct forms of evidence. The SQL runner executes production-extracted DDL/selectors with synthetic fixtures; it does not execute Android SQLiteOpenHelper or ContentResolver.

All Android/OEM native decoder, MediaStore visibility, document-provider semantics, actual URI permissions, system chooser, recipient compatibility, phone/tablet layout, hardware memory and process-death behavior remain NOT_RUN. `DEVICE_ACCEPTANCE_CHECKLIST.md` is the deferred matrix. There is no APK/AAB assembly, installation, real-media export/delete/share, actual permission grant, account, publication or deployment in this task.

The ordinary files and normal metadata remain app-private plaintext. Existing Save provenance is retained. No secure metadata erasure, Vault credential setup, encryption, biometric behavior, private playback or private sharing is claimed.

## Recovery materials

After freezing the development tag, a complete Git bundle, tracked-source archive, SHA-256 list and independent restoration receipt are prepared outside the repository. The external receipt records byte-for-byte tracked-file comparison, Git integrity and the cold Gradle build of restored sources using the existing admitted offline toolchain/cache. No package or device acceptance is implied.
