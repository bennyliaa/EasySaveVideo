package com.easysavevideo.discovery

import java.net.URI

/** Pending cards are deliberately a different type from a verified VideoSearchResult. */
data class PendingVideoCandidate(
    val candidateId: String,
    val canonicalPageUrl: String,
    val sourceReported: SourceReportedCandidateMetadata?,
    val provenance: Set<String>,
    val verification: VerificationSummary? = null,
    val verificationActive: Boolean = false,
) { override fun toString(): String = "PendingVideoCandidate([redacted])" }

object PendingCandidatePolicy {
    fun normalize(candidate: CandidateUrl, providerId: String, filters: SearchFilters): PendingVideoCandidate? {
        requireId(providerId)
        // Unvalidated duration is unknown for actual video filtering, even if the source reports one.
        if (!filters.includeUnknownDuration || filters.sourceNamespaces.isNotEmpty() &&
            filters.sourceNamespaces.none { it.equals(providerId, ignoreCase = true) }) return null
        val url = ResultNormalizer.canonicalUrl(candidate.pageUrl) ?: return null
        if (url.length > 4096) return null
        val metadata = candidate.sourceReported?.let {
            if ((it.title?.length ?: 0) > 512 || (it.sourceName?.length ?: 0) > 128 ||
                (it.description?.length ?: 0) > 4096 ||
                listOfNotNull(it.title, it.sourceName, it.description).any { text -> !isWellFormedUnicode(text) } ||
                it.reportedDurationMs?.let { duration -> duration <= 0 } == true ||
                it.reportedPublishedAtEpochMillis?.let { date -> date < 0 } == true) return null
            it.copy(title = clean(it.title), sourceName = clean(it.sourceName), description = clean(it.description))
        }
        return PendingVideoCandidate(digest(framed("candidate-url-v1", url)), url, metadata, setOf(providerId))
    }
    private fun clean(value: String?): String? = value?.filter {
        !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt()
    }?.trim()?.takeIf(String::isNotEmpty)
}

/** Ephemeral and bounded. It never writes Seen facts, verified pages, media, or persisted metadata. */
internal class PendingCandidatePool(private val capacity: Int) {
    private val values = linkedMapOf<String, PendingVideoCandidate>()
    init { require(capacity in 1..500) }
    val size: Int get() = values.size
    fun snapshot(): List<PendingVideoCandidate> = values.values.map { it.copy(provenance = it.provenance.toSet()) }
    fun add(candidates: List<PendingVideoCandidate>, confirmedUrls: Set<String>): Int {
        var added = 0
        values.entries.removeAll { it.value.canonicalPageUrl in confirmedUrls }
        for (candidate in candidates) {
            if (candidate.canonicalPageUrl in confirmedUrls) continue
            val old = values[candidate.candidateId]
            if (old != null) {
                // Keep the first report; merging a report never upgrades its evidence level.
                values[candidate.candidateId] = old.copy(provenance = (old.provenance + candidate.provenance).take(32).toSet())
            } else if (values.size < capacity) {
                values[candidate.candidateId] = candidate.copy(provenance = candidate.provenance.toSet()); added++
            }
        }
        return added
    }
    fun mark(id: String, active: Boolean, summary: VerificationSummary? = null) {
        values[id]?.let { values[id] = it.copy(verificationActive = active, verification = summary ?: it.verification) }
    }
    fun removeProvider(id: String?) {
        if (id == null) { clear(); return }
        values.entries.removeAll { id in it.value.provenance }
    }
    fun clear() = values.clear()
}
