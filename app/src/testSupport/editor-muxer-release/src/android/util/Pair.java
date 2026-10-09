package android.util;
/** Minimal host-only value pair. Never packaged into the application. */
public final class Pair<F, S> {
    public final F first;
    public final S second;
    public Pair(F first, S second) { this.first = first; this.second = second; }
    public static <F, S> Pair<F, S> create(F first, S second) { return new Pair<>(first, second); }
}
