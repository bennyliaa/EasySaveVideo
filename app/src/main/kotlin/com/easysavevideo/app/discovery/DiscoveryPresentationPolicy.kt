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

/** Finite, payload-free presentation result; display language is resolved only when rendered. */
enum class DiscoveryNotice {
    SOURCE_CONFIGURATION_CHECKING,
    VERIFICATION_NOT_STARTED,
    VIEW_SETTINGS_CHANGED,
    INVALID_QUERY,
    SORT_CHANGED,
    STORAGE_RETRY_UNAVAILABLE,
    STORAGE_RECOVERY_UNAVAILABLE,
    CONTROL_STALE,
    CONTROL_FAILED,
    CONTROL_CURRENT_HISTORY_CLEARED,
    CONTROL_ALL_HISTORY_CLEARED,
    CONTROL_PROVIDER_PROGRESS_RESET,
}


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

    fun controlOutcomeNotice(action: DiscoveryLocalAction, outcome: DiscoveryControlOutcome): DiscoveryNotice = when (outcome) {
        DiscoveryControlOutcome.STALE -> DiscoveryNotice.CONTROL_STALE
        DiscoveryControlOutcome.FAILED -> DiscoveryNotice.CONTROL_FAILED
        DiscoveryControlOutcome.COMMITTED -> when (action) {
            DiscoveryLocalAction.CLEAR_CURRENT_HISTORY -> DiscoveryNotice.CONTROL_CURRENT_HISTORY_CLEARED
            DiscoveryLocalAction.CLEAR_ALL_HISTORY -> DiscoveryNotice.CONTROL_ALL_HISTORY_CLEARED
            DiscoveryLocalAction.RESET_PROVIDER_PROGRESS -> DiscoveryNotice.CONTROL_PROVIDER_PROGRESS_RESET
        }
    }

    fun noticeText(notice: DiscoveryNotice, copy: DiscoveryPresentationCopy): String = when (notice) {
        DiscoveryNotice.SOURCE_CONFIGURATION_CHECKING -> copy.noticeSourceConfigurationChecking
        DiscoveryNotice.VERIFICATION_NOT_STARTED -> copy.noticeVerificationNotStarted
        DiscoveryNotice.VIEW_SETTINGS_CHANGED -> copy.noticeViewSettingsChanged
        DiscoveryNotice.INVALID_QUERY -> copy.noticeInvalidQuery
        DiscoveryNotice.SORT_CHANGED -> copy.noticeSortChanged
        DiscoveryNotice.STORAGE_RETRY_UNAVAILABLE -> copy.noticeStorageRetryUnavailable
        DiscoveryNotice.STORAGE_RECOVERY_UNAVAILABLE -> copy.noticeStorageRecoveryUnavailable
        DiscoveryNotice.CONTROL_STALE -> copy.noticeControlStale
        DiscoveryNotice.CONTROL_FAILED -> copy.noticeControlFailed
        DiscoveryNotice.CONTROL_CURRENT_HISTORY_CLEARED -> copy.noticeControlCurrentHistoryCleared
        DiscoveryNotice.CONTROL_ALL_HISTORY_CLEARED -> copy.noticeControlAllHistoryCleared
        DiscoveryNotice.CONTROL_PROVIDER_PROGRESS_RESET -> copy.noticeControlProviderProgressReset
    }

    /** Compatibility for existing non-UI callers; controller state retains only the typed notice. */
    fun controlOutcomeText(action: DiscoveryLocalAction, outcome: DiscoveryControlOutcome): String =
        noticeText(controlOutcomeNotice(action, outcome), DiscoveryPresentationCopy())

    fun durationText(durationMs: Long, copy: DiscoveryPresentationCopy): String =
        copy.format(copy.duration, durationMs / 60_000, durationMs / 1_000 % 60)

    fun retentionNotice(policy: DiscoveryRetentionPolicy = DiscoveryRetentionPolicy(),
        copy: DiscoveryPresentationCopy = DiscoveryPresentationCopy()): String =
        copy.format(copy.retention, copy.count(policy.maxProfiles), copy.count(policy.maxSeenAliases),
            copy.count(policy.maxProgress), copy.count(policy.maxCheckpoints))

    fun statisticsText(label: String, statistics: HistoryStatistics,
        copy: DiscoveryPresentationCopy = DiscoveryPresentationCopy()): String =
        copy.format(copy.statistics, label, copy.count(statistics.profileCount), copy.count(statistics.checkpointCount),
            copy.count(statistics.seenIdentityCount), copy.count(statistics.seenAliasCount), copy.count(statistics.progressCount))

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
    fun sortLabel(sort: ResultSort): String = sortLabel(sort, DiscoveryPresentationCopy())
    fun sortLabel(sort: ResultSort, copy: DiscoveryPresentationCopy): String = when (sort) {
        ResultSort.RELEVANCE -> copy.sortRelevance
        ResultSort.NEWEST -> copy.sortNewest
        ResultSort.DURATION_SHORTEST -> copy.sortShortest
        ResultSort.DURATION_LONGEST -> copy.sortLongest
    }
    fun providerProgressText(progress: ProviderDiscoveryProgress,
        copy: DiscoveryPresentationCopy = DiscoveryPresentationCopy()): String {
        val state = when (progress.state) {
            ProviderDiscoveryState.NOT_STARTED -> copy.providerNotStarted
            ProviderDiscoveryState.REQUESTING -> copy.providerRequesting
            ProviderDiscoveryState.RETRY_WAIT -> copy.providerRetryWait
            ProviderDiscoveryState.COOLDOWN -> copy.providerCooldown
            ProviderDiscoveryState.BLOCKED -> when (progress.lastError) {
                DiscoveryError.UNSUPPORTED_FILTER -> copy.providerUnsupportedFilter
                DiscoveryError.INVALID_QUERY -> copy.providerInvalidQuery
                else -> copy.providerUnavailable
            }
            ProviderDiscoveryState.READY -> copy.providerReady
            ProviderDiscoveryState.EXHAUSTED -> copy.providerExhausted
            ProviderDiscoveryState.CURSOR_EXPIRED -> copy.providerCursorExpired
            ProviderDiscoveryState.CURSOR_INCOMPATIBLE -> copy.providerCursorIncompatible
            ProviderDiscoveryState.SUSPENDED -> copy.providerSuspended
            ProviderDiscoveryState.STORAGE_UNAVAILABLE -> copy.providerStorageUnavailable
        }
        return copy.format(copy.providerProgress, progress.providerId, state,
            if (progress.lastSuccessAtEpochMillis != null) copy.committedPage else "",
            if (progress.hasContinuation) copy.savedContinuation else "")
    }

}
