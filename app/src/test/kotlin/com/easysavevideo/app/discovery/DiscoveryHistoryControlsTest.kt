package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryHistoryControlsTest {
    private val checkpoint = DiscoveryCheckpoint(SessionStamp("active-discovery", 7), SearchProfile("profile-one", "cats"),
        PoolState(viewRevision = 4))
    private val target = requireNotNull(DiscoveryPresentationPolicy.controlTarget(checkpoint, 3))
    private val providers = setOf("source-one", "source-two")

    private fun request(action: DiscoveryLocalAction = DiscoveryLocalAction.CLEAR_CURRENT_HISTORY, provider: String? = null) =
        DiscoveryControlRequest(target, action, provider)

    @Test fun capturedProfileSessionRevisionAndControllerGenerationMustAllMatch() {
        val mismatches = listOf(null, target.copy(profileId = "profile-two"),
            target.copy(stamp = SessionStamp("another-session", target.stamp.epoch)),
            target.copy(stamp = target.stamp.copy(epoch = target.stamp.epoch + 1)),
            target.copy(viewRevision = target.viewRevision + 1),
            target.copy(controllerGeneration = target.controllerGeneration + 1))
        DiscoveryLocalAction.entries.forEach { action ->
            val command = request(action, "source-one".takeIf { action == DiscoveryLocalAction.RESET_PROVIDER_PROGRESS })
            mismatches.forEach { current ->
                var writes = 0
                assertEquals(DiscoveryControlOutcome.STALE, DiscoveryPresentationPolicy.executeControl(command, current, providers,
                    { writes++; true }, { _, _ -> writes++; true }))
                assertEquals(0, writes)
            }
        }
    }

    @Test fun currentClearUsesCapturedProfileAndAllClearUsesNullWithoutResettingProgress() {
        val cleared = mutableListOf<String?>()
        val reset: (String, String) -> Boolean = { _, _ -> fail("History clear must not reset progress"); false }
        assertEquals(DiscoveryControlOutcome.COMMITTED, DiscoveryPresentationPolicy.executeControl(request(), target, providers,
            { cleared += it; true }, reset))
        assertEquals(DiscoveryControlOutcome.COMMITTED, DiscoveryPresentationPolicy.executeControl(
            request(DiscoveryLocalAction.CLEAR_ALL_HISTORY), target, providers, { cleared += it; true }, reset))
        assertEquals(listOf("profile-one", null), cleared)
    }

    @Test fun providerResetUsesCapturedProfileAndExactlyOneProviderWithoutClearingHistory() {
        val resets = mutableListOf<Pair<String, String>>()
        val command = request(DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, "source-two")
        assertEquals(DiscoveryControlOutcome.COMMITTED, DiscoveryPresentationPolicy.executeControl(command, target, providers,
            { fail("Progress reset must not clear history"); false }, { profile, provider -> resets += profile to provider; true }))
        assertEquals(listOf("profile-one" to "source-two"), resets)
        assertFalse(DiscoveryPresentationPolicy.validControl(command, target, setOf("source-one")))
    }

    @Test fun falseMutationHasFailureOutcomeAndNeverSuccessText() {
        DiscoveryLocalAction.entries.forEach { action ->
            val command = request(action, "source-one".takeIf { action == DiscoveryLocalAction.RESET_PROVIDER_PROGRESS })
            val outcome = DiscoveryPresentationPolicy.executeControl(command, target, providers, { false }, { _, _ -> false })
            assertEquals(DiscoveryControlOutcome.FAILED, outcome)
            val text = DiscoveryPresentationPolicy.controlOutcomeText(action, outcome)
            assertTrue(text.contains("未成功"))
            assertFalse(text.contains("已清除"))
            assertFalse(text.contains("已重置"))
            val stale = DiscoveryPresentationPolicy.controlOutcomeText(action, DiscoveryControlOutcome.STALE)
            assertTrue(stale.contains("未执行"))
            assertFalse(stale.contains("已清除"))
            assertFalse(stale.contains("已重置"))
        }
    }

    @Test fun mutationExceptionCannotBeConvertedToSuccess() {
        var returned = false
        try {
            DiscoveryPresentationPolicy.executeControl(request(), target, providers,
                { throw IllegalStateException("local failure") }, { _, _ -> true })
            returned = true
        } catch (_: IllegalStateException) { /* Android's guarded boundary reports an unavailable store. */ }
        assertFalse(returned)
    }

    @Test fun readOnlyStatisticsOrControlRefreshCanNeverMaterializePagesEvenWithPendingDemand() {
        listOf(false, true).forEach { pending ->
            listOf(false, true).forEach { missing ->
                assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(true, pending, missing, true, false, true, true))
            }
        }
        assertTrue(DiscoveryPresentationPolicy.mayFulfillPageDemand(false, true, true, true, false, true, true))
        // A successful/failed local control cancels pending demand before subsequent polling.
        assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(false, false, true, true, false, true, true))
        assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(false, true, false, true, false, true, true))
        assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(false, true, true, false, false, true, true))
        assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(false, true, true, true, true, true, true))
        assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(false, true, true, true, false, false, true))
        assertFalse(DiscoveryPresentationPolicy.mayFulfillPageDemand(false, true, true, true, false, true, false))
    }

    @Test fun everyConfirmationDisclosesRetentionReappearanceAndMediaIndependence() {
        DiscoveryLocalAction.entries.forEach { action ->
            val text = DiscoveryPresentationPolicy.controlConsequence(
                request(action, "source-one".takeIf { action == DiscoveryLocalAction.RESET_PROVIDER_PROGRESS }))
            assertTrue(text.contains("再次")); assertTrue(text.contains("现有页面"))
            assertTrue(text.contains("媒体文件")); assertTrue(text.contains("Vault"))
            assertTrue(text.contains("无法撤销"))
        }
        val progress = DiscoveryPresentationPolicy.controlConsequence(request(DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, "source-one"))
        assertTrue(progress.contains("source-one")); assertTrue(progress.contains("其他来源与其他资料"))
        assertTrue(progress.contains("现在不会请求来源")); assertTrue(progress.contains("Seen 历史"))
    }

    @Test fun capacityNoticeDisclosesActualLimitsNoAgeExpiryAndProtectedCurrentPages() {
        val policy = DiscoveryRetentionPolicy()
        val text = DiscoveryPresentationPolicy.retentionNotice(policy)
        listOf(policy.maxProfiles, policy.maxSeenAliases, policy.maxProgress, policy.maxCheckpoints).forEach {
            assertTrue(text.contains(it.toString()))
        }
        assertTrue(text.contains("不按天数过期")); assertTrue(text.contains("当前已生成页面的数据受保护"))
        assertTrue(text.contains("旧结果可能再次出现")); assertTrue(text.contains("独立保存"))
        assertTrue(text.contains("不会因此补记")); assertTrue(text.contains("不会删除媒体文件或 Vault"))
    }

    @Test fun statisticsAreLocalRecordCountsNotCompletionOrMediaCounts() {
        val text = DiscoveryPresentationPolicy.statisticsText("当前资料", HistoryStatistics(1, 2, 3, 4, 5))
        assertTrue(text.contains("1 个资料")); assertTrue(text.contains("2 个会话检查点"))
        assertTrue(text.contains("3 个 Seen 身份")); assertTrue(text.contains("4 条 Seen 别名"))
        assertTrue(text.contains("5 条来源进度")); assertFalse(text.contains("%"))
    }

    @Test fun controlSnapshotsAreRedactedAndCannotAccidentallyExposeQueryIdentifiers() {
        assertEquals("DiscoveryControlTarget([redacted])", target.toString())
        assertEquals("DiscoveryControlRequest([redacted])", request().toString())
        assertNull(DiscoveryPresentationPolicy.controlTarget(null, 1))
    }

    @Test fun malformedActionCannotCarryAnUnrelatedProvider() {
        try {
            request(DiscoveryLocalAction.CLEAR_ALL_HISTORY, "source-one")
            fail("History action must not carry a provider target")
        } catch (_: IllegalArgumentException) { }
        try {
            request(DiscoveryLocalAction.RESET_PROVIDER_PROGRESS)
            fail("Progress reset requires one explicit provider")
        } catch (_: IllegalArgumentException) { }
    }
}
