# Independent read-only review of the local HLS EOF compatibility candidate

Date: 2026-10-06. Reviewer scope: frozen `esv-hls-compat-candidate/`, the EOF research report, admitted Media3 1.11.1 source/binary contracts, and focused standalone host replays. Candidate and main checkout were not edited. No Gradle, network download, dependency addition, source fork, APK/AAB, emulator/device, Transformer/muxer execution, or native decoder was used.

## Verdict

No confirmed implementation blocker was found within the explicitly restricted candidate profile. The candidate is suitable to take into the documented integration work; this review is not production or Android acceptance. Independent source review and host replay are now established for the exact frozen manifest below.

The shipped documentation correctly keeps the independent full output MP4 sample scan, actual final-GOP decode through output EOS, complete Android HLS loading lifecycle, and actual-release fences as mandatory acceptance gates. They are not implemented or proven by this candidate.

## Exact reviewed identity and executed evidence

- `esv-hls-compat-candidate/SHA256SUMS` SHA-256: `c1f14d86010216a965008fa028f57cae42fc4071b6ae9630450a72b1fca51a90`.
- Every manifest entry verified before review/replay and again after the exploratory tests.
- All 11 Media3 artifact hashes recorded in `evidence/EVIDENCE.json` match the currently admitted AARs under `EasySaveVideo/vendor/media3-maven/`.
- Independent standalone replay: **77/77 shipped tests passed**, output `esv-hls-review-host/results.log`.
- Extra review suite: **32/32 passed**, comprising the 28 original adapter tests plus **4 additional tests**, output `esv-hls-review-host/extra-build/results.log`. Thus 81 distinct test cases were exercised; the 28 repeated cases are not extra unique coverage.
- Extra source: `esv-hls-review-host/extra-src/LocalAssetAdapterReviewTest.kt`; replay script: `esv-hls-review-host/run-extra.sh`.

Additional tests exercise:
1. Inherited SPS/PPS, two-slice P pictures, initial IDR, three sequential segments, bounded and unbounded PES, inspection versus custom HLS factory, byte-at-a-time reads, and peeking. Three samples, flags `[keyframe, 0, 0]`, equal normalized sample evidence, exactly one finalization.
2. A final source EOS without trailing AUD, bounded and unbounded PES. Exactly one real sample and one EOF callback.
3. Sixteen unbounded PES/resources versus concatenated bytes. Equal normalized sample evidence, sixteen samples, exactly one finalization.
4. Reader release before finalization prevents later read/completion.

All media fixtures remain synthetic transport/header data, not entropy-valid media or decoder evidence.

## Reviewed correctness properties

### EOF, samples, and reader composition

- `FinalAccessUnitGate.kt:18-32` transitions before the callback, permits at most one pending real VCL AU, verifies video and audio counts both before/after as applicable, skips a legacy already-delimited tail, and makes failure sticky. Callback failure cannot be retried.
- `LocalAvcExtractor.kt:83-112` binds each input to the current receipt, treats repeat consumed EOF as idempotent, and completes the final real sample before returning `END_OF_INPUT`.
- `LocalAvcExtractor.kt:120-130` shields both AVC and AAC payload readers from `TsExtractor`'s `instanceof PesReader` EOF reparse. The pinned source contract was checked; neither codec is accidentally left exposed.
- `LocalAvcExtractor.kt:132-172` enforces bounded pending/sample bytes, rejects empty/config-only video samples, unsupported crypto/data parts, impossible offsets, and extra sample metadata. It is a count/VCL guard, not a replacement for main's stronger per-sample conservation proof.
- `LocalHlsCompatibility.kt` uses the same `LocalAvcExtractor` and profile evidence for inspection and actual custom HLS factory composition. It passes HLS's timestamp adjuster through and does not fabricate clocks from EXTINF.
- `LocalHlsExtractorFactory.kt` rejects second creation, recreation and truncated/trick-play recovery; finality is not guessed from the first factory URI.

### Resource and cancellation states

- `LocalAssetReadSession.kt:47-116` enforces one claimed extraction, exact ordered whole-resource opens, expected byte count and captured SHA, identity verification, object-identity input binding, monotonic parser position, final immutable resource index, and close-after-consumed-EOF. Cancellation and any guard/identity failure remain sticky.
- Source EOF alone cannot commit the tail. Received-all-but-unconsumed data fails the parser-position guard. Foreign receipts and recreated/rewound input cannot borrow finality.
- `PermitDataSourceFactory.kt` only admits canonical local playlist/segment addresses and GET semantics; the playlist carries no media permit. Read/open/close errors revoke the pass. No new logging, external URL access, or identifier-bearing diagnostic strings were introduced.
- Actual release/handle ownership remains with the existing local-only factory/session; wrapper receipts do not prove release. `HlsMediaChunk` closes quietly in its framework lifecycle, making the explicit final `verifyComplete()` and existing release-status fence important.

### Strict framing and compatibility limits

The probe retains packet alignment/continuity and PSI/PES/ADTS checks, stable SPS/PPS, same-picture ordered slice-prefix interpretation, initial usable configuration plus IDR, strict no-wrap monotonic PTS, and audio rational cadence. Ordinary segment-start P pictures and inherited parameters are intentionally admitted; no-AUD, mixed-AUD, cross-segment PES/AU, multiple real AUs per PES, B frames, configuration changes, retries/ranges and discontinuities remain explicit gates.

Ordinary unbounded PES is accepted using complete resource evidence plus observable framing, not a fictional source delimiter. A captured length/hash cannot establish an upstream author's intended ending or prove entropy completeness. This is accurately disclosed.

## One nonblocking documentation precision note

`VALIDATION.md`, integrity-chain item 4, says “complete same-picture slice headers.” `CompatTsProfileProbe.readSlice` parses the supported picture-identity prefix through POC and checks that additional bits remain; it does not parse every remaining slice-header field or decode entropy. The source and broader documentation otherwise disclose the limitation correctly. Prefer wording such as “supported same-picture slice-header identity fields” to avoid implying full slice-header validation. This is a wording correction, not an identified bypass of the mandatory independent decode/publication gates.

## Remaining integration and device gates

1. Main wiring, both build variants' compile/test/lint, source-admission/dependency verification, and exception mapping that preserves actual-release failure priority.
2. Real complete `HlsMediaSource`/`HlsMediaChunk`/Transformer loading, shared timestamp-adjuster initialization, preparation, segment reuse, no retry/range fallback, final completion and cancellation/release behavior. Factory/chunk-interface tests are not that lifecycle.
3. Main raw/preflight/mux conservation and an **independent entire output MP4 sample scan through EOF**, comparing all count/PTS/sync/AV-offset/canonical-payload evidence. Annex-B versus AVCC normalization must change framing only.
4. Correct final-display-duration/endpoint rule. `lastPTS - firstPTS` excludes the last displayed sample; do not hide the difference by rewriting sample times.
5. Tail expectation/evidence versioning with genuine legacy-record compatibility and no invented legacy tail proof.
6. Entropy-valid owned media, independent decode of the last GOP from the preceding usable sync through the actual expected terminal PTS and **output EOS**, full audio decode/format checks, final AV offset, missing/truncated/dependency-corrupt tail negatives, timeout/no-progress/cancellation and blocked native release.

Until these pass, neither normal-HLS production acceptance nor full Android remux/decode success is established.
