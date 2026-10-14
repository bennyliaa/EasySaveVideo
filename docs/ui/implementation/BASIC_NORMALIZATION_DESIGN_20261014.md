# Free Direct MOV normalization: minimal implementation design for independent review

Date: 2026-10-14 UTC

Status: DESIGN ONLY / NOT IMPLEMENTED / NOT TESTED. This proposal changes no source, dependency, permission, device state, or production behavior. Independent review must accept the data model, recovery matrix, parser bounds, and local-lane lifecycle before implementation. File and type names below are proposed unless identified as existing anchors.

Source baseline: private development checkpoint `eb1ec3e1216c9bae655655906afb73c023024818`, including AGENTS.md and the current Direct 1/2 implementation. This repository copy is submitted for independent design review; it is not implementation acceptance. No WebM implementation or transcoding is included.

## 1. Decision and original requirement

Original `docs/requirement and design/01_Product_Requirements.md:205–217` makes Direct free and requires feasible compatibility conversion to verified playable MP4 as basic output. Original `04_Technical_Requirements.md:126–154` calls for actual container/codec inspection, remux where possible, explicit unsupported failures, and no extension-only conversion.

Implement ONE additional Direct output profile: a narrowly validated, unencrypted, self-contained, nonfragmented QuickTime MOV containing Baseline AVC without B-frames and optional AAC-LC, remuxed to MP4 without decoding/re-encoding. Preserve the ordinary MP4 pass-through route. Keep the final MP4 container verifier strict. Do not route this through the editor, VIDEO_COMPRESS, Stream entitlement, or Record.

This is a partial original-requirement implementation, not general MOV support or completion of all WebM/MOV compatibility. Unknown/unsupported media and unknown protection remain unavailable; known protection is rejected. No fallback silently records, drops audio/tracks, changes codecs, repairs source bytes, or fetches keys.

## 2. Critical design conclusions

1. Download evidence and canonical output evidence must be different types. HTTP Content-Length is NEVER replaced with normalized output size. The raw download remains the source transfer in the durable record.
2. Preserve raw and output as separate owned files. Do not overwrite raw while muxing. Do not copy raw into a second full-size backup. Rename raw only after a durable, dual-hash adoption intent.
3. The two adoption renames are not a transaction. Recovery uses the exact journal intent plus identities/hashes of the primary/raw/output files. Unexpected combinations are RECOVERY, never permission to overwrite or infer completion.
4. A shared Direct local lane covers MOV inspection/remux, adoption, native MP4 verification, existing commit/inspect/publication, and the first normal-path raw-cleanup attempt. It retains the existing Direct execution claim and batch native owner; it never acquires a new exclusive Save claim.
5. Ordinary mux-finalization/cleanup/storage failures do not automatically become permanent native poison. Actual unconfirmed reader/writer/native release still blocks publication and claim retirement under the existing rule.
6. Normal successful Direct2 active payload can be bounded by two 2 GiB inputs plus one 2 GiB output = 6 GiB. This is NOT a global disk bound. Previously retained WAITING_USER material, successful cleanup residues, existing library media, DB/WAL, exports, etc. are additional and reduce actual free space. Recheck a full 2 GiB free-output allowance at every new conversion; never subtract already written bytes from that admission threshold.
7. Terminal Save records are immutable in the current adapter. A small independent workspace ledger is necessary for terminal cleanup bookkeeping and bounded pending discovery; `SaveRecord.cleanupPending` alone is insufficient.

## 3. Verified current implementation anchors

| Concern | Existing evidence |
|---|---|
| Candidate exclusion | `PageSaveDetector.kt:101–117` excludes ordinary `.mov`/`.webm` from its MP4 hint |
| HTTP MIME rejection | `app/src/main/java/com/easysavevideo/save/network/SystemHttpsDirectTransfer.kt:90,160–163` allows only MP4/octet-stream |
| No normalization stage | `save/.../SaveCoordinator.kt:467–565` transfers then verifies |
| Conflated source/output length | Coordinator `:536–549`; `SaveModels.kt:163–171,193–202` |
| Direct cannot borrow Stream proof | `RecordingVerification.kt:87–93` |
| Final MP4-only gate | `AndroidLocalMp4Verifier.kt:85–88,159–160`; `Mp4ContainerProbe.kt:7,34–40` explicitly rejects `qt  ` |
| Existing single verifier | `AndroidSaveRuntime.kt:218–221`; `SerialArtifactVerifier.kt:10–14,34–64` |
| Direct batch/exact claims | `AndroidSaveRuntime.kt:121–125,1233–1252`; `SaveExecutionAdmission.kt:58–85,95–128` |
| Current second Direct disk check | `SaveExecutionAdmission.kt:64–68`, 2 × maxArtifactBytes |
| HLS workspace cannot be reused | `PrivateCommitProtocol.kt:118–155`: `.hls`, generated `.ts`, `output.mp4` only |
| Publication boundary to preserve | Coordinator `:578–592`; storage `compareAndSet` `:134–157` |
| Terminal rows cannot be updated | `AndroidSaveStorage.kt:137`; `listUnfinished` `:159–160` omits terminal rows |
| Existing post-success cleanup hook | Coordinator `:592`; storage `cleanup` `:190–194` |
| Existing recovery is passive | Coordinator `:717–739`, no network/media replay |
| Finalized-local resume | Coordinator `:232–265`; runtime `:740–818` |
| Existing schema versions | `SaveRecordCodec.VERSION=6` / evidence version 3; `AndroidSaveStorage.Database` version 8 |

