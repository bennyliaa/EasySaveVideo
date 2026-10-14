# Direct MOV exact-clock and canonical-byte contract, v1

Date: 2026-10-14 UTC. **Design and pinned-source inspection only. MOV host bytecode tests and device acceptance are NOT_RUN for this document.** No production source, existing contract file, dependency, Gradle task, APK or device was changed. This narrows the proposed adapter profile; the existing `DirectRemuxConservation` constructor remains a broader contract envelope, not an admission checker.

## 1. Selected engine and deliberately finite profile

Use admitted **Media3 1.11.1 `Mp4Extractor` → `Mp4Muxer`**, directly. Use `Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)`; do not enable edit-list workarounds, dependency-derived extra flags, auxiliary tracks, or sample-table omission. Do not use Transformer, MediaCodec, a decoder, or an encoder in this remux adapter. Final Android decode verification remains a separate required gate.

## Production admission decision from independent review

The first adapter profile admits **silent MOV only**. Audio admission defaults closed.
Any declared audio track rejects the entire source as UNSUPPORTED_MEDIA before
muxing; it must never be dropped to create a silent output. The optional AAC models,
clock/digest formulas and synthetic host cases below specify a future proof target,
not enabled support. ASC plus a declared 1,024-tick stts duration and successful
mux/extract byte conservation do not prove that each raw access unit actually
decodes to 1,024 PCM frames. Audio may be enabled only after a bounded executable
AU syntax/structure check plus independently tested actual decoder per-AU/total
PCM frame and timestamp evidence, or an equivalent executable proof, exists.

This first slice helps only already-silent, exact-profile QuickTime/AVC sources.
It does not cover typical camera MOV with audio, rotation, edit lists or a different
timebase, and is not general MOV conversion. Completing AAC PCM frame/timing
verification is an explicit next original-requirement item, not a permanent scope
exclusion. CLOCK-AAC and AV-TAIL synthetic host cases establish mux transport only;
no AAC validity, decoder support or production admission follows from those tests.

Retain the reviewed design's structural, protection, ownership, bounds and lifecycle gates. This document adds these mandatory restrictions:

- Exactly one AVC video track and zero or one AAC-LC audio track; every declared track must be accounted for. Both tracks start at exact media tick zero. No `edts/elst`, `ctts` (including an all-zero source table), offset, gap, priming, padding trim, external reference, orientation change, fragmented media, or sample-description switch.
- Video input **media timescale is exactly 90,000**. Every `stts` delta, including the final sample, is the same positive integer `D = 90,000/f`. Allowed integer `f` is exactly **{1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 15, 16, 18, 20, 24, 25, 30, 36, 40, 45, 48, 50, 60}**. No other integer or fractional frame rate or input video timescale is admitted in v1. The positive 32-bit `stts` entry counts sum exactly to the sample count; equivalent split runs are allowed only when every delta equals `D`.
- AVC is the reviewed progressive 8-bit Baseline/no-B profile. Source `avcC` has version 1, length-size 4, exactly one SPS and exactly one PPS, no extension/trailing data, valid reserved bits, and profile/compatibility/level bytes matching the actual SPS. The sole PPS references the sole SPS; every picture uses them. SPS/PPS are never regenerated. Config changes and unsupported NAL semantics are rejected. The v1 sample NAL allowlist is {1 (non-IDR slice), 5 (IDR slice), 7 (byte-identical SPS), 8 (byte-identical PPS), 9 (consistent AUD)}; reject SEI, filler, end-marker, extension and every other NAL type rather than drop it. Admit only I/P slice types with no slice groups/FMO or other unsupported partition/reordering semantics. See the byte rules below.
- Video sample 0 is an IDR sync sample. A source sync flag must agree with an independently checked IDR access unit; a non-IDR I picture is not invented as a sync point. Reject a source that would make the muxer silently discard pre-keyframe pictures. Preserve every admitted NAL, slice and access-unit boundary.
- Optional AAC input media timescale equals the actual sample rate `R ∈ {32,000, 44,100, 48,000}`; **every** `stts` delta is exactly 1,024. One complete raw AAC access unit per MP4/MOV sample, with no ADTS wrapper, concatenation, split, extra AU, SBR/PS, or codec-config sample. Prove the admitted AAC syntax/frame-length; `stts` alone does not prove encoded frame content.
- AAC `esds` carries exactly one two-byte AudioSpecificConfig (ASC): object type 2, indexed frequency, channel config 1 or 2, `frameLengthFlag=0`, `dependsOnCoreCoder=0`, `extensionFlag=0`, no extra bits/bytes. Exact allowed ASC values: 32 kHz mono `1288`, stereo `1290`; 44.1 kHz mono `1208`, stereo `1210`; 48 kHz mono `1188`, stereo `1190`. Sample-entry rate/channels, ASC and extractor Format must agree. No source gapless metadata or nonzero encoder delay/padding.
- Each track has at least two nonempty samples. Video `N ≤ 1,800f` (thus ≤108,000). Audio `N ≤ floor(1,800R/1,024)`: 56,250 / 77,519 / 84,375 respectively, also below the existing 90,000 envelope. Exact rational end must be ≤1,800 seconds. Source `mdhd.duration` equals `sum(stts)` exactly, not unknown/zero. Reject arithmetic overflow before multiplication/allocation.
- For the first owned-fixture adapter profile, source movie timescale is also restricted to **10,000**, with track/movie header durations exactly as in §2.4. This avoids importing another movie-header rounding convention. Widening either source clock is a future reviewed profile change, not a retry/fallback.

