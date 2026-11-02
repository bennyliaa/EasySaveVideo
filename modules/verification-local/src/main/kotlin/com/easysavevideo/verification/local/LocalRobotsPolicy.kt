package com.easysavevideo.verification.local

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Original, deliberately finite robots grammar. Only a readable HTTP 200 text/plain document is
 * considered by the caller. No 404/410, redirect, unavailable file, or parse failure grants access.
 * Supports User-agent, Allow and Disallow, merged matching groups, longest rule, '*' and final '$'.
 * Sitemap is ignored and never fetched. Other unknown directives (including Crawl-delay), malformed
 * groups/escapes, and unmatched agents abstain.
 * This conservative subset is not a claim of complete REP or generic-site support.
 */
internal object LocalRobotsPolicy {
    const val USER_AGENT = "ESV-local-validation/1"
    const val MAX_BYTES = 32768
    enum class Decision { ALLOW, DENY, UNKNOWN }
    private data class Rule(val allow: Boolean, val path: String, val anchored: Boolean, val weight: Int)
    private data class Group(val agents: List<String>, val rules: List<Rule>)
    private class MatchBudget(var comparisons: Int = 262144)

    fun evaluate(bytes: ByteArray, encodedPathAndQuery: String): Decision {
        if (bytes.size !in 1..MAX_BYTES || encodedPathAndQuery.length !in 1..4096) return Decision.UNKNOWN
        val target = canonical(encodedPathAndQuery, pattern = false) ?: return Decision.UNKNOWN
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { return Decision.UNKNOWN }
        if (text.any { it.isISOControl() && it !in "\r\n\t" } || '\uFEFF' in text) return Decision.UNKNOWN
        val lines = text.split('\n')
        if (lines.size > 512) return Decision.UNKNOWN
        val groups = mutableListOf<Group>()
        var agents = mutableListOf<String>()
        var rules = mutableListOf<Rule>()
        var hasDirective = false
        var ruleCount = 0
        fun finish(): Boolean {
            if (agents.isEmpty() || !hasDirective) return false
            groups += Group(agents.toList(), rules.toList())
            agents = mutableListOf(); rules = mutableListOf(); hasDirective = false
            return groups.size <= 64
        }
        for (original in lines) {
            if (original.length > 2048) return Decision.UNKNOWN
            val line = original.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) return Decision.UNKNOWN
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            when (name) {
                "user-agent" -> {
                    if (hasDirective && !finish()) return Decision.UNKNOWN
                    if (value.length !in 1..128 || !value.matches(Regex("[A-Za-z0-9_.*/-]+")) ||
                        ('*' in value && value != "*") || agents.size >= 16) return Decision.UNKNOWN
                    agents += value.lowercase(Locale.ROOT)
                }
                "allow", "disallow" -> {
                    if (agents.isEmpty() || ++ruleCount > 256) return Decision.UNKNOWN
                    hasDirective = true
                    // An explicitly empty rule has no path match, as in `Disallow:`.
                    if (value.isEmpty()) continue
                    val path = canonical(value, pattern = true) ?: return Decision.UNKNOWN
                    val anchored = path.endsWith('$')
                    val pattern = if (anchored) path.dropLast(1) else path
                    rules += Rule(name == "allow", pattern, anchored,
                        octetWeight(pattern))
                }
                "sitemap" -> Unit // Metadata only: never fetch, terminate a group, or grant access.
                else -> return Decision.UNKNOWN
            }
        }
        if (!finish()) return Decision.UNKNOWN
        val product = USER_AGENT.substringBefore('/').lowercase(Locale.ROOT)
        val full = USER_AGENT.lowercase(Locale.ROOT)
        // Do not interpret an unfamiliar partial/versioned name as an unrelated bot and silently
        // fall back to '*'. Such matching conventions are outside this finite grammar.
        if (groups.any { group -> group.agents.any { agent ->
                agent !in setOf("*", product, full) && (full.contains(agent) || agent.startsWith("$product/"))
            } }) return Decision.UNKNOWN
        fun specificity(agent: String): Int = when (agent) { full -> full.length; product -> product.length; "*" -> 0; else -> -1 }
        val best = groups.maxOfOrNull { it.agents.maxOf(::specificity) } ?: return Decision.UNKNOWN
        if (best < 0) return Decision.UNKNOWN
        val matched = mutableListOf<Rule>()
        val budget = MatchBudget()
        for (rule in groups.filter { it.agents.maxOf(::specificity) == best }.flatMap { it.rules }) {
            when (matches(rule.path, target, rule.anchored, budget)) {
                true -> matched += rule
                false -> Unit
                null -> return Decision.UNKNOWN
            }
        }
        val longest = matched.maxOfOrNull { it.weight } ?: return Decision.ALLOW
        val winners = matched.filter { it.weight == longest }
        // Equivalent Allow/Disallow ties use Allow; distinct conflicting winning patterns abstain.
        for (denied in winners.filter { !it.allow }) {
            if (winners.none { it.allow && it.path == denied.path && it.anchored == denied.anchored })
                return if (winners.any { it.allow }) Decision.UNKNOWN else Decision.DENY
        }
        return Decision.ALLOW
    }

    /** ASCII URL octets; normalize escaped unreserved bytes and hex case, never escaped separators. */
    private fun canonical(value: String, pattern: Boolean): String? {
        if (!value.startsWith('/') || value.any { it.code !in 33..126 || it == '\\' || it == '#' }) return null
        if (pattern && '$' in value.dropLast(1)) return null
        val result = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '%') {
                if (!pattern && c == '*') result.append("%2A")
                else if (!pattern && c == '$') result.append("%24")
                else result.append(c)
                i++; continue
            }
            if (i + 2 >= value.length) return null
            val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
            if (byte <= 32 || byte == 127 || byte == 92) return null
            val decoded = byte.toChar()
            if (decoded in 'a'..'z' || decoded in 'A'..'Z' || decoded in '0'..'9' || decoded in "-._~") result.append(decoded)
            else result.append('%').append(value.substring(i + 1, i + 3).uppercase(Locale.ROOT))
            i += 3
        }
        return result.toString()
    }

    private fun octetWeight(pattern: String): Int {
        var index = 0; var weight = 0
        while (index < pattern.length) {
            when (pattern[index]) {
                '%' -> { weight++; index += 3 }
                '*' -> index++
                else -> { weight++; index++ }
            }
        }
        return weight
    }

    /** Bounded glob matcher with no regex execution or downloaded expressions. */
    private fun matches(pattern: String, target: String, anchored: Boolean, budget: MatchBudget): Boolean? {
        var p = 0; var t = 0; var star = -1; var retry = 0
        while (t < target.length) {
            if (--budget.comparisons < 0) return null
            if (p == pattern.length && !anchored) return true
            if (p < pattern.length && pattern[p] == '*') { star = p++; retry = t }
            else if (p < pattern.length && pattern[p] == target[t]) { p++; t++ }
            else if (star >= 0) { p = star + 1; t = ++retry }
            else return false
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }
}
