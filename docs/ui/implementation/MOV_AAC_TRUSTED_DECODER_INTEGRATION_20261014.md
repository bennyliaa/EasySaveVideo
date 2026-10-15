# Direct MOV audio: smallest real native integration and explicit trust-policy decision

Date: 2026-10-14 UTC. Revised after first independent-review feedback: explicit policy-2 compatibility, video-tail scope and complete active audio-gate map. Status: **DESIGN FOR INDEPENDENT REVIEW; no source, build, remote, device, runtime-admission, dependency or record-codec changes made.** The current audio rejections and `movAdmissionEnabled=false` remain in force. Every proposed compile/test/device check below is **NOT_RUN in this task**.

This revision incorporates the parent’s explicit request to separate original product requirements, necessary safety/ownership boundaries, and the stronger per-AU formal-proof requirement introduced by the earlier engineering design. It does not silently override the accepted `DESIGN.md` or claim that aggregate PCM proves individual AU semantics.

## 1. Recommendation and decision to review first

**Recommend an explicit, reviewed trusted-decoder policy for the finite AAC transport profile, followed by one coherent extension of the existing controlled MOV chain. Do not build a second diagnostic route or a decoder-only component with no authoritative production caller.**

The implementation can use the existing admitted Media3 1.11.1 extractor/muxer and Android MediaExtractor/MediaCodec. It should prove exact source-to-output compressed conservation and observe the complete output audio stream’s real PCM frame total, clock, format and EOS in a fresh native decoder session. This is a useful playback/remux acceptance standard. It is **not** proof that every container sample contains exactly one syntactically complete raw AAC AU, that every bit was consumed, or that each individual AU independently produced exactly 1,024 frames.

The minimum decision for independent review is:

> For this bounded Direct MOV profile, permit the already trusted Media3/Android media implementations to interpret AAC elementary syntax. ESV independently checks bounded container structure and track/sample framing, exact two-byte ASC and configuration agreement, byte/timing conservation, complete fresh-session PCM observations, and owned-resource release. Do not require a newly implemented AAC bitstream conformance parser or individual-AU decoder attribution as a condition of playback/remux acceptance. Explicitly accept that a platform decoder can tolerate or conceal some malformed elementary content that meets the measured aggregate conditions; ESV must not advertise stricter conformance or per-AU proof.

**If that decision is not accepted, the previous stricter gate remains binding.** Section 10 gives the concrete normative-provenance work needed for that route. Nothing in this document turns a prior guard off by implication.

### Three layers, traced to the actual requirements

1. **Original product requirement.** `docs/requirement and design/01_Product_Requirements.md` §7.1, line 217: success is a verified playable MP4; feasible compatibility conversion belongs to basic output, with explicit unsupported failure otherwise. `04_Technical_Requirements.md` §5.1–5.4, lines 126–165: inspect actual container/codec, prefer remux, preserve expected tracks, use bounded streaming IO, decode/seek checks, consistent metadata, and atomic library commit. `05_Test_and_Acceptance_Strategy.md` T-S10/T-S11, lines 140–141: real conversion or explicit unsupported, and no success for broken finalized containers. These clauses do **not** prescribe full AAC grammar validation, bit-consumption attestation, or per-AU 1,024-frame measurements. They do require that observable corrupt/unplayable outputs fail.
2. **Required real-chain boundaries.** Keep source identity, exact track preservation, protection rejection, no external references, fixed bounded source/output extents, actual byte/sample/timestamp conservation, immutable owned FD, cancellation/deadline, native cleanup, release uncertainty fencing, no late publication, and original input/output quota accounting. Without these, plausible PCM could be attached to the wrong file or substituted source, a valid-looking track could conceal track loss, or cancellation could race success. A successful codec callback alone is insufficient.
3. **Prior additional engineering restrictions.** `MOV_CLOCK_AND_CANONICAL_CONTRACT_20261014.md` lines 10–19 introduced complete AU syntax plus actual per-AU/total proof or equivalent. Its finite rate/channel/clock/no-trim bounds are also engineering restrictions, not general MOV promises. Keep those useful finite bounds for this slice. Replace only the stronger elementary conformance/per-AU attribution obligation if review explicitly accepts the trust-policy decision above. The existing PCM helper’s constructor/causal counter remains a conditional accounting check, not evidence that this stronger obligation was already satisfied.

This is a threat-model/policy change, not an “equivalent mathematical proof” of the old obligation. Ordinary bounded media playback can reasonably trust system codecs while making a narrower acceptance claim. Review must explicitly decide whether the residual risk is acceptable for ESV’s original product requirement.

## 2. What exists now, and why a decoder-only patch is disconnected

Inspected source root: `/workspace/shared/esv-cloud-handoff-20fddc9/source`.

