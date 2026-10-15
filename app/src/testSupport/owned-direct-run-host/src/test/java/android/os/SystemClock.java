package android.os;
/** Deterministic-host clock availability only; no Android timing or native acceptance claim. */
public final class SystemClock {
  public static long elapsedRealtime() { return System.nanoTime() / 1000000L; }
  public static long uptimeMillis() { return elapsedRealtime(); }
  public static long elapsedRealtimeNanos() { return System.nanoTime(); }
}
