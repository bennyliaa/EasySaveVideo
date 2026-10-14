# Direct MOV: typed final Android verification, narrow next-layer design

Date: 2026-10-14 UTC. Status: DESIGN ONLY. This note changes no production source, build, dependency, device, or admission switch. It proposes the smallest final-verifier layer after the original silent MOV remux adapter. Storage/adoption implementation is a separate workstream.

## 1. Finding and scope

The missing entry point is concrete: `ArtifactVerifier` already has the typed overload, and `SerialArtifactVerifier` forwards the same descriptor without manufacturing a transfer. `AndroidLocalMp4Verifier` implements only the legacy `TransferResult` overload. Its inherited typed default therefore rejects `NormalizedDirect` with `METHOD_UNSUPPORTED` (`SavePorts.kt:51–60`). The existing `DirectNormalizationVerificationProof`, `DirectVideoTailProof`, and `VerificationEvidence.isValidFor(artifact, output, expectedAudio)` can carry the required result without a new durable schema.

The parent reports the preceding layer at pinned-host 25 PASS, clock/canonical 20 PASS, and probe 16 PASS. Those results were not rerun in this design task. They are transport/profile evidence, not Android decode acceptance. The source explicitly keeps `MovRemuxEngine` disconnected from production MOV admission.

Keep the independently reviewed narrowing: exactly one already-silent AVC Baseline/no-B track, zero origin, exact 90 kHz video clock, approved finite frame-rate set, exact 10 kHz movie-header clock, identity geometry, and the existing strict box/NAL/configuration limits. Any audio track rejects the whole file. The optional AAC contract types do not enable audio. Keep ordinary MP4, HLS, Record, the editor, and all production MOV admission unchanged during this layer.

Read baseline:

- `AGENTS.md`
- `docs/ui/implementation/BASIC_NORMALIZATION_DESIGN_20261014.md`, including its silent-only refinement
- `docs/ui/implementation/MOV_CLOCK_AND_CANONICAL_CONTRACT_20261014.md`
- `save/.../{DirectNormalizationContracts,DirectRemuxConservation,DirectMovClock,DirectMovCanonical,SavePorts,SaveModels,SerialArtifactVerifier}.kt`
- `app/.../save/{AndroidLocalMp4Verifier,NativeVerificationLease,StreamVideoTailState}.kt`
- `app/.../save/normalization/{MovProfileProbe,MovAvcProfile,MovRemuxEngine,MovRemuxFiles}.kt`

## 2. Minimal API and dispatch

Implement both public overloads in `AndroidLocalMp4Verifier`:

1. `verify(artifact, transfer, context)` wraps the **same** transfer in `ArtifactOutputDescriptor.Transferred` and calls the typed overload.
2. `verify(artifact, output, context)` enters the existing bounded native submission/wait/lease code once. Dispatch inside that worker to the unchanged transferred inspection or the new normalized inspection.

The transfer wrapper is permitted; creating a new `TransferResult` with output length, MP4 MIME, or a fabricated `expectedTotalBytes` is prohibited. Do not implement the typed method by recursively calling the public legacy method or acquiring the serial/native lane a second time. No second executor, coroutine, replacement native thread, or timeout budget is introduced.

For `NormalizedDirect`, before allocating Android resources:

- Check the original operation cancellation/deadline and the existing native recovery gate.
- Require proof artifact/commit IDs to match `StagingArtifact` and `proof.matchesTransfer(output.sourceTransfer)` to remain true. Check known proof/profile/revision values and reject nonnull audio/rate/channel fields for this implementation.
- Require output size within the existing 2 GiB cap and the applicable verification resource budget. The source transfer's HTTP length is checked **only against raw source size**, as already enforced by `matchesTransfer`; it is never compared with canonical output length.
- Carry the original descriptor and proof, without replacing their identities or fields, into the worker.

Task ID and source identity need special precision: this verifier signature does not receive the current `SaveRequest`, so it cannot independently discover the authoritative task/source. The caller must obtain the descriptor through `record.selectedOutputDescriptor()`, which checks those bindings. The verifier repeats the bindings available to it and binds its result to the full proof digest. Do not claim that copying `proof.taskId` constitutes independent request validation. Storage/coordinator integration must demonstrate this caller precondition before production enablement.

