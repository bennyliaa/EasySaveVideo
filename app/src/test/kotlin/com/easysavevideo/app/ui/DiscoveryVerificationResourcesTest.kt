package com.easysavevideo.app.ui

import com.easysavevideo.app.R
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.*
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

internal fun pendingResourceAppRoot(): File =
    listOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }

internal fun pendingResourceStrings(folder: String, file: String = "discovery_pending.xml"): Map<String, String> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val nodes = factory.newDocumentBuilder().parse(File(pendingResourceAppRoot(), "src/main/res/$folder/$file"))
        .getElementsByTagName("string")
    return (0 until nodes.length).associate { index ->
        val node = nodes.item(index)
        node.attributes.getNamedItem("name").nodeValue to node.textContent
    }.also { check(it.size == nodes.length) { "Duplicate resource name" } }
}

/** Executes actual selectors/domain fixtures. XML formatting is host evidence, not Android rendering. */
class DiscoveryVerificationResourcesTest {
    private val admission = mapOf(
        SourceAdmissionStatus.UNCONFIGURED to R.string.discovery_admission_unconfigured,
        SourceAdmissionStatus.READY to R.string.discovery_admission_ready,
        SourceAdmissionStatus.UNSUPPORTED to R.string.discovery_admission_unsupported,
        SourceAdmissionStatus.DENIED to R.string.discovery_admission_denied,
    )
    private val outcomes = mapOf(
        EngineOutcome.PAGE_EVIDENCE to R.string.discovery_verification_outcome_page_evidence,
        EngineOutcome.UNKNOWN to R.string.discovery_verification_outcome_unknown,
        EngineOutcome.UNAVAILABLE to R.string.discovery_verification_outcome_unavailable,
        EngineOutcome.DENIED to R.string.discovery_verification_outcome_denied,
        EngineOutcome.RATE_LIMITED to R.string.discovery_verification_outcome_rate_limited,
        EngineOutcome.TIMEOUT to R.string.discovery_verification_outcome_timeout,
        EngineOutcome.CANCELLED to R.string.discovery_verification_outcome_cancelled,
        EngineOutcome.ERROR to R.string.discovery_verification_outcome_error,
    )
    private val evidence = mapOf(
        EngineEvidence.NONE to R.string.discovery_verification_evidence_none,
        EngineEvidence.HTML_VIDEO to R.string.discovery_verification_evidence_html_video,
        EngineEvidence.DYNAMIC_VIDEO to R.string.discovery_verification_evidence_dynamic_video,
    )
    private val namesById = mapOf(
        R.string.discovery_pending_notice_candidates to "discovery_pending_notice_candidates",
        R.string.discovery_pending_notice_scope to "discovery_pending_notice_scope",
        R.string.discovery_pending_badge to "discovery_pending_badge",
        R.string.discovery_pending_site_unknown to "discovery_pending_site_unknown",
        R.string.discovery_pending_sources to "discovery_pending_sources",
        R.string.discovery_pending_unverified_metadata to "discovery_pending_unverified_metadata",
        R.string.discovery_pending_admission_notice to "discovery_pending_admission_notice",
        R.string.discovery_pending_verify_active to "discovery_pending_verify_active",
        R.string.discovery_pending_verify_attempted to "discovery_pending_verify_attempted",
        R.string.discovery_pending_verify_action to "discovery_pending_verify_action",
        R.string.discovery_pending_open to "discovery_pending_open",
        R.string.discovery_admission_unconfigured to "discovery_admission_unconfigured",
        R.string.discovery_admission_ready to "discovery_admission_ready",
        R.string.discovery_admission_unsupported to "discovery_admission_unsupported",
        R.string.discovery_admission_denied to "discovery_admission_denied",
        R.string.discovery_verification_outcome_page_evidence to "discovery_verification_outcome_page_evidence",
        R.string.discovery_verification_outcome_unknown to "discovery_verification_outcome_unknown",
        R.string.discovery_verification_outcome_unavailable to "discovery_verification_outcome_unavailable",
        R.string.discovery_verification_outcome_denied to "discovery_verification_outcome_denied",
        R.string.discovery_verification_outcome_rate_limited to "discovery_verification_outcome_rate_limited",
        R.string.discovery_verification_outcome_timeout to "discovery_verification_outcome_timeout",
        R.string.discovery_verification_outcome_cancelled to "discovery_verification_outcome_cancelled",
        R.string.discovery_verification_outcome_error to "discovery_verification_outcome_error",
        R.string.discovery_verification_evidence_none to "discovery_verification_evidence_none",
        R.string.discovery_verification_evidence_html_video to "discovery_verification_evidence_html_video",
        R.string.discovery_verification_evidence_dynamic_video to "discovery_verification_evidence_dynamic_video",
    )
    private fun text(folder: String, id: Int): String = pendingResourceStrings(folder).getValue(namesById.getValue(id))
    private fun summary(folder: String, value: VerificationSummary): String = String.format(
        if (folder == "values") Locale.US else Locale.SIMPLIFIED_CHINESE,
        pendingResourceStrings(folder, "discovery_copy.xml").getValue("discovery_copy_6"),
        text(folder, discoveryVerificationOutcomeLabel(value.outcome)),
        text(folder, discoveryVerificationEvidenceLabel(value.evidence)),
    )

