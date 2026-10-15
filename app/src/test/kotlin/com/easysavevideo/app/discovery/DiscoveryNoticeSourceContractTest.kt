package com.easysavevideo.app.discovery

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Keeps typed notices at their existing producer sites; broader ownership tests remain unchanged. */
class DiscoveryNoticeSourceContractTest {
    private fun source(path: String) = File(discoveryPresentationSourceRoot(), path).readText()

    @Test fun bothStateFieldsAreTypedAndEveryProducerIsFinite() {
        val controller = source("discovery/AndroidDiscoveryController.kt")
        assertTrue(controller.contains("val notice: DiscoveryNotice? = null"))
        assertTrue(controller.contains("private var notice: DiscoveryNotice? = null"))
        assertFalse(controller.contains("notice: String?"))
        val fixed = listOf("SOURCE_CONFIGURATION_CHECKING", "VERIFICATION_NOT_STARTED", "VIEW_SETTINGS_CHANGED",
            "INVALID_QUERY", "SORT_CHANGED", "STORAGE_RETRY_UNAVAILABLE")
        for (notice in fixed) assertEquals(notice, 1, Regex(Regex.escape("notice = DiscoveryNotice.$notice")).findAll(controller).count())
        assertEquals(6, Regex("notice = DiscoveryNotice\\.").findAll(controller).count())
        assertEquals(1, Regex(Regex.escape("notice = DiscoveryPresentationPolicy.controlOutcomeNotice(result.operation.request.action, result.outcome)")).findAll(controller).count())
        assertFalse(controller.contains("controlOutcomeText("))
        assertFalse(Regex("notice\\s*=\\s*\"").containsMatchIn(controller))
        val fence = source("discovery/DiscoverySourceUiFence.kt")
        assertTrue(fence.contains("notice = DiscoveryNotice.STORAGE_RECOVERY_UNAVAILABLE"))
        for (text in listOf(controller, fence)) {
            for (forbidden in listOf("noticeText(", "discoveryPresentationCopy(", "R.string.discovery_notice_", ".message")) {
                assertFalse(forbidden, text.contains(forbidden))
            }
        }
    }

    @Test fun existingNoticeLifetimeAndReadOnlyControlPublicationStayOwnedByTheController() {
        val controller = source("discovery/AndroidDiscoveryController.kt")
        assertEquals(6, Regex("notice = null").findAll(controller).count())
        assertTrue(controller.contains("notice = notice,"))
        val fence = controller.substringAfter("private fun fenceSourceConfiguration()").substringBefore("private fun loadedSourceConfiguration")
        assertTrue(fence.contains("state = state.copy("))
        assertTrue(fence.contains("notice = DiscoveryNotice.SOURCE_CONFIGURATION_CHECKING"))
        val afterWork = controller.substringAfter("afterWork = { result ->").substringBefore("\n    fun ")
        assertTrue(afterWork.contains("result.operation.request.action, result.outcome"))
        assertTrue(afterWork.contains("if (result.outcome != DiscoveryControlOutcome.STALE)"))
        assertTrue(afterWork.contains("pageDemandPending = false"))
        assertTrue(afterWork.contains("nextPageDemand.cancel()"))
        assertTrue(afterWork.contains("refresh(readOnly = true)"))
        assertFalse(afterWork.contains("executeControl("))
    }

    @Test fun noticeAndDurationResolversArePureExplicitCopyMappings() {
        val policy = source("discovery/DiscoveryPresentationPolicy.kt")
        val mappings = policy.substringAfter("    fun controlOutcomeNotice(").substringBefore("    fun retentionNotice(")
        for (forbidden in listOf("Context", "Resources", "AndroidDiscoveryController", "Locale.getDefault", "remember(", "LaunchedEffect",
            "recordSeen(", "onOpen(", "stopRecording(", "clearHistory(", "resetProgress(", "submit(", "java.net.")) {
            assertFalse(forbidden, mappings.contains(forbidden))
        }
        assertFalse(Regex("\\bvar\\b").containsMatchIn(mappings))
        assertEquals(12, Regex("DiscoveryNotice\\.[A-Z_]+ -> copy\\.").findAll(mappings).count())
        assertTrue(mappings.contains("noticeText(controlOutcomeNotice(action, outcome), DiscoveryPresentationCopy())"))
        assertTrue(mappings.contains("copy.format(copy.duration, durationMs / 60_000, durationMs / 1_000 % 60)"))
        assertFalse(mappings.contains("else ->"))
        assertFalse(mappings.contains("toInt()"))
    }

    @Test fun serializedCheckpointAndIndependentPrivacyReceiptDoNotAcquireDisplayNotices() {
        val codec = source("storage/DiscoveryCheckpointCodec.kt")
        assertFalse(codec.contains("DiscoveryNotice"))
        assertFalse(codec.contains("DiscoveryUiState"))
        assertFalse(codec.contains("discovery_notice_"))
        val privacy = source("ui/shell/DiscoveryPrivacySettingsDetail.kt")
        assertFalse(privacy.contains("DiscoveryNotice"))
        assertFalse(privacy.contains("controlOutcomeNotice"))
        assertFalse(privacy.contains("discovery_notice_"))
    }
}
