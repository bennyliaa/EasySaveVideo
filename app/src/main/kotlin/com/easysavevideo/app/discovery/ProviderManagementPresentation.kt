package com.easysavevideo.app.discovery

import com.easysavevideo.commons.maintenance.ProviderHealthLimits
import com.easysavevideo.commons.maintenance.ProviderHealthPhase
import com.easysavevideo.commons.maintenance.ProviderQuarantineReason
import com.easysavevideo.commons.maintenance.ProviderRepositoryError
import com.easysavevideo.commons.maintenance.ProviderRepositorySnapshot
import com.easysavevideo.commons.maintenance.ProviderRepositoryState
import java.time.Instant

/** Values describe installed app wiring, never infer trust configuration from a package snapshot. */
data class ProviderManagementConfiguration(
    val contactConfigured: Boolean,
    val packageRootsConfigured: Boolean = false,
    val policyRootsConfigured: Boolean = false,
    val updateEndpointConfigured: Boolean = false,
)

data class ProviderManagementPresentation(
    val summary: String,
    val configuration: List<String>,
    val versions: List<String>,
    val health: List<String>,
    val quarantine: List<String>,
    val notice: String?,
    val canRejectCandidate: Boolean,
    val canRollback: Boolean,
)

object ProviderManagementPresentationPolicy {
    const val SELF_TEST_NOTICE = "固定自测只做离线解析结构校验，不访问网站，也不代表真实网站兼容。"
    const val QUARANTINE_NOTICE = "结构异常隔离表示当前包与环境组合中观察到连续解析结构异常；不证明代码损坏或恶意。手动拒绝与固定自测失败会分别标明原因。"
    const val LIMIT_NOTICE = "这些阈值是本地有界策略，没有统计健康保证。稳定版本不做长期健康监控；网络中断等不能据此判为坏包。"
    const val RECOVERY_NOTICE = "恢复只使用仓库当前允许的候选拒绝或回滚。签名包高水位、策略版本、安全下限和累计撤销不会被清除；事务完成后仍可能停用。"
    const val LIFECYCLE_NOTICE = "关闭页面或进入后台会撤销本次显示并请求取消；已提交的本地事务不会撤回。重开后重新读取，旧操作退出前不会开启第二个管理任务。"

    fun present(state: ProviderManagementState, config: ProviderManagementConfiguration,
        localEnabled: Boolean, text: ProviderCopyText = ProviderCopyText.CHINESE): ProviderManagementPresentation {
        val snapshot = state.snapshot
        val configuration = listOf(
            if (config.contactConfigured) text.get(ProviderCopy.C5) else text.get(ProviderCopy.C6),
            text.get(ProviderCopy.C9, if (localEnabled) text.get(ProviderCopy.C7) else text.get(ProviderCopy.C8)),
            if (config.packageRootsConfigured) text.get(ProviderCopy.C10) else text.get(ProviderCopy.C11),
            if (config.policyRootsConfigured) text.get(ProviderCopy.C12) else text.get(ProviderCopy.C13),
            if (config.updateEndpointConfigured) text.get(ProviderCopy.C14) else text.get(ProviderCopy.C15),
        )
        val uncertain = snapshot?.let(::unreadable) == true
        val available = !uncertain && state.active && !state.busy && state.problem !in setOf(
            ProviderManagementProblem.WORKER_BUSY, ProviderManagementProblem.LOCAL_UNAVAILABLE)
        return ProviderManagementPresentation(
            summary = summary(snapshot, config, localEnabled, text), configuration = configuration,
            versions = (snapshot?.let { versions(it, text) } ?: listOf(text.get(ProviderCopy.C16))) +
                listOfNotNull(state.observedAtEpochMillis?.let { text.get(ProviderCopy.C17, Instant.ofEpochMilli(it)) }),
            health = snapshot?.let { health(it, text) }.orEmpty(),
            quarantine = if (uncertain) listOf(text.get(ProviderCopy.C18)) else snapshot?.quarantinedVersions?.map { entry ->
                val reason = when (entry.reason) {
                    ProviderQuarantineReason.FIXED_SELF_TEST_FAILED -> text.get(ProviderCopy.C19)
                    ProviderQuarantineReason.STRUCTURE_FAILURES -> text.get(ProviderCopy.C20)
                    ProviderQuarantineReason.EXPLICIT_REJECTION -> text.get(ProviderCopy.C21)
                }
                text.get(ProviderCopy.C22, entry.version, entry.policyVersion, reason, fingerprint(entry.packageDigest, text))
            }.orEmpty(),
            notice = notice(state, text),
            canRejectCandidate = available && snapshot?.canRejectCandidate == true && snapshot.pendingVersion != null,
            canRollback = available && snapshot?.canRollback == true,
        )
    }