    @Test fun everyExistingEnumValueHasAnExplicitDistinctResourceId() {
        assertEquals(SourceAdmissionStatus.entries.toSet(), admission.keys)
        assertEquals(EngineOutcome.entries.toSet(), outcomes.keys)
        assertEquals(EngineEvidence.entries.toSet(), evidence.keys)
        admission.forEach { (value, id) -> assertEquals(value.name, id, discoverySourceAdmissionLabel(value)) }
        outcomes.forEach { (value, id) -> assertEquals(value.name, id, discoveryVerificationOutcomeLabel(value)) }
        evidence.forEach { (value, id) -> assertEquals(value.name, id, discoveryVerificationEvidenceLabel(value)) }
        assertEquals(15, (admission.values + outcomes.values + evidence.values).toSet().size)
    }

    @Test fun bothLocalesHaveExactlyTheApprovedKeysAndMatchingPlaceholders() {
        val en = pendingResourceStrings("values")
        val zh = pendingResourceStrings("values-zh-rCN")
        assertEquals(26, namesById.size)
        assertEquals(namesById.values.toSet(), en.keys)
        assertEquals(en.keys, zh.keys)
        val placeholders = Regex("%[0-9]+\\\$(?:s|d)")
        for (key in en.keys) {
            assertEquals(key, placeholders.findAll(en.getValue(key)).map { it.value }.toList(),
                placeholders.findAll(zh.getValue(key)).map { it.value }.toList())
            for (strings in listOf(en, zh)) {
                val value = strings.getValue(key)
                assertTrue(key, value.isNotBlank())
                assertFalse(key, value.contains('\uFFFD'))
                assertFalse(key, placeholders.replace(value, "").contains('%'))
            }
            assertFalse(key, en.getValue(key).any { it in '\u4e00'..'\u9fff' })
        }
        assertEquals(listOf("%1\$s"), placeholders.findAll(en.getValue("discovery_pending_sources")).map { it.value }.toList())
        for (folder in listOf("values", "values-zh-rCN")) {
            for (ids in listOf(admission.values, outcomes.values, evidence.values)) {
                assertEquals(ids.size, ids.map { text(folder, it) }.toSet().size)
            }
        }
    }

    @Test fun actionPrecedenceCoversAllFourCombinationsWithoutClaimingSuccess() {
        assertEquals(R.string.discovery_pending_verify_action, pendingVerificationActionLabel(false, false))
        assertEquals(R.string.discovery_pending_verify_attempted, pendingVerificationActionLabel(false, true))
        assertEquals(R.string.discovery_pending_verify_active, pendingVerificationActionLabel(true, false))
        assertEquals(R.string.discovery_pending_verify_active, pendingVerificationActionLabel(true, true))
        assertEquals("Verification attempted this round", text("values", pendingVerificationActionLabel(false, true)))
        assertEquals("本轮已尝试验证", text("values-zh-rCN", pendingVerificationActionLabel(false, true)))
        assertEquals("Configured", text("values", discoverySourceAdmissionLabel(SourceAdmissionStatus.READY)))
        assertEquals("已配置", text("values-zh-rCN", discoverySourceAdmissionLabel(SourceAdmissionStatus.READY)))
    }

