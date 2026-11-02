package com.easysavevideo.discovery

import com.easysavevideo.core.model.RepeatPolicy

data class PoolEntry(val result: VideoSearchResult, val discoveredAtEpochMillis: Long, val order: Long)
data class PageSnapshot(
    val stamp: SessionStamp,
    val revision: Long,
    val pageIndex: Int,
    val orderedResultIds: List<String>,
    val pageSize: Int,
) { override fun toString(): String = "PageSnapshot(revision=$revision, pageIndex=$pageIndex, count=${orderedResultIds.size})" }
data class PoolState(
    val entries: List<PoolEntry> = emptyList(),
    val pages: List<PageSnapshot> = emptyList(),
    val viewRevision: Long = 0,
    val nextOrder: Long = 0,
)
data class PoolAdmission(val state: PoolState, val added: List<VideoSearchResult>)

/** Pure bounded transitions. A materialized page is immutable until an explicit view revision change. */
class ResultPool(private val capacity: Int = 500) {
    init { require(capacity in 1..500) }
    fun admit(state: PoolState, results: List<VideoSearchResult>, nowMillis: Long): PoolAdmission {
        val entries = state.entries.toMutableList()
        val protected = state.pages.flatMap { it.orderedResultIds }.toSet()
        val added = mutableListOf<VideoSearchResult>()
        var order = state.nextOrder
        for (candidate in results) {
            val incoming = candidate.copy(rights = candidate.rights.detached())
            try { RightsCodec.validate(incoming.rights) } catch (_: IllegalArgumentException) { throw DiscoveryFailure(DiscoveryError.INVALID_RESULT) }
            // Compute the transitive alias group, not just the first matching entry.
            val aliases = incoming.identityAliases.toMutableSet()
            val matches = mutableListOf<PoolEntry>()
            var expanded: Boolean
            do {
                expanded = false
                entries.filter { it !in matches && it.result.identityAliases.any(aliases::contains) }.forEach {
                    matches += it; aliases += it.result.identityAliases; expanded = true
                }
            } while (expanded)
            if (matches.isNotEmpty()) {
                val retained = matches.filter { it.result.resultId in protected }.ifEmpty { listOf(matches.minBy { it.order }) }
                val merged = matches.fold(incoming) { value, entry -> merge(entry.result, value) }
                matches.filter { it !in retained }.forEach { removed ->
                    entries.remove(removed); added.removeAll { it.resultId == removed.result.resultId }
                }
                retained.forEach { entry ->
                    val index = entries.indexOf(entry)
                    entries[index] = entry.copy(result = merge(entry.result, merged))
                }
                continue
            }
            if (entries.size >= capacity) {
                val evict = entries.indexOfFirst { it.result.resultId !in protected }
                if (evict < 0) throw DiscoveryFailure(DiscoveryError.CAPACITY_REACHED)
                val removed = entries.removeAt(evict).result.resultId
                added.removeAll { it.resultId == removed }
            }
            entries += PoolEntry(incoming, nowMillis, order++)
            added += incoming
        }
        return PoolAdmission(state.copy(entries = entries.toList(), nextOrder = order), added.toList())
    }

    /** Preserve callers that supply their history lookup positionally. */
    fun available(state: PoolState, policy: RepeatPolicy, seen: (String) -> SeenRecord?): List<PoolEntry> =
        available(state, policy, ResultSort.RELEVANCE, seen)

