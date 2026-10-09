package com.easysavevideo.app.discovery

import com.easysavevideo.commons.CommonsIdentity
import com.easysavevideo.discovery.ProviderDescriptor
import com.easysavevideo.discovery.SearchFilters
import com.easysavevideo.discovery.SearchProfile

/** In-memory editor only. Applied profiles are still persisted by the existing checkpoint codec. */
data class DiscoveryFilterDraft(
    val appliedProfile: SearchProfile,
    val demo: Boolean,
    val query: String = appliedProfile.query,
    val sourceNamespaces: Set<String> = appliedProfile.filters.sourceNamespaces.toSet(),
    val minimumSeconds: String = DiscoveryFilterPresentationPolicy.seconds(appliedProfile.filters.minimumDurationMs),
    val maximumSeconds: String = DiscoveryFilterPresentationPolicy.seconds(appliedProfile.filters.maximumDurationMs),
    val includeUnknownDuration: Boolean = appliedProfile.filters.includeUnknownDuration,
    val safeSearch: Boolean = appliedProfile.filters.safeSearch,
) { override fun toString() = "DiscoveryFilterDraft([redacted])" }

/** Captured UI intent, checked against the actual main-thread controller state at the click. */
data class DiscoveryFilterClaim(val target: DiscoveryControlTarget, val demo: Boolean, val draft: DiscoveryFilterDraft) {
    override fun toString() = "DiscoveryFilterClaim([redacted])"
}

enum class DiscoveryFilterTextField { QUERY, MINIMUM_SECONDS, MAXIMUM_SECONDS }

data class DiscoveryFilterValidation(val filters: SearchFilters?, val error: String? = null)

data class DiscoverySourceChoice(val namespace: String, val label: String, val synthetic: Boolean = false)

/** Pure presentation: no registry construction, provider calls, normalization of queries, or demand. */
object DiscoveryFilterPresentationPolicy {
    const val MAX_DURATION_TEXT = 20
    private const val DEMO_NAMESPACE = "local-demo"

    /** A descriptor ID is not generally a result namespace. Only these existing bridges prove it. */
    fun admittedSources(descriptors: List<ProviderDescriptor>, demo: Boolean): List<DiscoverySourceChoice> =
        descriptors.mapNotNull { descriptor ->
            when {
                !demo && descriptor.id == CommonsIdentity.PROVIDER_ID ->
                    DiscoverySourceChoice(CommonsIdentity.PROVIDER_ID, CommonsIdentity.SOURCE)
                !demo && descriptor.id in setOf("searxng-1", "searxng-2", "searxng-3") ->
                    DiscoverySourceChoice(descriptor.id, "SearXNG 候选来源 ${descriptor.id.last()}")
                demo && descriptor.id == DEMO_NAMESPACE ->
                    DiscoverySourceChoice(DEMO_NAMESPACE, "本地合成来源（隔离演示）", synthetic = true)
                else -> null
            }
        }.distinctBy { it.namespace }

    fun fromProfile(profile: SearchProfile, demo: Boolean): DiscoveryFilterDraft = DiscoveryFilterDraft(
        profile.copy(filters = profile.filters.copy(sourceNamespaces = profile.filters.sourceNamespaces.toSet())), demo)

    /** Sort/history ticks and source availability never rewrite drafts or erase obsolete selections. */
    fun reconcile(draft: DiscoveryFilterDraft?, profile: SearchProfile, demo: Boolean,
        appliedReadable: Boolean = true): DiscoveryFilterDraft {
        if (!appliedReadable) return draft ?: fromProfile(profile, demo)
        return if (draft != null && matchesApplied(draft, profile, demo)) draft else fromProfile(profile, demo)
    }

    fun matchesApplied(draft: DiscoveryFilterDraft, profile: SearchProfile, demo: Boolean): Boolean {
        val baseline = draft.appliedProfile
        return draft.demo == demo && baseline.profileId == profile.profileId && baseline.query == profile.query &&
            baseline.locale == profile.locale && baseline.filters == profile.filters
    }

    fun validClaim(claim: DiscoveryFilterClaim, currentDraft: DiscoveryFilterDraft?, currentTarget: DiscoveryControlTarget?,
        currentProfile: SearchProfile, demo: Boolean, ready: Boolean, unavailable: Boolean, busy: Boolean): Boolean =
        ready && !unavailable && !busy && claim.target == currentTarget && claim.demo == demo &&
            claim.target.profileId == currentProfile.profileId && claim.draft == currentDraft &&
            matchesApplied(claim.draft, currentProfile, demo)

    /** IME callbacks can arrive before recomposition. Rebase only this field, never the whole draft. */
    fun editText(claim: DiscoveryFilterClaim, currentDraft: DiscoveryFilterDraft?, currentTarget: DiscoveryControlTarget?,
        currentProfile: SearchProfile, demo: Boolean, ready: Boolean, unavailable: Boolean, busy: Boolean,
        field: DiscoveryFilterTextField, value: String): DiscoveryFilterDraft? {
        val current = currentDraft ?: return null
        if (!matchesApplied(claim.draft, currentProfile, demo) ||
            !validClaim(claim.copy(draft = current), current, currentTarget, currentProfile, demo, ready, unavailable, busy)) return null
        return when (field) {
            DiscoveryFilterTextField.QUERY -> current.copy(query = DiscoveryPresentationPolicy.boundedQuery(value))
            DiscoveryFilterTextField.MINIMUM_SECONDS ->
                if (value.length <= MAX_DURATION_TEXT) current.copy(minimumSeconds = value) else null
            DiscoveryFilterTextField.MAXIMUM_SECONDS ->
                if (value.length <= MAX_DURATION_TEXT) current.copy(maximumSeconds = value) else null
        }
    }

