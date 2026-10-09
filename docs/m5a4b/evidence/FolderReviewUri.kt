package android.net
/** Test double only. URI lexical parsing is not Android framework acceptance. */
data class Uri constructor(private val text: String) {
    private val parsed get() = java.net.URI(text)
    val scheme: String? get() = parsed.scheme
    val authority: String? get() = parsed.rawAuthority
    val query: String? get() = parsed.rawQuery
    val fragment: String? get() = parsed.rawFragment
    val path: String get() = parsed.rawPath ?: ""
    override fun toString() = text
    companion object { fun parse(text: String) = Uri(text) }
}
