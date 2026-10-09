package com.easysavevideo.commons

/** Output is plain text only; never render in a WebView, HTML/Markdown parser, or clickable spans. */
internal object CommonsText {
    data class Sanitized(val text: String?, val changed: Boolean, val truncated: Boolean)
    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "copy" to "©", "reg" to "®", "ndash" to "–", "mdash" to "—")
    fun plain(input: String, maxLength: Int = 2048): Sanitized {
        if (input.length > 16_384) return Sanitized(null, true, true)
        val output = StringBuilder(); var i = 0; var suppressed: String? = null; var changed = false
        while (i < input.length) {
            if (input[i] == '<') {
                val end = tagEnd(input, i + 1)
                if (end == null) { changed = true; break } // malformed markup is discarded, never interpreted.
                val token = input.substring(i + 1, end).trim().lowercase()
                val name = token.removePrefix("/").takeWhile { it.isLetterOrDigit() }
                val closing = token.startsWith('/')
                if (suppressed == null && !closing && name in setOf("script", "style", "iframe", "object", "svg", "math")) suppressed = name
                else if (suppressed == name && closing) suppressed = null
                if (suppressed == null) output.append(' ')
                changed = true; i = end + 1; continue
            }
            if (suppressed != null) { i++; continue }
            if (input[i] == '&') {
                val end = (i + 2..minOf(input.length - 1, i + 16)).firstOrNull { input[it] == ';' }
                if (end != null) {
                    val entity = input.substring(i + 1, end)
                    val replacement = entities[entity] ?: if (entity.startsWith('#')) {
                        val number = if (entity.startsWith("#x", true)) entity.substring(2).toIntOrNull(16)
                            else entity.substring(1).toIntOrNull()
                        number?.takeIf { Character.isValidCodePoint(it) && it !in 0xd800..0xdfff  }
                            ?.let { String(Character.toChars(it)) }
                    } else null
                    if (replacement != null) {
                        replacement.forEach { output.append(if (disallowed(it)) ' ' else it) }
                        changed = true; i = end + 1; continue
                    }
                }
            }
            val c = input[i++]
            if (disallowed(c)) {
                output.append(' '); changed = true
            } else output.append(c)
        }
        // Decode once only. Literal '<' from an entity remains inert because callers use plain text.
        val text = output.toString().replace(Regex("\\s+"), " ").trim()
        val truncated = text.length > maxLength
        val end = if (truncated && text[maxLength - 1].isHighSurrogate()) maxLength - 1 else maxLength
        return Sanitized(text.take(end).takeIf { it.isNotBlank() }, changed, truncated)
    }
    private fun disallowed(c: Char): Boolean = c.isISOControl() || c in '\u202a'..'\u202e' ||
        c in '\u2066'..'\u2069' || c == '\u200e' || c == '\u200f' || c == '\u061c'
    private fun tagEnd(input: String, start: Int): Int? {
        var quote: Char? = null
        for (i in start until input.length) {
            val c = input[i]
            if (quote != null) { if (c == quote) quote = null }
            else if (c == '\'' || c == '"') quote = c else if (c == '>') return i
        }
        return null
    }
}