No change is needed to `ArtifactVerifier`'s fail-closed default, `SerialArtifactVerifier`'s forwarding, the existing proof wire format, or `Mp4ContainerProbe` for this layer.

## 3. Snapshot and independent raw-output scan

The caller supplies the already adopted, closed, exclusively owned canonical staging artifact under the existing Direct local-processing capability. It retains the raw source and its original transfer/proof. This verifier neither adopts files nor opens, rewrites, removes, or re-downloads raw material.

Inside the one native worker:

1. Resolve the owned staging file using the existing storage callback. Open one read-only `RandomAccessFile`; capture the fixed extent. Require `actualSize == output.sizeBytes == proof.outputSizeBytes`.
2. Hash every byte from that same open file before parser/decode work. Require the result to equal `proof.outputSha256`; a mismatch is `RESOURCE_CHANGED`, not an incomplete HTTP transfer.
3. Run the unchanged strict `Mp4ContainerProbe.isCompleteMp4`. It must still reject QuickTime input. No extension/MIME-only acceptance and no MOV fallback.
4. Adapt that same file descriptor to a narrow `MovProbeInput` view and run a **fresh** `MovProfileProbe.scanCanonicalMp4`. Never reuse `MovRemuxEngine`'s previously returned `MovProfile` or trust its conservation object as an observation. The view reports only the fixed captured extent and bounds every positional read to that extent. Before and after each bounded read it checks actual descriptor length/identity against the captured values; any change fails with RESOURCE_CHANGED. It never adopts a new live length, closes the caller-owned file or implicitly reopens a pathname.
5. Require the rescanned `DirectRemuxConservation` to equal the immutable normalization proof exactly. The scan independently establishes output table layout, samples, SPS/PPS and AVC syntax, sync identities, exact clock, final `stts`/`mdhd` endpoint, and separately the movie/header rounding formulas. It also excludes hidden extra tracks/protection/edits/rotation.
6. Give `MediaExtractor` and `MediaMetadataRetriever` only this owned local FD, offset zero, and the captured extent. Never pass a network URL or user-supplied path to a media parser.
7. After **all** native sample/decode/retriever checks, rehash the same file and recheck extent. Require before hash == after hash == expected output hash. Recheck ownership/identity through the owning integration boundary before releasing the local capability. A fresh pathname open is not a substitute for the inspected descriptor.

The pre/post hashes bind decoder observations to the proof; they are not a replacement for exclusive ownership. They cannot make concurrent mutation safe or independently prove that a pathname still names the inspected inode. The existing storage owner must fence replacement/mutation and validate the artifact generation. That integration precondition is a blocker, not authority for this layer to invent another file registry.

Read failures and short local reads are local verification/storage failures. The normalized hash/read helpers must be independent of the legacy hashFile error mapping (or explicitly map normalized local read failures before escape). Do not emit `INCOMPLETE_TRANSFER` from the new normalized scan merely because `readSampleData` or a local hash read was short: that error is network-retryable in current `SaveErrors.policy`. Keep the old transferred branch's behavior unchanged.

## 4. Real Android extractor conservation, separate from Media3

Add a normalized-only Android sample walk, e.g. `inspectDirectSamples`. Its input is the fresh raw-output `MovProfile`, original proof, fixed FD view, and original checkpoint. Its result contains an independently recomputed conservation contract plus a bounded final-GOP manifest. It must not call the Stream accumulator or the HLS `canonicalAvcSample` helper; those have different framing/origin/timing policies.

### 4.1 Track/configuration facts

- Require exactly one Android track, MIME `video/avc`, zero audio, and geometry equal to the raw scan and expected proof. A hidden extra/nonmedia track is a failure, not an ignored track.
- Require no PSSH/crypto evidence. The raw structural scanner remains required because an empty `psshInfo` alone is not a clear-content proof.
- Read `csd-0` and `csd-1` through bounded duplicate buffers, preserving their positions. Require exactly one SPS then one PPS, no additional CSD entries, combined existing 64 KiB bound, and equality through `DirectMovCanonical.avcConfigurationFromInitializationData`. Compare its digest **and exact SPS/PPS bytes** to the raw `avcC` scan. No reconstruction from codec strings, profile labels, or frame rate.
- Raw geometry/SAR/rotation/SDR policy is authoritative. Any contradictory Android format fields fail closed; absence of optional Android hints does not overwrite the proven raw facts. Check available rotation/crop/SAR/color fields instead of silently permitting a conflicting output interpretation.
- Android format duration must be positive and plausibly agree with the independently proven endpoint/header clocks. Use a separately named metadata-only 1,000 µs envelope for the normalized path, never to relax sample/timing equality. MMR's integer milliseconds may differ by at most 1 ms from the canonical duration below. If a platform reports another convention, keep it unavailable pending explicit review; do not silently widen the tolerance.