- `save/.../DirectAudioPcmAccounting.kt` is the real aggregate helper. It validates successful input credit, PCM16 extent/stride/clock, total frames/bytes and EOS. It owns no codec/FD and creates no verification/publication capability. The parent reports its independent review and 92 focused passes; this task did not rerun them.
- `DirectNormalizedAndroidVerifier.kt:21–119` already acquires the adopted output inside the existing native worker, captures one FD identity, hashes/scans/observes it, releases native objects, rehashes the **same descriptor**, revalidates its lease and only then returns evidence.
- `DirectAndroidOutputInspection.kt:52–60` rejects audio in `DirectNormalizedPreflight`. The adopted owner also calls this preflight in `AdoptedCanonicalOutputOwner.acquire`; bypassing just the outer verifier would not create authorized audio ownership.
- `MovProfileProbe.kt:33–43` has only a video clock/config/sample list. Its scanner rejects every `soun` handler at lines 151–160. A caller cannot obtain authoritative audio sample references or source ASC from this model today.
- `MovRemuxEngine.kt:70` explicitly rejects audio. `OneVideoOutput` accepts one video callback track and uses AVC framing/conversion. It cannot preserve an audio track by adding a codec loop downstream.
- Native verification assumes exactly one track and native video index zero. `inspectDirectSamples` constructs an observed conservation object with null audio. Its `readDirectNativeSample` always interprets Annex-B AVC. None of these is an AAC reader.
- `AndroidLocalMp4Verifier.kt:53–104` owns the single native submission/abandonment lease. `NativeVerificationLease.released()` runs only after nested cleanup actually returns. Keep this envelope unchanged.
- Editor has actual complete PCM counting (`EditAudioDecodeLedger`) and native PCM16 format mapping (`AndroidEditOutputVerifier:292–310`). Reuse the approach, not Editor’s model, tolerances, priming rules or queue ordering. In particular, the new Direct helper must receive successful queue credit **after** the native queue succeeds.
- Existing Stream audio counts nonempty outputs and broad timestamps; Record checks selected windows. They do not establish whole-track PCM frames for Direct and must not be used as a shortcut.

**There is no existing fake MediaCodec port.** There is an existing fakeable `DirectNativeSampleSource`, pure accounting, cleanup callbacks, and owned-FD test seams. Host tests can exercise those real production helpers. They cannot execute the Android codec loop merely because those helper tests pass.

## 3. Minimal coherent slice and exact touch map

After the policy decision, the smallest useful implementation is a limited vertical extension of the already controlled pipeline. Its native function has a real caller in `DirectNormalizedAndroidVerifier.inspect`; its raw inputs come from a fresh bounded canonical scan. No new production diagnostic API, alternate owner, playback service, generic codec framework, executor or bypass flag is proposed.

### A. Required container/transport companions

1. **`app/.../save/normalization/MovProfileProbe.kt`**
   - Preserve the existing video members and add one optional app-local immutable audio section containing `DirectMovClock`, `DirectMovAacConfiguration` and bounded `List<MovSampleReference>`. Reuse `DirectRemuxConservation.audio`; do not add another durable manifest/digest.
   - Recognize exactly one video plus zero/one audio handler. Still reject every other/duplicate/unknown/protected track. Add the narrow `smhd`, version-zero `mp4a`, direct `esds` and audio table interpretation only; no QuickTime version-one/two sound descriptions, `wave` fallback, edit lists or gapless transformation in this slice.
   - Read source sample-entry rate/channels and the exact bounded `esds` DecoderSpecificInfo bytes from the owned raw file. Require one supported descriptor/config path, exact two-byte ASC, reserved/length/child extents and no extra configuration. Reuse `DirectMovCanonical.aacConfiguration`. A tolerant extractor’s overwritten rate/channel values are not proof of the original fields.
   - Validate audio `stts=1024`, exact rate-specific count/duration cap, zero origin, sample count, all-sync semantics, chunk/sample extent bounds, no cross-track overlap, media duration and per-track header duration. Movie duration is the maximum track endpoint under the existing header-rounding rule. Preserve the current video syntax check.
   - Add bounded `readAudioSample` returning the exact raw bytes at the proved sample extent, with **no framing conversion or trimming**. In the proposed trusted-decoder mode this does not claim to parse raw AAC elementary syntax.
   - Both the raw source scan and the independent finalized-output scan use these same explicit container rules. This is not accepting Media3’s interpretation without checking raw table bounds.
2. **`app/.../save/normalization/MovRemuxEngine.kt`**
   - Replace only the one-video dispatch assumption with an exact one-video/optional-one-audio dispatcher. Preserve existing video conversion checks. Add one audio TrackOutput with the same bounded assembly/offset-accounting discipline, independent accumulator and write sequence.
   - Compare callback AAC bytes directly with each raw-source sample; validate actual MIME/rate/channels/ASC, encryption/config/flags, delay/padding and exact PTS. Mux an explicitly constructed AAC Format from proved values. No copied remote metadata and no byte repair.
   - Preserve every audio sample exactly once and write its own empty EOS at its own clock endpoint. Never share the video endpoint or stretch/drop a shorter audio track.
   - Rescan finalized output and walk real Media3 callbacks again, requiring source/output/native-callback conservation equality for both tracks. Existing source/output hashes, sink bounds, no-batching choice and release ownership remain.
   - The extractor step bound must use total admitted video+audio sample count; do not accidentally preserve a video-only progress budget or introduce an unbounded loop.

### B. Native integration itself

3. **`app/.../save/DirectAndroidOutputInspection.kt`**
   - Add small app-only native-audio format facts and validation against the **raw-scanned** audio configuration. Exact CSD is one bounded `csd-0` containing the two bytes; reject extra CSD, positive protection indications, conflicting rate/channels/profile or nonzero delay/padding. If `is-adts` is present, it must be zero. Missing optional hints do not override raw facts.
   - Add `readDirectNativeAacSample` using the existing `DirectNativeSampleSource`; do not generalize or call the AVC canonical reader. It reads exactly the announced native extent, checks track/PTS/all-sync flags and compares every byte to the same-FD raw sample before queueing.
   - Keep a new `DirectMovSampleAccumulator` for the complete actually submitted audio sequence. Reuse `DirectNativeSample` and `DirectSampleIdentity`.
   - Change the ephemeral `DirectInspectedSamples` video-only return to hold the actual video accumulation plus tail plan, rather than inserting expected audio into an “observed” full conservation object. Assemble the full observed `DirectRemuxConservation` only after actual audio conservation succeeds.
   - Minimal optional test seam: a small concrete `queueDirectAudioInput(sample, accounting, queue)` function used by the real adapter and fake queue tests. It performs precheck → queue callback → immediate successful-credit event. It is not a new codec port or owner.