    private fun summary(snapshot: ProviderRepositorySnapshot?, config: ProviderManagementConfiguration,
        localEnabled: Boolean, text: ProviderCopyText): String {
        if (snapshot == null) return text.get(ProviderCopy.C23)
        val repository = when {
            unreadable(snapshot) -> text.get(ProviderCopy.C24)
            !snapshot.policy.enabled || snapshot.state == ProviderRepositoryState.DISABLED -> text.get(ProviderCopy.C25)
            snapshot.state == ProviderRepositoryState.CANDIDATE -> text.get(ProviderCopy.C26)
            snapshot.state == ProviderRepositoryState.BUNDLED -> text.get(ProviderCopy.C27)
            else -> text.get(ProviderCopy.C28)
        }
        return when {
            !config.contactConfigured -> text.get(ProviderCopy.C29, repository)
            !localEnabled -> text.get(ProviderCopy.C30, repository)
            else -> repository
        }
    }

    private fun unreadable(snapshot: ProviderRepositorySnapshot): Boolean = snapshot.error in setOf(
        ProviderRepositoryError.CORRUPT_STATE, ProviderRepositoryError.STORAGE_FAILURE,
        ProviderRepositoryError.BUSY, ProviderRepositoryError.CLOSED)

    private fun versions(snapshot: ProviderRepositorySnapshot, text: ProviderCopyText): List<String> {
        if (unreadable(snapshot)) return listOf(
            text.get(ProviderCopy.C31),
            text.get(ProviderCopy.C32),
            text.get(ProviderCopy.C33, snapshot.error?.let { errorText(it, text) }, snapshot.error?.name),
        )
        return listOf(
        text.get(ProviderCopy.C36, if (snapshot.policy.packageVersion == 0L) text.get(ProviderCopy.C34) else text.get(ProviderCopy.C35, snapshot.policy.packageVersion)),
        text.get(ProviderCopy.C38, snapshot.activeVersion?.let { "v$it" } ?: text.get(ProviderCopy.C37)),
        text.get(ProviderCopy.C39, snapshot.pendingVersion?.let { "v$it" } ?: text.get(ProviderCopy.C37)),
        text.get(ProviderCopy.C40, snapshot.highestAcceptedVersion),
        text.get(ProviderCopy.C41, snapshot.highestAcceptedPolicyVersion),
        text.get(ProviderCopy.C42, snapshot.minimumSafePackageVersion),
        text.get(ProviderCopy.C43, snapshot.lastKnownGoodVersion?.let { "v$it" } ?: text.get(ProviderCopy.C37)),
        text.get(ProviderCopy.C44, snapshot.state.name, snapshot.generation),
    ) + listOfNotNull(snapshot.currentDigest?.let { text.get(ProviderCopy.C45, fingerprint(it, text)) },
        snapshot.error?.let { text.get(ProviderCopy.C46, errorText(it, text), it.name) })
    }

    private fun health(snapshot: ProviderRepositorySnapshot, text: ProviderCopyText): List<String> {
        if (unreadable(snapshot)) return listOf(text.get(ProviderCopy.C47))
        val health = snapshot.health
        val phase = when (health.phase) {
            ProviderHealthPhase.NONE -> text.get(ProviderCopy.C48)
            ProviderHealthPhase.PROBATION -> text.get(ProviderCopy.C49)
            ProviderHealthPhase.HEALTHY -> text.get(ProviderCopy.C50)
            ProviderHealthPhase.SUSPENDED -> text.get(ProviderCopy.C51)
        }
        val counters = if (health.phase == ProviderHealthPhase.PROBATION || health.phase == ProviderHealthPhase.SUSPENDED) listOf(
            text.get(ProviderCopy.C52, health.consecutiveSuccesses, ProviderHealthLimits.REQUIRED_SUCCESSES),
            text.get(ProviderCopy.C53, health.consecutiveStructureFailures, ProviderHealthLimits.REQUIRED_STRUCTURE_FAILURES),
            text.get(ProviderCopy.C54, health.conclusiveObservations, ProviderHealthLimits.MAX_CONCLUSIVE_OBSERVATIONS),
            text.get(ProviderCopy.C55, health.totalObservations, ProviderHealthLimits.MAX_TOTAL_OBSERVATIONS),
        ) else listOf(text.get(ProviderCopy.C56))
        return listOf(phase) + counters + listOf(
            text.get(ProviderCopy.C57, ProviderHealthLimits.REQUIRED_SUCCESSES, ProviderHealthLimits.REQUIRED_STRUCTURE_FAILURES),
            text.get(ProviderCopy.C58, ProviderHealthLimits.MAX_CONCLUSIVE_OBSERVATIONS, ProviderHealthLimits.MAX_TOTAL_OBSERVATIONS),
            text.get(ProviderCopy.C59, ProviderHealthLimits.MAX_PROBATION_MILLIS / 3_600_000),
        ) + listOfNotNull(health.startedAtEpochMillis?.let { text.get(ProviderCopy.C60, Instant.ofEpochMilli(it)) },
            health.deadlineEpochMillis?.let { text.get(ProviderCopy.C61, Instant.ofEpochMilli(it)) })
    }

