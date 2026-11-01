package com.easysavevideo.app.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Narrow source contracts; they do not simulate Compose, semantics, native locale or user interaction. */
class PendingCandidateWiringTest {
    private fun source(name: String) = File(pendingResourceAppRoot(), "src/main/kotlin/com/easysavevideo/app/ui/$name.kt").readText()
    private fun compact(value: String) = value.replace(Regex("\\s+"), " ")

    @Test fun cardsResolveOwnedCopyAtCompositionAndKeepExternalContentLiteral() {
        val cards = source("PendingCandidateCards")
        for (key in listOf("notice_candidates", "notice_scope", "badge", "site_unknown", "sources",
            "unverified_metadata", "admission_notice", "open")) {
            assertTrue(key, cards.contains("stringResource(R.string.discovery_pending_$key"))
        }
        for (expression in listOf(
            "candidate.sourceReported?.title ?: stringResource(R.string.flow_title_unknown)",
            "candidate.sourceReported?.sourceName ?: stringResource(R.string.discovery_pending_site_unknown)",
            "stringResource(R.string.discovery_pending_sources, candidate.provenance.sorted().joinToString())",
            "candidate.sourceReported?.description?.take(320)?.let { EsvText(it, style = v.captionTextStyle) }",
            "stringResource(discoverySourceAdmissionLabel(admission[candidate.candidateId] ?: SourceAdmissionStatus.UNCONFIGURED))",
            "stringResource(discoveryVerificationOutcomeLabel(summary.outcome))",
            "stringResource(discoveryVerificationEvidenceLabel(summary.evidence))")) {
            assertTrue(expression, cards.contains(expression))
        }
        assertTrue(cards.contains("stringResource(R.string.discovery_copy_5,"))
        assertTrue(cards.contains("stringResource(R.string.discovery_copy_6,"))
        assertFalse(Regex("Esv(?:Text|Button)\\(\"").containsMatchIn(cards))
        assertFalse(cards.any { it in '\u4e00'..'\u9fff' })
        for (forbidden in listOf(".name", "String.format", ".format(", "ifBlank", "ifEmpty", "uppercase(", "lowercase(")) {
            assertFalse(forbidden, cards.contains(forbidden))
        }
    }

    @Test fun originalCardKeysVerifyPredicateAndCallbacksAreUnchanged() {
        val cards = source("PendingCandidateCards")
        assertTrue(cards.contains("items(candidates, key = { \"pending-\${it.candidateId}\" })"))
        assertTrue(cards.contains("stringResource(pendingVerificationActionLabel(candidate.verificationActive, candidate.verification != null))"))
        assertTrue(cards.contains("onClick = { onVerify(candidate) }, enabled = !candidate.verificationActive && candidate.verification == null"))
        assertTrue(cards.contains("EsvButton(stringResource(R.string.discovery_pending_open), onClick = { onOpen(candidate) }, modifier = Modifier.fillMaxWidth())"))
        assertEquals(1, Regex(Regex.escape("onVerify(candidate)")).findAll(cards).count())
        assertEquals(1, Regex(Regex.escape("onOpen(candidate)")).findAll(cards).count())
        val screen = compact(source("DiscoveryScreen"))
        assertTrue(screen.contains("PendingCandidateCards(state.pendingCandidates, columns, admission = state.pendingAdmission, onEvaluate = onEvaluateCandidate?.let { action -> { candidate -> action(state.controlTarget, candidate, cardNavigationLifecycle) } }, onVerify = { candidate -> controller.verifyCandidate(state.controlTarget, candidate) }) { candidate -> controller.candidateUrl(state.controlTarget, candidate)?.let(onLink) }"))
    }