4. **`app/.../save/DirectNormalizedAndroidVerifier.kt`**
   - Enumerate actual tracks, bind exactly one video and optional audio by MIME/config to the raw scan, and pass actual video index through current position/tail readers and decoder selection. Do not assume video is native index zero.
   - Add one synchronous full-track `decodeAudio` function, described below, and call it inside the existing `inspect` native-resource block after input/config inspection and before any native release/final hash/evidence.
   - Add an audio-specific output-buffer wrapper; the existing video wrapper validates YUV and only capacity, so it is not an appropriate PCM extent/format adapter.
   - Construct observed audio conservation from the decoder-input accumulator, observed R/C from validated native facts, and observed PCM result from the helper. Return one audio track/readable=true only after all checks and cleanup succeed. Never copy `expectedAudio`, the producer’s audio contract or a fake result into this field as observed evidence.
   - Video positions use the video endpoint. MMR overall duration and `VerificationEvidence.durationMillis` use the independently scanned combined/max endpoint when audio is longer. Audio and video keep their own exact endpoints.
5. **`DirectNormalizedPreflight` in `DirectAndroidOutputInspection.kt`**
   - Only in the reviewed coherent audio implementation remove its blanket nonnull-audio rejection, replacing it with finite-profile and consistent-audio-fields validation. Otherwise neither the real owner nor verifier can exercise the audio chain. This is an explicit scoped code change after review, not a suggested bypass.
   - Keep unknown revisions, proof/transfer binding, source/output size, ownership and recovery checks. Keep all current rejection behavior until the coherent implementation and decision are accepted.

### C. Tests/docs, no premature wire-format changes

6. Extend **`MovProfileProbeTest.kt`**, **`OriginalMovFixtures.kt`**, existing pinned Media3 host harness, **`DirectAndroidOutputInspectionTest.kt`**, **`DirectNormalizedVerifierSourceContractTest.kt`**, **`DirectNormalizedOwnershipCleanupTest.kt`** and **`FixedDirectOutputInputTest.kt`**. A new focused `DirectAndroidAudioInspectionTest.kt` is reasonable if it keeps audio cases isolated. The existing fake `audio=true` fixture currently adds a handler for rejection; it is not a valid AAC-positive fixture.
7. Update **`MOV_CLOCK_AND_CANONICAL_CONTRACT_20261014.md`** and acceptance documentation to explicitly record the approved trust-policy replacement and residual risks. Add native acceptance cases to the existing acceptance workflow, not a production diagnostic entry point.
8. **No change to the PCM helper’s accounting behavior, SaveModels historical-validation behavior, proof wire layout, SaveRecordCodec/schema version, dependencies, permissions, ordinary MP4/HLS/Record/Editor code or `AndroidSaveRuntime.movAdmissionEnabled=false`.** There is a demonstrated need to distinguish the new active audio-verification policy from an already representable historical audio-readable Boolean: extend `DirectNormalizationContracts.kt` to recognize **policy revision 2 for new audio-bearing proofs**, using the already serialized integer. New silent proofs remain policy 1. The existing broader model and synthetic/historical audio proof must remain readable; “this native adapter previously rejected audio” is not a compatibility argument. Section 3D defines all consumers and recovery behavior. No v9/record-layout migration follows from using the existing policy integer.
9. **`save/.../DirectVideoTailVerification.kt` and its tests**: remove the audio-null rejection in `DirectTailManifestBounds:99–101`, not by manufacturing an audio-null conservation copy. Keep the same complete immutable conservation, the existing exact video clock/index/preceding-sync/last-sample checks, 64 MiB tail bounds and causal one-to-one video decode state. These checks already use `conservation.video`; `DirectVideoTailProof.matches` already checks only video. Update the class precondition/comment to distinguish video input/tail observations from the separately required complete audio observations. A completed video tail with nonnull audio is only video evidence. Test identical video with no/shorter/longer valid audio gives identical tail behavior, all existing invalid video cases remain invalid with audio present, and the full native verifier still refuses success when the separate audio check fails.

The controlled implementation can therefore become testable as a real chain while production MOV admission remains false. Removing the controlled audio rejections is a reviewed implementation step; production enablement remains a separate actual-device acceptance decision. If the parent chooses to keep every audio rejection unchanged during the next code patch, only compile/helper evidence is attainable; label it honestly and do not count that as the real-chain slice.

### D. Policy-2 compatibility and complete active gate map

This is the minimal proposed semantic discriminator. **Do not change a historical proof’s integer in place.** The original proof digest already serializes policy revision, so such a change would invalidate the verification’s `normalizationProofSha256`, workspace `proofSha256`, selected descriptor, record and catalog evidence tuple.

**Durable representation:** keep schemaVersion=1, engineRevision=1, noEncoding=true and the existing profile enum. In `DirectNormalizationProof`’s constructor, keep every valid policy-1 value readable (including its optional audio), and additionally allow policy=2 **only when audio is present with the existing consistent rate/channel/conservation fields**. Unknown policy values and policy-2 silent values reject. The default remains 1 so old/silent producers do not change silently. The new real audio producer explicitly writes 2. `SaveRecordCodec.writeDirectNormalization/readDirectNormalization:257–273` already writes/reads this integer, and `digest():63` already includes it. Do not change their byte order, record v8, evidence v4, domain tags or schema version.

