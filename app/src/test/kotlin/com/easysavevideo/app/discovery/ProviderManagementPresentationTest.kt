package com.easysavevideo.app.discovery

import com.easysavevideo.commons.maintenance.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderManagementPresentationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun snapshot() = ProviderRepository(temporary.newFolder()).use { it.snapshot() }
    private val configured = ProviderManagementConfiguration(contactConfigured = true)
    private fun present(snapshot: ProviderRepositorySnapshot, completed: ProviderManagementOperation? = null,
        config: ProviderManagementConfiguration = configured, enabled: Boolean = true) =
        ProviderManagementPresentationPolicy.present(ProviderManagementState(active = true, snapshot = snapshot,
            completed = completed), config, enabled)

    @Test fun zeroProviderBuildStillShowsMissingRootsAndEndpoint() {
        val view = present(snapshot(), config = ProviderManagementConfiguration(contactConfigured = false))
        assertTrue(view.summary.contains("零来源模式"))
        assertTrue(view.configuration.any { it.contains("包验签根：缺失") })
        assertTrue(view.configuration.any { it.contains("策略验签根：缺失") })
        assertTrue(view.configuration.any { it.contains("无法检查远程更新") })
        assertTrue(view.versions.any { it.contains("内置 v0") })
    }

    @Test fun failedInspectionDoesNotInventZeroVersionsOrHealthyState() {
        val view = ProviderManagementPresentationPolicy.present(ProviderManagementState(active = true,
            problem = ProviderManagementProblem.LOCAL_UNAVAILABLE), configured, true)
        assertEquals("当前维护状态未知", view.summary)
        assertTrue(view.versions.single().contains("未知"))
        assertTrue(view.health.isEmpty())
        assertFalse(view.canRejectCandidate)
        assertFalse(view.canRollback)
    }

    @Test fun repositorySuccessWhileDisabledExplicitlyRemainsDisabled() {
        val view = present(snapshot().copy(state = ProviderRepositoryState.DISABLED,
            error = ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED), ProviderManagementOperation.REJECT_CANDIDATE)
        assertTrue(view.summary.contains("停用"))
        assertEquals("本地事务已完成；当前维护授权仍停用。", view.notice)
        assertFalse(view.notice!!.contains("恢复成功"))
    }

    @Test fun enabledSuccessDoesNotPromiseHealthOrCompatibility() {
        val view = present(snapshot(), ProviderManagementOperation.ROLLBACK)
        assertTrue(view.notice!!.contains("不代表网站兼容或持续健康"))
    }

    @Test fun localDisableRemainsVisibleWhenRepositoryAllowsBundled() {
        val view = present(snapshot(), enabled = false)
        assertTrue(view.summary.contains("本地来源已关闭"))
        assertTrue(view.configuration.contains("本地 Commons 开关：关"))
    }

    @Test fun pendingCandidateAloneDoesNotOfferRecovery() {
        val view = present(snapshot().copy(pendingVersion = 9, canRejectCandidate = false))
        assertFalse(view.canRejectCandidate)
        assertTrue(view.versions.any { it == "候选版本：v9" })
    }

    @Test fun recoverableInterruptedCandidateOffersOnlyRepositoryGrantedAction() {
        val view = present(snapshot().copy(state = ProviderRepositoryState.DISABLED, pendingVersion = 9,
            canRejectCandidate = true, canRollback = false, error = ProviderRepositoryError.RECOVERY_REQUIRED))
        assertTrue(view.canRejectCandidate)
        assertFalse(view.canRollback)
        assertTrue(view.versions.any { it.contains("中断候选需显式拒绝") })
    }

    @Test fun busyOrHiddenOrWorkerRejectedStateDisablesBothActions() {
        val snapshot = snapshot().copy(pendingVersion = 9, canRejectCandidate = true, canRollback = true)
        listOf(
            ProviderManagementState(active = true, busy = true, snapshot = snapshot),
            ProviderManagementState(active = false, snapshot = snapshot),
            ProviderManagementState(active = true, snapshot = snapshot, problem = ProviderManagementProblem.WORKER_BUSY),
        ).forEach { state ->
            val view = ProviderManagementPresentationPolicy.present(state, configured, true)
            assertFalse(view.canRejectCandidate); assertFalse(view.canRollback)
        }
    }

    @Test fun versionsExposeSeparateAnchorsAndDoNotCallDisabledLkgTrialPassed() {
        val view = present(snapshot().copy(highestAcceptedVersion = 12, highestAcceptedPolicyVersion = 7,
            minimumSafePackageVersion = 8, lastKnownGoodVersion = 10))
        assertTrue(view.versions.contains("签名包高水位：v12"))
        assertTrue(view.versions.contains("安全策略版本：v7"))
        assertTrue(view.versions.contains("最低安全包版本：v8"))
        assertTrue(view.versions.any { it.contains("LKG，可为停用包") && it.endsWith("v10") })
    }

    @Test fun probationShowsBoundedCountersAndNoStatisticalGuarantee() {
        val view = present(snapshot().copy(state = ProviderRepositoryState.CANDIDATE,
            health = ProviderHealthSnapshot(ProviderHealthPhase.PROBATION, consecutiveSuccesses = 2,
                consecutiveStructureFailures = 0, conclusiveObservations = 4, totalObservations = 6,
                startedAtEpochMillis = 0, deadlineEpochMillis = 86_400_000)))
        assertTrue(view.health.contains("连续新鲜成功：2 / 3"))
        assertTrue(view.health.contains("连续结构异常：0 / 3"))
        assertTrue(view.health.contains("确定性观察：4 / 12"))
        assertTrue(view.health.contains("总观察：6 / 20"))
        assertTrue(view.health.any { it.contains("1970-01-02T00:00:00Z") })
        assertTrue(ProviderManagementPresentationPolicy.LIMIT_NOTICE.contains("没有统计健康保证"))
    }

    @Test fun healthyPhaseDoesNotDisplayResetZeroAsOngoingFailedProbation() {
        val view = present(snapshot().copy(health = ProviderHealthSnapshot(ProviderHealthPhase.HEALTHY)))
        assertTrue(view.health.any { it.contains("非持续健康保证") })
        assertFalse(view.health.any { it.contains("连续新鲜成功：0 / 3") })
    }

    @Test fun suspendedObservationBudgetDoesNotImplyBadPackage() {
        val view = present(snapshot().copy(health = ProviderHealthSnapshot(ProviderHealthPhase.SUSPENDED),
            error = ProviderRepositoryError.HEALTH_BUDGET_EXHAUSTED))
        assertTrue(view.health.any { it.contains("不能据此晋级或判定坏包") })
    }

    @Test fun quarantineExplainsDistinctReasonsAndDoesNotPublishArbitraryDigestStrings() {
        val secret = "https://private.invalid/?query=secret-title"
        val view = present(snapshot().copy(currentDigest = secret, quarantinedVersions = listOf(
            ProviderQuarantinedVersion(3, "a".repeat(64), 2, null, ProviderQuarantineReason.STRUCTURE_FAILURES),
            ProviderQuarantinedVersion(4, secret, 2, null, ProviderQuarantineReason.FIXED_SELF_TEST_FAILED),
            ProviderQuarantinedVersion(5, "b".repeat(64), 2, null, ProviderQuarantineReason.EXPLICIT_REJECTION),
        )))
        assertTrue(view.quarantine.any { it.contains("连续解析结构异常") })
        assertTrue(view.quarantine.any { it.contains("固定离线自测未通过") })
        assertTrue(view.quarantine.any { it.contains("本地显式拒绝候选") })
        assertFalse((view.versions + view.quarantine).joinToString().contains(secret))
        assertTrue(ProviderManagementPresentationPolicy.QUARANTINE_NOTICE.contains("不证明代码损坏或恶意"))
    }

    @Test fun fixedSelfTestAndLifecycleLimitsAreExplicit() {
        assertTrue(ProviderManagementPresentationPolicy.SELF_TEST_NOTICE.contains("离线解析结构校验"))
        assertTrue(ProviderManagementPresentationPolicy.SELF_TEST_NOTICE.contains("不代表真实网站兼容"))
        assertTrue(ProviderManagementPresentationPolicy.LIFECYCLE_NOTICE.contains("已提交的本地事务不会撤回"))
        assertTrue(ProviderManagementPresentationPolicy.RECOVERY_NOTICE.contains("累计撤销不会被清除"))
    }

    @Test fun allTechnicalErrorsHaveSafeUserText() {
        ProviderRepositoryError.entries.forEach { error ->
            val text = ProviderManagementPresentationPolicy.errorText(error)
            assertTrue(text.isNotBlank())
            assertFalse(text.contains("http"))
        }
    }
    @Test fun unreadableRepositoryDoesNotPresentDefaultZeroAnchorsOrEmptyIsolationAsKnown() {
        val base = snapshot()
        listOf(ProviderRepositoryError.CORRUPT_STATE, ProviderRepositoryError.STORAGE_FAILURE,
            ProviderRepositoryError.BUSY, ProviderRepositoryError.CLOSED).forEach { error ->
            val view = present(base.copy(error = error, canRollback = true,
                pendingVersion = 7, canRejectCandidate = true))
            assertTrue(view.summary.contains("不可确认"))
            assertFalse(view.versions.any { it.contains("高水位：v0") || it.contains("内置 v0") })
            assertTrue(view.versions.any { it.contains("均不能据此确定") })
            assertTrue(view.health.single().contains("不可确认"))
            assertTrue(view.quarantine.single().contains("不能视为没有隔离记录"))
            assertFalse(view.canRejectCandidate)
            assertFalse(view.canRollback)
        }
    }

}