    /** Decimal seconds round-trip every nonnegative Long millisecond value without truncation. */
    fun seconds(milliseconds: Long?): String = milliseconds?.let {
        val fraction = (it % 1_000).toString().padStart(3, '0').trimEnd('0')
        (it / 1_000).toString() + if (fraction.isEmpty()) "" else ".$fraction"
    }.orEmpty()

    private fun milliseconds(text: String): Long? {
        if (text.isEmpty()) return null
        require(text.length <= MAX_DURATION_TEXT && text.matches(Regex("[0-9]+(?:\\.[0-9]{1,3})?")))
        val parts = text.split('.')
        val whole = parts[0].toLongOrNull() ?: throw IllegalArgumentException("DURATION")
        val fraction = parts.getOrNull(1)?.padEnd(3, '0')?.toLong() ?: 0
        require(whole <= (Long.MAX_VALUE - fraction) / 1_000)
        return whole * 1_000 + fraction
    }

    fun validate(draft: DiscoveryFilterDraft): DiscoveryFilterValidation {
        val minimum = try { milliseconds(draft.minimumSeconds) } catch (_: IllegalArgumentException) {
            return DiscoveryFilterValidation(null, "最短时长须为非负秒数，最多三位小数；留空表示不限。")
        }
        val maximum = try { milliseconds(draft.maximumSeconds) } catch (_: IllegalArgumentException) {
            return DiscoveryFilterValidation(null, "最长时长须为非负秒数，最多三位小数；留空表示不限。")
        }
        if (minimum != null && maximum != null && minimum > maximum)
            return DiscoveryFilterValidation(null, "最短时长不能大于最长时长；未改动已应用资料。")
        val filters = try { SearchFilters(draft.sourceNamespaces.toSet(), minimum, maximum,
            draft.includeUnknownDuration, draft.safeSearch) } catch (_: IllegalArgumentException) {
            return DiscoveryFilterValidation(null, "来源筛选无效；未改动已应用资料。")
        }
        return DiscoveryFilterValidation(filters)
    }

    fun isDirty(draft: DiscoveryFilterDraft): Boolean =
        draft.query != draft.appliedProfile.query || validate(draft).filters != draft.appliedProfile.filters

    fun canSubmit(draft: DiscoveryFilterDraft): Boolean = draft.query.isNotBlank() &&
        DiscoveryPresentationPolicy.validQuery(draft.query) && validate(draft).filters != null

    fun isSelected(namespaces: Set<String>, namespace: String): Boolean =
        namespaces.any { it.equals(namespace, ignoreCase = true) }

    fun addSource(draft: DiscoveryFilterDraft, choice: DiscoverySourceChoice): DiscoveryFilterDraft =
        if (isSelected(draft.sourceNamespaces, choice.namespace) || draft.sourceNamespaces.size >= 100) draft
        else draft.copy(sourceNamespaces = draft.sourceNamespaces + choice.namespace)

    fun removeSource(draft: DiscoveryFilterDraft, namespace: String): DiscoveryFilterDraft =
        draft.copy(sourceNamespaces = draft.sourceNamespaces.filterNot { it.equals(namespace, ignoreCase = true) }.toSet())

    fun unavailableNamespaces(namespaces: Set<String>, choices: List<DiscoverySourceChoice>): List<String> =
        namespaces.filter { selected -> choices.none { it.namespace.equals(selected, ignoreCase = true) } }.sorted()

    fun sourceSummary(filters: SearchFilters, choices: List<DiscoverySourceChoice>): String =
        if (filters.sourceNamespaces.isEmpty()) "全部当前启用来源（没有启用来源时仍为零来源）"
        else filters.sourceNamespaces.sorted().joinToString("、") { namespace ->
            choices.firstOrNull { it.namespace.equals(namespace, ignoreCase = true) }?.let { "${it.label} [$namespace]" }
                ?: "$namespace（未启用或未知；筛选已保留）"
        }

    fun durationSummary(filters: SearchFilters): String =
        "最短 ${filters.minimumDurationMs?.let { seconds(it) + " 秒" } ?: "不限"}；" +
            "最长 ${filters.maximumDurationMs?.let { seconds(it) + " 秒" } ?: "不限"}；" +
            "${if (filters.includeUnknownDuration) "包含" else "排除"}未知时长"

    fun capabilityText(choice: DiscoverySourceChoice): List<String> = if (choice.synthetic) listOf(
        "本地合成演示：来源与时长筛选只处理合成记录，不能据此证明真实来源能力。",
        "发布日期未知；安全搜索没有经过真实内容验证。",
    ) else if (choice.namespace in setOf("searxng-1", "searxng-2", "searxng-3")) listOf(
        "${choice.label}：安全搜索能力尚未核验；开启时不会向该来源发送查询。",
        "返回内容仅为来源报告的待验证网页；时长按未知处理，排除未知时长时不展示。",
        "未验证视频、图片、时长或许可；不会自动获取缩略图或网页。",
    ) else listOf(
        "${choice.label}：不支持安全搜索；开启时不会向此来源发送查询。",
        "时长来自来源报告，可能未知；最短、最长与未知时长开关在本地筛选，不改写关键词。",
        "发布日期未知：当前适配器未提供。作者与许可为来源报告，需逐文件核对。",
    )

    fun unknownDescriptorIds(descriptors: List<ProviderDescriptor>, choices: List<DiscoverySourceChoice>): List<String> =
        descriptors.map { it.id }.distinct().filter { id -> choices.none { it.namespace == id } }

    fun nativeHistoryNotice(): String = "原生视频的 SAVED（保存）与 PLAYED（播放）仍为 UNKNOWN（未知）。打开网页或来自该页的保存不能证明所选发现视频已保存或播放。"
}
