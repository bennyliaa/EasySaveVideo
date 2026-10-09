package android.util;
/** Test-only value holder used by admitted Media3 parsers; no media behavior is shimmed. */
public class Pair<F, S> {
  public final F first;
  public final S second;
  public Pair(F first, S second) { this.first = first; this.second = second; }
  public static <F,S> Pair<F,S> create(F first, S second) { return new Pair<>(first, second); }
  @Override public boolean equals(Object other) {
    if (!(other instanceof Pair)) return false;
    Pair<?,?> p = (Pair<?,?>) other;
    return java.util.Objects.equals(first, p.first) && java.util.Objects.equals(second, p.second);
  }
  @Override public int hashCode() { return java.util.Objects.hashCode(first) ^ java.util.Objects.hashCode(second); }
}