The editor is not a conversion adapter: `EditorEnginePorts.kt:13–25` admits catalog/recorded-segment references; `Media3EditorEngine.kt:85,116–130,179–186` restricts MP4 AVC/AAC and forces decoded output/transcoding; `AndroidEditRuntime.kt:200–205` rejects no-op edits and applies edit feature checks. HLS is TS-only and rejects codecs, see `Media3LocalHlsEngine.kt:94–98,175–180,332–371`.

## 4. Exact admitted APIs and selected engine approach

### 4.1 Use the official extractor and muxer directly

For this remux-only slice, prefer a new original `MovRemuxEngine` adapter around already-admitted `Mp4Extractor` and `Mp4Muxer`. Do not instantiate Transformer/ExoPlayer/GL/MediaCodec for normalization. This avoids inventing another codec/loader lifecycle when no decoding or encoding is required. Independent final verification still uses the existing Android native verifier and its one native lane.

This uses official compiled artifacts, not copied upstream implementation code. `app/build.gradle.kts:59–60` and `third_party/admitted-runtime.tsv:48–58` already admit Media3 1.11.1 common/container/extractor/muxer/transformer. Do not add submodules or change versions/hashes.

The preserved source JARs under `third_party/candidates/media3-1.11.1/sources/` establish these exact APIs:

- `media3-extractor/.../mp4/Sniffer.java:34–35,66`: QuickTime brand recognized.
- `mp4/Mp4Extractor.java:320`: `Mp4Extractor(SubtitleParser.Factory, flags)`; use unsupported subtitle handling and no workaround flags. `sniff` at 359, `init` at 371, `read` at 417, `release` at 412. Release is currently a Java no-op, but the owned input/channel must still close.
- `Mp4Extractor.java:559–562,641–664` handles QuickTime. This does NOT mean all QuickTime profiles are safe or accepted by ESV.
- `DefaultExtractorInput.java:57`: constructor accepts `DataReader`, current position, fixed input length.
- `TrackOutput.java:191–219`: bounded sampleData callbacks and sampleMetadata(timeUs, flags, size, offset, cryptoData). Never assume one callback equals one sample; account for `offset` and both data APIs. Reject cryptoData and supplemental/encrypted sample parts.
- `media3-muxer/.../Mp4Muxer.java:223`: Builder accepts `SeekableMuxerOutput`; `:311` can disable sample batching; `:325` controls streamable metadata layout; `:502` addTrack returns an integer track ID; `:575` writeSampleData(int, ByteBuffer, BufferInfo); `:647–679` close finalizes and closes the sink.
- `Mp4Muxer.java:395–417`: AVC/AAC are supported. Use an explicit restricted allowlist rather than exposing the muxer's full list.
- `SeekableMuxerOutput.java:27–73`: WritableByteChannel plus getPosition/setPosition/getSize/truncate. Implement a job-owned, byte-bounded sink; do not call its arbitrary-file-path factory.
- `BufferInfo.java:41`: immutable timestamp/size/flags constructor.

Choose an explicit zero-byte `C.BUFFER_FLAG_END_OF_STREAM` buffer per admitted track at its proved endpoint, with `LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS`; never rely on the duplicate-previous fallback. No sample may follow EOS. The shared conservation implementation must define source tick-to-output clock rounding, endpoint bounds, codec-configuration canonical digest and NAL framing before the adapter slice starts. Canonical AVC payload uses an ordered NAL sequence with fixed four-byte length prefixes; AnnexB/AVCC framing is normalized through one shared bounded function without discarding meaningful payload bytes. Configuration includes the exact accepted SPS/PPS and AAC AudioSpecificConfig bytes under a versioned length-delimited digest; both source and output scanners call that same implementation, not independent conventions. Pinned host tests must prove endpoint and framing behavior.

Pinned `Mp4Muxer.close` catches IOException but an unchecked finalization exception may bypass sink close. An outer adapter finally must independently close and verify the job-owned sink and input even when muxer close throws unchecked. A close invocation alone is never evidence of release; test unchecked finalization plus sink-close failure.

Configure batching off so a returned write does not retain arbitrary input sample buffers. Bound the input sample assembler, parser tables, output seeks/writes/truncates, bytes and runtime. Keep Media3 diagnostic output disabled as existing adapters do; no source URL, title, path, metadata, exception text, or filename may be logged. Do not copy remote metadata into output.

Handle `Extractor.RESULT_SEEK` by repositioning the SAME owned seekable input after validating the requested position in its fixed extent and reconstructing DefaultExtractorInput. Do not call extractor.seek for each requested internal seek, because that resets extractor state. On END_OF_INPUT require completed track/sample accounting; a tolerant extractor EOF alone is not proof of complete input.

### 4.2 Official API context, not new admission

