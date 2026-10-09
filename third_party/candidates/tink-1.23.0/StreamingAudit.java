// Original isolated audit harness. Synthetic public test patterns and ephemeral in-memory keys only.
import com.google.crypto.tink.*;
import com.google.crypto.tink.streamingaead.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public class StreamingAudit {
  static final int S = 1 << 20;
  static final byte[] AAD = "ESV audit synthetic object generation 1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  static int passed;
  static void check(boolean b, String label) { if (!b) throw new AssertionError(label); passed++; System.out.println("PASS " + label); }
  static class MemoryChannel implements SeekableByteChannel {
    final byte[] data; long pos; boolean open = true;
    MemoryChannel(byte[] data) { this.data=data; }
    public int read(ByteBuffer dst) throws IOException { if (!open) throw new ClosedChannelException(); if(pos>=data.length)return -1; int n=(int)Math.min(dst.remaining(),data.length-pos); dst.put(data,(int)pos,n);pos+=n;return n; }
    public int write(ByteBuffer b) {throw new UnsupportedOperationException();}
    public long position(){return pos;}
    public SeekableByteChannel position(long p){if(p<0)throw new IllegalArgumentException();pos=p;return this;}
    public long size(){return data.length;}
    public SeekableByteChannel truncate(long n){throw new UnsupportedOperationException();}
    public boolean isOpen(){return open;}
    public void close(){open=false;}
  }
  // Receives the whole actual 2 GiB encryption but retains selected ciphertext segments only.
  // This tests long positions with no multi-gigabyte file or plaintext allocation.
  static class SparseCiphertext implements WritableByteChannel {
    final Map<Long,byte[]> segments=new TreeMap<>(); long size; boolean open=true;
    SparseCiphertext(){for(long i:new long[]{0,1,2,1023,1024,1025,2047,2048})segments.put(i,new byte[S]);}
    public int write(ByteBuffer b){int all=b.remaining(); while(b.hasRemaining()){long k=size/S;int offset=(int)(size%S);int n=Math.min(b.remaining(),S-offset);byte[] saved=segments.get(k);if(saved==null)b.position(b.position()+n);else b.get(saved,offset,n);size+=n;}return all;}
    public boolean isOpen(){return open;}
    public void close(){open=false;}
    SeekableByteChannel reader(){return new SeekableByteChannel(){long p;boolean o=true;
      public int read(ByteBuffer b)throws IOException{if(!o)throw new ClosedChannelException();if(p>=size)return -1;int total=0;while(b.hasRemaining()&&p<size){byte[] a=segments.get(p/S);if(a==null)throw new IOException("Audit requested an unsaved segment");int n=(int)Math.min(Math.min(b.remaining(),S-p%S),size-p);b.put(a,(int)(p%S),n);p+=n;total+=n;}return total;}
      public int write(ByteBuffer b){throw new UnsupportedOperationException();}public long position(){return p;}public SeekableByteChannel position(long v){p=v;return this;}public long size(){return size;}public SeekableByteChannel truncate(long n){throw new UnsupportedOperationException();}public boolean isOpen(){return o;}public void close(){o=false;}
    };}
  }
  static byte pattern(long i){return (byte)((i*31+7)&255);}
  static byte[] plaintext(int n){byte[] x=new byte[n];for(int i=0;i<n;i++)x[i]=pattern(i);return x;}
  static byte[] encrypt(StreamingAead s,byte[] p)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(OutputStream enc=s.newEncryptingStream(out,AAD)){enc.write(p);}return out.toByteArray();}
  static SeekableByteChannel reader(StreamingAead s,byte[] c,byte[] aad)throws Exception{return s.newSeekableDecryptingChannel(new MemoryChannel(c),aad);}
  static void readExact(SeekableByteChannel ch,long pos,int n)throws Exception{ch.position(pos);ByteBuffer b=ByteBuffer.allocate(n);int attempts=0;while(b.hasRemaining()){int r=ch.read(b);if(r<0||++attempts>5)throw new AssertionError("short/no-progress read");}for(int i=0;i<n;i++)if(b.array()[i]!=pattern(pos+i))throw new AssertionError("pattern mismatch at "+(pos+i));}
  static void mustReject(StreamingAead s,byte[] c,byte[] aad,long pos,String label)throws Exception{ByteBuffer b=ByteBuffer.allocate(32);Arrays.fill(b.array(),(byte)0x55);boolean rejected=false;try(SeekableByteChannel ch=reader(s,c,aad)){ch.position(pos);ch.read(b);}catch(IOException|GeneralSecurityException expected){rejected=true;}check(rejected&&b.position()==0,label+" before target plaintext");}
  static Aead wrappingAead()throws Exception{KeyGenerator g=KeyGenerator.getInstance("AES");g.init(256);SecretKey k=g.generateKey();return new Aead(){public byte[] encrypt(byte[] p,byte[] a)throws GeneralSecurityException{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,k);c.updateAAD(a);byte[] enc=c.doFinal(p),out=new byte[12+enc.length];System.arraycopy(c.getIV(),0,out,0,12);System.arraycopy(enc,0,out,12,enc.length);return out;}public byte[] decrypt(byte[] p,byte[] a)throws GeneralSecurityException{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,k,new GCMParameterSpec(128,p,0,12));c.updateAAD(a);return c.doFinal(p,12,p.length-12);}};}
  public static void main(String[] args)throws Exception{
    StreamingAeadConfig.register();
    KeysetHandle key=KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB);
    StreamingAead s=key.getPrimitive(RegistryConfiguration.get(),StreamingAead.class);
    for(int n:new int[]{1,S-56,S-55,S-16,S,3*S+137}){
      byte[] c=encrypt(s,plaintext(n));
      try(SeekableByteChannel ch=reader(s,c,AAD)){
        readExact(ch,n-1,1);check(ch.read(ByteBuffer.allocate(1))==-1,"authenticated sequential EOF size="+n);
        for(long pos:new long[]{0,Math.max(0,n/2-16),Math.max(0,n-128)})readExact(ch,pos,(int)Math.min(128,n-pos));
        check(ch.size()==n,"random access and size after final segment size="+n);
      }
    }
    byte[] valid=encrypt(s,plaintext(3*S+137));
    byte[] bad=valid.clone();bad[S+100]^=1;mustReject(s,bad,AAD,S,"tampered middle segment");
    byte[] wrong=AAD.clone();wrong[0]^=1;mustReject(s,valid,wrong,0,"wrong AAD");
    byte[] header=valid.clone();header[10]^=1;mustReject(s,header,AAD,0,"tampered salt/header");
    byte[] truncated=Arrays.copyOf(valid,valid.length-1);mustReject(s,truncated,AAD,3*S,"truncated final segment");
    byte[] appended=Arrays.copyOf(valid,valid.length+1);mustReject(s,appended,AAD,3*S,"appended trailing byte");
    byte[] reorder=valid.clone();System.arraycopy(valid,2*S,reorder,S,S);System.arraycopy(valid,S,reorder,2*S,S);mustReject(s,reorder,AAD,S,"reordered segments");
    byte[] duplicate=valid.clone();System.arraycopy(valid,S,duplicate,2*S,S);mustReject(s,duplicate,AAD,2*S,"duplicate segment in different index");
    byte[] second=encrypt(s,plaintext(3*S+137)),swap=valid.clone();System.arraycopy(second,S,swap,S,S);mustReject(s,swap,AAD,S,"segment from distinct ciphertext");
    try(SeekableByteChannel ch=reader(s,bad,AAD)){readExact(ch,0,32);check(true,"untouched segment readable before remote corruption is visited (documented partial verification)");}
    ByteBuffer multi=ByteBuffer.allocate(S+64);boolean threw=false;try(SeekableByteChannel ch=reader(s,bad,AAD)){ch.read(multi);}catch(IOException expected){threw=true;}
    boolean prefix=true;for(int i=0;i<multi.position();i++)prefix&=multi.array()[i]==pattern(i);
    check(threw&&multi.position()==S-56&&prefix,"cross-segment exception leaves only prior authenticated bytes in caller buffer");
    try(SeekableByteChannel ch=reader(s,valid,AAD)){ch.position(3L*S+137);int r=ch.read(ByteBuffer.allocate(1));check(r==0,"KNOWN EDGE: seek directly to EOF returns zero, not authenticated EOF");}
    byte[] empty=encrypt(s,new byte[0]);try(SeekableByteChannel ch=reader(s,empty,AAD)){check(ch.read(ByteBuffer.allocate(1))==0,"KNOWN EDGE: empty seekable plaintext returns zero");}
    MemoryChannel underlying=new MemoryChannel(valid);SeekableByteChannel closed=s.newSeekableDecryptingChannel(underlying,AAD);readExact(closed,0,8);closed.close();int readAfterClose=closed.read(ByteBuffer.allocate(8));check(!underlying.isOpen()&&readAfterClose==8,"KNOWN EDGE: public wrapper close leaves cached plaintext readable; outer lease MUST revoke");
    Aead wrap=wrappingAead();byte[] envelope=TinkProtoKeysetFormat.serializeEncryptedKeyset(key,wrap,AAD,RegistryConfiguration.get());KeysetHandle restored=TinkProtoKeysetFormat.parseEncryptedKeyset(envelope,wrap,AAD,RegistryConfiguration.get());try(SeekableByteChannel ch=reader(restored.getPrimitive(RegistryConfiguration.get(),StreamingAead.class),valid,AAD)){readExact(ch,S,64);check(true,"binary encrypted keyset roundtrip with explicit AAD");}
    boolean badWrap=false;try{TinkProtoKeysetFormat.parseEncryptedKeyset(envelope,wrap,wrong,RegistryConfiguration.get());}catch(GeneralSecurityException e){badWrap=true;}check(badWrap,"wrapped keyset wrong AAD rejected");Arrays.fill(envelope,(byte)0);
    long total=1L<<31;SparseCiphertext sparse=new SparseCiphertext();byte[] block=plaintext(64*1024);long started=System.nanoTime();try(WritableByteChannel enc=s.newEncryptingChannel(sparse,AAD)){for(long p=0;p<total;p+=block.length){ByteBuffer b=ByteBuffer.wrap(block);while(b.hasRemaining())enc.write(b);}}
    check(sparse.size==2147516472L,"2 GiB synthetic plaintext ciphertext extent uses long arithmetic");
    try(SeekableByteChannel ch=s.newSeekableDecryptingChannel(sparse.reader(),AAD)){for(long p:new long[]{0,S-70,1024L*S-80,total-128})readExact(ch,p,128);check(ch.size()==total,"2 GiB random seeks including last byte verified");check(ch.read(ByteBuffer.allocate(1))==-1,"2 GiB authenticated final EOF");}
    System.out.println("2GiB streaming encrypt and random-read elapsed_ms="+(System.nanoTime()-started)/1000000);
    System.out.println("TOTAL PASS="+passed+"; no Android/Keystore/device/Gradle test performed");
  }
}