The finite set is deliberate. For example 32 fps has a 2,812.5-tick video period at the pinned 90 kHz output clock; the first 31,250 µs input PTS becomes 2,813 ticks / 31,255 µs on extraction. That is not the exact existing microsecond timing representation and exceeds its 2 µs envelope. Reject, rather than widen tolerance or silently retime.

## 2. Exact timestamp, endpoint and rate representation

### 2.1 Integer functions and domains

All values here are nonnegative integers. `T` is 90,000 for video or `R` for AAC; `D` is the constant sample duration in that track's ticks. `i` is a zero-based sample index; `N` is the proved sample count. Define with checked integer arithmetic (no float/double):

- `ticks(i) = i × D`, for `0 ≤ i ≤ N`.
- `floorUs(q,T) = floor(q × 1,000,000 / T)`.
- `ceilUs(q,T) = ceil(q × 1,000,000 / T)`; implement quotient plus nonzero-remainder, not overflow-prone addition.
- `halfUpUsToTicks(u,T) = floor((u × T + 500,000) / 1,000,000)`.
- `halfUpTicksToUs(q,T) = floor(q × 1,000,000 / T + 1/2)`; implement quotient/remainder to handle ties exactly.

`U[i] = floorUs(ticks(i),T)` is the **only** accepted source/extractor/mux-input/output-extractor presentation time. Do not subtract a discovered first timestamp, estimate a relative origin, round successive deltas, or use `Format.frameRate` as timing evidence. `frameRate` in the Direct proof is the exact integer `90,000/D` established by the raw table. An extractor's computed float frame rate is informational; construct the mux Format from proved whitelisted values.

### 2.2 Why this profile round-trips exactly

The pinned extractor uses `RoundingMode.DOWN`, which is floor on this nonnegative profile. The pinned muxer converts each **absolute** input PTS with HALF_UP and only then subtracts consecutive converted timestamps. It always uses video `T=90,000`, audio `T=sampleRate`.

For any admitted tick `q`, let `u=floorUs(q,T)`. The error in `u×T/1,000,000` is strictly less than `T/1,000,000 ≤0.09` ticks. Thus `halfUpUsToTicks(u,T)=q` exactly. This applies independently to all sample timestamps **and the EOS endpoint**, including the 30-minute boundary. No error accumulates. Therefore output `stts` deltas remain exactly `D`, output timestamps are exactly `U[i]`, and source/output timing digests must be **byte-for-byte equal**, without tolerance.

An arithmetic-only local enumeration checked all 1,094,770 sample/endpoint clock positions across the 23 admitted video rates and 3 admitted audio rates through their 30-minute caps. It verified exact tick recovery and identical floored output microseconds. This is a finite integer calculation, not execution of the muxer, a decoder, or the forthcoming MOV host suite.

### 2.3 Endpoint and EOS

