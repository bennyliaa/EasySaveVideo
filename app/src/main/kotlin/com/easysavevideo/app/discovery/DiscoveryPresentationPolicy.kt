package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.RepeatPolicy
import com.easysavevideo.discovery.*

/** Captured when a confirmation opens; never resolve a new search as its target. */
data class DiscoveryControlTarget(
    val profileId: String,
    val stamp: SessionStamp,
    val viewRevision: Long,
    val controllerGeneration: Long,
) { override fun toString() = "DiscoveryControlTarget([redacted])" }

enum class DiscoveryLocalAction { CLEAR_CURRENT_HISTORY, CLEAR_ALL_HISTORY, RESET_PROVIDER_PROGRESS }
enum class DiscoveryControlOutcome { COMMITTED, FAILED, STALE }

data class DiscoveryControlRequest(
    val target: DiscoveryControlTarget,
    val action: DiscoveryLocalAction,
    val providerId: String? = null,
) {
    init { require((action == DiscoveryLocalAction.RESET_PROVIDER_PROGRESS) == (providerId != null)) }
    override fun toString() = "DiscoveryControlRequest([redacted])"
}

/** These counts describe local records, not playback, media files, or source completion. */
data class DiscoveryHistoryStatistics(
    val target: DiscoveryControlTarget,
    val currentProfile: HistoryStatistics,
    val allProfiles: HistoryStatistics,
) { override fun toString() = "DiscoveryHistoryStatistics([redacted])" }

/** Pure UI policies, testable without the Android runtime. */
object DiscoveryPresentationPolicy {
    /** A read-only refresh cannot consume pending demand or create a materialized page. */
    fun mayFulfillPageDemand(readOnly: Boolean, pending: Boolean, pageMissing: Boolean, initialized: Boolean,
        unavailable: Boolean, foreground: Boolean, visible: Boolean): Boolean =
        !readOnly && pending && pageMissing && initialized && !unavailable && foreground && visible

    fun controlTarget(checkpoint: DiscoveryCheckpoint?, generation: Long): DiscoveryControlTarget? = checkpoint?.let {
        DiscoveryControlTarget(it.profile.profileId, it.stamp, it.pool.viewRevision, generation)
    }

    fun validControl(request: DiscoveryControlRequest, current: DiscoveryControlTarget?, providerIds: Set<String>): Boolean =
        current != null && request.target == current &&
            (request.action != DiscoveryLocalAction.RESET_PROVIDER_PROGRESS || request.providerId?.let(providerIds::contains) == true)

    /** Only the supplied scoped mutation may run; no page, source, or refresh callback is accepted. */
    fun executeControl(request: DiscoveryControlRequest, current: DiscoveryControlTarget?, providerIds: Set<String>,
        clearHistory: (String?) -> Boolean, resetProgress: (String, String) -> Boolean): DiscoveryControlOutcome {
        if (!validControl(request, current, providerIds)) return DiscoveryControlOutcome.STALE
        val committed = when (request.action) {
            DiscoveryLocalAction.CLEAR_CURRENT_HISTORY -> clearHistory(request.target.profileId)
            DiscoveryLocalAction.CLEAR_ALL_HISTORY -> clearHistory(null)
            DiscoveryLocalAction.RESET_PROVIDER_PROGRESS -> resetProgress(request.target.profileId, requireNotNull(request.providerId))
        }
        return if (committed) DiscoveryControlOutcome.COMMITTED else DiscoveryControlOutcome.FAILED
    }

    fun controlTitle(request: DiscoveryControlRequest): String = when (request.action) {
        DiscoveryLocalAction.CLEAR_CURRENT_HISTORY -> "清除当前资料的 Seen 历史？"
        DiscoveryLocalAction.CLEAR_ALL_HISTORY -> "清除全部资料的 Seen 历史？"
        DiscoveryLocalAction.RESET_PROVIDER_PROGRESS -> "重置来源 ${request.providerId} 的进度？"
    }

    fun controlConsequence(request: DiscoveryControlRequest): String = when (request.action) {
        DiscoveryLocalAction.CLEAR_CURRENT_HISTORY ->
            "仅清除打开此确认时，当前关键词、筛选和语言资料的展示、打开、播放、保存事件记录。之后可能再次看到旧结果。来源进度、现有页面、媒体文件和 Vault 保留。此操作无法撤销。"
        DiscoveryLocalAction.CLEAR_ALL_HISTORY ->
            "清除本地全部关键词、筛选和语言资料的展示、打开、播放、保存事件记录。之后可能再次看到旧结果。所有来源进度、现有页面、媒体文件和 Vault 保留。此操作无法撤销。"
        DiscoveryLocalAction.RESET_PROVIDER_PROGRESS ->
            "仅重置打开此确认时，当前资料中来源 ${request.providerId} 的续页和结束记录。下次主动请求将从头检查，旧结果可能再次出现。Seen 历史、其他来源与其他资料的进度、现有页面、媒体文件和 Vault 保留。现在不会请求来源。此操作无法撤销。"
    }

