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
        localEnabled: Boolean): ProviderManagementPresentation {
        val snapshot = state.snapshot
        val configuration = listOf(
            if (config.contactConfigured) "Commons 服务联系配置：已配置" else "Commons 服务联系配置：缺失，本构建保持零来源模式",
            "本地 Commons 开关：${if (localEnabled) "开" else "关"}",
            if (config.packageRootsConfigured) "包验签根：已配置" else "包验签根：缺失，不能准入新的签名参数包",
            if (config.policyRootsConfigured) "策略验签根：已配置" else "策略验签根：缺失，不能准入新的签名安全策略",
            if (config.updateEndpointConfigured) "更新端点：已配置；本页面仅管理本地状态" else "更新端点：缺失，无法检查远程更新",
        )
        val uncertain = snapshot?.let(::unreadable) == true
        val available = !uncertain && state.active && !state.busy && state.problem !in setOf(
            ProviderManagementProblem.WORKER_BUSY, ProviderManagementProblem.LOCAL_UNAVAILABLE)
        return ProviderManagementPresentation(
            summary = summary(snapshot, config, localEnabled), configuration = configuration,
            versions = (snapshot?.let(::versions) ?: listOf("本地仓库尚未读取，版本与健康状态未知。")) +
                listOfNotNull(state.observedAtEpochMillis?.let { "上次本地读取（UTC）：${Instant.ofEpochMilli(it)}" }),
            health = snapshot?.let(::health).orEmpty(),
            quarantine = if (uncertain) listOf("本地隔离记录不可确认；已有数据保留，不能视为没有隔离记录。") else snapshot?.quarantinedVersions?.map { entry ->
                val reason = when (entry.reason) {
                    ProviderQuarantineReason.FIXED_SELF_TEST_FAILED -> "固定离线自测未通过"
                    ProviderQuarantineReason.STRUCTURE_FAILURES -> "连续解析结构异常"
                    ProviderQuarantineReason.EXPLICIT_REJECTION -> "本地显式拒绝候选"
                }
                "包 v${entry.version} · 策略 v${entry.policyVersion} · $reason\n包指纹 ${fingerprint(entry.packageDigest)}"
            }.orEmpty(),
            notice = notice(state),
            canRejectCandidate = available && snapshot?.canRejectCandidate == true && snapshot.pendingVersion != null,
            canRollback = available && snapshot?.canRollback == true,
        )
    }

    private fun summary(snapshot: ProviderRepositorySnapshot?, config: ProviderManagementConfiguration,
        localEnabled: Boolean): String {
        if (snapshot == null) return "当前维护状态未知"
        val repository = when {
            unreadable(snapshot) -> "维护授权停用，仓库状态不可确认"
            !snapshot.policy.enabled || snapshot.state == ProviderRepositoryState.DISABLED -> "维护授权停用"
            snapshot.state == ProviderRepositoryState.CANDIDATE -> "候选试用中"
            snapshot.state == ProviderRepositoryState.BUNDLED -> "使用内置参数"
            else -> "使用签名参数"
        }
        return when {
            !config.contactConfigured -> "$repository · 零来源模式"
            !localEnabled -> "$repository · 本地来源已关闭"
            else -> repository
        }
    }

    private fun unreadable(snapshot: ProviderRepositorySnapshot): Boolean = snapshot.error in setOf(
        ProviderRepositoryError.CORRUPT_STATE, ProviderRepositoryError.STORAGE_FAILURE,
        ProviderRepositoryError.BUSY, ProviderRepositoryError.CLOSED)

    private fun versions(snapshot: ProviderRepositorySnapshot): List<String> {
        if (unreadable(snapshot)) return listOf(
            "本地仓库状态不可确认，当前版本、高水位、策略、安全下限和 LKG 均不能据此确定。",
            "已有数据保持原状；未恢复为内置参数，也未清除版本锚点。",
            "技术状态：${snapshot.error?.let(::errorText)}（${snapshot.error?.name}）",
        )
        return listOf(
        "当前指针：${if (snapshot.policy.packageVersion == 0L) "内置 v0" else "包 v${snapshot.policy.packageVersion}"}",
        "有效启用的签名版本：${snapshot.activeVersion?.let { "v$it" } ?: "无"}",
        "候选版本：${snapshot.pendingVersion?.let { "v$it" } ?: "无"}",
        "签名包高水位：v${snapshot.highestAcceptedVersion}",
        "安全策略版本：v${snapshot.highestAcceptedPolicyVersion}",
        "最低安全包版本：v${snapshot.minimumSafePackageVersion}",
        "最近保留的安全签名版本（LKG，可为停用包）：${snapshot.lastKnownGoodVersion?.let { "v$it" } ?: "无"}",
        "仓库状态：${snapshot.state.name} · 授权代次：${snapshot.generation}",
    ) + listOfNotNull(snapshot.currentDigest?.let { "当前包指纹：${fingerprint(it)}" },
        snapshot.error?.let { "停用 / 恢复原因：${errorText(it)}（${it.name}）" })
    }

    private fun health(snapshot: ProviderRepositorySnapshot): List<String> {
        if (unreadable(snapshot)) return listOf("本地试用阶段与计数不可确认。")
        val health = snapshot.health
        val phase = when (health.phase) {
            ProviderHealthPhase.NONE -> "无候选试用记录"
            ProviderHealthPhase.PROBATION -> "有界候选试用中"
            ProviderHealthPhase.HEALTHY -> "本地试用阈值已达到，非持续健康保证"
            ProviderHealthPhase.SUSPENDED -> "试用已暂停，不能据此晋级或判定坏包"
        }
        val counters = if (health.phase == ProviderHealthPhase.PROBATION || health.phase == ProviderHealthPhase.SUSPENDED) listOf(
            "连续新鲜成功：${health.consecutiveSuccesses} / ${ProviderHealthLimits.REQUIRED_SUCCESSES}",
            "连续结构异常：${health.consecutiveStructureFailures} / ${ProviderHealthLimits.REQUIRED_STRUCTURE_FAILURES}",
            "确定性观察：${health.conclusiveObservations} / ${ProviderHealthLimits.MAX_CONCLUSIVE_OBSERVATIONS}",
            "总观察：${health.totalObservations} / ${ProviderHealthLimits.MAX_TOTAL_OBSERVATIONS}",
        ) else listOf("当前没有进行中的试用计数；达到阈值后不保留为累计成功次数。")
        return listOf(phase) + counters + listOf(
            "晋级要求连续 ${ProviderHealthLimits.REQUIRED_SUCCESSES} 次新鲜成功；连续 ${ProviderHealthLimits.REQUIRED_STRUCTURE_FAILURES} 次结构异常触发隔离。",
            "观察上限：${ProviderHealthLimits.MAX_CONCLUSIVE_OBSERVATIONS} 次确定性 / ${ProviderHealthLimits.MAX_TOTAL_OBSERVATIONS} 次总观察。",
            "候选窗口最长 ${ProviderHealthLimits.MAX_PROBATION_MILLIS / 3_600_000} 小时；到达观察 / 时间上限只暂停。",
        ) + listOfNotNull(health.startedAtEpochMillis?.let { "试用起点（UTC）：${Instant.ofEpochMilli(it)}" },
            health.deadlineEpochMillis?.let { "试用截止（UTC）：${Instant.ofEpochMilli(it)}" })
    }

    private fun notice(state: ProviderManagementState): String? {
        if (state.busy) return "正在处理本地仓库，请等待；不会访问更新服务。"
        return when (state.problem) {
            ProviderManagementProblem.WORKER_BUSY -> "上一个本地管理操作尚未退出。没有排队或开启新任务，请稍后刷新。"
            ProviderManagementProblem.LOCAL_UNAVAILABLE -> "本地仓库不可读，状态未知。已有数据保留，没有重置或自动重试初始化。"
            ProviderManagementProblem.ACTION_REJECTED -> "仓库未接受此操作：${state.repositoryError?.let(::errorText) ?: "当前状态不允许"}。请查看最新状态。"
            null -> when (state.completed) {
                ProviderManagementOperation.REJECT_CANDIDATE, ProviderManagementOperation.ROLLBACK ->
                    if (state.snapshot?.policy?.enabled != true || state.snapshot?.state == ProviderRepositoryState.DISABLED)
                        "本地事务已完成；当前维护授权仍停用。"
                    else "本地事务已完成；当前状态见下方，不代表网站兼容或持续健康。"
                else -> null
            }
        }
    }

    private fun fingerprint(value: String): String = if (value.matches(Regex("[0-9a-f]{64}"))) value.take(12) + "…" else "不可用"

    fun errorText(error: ProviderRepositoryError): String = when (error) {
        ProviderRepositoryError.CANCELLED -> "本地操作已取消"
        ProviderRepositoryError.RECOVERY_REQUIRED -> "中断候选需显式拒绝"
        ProviderRepositoryError.CORRUPT_STATE, ProviderRepositoryError.STORAGE_FAILURE -> "本地存储不确定，保持停用"
        ProviderRepositoryError.BUSY -> "本地仓库被占用"
        ProviderRepositoryError.CLOSED -> "本地仓库已关闭"
        ProviderRepositoryError.POLICY_DISABLED -> "安全策略已停用"
        ProviderRepositoryError.POLICY_EXPIRED -> "安全策略已过期"
        ProviderRepositoryError.EXPIRED -> "参数包已过期"
        ProviderRepositoryError.REVOKED_VERSION -> "包版本已撤销"
        ProviderRepositoryError.REVOKED_KEY -> "包签名密钥已撤销"
        ProviderRepositoryError.UNSAFE_VERSION -> "低于最低安全版本"
        ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED, ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED -> "缺少有效的精确签名回退授权"
        ProviderRepositoryError.DISABLE_BARRIER -> "受签名停用屏障限制"
        ProviderRepositoryError.CLOCK_ROLLBACK, ProviderRepositoryError.INVALID_TIME -> "本地时间检查未通过"
        ProviderRepositoryError.QUARANTINED -> "当前包版本已被本地隔离"
        ProviderRepositoryError.SELF_TEST_FAILED -> "固定离线自测未通过"
        ProviderRepositoryError.HEALTH_BUDGET_EXHAUSTED -> "已达本地试用观察上限"
        ProviderRepositoryError.HEALTH_WINDOW_EXPIRED -> "已达本地试用时间上限"
        ProviderRepositoryError.QUARANTINE_CAPACITY_REACHED -> "本地隔离记录已满，需另行评审恢复方案"
        else -> "当前安全或恢复条件不满足"
    }
}
