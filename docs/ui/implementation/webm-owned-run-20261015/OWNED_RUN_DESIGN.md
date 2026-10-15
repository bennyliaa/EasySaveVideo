# One actual owner-bound WebM Direct run

2026-10-14. Implementation design, not implementation or production admission. Read-only inspection of the current ESV source and already local Media3 1.11.1 sources; deliverables are outside the source tree. No build, host test, device operation, network access or GitHub action was performed.

## Decision and useful next outcome

Use the existing `OwnedDirectTranscodeAttempt` itself as the run capability. Add a one-shot method that privately selects the timeline, creates the real session, invokes the existing concrete `TransformerEngine` through `runTransformerSession`, captures source completion while the worker entry is active, retains the actual returned outcome, then calls the existing same-FD `sealResources`. No second owner, run registry, generic run DTO, factory injection, lane, parser or dependency is needed.

The first executable implementation stage is a **genuinely silent VP8 file → H.264 MP4** through the actual Android Transformer. This is explicitly staging, not completion of the requested VP8/optional-Opus product. An input containing Opus is never silently made silent; it is either processed by the same audio-aware path once its narrowly specified audible-boundary gate passes, or rejected before encoding as not yet admitted. Preserve the real mono/stereo Opus fixture and implementation work described below. Host ownership tests cannot open either Android playback or production admission.

The tangible run-stage result is a sealed, bounded output produced by the exact pair/target/session, with actual source/muxer observations and positively confirmed releases. It is not yet a Library item. The immediate next handoff is the existing durable `DirectConversionProof`/workspace OUTPUT_READY shape; independent output full decode, adoption and the existing commit protocol remain required for a user-visible playable conversion.

## 1. Current reuse and remaining gaps

Already implemented:

- One registered attempt and the existing `transcodes`, `inputBindings`, `inputSessions` identity maps.
- `bindWebmInputOnce`, bounded complete `probe`, paired `createMediaSource(session)`, one RAW retained anchor and the same complete extraction policy in probe and actual loading.
- `ownedTarget(binding)`, one existing OUTPUT inode, one logical writer, exact session writer/source pins, finite active/finalization/hash budgets, same-FD hashes and positive closes in `sealResources`.
- Shared `TransformerEngine`, `TransformerNativeFactories(... VP8_OPUS, observer)`, displayed-frame observer, source-consumer S and encoder-input E ledgers, bounded real Java MP4 muxer, the one process-wide session runner and release fence.
- Durable conversion expectation/settings/completion/proof and verification shapes and codecs. These are data, not issuance authority.

Missing:

1. Owner-private execution and owner-held actual outcome. `execute(spec,target,session)` remains Editor-only and rejects the freely constructible Direct spec.
2. A declaration-derived fixed candidate timeline. The guard validates DefaultDuration but discards it. Pinned Matroska does not place it in `Format.frameRate`.
3. A real Direct loader factory that binds the actual callback item, the exact preconstructed MediaSource and mandatory observer hooks.
4. Finish facts captured before `TransformerWorkerEntry.retire`. Calling active pair/observer getters after `runTransformerSession` returns is too late.
5. Typed producer finish/CAS and independent converted-output verification; `TRANSCODE → FINISH` is still intentionally closed.

## 2. Minimal concrete issuance API

Proposed names below are exact intended roles; do not add public polymorphic executor/factory seams to implement them.

### Storage composition and entry

- `AndroidSaveStorage` retains its application Context and lazily constructs one **concrete final** `TransformerEngine`, just as the Editor wrapper already does. Supply that concrete instance to its existing `NormalizationAttemptOwner`; a nullable/inactive default is acceptable only to preserve unrelated existing tests and must fail before work. Do not change `AndroidSaveRuntime` in this stage.
- `NormalizationAttemptOwner.runOwnedTranscode(attempt): Unit` checks the exact map member, Scope, original OperationContext, original SaveRecord and WRITING row, and invokes the same attempt's internal one-shot method. It accepts no profile, target, session, source factory, result or Boolean.
- `OwnedDirectTranscodeAttempt.runOwnedDirect(): Unit` obtains the owner's captured concrete engine, consumes a private `runStarted` flag before probing, and stores all run-only state on this attempt. It may be triggered only for the existing registration. A failed/rejected/cancelled invocation consumes it permanently; there is no reset or second encoding attempt on WRITING.
- `TransformerEngine.executeOwnedDirect(attempt): TransformerEngineFacts` is the one new mechanism entry. Its first action is a one-shot exact-attempt claim that checks the already-entered captured session, captured concrete engine identity, current run phase, exact pair registration and Scope. It takes no independent target/spec/session/factory. All mechanism reads use the attempt's captured slots, guarded by the same identity check.