    fun controlOutcomeText(action: DiscoveryLocalAction, outcome: DiscoveryControlOutcome): String = when (outcome) {
        DiscoveryControlOutcome.STALE -> "查询或本地视图已变化，未执行操作。请重新打开确认。"
        DiscoveryControlOutcome.FAILED -> "本地操作未成功，未确认任何清除或重置。请检查本地存储状态后重试。"
        DiscoveryControlOutcome.COMMITTED -> when (action) {
            DiscoveryLocalAction.CLEAR_CURRENT_HISTORY -> "已清除当前资料的 Seen 历史，来源进度和媒体文件保留。"
            DiscoveryLocalAction.CLEAR_ALL_HISTORY -> "已清除全部资料的 Seen 历史，来源进度和媒体文件保留。"
            DiscoveryLocalAction.RESET_PROVIDER_PROGRESS -> "已重置所选来源在当前资料的进度，Seen 历史和其他来源进度保留。"
        }
    }

    fun retentionNotice(policy: DiscoveryRetentionPolicy = DiscoveryRetentionPolicy()): String =
        "本地容量上限：${policy.maxProfiles} 个资料、${policy.maxSeenAliases} 条 Seen 别名、${policy.maxProgress} 条来源进度、${policy.maxCheckpoints} 个会话检查点。" +
            "按容量保留，不按天数过期；优先清理最久未在本地变更且未受保护的记录，当前已生成页面的数据受保护。" +
            "记录被清理后，旧结果可能再次出现。Seen 历史与来源进度独立保存，不会因此补记展示、打开、播放或保存事件，也不会删除媒体文件或 Vault。"

    fun statisticsText(label: String, statistics: HistoryStatistics): String =
        "$label：${statistics.profileCount} 个资料 · ${statistics.checkpointCount} 个会话检查点 · " +
            "${statistics.seenIdentityCount} 个 Seen 身份 · ${statistics.seenAliasCount} 条 Seen 别名 · ${statistics.progressCount} 条来源进度"

    fun boundedQuery(value: String): String = value.take(512).dropLastWhile { it.isHighSurrogate() }
    fun validQuery(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) return false
        }
        return value.length <= 512
    }

    fun profile(query: String, locale: String, filters: SearchFilters, policy: RepeatPolicy, demo: Boolean): SearchProfile {
        val value = SearchProfile("temporary", query, locale, filters, policy)
        return value.copy(profileId = (if (demo) "demo-" else "p-") + value.queryKey)
    }
    fun fraction(left: Int, top: Int, width: Int, height: Int, viewportWidth: Int, viewportTop: Int,
                 viewportBottom: Int, active: Boolean, unobscured: Boolean): Double {
        if (!active || !unobscured || width <= 0 || height <= 0 || viewportWidth <= 0 || viewportBottom <= viewportTop) return 0.0
        val visibleWidth = (minOf(left.toLong() + width, viewportWidth.toLong()) - maxOf(left, 0)).coerceAtLeast(0)
        val visibleHeight = (minOf(top.toLong() + height, viewportBottom.toLong()) - maxOf(top, viewportTop)).coerceAtLeast(0)
        return (visibleWidth.toDouble() * visibleHeight / (width.toDouble() * height)).coerceIn(0.0, 1.0)
    }
    fun sortLabel(sort: ResultSort): String = when (sort) {
        ResultSort.RELEVANCE -> "来源顺序"
        ResultSort.NEWEST -> "发布日期：新到旧"
        ResultSort.DURATION_SHORTEST -> "时长：短到长"
        ResultSort.DURATION_LONGEST -> "时长：长到短"
    }
    fun providerProgressText(progress: ProviderDiscoveryProgress): String {
        val state = when (progress.state) {
            ProviderDiscoveryState.NOT_STARTED -> "尚未查询"
            ProviderDiscoveryState.REQUESTING -> "正在请求"
            ProviderDiscoveryState.RETRY_WAIT -> "等待本次重试"
            ProviderDiscoveryState.COOLDOWN -> "来源冷却，稍后可重试"
            ProviderDiscoveryState.BLOCKED -> when (progress.lastError) {
                DiscoveryError.UNSUPPORTED_FILTER -> "不支持当前筛选"
                DiscoveryError.INVALID_QUERY -> "关键词不受支持"
                else -> "来源配置不可用"
            }
            ProviderDiscoveryState.READY -> "可按需继续"
            ProviderDiscoveryState.EXHAUSTED -> "当前查询已无后续页"
            ProviderDiscoveryState.CURSOR_EXPIRED -> "续页已过期，下次从头检查"
            ProviderDiscoveryState.CURSOR_INCOMPATIBLE -> "续页版本不兼容，下次从头检查"
            ProviderDiscoveryState.SUSPENDED -> "已暂停"
            ProviderDiscoveryState.STORAGE_UNAVAILABLE -> "本地进度不可读"
        }
        return "来源 ${progress.providerId}：$state" +
            (if (progress.lastSuccessAtEpochMillis != null) " · 有已提交结果页" else "") +
            (if (progress.hasContinuation) " · 续页已保存" else "")
    }

}
