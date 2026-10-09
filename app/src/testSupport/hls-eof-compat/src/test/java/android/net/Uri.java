package android.net;
/** Host-only shim, never shipped. URI semantics are tested separately in Android integration. */
public final class Uri {
  public static final Uri EMPTY = new Uri("");
  private final String text;
  private Uri(String text) { this.text = text; }
  public static Uri parse(String text) { return new Uri(text); }
  @Override public String toString() { return text; }
  public String getScheme() { int at = text.indexOf(':'); return at < 0 ? null : text.substring(0, at); }
  @Override public int hashCode() { return text.hashCode(); }
  @Override public boolean equals(Object other) { return other instanceof Uri && text.equals(((Uri) other).text); }
}
