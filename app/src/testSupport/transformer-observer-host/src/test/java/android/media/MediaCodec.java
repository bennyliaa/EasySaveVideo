package android.media;
/** Original host-only data carriers. No codec, decoding, rendering or native behavior is simulated. */
public final class MediaCodec {
  public static final int BUFFER_FLAG_KEY_FRAME = 1;
  public static final int BUFFER_FLAG_CODEC_CONFIG = 2;
  public static final int BUFFER_FLAG_END_OF_STREAM = 4;
  public static final class BufferInfo {
    public int offset, size, flags;
    public long presentationTimeUs;
    public void set(int offset, int size, long pts, int flags) {
      this.offset = offset; this.size = size; this.presentationTimeUs = pts; this.flags = flags;
    }
  }
  public static final class CryptoInfo {
    public int numSubSamples, mode;
    public int[] numBytesOfClearData, numBytesOfEncryptedData;
    public byte[] key, iv;
    public void setPattern(Pattern pattern) { }
    public static final class Pattern {
      public Pattern(int encryptedBlocks, int clearBlocks) { }
      public void set(int encryptedBlocks, int clearBlocks) { }
    }
  }
}