    @Test fun everyOutcomeEvidencePairRemainsIndependentInBothLocalesIncludingSyntheticPairs() {
        for (folder in listOf("values", "values-zh-rCN")) {
            val all = EngineOutcome.entries.flatMap { outcome -> EngineEvidence.entries.map { kind ->
                val value = VerificationSummary("local", outcome, kind)
                val before = value.copy()
                summary(folder, value).also {
                    assertTrue(it.contains(text(folder, outcomes.getValue(outcome))))
                    assertTrue(it.contains(text(folder, evidence.getValue(kind))))
                    assertTrue(it.contains(if (folder == "values") "Playback, download and saving are not established." else "不代表可播放、下载或保存。"))
                    assertEquals(before, value)
                }
            } }
            assertEquals(24, all.toSet().size)
        }
    }

    @Test fun localeChangeKeepsMixedCandidateFactsAndLiteralExternalText() {
        val metadata = SourceReportedCandidateMetadata("50% %1\$s 标题", "100% 网站", "说明%2\$s".repeat(80))
        val candidate = PendingVideoCandidate("candidate", "https://video.example/watch", metadata, setOf("来源%2\$s", "alpha%"))
        val mixed = listOf(candidate,
            candidate.copy(candidateId = "failed", verification = VerificationSummary("local", EngineOutcome.ERROR, EngineEvidence.NONE)),
            candidate.copy(candidateId = "page", verification = VerificationSummary("local", EngineOutcome.PAGE_EVIDENCE, EngineEvidence.HTML_VIDEO)))
        val before = mixed.map { it.copy() }
        for (folder in listOf("values", "values-zh-rCN")) {
            val strings = pendingResourceStrings(folder)
            val locale = if (folder == "values") Locale.US else Locale.SIMPLIFIED_CHINESE
            val sourceText = String.format(locale, strings.getValue("discovery_pending_sources"), candidate.provenance.sorted().joinToString())
            assertEquals((if (folder == "values") "Sources: " else "来源：") + "alpha%, 来源%2\$s", sourceText)
            assertEquals(metadata.title, candidate.sourceReported?.title ?: pendingResourceStrings(folder, "flow_states.xml").getValue("flow_title_unknown"))
            assertEquals(metadata.sourceName, candidate.sourceReported?.sourceName ?: strings.getValue("discovery_pending_site_unknown"))
            assertEquals(320, candidate.sourceReported?.description?.take(320)?.length)
            val noMetadata = candidate.copy(sourceReported = null)
            assertEquals(if (folder == "values") "Unknown title" else "标题未知",
                noMetadata.sourceReported?.title ?: pendingResourceStrings(folder, "flow_states.xml").getValue("flow_title_unknown"))
            assertEquals(if (folder == "values") "Unknown website" else "网站未知",
                noMetadata.sourceReported?.sourceName ?: strings.getValue("discovery_pending_site_unknown"))
            // Null-only fallback: intentionally empty external values are not reinterpreted.
            val empty = candidate.copy(sourceReported = SourceReportedCandidateMetadata("", "", ""))
            assertEquals("", empty.sourceReported?.title ?: "fallback")
            assertEquals("", empty.sourceReported?.sourceName ?: "fallback")
            assertEquals(listOf(R.string.discovery_pending_verify_action, R.string.discovery_pending_verify_attempted,
                R.string.discovery_pending_verify_attempted), mixed.map { pendingVerificationActionLabel(it.verificationActive, it.verification != null) })
            mixed.mapNotNull { it.verification }.forEach { summary(folder, it) }
            assertEquals(before, mixed)
        }
    }

