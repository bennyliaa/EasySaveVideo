package com.easysavevideo.discovery

/** Operator-supplied evaluation evidence, never inferred from discovery or video detection. */
enum class SaveEvaluationOutcome { NOT_TESTED, BLOCKED, SUCCEEDED, FAILED, SKIPPED }
enum class SaveEvaluationEvidence { SYNTHETIC_FIXTURE, OBSERVED_SESSION }
/** FALLBACK preserves the original gated protocol. JOINT tests all three methods independently. */
enum class SaveEvaluationMode { FALLBACK, JOINT, INDEPENDENT_RECORD }

/** One page in one evaluation run. No URL or source metadata is emitted in diagnostics. */
data class CandidateSaveEvaluation(
    val candidate: PendingVideoCandidate,
    val evidence: SaveEvaluationEvidence,
    val testStarted: Boolean = false,
    val save1: SaveEvaluationOutcome = SaveEvaluationOutcome.NOT_TESTED,
    val save2: SaveEvaluationOutcome = SaveEvaluationOutcome.NOT_TESTED,
    val save3: SaveEvaluationOutcome = SaveEvaluationOutcome.NOT_TESTED,
) { override fun toString(): String = "CandidateSaveEvaluation([redacted])" }

data class SaveMethodEvaluationCounts(
    val succeeded: Int, val failed: Int, val blocked: Int, val notTested: Int, val skipped: Int = 0,
) {
    val tested: Int get() = succeeded + failed
    /** Null means unknown, not zero success. Blocked, skipped and untested pages are not failures. */
    val successRate: Double? get() = if (tested == 0) null else succeeded.toDouble() / tested
}

data class SaveEvaluationCounts(
    val uniquePages: Int,
    val actualTestPages: Int,
    val save1: SaveMethodEvaluationCounts,
    val save2: SaveMethodEvaluationCounts,
    val fallbackEligiblePages: Int,
    /** All started pages in JOINT, only eligible pages in FALLBACK; empty in INDEPENDENT_RECORD. */
    val save3: SaveMethodEvaluationCounts,
    val mode: SaveEvaluationMode = SaveEvaluationMode.FALLBACK,
    /** The both-primary-failed subset only; never includes independent recording results. */
    val fallbackSave3: SaveMethodEvaluationCounts = save3,
    val independentRecord: SaveMethodEvaluationCounts = SaveMethodEvaluationCounts(0, 0, 0, 0),
) {
    val notStartedPages: Int get() = uniquePages - actualTestPages
    /** Main shares have the SAME denominator: all unique pages explicitly entering this run. */
    val save1Share: Double? get() = if (mode == SaveEvaluationMode.INDEPENDENT_RECORD) null else share(save1.succeeded)
    val save2Share: Double? get() = if (mode == SaveEvaluationMode.INDEPENDENT_RECORD) null else share(save2.succeeded)
    val fallbackShare: Double? get() = if (mode == SaveEvaluationMode.INDEPENDENT_RECORD) null else share(fallbackSave3.succeeded)
    val jointRecordShare: Double? get() = if (mode == SaveEvaluationMode.JOINT) share(save3.succeeded) else null
    val jointRecordTestCoverage: Double? get() = if (mode == SaveEvaluationMode.JOINT) share(save3.tested) else null
    val independentRecordShare: Double? get() = if (mode == SaveEvaluationMode.INDEPENDENT_RECORD) share(independentRecord.succeeded) else null
    val independentRecordTestCoverage: Double? get() = if (mode == SaveEvaluationMode.INDEPENDENT_RECORD) share(independentRecord.tested) else null
    val partial: Boolean get() = notStartedPages > 0 || when (mode) {
        SaveEvaluationMode.FALLBACK -> save1.tested != actualTestPages || save2.tested != actualTestPages ||
            save3.tested != fallbackEligiblePages
        SaveEvaluationMode.JOINT -> save1.tested != actualTestPages || save2.tested != actualTestPages ||
            save3.tested != actualTestPages
        SaveEvaluationMode.INDEPENDENT_RECORD -> independentRecord.tested != actualTestPages
    }
    private fun share(successes: Int): Double? = if (actualTestPages == 0) null else successes.toDouble() / actualTestPages
    /** Coverage of eligible pages, separate from success among actually tested fallback pages. */
    val fallbackTestCoverage: Double? get() = if (fallbackEligiblePages == 0) null else
        fallbackSave3.tested.toDouble() / fallbackEligiblePages
    /** Unknown until every eligible page has a completed recording test, irrespective of event order in JOINT. */
    val fallbackRescueRate: Double? get() = if (fallbackEligiblePages == 0 || fallbackSave3.tested != fallbackEligiblePages) null
        else fallbackSave3.succeeded.toDouble() / fallbackEligiblePages
}

data class DiscoverySaveEvaluationReport(
    val evidence: SaveEvaluationEvidence?,
    val overall: SaveEvaluationCounts,
    val byProvider: Map<String, SaveEvaluationCounts>,
    val mode: SaveEvaluationMode = SaveEvaluationMode.FALLBACK,
)

/** Already normalized and deduplicated by the evaluator or session; contains no page address or metadata. */
internal data class SaveEvaluationObservation(
    val provenance: Set<String>, val testStarted: Boolean,
    val save1: SaveEvaluationOutcome = SaveEvaluationOutcome.NOT_TESTED,
    val save2: SaveEvaluationOutcome = SaveEvaluationOutcome.NOT_TESTED,
    val save3: SaveEvaluationOutcome = SaveEvaluationOutcome.NOT_TESTED,
)