### 4.2 Complete sample walk

Select the single video track and seek to zero/previous sync. Require the first actual sample to be index zero, PTS zero, and sync. Walk every sample sequentially, with no midpoint/tail seek shortcut:

1. Bound index before access and require the selected track, positive declared sample size <= 4 MiB, and exact expected `U[i] = floor(i * D * 1,000,000 / 90,000)` from the **rescanned** `DirectMovClock`.
2. Check Android sample flags independently. Accept only zero or `SAMPLE_FLAG_SYNC`; reject encryption as `PROTECTED_MEDIA`, and partial-frame/unknown flags as an unavailable native representation. Do not feed Android flags through `DirectMovCallbackFlags`: its `LAST_SAMPLE` allowance is for pinned Media3 callbacks only. Require sync exactly equal to the raw sample-table/AVC-profile result.
3. Read exactly the declared size into a bounded buffer and reset its view explicitly. Use the shared canonical helper with **explicit `ANNEX_B` framing**, without AVCC sniffing/fallback. Compare byte-for-byte against `MovProfileProbe.readCanonicalSample` for that same raw-output sample. Preserve all admitted NALs, byte values and boundaries; no config injection, padding trim, reordered NALs, or permissive HLS framing.
4. Feed canonical bytes, exact PTS and sync into a new `DirectMovSampleAccumulator` initialized from the rescanned clock and **Android-derived** configuration digest. Check every compact identity and final contract against the raw scan/expected proof.
5. Advance once. After exactly N samples, independently require `sampleTime == -1`, `sampleTrackIndex == -1`, `sampleSize == -1`, and `readSampleData == -1`. Early or extra samples reject.

The Annex-B choice is a deliberately fixed Android adapter contract, not a universal promise about every vendor extractor. Android documents SPS/PPS CSD start codes, and AOSP's MP4 extractor performs AVCC-to-four-byte-start-code conversion. Actual native sample/config framing on emulator-5556 still needs acceptance. If a device does not supply the selected representation, return `VERIFICATION_UNAVAILABLE`; do not try another interpretation until one passes. [Android MediaCodec CSD contract](https://developer.android.com/reference/android/media/MediaCodec#csd), [AOSP MP4 extractor, immutable revision](https://android.googlesource.com/platform/frameworks/av/+/45fa42d7989078fe34a32a0e1f21a60dbea854b5/media/module/extractors/mp4/MPEG4Extractor.cpp#6589).

### 4.3 Exact endpoint versus display duration

Use the rescanned `stts`/`mdhd` clock for final-duration evidence. `E=N*D`; endpoint is `[floor(E*1e6/90000), ceil(E*1e6/90000)]`, width zero or one µs. `tkhd`/`mvhd` retain their separate exact two-step 10 kHz rounding check. Neither `MediaFormat.KEY_DURATION`, MMR metadata, nor decoded EOS time establishes this endpoint.

For normalized `VerificationEvidence.durationMillis`, emit `endpoint.minimumUs / 1000`, derived from this independent scan, and retain the current typed `isValidFor` millisecond containment check. Do not substitute raw HTTP length, last PTS, or muxer duration callbacks. Final sample PTS and final sample endpoint are distinct facts.

## 5. Terminal decoder proof and bounds

Retain the existing genuine beginning/middle/end decode-position checks and MMR frame checks for normalized output. Supply the independently proven media duration to select those positions. Do not fabricate `DecodePosition.entries`; an END-range frame within the existing tolerance does not establish the last sample.

Add a Direct-specific final-GOP decoder pass. The raw scan has already established `k`, the greatest sync index at or before `N-1`. Decode the inclusive range `[k, N-1]`. The final frame may be nonsync; when the final sample itself is sync, the tail contains exactly one sample even though the whole track has at least two.

### 5.1 Tail manifest and bounded work