    fun available(state: PoolState, policy: RepeatPolicy, sortOrder: ResultSort = ResultSort.RELEVANCE,
                  seen: (String) -> SeenRecord?): List<PoolEntry> {
        val allocated = state.pages.flatMap { it.orderedResultIds }.toSet()
        val historyCache = state.entries.associate { entry -> entry.result.fingerprint to
            mergeSeenRecords("pool", entry.result.fingerprint, (entry.result.identityAliases + entry.result.fingerprint).mapNotNull(seen)) }
        fun history(result: VideoSearchResult): SeenRecord? = historyCache[result.fingerprint]
        val usedAliases = state.entries.filter { it.result.resultId in allocated }.flatMap { it.result.identityAliases }.toMutableSet()
        val ordering = when (sortOrder) {
            ResultSort.RELEVANCE -> compareBy<PoolEntry> { it.order }
            ResultSort.NEWEST -> compareBy<PoolEntry> { it.result.publishedAtEpochMillis == null }
                .thenByDescending { it.result.publishedAtEpochMillis }
            ResultSort.DURATION_SHORTEST -> compareBy<PoolEntry> { it.result.durationMs == null }
                .thenBy { it.result.durationMs }
            ResultSort.DURATION_LONGEST -> compareBy<PoolEntry> { it.result.durationMs == null }
                .thenByDescending { it.result.durationMs }
        }
        // History priority always wins over metadata ordering. Missing metadata follows known
        // values within each history bucket; arrival order and identity settle every tie.
        return state.entries.filter { it.result.resultId !in allocated && SeenHistoryPolicy.allows(policy, history(it.result)) }
            .sortedWith(compareBy<PoolEntry> { SeenHistoryPolicy.priority(policy, history(it.result)) }
                .then(ordering).thenBy { it.order }.thenBy { it.result.resultId })
            .filter { entry ->
                if (entry.result.identityAliases.any(usedAliases::contains)) false
                else { usedAliases += entry.result.identityAliases; true }
            }
    }

    fun materialize(state: PoolState, stamp: SessionStamp, pageIndex: Int, pageSize: Int,
                    policy: RepeatPolicy, seen: (String) -> SeenRecord?): Pair<PoolState, PageSnapshot?> =
        materialize(state, stamp, pageIndex, pageSize, policy, ResultSort.RELEVANCE, seen)

    fun materialize(state: PoolState, stamp: SessionStamp, pageIndex: Int, pageSize: Int,
                    policy: RepeatPolicy, sortOrder: ResultSort = ResultSort.RELEVANCE,
                    seen: (String) -> SeenRecord?): Pair<PoolState, PageSnapshot?> {
        require(pageIndex >= 0); require(pageSize in 1..100)
        state.pages.firstOrNull { it.pageIndex == pageIndex }?.let { return state to it }
        require(pageIndex == state.pages.size) { "Pages must be materialized in order" }
        val ids = available(state, policy, sortOrder, seen).take(pageSize).map { it.result.resultId }
        if (ids.isEmpty()) return state to null
        val page = PageSnapshot(stamp, state.viewRevision, pageIndex, ids, pageSize)
        return state.copy(pages = state.pages + page) to page
    }

    /** Retain the entire current page as the anchor. New size applies to subsequent pages. */
    fun revise(state: PoolState, stamp: SessionStamp, currentPageIndex: Int, pageSize: Int): PoolState {
        require(pageSize in 1..100)
        val anchor = state.pages.firstOrNull { it.pageIndex == currentPageIndex }
        val revision = state.viewRevision + 1
        return state.copy(viewRevision = revision, pages = anchor?.let {
            listOf(it.copy(stamp = stamp, revision = revision, pageIndex = 0, pageSize = pageSize))
        } ?: emptyList())
    }

    private fun merge(old: VideoSearchResult, new: VideoSearchResult): VideoSearchResult {
        if ((old.identityAliases + new.identityAliases).size > 128 || (old.provenance + new.provenance).size > 32)
            throw DiscoveryFailure(DiscoveryError.INVALID_RESULT)
        return old.copy(
        title = old.title ?: new.title,
        // A thumbnail may depict an earlier file revision. Do not retain it under a different
        // attribution observation, even when the logical result identity remains the same.
        thumbnailUrl = if (old.rights == new.rights) old.thumbnailUrl ?: new.thumbnailUrl else null,
        durationMs = old.durationMs ?: new.durationMs,
        description = listOfNotNull(old.description, new.description).maxByOrNull { it.length },
        publishedAtEpochMillis = old.publishedAtEpochMillis ?: new.publishedAtEpochMillis,
        sourceNamespace = old.sourceNamespace ?: new.sourceNamespace,
        sourceVideoId = old.sourceVideoId ?: new.sourceVideoId,
        provenance = old.provenance + new.provenance,
        identityAliases = old.identityAliases + new.identityAliases,
        rights = try { RightsAttribution.merge(old.rights, new.rights) } catch (_: IllegalArgumentException) {
            throw DiscoveryFailure(DiscoveryError.INVALID_RESULT)
        },
    )
    }
}