For each track independently, `E=ticks(N)` and:

- `DirectTrackEndpoint.minimumUs = floorUs(E,T)`.
- `DirectTrackEndpoint.maximumUs = ceilUs(E,T)`.
- Width is 0 or 1 µs. The existing 2 µs cap is an outer envelope, not permission to weaken this generator.
- Final sample identity is `(N−1, U[N−1], canonical length/hash, sync flag)`. `precedingVideoSync` is the greatest index with sync=1 at or before that final sample.
- The media endpoint is obtained from bounded raw `stts` plus independently agreeing `mdhd`, on both source and output. Never infer it from last PTS, a duplicate-previous heuristic, `Format`, `SeekMap`, `TrackOutput.durationUs`, or decoded EOS timestamp alone.

Explicitly configure `LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS`. After all source sample callbacks and final exact accounting succeed, call **once per admitted track**:

`writeSampleData(trackId, ByteBuffer with position=limit=0, BufferInfo(floorUs(E,T), 0, C.BUFFER_FLAG_END_OF_STREAM))`.

This control buffer is not a sample, has no payload/config digest entry, does not increment `N`, and never has KEY_FRAME/LAST_SAMPLE/codec-config flags. Do not share the maximum video/audio endpoint: each track gets its own endpoint. A shorter audio track stays shorter; no silence, truncation, delay or tail stretching is introduced. If no audio track exists, do not add an empty track or audio EOS.

The wrapper rejects zero-length media samples, EOS before the proved final sample, duplicate EOS, EOS with nonempty data, any following write, missing EOS at close, and EOS time other than the exact proved `floorUs(E,T)`. Pinned `Track.writeSampleData` treats only an **empty** EOS buffer as the endpoint; a nonempty EOS buffer does not establish this state. Empty non-EOS samples are silently skipped by the library, so they must be rejected by ESV before calling it. For two samples the library's no-EOS fallback produces a zero-duration final sample; EOS is mandatory even if a longer CFR fixture happens to pass without it.

### 2.4 Movie/header duration is a different clock

The pinned muxer writes exact `mdhd.duration=E` and `mdhd.timescale=T`, but its `tkhd/mvhd` clock is 10,000 Hz:

- `A_track = halfUpTicksToUs(E,T)`.
- `tkhd.duration = halfUpUsToTicks(A_track,10,000)`.
- `mvhd.duration = halfUpUsToTicks(max(A_video,A_audio if present),10,000)`.
- Both first PTS are zero, so **no output `edts/elst`** is produced. No B-frame/reordering means **no output `ctts`**.

Validate these header values by this exact two-step formula, separately from Direct media endpoint equality. Do not compare movie-header-derived duration to the ≤2 µs endpoint. In particular pinned `Mp4Extractor` prefers `tkhd`-derived track duration in its `durationUs` callback, so that callback is not precise track-end evidence.

Golden clocks (PTS and endpoint are integer µs; header values are 10 kHz ticks):

| Track, N | PTS | Direct endpoint [min,max] | tkhd.duration |
|---|---|---|---|
| Video 30 fps, 2 | 0; 33,333 | [66,666,66,667] | 667 |
| Video 24 fps, 3 | 0; 41,666; 83,333 | [125,000,125,000] | 1,250 |
| AAC 32 kHz, 2 | 0; 32,000 | [64,000,64,000] | 640 |
| AAC 44.1 kHz, 2 | 0; 23,219 | [46,439,46,440] | 464 |
| AAC 48 kHz, 2 | 0; 21,333 | [42,666,42,667] | 427 |

## 3. Shared canonical bytes; no payload repair

One original bounded helper must be called by **both raw source and raw output scanners**, plus extractor-callback comparisons. Do not maintain a separate output interpretation. Callers select an explicit framing enum; there is no AUTO sniff/fallback.

### 3.1 AVC access units