- Retain immutable compact identities only, never all compressed payload bytes. Each item records absolute sample index, exact extracted PTS, normalized sync flag, actual native input length, canonical length and SHA-256. Reuse `DirectSampleIdentity` within a small `DirectNativeSample` wrapper.
- Because k is known before the Android scan, collect only indices >= k; do not retain or reject earlier large GOPs merely while searching for the final sync.
- Require manifest count exactly `N-k`, between 1 and 108,000 inclusive, with whole-track N still capped by the exact clock/duration profile. First item equals `precedingVideoSync`, last equals `video.lastSample`, and there is no later sync after the first item. Every PTS is the clock's exact indexed PTS.
- Cap compressed final-GOP input at the existing 64 MiB verifier tail allowance. Checked-sum the raw final-GOP extents before retaining identities, and check native input totals on append and again while queueing. Keep each native/canonical sample <= 4 MiB and require it to fit the actual codec input buffer. Exceeding the cap is an honest bounded-verification limitation, never sample dropping or a new decoder lane.
- Hashes/identities are transient. Do not add a per-sample list to the 32 KiB Save record or 4096-byte standalone evidence format.
- Retain the one absolute verification deadline: `min(configured verifier timeout, remaining original operation time)`, currently supplied as 60 seconds by the runtime. A 30-minute admitted source is not promised to finish verification in that time. Use the existing 5-second no-progress bound and bounded dequeue waits. Only a successfully queued media/EOS input or validated decoded media/output EOS renews progress; repeated empty/config/format events do not.

### 5.2 Seek, queue, decode, EOS

1. Use a fresh decoder for this tail pass in ByteBuffer mode, configured from the already compared Android format. Do not attach a Surface, enable adaptive dropping, transcode, or create an encoder. Do not submit duplicate CSD when configure/start supplies it.
2. Seek the extractor to the proven sync PTS with `SEEK_TO_PREVIOUS_SYNC`. Require the exact selected track, exact sync PTS and sync flag. Earlier/later GOP substitution rejects, even if a later frame appears near the expected endpoint.
3. Re-read every queued sample and compare the full `DirectNativeSample` identity against the manifest before `queueInputBuffer`. Queue the actual verified native bytes and their actual exact PTS. No input after N-1 except one empty EOS buffer.
4. Verify actual extractor exhaustion with the same complete EOF checks, then signal input EOS exactly once. Queueing EOS is not completion. Use a deterministic input EOS PTS (the proved endpoint minimum is convenient), but do not treat that timestamp as a decoder duration observation.
5. A pure Direct tail state accounts for each positive-size, non-config output in order. Require that corresponding input was queued, strictly increasing output PTS, and `abs(actualOutputPTS - expectedIndexedPTS) <= 2 µs` for **each** output, using the existing Direct outer bound without accumulation. Retain the actual final nonempty output PTS; never replace it with the expected PTS. Extractor/source/mux clocks remain exact, without this decoder-only tolerance.
6. Validate every output buffer's nonnegative offset/size, overflow-safe capacity bounds, permitted flags, and stable decoded video/raw format. Require crop/display geometry to match the proven dimensions, valid bounded coded dimensions and a supported ByteBuffer output representation. Reject a late format change. A config buffer cannot count as a frame; empty non-EOS output cannot satisfy progress or last-frame evidence.
7. Output EOS may accompany the last nonempty frame or follow it in a separate empty buffer. Account for a nonempty final frame **before** processing its EOS flag. Require input EOS already submitted and exactly `N-k` real decoded frames. Reject early EOS, config+EOS, duplicate/missing/reordered frames, unexpected extra frames and calls after completion. Ignore an empty EOS buffer's PTS; it cannot become `terminalOutputTimeUs`.
8. Build `DirectVideoTailProof` only from the independently observed first/last identities, actual terminal nonempty output PTS and actual output EOS. Require `tail.matches(observedConservation)` and `tail.matches(expectedConservation)`.

The two-microsecond decoder allowance is a per-frame absolute bound chosen from the existing Direct proof envelope, not permission to shift the extractor timeline. All native outputs must still be accounted for one-to-one under this narrow no-B progressive profile. Unsupported batching/output representation is unavailable, not proof of success.

Android distinguishes input EOS from eventual output EOS and says empty EOS timestamps are immaterial; seek-start input for AVC must begin at an IDR. These documented rules motivate the separate state machine; they do not establish this device's actual output behavior. [Android MediaCodec EOS and seeking](https://developer.android.com/reference/android/media/MediaCodec#end-of-stream-handling).