Keep `TransformerEngine.execute(spec,target,session)` and `requireIssuedTransformerRun` Editor-only. The freely constructible `DirectVp8Opus(Unissued, output)` must still fail before I/O. Do not turn that data constructor into authority simply because the new owner-only path exists. Update its comment to distinguish the deliberately unissued data path from the real attempt path. No replacement binding/receipt class is required.

### Attempt-local sequence

1. Check exact registration and immutable Save/ledger snapshots; consume `runStarted`. Bind/probe exactly once on the original RAW anchor. Capture the fixed candidate described in section 3 in private fields. Never accept probe facts back from a caller.
2. Create the real `TransformerSession` itself. Its checkpoint captures this original context and owner's existing stop check, with current Scope liveness. No fresh OperationContext, deadline, permit or native batch is minted. Use the existing session limits type and shared release policy.
3. Store the concrete engine/session before scheduling. Call `runTransformerSession(session)` directly, never a passed lambda executor or alternate lane.
4. In the accepted worker closure, call `pair.createMediaSource(session)` first. This is the existing operation which registers the exact input session. Then obtain `attempt.ownedTarget(pair)`; neither second session binding nor an independent target is allowed. Store the exact source/target and call `engine.executeOwnedDirect(this)` once.
5. The engine's successful active return includes `sourceObservation` from the real mandatory observer. Before returning from that closure, call `pair.completedTransportFacts()` and retain the exact result privately, checking equality with the preflight facts including added timeline fields. Both this and observer finishing must happen before the worker enters cleanup. No getter is deferred until retirement.
6. The runner performs its existing actual cleanup and retires the entry before returning. Only `runOwnedDirect` assigns the **actual returned** `TransformerOutcome` to its private field. Do not expose a `recordCompleted(outcome)` or `finish(facts)` setter. A caller-created `Completed`, engine facts or copied DTO cannot reach this assignment.
7. On actual Completed, recheck stop/Scope and exact immutable snapshots; require mux video samples equal observed displayed frames and the declaration-derived candidate count. Call the existing `sealResources()` once. Compare its OUTPUT size with actual engine bytes and its RAW/hash/source-transfer observations with the same captured transfer. Preserve private run outcome, completion observations and sealed resources until consumed by durable finish.
8. Any failure invalidates tentative completion; preserve original stop/storage error and the existing unconfirmed-release/process-recovery semantics. Both success and error drain the same resources. Normal failure never upgrades to success because cleanup succeeded. Unconfirmed cleanup never permits a new native run.

Use private fields plus a small monotonic phase enum on the same attempt (new/claimed/working/observed/released/sealed/consumed or failed). This is state on the existing owner, not another capability system. Never hold the attempt/owner/session monitor across the runner wait, filesystem I/O or native calls. `requireRegistered()` rejects once sealing starts; the future finish check must use exact owner registration plus sealed-state checks, not weaken that active-work gate.

### Concrete local limits