**Active policy:** a small shared predicate next to the proof model can say “silent policy 1, or audio policy 2”; it is a policy eligibility check, never proof that decoding happened. Keep `VerificationEvidence.isValidFor`, `DirectNormalizationVerificationProof.matches`, `SaveRecord` historical construction, codec decoding, terminal reading and existing terminal cleanup structurally compatible. Actual active consumers separately require the current policy and real issued ownership/completion handles. The existing full proof/transfer/digest/ledger comparisons must remain in addition to that predicate.

Complete source-inspected gate replacements, all conditional on review and the coherent implementation:

| Current gate | Required narrow replacement |
|---|---|
| `DirectNormalizationContracts.kt:44`, the model’s `policyRevision==1` | Recognize historical policy 1 and audio-only policy 2 as above; unknown rejects. This is durable shape recognition, not current admission. |
| `DirectAndroidOutputInspection.kt:57–60`, revision-1 and null-audio preflight | Keep exact schema/engine/profile/bindings and apply active policy: silent1 or audio2 plus finite audio facts. Historical audio1 on an active attempt is `RECOVERY_REQUIRED`; no old Boolean promotion. Unknown revision remains rejected. |
| `AdoptedCanonicalOutputOwner.kt:89,167` | Both call the same updated preflight; retain complete authoritative record/ledger/adopted-generation/execution equality. Do not bypass either check. |
| `MovProfileProbe.kt:151–160` | Replace unconditional soun rejection with the finite raw audio container/config/table reader described above, including every declared track and exact ranges. Unknown audio/container/protection still fails. |
| `MovRemuxEngine.kt:70` | Replace blanket audio rejection only with the new actual two-track callback/conservation implementation; never remove it before audio preservation exists. |
| `DirectRawSelectionPolicy.kt:91,94` | Do not reject because the page expects audio. Run the real raw profile scanner; require audio present when expectedAudio=true. If the actual source has audio when expectedAudio=false/unknown, preserve and verify it. No absent-audio downgrade and no ordinary-MP4 behavior change. |
| `NormalizationAttemptOwner.kt:330,334–336` | Replace evidence.audio-null condition with finite real remux-evidence consistency, retaining raw/output identity/hash/release checks. Create policy 2 only from actual audio-bearing remux conservation; silent evidence explicitly/default policy 1. Never select policy from the page expectation alone. Ledger `finishOutput` binds this newly generated proof digest exactly once. |
| `AndroidControlledDirectStorage.kt:493–500` (`proofMatches`) | Retain all raw/output/digest/workspace/transfer/checkpoint bindings; replace audio-null/expectedAudio-false requirements with active policy and `expectedAudio implies actual audio`. This blocks old audio1 in every active caller including recovery/adopt/verify/handoff. Do not put this current-policy rule in generic terminal binding. |
| `SaveCoordinator.kt:821,826,858–859` | Replace expected-audio/any-audio rejections with consistency and active-policy checks on actual returned/selected proof. Preserve runtime MOV flag and typed completion CAS. Apply the selected-proof active check before either VERIFY or COMMIT, including recovery; do not trust a storage-returned COMMIT enum to bypass it. |
| `DirectTailManifestBounds:99–101` | Scope this helper to its existing video checks while retaining full conservation; the helper does not decide current audio admission. No synthetic audio-null copy. |
| `NormalizedCommitProtocol.kt:404–406` | Replace audio/expectedAudio blanket rejection with active proof policy plus expected-audio presence, retaining size/budget, actual claims, `isValidFor`, full ledger tuple, file roles/hashes and real release/commit checks. |
| `AndroidNormalizedCommitJournal.kt:234` (`active`) | Apply the same active-policy condition to SQL intent/index/publication, retaining full binding/evidence checks. Its separate `binding`, `terminal` and terminal-cleanup paths keep historical policy-1 audio readable and do not become a migration or deletion trigger. |

A full `src/main` search found **only two explicit `policyRevision==1` checks**: the model constructor and native preflight. The codec already transports the field. Other guards listed above are audio/expectation gates; changing the two revision comparisons alone would leave the chain unreachable. Existing schemaVersion==1 checks refer to the unchanged schema and must not be widened.

**Recovery/consumer behavior:**

