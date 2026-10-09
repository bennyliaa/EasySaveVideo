package android.text;
/** Minimal host-only string predicates used by the admitted value classes. */
public final class TextUtils {
    public static boolean isEmpty(CharSequence value) { return value == null || value.length() == 0; }
    public static boolean equals(CharSequence a, CharSequence b) {
        return a == null ? b == null : b != null && a.toString().contentEquals(b);
    }
}