Use the original OperationContext deadline as the authoritative bound throughout. For the one-item session use maxLoaders=1, maxCodecs=2 for silent or 4 for audio, maxOpenInputs=1, maxInputOpens=1 (the current paired path supports one), maxExecutors=3, stall=60,000 ms and release=10,000 ms. Set native timeout no greater than the remaining original deadline and 3,600,000 ms; reject nonpositive remaining time before scheduling. The attempt's existing nonresetting input budget remains authoritative; session transferred-byte ceiling can use checked `min(32 GiB, 8 * rawExtent + 4 MiB)` without creating another allowance. Probe budget is not replenished at native entry. These values are a proposed finite policy to test, not runtime measurements. The one-open figure reflects current `OwnedWebmInputBinding` itself: `dataSourceIssued`, `extractorIssued`, `PairedDataSource.tried`, zero-position open and one pre-read zero seek already make its production bridge one sequential load. The underlying attempt supports four live logical readers and eight cumulative opens, but that does not establish that the current paired bridge can reopen. Actual Media3 must demonstrate this one-pass path. If an ordinary accepted file causes a legitimate reopen/seek, that is a compatibility blocker requiring a small reviewed extension of the same pairing/budget protocol, not permission to bypass it or silently claim native acceptance.

## 3. Fixed timeline without a new parser or compressed-frame assumption

### Two small additions to existing transport observations

1. At the current `Policy.endMasterElement(TRACK_ENTRY)`, before `super` clears the current track, save that video track's declared `Track.defaultSampleDurationNs` only if the already validated `0x23E383` field was present. Transfer it by existing track number into `WebmTrackTransport`. Enforce TrackEntry context for this field and keep the existing uniqueness/range checks. Missing is null, not a fabricated default. Include it in probe/load equality.
2. Capture the actual official `SeekMap.durationUs` in the existing `ObservedOutput.seekMap` callback; store a bounded nullable `demuxerDurationUs` in `WebmTransportFacts` and compare it in both passes. Preserve `declaredContainerDurationUs` separately. The current guard multiplies fractional ticks before truncation; pinned Matroska truncates ticks first, so exact equality between those values is false in general. Reject inconsistent multiple duration observations; do not derive duration from a compressed last sample.

No `Format.frameRate` inference: pinned Matroska's format builder never sets it. Do not set `EditedMediaItem.frameRate` as a workaround: in Media3 this is a maximum-rate/drop instruction for video. Leave it unset, with no frame-rate effect, speed change, clipping, crop or scale.

### Concrete first-stage declaration policy

- Existing accepted VP8 SDR, square pixels, rotation zero, even dimensions, at most 1920×1080 area and 30 minutes remain unchanged.
- Require positive D=`declaredContainerDurationUs`, positive official demuxer duration M, and `abs(D-M) <= 1000 us`. Use M as the fixed composition duration. No duration estimate fallback.
- Require video DefaultDuration d to match one unique integer r in 1..60 with `abs(d*r - 1_000_000_000) <= r` (at most one nanosecond rounding of 1/r second). Checked integer arithmetic only.
- Choose candidate N=`floor((M*r + 500000)/1000000)`; require N in 3..108000 and `abs(floor(N*1000000/r)-M) <= 1000 us`. This intentionally narrow declared-total-duration policy may reject otherwise playable WebM; it does not claim to support every WebM Duration convention.
- Video source first timestamp must be within the existing zero-origin tolerance; actual visible source observations must subsequently satisfy the existing every-frame CFR/continuity checks. Most importantly, require observed displayed count=N and output mux video count=observed displayed count. N is a declaration-derived candidate checked against decoding, never asserted from `sourceSampleCallbacks`.
- Source compressed sample counts remain transport diagnostics and EOF/equality observations only. Invisible/reference-only VP8 samples may exist without counting as displayed frames. If actual displayed output cannot satisfy the fixed candidate, reject rather than rescan/rebase/drop/duplicate or rewrite the output spec.
- Fix normal-quality video bitrate deterministically at `clamp(width*height*r*16/100, 256000, 20000000)` using checked Long arithmetic; this is the existing Editor original-quality numeric policy without paid controls or EditPlan. Silent audio bitrate=0. Future mono/stereo AAC remains 96000/192000 bps, 48000 Hz, unchanged channel count.
- Freeze these OutputSpec formulas in the Stage A pure policy so Stage B cannot independently choose them: `durationUs=M`; `durationToleranceUs=ceil(1_000_000/r)+1000`, implemented as `(1_000_000L+r-1)/r+1000`; `expectedVideoFrames=N`; silent `audio=null`, `audioBitrate=0`, `audioPrimingToleranceUs=0`. A future admitted audio run uses unchanged source channels/48000 Hz and `audioPrimingToleranceUs=42667`. The 42667 us audio endpoint/effective-duration acceptance limit stays independent of the video duration tolerance and the source Opus metadata bound; a shared muxer's broad timestamp resource guard cannot authorize their addition.
- OUTPUT `maxBytes` is the captured attempt maximum. Define checked `bitMicroseconds=(videoBitrate+audioBitrate)*M`; `estimatedBytes=ceil(bitMicroseconds/8_000_000)`, implemented by quotient plus one only when the remainder is nonzero. `requiredFreeBytes=min(outputMaximum, checked(2*estimatedBytes+32*1024*1024))`. Check every multiply/add in Long and reject overflow/nonpositive invalid input. This estimate is a conservative precondition plus the existing actual write/free-space checks, never a promised output file size.