- `AVCC4`: parse the exact sample extent as repeated `u32be(nonzero NAL length) || NAL bytes`. Length is bounded by remaining extent; reject truncation, leftovers, zero length, overflowing lengths/counts. At most 1,024 NAL units per sample. Source and output `avcC` both declare length-size 4 in v1.
- `ANNEX_B`: only a complete bounded sample callback buffer, starting at position zero. Recognize exactly three- or four-byte start-code separators (`000001` or `00000001`), preferring the four-byte form when it matches, with no leading/trailing padding. Require a nonempty NAL after every separator. The actual pinned extractor emits four-byte separators. Three-byte separators are permitted only by the helper so their equivalence can be proved; they do not admit a different raw MOV sample framing.
- **Crucial conservative restriction:** accepted NAL payloads must not end in `00`; forbidden/unescaped `000000`, `000001`, `000002` inside NAL bytes are rejected; `000003` requires a following byte in `00..03`. Retain every emulation-prevention `03`. This removes the otherwise ambiguous boundary between a length-delimited terminal zero and Annex-B framing zeros. The pinned converter can strip zero framing, so merely hashing its already-converted result would fail to detect lost source bytes.
- The canonical result is the exact ordered NAL sequence `Σ u32be(nal.size) || nal`. Preserve NAL order, every byte (including NAL headers, admitted AUD, duplicate matching in-band SPS/PPS, and emulation-prevention bytes), access-unit boundaries and all slices. Never sort, de-duplicate, strip meaningful trailing zeros, unescape RBSP into the digest, drop AUD/SEI, inject SPS/PPS, or regenerate a NAL. Unsupported NAL kinds are rejected as a profile decision, not omitted.
- Profile validation additionally checks AVC syntax, no-B/no-interlace restrictions, stable IDs/config and one complete picture per sample. The canonical framing helper alone is not a codec validator. Parameter-set parsing may use a separate unescaped read view; the digest always uses original EBSP bytes.
- Both source size and canonical size must be in `1..4 MiB`. A source NAL that the pinned default Annex-B converter would alter is unsupported. Before any mux write, require canonical(raw AVCC4 source sample) = canonical(assembled extractor Annex-B sample) byte-for-byte, and require its exact pinned conversion to AVCC4 to match that same sequence in the host acceptance suite. Final output repeats the same raw/Extracted cross-check.

The v1 no-terminal-zero restriction is intentional. Do not reuse the HLS helper's permissive leading/trailing-zero interpretation as Direct admission; that suite checks converter behavior, whereas Direct additionally owns the original length-delimited sample boundaries.

### 3.2 AVC configuration and AAC

AVC configuration consists of exactly the original SPS NAL then the original PPS NAL, **without** their container length fields/start codes. Raw `avcC`, the extractor's two initialization buffers, and raw output `avcC` must produce the same tuple through the shared bounded parser. Each initialization buffer must contain exactly its one expected NAL, and SPS/PPS satisfy the same unambiguous EBSP rules. Each raw parameter set is ≤65,535 bytes; combined raw config and canonical config encoding must each fit the existing 64 KiB cap. Do not hash `Format.toString`, codec strings, raw avcC box headers, or arbitrary initialization-list serialization.

Pinned `Boxes.avcCBox` takes only the first NAL from initializationData[0] and [1], and writes one SPS/one PPS. This is why multiple parameter sets and extra initialization buffers are rejected **before muxing**, even if all would parse in the extractor.

AAC sample canonical bytes are exactly the original raw AAC sample bytes, unchanged; there is no ADTS removal or payload normalization. AAC config canonical bytes contain the exact admitted two ASC bytes, never reconstructed from sample rate/channels. Reject extra initialization buffers. Pinned `esdsBox` copies initializationData[0] verbatim, so this tuple is conserved.

### 3.3 Exact SHA-256 byte protocol

Use SHA-256, lower-case 64-character hex in the existing fields. All integers are unsigned big-endian with the exact widths below; no platform ordinal, native byte order, JSON, delimiter text, or locale conversion. Lengths/counts are checked before serialization. Define:

`H(domain,kind) = ASCII("ESV-DIRECT-REMUX") || 00 || u32be(1) || u8(domain) || u8(kind)`.

Kinds: AVC_BASELINE=1, AAC_LC=2. Domains: CONFIG=1, PAYLOAD=2, FLAGS=3, TIMING=4. The numeric tags are fixed contract values, not enum ordinals.

