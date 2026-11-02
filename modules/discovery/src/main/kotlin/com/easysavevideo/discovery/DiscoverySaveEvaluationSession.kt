package com.easysavevideo.discovery

enum class SaveEvaluationMethod { SAVE1, SAVE2, SAVE3 }
enum class SaveEvaluationSessionState { NEW, ACTIVE, FINISHED }

/** Opaque correlation ticket. A result is accepted only for this exact session-issued attempt. */
class SaveEvaluationAttempt internal constructor(
    internal val owner: Any, internal val pageId: String, internal val method: SaveEvaluationMethod,
) { override fun toString(): String = "SaveEvaluationAttempt([redacted])" }

data class SaveEvaluationSessionReport(
    val state: SaveEvaluationSessionState,
    val evaluation: DiscoverySaveEvaluationReport,
    val unfinishedMethodAttempts: Int,
) {
    val mode: SaveEvaluationMode get() = evaluation.mode
    override fun toString(): String = "SaveEvaluationSessionReport(state=$state,mode=$mode,[redacted])"
}

/** Explicit opt-in harness only. Never observes production browsing, detection, saves or recordings. */
class DiscoverySaveEvaluationSession(
    private val evidence: SaveEvaluationEvidence,
    val mode: SaveEvaluationMode = SaveEvaluationMode.FALLBACK,
) {
    private val owner = Any()
    private var state = SaveEvaluationSessionState.NEW
    private val pages = linkedMapOf<String, SaveEvaluationObservation>()
    private val attempts = linkedMapOf<Pair<String, SaveEvaluationMethod>, SaveEvaluationAttempt>()
    private var finished: SaveEvaluationSessionReport? = null

    @Synchronized fun begin() {
        require(state == SaveEvaluationSessionState.NEW) { "Session already begun" }
        state = SaveEvaluationSessionState.ACTIVE
    }

    /** Registering a discovered page preserves provenance but never increments N_actual. */
    @Synchronized fun register(candidate: PendingVideoCandidate) {
        active()
        DiscoverySaveEvaluation.summarize(listOf(CandidateSaveEvaluation(candidate, evidence)), mode)
        val old = pages[candidate.candidateId]
        require(old != null || pages.size < 500) { "Evaluation capacity reached" }
        val sources = (old?.provenance.orEmpty() + candidate.provenance).toSet()
        require(sources.size <= 32) { "Provider capacity reached" }
        pages[candidate.candidateId] = old?.copy(provenance = sources)
            ?: SaveEvaluationObservation(sources, testStarted = false)
    }

    /** Explicit page test start, even if no method eventually produces a result. */
    @Synchronized fun beginPage(candidateId: String) {
        active()
        val row = page(candidateId)
        require(!row.testStarted) { "Page test already started" }
        pages[candidateId] = if (mode == SaveEvaluationMode.INDEPENDENT_RECORD) {
            row.copy(testStarted = true, save1 = SaveEvaluationOutcome.SKIPPED, save2 = SaveEvaluationOutcome.SKIPPED)
        } else row.copy(testStarted = true)
    }

    @Synchronized fun beginMethod(candidateId: String, method: SaveEvaluationMethod): SaveEvaluationAttempt {
        active()
        val row = page(candidateId)
        require(row.testStarted) { "Page test has not started" }
        require(mode != SaveEvaluationMode.INDEPENDENT_RECORD || method == SaveEvaluationMethod.SAVE3) {
            "Primary methods are skipped in independent recording"
        }
        val key = candidateId to method
        require(key !in attempts && outcome(row, method) == SaveEvaluationOutcome.NOT_TESTED) { "Method already started" }
        if (method == SaveEvaluationMethod.SAVE3 && mode == SaveEvaluationMode.FALLBACK) {
            require(row.save1 == SaveEvaluationOutcome.FAILED && row.save2 == SaveEvaluationOutcome.FAILED) {
                "Fallback requires completed primary failures"
            }
        }
        return SaveEvaluationAttempt(owner, candidateId, method).also { attempts[key] = it }
    }

    @Synchronized fun finishMethod(attempt: SaveEvaluationAttempt, result: SaveEvaluationOutcome) {
        active()
        require(result == SaveEvaluationOutcome.SUCCEEDED || result == SaveEvaluationOutcome.FAILED ||
            result == SaveEvaluationOutcome.BLOCKED) { "Attempt terminal result required" }
        val key = attempt.pageId to attempt.method
        require(attempt.owner === owner && attempts[key] === attempt) { "Unknown or completed attempt" }
        val row = page(attempt.pageId)
        pages[attempt.pageId] = when (attempt.method) {
            SaveEvaluationMethod.SAVE1 -> row.copy(save1 = result)
            SaveEvaluationMethod.SAVE2 -> row.copy(save2 = result)
            SaveEvaluationMethod.SAVE3 -> row.copy(save3 = result)
        }
        attempts.remove(key)
    }

    @Synchronized fun snapshot(): SaveEvaluationSessionReport {
        require(state != SaveEvaluationSessionState.NEW) { "Session has not begun" }
        return finished ?: report()
    }

    /** Finish may preserve incomplete observations; it never invents cancellation/failure outcomes. */
    @Synchronized fun finish(): SaveEvaluationSessionReport {
        finished?.let { return it }
        active()
        state = SaveEvaluationSessionState.FINISHED
        return report().also { finished = it; pages.clear(); attempts.clear() }
    }

    private fun active() { require(state == SaveEvaluationSessionState.ACTIVE) { "Session is not active" } }
    private fun page(id: String): SaveEvaluationObservation = requireNotNull(pages[id]) { "Unknown page" }
    private fun report() = SaveEvaluationSessionReport(state,
        DiscoverySaveEvaluation.summarizeObservations(pages.values.toList(), evidence, mode), attempts.size)
    private fun outcome(row: SaveEvaluationObservation, method: SaveEvaluationMethod) = when (method) {
        SaveEvaluationMethod.SAVE1 -> row.save1
        SaveEvaluationMethod.SAVE2 -> row.save2
        SaveEvaluationMethod.SAVE3 -> row.save3
    }
}