    @Test fun filteredPageEvidenceRemainsAnAttemptedPendingCandidateWithNeutralHeading() {
        for (rejectMetadata in listOf(false, true)) {
            val clock = EpochClock { 1_000L }
            val runtime = QueuedRuntime()
            val provider = object : VideoSearchProvider {
                override val descriptor = ProviderDescriptor("source", "1")
                override fun search(request: SearchRequest, operation: DiscoveryOperation) =
                    ProviderPage(candidates = listOf(CandidateUrl("https://video.example/watch")), exhausted = true)
            }
            val timer = object : DiscoveryTimer {
                override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle { }
            }
            val filters = SearchFilters(safeSearch = false, minimumDurationMs = 2_000L)
            val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(provider)), runtime, InMemoryDiscoveryStore(), timer, clock)
            coordinator.use {
                coordinator.setVisibility(true, true)
                val stamp = coordinator.start("session", SearchProfile("profile", "query", filters = filters))
                val initial = runtime.calls.single()
                val initialPage = ValidationPipeline(clock = clock, retainUnvalidatedCandidates = true).validate(
                    "source", initial.provider.search(initial.request, initial.operation), filters, initial.operation)
                initial.callback(ProviderOutcome.Success(initialPage))
                val candidate = coordinator.pendingCandidates().single()
                assertNull(candidate.verification)
                assertTrue(coordinator.verifyPending(stamp, candidate.candidateId))
                assertTrue(coordinator.pendingCandidates().single().verificationActive)
                val explicit = runtime.calls.last()
                val pageEvidence = VerificationSummary("local", EngineOutcome.PAGE_EVIDENCE, EngineEvidence.HTML_VIDEO)
                val validator = object : CandidateValidator {
                    override fun validate(candidate: CandidateUrl, operation: DiscoveryOperation): RawVideoResult? =
                        error("Pipeline must retain the evidence summary")
                    override fun validateEvidence(candidate: CandidateUrl, operation: DiscoveryOperation) = CandidateValidation(
                        RawVideoResult(candidate.pageUrl, true, title = if (rejectMetadata) "x".repeat(513) else "Title",
                            durationMs = if (rejectMetadata) 3_000L else 1_000L), pageEvidence)
                }
                val page = ValidationPipeline(validator, clock = clock, retainFailedValidationCandidates = true).validate(
                    "source", explicit.provider.search(explicit.request, explicit.operation), filters, explicit.operation)
                assertTrue(page.results.isEmpty())
                assertEquals(pageEvidence, page.verificationReports.single().summary)
                explicit.callback(ProviderOutcome.Success(page))
                val pending = coordinator.pendingCandidates().single()
                assertEquals(pageEvidence, pending.verification)
                assertFalse(pending.verificationActive)
                assertEquals(0, coordinator.status().availableCount)
                assertFalse(coordinator.verifyPending(stamp, pending.candidateId))
                assertEquals(2, runtime.calls.size)
                assertEquals(R.string.discovery_pending_verify_attempted,
                    pendingVerificationActionLabel(pending.verificationActive, pending.verification != null))
                for (folder in listOf("values", "values-zh-rCN")) {
                    assertEquals(if (folder == "values")
                        "Candidates reported by search sources; see each verification result below." else
                        "搜索来源报告的候选；各项验证结果见下方。",
                        pendingResourceStrings(folder).getValue("discovery_pending_notice_candidates"))
                    val displayed = summary(folder, requireNotNull(pending.verification))
                    assertTrue(displayed.contains(text(folder, R.string.discovery_verification_outcome_page_evidence)))
                    assertTrue(displayed.contains(text(folder, R.string.discovery_verification_evidence_html_video)))
                }
            }
        }
    }

    private class QueuedRuntime : ProviderRuntime {
        data class Call(val provider: VideoSearchProvider, val request: SearchRequest,
            val operation: DiscoveryOperation, val callback: (ProviderOutcome) -> Unit)
        val calls = mutableListOf<Call>()
        override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation,
            callback: (ProviderOutcome) -> Unit): CancellationHandle {
            calls += Call(provider, request, operation, callback)
            return CancellationHandle { operation.cancellation.cancel() }
        }
    }
}