- `codecConfigurationSha256`: hash `H(1,kind) || u32be(recordCount) || records`. AVC recordCount=2; records are `u8(7)||u32be(SPS.size)||SPS`, then `u8(8)||u32be(PPS.size)||PPS`. AAC recordCount=1; record is `u8(5)||u32be(ASC.size)||ASC` (5 denotes DecoderSpecificInfo, not AAC object type).
- `DirectSampleIdentity.sha256`: plain `SHA256(canonical sample bytes)`. Its index/PTS/flag are bound by the separate sequence fields, not by this per-sample byte hash.
- `payloadSha256`: hash `H(2,kind)`, then for every sample in order `01||u64be(i)||u32be(canonical.size)||canonical`, then footer `ff||u64be(N)||u64be(totalCanonicalBytes)`.
- `sampleFlagsSha256`: hash `H(3,kind)`, then each `01||u64be(i)||u8(sync)`, then footer `ff||u64be(N)`. `sync` is exactly 0 or 1. Every AAC sample is 1; video flags match the proved raw sync table and access units.
- `timingSha256`: hash `H(4,kind)||u32be(T)||u32be(D)`, then each `01||u64be(i)||u64be(U[i])`, then footer `ff||u64be(N)||u64be(E)||u64be(floorUs(E,T))||u64be(ceilUs(E,T))`. This binds the normalized-microsecond representation, exact clock/profile, all order positions and the proved final duration. No EOS record is appended as a sample.
- Source and output proofs compare all fields exactly, including kind, config hash, count, canonical byte total, payload/flags/timing digests, first PTS=0, final identity, endpoint, dimensions, integer frame rate, audio presence/rate/channels and preceding video sync. No tolerance-based digest matching and no HLS origin/tick recovery mode.

Byte-protocol goldens (framing/digest only; the short AVC bytes do not assert decoder-valid syntax):

- SPS `6742001e80`, PPS `68ce06e2` → CONFIG SHA-256 `d7043c95da89ec697ef3d135685964aef547739e1b254a2e81384e6865d56389`.
- ASC `1210` → CONFIG SHA-256 `9ec82d69968492e81640faa1381a5e106d6750c9f0cfdb8cd4cedfca9ca7d4c1`.
- Canonical sample `0000000265aa0000000761bb00000301cc` → sample SHA-256 `ba1df6b884c3c490701d39b361014828eb974d167d64e54a90b7201eef9dae74`.

### 3.4 Callback flags and bounded assembly

For this flags=0 extractor configuration, accept only KEY_FRAME and the pinned LAST_SAMPLE bookkeeping bit. LAST_SAMPLE must appear only on the preflight-proved last callback, and is removed before calculating the stored sync bit; reject any other flag, EOS/codec-config metadata flag, cryptoData, encryption or supplemental part. Raw source sync flags remain authoritative and must agree with extracted sync flags. Require AAC all-sync; require video first-sync.

Do not assume one sampleData call is one sample. Maintain a bounded per-track byte assembler for both DataReader and ParsableByteArray paths, requiring MAIN data, positive progress, exact accounted consumption, and source EOF only where allowed. On metadata, `start=pendingSize-offset-size` must be exactly zero; emit exactly `size` bytes and retain exactly the trailing `offset` bytes. Reject negative/overflowed values, incomplete ranges, unaccounted leading data or leftover bytes at complete EOF. Limit one media sample to 4 MiB and pending sample-plus-prefetch to 8 MiB per track (offset ≤4 MiB). This explicit bound covers offset callbacks without unbounded allocation; pinned MOV currently reports offset=0.

Require complete raw table / extractor / mux-write / output-scanner sample counts to agree. Do not permit `RESULT_END_OF_INPUT`, mux close, or extractor duration callback to stand in for accounting. Handle validated `RESULT_SEEK` by repositioning the same owned input without resetting extractor state. Rehash the closed raw source after scanning; a changed raw source invalidates the proof.

## 4. Owned fixture and mutation matrix for the next host slice

Use original deterministic MOV builder bytes and synthetic AVC/AAC bytes already allowed by repository fixture practice, never copied public media. A true codec/decode fixture is a separately labeled later device gate. Reuse the admitted bytecode runner/toolchain/shims and existing SHA gates, but write a Direct-specific suite; do not claim the HLS suite proves this contract. Source builder and raw output-box inspector must be independent of the muxer and shared digester, so a common implementation error cannot self-confirm.