    private fun notice(state: ProviderManagementState, text: ProviderCopyText): String? {
        if (state.busy) return text.get(ProviderCopy.C62)
        return when (state.problem) {
            ProviderManagementProblem.WORKER_BUSY -> text.get(ProviderCopy.C63)
            ProviderManagementProblem.LOCAL_UNAVAILABLE -> text.get(ProviderCopy.C64)
            ProviderManagementProblem.ACTION_REJECTED -> text.get(ProviderCopy.C66, state.repositoryError?.let { errorText(it, text) } ?: text.get(ProviderCopy.C65))
            null -> when (state.completed) {
                ProviderManagementOperation.REJECT_CANDIDATE, ProviderManagementOperation.ROLLBACK ->
                    if (state.snapshot?.policy?.enabled != true || state.snapshot?.state == ProviderRepositoryState.DISABLED)
                        text.get(ProviderCopy.C67)
                    else text.get(ProviderCopy.C68)
                else -> null
            }
        }
    }

    private fun fingerprint(value: String, text: ProviderCopyText): String = if (value.matches(Regex("[0-9a-f]{64}"))) value.take(12) + "…" else text.get(ProviderCopy.C69)

    fun errorText(error: ProviderRepositoryError, text: ProviderCopyText = ProviderCopyText.CHINESE): String = when (error) {
        ProviderRepositoryError.CANCELLED -> text.get(ProviderCopy.C70)
        ProviderRepositoryError.RECOVERY_REQUIRED -> text.get(ProviderCopy.C71)
        ProviderRepositoryError.CORRUPT_STATE, ProviderRepositoryError.STORAGE_FAILURE -> text.get(ProviderCopy.C72)
        ProviderRepositoryError.BUSY -> text.get(ProviderCopy.C73)
        ProviderRepositoryError.CLOSED -> text.get(ProviderCopy.C74)
        ProviderRepositoryError.POLICY_DISABLED -> text.get(ProviderCopy.C75)
        ProviderRepositoryError.POLICY_EXPIRED -> text.get(ProviderCopy.C76)
        ProviderRepositoryError.EXPIRED -> text.get(ProviderCopy.C77)
        ProviderRepositoryError.REVOKED_VERSION -> text.get(ProviderCopy.C78)
        ProviderRepositoryError.REVOKED_KEY -> text.get(ProviderCopy.C79)
        ProviderRepositoryError.UNSAFE_VERSION -> text.get(ProviderCopy.C80)
        ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED, ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED -> text.get(ProviderCopy.C81)
        ProviderRepositoryError.DISABLE_BARRIER -> text.get(ProviderCopy.C82)
        ProviderRepositoryError.CLOCK_ROLLBACK, ProviderRepositoryError.INVALID_TIME -> text.get(ProviderCopy.C83)
        ProviderRepositoryError.QUARANTINED -> text.get(ProviderCopy.C84)
        ProviderRepositoryError.SELF_TEST_FAILED -> text.get(ProviderCopy.C19)
        ProviderRepositoryError.HEALTH_BUDGET_EXHAUSTED -> text.get(ProviderCopy.C85)
        ProviderRepositoryError.HEALTH_WINDOW_EXPIRED -> text.get(ProviderCopy.C86)
        ProviderRepositoryError.QUARANTINE_CAPACITY_REACHED -> text.get(ProviderCopy.C87)
        else -> text.get(ProviderCopy.C88)
    }
}
