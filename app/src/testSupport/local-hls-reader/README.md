# Pinned Media3 reader contract regression

These handwritten host-only checks invoke actual admitted Media3 1.11.1 binary `TsExtractor`, `PesReader`, `H264Reader`, and `AdtsReader`. Tiny original Android API shims implement only collections, build identifiers, test-harness detection and text helpers required by this parser path. They must never enter production sources or an Android artifact. No new dependency or network fetch is required. `SyntheticTs` is original test data, not codec-valid decoded media.

Run with the approved SDK `android.jar` and JDK paths:

    ANDROID_JAR=/approved/platform/android.jar \
    JAVA_BIN=/approved/jdk/bin/java JAVAC_BIN=/approved/jdk/bin/javac \
      ./run.sh /path/to/EasySaveVideo /tmp/esv-pinned-reader-result

Assertions cover:
- Real AVC initialization data starts with the three-byte `00 00 01` prefix.
- With mandatory AUD, enabling slice-based `FLAG_DETECT_ACCESS_UNITS` produces five pseudo/real samples from two access units, including an initial non-key sample. Disabling it yields two key samples.
- One-segment, two-segment, bounded PES, unbounded PES with EOS, and AVC/AAC sample counts.
- Per-segment EOF with the same extractor/clock versus contiguous input produces identical PTS, flags, sizes and payload SHA-256.
- An AV-only reader factory versus normal reader factory, with zero-based timestamp adjustment, produces identical per-track normalized PTS, flags, sizes and payload SHA-256. This is a parser contract check, not execution of `HlsMediaChunk` or `Transformer`.
- A final-only extra AUD followed by EOS flushes the last real AVC sample. Removing it reproducibly loses the last sample on these PTS-bearing MODE_HLS inputs. The test expects this pinned behavior, so a future library change requires explicit re-review.

## Important correction to static review

A superficial reading of `TsExtractor` EOF forwarding suggested that its synthesized PUSI would flush AVC automatically. Actual 1.11.1 bytecode disproves that for these PTS-bearing HLS packets. `PesReader.canConsumeSynthesizedEmptyPusi(true)` calls `parseHeader()` on `pesScratch`; the buffer was overwritten by the PES timestamp extension and no longer starts with `00 00 01`. It returns false. The earlier proposed removal of the final AUD was withdrawn before finalization.

## Not acceptance evidence

These checks do not run Android, MediaCodec decoding, HlsMediaChunk, Transformer, MP4 muxing, cancellation, native release, device/emulator playback, real HLS sources, or network/storage integration. They do not establish entropy-code validity. One test has one video access unit to isolate EOF behavior; the full engine may reject it under its separate minimum-sample/timeline policy.

## Production raw-profile agreement

To also assert production `StrictTsProfileProbe` counts on every positive fixture, set `ESV_RAW_CLASSPATH` to the already-compiled production scanner classes plus the admitted Kotlin standard library. The script then passes `--raw`; it uses reflection only to avoid compiling a second copy of production code. In the reviewed workspace this was:

    ESV_RAW_CLASSPATH=/tmp/esv-local-hls-check/classes:/approved/kotlin-stdlib-2.2.10.jar

The completed run asserted raw counts `2/0`, `1/0`, `2/0`, and `2/2`, matching actual pinned extractor counts for the same bytes. Audio uses PTS 90000 then 91920 for 48 kHz/1024-sample frames; video uses 90000 then 93000. The production scanner's file convenience entry delegates to the same `inspectSources` implementation. It does not exercise production path/FD capability guards.
