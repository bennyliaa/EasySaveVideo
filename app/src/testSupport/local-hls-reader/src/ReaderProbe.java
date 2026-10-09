import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.extractor.ts.TsPayloadReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual pinned Media3 bytecode, original synthetic transport, minimal host Android API shims.
 * This does not execute Android, a codec, Transformer, or an MP4 muxer. */
public final class ReaderProbe {
  private static boolean validateProductionRaw;
  record Sample(long timeUs, int flags, int size, String sha256) {
    String normalized(long firstUs) {
      return (timeUs - firstUs) + ":" + flags + ":" + size + ":" + sha256;
    }
  }

  static final class Track implements TrackOutput {
    final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    final List<Sample> samples = new ArrayList<>();
    Format format;

    @Override public void format(Format next) {
      format = next;
      System.out.println("format=" + next.sampleMimeType + " init="
          + (next.initializationData.isEmpty() ? "empty" : HexFormat.of().formatHex(
              Arrays.copyOf(next.initializationData.get(0),
                  Math.min(8, next.initializationData.get(0).length)))));
    }
    @Override public int sampleData(DataReader input, int length, boolean allowEnd, int part)
        throws IOException {
      byte[] bytes = new byte[length];
      int count = input.read(bytes, 0, length);
      if (count > 0) pending.write(bytes, 0, count);
      return count;
    }
    @Override public void sampleData(ParsableByteArray input, int length, int part) {
      byte[] bytes = new byte[length];
      input.readBytes(bytes, 0, length);
      pending.write(bytes, 0, length);
    }
    @Override public void sampleMetadata(long timeUs, int flags, int size, int offset,
        TrackOutput.CryptoData crypto) {
      try {
        byte[] bytes = pending.toByteArray();
        int start = bytes.length - offset - size;
        byte[] sampleBytes = Arrays.copyOfRange(bytes, start, start + size);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(sampleBytes));
        Sample sample = new Sample(timeUs, flags, size, hash);
        samples.add(sample);
        pending.reset();
        pending.write(bytes, bytes.length - offset, offset);
        System.out.println("sample=" + sample);
      } catch (Exception error) { throw new RuntimeException(error); }
    }
    List<String> normalized() {
      long first = samples.get(0).timeUs();
      return samples.stream().map(sample -> sample.normalized(first)).toList();
    }
  }

  private static Map<Integer, Track> run(List<byte[]> chunks, boolean detect,
      boolean perSegment, boolean avOnly, boolean zeroBased) throws Exception {
    System.out.println("RUN detect=" + detect + " perSegment=" + perSegment
        + " avOnly=" + avOnly + " zeroBased=" + zeroBased);
    Map<Integer, Track> tracks = new LinkedHashMap<>();
    DefaultTsPayloadReaderFactory base = new DefaultTsPayloadReaderFactory(
        DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
            | (detect ? DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS : 0),
        Collections.emptyList());
    TsPayloadReader.Factory factory = avOnly ? new TsPayloadReader.Factory() {
      @Override public android.util.SparseArray<TsPayloadReader> createInitialPayloadReaders() {
        return base.createInitialPayloadReaders();
      }
      @Override public TsPayloadReader createPayloadReader(int type, TsPayloadReader.EsInfo info) {
        return type == TsExtractor.TS_STREAM_TYPE_H264
            || type == TsExtractor.TS_STREAM_TYPE_AAC_ADTS
            ? base.createPayloadReader(type, info) : null;
      }
    } : base;
    TsExtractor extractor = new TsExtractor(TsExtractor.MODE_HLS, 0,
        SubtitleParser.Factory.UNSUPPORTED,
        new TimestampAdjuster(zeroBased ? 0 : TimestampAdjuster.MODE_NO_OFFSET), factory,
        TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES);
    extractor.init(new ExtractorOutput() {
      @Override public TrackOutput track(int id, int type) {
        System.out.println("track=" + id + "/" + type);
        return tracks.computeIfAbsent(type, ignored -> new Track());
      }
      @Override public void endTracks() {}
      @Override public void seekMap(SeekMap map) {}
    });
    if (!perSegment) {
      ByteArrayOutputStream all = new ByteArrayOutputStream();
      for (byte[] bytes : chunks) all.write(bytes);
      chunks = List.of(all.toByteArray());
    }
    for (byte[] bytes : chunks) {
      ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
      DefaultExtractorInput input = new DefaultExtractorInput(
          (buffer, offset, length) -> stream.read(buffer, offset, length), 0, bytes.length);
      PositionHolder position = new PositionHolder();
      int result;
      while ((result = extractor.read(input, position)) != Extractor.RESULT_END_OF_INPUT) {
        require(result != Extractor.RESULT_SEEK, "Unexpected seek");
      }
    }
    extractor.release();
    System.out.println("counts=" + tracks.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue().samples.size()).toList());
    return tracks;
  }

  private static void compare(List<byte[]> chunks, int videoCount, int audioCount)
      throws Exception {
    if (validateProductionRaw) validateRaw(chunks, videoCount, audioCount);
    Map<Integer, Track> continuous = run(chunks, false, false, true, false);
    Map<Integer, Track> segmented = run(chunks, false, true, true, false);
    Map<Integer, Track> hlsLike = run(chunks, false, true, false, true);
    for (int type : List.of(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
      int expected = type == C.TRACK_TYPE_VIDEO ? videoCount : audioCount;
      if (expected == 0) { require(!continuous.containsKey(type), "Unexpected AV track"); continue; }
      Track original = continuous.get(type);
      require(original.samples.size() == expected, "Wrong sample count");
      require(original.samples.equals(segmented.get(type).samples), "Per-segment EOF changed samples");
      require(original.normalized().equals(hlsLike.get(type).normalized()), "Normalized HLS-like samples differ");
      require((original.samples.get(0).flags() & C.BUFFER_FLAG_KEY_FRAME) != 0, "Missing initial key flag");
    }
    byte[] sps = continuous.get(C.TRACK_TYPE_VIDEO).format.initializationData.get(0);
    require(sps[0] == 0 && sps[1] == 0 && sps[2] == 1 && (sps[3] & 31) == 7,
        "Expected actual reader three-byte SPS start code");
  }

  public static void main(String[] args) throws Exception {
    Log.setLogLevel(Log.LOG_LEVEL_OFF);
    validateProductionRaw = args.length > 0 && args[0].equals("--raw");
    SyntheticTs ts = new SyntheticTs(false);
    List<byte[]> two = List.of(ts.segment(90000, false, false, false),
        ts.segment(93000, true, true, false));
    System.out.println("TWO_SEGMENTS_FINAL_ONLY_AUD"); compare(two, 2, 0);
    System.out.println("DETECT_WITH_AUD_REPRO");
    Track split = run(two, true, false, true, false).get(C.TRACK_TYPE_VIDEO);
    require(split.samples.size() == 5 && split.samples.get(0).flags() == 0,
        "Expected duplicate AUD/VCL delimiting regression");
    ts = new SyntheticTs(false);
    System.out.println("NO_FINAL_AUD_LOSES_TAIL_REPRO");
    Track lost = run(List.of(ts.segment(90000, false, false, false),
        ts.segment(93000, false, false, false)), false, true, true, false).get(C.TRACK_TYPE_VIDEO);
    require(lost.samples.size() == 1, "Expected missing final sample without sentinel AUD");
    ts = new SyntheticTs(false);
    System.out.println("ONE_SEGMENT_FINAL_ONLY_AUD");
    compare(List.of(ts.segment(90000, true, true, false)), 1, 0);
    ts = new SyntheticTs(false);
    System.out.println("UNBOUNDED_PES_EOS");
    compare(List.of(ts.segment(90000, false, true, true),
        ts.segment(93000, true, true, true)), 2, 0);
    ts = new SyntheticTs(true);
    System.out.println("AVC_AND_AAC");
    compare(List.of(ts.segment(90000, false, false, false),
        ts.segment(93000, true, true, false)), 2, 2);
    System.out.println("PASS: actual pinned 1.11.1 extractor contracts, synthetic headers and host API shims only.");
    System.out.println("NOT TESTED: valid entropy decoding, Android media, HlsMediaChunk, Transformer, muxer, device lifecycle.");
  }
  private static void validateRaw(List<byte[]> chunks, int videos, int audios) throws Exception {
    List<java.io.File> files = new ArrayList<>();
    try {
      for (byte[] bytes : chunks) {
        java.nio.file.Path path = java.nio.file.Files.createTempFile("esv-pinned-raw-", ".ts");
        java.nio.file.Files.write(path, bytes); files.add(path.toFile());
      }
      Class<?> probe = Class.forName("com.easysavevideo.app.save.stream.StrictTsProfileProbe");
      Object instance = probe.getField("INSTANCE").get(null);
      java.lang.reflect.Method inspect = Arrays.stream(probe.getDeclaredMethods())
          .filter(method -> method.getName().equals("inspect$default")).findFirst().orElseThrow();
      Object evidence;
      try { evidence = inspect.invoke(null, instance, files, null, 2, null); }
      catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("Production raw scanner rejected exact positive fixture", error.getCause()); }
      Class<?> type = evidence.getClass();
      long rawVideos = (long) type.getMethod("getVideoAccessUnitCount").invoke(evidence);
      long rawAudios = (long) type.getMethod("getAudioFrameCount").invoke(evidence);
      require(rawVideos == videos && rawAudios == audios, "Production raw counts differ");
      System.out.println("production_raw_counts=" + rawVideos + "/" + rawAudios);
    } finally { for (java.io.File file : files) java.nio.file.Files.deleteIfExists(file.toPath()); }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
