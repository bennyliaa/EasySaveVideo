# Next minimal slice: registered asynchronous raw anchor and paired guarded loader

External design only, 2026-10-14. Current 29-file source freeze and Direct `NOT_ISSUED` gate remain unchanged. This proposes actual input capability/loader mechanics, not conversion completion or publication. No new dependency, parser, executor lane, reservation registry or upstream source copy.

## 1. Concrete implementation boundary

Implement the shared-reservation async anchor and bounded logical-reader lifetime, then a public Media3 paired loader which actually drives the existing owned guarded input. Test these production objects with registered owner/syscall seams and pinned public interfaces. The first slice can stop here: normal Direct selection/execution remains unavailable; no conversion proof, verification, adoption or commit is issued.

Reuse the storage map `../durable-contracts/ASYNC_STORAGE_PAIR_TOUCH_MAP.md` (SHA-256 a9ba47b6583948921655b56c27bc8a500b2fc4e949ca55f308cd6b756ea95683). Its same owner/reservation/Scope, fresh-raw witness, acknowledged WRITING CAS, generated paths and recovery gates are mandatory. The following is its input-side handoff, not an alternative storage architecture.

Both `OwnedMovRemuxAttempt.check()` and `AndroidNormalizationFiles.Descriptor.call()/close()` enforce opening-thread ownership. Neither changes. Add a distinct async descriptor held in the SAME files owner, resource snapshots and reservation. All actual read handles retain the same captured RAW FD/inode/extent and use positional reads. No file/path/FD is exported to the Transformer mechanism.

## 2. Narrow private signatures and authority

Proposed app-internal signatures; names are new and unavailable until implemented. Existing core stays platform-neutral.

```kotlin
// Existing storage owner, not a public converter or user-created DTO.
NormalizationAttemptOwner.prepareTranscode(
    owner: NormalizationOwner,
    context: OperationContext,
    reservation: NormalizationIoReservations.Token,
    scope: AndroidDirectExecutionBridge.Scope,
): OwnedDirectTranscodeAttempt

// Concrete owner-issued class, constructor private; owner registry holds reference identity.
internal class OwnedDirectTranscodeAttempt private constructor(/* captured owner state */) {
    internal fun requireRegistered()  // exact owner/key/token/scope; memory-only live checks on callbacks
    internal fun bindWebmInputOnce(): OwnedWebmInputBinding
    // Subsequent writer/finish methods require a separate implementation/review gate.
}

// Concrete pair, constructor private; a caller cannot provide a source, factory or timeline.
internal class OwnedWebmInputBinding private constructor(/* exact registered attempt */) {
    internal fun probe(): WebmTransportFacts
    internal fun createMediaSource(session: TransformerSession): MediaSource
    internal fun completedTransportFacts(): WebmTransportFacts
}
```

`bindWebmInputOnce()` must call back to the issuing owner and register the exact returned binding against its attempt; constructor visibility alone is insufficient. Repeat bind, foreign attempt, retired/revoked token, changed generation and mixed Scope fail before retain/open/factory creation. Neither `WebmTransportFacts` nor a caller-provided `WebmOwnedInput` issues a binding. Source facts/byte-array snapshots are fixed defensively by that owner, not passed back as authority.

The binding privately owns the existing `TransformerOwnedSource` view (`retainNativeReader`, `openReader`), the exact Webm positional borrowed-input view, the cumulative budget, expected track snapshot, opaque route and both Media3 factories. It exports only a MediaSource built from the fixed private MediaItem and both internal paired factories, never an arbitrary-item factory or independently composable DataSource/Extractor factories. The route is an exact random local technical identifier, not an allowed File/URL prefix. One finite item, no clipping, ads, DRM, sidecars, stream keys, custom cache key or externally supplied tag. createMediaSource(session) binds the exact session reference once through the issuing owner registry; repeated factory creation, foreign or replacement sessions fail before retain/open. All native source retentions belong to that same registered session; only its actual confirmed native cleanup may retire them. An unrelated empty session.finish or another session callback is never release evidence.

Future Direct activation must obtain target, fixed timeline/profile and loader from ONE owner-registered run capability. The existing separate `execute(spec, target, session)` Editor entry must not let a Direct caller supply an independently constructed target. Either add a private `executeDirect(issuedPair, session)` or explicitly verify target reference against the same registry before any resource action. Do not replace `Unissued` in this input-only slice.


### Exact shared worker-entry fact

