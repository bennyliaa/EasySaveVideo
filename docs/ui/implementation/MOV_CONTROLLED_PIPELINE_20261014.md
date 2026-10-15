# Controlled Direct / MOV pipeline — 2026-10-14

## Result and admission status

The existing Direct Runtime and Coordinator now compose the storage-owned format selector, fresh remux attempt, normalization proof, adoption, exact canonical-reader binding, typed commit/publication, and terminal cleanup. Ordinary MP4 retains its local recovery path, including historical INTENT/INDEXED records with matching verified evidence. Production MOV admission remains explicitly disabled pending Android/native acceptance; this checkpoint does not claim user-visible MOV support. The first supported MOV profile remains silent AVC QuickTime. Audio-bearing MOV is rejected as a whole until executable AAC frame/timing validation is implemented.

## Implemented boundaries

- The actual accepted/entered execution, exact Direct admission claim, atomic native-batch live view and existing Direct local permit form the bridge. The bridge does not acquire a second native/serial lane.
- Actual transfer completion is recorded once after positive release and exact full Save proposal confirmation. Stops may preserve this historical fact but cannot create new execution authority. Genuine process-start provenance under the exclusive directory lease is distinct from same-process object recreation.
- Shared reservations fence legacy paths even while the durable ledger is ABSENT. Hashing, descriptor open/close, directory enumeration, fsync and byte IO execute outside the global storage monitor; outstanding and uncertain obligations remain visible through memory-only gates.
- Fresh WRITING requires the exact acknowledged RAW_COMPLETE transition. Old WRITING never receives a new writer. OUTPUT_READY without the complete Save proof cannot reconstruct producer evidence.
- Proof and checkpoint removal are one exact Save transaction. Generic journal updates cannot strip proof or bypass durable ownership; phase-only transitions use exact lifecycle/owner/raw bindings and a narrow whitelist.
- Native verification receives the exact storage-issued canonical reader, shares the existing serial/native lane, and reports actual input/native release. A queued legacy read has atomic issue/retire state; a late callback cannot fall through to an unowned reader.
- Typed commit performs exact P-or-final reconciliation, invisible index preparation, and atomic Save/Library/destination publication. Unknown outcomes use the same fixed proposal for readback, without remux or a second publication.
- Startup and periodic maintenance independently scan bounded terminal-ledger keysets. Cleanup uses the same local gate with cleanup-only authority, one deadline and fair cursors. Damaged/ineligible entries are preserved without starving later keys. No network, execution claim, verification or publication is created by cleanup.
- New Direct creation checks existing Save/workspace state on the control worker before browser credential/session capture. Complete local Resume does not acquire a fresh network lease.

## Evidence

All results below are from the cloud workspace; no device was operated.

- Final forced 13-module tests: 4,532 cases, 4,530 passed and two existing skips, zero failures/errors. App: 3,008; save: 347. Both variants compile; lint has zero errors, 79/80 warnings and eight notes. Fourteen new RequiresFeature warnings belong to the guarded WebKit foundation and are tracked for call-site review in the next browser slice. The full run and all ancillary checks exited zero.
- Actual external-claim/Coordinator/bridge/adapter/owner/typed-commit composition: ten tests passed. These use explicit fake remux/native decoding/OS/SQL ports, while exercising production ownership, adoption, commit and retirement classes. They cover positive late release, sticky uncertainty, stale queued issuance, proof/publication write-after-throw, and successful terminal cleanup.
- Terminal sweep: 13 real-Coordinator tests passed, including restart discovery, whole-sweep deadline, malformed pages, per-key fairness, blocked cleanup and ordinary pending fairness.
- Real pinned Media3 1.11.1 muxer/extractor through the current owned-file adapter: six tests passed. The runner compiles current production source and uses Android host shims; it is not Android platform execution.
- Host SQLite typed-commit contract: ten tests passed using actual schema/query shapes and synthetic BLOBs. This is not Kotlin Android SQLite execution or codec evidence.
- Independent storage review approved the 26-file manifest, including the orphan-proof and lifecycle-fence corrections. Independent execution/Runtime review approved actual external claims, batch identity, cancellation, transfer facts, serial verification and retirement evidence.
- WebKit 1.17.1 foundation/capture policy: 45 tests passed. These modules remain without browser UI and Save/Record private-session integration; they do not yet make Incognito available.

## Explicit remaining gates

1. Actual Android FD identity/close, SQLite transaction, codec input framing/terminal EOS, fsync failure and real native-release behavior must pass before enabling MOV. The production flag remains false.
2. Audio/AAC support remains an original-requirement continuation, not a permanent silent-audio workaround.
3. Version-1 cleanup plans remain readable and preserved; the new typed cleanup path does not upgrade them into deletion authority.
4. Device rendering, accessibility, performance, battery and APK installation acceptance remain unperformed.
5. Private browsing still needs the approved partition-aware Save source/credential boundary and all three save methods, including explicitly authorized private recording with output-preserving stop/finalize behavior.

## Backup status

The last verified private dev commit is `06d3f50884ea88d1de647d212264c6488d3a658d`. The separate WebKit admission payload and this checkpoint are retained locally. A GitHub immutable-tree request did not return; no commit or ref update was issued afterward. Remote synchronization is pending, and the existing main branch has not been changed. Build/test progress does not wait for that connector.
