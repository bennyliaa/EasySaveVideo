package android.os;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
/** Daemon host dispatcher only, never a replacement for native release/drain acceptance. */
public class HandlerThread extends Thread {
  private static final CopyOnWriteArrayList<HandlerThread> ALL = new CopyOnWriteArrayList<>();
  private final Looper looper = new Looper();
  public HandlerThread(String name) { super(name); setDaemon(true); looper.thread = this; ALL.add(this); }
  public HandlerThread(String name, int priority) { this(name); }
  @Override public void run() {
    Looper.CURRENT.set(looper);
    try { while (!looper.ended) looper.queue.take().run(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    finally { Looper.CURRENT.remove(); }
  }
  public Looper getLooper() { return looper; }
  public int getThreadId() { return (int) getId(); }
  public boolean quit() { looper.quit(); return true; }
  public boolean quitSafely() { return quit(); }
  public static boolean drainAll(long milliseconds) throws InterruptedException {
    for (HandlerThread thread : ALL) {
      if (thread.looper.ended) continue;
      CountDownLatch drained = new CountDownLatch(1);
      thread.looper.queue.offer(drained::countDown);
      if (!drained.await(milliseconds, TimeUnit.MILLISECONDS)) return false;
    }
    return true;
  }
}