## 6. Pure helper contracts, without Android types in core

Proposed new `save/.../DirectVideoTailVerification.kt`:

- `DirectNativeSample(identity: DirectSampleIdentity, inputBytes: Int)`: immutable, bounded, technical only. No file path, MediaFormat, ByteBuffer, codec name or Android flag type.
- `DirectVideoTailPlan(clock, conservation, samples)`: validates the exact contiguous `[k,N)` manifest, first/last bindings, greatest-sync rule, exact PTS, size/count/byte caps, and defensive immutable ownership. This is ephemeral, not serialized.
- `DirectVideoTailState(plan, checkpoint)`: `input(sample)`, `inputEndOfStream()`, `output(timeUs, bytes, codecConfig, eos)`, `complete`, and `finish()` returning `DirectVideoTailProof` only after complete. Sticky failure on any mismatch or checkpoint failure; no recovery of a partially failed state. It tracks queued/decoded counts and actual terminal PTS.
- A tiny platform-neutral decoded-format value/state only if needed to avoid importing Stream-named policy. Its job is stable raw output/crop geometry validation, not codec selection or an assertion that a format is available.

Reuse `DirectMovClock`, `DirectMovCanonical`, `DirectMovSampleAccumulator` unchanged. A generic progress-clock helper may be shared only through a small behavior-preserving extraction; reusing Stream-named proof/canonical/tail types as Direct evidence is not acceptable. A Direct-local progress helper is also sufficient and avoids refactoring unrelated engines.

Proposed app-only `DirectAndroidOutputInspection.kt` (or small private methods if clearer): adapts the same FD to `MovProbeInput`, maps bounded Android format/config/sample facts to the existing shared Direct helpers, and returns the conservation plus tail plan. Android API operations remain in the app and on the existing native worker. Do not introduce a second media engine or a broad verifier-framework rewrite just to obtain test seams.

## 7. Release, cancellation, and the existing temporary draining semantics

Preserve `NativeVerificationLease`'s actual lifecycle:

- Before native start, abandonment revokes the queued lease; it must not begin later or increment draining for nonexistent work.
- After start, caller cancellation/deadline marks abandonment, cancels the Future as a request, fences that call's late result, and leaves the single native lane occupied.
- Invoke `lease.released()` only in the native worker's outermost `finally`, after the inspection body's nested cleanup/finally blocks actually finish. A Future's done/cancelled state is not proof of release.
- If a native stop/release blocks, temporary draining stays true. Once every cleanup call actually returns successfully, the draining counter can fall to zero and a **new explicit operation** may enter. That does not rehabilitate the abandoned operation's result.
- Keep `releaseUnconfirmed` latched for genuine unconfirmed descriptor/codec/extractor/retriever release. Preserve the serial wrapper's separate latch only for an unreadable recovery probe. A normal timeout, unavailable codec, invalid media, or successfully released stop failure is not automatic permanent process poison.

Every resource must be assigned into a cleanup-owned variable immediately on acquisition, including failures during configure/start. Output buffers are returned in `finally`; decoder stop is attempted only when started; decoder release is attempted even if stop throws; extractor/retriever/input cleanup attempts are independent so one failure cannot skip the others. Never checkpoint before a required cleanup in a way that skips cleanup on cancellation. Do not release a codec from a replacement/caller thread to simulate termination.

An ordinary stop or buffer-processing exception fails the operation, but if final release succeeds it does not imply lingering ownership. Conversely a release call that throws cannot be counted as confirmed merely because later Java code proceeds. The outer lease can finish while the explicit uncertainty latch still correctly blocks admission.

Fixed nesting remains outer Direct local permit → existing serial verifier → existing native worker. The storage/coordinator owner retains its execution/native ownership until the required release/publication boundary; this note does not change or implement that protocol. Failure to meet that boundary blocks integration.

## 8. Evidence construction and error mapping

After all checks and final hash, construct the existing `DirectNormalizationVerificationProof` using the original normalization/task/source/artifact/commit IDs, `normalizationProof.digest()`, the newly observed conservation, and actual Direct tail proof. `VerificationEvidence` has canonical size/hash, one video/zero audio, independently derived duration and dimensions, actual decoded-position set, and null Stream/Record fields. For zero audio, `audioTrackReadable=true` is vacuous; an external `expectedAudio=true` still rejects via existing typed validation. Do not change expected-audio policy to make a silent result pass.