## 4. Actual callback identity and one source

Pinned Media3 `SequenceAssetLoader` rebuilds the EditedMediaItem to enforce sequence track types. **Do not require `callbackItem === originalEditedItem`.** Its builder preserves the underlying `MediaItem` reference, and `ExoPlayerAssetLoader` passes that same reference to `MediaSource.Factory.createMediaSource`.

- Construct one original EditedMediaItem from the exact `fixedMediaSource.mediaItem` object returned by the registered pair. Leave duration and frameRate unset in EditedMediaItem; the output spec already fixes the candidate and callback duration is independently checked.
- Use one sequence, no loop, video only for actually silent input or video+audio for admitted Opus. Do not ask Media3 to remove a detected Opus track.
- On the first actual AssetLoader.Factory callback, require `item.mediaItem === fixedMediaSource.mediaItem`. Validate effective removeVideo=false; removeAudio=true only for genuinely silent input (the sequence constraint may set it), false for audio. Validate the public effects lists, speed/default parameters, flattening flag and unset duration/rate; exact MediaItem identity preserves its known no-clipping/non-gap configuration. Preprocessing audio processors and `isGap()` are package-private in the pinned class: do not read them reflectively or invent public getters. A freshly constructed item has an empty preprocessing list and the inspected trusted builder copy preserves it. Consume one loader claim and retain **this actual callback item's identity**.
- Create the real ExoPlayerAssetLoader using this exact callback item. Its private MediaSource.Factory accepts exactly `actualCallbackItem.mediaItem` by reference, once, and returns only the already paired source (with the existing prepareSource playback-thread tracking wrapper). It is not a token-to-arbitrary-source resolver. A same-URI clone, second callback, second source request or same-fields item from another attempt is rejected.
- Required MediaSource.Factory settings methods may return this adapter only for the same no-DRM/no-retry policy. Do not delegate them to a stock fallback. Keep one supported content type and no alternate extractor. Configure the paired ProgressiveMediaSource with the existing session-owned download executor, unsupported DRM provider and NoRetry policy before building it, matching Editor's tracked resource handling.
- `onDurationUs` must equal captured M; track count/inventory must equal captured transport inventory; formats must match exact VP8/Opus dimensions/rate/channels, no DRM/HDR, unchanged Opus CSD. Record duplicate type/format change violations explicitly rather than only matching total count.
- The real factory is `TransformerNativeFactories(app, output, session, VP8_OPUS, observer)` and its `sourcePcm` wraps the exact delegated audio consumer. Never use the Editor default AVC/AAC factory for Direct, even for silent input.

Keep common Transformer builder flags, no fallback, native resource counting, GL termination, diagnostics, exact writer retention and bounded muxer from the shared engine. Factor private common functions only where useful; do not add a second executor or a public configure callback. The callback identity claims and observer are local to this actual one-run invocation.

## 5. Active completion, cleanup and resource sealing

`onCompleted` alone is neither source EOF nor positive release. The engine checks actual export encoder/process fields, bounded muxer finalized evidence, observer terminal input/output EOS and displayed count. Its actual active success path then calls `observer.finish()` exactly once and returns those observations in the existing `TransformerEngineFacts.sourceObservation`. The attempt's worker closure captures `completedTransportFacts` before returning. A failure in either discards the tentative success and still runs normal cleanup.