- **Old audio policy 1:** decoding/round-trip and terminal history remain exactly representable. Existing terminal records/media are not deleted, rewritten or reclassified as newly PCM-verified. Unfinished audio1 returns `DirectRecoveryAction.REVIEW` from active storage inspection and surfaces `RECOVERY_REQUIRED`; direct calls into current owner/verifier/commit also refuse it. No network, remux, proof regeneration, automatic policy upgrade or new publication occurs. Keep original files and journal facts.
- **Why no promised in-place local upgrade:** `proofMatches`, `AdoptedCanonicalOutputOwner`, `NormalizationDurableMutationPolicy`, normalization workspace and commit/catalog records bind the old proof digest/whole tuple. The existing finish/proof CAS path is for a new normalized attempt, not replacement of an adopted proof. No existing authorized atomic operation was found that rebinds old proof, workspace digest, selected descriptor, verification and catalog evidence together; at INTENT/INDEXED the canonical primary may already be the final role. Merely running a decoder would not change that authority. Therefore this slice explicitly stops at RECOVERY_REQUIRED, rather than inventing a new recovery/write route or promising fresh-local revalidation can upgrade old1.
- **New audio policy 2 with no stored verification:** raw2 production still requires actual remux; OUTPUT_READY/ADOPT_INTENT proceeds through existing adoption, and ADOPTED with exact STAGING/no evidence proceeds to actual VERIFY. A policy integer alone never authorizes COMMIT.
- **New audio policy 2 with complete stored verification:** preserve the existing durable commit-recovery semantics. `AndroidControlledDirectStorage.inspectRecovery:260–277` can return COMMIT only when its current-policy `proofMatches` succeeds and the stored verification is valid for that exact policy-2 proof; for INTENT/INDEXED the catalog evidence must equal it too. `SaveCoordinator`’s COMMIT skip is then reuse of **previous policy-2 verification**, not a claim that PCM was decoded again during recovery. Both commit layers recheck current policy, full tuple and exact actual file-role/hash. Null/mismatched/wrong-revision verification cannot take this skip.
- **Silent policy 1:** existing live verification and adopted/INTENT/INDEXED recovery remain unchanged. Do not force unrelated legacy revalidation or broaden accepted input.
- **Cross revision:** audio1 evidence cannot match audio2 by copying `audioTrackReadable=true`; its verification binds the policy-bearing normalization proof digest. Changing policy requires a new digest, and rewriting that digest/ledger/evidence to manufacture an upgrade is prohibited. Synthetic tests can construct model objects, but those values still cannot mint storage-issued completion/publication capabilities.

**Required compatibility tests:** old audio1 proof/evidence/SaveRecord round-trip and terminal read; unchanged silent1 recovery; new audio2 round-trip through unchanged record/evidence formats; constructor unknown-policy and silent2 rejection; cross-revision proof/verification/digest mismatch; unchanged ledger digest with changed revision rejects; old1audio unfinished VERIFY/COMMIT/INTENT/INDEXED routes all stop in recovery without IO/remux/network or publication; new2 no-evidence executes native verification; new2 verified ADOPTED/INTENT/INDEXED recovery uses only exact matching prior evidence and still verifies final file role/hash; false audio-readable/new2 mismatches do not publish; typed completion and SQL publication cannot be forged by constructing matching model values. Cover no rewrite/deletion of terminal historical audio1 and all existing silent regressions.

## 4. Minimal native function contract

Keep the function in `DirectNormalizedAndroidVerifier`; conceptually:

```kotlin
private fun decodeAudio(
    extractor: MediaExtractor,
    source: DirectNativeSampleSource,
    input: FixedDirectOutputInput,
    track: Int,
    format: MediaFormat,
    audioClock: DirectMovClock,
    audioConfig: DirectMovAacConfiguration,
    rawSamples: List<MovSampleReference>,
    expected: DirectTrackConservation,
    lease: DirectNormalizedOutputLease,
    checkpoint: () -> Unit,
): Pair<DirectTrackConservation, DirectAudioPcmAccounting.Result>
```

The final implementation can pass the new raw-profile audio section instead of repeating its fields. That section is generated by the scanner over this same owned input, not a public DTO that authorizes itself. The function creates/releases a fresh codec and selects/unselects the audio track; it does not acquire a lease, native lane, path, second descriptor, worker or timeout. It cannot publish or create `VerificationEvidence` independently.

**Authority chain:** durable selected output/claim → existing adopted lease → fixed FD and expected hash → independent raw container/ASC/sample scan → exact Android track/CSD match → actual bytes reread into codec input and compared with the same FD’s raw extent → successful queue → PCM observation → output-buffer return → codec/extractor/retriever release → same-FD final hash/revalidation → evidence → caller’s unchanged typed validation/commit gate.

Test-created clocks, ASC, references, flags and fake sample bytes can exercise rules but do not establish any link in this actual chain. In particular, the native input accumulator must be recomputed from the bytes really queued; receiving `expected.configurationSha256` or `expected.payloadSha256` as an argument is not independently observing it.

## 5. Exact event order, frames, failure and cleanup

### Session setup

1. The existing native worker must already have passed `NativeVerificationLease.start()` and acquired its real adopted lease. Before setup, use its original operation checkpoint and fixed-input identity check.
2. Select only the bound audio track; seek to zero/previous-sync and require exact first track, time zero and all-sync flag. No other selected track may leak into this read pass.
3. Validate the extractor’s actual CSD/rate/channels against raw bytes. Configure a **fresh** `MediaCodec.createDecoderByType("audio/mp4a-latm")` for ByteBuffer output. Use a narrowly constructed format containing validated rate/channels/CSD and request PCM16; no Surface, Crypto, ADTS mode, duplicate queued CSD, adaptive/drop mode, trim, speed, channel conversion or encoder. Check conflicting delay/padding before leaving those keys out. The request is never evidence of the actual output encoding.
4. Start exactly once; no flush/seek reuse, cross-track reuse, replacement thread or decoder fallback after a failed observation. Every allocated object is retained immediately for cleanup even if configure/start fails. Instantiate `DirectAudioPcmAccounting(expected, audioClock, channels, checkpoint)` and one `DirectDecodeProgress(System.nanoTime(), checkpoint=checkpoint)`.

### One media-input iteration