[Transformer transformations](https://developer.android.com/media/media3/transformer/transformations) distinguishes remux from transcoding. [Transformer supported formats](https://developer.android.com/media/media3/transformer/supported-formats) explains extractor/decoder/device-encoder limits. These are context, not acceptance evidence for this pinned implementation or any device. No API claiming general MOV support is inferred from these pages.

## 5. Initial MOV profile and preflight

Proposed engineering bounds for review, not existing measured capability:

- Raw and canonical output each <= existing 2 GiB `maxArtifactBytes`.
- Duration <= 30 minutes; one video track, zero or one audio track.
- Video <= 1920 per edge and <= 1920×1080 pixels, even dimensions, 8-bit progressive Baseline AVC, no B-frames, square pixels, explicitly supported SDR. Rotation and source track matrix must be identity; no geometry or orientation transformation.
- Optional AAC-LC mono/stereo at 32, 44.1 or 48 kHz; no priming/edit-list transformations in this first profile. An actual audio track must be retained even when page expectedAudio was false/unknown.
- Constant integer 1–60 fps in this first profile, bounded clock-conversion tolerance explicitly tested against the pinned muxer. At most 108,000 video samples; set an independently justified AAC sample cap for 30 minutes (<= 90,000 is sufficient for the admitted rates). At most 4 MiB per compressed sample and 64 KiB per codec initialization block.
- Bounded moov/table bytes (proposed 8 MiB), box count/depth (proposed 16,384/12), table entry counts, arithmetic and sample ranges. Reject sizes/counts before handing material to allocation-producing extractor paths.

Required preflight facts:

1. Download is complete according to original HTTP framing/Range/ETag rules. Stable size/hash is calculated from the closed owned raw file; source length cannot come from extension or page metadata.
2. Actual `ftyp` declares QuickTime. Require an explicit `ftyp` for this slice; no legacy ftyp-less MOV, fragmented `moof/mvex`, compressed `cmov`, external-reference movie, reference movie, or unsupported file family.
3. File has the admitted movie/media structure and bounded tables. Validate every declared track and sample entry, not only tracks the general extractor chooses to emit. Reject unrecognized critical boxes/semantics, sample-description switching, metadata/text/timecode/auxiliary tracks and hidden track loss. Maintain a documented structural whitelist; benign ignored padding must be explicitly listed.
4. Data references must prove self-contained media. Reject external URL/URN/reference entries and non-self-contained dref semantics even if the extractor happens to ignore them. Input sample extents must be inside the owned mdat extent and file bounds, without overlapping/ambiguous ranges prohibited by the profile.
5. Reject any edit list in v1, any composition-offset table requiring reordered presentation, nonidentity matrix/orientation, fragmented/encrypted entries, PSSH, sinf/schi/tenc, protected sample indicators and cryptoData. Known protection maps PROTECTED_MEDIA. An unsupported/ambiguous critical protection structure maps to a fail-closed unavailable result with NO alternative-record suggestion; absence of a recognized DRM flag alone is not a clear-content proof.
6. Inspect AVC SPS/PPS and ALL sample NAL/slice boundaries using admitted parsing primitives and original bounded policy code; verify no-B and stable config, not just codec strings. Inspect AAC config and sample timing. No HLS AUD/PES-specific prerequisite is imported into MOV.
7. Extractor output track count/formats/sample count must match preflight. Preserve every admitted compressed sample in order. Rehash raw after read to reject same-size mutations.

The preflight is an original bounded admission/profile checker around the official demuxer, not an alternative media demux engine. If a structural case cannot be proved with the narrow checker, reject it; do not silently rely on the extractor's tolerance.

### Device capability reporting

Remuxing needs no encoder. Do not expose encoder-availability claims or transcode fallback. A pure format/preflight success means only “candidate fits remux profile.” Final success requires actual native video/audio decode evidence from the canonical output. Existing final verifier timeouts remain an honest capability limit; 30-minute input admission is not a promise it can be verified within its 60-second budget. Report device verification failure accurately, without calling it a download failure or claiming universal codec support.

## 6. Domain data model: separate source, output and file lifecycle

### 6.1 Keep original source transfer authoritative

Keep `SaveRecord.transfer: TransferResult?` as the existing completed port result. For new normalized Direct tasks, this is the RAW network result forever: actual raw bytes, response MIME and optional HTTP expected total remain unchanged. `bytesTransferred` remains charged NETWORK bytes across retries. `DownloadCheckpoint` refers only to raw download offsets; after complete transfer it is retired and never applied to a normalized primary file.

Add an optional immutable `directNormalization: DirectNormalizationProof?` to SaveRecord. It is null for all old records, ordinary MP4, HLS and Record. Require TaskMethod.DIRECT if nonnull. A successful normalized task must have the proof. No proof is synthesized when decoding legacy data.

Proposed proof contents, bounded and technical only:

- schema/profile revision, normalizationId (random local UUID), exact task/artifact/commit and source-identity binding;
- raw size and SHA-256; binding to the unchanged completed source transfer (including response MIME and expected length through a canonical tuple digest or exact comparison, not a guessed media MIME);
- actual input container/profile enum `QT_MOV_AVC_BASELINE_AAC_LC_V1`;
- canonical output size and SHA-256, MP4 output type;
- fixed geometry/audio configuration and compact remux-conservation contract: per-track codec-config digest, sample count, canonical compressed-payload digest, keyframe/sample-flag digest, normalized timing digest, first/last timestamps, supported origin offset and final endpoint/rounding bounds;
- engine/policy revision and no-encode proof. Do not store raw metadata, paths, signed URLs, cookies, server filenames or codec exception text.

Do not put an HLS `StreamArtifactExpectation` on a Direct result. Arithmetic helpers may be reused only after their clock assumptions are checked. Direct MOV gets its own semantic proof; the Stream/Record method discriminator remains strict.

### 6.2 Add a typed verifier input

Introduce `ArtifactOutputDescriptor` to separate verification of an output from completion of a network transfer:

- `Transferred(transfer)` preserves the existing ordinary MP4/HLS/Record behavior and current length checks.
- `NormalizedDirect(sourceTransfer, normalizationProof)` exposes output size/hash/type to the verifier while retaining the original raw transfer separately. It validates the source/proof binding before any native work.

Change ArtifactVerifier/SerialArtifactVerifier/AndroidLocalMp4Verifier to consume this descriptor, with compatibility convenience factory/overload for existing tests/call sites if useful. No ephemeral “TransferResult whose expectedTotalBytes means output bytes” is manufactured. No file-magic change weakens Mp4ContainerProbe.

Extend `VerificationEvidence.isValidFor` to use the selected output descriptor. Existing callers keep their exact validation through `Transferred`. For normalized Direct, require output size/hash equality AND independently recomputed Direct remux conservation/tail proof, not only a callback from MovRemuxEngine. Require before/after output hash identity around native inspection as already done for Stream/Record. Keep all current structural, video decode, audio decode, encrypted-sample and MP4 MIME checks.

`VerifiedArtifact`, commit receipts and Library publication continue to represent ONLY canonical verified MP4 bytes. Raw metadata never supplies the committed size/hash. All durable consumers use one record-selected output descriptor: SaveRecord constructor validation, `LibraryPublicationEvidence.requireValid` (including the nonlegacy publication transaction), and `mediaTaskHasCommittedSave` / Discovery success feedback. Keeping a raw-transfer compatibility overload is not permission for these consumers to bypass descriptor selection. Tests must cover output both larger and smaller than raw through publication and final success feedback; legacy branches retain only their historical exceptions.

### 6.3 Wire compatibility

Propose SaveRecord codec v7 and standalone verification-evidence codec v4, preserving readers for record versions 1–6 and evidence versions 0–3. Leave `SaveRecord.schemaVersion=1` unless a separately reviewed reason requires otherwise. Legacy normalization is null. No legacy Direct becomes “converted.” Reject malformed/trailing/oversized proof data within the existing 32 KiB record cap and the independent 4096-byte evidence cap; test both standalone evidence v4 and nested record v7 codecs. Use digests rather than per-sample arrays in the journal.

Add UI subphases NORMALIZING and ADOPTING (or equally explicit names), without inventing a new public TaskState. Failure mappings distinguish unsupported input, canonical-output invalidity, local IO/storage, protected/unknown-protection, and release uncertainty.

## 7. Storage ledger and owned workspace

### 7.1 Paths and authority

Use the current owned artifact under the same no-backup private root:

- P: `staging/<artifactId>.part`, initially raw; after adoption, canonical MP4.
- D: `staging/<artifactId>.normalization/<normalizationId>/`, one live generation only.
- R: D/`raw.bin`, created solely by P→R rename; never by copying raw.
- O: D/`output.mp4`, muxed into a newly created, empty, owned file.

Names derive only from journal-issued validated IDs. No UI/intent/remote path is accepted. Do not borrow `.hls`. Reject symlinks, nonregular files, cross-filesystem moves, unexpected directory entries and collisions. The same storage owner/lock protects ownership changes; expensive hashing is outside admission/Coordinator monitors. Descriptor/file-key checks are additional live-process defenses; durable ownership and exact hashes remain required after restart.

### 7.2 Small additive workspace ledger

Add a `save_normalization_workspace` table in the existing DB (proposed DB v9). New-database creation and every supported upgrade 1–8 → 9 must install the new schema while preserving all existing version-specific migrations, foreign keys and rollback; do not implement only 8 → 9 or leave the old `newVersion == 8` guard. This is the authoritative FILE LIFECYCLE/cleanup ledger, not another Save state machine. Row key: normalizationId; unique live artifact/commit generation. Foreign-key/binding to exact owned Save artifact. Data:

- taskId, artifactId, commitId, normalizationId, rowRevision;
- lifecycle: RAW_COMPLETE, WRITING, OUTPUT_READY, ADOPT_INTENT, ADOPTED, CLEANUP_PENDING, CLEANED, RECOVERY_REQUIRED;
- raw size/hash and fixed source-transfer binding once known;
- output size/hash and immutable Direct proof digest once OUTPUT_READY;
- create/adopt/cleanup intent with exact generated path roles, not arbitrary paths;
- disposition CONTINUE or DISCARD_ONLY; bounded technical failure reason;
- no reusable network context.

Authority rules:

- SaveRecord owns source transfer, semantic normalization proof, task state, final verification and publication.
- Workspace row owns file movement, generation and cleanup. It may be updated after Save becomes terminal. A CLEANED row is historical and does not authorize new writes.
- Hashes/proof IDs are immutable after adoption intent. Save proof and ledger proof digest must agree; mismatch fails closed. Do not keep competing independently writable “normalization phase” copies in both places.
- Persist completed RAW `SaveRecord.transfer` first; only after its authoritative read/CAS acknowledgement may the workspace enter RAW_COMPLETE. Persist workspace creation intent before any output path creation. No second generation begins while the old row owns unresolved files.
- OUTPUT_READY is recorded only after bounded mux finalization, all relevant handles actually released, fsync, fresh complete output hash and raw rehash.
- Before ADOPT_INTENT, persist the immutable SaveRecord proof using ordinary revision CAS. Then persist ledger ADOPT_INTENT with exactly matching proof. A crash between these writes is recognizable and safe: raw is still P, no rename is authorized without ledger intent.

Why a separate table is necessary: `AndroidSaveStorage.compareAndSet:137` refuses terminal updates and `listUnfinished` excludes terminal rows. Post-success cleanup cannot mark a field in an immutable Save record. The ledger provides an indexed, bounded query of pending residues including SUCCEEDED/FAILED/CANCELLED tasks, without an unbounded directory or historic-task scan. Add keyset pagination/explicit bounds; incomplete inspection means cleanup status unknown, not “nothing pending.”

Existing generic failure cleanup marks the catalog parent DISCARDED rather than deleting its row. Exact ledger-owned residual cleanup must therefore permit a correctly bound DISCARDED parent as well as the appropriate staging/published states; it must not require STAGING and strand residues, nor treat DISCARDED as authority to delete arbitrary files.

The original commit/inspect/SUCCEEDED CAS invariant is unchanged. The new table does not publish media. No final file is visible before the existing CAS.

## 8. Execution and stage ports

Suggested platform-neutral API boundaries (names are illustrative, semantics required):

1. `DirectLocalProcessingGate.withPermit(context, owner, block)` — fair, cancellable waiting on existing worker; no new native Future; exact owner capability; no lock held during IO. Holds only Direct local processing, not HTTP transfer.
2. `DirectNormalizationPort.inspectRaw(artifact, sourceTransfer, context)` — returns `AlreadyMp4`, `AdmittedMov(profileEvidence)`, or bounded failure. Only closed owned descriptors; no arbitrary URI or path.
3. `prepareWorkspace(artifact, sourceFingerprint, context)` — creates/reuses exact ledger-owned generation and empty output; checks full output free allowance before first output creation.
4. `remux(workspace, inspectedSource, context, limits)` — returns closed-output candidate evidence, never publishable success. No encoder/decoder/Transformer or network.
5. `finishOutput(workspace, context)` — rehashes exact raw/output, synchronizes, persists OUTPUT_READY and compact proof.
6. Coordinator persists `record.directNormalization` by CAS, then `adopt(workspace, proof, context)` runs the journaled two-rename protocol.
7. `reconcile(workspace, proof, context)` — checks legal recovery configurations; called only by explicit local Resume for continuable state, or passive inspection without mutations during startup.
8. `verifier.verify(artifact, ArtifactOutputDescriptor.NormalizedDirect(...), verificationContext)` — unchanged strict MP4 gates plus Direct conservation proof.
9. Existing `commit(VerifiedArtifact)`, `inspectCommitted`, final cancel-versus-SUCCEEDED CAS.
10. Existing cleanup hook invokes exact normalization cleanup; terminal result remains success if cleanup alone fails. Ledger reports remaining owned residues.

Run ordering for Direct:

- Download exactly as before, except explicit additional QuickTime MIME handling. Reject WebM MIME; keep TLS/origin/auth/Range/byte policies unchanged. Persist completed RAW transfer after raw completeness checks; do not enforce the old final-MP4 MIME allowlist against raw QuickTime.
- Acquire the shared Direct local permit after network completion. Ordinary MP4 passes through the existing verification/commit path under this permit; no converter or workspace is created.
- MOV: inspect → reserve/create O → remux → close/fsync/prove → persist Save proof → journal adoption → adopt → canonical verification → existing commit/inspect/CAS → first cleanup attempt.
- The current SerialArtifactVerifier can remain nested inside this OUTER Direct gate, in fixed order outer Direct gate → serial verifier → native verifier. This avoids changing HLS/Record sequencing or reentrant ownership APIs. No path may acquire those gates in reverse. HLS/Record are already excluded by SaveExecutionAdmission and retain their current behavior.

Mandatory before the current `transfer == null` network branch: read a typed workspace-recovery view for the exact task/artifact/commit. It has ABSENT, PRESENT and UNREADABLE/CONFLICT outcomes; an exception, partial lookup, mismatched generation or unknown state is never ABSENT. PRESENT with RAW_COMPLETE/OUTPUT_READY/ADOPT_INTENT/ADOPTED requires local reconciliation BEFORE any transport call. If the required completed source transfer is missing from SaveRecord despite that ledger state, stop in RECOVERY; do not reconstruct HTTP completeness from a file hash or start network to repair it. The normal write ordering above makes that mismatch impossible without an interrupted/uncertain write or inconsistency, which is precisely why it must fail closed. A still-unacknowledged source-transfer CAS is reread before deciding whether to create a workspace. An unjournaled full raw file with no workspace retains legacy incomplete-download behavior, but can never contain adopted normalized bytes under this protocol.

Network retry still concerns raw download only. A completed raw transfer followed by a local failure must not re-enter network transfer, reapply a raw Range checkpoint to output, or reset network-byte charges. Resume with `record.transfer != null` uses local material; runtime must avoid `leaseFor(...)` merely because an old in-memory source still exists (`AndroidSaveRuntime.kt:789` currently does so). Existing accepted-worker/unadmitted-resume ownership rules remain intact.

## 9. Adoption protocol and crash matrix

Precondition: P is exact closed raw S; O is exact closed canonical candidate C; R absent; durable source/output hashes and sizes agree between Save proof and workspace OUTPUT_READY. Both source and output remain within allowed extents. Ledger ADOPT_INTENT must be committed before the first rename.

Normal sequence:

1. Verify intent/proof binding and current P/O identities, hashes, absence of R; honor cancellation before mutating.
2. Move P→R using no-replace semantics under sole directory ownership. Never overwrite R. Sync D and staging directories as applicable.
3. Move O→P using no-replace semantics; P must be absent. Sync D and staging directories.
4. Freshly validate P=C and R=S, O absent. Mark ledger ADOPTED. No native readers/writers may still refer to a moved writable artifact.
5. Run strict independent verification against P with the normalized output descriptor. Do not treat ADOPTED as success.

The existing `replaceOwnedStaging` replacement operation must NOT be used for a blind O→P overwrite. Use the existing no-replace promotion discipline or a specifically reviewed same-filesystem, absent-destination move under the same sole-owner lock. Atomic file rename does not make the two-step adoption atomic.

| Durable state / observed files | Safe behavior |
|---|---|
| WRITING; P=S, R absent, O incomplete or absent | No success. Explicit Resume may discard/recreate only positively ledger-owned incomplete O after release confirmation; revalidate raw. Ambiguous creation/collision remains RECOVERY. |
| OUTPUT_READY; P=S, R absent, O=C | Persist/match Save proof, then adoption intent on explicit continuation. |
| Save proof persisted but ledger not ADOPT_INTENT; P=S, O=C | No automatic move. Revalidate both and complete intent on explicit Resume. |
| ADOPT_INTENT; P=S, R absent, O=C | Before first rename; explicit Resume may perform steps 2–4. |
| ADOPT_INTENT; P absent, R=S, O=C | First rename happened; explicit Resume may perform steps 3–4. |
| ADOPT_INTENT or ADOPTED; P=C, R=S, O absent | Adoption finished; verify hashes/ownership, finish ledger ADOPTED if needed, then independently reverify on explicit Resume. |
| COMMITTING, canonical file promoted by existing protocol; R=S | Delegate final-output reconciliation to existing commit/inspect logic; do not recreate P or fetch network. Keep R until matching visible success is known. |
| SUCCEEDED with matching published receipt; R=S and/or empty owned D | Cleanup exact raw/workspace only; never re-publish/re-verify via active-commit API or modify terminal Save. |
| Duplicate raw/output paths, both P and O contain C, wrong hash/extent, unknown child, unexpected final path, mixed generation, or missing unique source in a pre-adoption state | RECOVERY_REQUIRED; preserve material and show exact bounded recovery category. Never guess which copy is authoritative or overwrite. |

Hash equality alone does not authorize deleting an unowned file. A durable intent, exact owned generation/path role, expected regular-file checks and content binding are all required. Every rename/fsync/journal boundary gets injected-failure tests, including a write/rename that completed before throwing.

## 10. Cancellation, failure and recovery

### Cancellation

- Immediate existing cancellation/foreground fences stay independent of disk IO. All waits, hashes, parser loops, sample writes and stage boundaries check the original context.
- Cancellation during remux stops new samples, performs bounded cleanup/finalization as allowed, and never makes that output publishable. A close callback cannot resurrect success.
- A blocked file/native call retains its exact worker/handle owner until actual completion. Do not detach it and start a replacement lane.
- Before any filesystem uncertainty is presented as a cancellable recovery wait, persist ledger disposition DISCARD_ONLY when cancellation was acknowledged durably. An explicit “recover” for DISCARD_ONLY may reconcile ownership and clean, never publish the abandoned output. Restarting saving requires a fresh user task.
- Save task terminal state is authoritative over ledger disposition: CANCELLED or FAILED forbids CONTINUE/publication even if a crash or failed ledger write left CONTINUE. Recovery may repair that exact ledger to DISCARD_ONLY and clean only after ownership/release checks. Prefer recording cancellation and disposition in the same existing database transaction; if separate, terminal-first ordering plus mandatory reread enforces the invariant. A cancellation that loses to SUCCEEDED must never mark that workspace DISCARD_ONLY or authorize canonical-media removal. Test terminal CAS success then disposition-write failure/restart, inverse write uncertainty, and success-winning-cancel.
- If cancellation occurs between renames, keep known source and candidate until safe reconciliation. Do not run generic failure cleanup blindly over P. Known, fully released and uncommitted owned material can be discarded after exact-role reconciliation; unknown state remains visible RECOVERY.
- Preserve the existing final publication race: a cancellation that loses to SUCCEEDED does not turn committed media into failure.

### Failure classification and lane release

- Finalization error with all channels positively closed: invalid/incomplete output or local storage failure; no native poison. Preserve raw and record repairable local state when useful.
- Unlink/fsync/cleanup error with no live handles: ledger CLEANUP_PENDING/RECOVERY; release Direct local permit and execution claim normally after safely recording outcome. Display retained-material status. Further work sees actual reduced free space.
- Proven unconfirmed descriptor/native release: aggregate a memory-only release gate into Coordinator `resourcesUnreleased`, runtime `executionReleaseUnconfirmed`, admission and retirement. Hold original resources and batch owner; no publication, no replacement workers. Follow existing distinction between temporary draining and explicit restart poison.
- Journal/rename result uncertain: reread authoritative row and classify files; never blindly repeat. If classification remains uncertain, WAITING_USER/RECOVERY rather than a normal retry loop.
- Add normalization-aware failure handling before Coordinator's current `finishFailure` cleanup at `:759–762`. An adoption intent or unknown publication state must not be deleted by the old generic pre-COMMITTING cleanup branch.

### Startup and explicit continuation

Startup may perform bounded READ-ONLY ledger inspection and existing task interruption/wait transitions. It must not remux, finish adoption, run native verification, publish, reacquire network context or delete ambiguous material automatically. Explicit Resume acquires the same Direct claim, then the Direct local lane, validates current generation/state and performs only the needed local continuation. Network policy and session expiry do not block an already completed valid raw local continuation.

Expose a typed cleanup-pending status for terminal residues, since terminal Save rows cannot be resumed. Do NOT add a general file-deletion control or broaden existing user-media deletion actions. The existing task recovery panel may provide this feature's narrowly scoped, explicitly confirmed “clean this task's remaining temporary material” action. Bind confirmation to the exact task/artifact/normalizationId, ledger revision and checked disposition/publication snapshot; stale confirmation cannot act on another generation or changed ownership. A `retryOwnedNormalizationCleanup` entry point may touch only exact ledger-owned raw/output temporary paths whose state makes deletion safe. It never touches unrelated, unknown or canonical published files, never recursively removes unknown entries, and never changes the immutable terminal receipt. Normal post-success cleanup still uses the existing cleanup hook without an extra prompt. Pending discovery uses an indexed bounded ledger query, never an arbitrary directory/historic-task scan. All cleanup authority comes from exact existing task/workspace ownership and the corresponding confirmed recovery action, never filename matching alone. If ownership/publication is still ambiguous, confirmation does not authorize guessing or deleting it; retain RECOVERY and explain the blocker.

## 11. Disk bounds and Direct2

Current per-operation limits stay raw <= 2 GiB, canonical output <= 2 GiB, cumulative network charge <= 4 GiB. Conversion adds no network bytes. Keep the existing 1 MiB journal reserve in availableBytes.

- Second Direct network admission continues requiring >= 4 GiB reliable available space under the existing versioned two-phase admission check. This is conservative raw-download headroom, not a reservation guaranteeing later conversion.
- Before EACH new normalized output generation, after acquiring the local lane, require >= full 2 GiB current available space, regardless of raw size or earlier checks. Unknown/negative/overflowed/failed/insufficient reads refuse conversion with storage guidance before creating output. Recheck identity/configuration where appropriate; do not hold admission locks during filesystem probes.
- Every seek/write/truncate enforces the output extent cap and free-space policy. Growing sparse seeks cannot evade cap; final moov writes/close are also bounded. Concurrent raw network writes still use their own per-write free checks. No claim of atomic reservation against downloads, exports or other processes.
- While two active raw inputs coexist, one converter output adds at most 2 GiB. Holding the local lane through adoption, verification, commit outcome and the normal cleanup attempt prevents a second normal conversion output from overlapping the first normal dual-file lifecycle.
- If A moves to WAITING_USER or succeeds with cleanup pending, its retained raw/output becomes explicit residue, not part of the “normal successful active batch <= 6 GiB” statement. Releasing its local permit is correct once all resources are closed. B may proceed only if its fresh full-output free check passes. Total stored payload can exceed 6 GiB; documentation/UI must say so.
- Do not freeze a native owner forever to enforce a disk budget. If review demands a hard bound including all residues, that requires an additional durable disk-reservation policy and is outside this minimal proposal.

## 12. Source touch map

Core Save/domain:

- `save/.../SaveModels.kt`: Direct normalization proof, output descriptor, normalized-evidence invariants and subphases; preserve source transfer/network accounting.
- `save/.../SavePorts.kt`: normalization/local-stage ports and typed verifier input; existing storage publication interface remains semantically unchanged.
- New `DirectLocalProcessingGate.kt`, `DirectNormalizationContracts.kt`, `DirectRemuxConservation.kt` and pure recovery-policy model.
- `SaveCoordinator.kt`: post-download local stage, exact source/output selection, intent-safe exception handling, local resume, strict final CAS and post-success cleanup outcome.
- `SerialArtifactVerifier.kt`: typed descriptor adaptation only; existing serial/drain/deadline behavior remains.
- `RecordingVerification.kt`: preserve strict method discriminators; Direct normalization is separate from Stream/Record expectations.
- `SaveNetworkGate.kt`: completed raw transfer stays local-only; verify semantics, do not introduce network needs for normalization.

Android Save/storage:

- `SystemHttpsDirectTransfer.kt`: narrowly add `video/quicktime` negotiation/acceptance; preserve response framing/identity, range, TLS, privacy and byte limits. No WebM admission.
- `PageSaveDetector.kt`: MOV hint may offer an explicit Direct attempt with “compatibility check required”; hint is not supported-container/codec proof. Do not broaden all unknown extensions or WebM.
- New `save/normalization/MovProfileProbe.kt`, `MovRemuxEngine.kt`, `OwnedNormalizationInput.kt`, `BoundedNormalizationMuxerOutput.kt`, adapter/session owner and output-conservation scanner as needed.
- New storage `NormalizationWorkspaceSchema.kt`, `NormalizationWorkspaceCodec.kt`, `NormalizationWorkspaceProtocol.kt`; additive DB migration 8→9 and bounded pending query.
- `AndroidSaveStorage.kt`: exact ledger/path/reader/writer access, new cleanup hook, file-role resolution; preserve terminal immutability and SUCCEEDED publication transaction.
- `PrivateCommitProtocol.kt`: preferably unchanged commit/inspect logic; expose/reuse reviewed file-ops helpers through the new workspace protocol. Do not weaken `.hls` cleanup allowlist.
- `SaveRecordCodec.kt`: versioned normalized proof/evidence, legacy readers.
- `library/storage/LibraryPublicationEvidence.kt` and `save/MediaTaskActivitySaveOutcome.kt`: use the record-selected output descriptor for strict publication and final committed/Discovery feedback; source transfer remains raw and unchanged.
- `AndroidLocalMp4Verifier.kt`: typed output descriptor, extra Direct-remux evidence verification; no relaxation of existing MP4/decode guards. `Mp4ContainerProbe.kt` unchanged.
- `AndroidSaveRuntime.kt`: one Direct local gate, release-state aggregation, local Resume avoids credential lease creation, visible residue status and a revision-bound confirmed exact-task temporary-cleanup action, without generic file deletion; existing fixed transport pool and exact claim retirement preserved.

UI/tests/build-contract bookkeeping:

- Save phase/status/failure/recovery resources and paired EN/ZH strings, typed UI presentation, existing aggregate contract-input list in `app/build.gradle.kts` if new source/resource contract tests require it.
- Keep SkinKit, AndroidManifest, permissions, Gradle dependency graph, notices, HLS/Record/editor engines and billing unchanged.

## 13. Required review and tests before implementation acceptance

### A. Pure contracts and concurrency

- Source HTTP length/MIME/size never replaced by output facts; output larger AND smaller than raw supported correctly; network charges unchanged.
- Direct proof binds exact task/source/artifact/commit/generation; Stream/Record proof cannot be forged into Direct.
- Default MP4 behavior, expected-audio semantics, free entitlement and zero-provider behavior unchanged.
- Two network transfers overlap at limit 2; only one Direct local stage enters. No reverse gate order, no exclusive acquisition from a Direct claim, no native Future queued while waiting.
- Cancel/deadline/background while waiting, normalization, adoption, verification, publication; stale/duplicate workers and claim releases; lowering 2→1; unrelated B continues after ordinary A failure.
- A real release-uncertain failure blocks B publication and claim retirement; later actual draining release behaves like existing gates. Cleanup-only errors do not poison native state.
- Full-output free check on every conversion, unknown/negative/overflow/insufficient space, two concurrent raw writers, output growth and finalization writes; reserve retained.

### B. Storage and wire fault matrix

- v1–v6 Save and v0–v3 evidence reads unchanged; v7/v4 round trips; no invented normalization proof; malformed/oversized/mismatched proof rejected.
- New DB and every supported 1–8 → 9 additive migration preserve Save/Library/export/editor/destination rows and foreign keys. Terminal CAS remains immutable.
- Failure before/after every create, ledger write, Save proof CAS, rename, fsync, adoption ACK, verifier result, commit intent/promote/index, SUCCEEDED CAS and raw unlink.
- Write/rename succeeded then threw; duplicate/stale generation; same bytes at an unowned path; symlink/hardlink/nonregular/collision/unknown child/cross-filesystem; duplicate raw/output representations.
- No copy of raw, no overwrite of unique raw, no partial output published, no committed media deleted; uncertain publication retains source. RAW_COMPLETE/half-adoption with task transfer/proof absent or unreadable cannot reach network onRestart/truncate; unknown ledger lookup is not absence.
- Successful receipt with failed cleanup remains successful and discoverable; pending cleanup query includes terminal rows and is bounded/paginated. Recovery cleanup never resurrects a deleted Library item.
- Cancellation DISCARD_ONLY survives restart and cannot resume into publication.

### C. Exact pinned extractor/muxer host evidence

- Run approved pinned 1.11.1 bytecode against original owned MOV fixtures, not copied third-party media.
- Accepted minimal MOV AVC silent and optional AAC-LC; generated output parsed independently as MP4; encoded sample/config/count/payload/timing conservation, beginning/middle/tail and EOS duration rules.
- Both metadata orderings, supported sample/chunk layouts, sampleData fragmentation/offset and internal RESULT_SEEK behavior.
- Reject malformed/oversized boxes and sample tables before allocation, truncated tail, external references, unknown critical semantics, all edit lists, nonidentity rotation/matrix, B-frames/ctts, changing config, extra tracks, unsupported AAC, encrypted entries/PSSH/crypto flags and ambiguous protection.
- Output bound on write/seek/truncate/finalization; channel release after finalization exception; disable sensitive Media3 diagnostics.
- Host parsing/mux tests prove bytes and policy only, not Android decode/playback.

### D. Authorized device acceptance, separately scheduled

Use only emulator-5556 and owned entropy-valid fixtures after independent source review and permitted build/install, preserving existing data/signature and versionCode policy. No public media, other device, account, credentials or new permission.

- Actual MOV input → independently verified playable MP4, with and without AAC, accurate geometry/duration/A/V tail.
- Original MP4, HLS and Record regressions; unavailable decoder/timeouts produce no success.
- Two Direct tasks, cancellation during normalization/native verification, foreground interruption, process death/adoption Resume, cleanup residue recovery, exact native/file release and UI states.
- Cannot label support accepted until this gate passes; no broad device/codec/performance claims.

## 14. Implementation slices and stop conditions

1. Independent review of this design: resolve proof structure, ledger authority, no-replace rename/fault matrix, parser bounds, cancellation disposition, lane/cleanup semantics and the exact-task temporary-cleanup confirmation and revision binding (no general file deletion). No source changes before acceptance.
2. Contracts + additive persistence/recovery with fake normalizer only in tests; production MOV still unavailable. Prove legacy compatibility and crash matrix.
3. Restricted pinned MOV extractor/muxer adapter and host fixtures; no production admission until evidence review. No WebM or transcode.
4. Wire free Direct admission/local stages/strict verification/recovery UI. Full source regression and independent review. Report source-ready/device-NOT_RUN honestly.
5. Authorized preserved-data emulator acceptance. Only after it passes, document exact supported MOV profile and remaining limitations.

Stop or narrow the slice if no bounded raw-structure/track/DRM proof is available, pinned API behavior violates conservation, final MP4 verification cannot establish acceptance, or any ownership/recovery ambiguity cannot be resolved without overwriting/deleting unknown material. Do not “fix” such gaps by widening MIME/container acceptance or treating a mux callback as success.

## Adapter-entry refinement after pinned-clock review

See `MOV_CLOCK_AND_CANONICAL_CONTRACT_20261014.md`. The first production adapter
profile is silent-only: an audio track rejects the entire MOV before muxing.
Optional AAC remains a model/transport-test target until bounded executable AU
validation and real PCM-frame/timestamp evidence are independently tested. No
audio track may be silently dropped. Exact 90 kHz video/10 kHz movie clocks and
the finite admitted frame-rate set further narrow this first profile. This is
useful only for already-silent compatible sources, not typical camera MOV or
general MOV support; completing AAC verification remains queued original scope.