The source getter currently requires DataSource logical close acknowledgement. Keep that requirement: actual EOF should close the loader's DataSource before final export completion. Add a pinned/native order test; if actual ordering differs, fix only the positive logical-close timing at that exact real path, not by moving a live getter after retirement or synthesizing close. Native source retention remains separately pinned until actual cleanup.

After the runner returns, never call observer/pair active methods again. The actual returned outcome and exact source/writer release flags permit sealing; neither a historical session.finish call nor `releaseAcknowledged` alone can manufacture run success. Cleanup timeout/failure, final-table failure, source failure, output/hash mutation or late stop invalidate it. Existing cleanup deadlines and seal reserves remain unchanged.

### Required Direct asynchronous error contract

Do not copy the current Editor-oriented `Transformer.Listener.onError` mapping into Direct: it collapses the received ExportException to EXECUTION_FAILED/UNSUPPORTED_PROFILE and can erase an original Save cancellation, deadline, storage-full or identity error wrapped asynchronously by Media3. Editor keeps its current mapping unchanged.

Use one sticky, bounded **first original failure slot on this same attempt**, populated only by its actual registered input/output/checkpoint boundaries. At the paired boundary, unwrap the existing WebmCallerFailure to its exact original exception before Media3 receives it; retain the actual original SaveFailure/stop/identity exception object, and retain an actual guarded WebmProbeException separately from generic native export classification where applicable. OUTPUT operations and the run's captured checkpoint must cover their actual asynchronous exceptions too. Recording uses exact attempt/pair/session identity even when that very failure has just invalidated liveness; do not perform a new live check that replaces it with a later INCOMPLETE/revoked error. Do not expose a caller-writable `recordFailure(Throwable)` authority or accept arbitrary failures from a free factory. The slot does not create success authority, cannot be reset on retry/cleanup, and retains no payload or emitted exception-message log.

The Direct listener closes over that exact attempt/run: onError completes the session future exceptionally with the recorded original object when present, otherwise uses the bounded known native technical mapping. Existing ExecutionException/CompletionException transport unwrapping then preserves that object. Do not search arbitrary cause chains for a matching message/type and treat an unrelated throwable as a registered original; any alternative controlled cause-unwrapping implementation must prove the cause came from this run's registered boundary. Check the sticky slot again before accepting onCompleted/export facts, before active completion capture and after actual cleanup. A late success callback cannot erase an already recorded failure. A confirmed cleanup does not change the original reason; UNCONFIRMED release is still represented independently and keeps the process-recovery fence.

This contract must be tested through the **actual Direct listener and asynchronous Media3 wrapper path**, not only by throwing the original exception directly from the runner lambda. At minimum cover input cancellation/deadline, output ENOSPC/STORAGE_FULL, input/output identity failure, known native unsupported with no registered original, late onCompleted after failure, and unchanged Editor mapping.

## 6. Opus is retained work, with a specific admission gate

The exact local source chain is documented in SOURCE_EVIDENCE.md. It establishes that codec-private Opus header, CodecDelay and SeekPreRoll are passed as CSD to Android; positive DiscardPadding is emitted as supplemental data; renderer S contains decoder-output PCM; graph may append silence; E timestamps are regenerated from cumulative encoder PCM bytes. It does **not** establish effective audible head/tail mapping on emulator-5556. In particular, lack of a supplemental read in DefaultCodec is not sufficient evidence that the whole pipeline does or does not trim.

Required audio follow-through uses the same run path, no alternative parser/decoder:

- Preserve complete official CSD and supplemental bytes, retain the existing bounded negative source-time allowance, and verify mono/stereo PCM16 at 48 kHz on S and E.
- Retain actual codec-delay/seek-preroll declarations in the existing track facts from guarded callbacks; preserve nanoseconds internally and apply a checked conservative conversion at the existing microsecond proof boundary. For final discard, capture only bounded official callback/supplemental metadata, never parse Opus packets. Precisely associate final padding with the audio terminal sample before populating the existing single discardPadding field; ambiguous multiple/nonterminal/laced-padding cases may be unsupported initially. Pinned Matroska itself omits supplemental payload for multi-sample blocks, so do not silently pretend those cases were measured. This is one small audio metadata extension, not a new parser.
- Known audible-head/tail original fixtures must include zero and nonzero delay, preroll, negative prefix, positive final padding, mono and stereo, realistic browser-produced WebM, video rates including low fps, and a short/delayed/long audio negative case. Measure S start/end/PCM count, E count and independently decoded AAC output markers/time. Actual VP8 picture endpoints must also agree.
- Require S/E total-frame equality, all EOS, continuous S timing and independently source-bound endpoints. E zero alone never defines source zero. AudioGraph silence insertion must be detected by unequal S/E counts or endpoint mismatch.
- If ordinary official processing passes, admit that finite audio subprofile. If a case fails, identify its concrete timing/codec boundary and reject that case; a focused public-boundary fix can be reviewed. Do not demand a compressed-bit conservation proof, introduce an Opus parser, or claim blanket Opus impossibility.

Until these actual fixtures pass, the silent execution loop is only an implementation stage and all audio production admission remains pending. This does not remove the audio requirement. Source inspection and synthetic S/E wrapper tests are insufficient for an audible success claim.

## 7. Next durable finish handoff, using existing schema

Implement immediately after the actual owner-run path is validated; do not stall it behind a new generic evidence framework.

1. Add `finishOwnedTranscode(attempt)` on the same NormalizationAttemptOwner. It accepts no outcome, observations, proof or alternate context. Consume the attempt's own private successful actual-run state once. Recheck exact original SaveRecord/WRITING row, original live Scope/context/deadline, source/transfer identities, current owner map, sealed hashes and positive closes. No outstanding calls/obligations/uncertainty may remain.
2. Only this path enables `TRANSCODE → FINISH`; preserve `reservations.advance` zero calls/zero obligations. Sealed active-work rejection remains unchanged. An old, recovered or merely DTO-reconstructed WRITING row cannot run or finish.
3. Construct existing `DirectConversionExpectation`, `DirectConversionEncodingSettings` and `DirectConversionCompletionFacts` from the owner-kept observer, real muxer/EOF and real cleanup records. Their true flags are consequences of checked terminal records, not caller assertions. Silent has null audio fields; admitted audio uses actual S/E/boundary observations.
4. Add one narrow typed `DirectConversionProofCompletion : AutoCloseable` core port and concrete storage completion, analogous to MOV, and an exact conversion proof CAS. This is the typed durable handoff the current schema needs, not another run owner or data proof format. Preserve MOV `DirectProofCompletion` typing.
5. Add `NormalizationWorkspaceProtocol.finishOutput` conversion overload with existing output/hash/source-transfer validation and unchanged v2 row/lifecycle. Proceed FINISH → PROOF_HANDOFF and keep the same reservation until exact Save CAS is acknowledged. On an uncertain CAS return inspect the exact committed record/revision; never rerun encoding.
6. Add conversion-specific `recordAdoptionIntent` validation then reuse PRIMARY→RAW and OUTPUT→PRIMARY no-replace moves. Independently full-decode canonical AVC and optional AAC with its conversion expectation; use existing `DirectConversionVerificationProof`, same read owner/native verification lane and pre/post fixed-FD hashes. Then reuse typed normalized commit/publication machinery.
7. Only after those real branches and native acceptance exist add WEBM selector/admission and runtime wiring. `movAdmissionEnabled` cannot authorize WebM. Recovered OUTPUT_READY/ADOPTED/COMMITTING may resume exact durable stages; WRITING stays review/discard-only.

No new database column, folder role, persisted run object, parallel commit handle or reusable Completed authority is required. Existing 4096-byte evidence and 32 KiB Save caps stay unchanged.

## 8. Exact touch map and stop line

Paths are relative to the source root; `app/…` means `app/src/main/kotlin/com/easysavevideo/app/`.

Run-stage required production edits:

1. `app/…/save/storage/OwnedDirectTranscodeAttempt.kt`: private one-shot run state, immutable candidate selection, real concrete session invocation and outcome capture, exact first asynchronous caller-failure retention, active transport capture, seal/size agreement, future consume-only seam. Existing resource logic is reused.
2. `app/…/save/storage/NormalizationAttemptOwner.kt`: concrete engine composition, exact start/finish snapshot check using existing maps; one owner-only entry. No extra run registry.
3. `app/…/save/storage/AndroidSaveStorage.kt`: application-context-backed private concrete engine construction passed to the existing owner. No user-visible admission/UI/runtime toggle.
4. `app/…/save/conversion/BoundedWebmExtractor.kt`: copy declared DefaultDuration in existing TrackEntry callback, record official SeekMap duration, later narrow audio metadata fields; retain policy bounds and all-track checks.
5. `app/…/save/conversion/WebmProfileProbe.kt`: add only those immutable observations to existing transport classes.
6. `app/…/save/conversion/OwnedWebmInputBinding.kt`: compare new observations; configure existing progressive source's tracked download/no-DRM policy; preserve exact paired source and active getter rules.
7. `app/…/media/transform/TransformerEngine.kt`: private same-attempt entry/configuration, callback identity, VP8/Opus observer factory, Direct-specific original asynchronous error preservation, active final capture, share existing builder/wait/tracking logic with Editor.
8. `app/…/save/conversion/WebmDirectCandidatePolicy.kt`: optional tiny pure calculation returning existing OutputSpec, for clean Stage A/B file ownership; no new DTO/owner.
9. `app/…/media/transform/TransformerRunSpec.kt`: documentation/guard tests only; no caller-constructible issued Direct spec. Existing EngineFacts.sourceObservation already suffices.

No resource-layer rewrite is required. `TransformerSession`, `TransformerSessionExecution`, `TransformerBoundedMuxer`, `TransformerSourceObserver`, `SharedTransformerRuntime` need no semantic change for the proposed silent declared-count path; change them only if a concrete pinned/native integration test demonstrates a necessary incompatibility. In particular, no replacement expected-count model is needed.

Durable follow-on edits: `NormalizationIoReservations.kt`; `NormalizationWorkspaceProtocol.kt`; `NormalizationAttemptOwner.kt`; `save/.../ControlledDirectPipeline.kt`; `save/.../SaveCoordinator.kt`; `AndroidControlledDirectStorage.kt`; `AndroidSaveStorage.kt`; existing adoption/read-verification/normalized-commit owners and verifier. Separate typed conversion additions, no nullable MOV union. `AndroidSaveRuntime.kt` remains coordinator-owned and is wired last.

Tests and evidence required are enumerated in ACCEPTANCE_MATRIX.md. None were run by this design task. Stop at a reviewed implementation plan and its exact source hashes; this document does not claim native execution, generated media playability, durable producer proof, independent verification or publication.

## 9. First independently implementable work split

A. Freeze and implement only declaration facts and pure candidate policy first: `BoundedWebmExtractor.kt`, `WebmProfileProbe.kt`, `OwnedWebmInputBinding.kt`, their existing probe/paired tests and pinned extractor fixtures. The pure candidate calculation should live in one tiny `save/conversion/WebmDirectCandidatePolicy.kt` file as an internal testable function returning the existing `TransformerOutputSpec`, keeping Stage A independent of Stage B edits to the attempt. An existing attempt companion would also be technically sufficient, but is not preferred during parallel work. It must do no I/O, schedule nothing and issue no authority. Construct `TransformerSourceTimeline` from that same selected spec and captured start, not a second selection. Do not add an independent candidate DTO. This slice leaves every Direct run entry and TRANSCODE → FINISH closed.

B. With A's field semantics frozen, implement the existing attempt/owner/engine one-run chain and concrete storage engine composition. This can be developed separately from A's fixture extension, with one build/source owner serializing integration. Its focused tests exercise actual callback item rebuilding and active-before-retired completion timing. No change to recording runtime composition.

C. Follow with the typed durable producer handoff and independent converted verification using current schema. Native silent and Opus boundary fixtures are their separate acceptance gates, not reasons to expand A/B into speculative parser work. No public WebM admission until the complete typed chain and native corpus are accepted.