| ID | Owned case / parameterization | Required evidence |
|---|---|---|
| CLOCK-ALL | Each of the 23 video rates; N=2, N=3, and a small sequence spanning the complete µs rounding phase; arithmetic all indices through 1,800f | Raw source ticks, exact callback PTS, input PTS, output stts/mdhd ticks, output callback PTS, all digests and endpoints agree. Never use float fps. |
| CLOCK-AAC | Each R × mono/stereo (6 ASCs), N=2, N=3 and a sequence spanning `R/gcd(1,024×1,000,000,R)` phases | AAC payload/config/flags/count/ticks exact; all six configs preserved; terminal caps 56,250/77,519/84,375. |
| AV-TAIL | 30 fps video plus each AAC rate; once video longer, once audio longer; both zero origin | Per-track EOS and ends remain distinct; overall endpoint=max of exact per-track ends; no inserted/dropped samples or edts/ctts. |
| EOS-TWO | Exact two-sample video and AAC tracks, with and without explicit EOS; a 3/4-sample CFR comparison | Accepted path has N stored samples and final duration D. A no-EOS two-sample output exposes zero final duration and fails; no duplicate-previous acceptance. Longer accidental fallback agreement never substitutes for wrapper EOS state. |
| EOS-BAD | Missing/early/duplicate/nonempty EOS, empty non-EOS sample, write after EOS, wrong track endpoint; EOS endpoint shifted by one track tick | Wrapper rejects invalid input; altered output duration fails exact table/proof comparison. Also reject an input EOS +1 µs even if quantization would yield the same output tick. |
| HEADER-CLOCK | The five golden clocks in §2.4; AAC shorter/longer variants | Verify exact mdhd/stts and separately exact two-stage tkhd/mvhd values. Demonstrate that extractor durationUs can lie outside the narrow endpoint without weakening media proof. Corrupt either header/table independently and reject. |
| RATE-REJECT | Video fps {7,11,29,32,59}, 30000/1001; source video timescale 600; rate changes; zero/negative/overflow delta; mdhd mismatch | Fail closed before mux, even if the general extractor accepts; source 32 fps example demonstrates >2 µs conversion error. |
| FRAMING | One/multi-NAL and multislice AUs; three/four-byte helper prefixes; valid emulation-prevention sequences; identical in-band config if profile permits | Raw AVCC4 ↔ extractor Annex-B ↔ actual pinned converter ↔ output AVCC4 canonical bytes identical, ordered and length-delimited. Three-byte helper case does not widen source profile. |
| FRAMING-BAD | Terminal 1/2/3 zero payload bytes, leading/trailing framing zero, zero-length NAL, oversized/truncated length, embedded unescaped start code, malformed EPB, >1,024 NALs, unsupported NAL kind | Reject without trimming, repairing or omitting a NAL. Include cases where the pinned converter alone would silently change source AVCC payload. |
| CONFIG | Exact one SPS/PPS, all 6 ASCs, three digest goldens; byte-mutated SPS/PPS/ASC with unchanged high-level codec label | Shared helper gives exact golden hashes; mutation changes proof or fails profile. Two SPS, two PPS, extra init buffer/avcC bytes, inconsistent avcC/SPS or ASC/sample-entry values all reject before mux. |
| FLAGS-TAIL | IDR→P→P with last non-sync; all-IDR; no initial IDR; forged final sync; LAST_SAMPLE on wrong index; unknown flags | Exact sync digest and final/preceding-sync identities; no pre-keyframe silent drop; only final extractor bookkeeping LAST_SAMPLE removed. |
| CONSERVE | Independently mutate actual output: drop/duplicate/reorder sample; change video/AAC middle or last byte; shift one PTS by a tick; change final stts duration; drop audio; append EOS as real sample | Every mismatch detected despite plausible count/header combinations. Exact pre-mux guards also reject input PTS +1 µs even when output quantization masks that difference. |
| IO-ORDER | moov-before/after-mdat; stco/co64; one/multiple samples per chunk; both sampleData APIs fragmented at every prefix/length boundary; synthetic valid offsets; internal RESULT_SEEK | Same evidence, bounded assembly, fixed-extent seek, no reset/lost bytes; invalid offsets/leftovers/zero progress reject. |
| LIMITS/RELEASE | 4 MiB sample boundary and +1; config/counter/box bounds; bounded output seek/write/truncate/finalization; unchecked mux close failure then sink-close failure | Reject before oversized allocations/writes; both owned source and sink receive independently verified close handling; no publication on uncertain release. Existing stage-1 storage/ownership gates stay intact. |

