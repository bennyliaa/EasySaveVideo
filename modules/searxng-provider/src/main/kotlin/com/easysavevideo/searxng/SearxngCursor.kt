package com.easysavevideo.searxng

import java.util.Base64

/** Local progress only; never follows server-provided continuation URLs or trusts total counts. */
internal object SearxngCursor {
    const val TTL_MILLIS = 5 * 60_000L
    fun key(endpoint: SearxngEndpoint, query: String, language: String, profileKey: String, limits: SearxngLimits): String =
        sha256(listOf(endpoint.id, endpoint.version, query, language, profileKey, "safesearch=0", limits.maxPages.toString(),
            limits.maxCandidates.toString()).joinToString("") { "${it.length}:$it" })

    fun encode(page: Int, key: String, expires: Long): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(StrictJson.write(Json.Obj(linkedMapOf("v" to Json.Num("1"), "q" to Json.Str(key),
            "page" to Json.Num(page.toString()), "expires" to Json.Num(expires.toString())))).toByteArray(Charsets.UTF_8))

    fun decode(token: String, key: String, now: Long, maxPages: Int, check: () -> Unit): Int {
        check()
        try {
            if (token.length !in 1..1024 || !token.matches(Regex("[A-Za-z0-9_-]+"))) fail()
            val value = StrictJson.read(Base64.getUrlDecoder().decode(token), check).objectValue()
            val page = value["page"]?.longOrNull() ?: fail()
            val expires = value["expires"]?.longOrNull() ?: fail()
            if (value.keys != setOf("v", "q", "page", "expires") || value["v"]?.longOrNull() != 1L ||
                value["q"]?.stringOrNull() != key || page !in 2..maxPages.toLong() ||
                expires <= now || expires > plus(now, TTL_MILLIS)) fail()
            check()
            return page.toInt()
        } catch (failure: SearxngFailure) {
            if (failure.code == SearxngError.CANCELLED || failure.code == SearxngError.DEADLINE_EXCEEDED) throw failure
            fail()
        } catch (_: IllegalArgumentException) { fail() }
    }
    private fun fail(): Nothing = throw SearxngFailure(SearxngError.INVALID_CURSOR)
}
