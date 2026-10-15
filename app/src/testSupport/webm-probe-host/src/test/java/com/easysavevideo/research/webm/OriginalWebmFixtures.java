package com.easysavevideo.research.webm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Original, small EBML writer for extractor transport tests. No decoder-valid media claim. */
public final class OriginalWebmFixtures {
  private OriginalWebmFixtures() {}
  public static final int EBML = 0x1A45DFA3, SEGMENT = 0x18538067, INFO = 0x1549A966;
  public static final int TRACKS = 0x1654AE6B, TRACK_ENTRY = 0xAE, CLUSTER = 0x1F43B675;
  public static final int DOC_TYPE = 0x4282, CODEC_ID = 0x86, CODEC_PRIVATE = 0x63A2;
  public static final int SIMPLE_BLOCK = 0xA3, BLOCK = 0xA1, BLOCK_GROUP = 0xA0, VOID = 0xEC;
  // Deliberately opaque payloads: not decoded, no frame structure or audible content asserted.
  public static final byte[] VIDEO_PACKET = {0x12, 0x34, 0x56, 0x78};
  public static final byte[] AUDIO_PACKET = {(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};

  public static byte[] header() {
    return element(EBML, concat(uint(0x42F7, 1), ascii(DOC_TYPE, "webm"), uint(0x4285, 2)));
  }
  public static byte[] info() {
    return element(INFO, concat(uint(0x2AD7B1, 1_000_000), floating(0x4489, 100.0)));
  }
  /** Timeline declarations only; opaque packets do not prove any displayed rate or count. */
  public static byte[] declaredTimeline(long defaultDurationNs, double durationTicks, boolean audio) {
    byte[] video = videoTrack(1, "V_VP8", uint(0x23E383, defaultDurationNs));
    return fileWithBody(concat(element(INFO, concat(uint(0x2AD7B1, 1_000_000),
        floating(0x4489, durationTicks))), audio ? tracks(video, audioTrack(2)) : tracks(video),
        cluster(0, audio, 0), cluster(33, audio, 0), cluster(67, audio, 0)), 8);
  }
  public static byte[] videoTrack(int number) { return videoTrack(number, "V_VP8", new byte[0]); }
  public static byte[] videoTrack(int number, String codec, byte[] extra) {
    return element(TRACK_ENTRY, concat(uint(0xD7, number), uint(0x73C5, number), uint(0x83, 1),
        ascii(CODEC_ID, codec), element(0xE0, concat(uint(0xB0, 16), uint(0xBA, 16))), extra));
  }
  public static byte[] opusHead(int channels) {
    return ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put((byte) channels)
        .putShort((short) 312).putInt(48_000).putShort((short) 0).put((byte) 0).array();
  }
  public static byte[] audioTrack(int number) {
    return element(TRACK_ENTRY, concat(uint(0xD7, number), uint(0x73C5, number), uint(0x83, 2),
        ascii(CODEC_ID, "A_OPUS"), element(CODEC_PRIVATE, opusHead(2)), uint(0x56AA, 6_500_000),
        uint(0x56BB, 80_000_000), element(0xE1, concat(uint(0x9F, 2), floating(0xB5, 48_000)))));
  }
  public static byte[] unsupportedTrack(int number) {
    return element(TRACK_ENTRY, concat(uint(0xD7, number), uint(0x83, 17), ascii(CODEC_ID, "D_ESV_TEST")));
  }
  public static byte[] tracks(byte[]... entries) { return element(TRACKS, concat(entries)); }
  public static byte[] block(int trackNumber, int relativeMilliseconds, byte[] packet) {
    if (trackNumber < 1 || trackNumber > 126) throw new IllegalArgumentException("fixture track id");
    return concat(new byte[] {(byte) (0x80 | trackNumber), (byte) (relativeMilliseconds >> 8),
        (byte) relativeMilliseconds, (byte) 0x80}, packet);
  }
  public static byte[] simpleBlock(int track, int relativeMs, byte[] packet) {
    return element(SIMPLE_BLOCK, block(track, relativeMs, packet));
  }
  public static byte[] clusterBody(int timeMs, boolean audio) {
    return concat(uint(0xE7, timeMs), simpleBlock(1, 0, VIDEO_PACKET),
        audio ? simpleBlock(2, 0, AUDIO_PACKET) : new byte[0]);
  }
  public static byte[] cluster(int timeMs, boolean audio, int unknownWidth) {
    byte[] body = clusterBody(timeMs, audio);
    return unknownWidth == 0 ? element(CLUSTER, body) : unknownMaster(CLUSTER, unknownWidth, body);
  }
  public static byte[] file(boolean audio, int unknownSegmentWidth, int unknownClusterWidth, int clusters) {
    List<byte[]> parts = new ArrayList<>();
    parts.add(info()); parts.add(audio ? tracks(videoTrack(1), audioTrack(2)) : tracks(videoTrack(1)));
    for (int i = 0; i < clusters; i++) parts.add(cluster((i * 100 + 1) / 3, audio, unknownClusterWidth));
    return fileWithBody(concat(parts.toArray(new byte[0][])), unknownSegmentWidth);
  }
  public static byte[] fileWithBody(byte[] body, int unknownSegmentWidth) {
    return concat(header(), unknownSegmentWidth == 0 ? element(SEGMENT, body)
        : unknownMaster(SEGMENT, unknownSegmentWidth, body));
  }
  public static byte[] knownClusterSentinelOverrun() {
    // 40 7F denotes known size 127, while FF denotes unknown size with the same decoded number.
    return fileWithBody(concat(info(), tracks(videoTrack(1)),
        declared(CLUSTER, 127, 2, clusterBody(0, false))), 8);
  }
  public static byte[] knownClusterPastKnownParent() {
    byte[] prefix = concat(info(), tracks(videoTrack(1)));
    byte[] cluster = element(CLUSTER, clusterBody(0, false));
    // Entire Cluster is in the file, but the parent declared extent stops one byte too early.
    return concat(header(), declared(SEGMENT, prefix.length + cluster.length - 1, 2,
        concat(prefix, cluster)));
  }
  public static byte[] truncatedKnownChild() {
    byte[] declaredBlock = declared(SIMPLE_BLOCK, 20, 1, block(1, 0, VIDEO_PACKET));
    return fileWithBody(concat(info(), tracks(videoTrack(1)),
        unknownMaster(CLUSTER, 8, concat(uint(0xE7, 0), declaredBlock))), 8);
  }
  public static byte[] truncatedElementHeader() {
    return concat(file(false, 8, 0, 3), new byte[] {0x1F, 0x43});
  }
  public static byte[] missingTrackInventory() {
    return fileWithBody(concat(info(), cluster(0, false, 0)), 8);
  }
  public static byte[] unterminatedKnownTrackInventory() {
    byte[] entry = videoTrack(1);
    return fileWithBody(concat(info(), declared(TRACKS, entry.length + 7, 1, entry)), 8);
  }
  public static byte[] thirdTrack() {
    return fileWithBody(concat(info(), tracks(videoTrack(1), audioTrack(2), unsupportedTrack(3)),
        cluster(0, true, 0)), 8);
  }
  public static byte[] unsupportedSecondTrack() {
    return fileWithBody(concat(info(), tracks(videoTrack(1), unsupportedTrack(3)), cluster(0, false, 0)), 8);
  }
  public static byte[] duplicateTrackNumber() {
    return fileWithBody(concat(info(), tracks(videoTrack(1), audioTrack(1)), cluster(0, false, 0)), 8);
  }
  public static byte[] oversizedCodecIdentifier() {
    char[] chars = new char[129]; Arrays.fill(chars, 'A');
    return fileWithBody(concat(info(), tracks(videoTrack(1, new String(chars), new byte[0])),
        cluster(0, false, 0)), 8);
  }
  public static byte[] oversizedCodecPrivate() {
    return fileWithBody(concat(info(), tracks(videoTrack(1, "V_VP8", element(CODEC_PRIVATE,
        new byte[65_537]))), cluster(0, false, 0)), 8);
  }
  public static byte[] contentEncoding() {
    return fileWithBody(concat(info(), tracks(videoTrack(1, "V_VP8", element(0x6D80,
        element(0x6240, element(0x5035, new byte[0]))))), cluster(0, false, 0)), 8);
  }
  public static byte[] opusDiscardPadding() {
    byte[] audioGroup = element(BLOCK_GROUP, concat(element(BLOCK, block(2, 0, AUDIO_PACKET)),
        uint(0x75A2, 20_000_000)));
    return fileWithBody(concat(info(), tracks(videoTrack(1), audioTrack(2)), element(CLUSTER,
        concat(uint(0xE7, 0), simpleBlock(1, 0, VIDEO_PACKET), audioGroup))), 8);
  }
  public static byte[] shortUnknownClusterCrossing() {
    // Short unknown Cluster's numeric end is crossed by one actual Void, then a real block follows.
    byte[] body = concat(uint(0xE7, 0), element(VOID, new byte[128]), simpleBlock(1, 0, VIDEO_PACKET));
    return fileWithBody(concat(info(), tracks(videoTrack(1)), unknownMaster(CLUSTER, 1, body)), 8);
  }
  public static byte[] wrappedBinarySize() {
    // Diagnostic only: header says 2^32+4; pinned binary callback may expose only 4.
    byte[] wrapped = declared(CODEC_PRIVATE, (1L << 32) + 4, 5, new byte[] {1, 2, 3, 4});
    return fileWithBody(concat(info(), tracks(videoTrack(1, "V_VP8", wrapped)), cluster(0, false, 0)), 8);
  }
  public static byte[] wrappedIgnoredSize() {
    byte[] wrapped = declared(VOID, (1L << 32) + 4, 5, new byte[] {1, 2, 3, 4});
    return fileWithBody(concat(info(), tracks(videoTrack(1)), wrapped, cluster(0, false, 0)), 8);
  }

  public static byte[] element(int id, byte[] body) { return concat(id(id), knownSize(body.length, 0), body); }
  public static byte[] declared(int id, long size, int width, byte[] actualBody) {
    return concat(id(id), knownSize(size, width), actualBody);
  }
  public static byte[] unknownMaster(int id, int width, byte[] body) {
    return concat(id(id), unknownSize(width), body);
  }
  public static byte[] ascii(int id, String text) { return element(id, text.getBytes(StandardCharsets.US_ASCII)); }
  public static byte[] uint(int id, long value) {
    int width = 1; while (width < 8 && (value >>> (8 * width)) != 0) width++;
    byte[] body = new byte[width];
    for (int i = width - 1; i >= 0; i--) { body[i] = (byte) value; value >>>= 8; }
    return element(id, body);
  }
  public static byte[] floating(int id, double value) {
    return element(id, ByteBuffer.allocate(8).putDouble(value).array());
  }
  public static byte[] id(int id) {
    int width = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;
    byte[] out = new byte[width];
    for (int i = width - 1; i >= 0; i--) { out[i] = (byte) id; id >>>= 8; }
    return out;
  }
  public static byte[] knownSize(long size, int width) {
    if (size < 0 || size >= (1L << 56) - 1) throw new IllegalArgumentException("fixture size");
    if (width == 0) { width = 1; while (size >= (1L << (width * 7)) - 1) width++; }
    if (width < 1 || width > 8 || size >= (1L << (width * 7)) - 1)
      throw new IllegalArgumentException("fixture size width");
    byte[] out = new byte[width];
    for (int i = width - 1; i >= 0; i--) { out[i] = (byte) size; size >>>= 8; }
    out[0] |= (byte) (1 << (8 - width)); return out;
  }
  public static byte[] unknownSize(int width) {
    if (width < 1 || width > 8) throw new IllegalArgumentException("fixture unknown width");
    byte[] out = new byte[width]; Arrays.fill(out, (byte) 0xFF);
    out[0] = (byte) ((1 << (9 - width)) - 1); return out;
  }
  public static byte[] concat(byte[]... chunks) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] chunk : chunks) out.writeBytes(chunk);
    return out.toByteArray();
  }
}
