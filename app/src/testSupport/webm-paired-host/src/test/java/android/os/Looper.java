package android.os;
import java.util.concurrent.LinkedBlockingQueue;
/** Host-only controlled callback queue. This provides no Android/native execution evidence. */
public final class Looper {
  static final ThreadLocal<Looper> CURRENT = new ThreadLocal<>();
  static final Looper MAIN = new Looper();
  final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
  volatile boolean ended;
  Thread thread;
  public static Looper myLooper() { return CURRENT.get(); }
  public static Looper getMainLooper() { return MAIN; }
  public Thread getThread() { return thread; }
  public void quit() { ended = true; queue.offer(() -> {}); }
  public void quitSafely() { quit(); }
}