1. Check cancellation/absolute deadline/no-progress, then dequeue input with the existing bounded 10,000 µs wait.
2. For a nonnegative input index, obtain its real buffer; require capacity sufficient for the next bounded sample. Snapshot actual time/track/size/flags. Check exact indexed clock/track, Android sync=1, no encryption/partial/unknown flags, positive sample size ≤4 MiB.
3. Read exactly the declared size, with input identity checks before/after. Establish explicit buffer range `[0,count)` without changing the bytes. Read the expected raw sample from the same fixed FD and require full byte equality. Hash that actual byte sequence; its identity must match raw index/PTS/size/sync and the expected final identity when applicable.
4. Append these verified bytes to the independent input-conservation accumulator (this is prepared-byte evidence, **not queued-frame credit**). `accounting.requireNextInput(nativeSample)`.
5. Call actual `codec.queueInputBuffer(index, 0, count, exactPts, 0)`.
6. Only after successful return, immediately call `accounting.inputQueued(nativeSample)` on this same serial owner, **before any output processing**. A checkpoint failure here aborts; it does not permit a late result. Failed queue calls never grant credit.
7. Advance the extractor once. Mark progress for the successful media queue; no dequeued index, config or format event counts as progress. Do not continue after advance failure.

### Input EOS

After exactly N successful media queues, use the existing strict EOF checks: sample time −1, track index −1, size −1 and actual read −1. Finalize the input accumulator once; require complete equality with raw-scanned `expected` including config, payload, flags, timing, last identity, byte count and endpoint. Then queue exactly one **empty** input EOS at the audio endpoint, record `accounting.inputEndOfStream()` only after the queue succeeds, and mark progress. No input after this. Native EOF before N or extra data after N fails.

### Output event

- Only recognized native output flags are accepted: codec-config, EOS and the existing harmless key-frame bit. Reject partial/unknown flags before mapping Booleans. Config+EOS fails. Do not interpret a buffer flag as evidence about AAC syntax.
- On `INFO_OUTPUT_FORMAT_CHANGED`, read actual format and require `audio/raw`, exact sample rate/channels and PCM16. This event grants no progress or frames. Recheck actual `codec.getOutputFormat(index)` for each media buffer, not merely a remembered requested format.
- Actual PCM16 mapping: missing `KEY_PCM_ENCODING` uses the documented signed-16-bit default; a present float/other value fails. Unreadable keys fail; never convert an exception to “missing”. Check all fields again on later format events.
- For every nonnegative output index, enter a `try/finally` **before** any fallible checkpoint/format/buffer read. Always return that index with `releaseOutputBuffer(index,false)` in the finally.
- Pass the real output ByteBuffer, absolute offset/size/PTS and observed format to the helper. Never expand its limit to capacity or replace observed values with expected ones. The helper traverses the accessible bytes, uses stride `channels*2`, and computes frame count from bytes. Split/coalesced buffers are permitted.
- The helper enforces cumulative frames ≤ successful queues×1,024 and the absolute clock `floor(previousFrames*1e6/rate)` within the existing 2 µs PCM-only tolerance. No future input credit, PTS rebasing, accumulated rounding or one-buffer-per-AU assumption.
- A nonempty EOS buffer first counts PCM, then validates EOS. Empty EOS contributes no timestamp/frame count and may have no ByteBuffer. Helper output returns true only for validated PCM or valid EOS; only then renew no-progress allowance.
- Exit on valid output EOS and obtain `finish()` once. Its exact `N*1024` frames, `N*1024*channels*2` bytes and audio endpoint are **aggregate observed facts**. They are not individual-AU attribution or an independently proved syntax invariant.