Before return, check typed `isValidFor` at the owning caller with its actual expected-audio request. The verifier may also perform the request-independent invariants locally; it must not fabricate an expected-audio value and claim full request validation. Evidence is returned only after normal cleanup completes and the worker/serial caller rechecks the original stop/recovery conditions.

Normalized-only failure mapping:

| Observation | Existing error | Meaning |
|---|---|---|
| Invalid source/proof/artifact/commit binding or wrong generation/precondition | `RECOVERY_REQUIRED` | Do not reinterpret raw and output or retry transport. |
| Proof/output audio present, otherwise unsupported declared profile | `UNSUPPORTED_MEDIA` | Reject whole artifact; never strip audio. |
| Positive encryption/protection evidence | `PROTECTED_MEDIA` | No decryption, key lookup or alternative recording route. |
| Canonical length/hash/identity changed before or during inspection | `RESOURCE_CHANGED` | No result is tied to a stable expected output. |
| Validly read output fails strict boxes, AVC syntax, exact tables, conservation, native identity/count/PTS, decode count/order/tail checks | `INVALID_MEDIA` | No publication; do not call it a network failure. |
| Hard file/resource byte cap exceeded | `BYTE_LIMIT_EXCEEDED` | Bound remains enforced. |
| Codec unavailable/create/configure/start/resource failure; codec exception without independent corruption evidence; unsupported native framing/partial sample/output representation or tail-work cap | `VERIFICATION_UNAVAILABLE` | This device/path cannot establish proof; no claim that the source codec universally fails. |
| Local FD open/read fails | `STORAGE_FAILURE` | Local material needs storage/recovery handling, not network retry. Missing/ambiguous owned generation remains the caller's `RECOVERY_REQUIRED`. |
| Original cancel/deadline/retry budget stops, or native no-progress bound | Preserve `CANCELLED` / `DEADLINE_EXCEEDED` / `RETRY_EXHAUSTED` | No restart of the context/deadline; draining separately tracks unfinished native cleanup. |
| Actual resource release cannot be confirmed | `RESOURCE_RELEASE_UNCONFIRMED` plus existing gate | Retain/fence ownership and publication as currently required. |

Map Android setup/runtime exceptions within the new normalized branch at the phase where they occur. The existing outer blanket `ExecutionException → INVALID_MEDIA` must not turn a known decoder-unavailable event into alleged corrupt media. Do not classify by exception message text or log format dumps, file names, paths, URLs, CSD/payload, or diagnostic exception messages. Preserve already typed `SaveFailure` codes. For simultaneous cancellation and draining, preserve the caller's existing stop reason while the independent release gate remains authoritative.

Do not automatically loop through alternative decoders or retry after a timeout. Capability enumeration, if used, only selects one suitable decoder; advertised support is not acceptance. Actual successful creation/configuration/decode/EOS/release is required.

## 9. Acceptance layers and test matrix

### Host/core tests can establish

- Typed overload routes `Transferred` without changing legacy semantics and routes `NormalizedDirect` without constructing a new transfer. The exact source transfer stays unchanged when output is smaller or larger than raw; original HTTP MIME and expected-total remain raw facts.
- Binding rejection precedes native work. Existing SerialArtifactVerifier typed forwarding, cancelled wait, same-context deadline, temporary drain, late-evidence fencing and recovery-probe latch regressions remain passing.
- Shared raw/Android-shaped CSD/sample facts compare exact bytes/config/flags/times; dropped/duplicated/reordered middle/last samples, a changed SPS/PPS, wrong `LAST_SAMPLE` flag, partial/encrypted flags, extra track, forged sync, changed final duration and early/extra EOF reject. JVM fake Android observations prove adapter policy only.
- Tail: IDR→P→P; all-IDR final one-sample GOP; preceding-sync in the middle; wrong seek result; wrong sample identity/input byte length; output before corresponding input; missing/duplicate/reordered/extra output; ±2 µs accepted only within all other timestamp bounds, ±3 µs rejected; nonmonotonic output; separate-empty vs attached-final EOS; config+EOS; early/missing/double EOS; ignored empty-EOS timestamp; post-completion calls; failed/cancelled state never yields proof.
- Bounds checked before allocation/append: 4 MiB/sample, 64 KiB config, 64 MiB tail, 108,000 total samples and rate-specific duration cap. Progress flooding with empty/config/format callbacks cannot extend the no-progress allowance or original absolute deadline.
- Simulated same-size hash mutation, truncated/expanded output and IO errors return normalized local errors and no evidence. Release-order injection covers configure/start/stop/release failures, independently attempted closes, queued revocation, running abandonment, and a blocked `finally` that later actually returns. Genuine release uncertainty stays blocked; ordinary timeout with later successful release does not permanently poison the process.
- Existing proof/evidence codec round trips already accommodate the same fields; no per-sample durable payload or new schema. Legacy MP4/HLS/Record contracts and no-MOV-admission assertions must stay unchanged.