    @Test fun mainSummaryLocalizesAllThreeArgumentsAndKeepsCurrentNoticeAndAddressGates() {
        val screen = compact(source("DiscoveryScreen"))
        assertTrue(screen.contains("stringResource(R.string.discovery_copy_5, stringResource(discoverySourceAdmissionLabel(state.localSourceAdmission)))"))
        assertTrue(screen.contains("stringResource(R.string.discovery_copy_6, stringResource(discoveryVerificationOutcomeLabel(summary.outcome)), stringResource(discoveryVerificationEvidenceLabel(summary.evidence)))"))
        assertFalse(screen.contains("R.string.discovery_copy_5, state.localSourceAdmission"))
        assertFalse(screen.contains("R.string.discovery_copy_6, summary.outcome, summary.evidence"))
        assertEquals(2, Regex(Regex.escape("state.notice?.let { EsvText(DiscoveryPresentationPolicy.noticeText(it, presentationCopy), style = v.captionTextStyle) }")).findAll(screen).count())
        for (existing in listOf("result.durationMs?.let { DiscoveryPresentationPolicy.durationText(it, presentationCopy) }",
            "addressInput.edit(addressActionCurrent())",
            "addressInput.clear(renderedAddressToken, addressActionCurrent(), renderedAddress.isNotEmpty())",
            "actionCurrent = addressActionCurrent")) assertTrue(existing, screen.contains(existing))
    }

    @Test fun fourSelectorsRemainPureWithExhaustiveTypedMapsAndNoNewOwner() {
        val selector = source("DiscoveryVerificationResources")
        assertEquals(4, Regex("internal fun ").findAll(selector).count())
        assertEquals(3, Regex(Regex.escape("when (value)")).findAll(selector).count())
        assertEquals(15, Regex("(?:SourceAdmissionStatus|EngineOutcome|EngineEvidence)\\.[A-Z_]+ -> R.string.").findAll(selector).count())
        val enumMaps = selector.substringBefore("internal fun pendingVerificationActionLabel")
        assertFalse(enumMaps.contains("else ->"))
        assertTrue(compact(selector).contains("when { active -> R.string.discovery_pending_verify_active attempted -> R.string.discovery_pending_verify_attempted else -> R.string.discovery_pending_verify_action }"))
        for (text in listOf(selector, source("PendingCandidateCards"))) {
            for (forbidden in listOf("remember", "LaunchedEffect", "DisposableEffect", "SideEffect", "mutableState",
                "AndroidDiscoveryController", "java.net.", "java.io.", "android.content", "Log.",
                "verifyCandidate(", "startRecording(", "recordSeen(", ".name", "valueOf(", "UiState")) {
                assertFalse(forbidden, text.contains(forbidden))
            }
        }
        assertFalse(Regex("\\b(?:class|object|var)\\b").containsMatchIn(selector))
    }

    @Test fun sourceAndResourceContractsRegisterEveryDirectlyReadInput() {
        val root = pendingResourceAppRoot()
        val build = File(root, "build.gradle.kts").readText()
        for (name in listOf("DiscoveryVerificationResources", "PendingCandidateCards", "DiscoveryScreen")) {
            val path = "src/main/kotlin/com/easysavevideo/app/ui/$name.kt"
            assertTrue(path, File(root, path).isFile)
            assertTrue(path, build.contains("\"$path\""))
        }
        for (folder in listOf("values", "values-zh-rCN")) {
            for (name in listOf("discovery_pending.xml", "discovery_copy.xml", "flow_states.xml")) {
                val path = "src/main/res/$folder/$name"
                assertTrue(path, File(root, path).isFile)
                if (name == "flow_states.xml") assertTrue(path, build.contains("\"$path\""))
                else assertTrue(name, build.contains("\"$name\""))
            }
        }
        assertTrue(build.contains("for (locale in listOf(\"values\", \"values-zh-rCN\"))"))
        assertTrue(build.contains("include(\"\$locale/\$resource\")"))
        assertTrue(build.contains("inputs.files(contractResourceInputs)"))
        assertTrue(build.contains("inputs.files(contractSourceInputs)"))
    }
}
