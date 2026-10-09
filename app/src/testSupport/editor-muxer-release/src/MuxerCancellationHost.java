package com.easysavevideo.app.editor.engine;
import androidx.media3.common.Format;
import androidx.media3.muxer.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import kotlin.Unit;
public final class MuxerCancellationHost {
  static final class Out implements SeekableMuxerOutput {
    final FileOutputStream stream;
    final EditorOutputIoPolicy policy = new EditorOutputIoPolicy(1024*1024);
    boolean cancelled, closed; int sampleWrites, releaseWrites;
    Out(File file) throws Exception { stream=new FileOutputStream(file); }
    public int write(ByteBuffer bytes) throws IOException {
      policy.activeCheckpoint(() -> { if(cancelled) throw new IllegalStateException("SYNTHETIC_CANCEL"); return Unit.INSTANCE; });
      policy.range(stream.getChannel().position(), bytes.remaining());
      if(cancelled) releaseWrites++; else sampleWrites++;
      int count=stream.getChannel().write(bytes);
      if(count==0 && bytes.hasRemaining()) throw new IOException("ZERO_PROGRESS"); return count;
    }
    public long getPosition() throws IOException {return stream.getChannel().position();}
    public void setPosition(long pos) throws IOException {policy.range(pos,0);stream.getChannel().position(pos);}
    public long getSize() throws IOException {return stream.getChannel().size();}
    public void truncate(long size) throws IOException {policy.range(size,0);stream.getChannel().truncate(size);}
    public boolean isOpen() {return stream.getChannel().isOpen();}
    public void close() throws IOException {stream.close(); if(stream.getFD().valid())throw new IOException("NOT_CLOSED"); policy.confirmedClosed();closed=true;}
  }
  public static void main(String[] args) throws Exception {
    File file=File.createTempFile("editor-cancel-synthetic-", ".tmp");
    Out out=new Out(file);
    Mp4Muxer muxer=new Mp4Muxer.Builder(out).setSampleBatchingEnabled(false).setAttemptStreamableOutputEnabled(false).build();
    Format aac=new Format.Builder().setSampleMimeType("audio/mp4a-latm").setSampleRate(48000).setChannelCount(2)
      .setInitializationData(List.of(new byte[]{0x11,(byte)0x90})).build();
    int track=muxer.addTrack(aac);
    for(int i=0;i<3;i++) muxer.writeSampleData(track,ByteBuffer.wrap(new byte[]{1,2,3,4}),new BufferInfo(i*21333L,4,0));
    if(out.sampleWrites==0) throw new AssertionError("No sample IO before cancellation");
    out.cancelled=true;out.policy.beginFinalization();muxer.close();
    if(!out.closed || out.isOpen() || out.releaseWrites==0)throw new AssertionError("Cleanup not demonstrated");
    System.out.println("PASS pinned Media3 1.11.1 Mp4Muxer: three synthetic samples written, cancellation set, bounded finalization wrote tables, FD closed. Android/native codec NOT_RUN.");
    if(!file.delete())throw new AssertionError("Synthetic fixture cleanup failed");
  }
}
