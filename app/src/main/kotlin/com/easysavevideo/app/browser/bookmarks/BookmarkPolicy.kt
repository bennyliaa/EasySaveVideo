package com.easysavevideo.app.browser.bookmarks

import com.easysavevideo.app.browser.BrowserInputPolicy
import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.core.model.BrowserLoadState
import com.easysavevideo.core.model.BrowserTab
import java.net.URI
import java.util.Locale

/** Ordinary local functional data, never diagnostic context or network authority. */
data class BrowserBookmark(val id: String, val address: String, val title: String) {
    override fun toString() = "BrowserBookmark(redacted)"
}
data class BookmarkPageToken(
    val context: BrowserContextRef, val epoch: Long, val selectionRevision: Long,
    val navigationRevision: Long, val address: String, val title: String,
) {
    override fun toString() = "BookmarkPageToken(redacted)"
}

object BookmarkPolicy {
    const val MAX_ITEMS = 200
    const val MAX_ADDRESS_BYTES = 8192
    const val MAX_TITLE_CODEPOINTS = 160
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Deliberately preserves path, percent escapes, query, and fragment semantics. */
    fun canonicalAddress(input: String): String? = runCatching {
        require(input.length in 1..8192)
        require(input == input.trim() && input.none { unsafe(it) || it.isWhitespace() })
        val accepted = requireNotNull(BrowserInputPolicy.address(input))
        val uri = URI(accepted.value)
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        // DNS labels and IPv6 parsing remain the System browser's validated HTTP(S) boundary.
        require('%' !in host) // No scoped IPv6 zone identifiers.
        val port = uri.port.takeUnless { it == -1 || scheme == "https" && it == 443 || scheme == "http" && it == 80 }
        val path = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
        val result = "$scheme://$host${port?.let { ":$it" } ?: ""}$path" +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_ADDRESS_BYTES)
        requireNotNull(BrowserInputPolicy.address(result)).value
    }.getOrNull()

    fun safeTitle(input: String?, address: String): String {
        val safe = StringBuilder()
        var count = 0
        var space = false
        val source = input.orEmpty().take(4096)
        var i = 0
        while (i < source.length && count < MAX_TITLE_CODEPOINTS) {
            val codepoint = source.codePointAt(i)
            i += Character.charCount(codepoint)
            val type = Character.getType(codepoint)
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                type == Character.SURROGATE.toInt() || Character.isWhitespace(codepoint) || Character.isSpaceChar(codepoint)) {
                space = safe.isNotEmpty(); continue
            }
            if (space && count < MAX_TITLE_CODEPOINTS - 1) { safe.append(' '); count++ }
            space = false
            safe.appendCodePoint(codepoint); count++
        }
        return safe.toString().trim().ifEmpty { URI(requireNotNull(canonicalAddress(address))).host.take(MAX_TITLE_CODEPOINTS) }
    }

    fun valid(item: BrowserBookmark): Boolean = idPattern.matches(item.id) &&
        canonicalAddress(item.address) == item.address && safeTitle(item.title, item.address) == item.title

    fun validate(items: List<BrowserBookmark>) {
        require(items.size <= MAX_ITEMS)
        require(items.all(::valid))
        require(items.map { it.id }.distinct().size == items.size)
        require(items.map { it.address }.distinct().size == items.size)
    }

    fun capture(tab: BrowserTab?, actualAddress: String?, admittedAddress: String?, title: String?,
        selectionRevision: Long, navigationRevision: Long, active: Boolean): BookmarkPageToken? {
        if (!active || tab == null || tab.incognito || tab.loadState != BrowserLoadState.READY || tab.epoch <= 0 ||
            actualAddress == null || actualAddress != admittedAddress) return null
        val canonical = canonicalAddress(actualAddress) ?: return null
        return BookmarkPageToken(tab.context, tab.epoch, selectionRevision, navigationRevision,
            canonical, safeTitle(title, canonical))
    }

    /** Titles may change without navigation (including after the old load deadline).
     * Bind page authority to identity/address/generations, then capture the current safe title. */
    fun accept(expected: BookmarkPageToken?, current: BookmarkPageToken?): BookmarkPageToken? =
        current?.takeIf { expected != null && it.copy(title = expected.title) == expected }

    private fun unsafe(char: Char): Boolean = when (Character.getType(char)) {
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt() -> true
        else -> false
    }
}