Run actual bytecode cases only under the parent's serialized host/build ownership, after implementing the helper and original fixtures. A passing host suite proves deterministic byte/sample/timing conservation, not Android playback, terminal decoded output, native release or production MOV support. Keep production admission disabled until those separately required gates pass.

## 5. Pinned local source anchors

All anchors below refer to files inside the already preserved official 1.11.1 source JARs under `third_party/candidates/media3-1.11.1/sources/`; no upstream implementation is to be copied into production.

- `androidx/media3/extractor/mp4/BoxParser.java:533–537,650–670,739–755`: stts tick construction and tick→µs conversion without edits. `:505–508` computes only an approximate float frame rate. `:1200–1225` parses mdhd duration.
- `androidx/media3/common/util/Util.java:2092–2107,2123–2132`: scaleLargeTimestamp/array scale use `RoundingMode.DOWN`.
- `androidx/media3/muxer/Track.java:78–98`: empty EOS handling, post-EOS rejection, silent pre-keyframe/empty sample drop. `:122–127`: fixed 90 kHz video and audio sample-rate clocks.
- `androidx/media3/muxer/Boxes.java:943–998,2084–2088`: HALF_UP absolute PTS→ticks before duration subtraction, including EOS. `:1432–1449`: explicit EOS final duration and fallback zero for <3 samples.
- `Boxes.java:180–198,265–292,340–352,378–390,422–436,1425–1429`: exact track duration and double-rounded movie header clocks. `:869–884` omits edts at zero origin. `:1039–1046,1098–1130` omits ctts without B-frame ordering.
- `androidx/media3/extractor/mp4/Mp4Extractor.java:703–719`: duration callback prefers tkhd-derived track duration. `:951–1013`: replaces AVCC length fields with four-byte Annex-B delimiters. `:1057–1070,1257–1266`: sample flags and optional dependency flag parsing. `androidx/media3/extractor/mp4/TrackSampleTable.java:89–90`: final LAST_SAMPLE bookkeeping annotation.
- `androidx/media3/extractor/AvcConfig.java:42–57,70–86`: avcC parameter-set list and parsed SPS. `androidx/media3/muxer/Boxes.java:1470–1523`: first-SPS/first-PPS-only writing, four-byte output lengths. `:1878–1932`: exact AAC initializationData[0] copy into esds.
- `androidx/media3/muxer/AnnexBUtils.java:41–75,122–170`: splitter and zero/start-code termination semantics. `AnnexBToAvccConverter.java:38–65`: ordered four-byte-length conversion.
- `androidx/media3/muxer/Mp4Muxer.java:255–270,549–580,647–679`: EOS-mode/converter builder, batching-disabled buffer lifetime, size equality and unchecked-close caveat. `Mp4Writer.java:160–183` writes pending samples immediately with batching disabled.

Source JAR SHA-256 identities:

- media3-muxer: `eefa3f69ad13ccc7c59988bd934cc90653fe24ea64f1eb072db5cf9a674ea47e`
- media3-extractor: `547129d7df7ef9cc3d2622036d3880ab205e5fe3241095049b39c6632c64bc4a`
- media3-common: `c9a67ff8d485c9aeb77229a53ae91e39acdf9f591c9c8aff358307a4da031f21`

Existing actual-bytecode runner reference: `app/src/testSupport/hls-mux-conservation/run.sh`; it pins muxer AAR `f68fa5996882a5f08ea0925463bc56e1c2b20ead3f556dadba24686fc4c979f1` and extractor AAR `6b2d58d23a0c53425249d8b7c945600019d61a8b404c8fb8cd9ca860c5738508`. Those pins and the existing dependency admission remain unchanged.