A context checkpoint or occupied fence does not prove that the session entered the shared worker. Add the minimal exact-session entered lifecycle to the existing production execution seam: only the actual accepted worker beginning execution establishes its session entry; final worker retirement revokes it monotonically. No caller Boolean, new executor or lane is added. Native loader DataSource.open/read/retention requires that exact registered session's entered, non-retired invocation plus the existing live scope/context. Queued cancellation, foreign session, completed old invocation and mere session construction cannot open a native reader. Storage preparation and the separately authorized owned preflight retain their existing Scope/prepare authority; they do not claim to be native-loader entry. The implementation API is reviewed with the existing runner/session delta.

## 3. Stage/open ordering and retained cleanup

`NormalizationIoReservations.advance()` currently requires zero calls and zero obligations. Preserve it:

1. Reuse actual fresh RAW_COMPLETE witness and acknowledged WRITING preparation, including exact empty OUTPUT and expected RAW identity. This input-only slice does not issue or retain an async output anchor/writer. Existing PREPARE exclusive empty OUTPUT creation, fsync and confirmed temporary-FD close remain mandatory and complete before stage advance. prepareTranscode receives the actual Scope and the owner composition verifies its exact issuing bridge; a caller-supplied Scope-like object or live Boolean is insufficient. Finish/close all preparation-only resources positively.
2. Advance PREPARE → TRANSCODE while calls/obligations are zero.
3. Register the new anchor obligation BEFORE entering each async open/construction; capture the WRITING-stage raw bytes once from the existing PRIMARY role (not the later-adoption raw.bin role; no early rename). A complete input preflight and the loading pass use this same anchor. Earlier selector facts are only candidates and do not substitute for that anchored validation. Shared cumulative IO/event limits are not replenished per preflight, extractor or logical open. The async path uses one attempt-owned short-lock/CAS budget: request bytes, physical bytes, events and cumulative opens are atomically reserved before delegate IO/allocation; failed or short calls do not refund that conservative reservation. Concurrent readers cannot each spend the same remaining credit. No budget lock spans syscalls or native callbacks; the existing synchronous preflight semantics stay unchanged.
4. Input logical closes revoke only their own cursor. They cannot close the anchor while any native retention or accepted read remains. Up to four live readers share positional IO but have independent cursor state; retired handles never reopen. A second open while a DataSource is open fails. Bound cumulative opens as well as concurrent readers. Each logical cursor serializes accepted read/close state: close first revokes that exact cursor; a closing cursor with accepted calls still occupies its slot until actual drain. A stale handle cannot close/revoke a replacement cursor. Four blocked reads followed by logical close still prevent a fifth cursor until at least one actually drains.
5. Stop/revoke is memory-only and rejects new calls. Accepted calls drain; a late returned read cannot become successful after revoke. No owner monitor is held across pread, native cleanup or waiting. A separate irreversible release-only path remains usable after cancellation or Scope exit: it cannot open/read/issue facts, never requires requireLive to attempt cleanup, and closes each positively drained owned handle exactly once. Cancel cleanup needs no successful final hash; confirmed close retires that handle obligation, whereas unknown release stays retained. Success hashing and any future FINISH still require live authority.
6. Real shared-session native release precedes anchor final hash/identity validation and positive actual close. An uncertain open/close/call retains the exact obligation/anchor, poisons the same registry and blocks recovery/reuse. No Future cancellation or file-descriptor validity guess releases an obligation.
7. Only a later owned conversion-finish implementation may close all raw/output anchors, prove zero calls/obligations and advance TRANSCODE → FINISH. Input-only success is not that event. Tests and failed runs retire or abandon through the existing exact cleanup/recovery contract; they never invent a completion.

If implementation wants to retain a RAW_PROBE anchor through PREPARE instead, that needs a separately reviewed exact retained-obligation transfer. Do not weaken general `advance()` to accommodate it. `Operation.exactCheck` performs durable SQL checks and must not be copied unchanged into per-read native callbacks: capture the original live Scope/token/context memory check, with durable exact-record checks at bounded prepare/finish sections.

## 4. Public pinned bridge and complete guard

Verified against the admitted local `media3-exoplayer:1.11.1` AAR, public API includes:

