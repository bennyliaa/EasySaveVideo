package com.easysavevideo.searxng

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Original conservative policy interpreter informed by https://www.rfc-editor.org/rfc/rfc9309.
 * The caller first requires exact-origin HTTPS /robots.txt, status 200, UTF-8 text/plain,
 * identity encoding and <=32 KiB; redirects, missing policies and transport failures deny.
 * This reader performs no network I/O and never follows Sitemap or other supplied URLs.
 *
 * The RFC product token is EasySaveVideo (the HTTP identification string has /1.0 appended).
 * Only an applicable group with an explicit Allow/Disallow directive establishes a known
 * policy. Unsupported applicable directives and ambiguous conflicting equal-specificity
 * patterns deny. These intentional restrictions are stricter than general RFC permissiveness.
 * Robots permission is a prerequisite, never a substitute for source/user authorization.
 */
internal object SearxngRobotsPolicy {
    private const val MAX_BYTES = 32_768
    private const val MAX_LINES = 1024
    private const val MAX_LINE = 2048
    private const val MAX_GROUPS = 64
    private const val MAX_RULES = 256
    private const val MAX_MATCH_WORK = 1_000_000
    private const val PRODUCT = "easysavevideo"
    private val hex = "0123456789ABCDEF"
    private data class Rule(val pattern: String, val anchored: Boolean, val allow: Boolean, val specificity: Int)
    private class Group {
        val agents = mutableSetOf<String>()
        val rules = mutableListOf<Rule>()
        var hasDirective = false
        var unsupported = false
    }

    fun requireAllowed(bytes: ByteArray, endpoint: SearxngEndpoint, requestUri: URI, check: () -> Unit) {
        check()
        if (bytes.size > MAX_BYTES) denied()
        // The origin robots policy is evaluated against the exact configured path AND query.
        // Never reconstruct a root /search endpoint or borrow another deployment's permission.
        try { SearxngUrlPolicy.requireRequest(endpoint, requestUri, SearxngRequestKind.HTML_SEARCH) }
        catch (_: SearxngFailure) { denied() }
        var input = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { denied() }
        if (input.startsWith('\ufeff')) input = input.substring(1)
        for ((index, char) in input.withIndex()) {
            if (index % 256 == 0) check()
            if (char.isISOControl() && char !in "\r\n\t") denied()
        }
        val groups = mutableListOf<Group>()
        var current: Group? = null
        var rules = 0
        var lines = 0
        for (raw in input.lineSequence()) {
            check()
            if (++lines > MAX_LINES || raw.length > MAX_LINE) denied()
            val line = raw.substringBefore('#').trim(' ', '\t')
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon < 1) denied()
            val key = line.substring(0, colon).trim(' ', '\t').lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim(' ', '\t')
            when (key) {
                "user-agent" -> {
                    val agent = value.lowercase(Locale.ROOT)
                    if (agent.length !in 1..128 || agent != "*" && agent.any { it !in 'a'..'z' && it !in "_-" }) denied()
                    if (current == null || current.hasDirective || current.unsupported) {
                        current = Group()
                        groups += current
                        if (groups.size > MAX_GROUPS) denied()
                    }
                    current.agents += agent
                }
                "allow", "disallow" -> {
                    val group = current ?: denied()
                    group.hasDirective = true
                    if (++rules > MAX_RULES) denied()
                    // Both empty directives have no matching pattern, including Disallow:.
                    if (value.isEmpty()) continue
                    if (!value.startsWith('/') || value.any { it.isWhitespace() || Character.isSpaceChar(it) }) denied()
                    val anchored = value.endsWith('$')
                    val pattern = canonical(if (anchored) value.dropLast(1) else value, true, check)
                    if ('$' in pattern) denied()
                    var specificity = 0
                    var offset = 0
                    while (offset < pattern.length) {
                        if (pattern[offset] != '*') specificity++
                        offset += if (pattern[offset] == '%') 3 else 1
                    }
                    group.rules += Rule(pattern, anchored, key == "allow", specificity)
                }
                "sitemap" -> Unit // Does not end the current group or authorize fetching anything.
                else -> { (current ?: denied()).unsupported = true }
            }
        }
        val specific = groups.filter { PRODUCT in it.agents }
        val applicable = if (specific.isNotEmpty()) specific else groups.filter { "*" in it.agents }
        if (applicable.isEmpty() || applicable.none { it.hasDirective } || applicable.any { it.unsupported }) denied()
        val target = canonical(requestUri.rawPath + "?" + requestUri.rawQuery, false, check)
        var work = 0
        fun step() {
            if (++work > MAX_MATCH_WORK) denied()
            if (work % 256 == 0) check()
        }
        var best = -1
        val winners = mutableListOf<Rule>()
        for (group in applicable) for (rule in group.rules) {
            check()
            if (matches(rule, target, ::step)) {
                if (rule.specificity > best) { best = rule.specificity; winners.clear() }
                if (rule.specificity == best) winners += rule
            }
        }
        // An applicable known policy with no matching restrictions permits this exact request.
        for (restriction in winners.filter { !it.allow }) {
            if (winners.none { it.allow && it.pattern == restriction.pattern && it.anchored == restriction.anchored }) denied()
        }
        check()
    }

    /** Normalize UTF-8 octets, unreserved escapes and literal wildcard symbols without decoding reserved separators. */
    private fun canonical(value: String, pattern: Boolean, check: () -> Unit): String {
        if (!isWellFormedUnicode(value)) denied()
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        var index = 0
        while (index < bytes.size) {
            if (index % 256 == 0) check()
            val byte = bytes[index].toInt() and 255
            if (byte == '%'.code) {
                if (index + 2 >= bytes.size) denied()
                val high = (bytes[index + 1].toInt() and 255).toChar().digitToIntOrNull(16) ?: denied()
                val low = (bytes[index + 2].toInt() and 255).toChar().digitToIntOrNull(16) ?: denied()
                val decoded = high * 16 + low
                if (unreserved(decoded)) out.append(decoded.toChar())
                else out.append('%').append(hex[high]).append(hex[low])
                index += 3
            } else {
                if (byte < 0x21 || byte == 0x7f) denied()
                if (byte >= 0x80 || !pattern && (byte == '*'.code || byte == '$'.code))
                    out.append('%').append(hex[byte ushr 4]).append(hex[byte and 15])
                else out.append(byte.toChar())
                index++
            }
        }
        check()
        return out.toString()
    }

    private fun unreserved(value: Int) = value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code ||
        value in '0'.code..'9'.code || value.toChar() in "-._~"

    /** Glob matching has a global comparison budget; adversarial backtracking cannot run unbounded. */
    private fun matches(rule: Rule, target: String, step: () -> Unit): Boolean {
        var pattern = 0
        var offset = 0
        var star = -1
        var retry = 0
        while (offset < target.length) {
            step()
            if (pattern == rule.pattern.length && !rule.anchored) return true
            if (pattern < rule.pattern.length && rule.pattern[pattern] == '*') {
                star = pattern++
                retry = offset
            } else if (pattern < rule.pattern.length && rule.pattern[pattern] == target[offset]) {
                pattern++; offset++
            } else if (star >= 0) {
                pattern = star + 1
                offset = ++retry
            } else return false
        }
        while (pattern < rule.pattern.length && rule.pattern[pattern] == '*') { step(); pattern++ }
        return pattern == rule.pattern.length
    }

    private fun denied(): Nothing = throw SearxngFailure(SearxngError.SERVICE_DENIED)
}