/** Bounded, pure evaluator for an explicitly supplied run; it does not collect or certify live evidence. */
object DiscoverySaveEvaluation {
    fun summarize(rows: List<CandidateSaveEvaluation>, mode: SaveEvaluationMode = SaveEvaluationMode.FALLBACK): DiscoverySaveEvaluationReport {
        require(rows.size <= 500)
        require(rows.map { it.evidence }.distinct().size <= 1) { "Mixed evidence kinds" }
        val unique = linkedMapOf<String, CandidateSaveEvaluation>()
        for (row in rows) {
            val candidate = row.candidate
            require(candidate.provenance.isNotEmpty() && candidate.provenance.size <= 32)
            candidate.provenance.forEach(::requireId)
            val normalized = PendingCandidatePolicy.normalize(CandidateUrl(candidate.canonicalPageUrl),
                candidate.provenance.first(), SearchFilters(safeSearch = false))
            require(normalized != null && normalized.candidateId == candidate.candidateId &&
                normalized.canonicalPageUrl == candidate.canonicalPageUrl) { "Invalid candidate identity" }
            val old = unique[candidate.candidateId]
            val merged = if (old == null) row.copy(candidate = candidate.copy(provenance = candidate.provenance.toSet())) else {
                val sources = old.candidate.provenance + candidate.provenance
                require(sources.size <= 32)
                old.copy(candidate = old.candidate.copy(provenance = sources), testStarted = old.testStarted || row.testStarted,
                    save1 = merge(old.save1, row.save1), save2 = merge(old.save2, row.save2), save3 = merge(old.save3, row.save3))
            }
            unique[candidate.candidateId] = merged
        }
        return summarizeObservations(unique.values.map {
            SaveEvaluationObservation(it.candidate.provenance, it.testStarted, it.save1, it.save2, it.save3)
        }, rows.firstOrNull()?.evidence, mode)
    }

    /** Internal handoff after identity validation/deduplication; avoids retaining URLs in a live session. */
    internal fun summarizeObservations(pages: List<SaveEvaluationObservation>, evidence: SaveEvaluationEvidence?,
        mode: SaveEvaluationMode = SaveEvaluationMode.FALLBACK): DiscoverySaveEvaluationReport {
        // Validation follows merging so separate method observations can describe the same page.
        pages.forEach {
            require(it.testStarted || listOf(it.save1, it.save2, it.save3).all { outcome ->
                outcome == SaveEvaluationOutcome.NOT_TESTED
            }) { "Outcome without explicit test start" }
            when (mode) {
                SaveEvaluationMode.FALLBACK -> require(it.save3 == SaveEvaluationOutcome.NOT_TESTED || eligible(it)) {
                    "Fallback requires both primary methods to have failed"
                }
                SaveEvaluationMode.JOINT -> Unit // Recording is independently callable; fallback is only a derived subset.
                SaveEvaluationMode.INDEPENDENT_RECORD -> require(!it.testStarted ||
                    (it.save1 == SaveEvaluationOutcome.SKIPPED && it.save2 == SaveEvaluationOutcome.SKIPPED)) {
                    "Independent recording requires explicitly skipped primary methods"
                }
            }
        }
        return DiscoverySaveEvaluationReport(evidence, count(pages, mode),
            java.util.Collections.unmodifiableMap(pages.flatMap { it.provenance }.distinct().sorted().associateWith { provider ->
                count(pages.filter { provider in it.provenance }, mode)
            }), mode)
    }

    private fun merge(a: SaveEvaluationOutcome, b: SaveEvaluationOutcome): SaveEvaluationOutcome {
        if (a == SaveEvaluationOutcome.NOT_TESTED) return b
        if (b == SaveEvaluationOutcome.NOT_TESTED) return a
        require(a == b) { "Conflicting observations require separate evaluation runs" }
        return a
    }
    private fun eligible(row: SaveEvaluationObservation): Boolean =
        row.save1 == SaveEvaluationOutcome.FAILED && row.save2 == SaveEvaluationOutcome.FAILED
    private fun counts(outcomes: List<SaveEvaluationOutcome>) = SaveMethodEvaluationCounts(
        outcomes.count { it == SaveEvaluationOutcome.SUCCEEDED }, outcomes.count { it == SaveEvaluationOutcome.FAILED },
        outcomes.count { it == SaveEvaluationOutcome.BLOCKED }, outcomes.count { it == SaveEvaluationOutcome.NOT_TESTED },
        outcomes.count { it == SaveEvaluationOutcome.SKIPPED })
    private fun count(pages: List<SaveEvaluationObservation>, mode: SaveEvaluationMode): SaveEvaluationCounts {
        val started = pages.filter { it.testStarted }
        val fallback = if (mode == SaveEvaluationMode.INDEPENDENT_RECORD) emptyList() else started.filter(::eligible)
        val record = when (mode) {
            SaveEvaluationMode.FALLBACK -> fallback
            SaveEvaluationMode.JOINT -> started
            SaveEvaluationMode.INDEPENDENT_RECORD -> emptyList()
        }
        val independent = if (mode == SaveEvaluationMode.INDEPENDENT_RECORD) started else emptyList()
        return SaveEvaluationCounts(pages.size, started.size, counts(started.map { it.save1 }), counts(started.map { it.save2 }),
            fallback.size, counts(record.map { it.save3 }), mode, counts(fallback.map { it.save3 }), counts(independent.map { it.save3 }))
    }
}