```java
new ProgressiveMediaSource.Factory(DataSource.Factory, ProgressiveMediaExtractor.Factory)
ProgressiveMediaExtractor.Factory.createProgressiveMediaExtractor(PlayerId)
ProgressiveMediaExtractor.init(DataReader, Uri, Map<String,List<String>>, long position,
                              long length, ExtractorOutput)
ProgressiveMediaExtractor.read(PositionHolder)
ProgressiveMediaExtractor.seek(long position, long timeUs)
ProgressiveMediaExtractor.getCurrentInputPosition()
ProgressiveMediaExtractor.release()
```

The custom adapter must create `WebmExtractorFactory(binding).create()` and receive that extractor's OWN single `WebmGuardedInput`; pass this exact instance to sniff/read. After a successful sniff, resetPeekPosition on that same guarded input before extraction, preserving buffered physical read-ahead and all cumulative budget charges; do not recreate the input or rewind the underlying DataSource. Preserve `BoundedWebmExtractor.owned(candidate)`, binary callback identity, full peek/read/skip/range/depth guards, no seek-reset and fixed-extent EOF checks. Do not return a stock MatroskaExtractor or use the ordinary BundledExtractorsAdapter, which creates a different DefaultExtractorInput.

Minimal addition to `WebmInputBinding`/`BoundedWebmExtractor`: an internal single-use owned sequential-reader input-construction path, accessible only through the concrete registered paired bridge. Keep the current synchronous `newInput()` behavior and all `FOREIGN_INPUT` tests intact. Do not add `newInput(arbitrary DataReader)` as a public capability shortcut.

### Exact framework reader linkage

Pinned `ExtractingLoadable` first opens its DataSource, then supplies a StatsDataSource DataReader to `init`; it uses `getCurrentInputPosition()` to account progress and resumed positions. Do not ignore this DataReader or rely on casting through its private fields.

Use one small private pending-read receipt inside the pair:

- The guard's owned sequential DataReader establishes a single-use pending read containing its exact pair/generation, current source offset and original buffer identity/offset/request before calling the framework DataReader.
- Only the paired DataSource.read may consume that pending call. It verifies the same cursor and tuple, reads the registered fixed anchor, records returned count after successful owned IO, then returns.
- The owned sequential wrapper checks the same call was consumed once and returned the identical count; wrong/missing/duplicate receipt, cross-pair source, unexpected reentry or mutated tuple fails sticky. No buffer/data is retained after the call. This is a per-call route receipt, not a general scheduler or authorization Boolean.
- DataSource returns no response headers; in particular it cannot enable the pinned IcyDataSource metadata path. No redirect/caching/network source exists. The pinned StatsDataSource forwards the byte array unchanged; the receipt ensures the actual supplied chain traversed this paired source.
- Header suffix look-behind remains direct positional `WebmOwnedInput.readAt` over the same anchor and budget; it never changes the sequential cursor. Existing guard-request charging remains in force, and physical reads share the same bounded attempt allowance. Do not accidentally grant a fresh allowance per view.

The adapter accepts initial position/time `(0, 0)` only, rejects RESULT_SEEK and unexpected reinitialization/reopen; normal pinned load-control pauses retain the same extractor/input. When loader EOF is reported, require `extractor.finish()` immediately and exact extent/complete pending samples, then store its immutable transport facts once. `getCurrentInputPosition()` reports the guarded consumed position, not physical peek read-ahead. Release is exactly-once and does not itself prove raw/source/native release. The full profile installs an explicit no-retry load-error policy; unexpected reopen is unsupported until an exact compatible case is proven with pinned tests. Framework closeQuietly may swallow DataSource.close exceptions, so the pair independently latches any logical-close/descriptor/native-release uncertainty before returning or throwing. Subsequent completedTransportFacts and all future success handoffs check that sticky state. Extractor EOF or an onCompleted callback alone cannot erase a swallowed close failure.

Preflight and loading must agree on fixed source identity, extent, declared duration, track IDs/types, geometry, audio format and configuration bytes. No source video/CFR or audible duration may be inferred from compressed sample count. Mandatory shared visible-frame/S/E observers remain separate native facts and the source audible mapping remains unverified.

## 5. File ownership/touch map

Storage worker owns, reusing the referenced map:

- New `save/storage/OwnedDirectTranscodeAttempt.kt` and a narrowly scoped async descriptor if needed.
- `AndroidNormalizationFiles.kt`: register async anchors in existing hasLeases/requireReleased/pending/poison accounting, actual positional calls and positive close; preserve original Descriptor.
- `NormalizationIoReservations.kt`: only explicit TRANSCODE transitions; existing retain/call/retire semantics stay.
- `NormalizationAttemptOwner.kt`: registered fresh preparation/attempt/binding, actual lifecycle guards.
- `AndroidControlledDirectStorage.kt` / `AndroidSaveStorage.kt`: exact scope/owner composition and unreleased-resource inclusion, with default selector unavailable.

