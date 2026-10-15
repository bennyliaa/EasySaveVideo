package android.os;
/** Host-only dispatch support for the actual session's null-native cancellation callback. */
public class Handler {
  public interface Callback { boolean handleMessage(Message message); }
  private final Looper looper;
  public Handler() { this(Looper.getMainLooper()); }
  public Handler(Looper looper) { this.looper = looper; }
  public Handler(Looper looper, Callback callback) { this(looper); }
  public static Handler createAsync(Looper looper) { return new Handler(looper); }
  public Looper getLooper() { return looper; }
  public boolean post(Runnable task) { return !looper.ended && looper.queue.offer(task); }
  public boolean postDelayed(Runnable task, long delay) { return post(task); }
  public boolean postAtFrontOfQueue(Runnable task) { return post(task); }
  public void removeCallbacks(Runnable task) { looper.queue.remove(task); }
  public void removeCallbacksAndMessages(Object token) { looper.queue.clear(); }
}
