package com.easysavevideo.verification

/** Exact v1 schema. Numbers are integer tokens, never coerced decimal/exponent/string values. */
internal object VerificationProtocol {
    private val RESPONSE_KEYS = setOf("version", "requestId", "generation", "candidateUrl", "outcome", "pageUrl",
        "evidence", "title", "thumbnailUrl", "durationMs", "playbackVerified", "downloadable")

    fun request(requestId: String, generation: String, candidateUrl: String, budgetMs: Long,
        dynamicAllowed: Boolean): ByteArray {
        if (!requestId.matches(Regex("[0-9a-f]{32}")) || !generation.matches(Regex("[0-9a-f]{64}")) ||
            VerificationUrlPolicy.candidate(candidateUrl) != candidateUrl || budgetMs !in 1..10_000)
            throw VerificationFailure(VerificationError.INVALID_REQUEST)
        val bytes = StrictJson.write(Json.Obj(linkedMapOf(
            "version" to Json.Num("1"), "requestId" to Json.Str(requestId), "generation" to Json.Str(generation),
            "candidateUrl" to Json.Str(candidateUrl), "budgetMs" to Json.Num(budgetMs.toString()),
            "dynamicAllowed" to Json.Bool(dynamicAllowed),
        ))).toByteArray(Charsets.UTF_8)
        if (bytes.size > 8192) throw VerificationFailure(VerificationError.INVALID_REQUEST)
        return bytes
    }

    fun response(bytes: ByteArray, requestId: String, generation: String, candidateUrl: String,
        dynamicAllowed: Boolean, check: () -> Unit): VerificationResult {
        val root = StrictJson.read(bytes, check).objectValue()
        if (root.keys != RESPONSE_KEYS || root["version"]?.longOrNull() != 1L ||
            root["requestId"]?.stringOrNull() != requestId || root["generation"]?.stringOrNull() != generation ||
            root["candidateUrl"]?.stringOrNull() != candidateUrl || root["pageUrl"]?.stringOrNull() != candidateUrl ||
            root["playbackVerified"] != Json.Bool(false) || root["downloadable"] != Json.Bool(false)) invalidResponse()
        val outcome = VerificationOutcome.entries.firstOrNull { it.name == root["outcome"]?.stringOrNull() } ?: invalidResponse()
        val evidence = VerificationEvidence.entries.firstOrNull { it.name == root["evidence"]?.stringOrNull() } ?: invalidResponse()
        val title = nullableString(root.getValue("title"))?.also {
            // The Discovery text bound is UTF-16 units. Reject instead of silently truncating.
            if (it.length > 512 || it.any(Char::isISOControl)) invalidResponse()
        }
        val thumbnail = nullableString(root.getValue("thumbnailUrl"))?.also {
            if (VerificationUrlPolicy.thumbnail(it) != it) invalidResponse()
        }
        val duration = root.getValue("durationMs").let {
            if (it == Json.Nil) null else it.longOrNull()?.takeIf { value -> value in 1..86_400_000 } ?: invalidResponse()
        }
        if (outcome == VerificationOutcome.PAGE_EVIDENCE) {
            if (evidence == VerificationEvidence.NONE || evidence == VerificationEvidence.DYNAMIC_VIDEO_XHR && !dynamicAllowed)
                invalidResponse()
        } else if (evidence != VerificationEvidence.NONE || title != null || thumbnail != null || duration != null) invalidResponse()
        check()
        return VerificationResult(requestId, generation, candidateUrl, outcome, candidateUrl, evidence, title, thumbnail, duration)
    }

    private fun nullableString(value: Json): String? = when (value) {
        Json.Nil -> null
        is Json.Str -> value.value
        else -> invalidResponse()
    }
}
