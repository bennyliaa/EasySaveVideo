# Actual pinned MP4 mux/sample-conservation host regression

Run `app/src/testSupport/hls-mux-conservation/run.sh` from any directory. The standalone runner uses only the already-admitted local Kotlin compiler, JUnit, Android SDK compile jar, and vendor Media3 1.11.1 artifacts. It verifies the muxer/extractor AAR SHA-256 pins before running. No Gradle, downloads, dependency additions, APKs, devices, accounts or network access are involved. Output defaults to ignored `build/hls-mux-conservation-host/results.log`.

The suite contains 15 distinct JUnit cases.

## What actually runs

1. Original synthetic TS fixture bytes pass `CompatTsProfileProbe` and production `LocalAvcExtractor` through `LocalAssetReadSession` / `PermitDataSourceFactory` to consumed EOF, including guarded no-tail finalization.
2. Captured parser `Format`, complete payloads, PTS and sync flags go into the actual admitted `Mp4Muxer`, configured like the production remux (`setSampleBatchingEnabled(false)` and `setAttemptStreamableOutputEnabled(false)`). It creates a real local MP4 file and closes it.
3. The actual admitted `Mp4Extractor` parses that MP4, follows its requested byte seeks, and reads every sample until `RESULT_END_OF_INPUT`. No first/middle/near-end seek is substituted for the full scan. Temporary MP4 files are bounded and deleted after each test.
4. Both sequences feed production `StreamSampleAccumulator`, in their respective mux-input and extracted-output clock modes. Assertions check every payload byte, per-sample sync flag, count, sequence digest, final identity, preceding sync and endpoint. AAC bytes stay unchanged. AVC canonicalization changes only start-code versus four-byte-length framing.

The runner reuses the existing hls-eof-compat test-only synthetic TS helper and Android shims. Its only additional Android shim is `android.util.Pair`, a plain value holder used by Media3 parsing. Neither muxer, extractor, NAL converter, timestamps nor production evidence are mocked. These sources are outside app source sets and are not shipped.

## Cases and exact timing rules

- No-tail TS finalization retains all samples in actual MP4.
- Multislice pictures inherit SPS/PPS; an IDR followed by P pictures exercises real non-sync samples and a final GOP anchored at the first sync.
- Optional AAC-LC sample payloads/counts/flags are conserved alongside AVC.
- Positive/negative AV start offsets: source +1,555 us becomes +1,600 us, and source -1,556 us becomes -1,600 us. The pinned muxer's `Boxes.edts/elst` uses the 10 kHz movie timescale with HALF_UP and subtracts the common positive origin. Per-track relative proof cancels that edit-list offset.
- Video media time is 90 kHz. `Boxes.convertPresentationTimestampsToDurationsVu` applies HALF_UP to each absolute mux-input PTS before subtracting ticks. A +6 us origin shift near half-tick boundaries proves that simply truncating or quantizing relative input PTS is not equivalent. Extracted timestamps are truncated integer microseconds from track ticks; the output accumulator recovers those ticks after subtracting the observed first PTS.
- A positive source clock near the 33-bit MPEG-TS PTS limit, beyond 24 hours in absolute microseconds, retains its short relative sample sequence and normalizes output origin to zero. The 24-hour limit applies to relative span, not the absolute clock.
- Three/four-byte start codes, leading/trailing zero framing, one/two terminal zero payload bytes and emulation-prevention bytes are checked against actual pinned `AnnexBToAvccConverter.DEFAULT`. AVCC canonicalization is independently exercised on its length-prefixed result. Unchanged NAL bytes are retained.
- Actual MP4 variants deliberately lose or duplicate the final video sample, change its bytes/flag/PTS, or change the final audio payload. Their conservation proof differs despite otherwise plausible MP4 structure.

Pinned `TrackSampleTable` adds `C.BUFFER_FLAG_LAST_SAMPLE` to its final sample as extractor bookkeeping. The capture explicitly permits and removes only that annotation; it preserves the sync bit and rejects all other unexpected/encryption flags. This is not a relaxation of stored sample-sync conservation.

## Evidence limits

These original TS headers/slice prefixes and AAC payloads intentionally contain synthetic entropy, not decoder-valid media. This suite proves the pinned host parser→muxer→MP4 parser conservation path, with small bounded fixtures. It does not prove Android `HlsMediaSource`/`HlsMediaChunk`/Transformer lifecycle, framework `MediaExtractor` independence, hardware/software decoder acceptance, terminal decoded output, decode-to-output-EOS, Android native release fencing, device behavior, or production normal-HLS acceptance. Those Android acceptance gates remain NOT_RUN, and no device/APK permission is implied.

Local source evidence for the precise pinned behavior was read from already-admitted Media3 1.11.1 sources: muxer `Boxes.java` (`MVHD_TIMEBASE`, `edts/elst`, `vuFromUs`, duration conversion), `Track.java` (video 90 kHz/audio sample-rate timescale), `AnnexBUtils.java`/`AnnexBToAvccConverter.java`, extractor `TrackSampleTable.java` (`LAST_SAMPLE` annotation) and `Mp4Extractor.java` (full sample output). No library source was copied into production.