/** Explicit local lookup only; do not serialize, log or attach this mapping to the export. */
class SaveEvaluationProviderMapping internal constructor(private val sources: Map<String, String>) {
    fun sourceIdFor(alias: String): String? = sources[alias]
    override fun toString(): String = "SaveEvaluationProviderMapping([redacted])"
}

/** Local export payload only: no filesystem/network writes, raw identifiers, metadata or timestamps. */
object DiscoverySaveEvaluationJson {
    const val SCHEMA_VERSION = 2
    const val MAX_EXPORT_BYTES = 1_048_576
    /** Use the SAME immutable report for export and local lookup. Ordinals may change in a later snapshot. */
    fun localProviderMapping(report: SaveEvaluationSessionReport): SaveEvaluationProviderMapping =
        SaveEvaluationProviderMapping(report.evaluation.byProvider.keys.mapIndexed { index, source ->
            "source-${index + 1}" to source
        }.toMap())
    fun export(report: SaveEvaluationSessionReport): String = buildString {
        append("{\"schemaVersion\":$SCHEMA_VERSION,\"mode\":\"${report.mode.name}\",\"evidence\":")
        append(report.evaluation.evidence?.let { "\"${it.name}\"" } ?: "null")
        append(",\"state\":\"${report.state.name}\",\"unfinishedMethodAttempts\":${report.unfinishedMethodAttempts}")
        append(",\"overall\":"); counts(report.evaluation.overall)
        append(",\"providers\":[")
        // The stable local ordinal is deliberately not a source ID or a reversible hash of one.
        report.evaluation.byProvider.values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append("{\"provider\":\"source-${index + 1}\",\"counts\":"); counts(value); append('}')
            require(length <= MAX_EXPORT_BYTES) { "Export capacity reached" }
        }
        append("]}")
        // Every emitted character is fixed-schema ASCII; character and UTF-8 byte limits are identical.
        require(length <= MAX_EXPORT_BYTES) { "Export capacity reached" }
    }
    private fun StringBuilder.counts(c: SaveEvaluationCounts) {
        append("{\"uniquePages\":${c.uniquePages},\"actualTestPages\":${c.actualTestPages},\"notStartedPages\":${c.notStartedPages}")
        append(",\"partial\":${c.partial},\"save1Share\":${c.save1Share},\"save2Share\":${c.save2Share},\"fallbackShare\":${c.fallbackShare}")
        append(",\"fallbackEligiblePages\":${c.fallbackEligiblePages},\"fallbackTestCoverage\":${c.fallbackTestCoverage},\"fallbackRescueRate\":${c.fallbackRescueRate}")
        append(",\"jointRecordShare\":${c.jointRecordShare},\"jointRecordTestCoverage\":${c.jointRecordTestCoverage}")
        append(",\"independentRecordShare\":${c.independentRecordShare},\"independentRecordTestCoverage\":${c.independentRecordTestCoverage}")
        append(",\"fallbackSave3\":"); method(c.fallbackSave3)
        append(",\"independentRecord\":"); method(c.independentRecord)
        append(",\"save1\":"); method(c.save1); append(",\"save2\":"); method(c.save2)
        append(",\"save3\":"); method(c.save3); append('}')
    }
    private fun StringBuilder.method(c: SaveMethodEvaluationCounts) {
        append("{\"succeeded\":${c.succeeded},\"failed\":${c.failed},\"blocked\":${c.blocked},\"notTested\":${c.notTested}")
        append(",\"skipped\":${c.skipped},\"tested\":${c.tested},\"successRate\":${c.successRate}}")
    }
}