Paired-input worker owns:

- New `save/conversion/OwnedWebmInputBinding.kt`: concrete registered pair, private route/receipt, combined MediaSource factory and immutable transport-facts comparison.
- New `save/conversion/OwnedWebmProgressiveExtractor.kt`: small public API adapter; no EBML implementation.
- `WebmOwnedInput.kt` and `BoundedWebmExtractor.kt`: narrowly add exact owned sequential-input construction, preserve all existing preflight behavior and policy.
- Focused tests under `app/src/test` and existing pinned WebM host harness; no dependencies.

This slice adds only the exact entered/retired lifecycle seam to TransformerSession/SessionExecution/Runner and its tests; it does not change TransformerEngine, Direct run-spec, native observer, output muxer, verifier, save proof schema or publication. Its factory can be tested directly against the production paired objects. Actual Direct execution is a subsequent single-owner pairing change; it will consume the existing session/observer machinery, never construct a paid Editor request.

## 6. Required acceptance tests before any activation

Actual owner/async-descriptor tests: genuine registration; absent/foreign/stale scope; lost WRITING acknowledgement; stage advance while obligations held rejected; two overlapping positional logical readers; same original inode for sequential and suffix; fifth reader refused; four blocked reads then close retain all four slots until actual drain; stale close cannot affect a new cursor; cumulative open cap; two readers racing the last request/physical/event credit permit only the winner to reach pread; concurrent close/read and revoke during IO; logical close while native retained; exactly-one real close after drain; pending-open/close throw retains and poisons exact owner; cancellation/Scope exit followed by positive release-only close retires only the exact drained obligation without success hash or restored authority; original MOV thread-affinity tests unchanged.

Actual paired adapter/pinned tests: correct DataSpec and opaque route; mixed factory/source; repeated factory issuance and session A/B substitution; empty/foreign session.finish cannot retire actual loading session retentions; arbitrary DefaultExtractorInput still FOREIGN_INPUT; missing/duplicate/read-tuple receipt; StatsDataSource real forwarding; sniff/resetPeek/read preserves both cursor accounting and consumed budget; closeQuietly-swallowed failure remains sticky and forbids completed facts; explicit no-retry policy; suffix leaves cursor unchanged; all existing malformed/huge/unknown-master/peek allocation boundaries through the LOADING path, not only preflight; load-control pause preserving one guard; nonzero seek/reinit/retry rejected; physical read-ahead versus consumed position; exact EOF/truncated child/pending sample; unexpected header/sidecar; cancellation original exception survives parser wrapping and no late facts after stop. Constructor/MediaSource creation alone must not open/retain; queued, foreign and retired native sessions are rejected by the actual worker-entry fact, not merely a checkpoint.

No test may label this as a playable conversion or storage proof. Native marker/audible mapping, actual Transformer native cleanup, output quality, same-FD final raw/output hash, typed conversion completion/CAS, independent decode verification and commit remain explicit later gates.

## Static API evidence

Only locally admitted bytecode was inspected, no build or download. AAR SHA-256: `588592baec110837c33ce0c82bb76464203e6b0621177bbe5d88779001a6f1ed`; extracted classes.jar: `64ac1eea82cc28d6340e5d91391d14d3b0f352304dbd857d9c0c7131d4a2087f`. Public signatures and ExtractingLoadable control flow are recorded in `paired-input-design/progressive-bytecode.txt`. These establish API availability, not runtime acceptance.

## Fixed local-input budget clarification

The input-only implementation fixes these allowances once for the registered attempt, after validating raw extent <=2 GiB and using checked Long arithmetic: physical reads min(8*extent+4 MiB,32 GiB); parser request charges min(32*extent+16 MiB,32 GiB); 2,000,000 events; four live/closing cursors and eight cumulative opens. Preflight, loading and suffix views share all applicable limits without refund or pair-reset. These are independent finite local-processing limits, not remaining network transfer allowance or raw-plus-output disk headroom; existing network, storage-size and deadline budgets are not enlarged or reinterpreted. Future final hashing needs its own bounded reserved work and cannot reset this pair. Test cap-1/cap/cap+1, checked arithmetic and concurrent readers competing for the last credit before delegate IO.