### Only actual Android/device execution can establish

- Real `MediaExtractor` sample framing, CSD layout, exact PTS/sync/count and EOF behavior for canonical outputs produced by the actual remux engine.
- Real decoder acceptance and real nonempty terminal output at the last sample followed by output EOS, including the nonsync-final-frame case and smallest two-sample input.
- Real ByteBuffer decoded format/crop/geometry, MMR format/duration/frame behavior, codec input-capacity behavior, unavailable-decoder reporting, cancellation and actual native release on this device.
- Complete integration through the caller's ownership/adoption/publication boundaries, plus ordinary MP4/HLS/Record and Direct2 sequencing regressions.

Use the original owned entropy-valid fixture generator (`OriginalMovFixtures`, explicitly constructs I_PCM IDR/P-skip syntax), but do not relabel its host success as device proof. Cover two/three/multislice samples, approved rate rounding phases, all-IDR and final-P tails, matching in-band SPS/PPS/AUD, metadata order/chunk variants, and deliberately mutated canonical outputs. The eventual device run is separately scheduled on **emulator-5556 only**, preserving data/signature and the increasing-versionCode rule. This task performs none of those operations.

## 10. Touch map, implementation order, and blockers

Minimal intended production touches for a later authorized implementation:

1. `AndroidLocalMp4Verifier.kt`: explicit typed override, one shared native invocation envelope, normalized-only inspector/tail branch, narrow phase-aware exception mapping. Preserve legacy transferred path.
2. New small `app/.../save/DirectAndroidOutputInspection.kt` if needed: bounded FD/MediaFormat/MediaExtractor adapters around existing raw-output scanner and canonical helpers.
3. New `save/.../DirectVideoTailVerification.kt`: transient Direct-native sample/tail state and bounded format facts, with no Android types.
4. Focused tests alongside core Direct tests and app native lease/verifier tests; later authorized Android instrumentation fixtures/acceptance. Existing contract-input bookkeeping may need the added test/source paths; no dependency or version change.

Normally unchanged: `DirectNormalizationContracts`, `DirectRemuxConservation`, proof/evidence codecs, `ArtifactVerifier` default, `SerialArtifactVerifier`, `NativeVerificationLease`, `Mp4ContainerProbe`, the remux engine/profile parser, ordinary Stream/Record helpers, storage schema/ledger/path protocol, network MIME admission, detector/UI, manifest, SkinKit, billing, and dependencies. If a supposed verifier fix requires widening any of these semantic gates, stop for focused review instead of hiding it in this layer.

Order: review this design → pure helper/branch tests → typed Android implementation/source regression with production admission still off → independent source review → storage/coordinator integration review → separately authorized real Android acceptance → only then a separate production-admission decision.

Remaining blockers to a support claim:

- No typed Android override, independent Android Direct conservation walk, or Direct terminal state implementation exists yet.
- Actual Android sample/CSD representation and codec terminal/EOS/release behavior are not established by host25/core20/probe16 results.
- The owner must supply an adopted, mutation-fenced canonical FD/generation and select the descriptor through the authoritative record. Storage/publication integration is not delivered by this note.
- Exact-device metadata/crop/output-format differences may make this narrow profile unavailable. Reject and report rather than widen clocks/framing/format limits silently.
- Silent-only intentionally leaves AAC AU/PCM-frame/timestamp proof and general camera MOV/WebM/transcoding outside this first enabled profile. Those original-requirement gaps remain open.

No Gradle task, APK build/install, device operation, dependency admission, Git operation, production edit, or storage implementation was performed to prepare this design.
