package com.easysavevideo.commons.rules

import com.easysavevideo.commons.CommonsError
import com.easysavevideo.commons.CommonsFailure
import com.easysavevideo.commons.CommonsText
import com.easysavevideo.commons.Json
import com.easysavevideo.commons.StrictJson
import com.easysavevideo.commons.isWellFormedUnicode
import com.easysavevideo.commons.longOrNull
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.CandidateUrl
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryLimits
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.ProviderDescriptor
import com.easysavevideo.discovery.ProviderPage
import com.easysavevideo.discovery.ResultNormalizer
import com.easysavevideo.discovery.SourceReportedCandidateMetadata
import java.util.Collections

/**
 * Pure one-page interpretation of caller-supplied bytes. There is no transport, registration,
 * automatic pagination, playback evidence, rights claim, file access, or package trust decision.
 * The caller owns source identity, operation budgets and any later validation of the candidates.
 */
class DeclarativeJsonRuleExecutor(
    private val rule: DeclarativeJsonRule,
    descriptor: ProviderDescriptor,
    private val sourceName: String,
    private val limits: DiscoveryLimits = DiscoveryLimits(),
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
) {
    val descriptor: ProviderDescriptor = descriptor.copy(
        compatibleCursorVersions = Collections.unmodifiableSet(LinkedHashSet(descriptor.compatibleCursorVersions)))

    init {
        require(sourceName.length in 1..128 && sourceName.isNotBlank() && isWellFormedUnicode(sourceName) &&
            sourceName.none { it.isISOControl() })
    }

    fun execute(responseBytes: ByteArray, operation: DiscoveryOperation): ProviderPage =
        executeObserved(responseBytes, operation) {}

    /** Internal attribution hook: called only after a bounded JSON items array is recognized. */
    internal fun executeObserved(responseBytes: ByteArray, operation: DiscoveryOperation,
        structureRecognized: () -> Unit): ProviderPage {
        fun check() {
            if (Thread.currentThread().isInterrupted) ruleFailure(DiscoveryError.CANCELLED)
            operation.check(clock.nowMillis())
        }
        check()
        val maximumBytes = minOf(MAX_RESPONSE_BYTES, limits.maxResponseBytes,
            operation.context.resourceBudget.maxBytes ?: Long.MAX_VALUE)
        if (responseBytes.size.toLong() > maximumBytes) ruleFailure(DiscoveryError.RESPONSE_TOO_LARGE)
        val root = try { StrictJson.read(responseBytes.copyOf(), ::check) }
            catch (failure: CommonsFailure) {
                ruleFailure(if (failure.code == CommonsError.RESPONSE_TOO_LARGE)
                    DiscoveryError.RESPONSE_TOO_LARGE else DiscoveryError.INVALID_RESULT)
            }
        val rows = (rule.itemsPath.read(root, ::check) as? Json.Arr)?.values ?: invalidRuleResponse()
        if (rows.size > minOf(MAX_ITEMS, limits.maxCandidatesPerResponse))
            ruleFailure(DiscoveryError.TOO_MANY_CANDIDATES)
        structureRecognized()
        val nextCursor = cursor(rule.nextCursorPath?.read(root, ::check))
        val found = linkedMapOf<String, CandidateUrl>()
        for (row in rows) {
            check()
            if (row !is Json.Obj) invalidRuleResponse()
            val fields = rule.fields
            val rawUrl = string(fields.pageUrl.read(row, ::check))
            val title = text(fields.title?.read(row, ::check), MAX_TITLE_LENGTH)
            val description = text(fields.description?.read(row, ::check), MAX_DESCRIPTION_LENGTH)
            val duration = number(fields.durationMs?.read(row, ::check), positive = true)
            val published = number(fields.publishedAtEpochMillis?.read(row, ::check), positive = false)
            // Missing/null or unsafe page links are ordinary unusable candidates, not authority.
            // URL normalization performs no DNS lookup or fetch; downstream network gates still apply.
            val url = rawUrl?.takeIf { it.length <= MAX_URL_LENGTH }
                ?.let(ResultNormalizer::canonicalUrl)?.takeIf { it.length <= MAX_URL_LENGTH } ?: continue
            found.putIfAbsent(url, CandidateUrl(url, SourceReportedCandidateMetadata(
                title = title, sourceName = sourceName, description = description,
                reportedDurationMs = duration, reportedPublishedAtEpochMillis = published)))
        }
        check()
        return ProviderPage(candidates = found.values.toList(), nextCursor = nextCursor,
            cursorExpiresAtEpochMillis = null, exhausted = nextCursor == null,
            responseBytes = responseBytes.size.toLong())
    }

    override fun toString(): String = "DeclarativeJsonRuleExecutor([redacted])"

    private fun string(value: Json?): String? = when (value) {
        null, Json.Nil -> null
        is Json.Str -> value.value
        else -> invalidRuleResponse()
    }

    private fun text(value: Json?, maximum: Int): String? {
        val raw = string(value) ?: return null
        if (raw.length > maximum) invalidRuleResponse()
        return CommonsText.plain(raw, maximum).text
    }

    private fun number(value: Json?, positive: Boolean): Long? {
        if (value == null || value === Json.Nil) return null
        val parsed = value.longOrNull() ?: invalidRuleResponse()
        if (parsed < 0 || positive && parsed == 0L) invalidRuleResponse()
        return parsed
    }

    private fun cursor(value: Json?): String? {
        val raw = string(value) ?: return null
        if (raw.isBlank() || raw.any { it.isISOControl() } || raw.toByteArray(Charsets.UTF_8).size > MAX_CURSOR_BYTES)
            invalidRuleResponse()
        return raw
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 256L * 1024
        const val MAX_ITEMS = 100
        const val MAX_URL_LENGTH = 4096
        const val MAX_TITLE_LENGTH = 512
        const val MAX_DESCRIPTION_LENGTH = 2048
        const val MAX_CURSOR_BYTES = 2048
    }
}
