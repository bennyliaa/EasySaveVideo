package com.easysavevideo.app.save.stream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Original synthetic transport/header fixture. Its slice/AAC entropy is NOT decoder-valid proof. */
final class SyntheticTs {
  private final boolean audio;
  private final Map<Integer, Integer> counters = new HashMap<>();
  private Long nextAudioPts;

  SyntheticTs(boolean audio) { this.audio = audio; }

  byte[] segment(long pts, boolean trailingAud, boolean end, boolean unbounded) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    write(out, packet(0, concat(new byte[]{0}, pat()), true));
    write(out, packet(4096, concat(new byte[]{0}, pmt()), true));
    byte[] accessUnit = concat(
        nal(9, new byte[]{(byte) 0xf0}), nal(7, sps()), nal(8, pps()), nal(5, slice()),
        trailingAud ? nal(9, new byte[]{(byte) 0xf0}) : new byte[0],
        end ? nal(10, new byte[0]) : new byte[0]);
    write(out, packets(256, pes(pts, accessUnit, unbounded, 0xe0)));
    if (audio) {
      long audioPts = nextAudioPts == null ? pts : nextAudioPts;
      nextAudioPts = audioPts + 1920; // 1024 samples at 48 kHz, in exact 90 kHz ticks.
      write(out, packets(257, pes(audioPts,
          HexFormat.of().parseHex("fff14c80013ffc2110"), false, 0xc0)));
    }
    return out.toByteArray();
  }

  byte[] pat() {
    return crc(HexFormat.of().parseHex("00b00d0001c100000001f000"));
  }

  byte[] pmt() {
    byte[] streams = HexFormat.of().parseHex(audio
        ? "1be100f0000fe101f000" : "1be100f000");
    return crc(concat(new byte[]{2, (byte) 0xb0, (byte) (13 + streams.length),
        0, 1, (byte) 0xc1, 0, 0, (byte) 0xe1, 0, (byte) 0xf0, 0}, streams));
  }

  static byte[] pes(long pts, byte[] payload, boolean unbounded, int streamId) {
    byte[] timing = new byte[]{
        (byte) (0x21 | ((pts >>> 30) & 7) << 1), (byte) (pts >>> 22),
        (byte) (1 | ((pts >>> 15) & 127) << 1), (byte) (pts >>> 7),
        (byte) (1 | (pts & 127) << 1)};
    byte[] body = concat(new byte[]{(byte) 0x80, (byte) 0x80, 5}, timing, payload);
    int length = unbounded ? 0 : body.length;
    return concat(new byte[]{0, 0, 1, (byte) streamId, (byte) (length >>> 8),
        (byte) length}, body);
  }

  byte[] packets(int pid, byte[] bytes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int at = 0; at < bytes.length; at += 184) {
      write(out, packet(pid, java.util.Arrays.copyOfRange(bytes, at,
          Math.min(at + 184, bytes.length)), at == 0));
    }
    return out.toByteArray();
  }

  byte[] packet(int pid, byte[] payload, boolean start) {
    if (payload.length < 1 || payload.length > 184) throw new IllegalArgumentException();
    int counter = counters.getOrDefault(pid, 0);
    counters.put(pid, (counter + 1) & 15);
    byte[] b = new byte[188];
    java.util.Arrays.fill(b, (byte) 0xff);
    b[0] = 0x47; b[1] = (byte) ((pid >>> 8) | (start ? 0x40 : 0));
    b[2] = (byte) pid; b[3] = (byte) ((payload.length == 184 ? 0x10 : 0x30) | counter);
    if (payload.length < 184) {
      b[4] = (byte) (183 - payload.length);
      if (payload.length < 183) b[5] = 0;
    }
    System.arraycopy(payload, 0, b, 188 - payload.length, payload.length);
    return b;
  }

  static byte[] sps() {
    Bits b = new Bits();
    b.bits(66, 8); b.bits(0, 8); b.bits(30, 8);
    b.ue(0); b.ue(0); b.ue(2); b.ue(1); b.bit(false); b.ue(0); b.ue(0);
    b.bit(true); b.bit(true); b.bit(false); b.bit(false);
    return b.finish();
  }

  static byte[] pps() {
    Bits b = new Bits();
    b.ue(0); b.ue(0); b.bit(false); b.bit(false); b.ue(0); b.ue(0); b.ue(0);
    b.bit(false); b.bits(0, 2); b.ue(0); b.ue(0); b.ue(0);
    b.bit(true); b.bit(false); b.bit(false);
    return b.finish();
  }

  static byte[] slice() {
    Bits b = new Bits();
    b.ue(0); b.ue(2); b.ue(0); b.bits(0, 4); b.ue(0); b.bits(0xaa, 8);
    return b.finish();
  }

  static byte[] nal(int type, byte[] rbsp) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    write(out, new byte[]{0, 0, 1, (byte) (type | (type == 5 || type == 7 || type == 8 ? 0x60 : 0))});
    int zeros = 0;
    for (byte b : rbsp) {
      int value = b & 255;
      if (zeros >= 2 && value <= 3) { out.write(3); zeros = 0; }
      out.write(value); zeros = value == 0 ? zeros + 1 : 0;
    }
    return out.toByteArray();
  }

  static byte[] crc(byte[] body) {
    int crc = -1;
    for (byte b : body) {
      crc ^= (b & 255) << 24;
      for (int i = 0; i < 8; i++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
    }
    return concat(body, new byte[]{(byte) (crc >>> 24), (byte) (crc >>> 16),
        (byte) (crc >>> 8), (byte) crc});
  }

  static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] b : parts) write(out, b);
    return out.toByteArray();
  }
  static void write(ByteArrayOutputStream out, byte[] b) { out.write(b, 0, b.length); }

  static final class Bits {
    final StringBuilder bits = new StringBuilder();
    void bit(boolean v) { bits.append(v ? '1' : '0'); }
    void bits(int value, int count) { for (int i = count - 1; i >= 0; i--) bit(((value >>> i) & 1) != 0); }
    void ue(int value) {
      String binary = Integer.toBinaryString(value + 1);
      for (int i = 1; i < binary.length(); i++) bit(false);
      bits.append(binary);
    }
    byte[] finish() {
      bit(true); while (bits.length() % 8 != 0) bit(false);
      byte[] out = new byte[bits.length() / 8];
      for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
      return out;
    }
  }
}