The Android API explicitly permits more than one audio AU in an encoded buffer and defines raw audio buffers as complete PCM frames. Its EOS and actual-output-format contracts motivate this adapter, not a claim of strict elementary conformance. Sources: [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec), [PCM encoding](https://developer.android.com/reference/android/media/MediaFormat#KEY_PCM_ENCODING).

### Failure, cancellation and release

- Any native failure, conservation mismatch, helper failure, cancellation or deadline aborts the entire inspection; no retry with changed interpretation and no late successful evidence.
- Expected compressed sequence/byte/table mismatch: `INVALID_MEDIA` (or existing raw unsupported classification before normalization). Unsupported/unreadable PCM, codec absence, native mapping or aggregate PCM mismatch: `VERIFICATION_UNAVAILABLE`. Known protection: `PROTECTED_MEDIA`. Local extent/hash changes remain `RESOURCE_CHANGED`. No completed-local audio error becomes an HTTP retry/`INCOMPLETE_TRANSFER` escape.
- Preserve `CANCELLED`, `DEADLINE_EXCEEDED`, `RETRY_EXHAUSTED` exactly. Native uncertainty still latches independently of the preserved primary stop reason.
- Reuse `finishDirectCleanup`: attempt codec stop if started, always attempt codec release, then unselect; outer finally independently releases extractor/retriever. A stop failure with successful release prevents success but need not poison the process. Codec release failure latches both native recovery and `lease.retainOnNativeReleaseUncertainty()`.
- Returning an individual output index may fail; eventual codec release still owns it. Never count a failed return as successful final verification. Do not skip later cleanup because a prior release threw.
- Only after every native object has actually released may the outer verifier rehash the same FD and revalidate lease/identity. The PCM result stays local until this point. The lease then really closes the original descriptor before retiring its ownership fence; native-uncertainty latch is never cleared by a successful FD close.
- A blocked native stop/release leaves the existing lane/abandonment drain and owned fence held. `Future.cancel` is a request, not physical cleanup. No replacement executor, forced close under native IO or deadline extension is introduced.

## 6. Why the proposed aggregate acceptance is useful, and what it cannot guarantee

Assuming the approved platform/library trust boundary, the combined checks establish materially more than “AAC metadata looks plausible”:

- The compressed bytes, sample boundaries and exact PTS supplied to native decode are the same as the bounded raw-output table says, and output transport equals the preserved source transport. A skipped/changed middle packet, source/output AAC config substitution or timing repair cannot be hidden by a correct final aggregate count.
- Fresh codec creation, single selected track, sequential input, no flush and successful queue-before-credit remove ordinary stale-session/cross-track accounting routes. PCM must be read from actual accessible native output, using its actual format.
- The full track produces observed continuous PCM through EOS with the required frame/byte total and exact rational endpoint. Short/extra PCM, rate/channel changes, unsupported encoding, clock discontinuity, partial frame stride, missing EOS and native errors fail.
- Existing video/metadata/owner/hash/commit gates still apply. Audio success cannot rescue an invalid video, changed file, failed release or abandoned operation.

**Residual risk for reviewer acceptance:** Android may conceal an elementary decoding error, ignore trailing elementary bits/bytes, or produce output whose AU attribution is ambiguous. A pair of unusual/malformed packet contributions can potentially have the correct combined frames/timestamps within queued credit. In-band features not signaled by the two-byte ASC may be interpreted/tolerated without an observable format/count violation. Native decoder bugs remain in the trusted computing base. This design does not make these impossible.

Therefore describe support as the finite **ASC-declared AAC-LC, exact-transport, completely decoded PCM profile on a tested Android implementation**, with no observed rate/channel/trim change. Do not assert “all raw packets formally valid AAC-LC”, “all bits consumed”, “SBR/PS impossible in every payload”, or “every packet separately decoded to 1,024 frames”. Explicitly signaled extension ASC/HE/PS, observed incompatible output and unsupported container semantics still fail. Whether retaining the existing `AAC_LC` model label is acceptable under this narrower operational meaning is part of the policy review, not a stealth rename.

This is sufficient for a reasonable finite playback/remux acceptance policy **if that residual native trust is accepted**. It is not sufficient for the superseded stronger formal-conformance/per-AU property. Tests alone cannot erase this distinction.

## 7. Host, compile and actual-device evidence

### Existing fake seams: real code, fake observations

Use `DirectNativeSampleSource` with owned deterministic buffers and the actual new AAC reader/format/queue bridge; use the real `DirectAudioPcmAccounting` for PCM. Test:

- exact six ASCs and source/sample-entry/native mismatches; duplicate/extra CSD; unknown/positive encryption; invalid ADTS/profile/delay/padding hints;
- native/raw size/hash/bytes, middle substitution, missing/duplicated/reordered sample, wrong track/PTS/flags, every independent EOF fact;
- full accumulator equality and no forged success from expected digests; native bytes unchanged between comparison and queue;
- queue callback throws → no input credit; successful queue → credit visible before synchronous fake output; no continuation after queue/advance failure;
- actual-encoding mapping, key read failure, limits below capacity, nonzero offsets/read-only buffers, null empty EOS, media/config/EOS unknown flags;
- helper’s existing split/coalesced/silence/44.1 kHz clock/causal-credit tests, and adapter passes actual observations unchanged;
- output return/stop/release/unselect each failing, independent later cleanup, cancellation/deadline precedence and release uncertainty latch;
- same-size mutation, truncation, closed/replaced FD, revoked adopted binding and no re-open fallback;
- video index one/audio index zero, silent-source regression, per-track shorter/longer endpoints, combined movie/MMR duration;
- no source-contract change turns the production admission flag on.

A fake raw packet or fake PCM is **never** named a valid encoded AAC fixture or a decoder acceptance test. Source-text assertions may guard call wiring but do not execute MediaCodec.

### Pinned Media3 host tests

Use the already admitted exact extractor/muxer bytecode and owned fixtures. Extend the existing real callback/round-trip tests to two tracks, exact raw ASC, per-track timing and distinct EOS, unchanged AAC bytes, last-sample handling, offset/prefetch assembly and complete output rescan. Preserve raw/canonical digests for comparisons. These are real library transport tests; they still do not run Android decoding or prove elementary validity.

The existing source JAR inspection confirms `AacUtil.AAC_LC_AUDIO_SAMPLE_COUNT=1024` and that its parser reads ASC, not the AAC payload. `BoxParser` replaces sample-entry rate/channels from ASC, explaining the independent raw-field check. `Mp4Muxer` lists AAC support and its audio track timescale derives from sample rate. Pinned inspected source archives:

- extractor SHA-256 `547129d7df7ef9cc3d2622036d3880ab205e5fe3241095049b39c6632c64bc4a`
- muxer SHA-256 `eefa3f69ad13ccc7c59988bd934cc90653fe24ea64f1eb072db5cf9a674ea47e`

These are admitted **source-inspection** hashes, not a replacement for the existing admitted runtime-artifact hash gate. No upstream code was copied.

### Exact Android compile, not device acceptance

The build owner can run the existing offline app debug/release Kotlin compilation plus focused app/save JVM tests and runtime-admission equality on the exact candidate. No new dependencies or Robolectric/mock codec framework are needed. Compiling `MediaCodec`/`MediaExtractor` calls proves API/type compatibility only. This design task performed no Gradle command, assembly or device operation.

### Mandatory actual Android gate before production support

Use only the separately authorized emulator-5556, preserved data and compatible signature/increasing versionCode when the parent schedules it. Exercise the **existing controlled source → remux → adopt → native verifier → typed evidence** route, under its actual claims, same-FD owner and existing lane; keep production MOV discovery/admission disabled until acceptance. No test-only production bypass Boolean is needed.

Use owned, independently documented encoded fixtures. Native-encoder-generated fixtures may be produced with the already available official platform solely for testing; document how PCM length, encoder delay, sample times and the untrimmed packet sequence are obtained. Do not confuse fixture generation with adding a normalizer encoder or pretend opaque synthetic host AAC bytes are decodable.

Required cases: all six rate/channel combinations, two/three packets, audible/silent content, 44.1 kHz partition phase, nonuniform compressed sizes, shorter and longer audio than video, independent EOS, actual CSD/native sample framing, stable PCM16/default encoding, output clock/aggregate total, unknown codec/unavailable representation, cancellation in queue/read/output/release, late physical cleanup, no phantom success, no changed ordinary MP4/HLS/Record behavior. Include malformed/truncated/concatenated/extra-packet fixtures as adversarial observations; if the trusted decoder tolerates one, report its actual aggregate result and apply the reviewed acceptance policy instead of misreporting a syntax rejection.

Measure sample/frame counts and actual native events without logging private source URLs, names, paths, tokens or exception messages. Native success on this emulator is this implementation’s tested capability, not universal device support. The original absolute deadline and five-second no-progress guard remain; a 30-minute profile ceiling does not promise every device can complete full verification in the configured timeout.

## 8. Changes that are expressly outside this slice

No general MOV/WebM conversion, transcode fallback, arbitrary AAC formats, SBR/PS support promise, priming/padding repair, edit lists, timestamp normalization, audio dropping, microphone access, output-file replacement by pathname, private-field upload, new permissions, storage redesign, historical-proof migration or record-schema migration. The explicit new policy value uses the existing serialized field; it does not rewrite old evidence. No copying third-party AAC parser/decoder code or adding a new library. No alteration of Editor/Stream/Record proofs to make Direct pass.

## 9. Stop conditions and review checklist

Do not start the code slice if independent review has not resolved the explicit trust-policy substitution. If accepted, require all of the following before marking the controlled implementation complete:

1. Raw audio container/ASC/sample authority is available from the same-FD independent scanner.
2. Source/callback/output/native compressed conservation is complete for every declared track.
3. The actual Android loop uses the tested helper, correct successful-queue event order, real PCM, same-session EOS and independent cleanup.
4. No helper result can reach evidence before native release and final same-FD hash/revalidation.
5. Fake/host/compile/native results are reported separately; production stays disabled pending the device gate.
6. Documentation claims exactly the newly accepted operational profile and records the residual elementary-conformance risk.
7. Policy-2 live/restore consumers and all listed audio gates are implemented consistently; historical audio1 remains readable but cannot be newly published or silently upgraded; unchanged silent1 recovery is tested.

Do not implement the decoder alone and count it as progress on executable product support when items 1–2 have no producer. Do not expand a test seam into a second production owner or diagnostic subsystem.

## 10. If review retains the older full elementary-syntax/per-AU requirement

The normative-provenance blocker is specific, not a permanent TODO:

- The official ISO listing identifies **ISO/IEC 14496-3:2019, edition 5**; IEC identifies the same standard. The official OBP page returned a JavaScript-only shell in this research, not the syntax/tables. No complete authoritative licensed copy was established in the inspected ESV source. [ISO standard](https://www.iso.org/standard/76383.html), [IEC publication](https://webstore.iec.ch/en/publication/66278).
- W3C’s AAC registration points to `raw_data_block()` §4.4.2.1 and ASC §1.6.2.1, but explicitly calls itself non-normative and does not provide the grammar/codebooks. It is a locator, not adequate parser provenance. [W3C registration](https://www.w3.org/TR/2026/DNOTE-webcodecs-aac-codec-registration-20260924/).
- Minimum acquisition work: first inspect the official OBP preview in the available browser and check any already licensed user-supplied standard/reference access. If the required text is absent, request authorized access to the relevant official edition; do not buy, accept new terms or import a random mirrored PDF automatically. Record edition, clauses, corrections and permitted-use provenance. This is reference acquisition, not dependency admission.
- Minimum verification work: map the chosen LC mono/stereo subset to normative raw-block termination/alignment, element counts/channel layout, individual-channel syntax, section/scalefactor/spectral traversal, window grouping, codebook/escape bounds and extension handling. Determine exactly which normative tables are needed to locate the real end of each AU. Independently review the original bounded implementation and fixtures against that mapping. A “find end marker” byte heuristic, ASC check or Huffman-skipping stub cannot prove complete consumption.
- A wholly fresh decoder session per single packet with EOS can experimentally attribute measured frames to that sole submitted packet; it still does not prove all bytes/bits were consumed or that malformed data was not concealed. It also incurs up to one session per sample and may be impractical under the unchanged operation deadline. It is **not** an equivalent substitute for the old combined syntax/per-AU property on the public MediaCodec contract. A single full-stream aggregate session proves even less about individual attribution.
- No admitted existing strict AAC conformance validator was found. Media3 `AacUtil` validates configuration; Android exposes decoded output, not a normative raw-bit-consumption certificate. A new reference decoder/validator would require separate source/license/runtime review and is outside the no-new-dependency slice.

Consequently, a complete parser route requires real new prerequisite work. The recommended trusted-decoder route avoids that work by openly choosing a different sufficient product acceptance property, not by pretending to have fulfilled the stronger one.

## 11. Design-task evidence

Read-only source/requirements inspection and public official documentation research only. No source edits, build, test, device, remote, upload, APK, dependency or runtime operation occurred. Only this external design deliverable was written and then revised after review feedback. Source-reference anchors are recorded above; all future test outcomes remain NOT_RUN.
