package com.easysavevideo.discovery

import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test

class ResultPoolSortingTest {
    private val pool = ResultPool()
    private val stamp = SessionStamp("sort-session", 1)
    private fun video(id: Int, duration: Long? = null, published: Long? = null) =
        ResultNormalizer.normalize(RawVideoResult("https://video.example/watch?id=$id", true,
            sourceNamespace = "video.example", sourceVideoId = id.toString(),
            durationMs = duration, publishedAtEpochMillis = published), "source")!!
    private fun state(vararg videos: VideoSearchResult) = pool.admit(PoolState(), videos.toList(), 1).state
    private fun ids(vararg videos: VideoSearchResult) = videos.map { it.resultId }
    private fun available(state: PoolState, sort: ResultSort, policy: RepeatPolicy = RepeatPolicy.SHOW_ALL,
                          seen: (String) -> SeenRecord? = { null }) =
        pool.available(state, policy, sort, seen).map { it.result.resultId }

    @Test fun sortDefaultsToRelevanceAndDoesNotChangeSourceQueryOrProfileIdentity() {
        val original = SearchProfile("profile", "query", "en", SearchFilters(maximumDurationMs = 90_000))
        assertEquals(ResultSort.RELEVANCE, original.sortOrder)
        ResultSort.entries.forEach { sort ->
            val changed = original.copy(sortOrder = sort)
            assertEquals(original.profileId, changed.profileId)
            assertEquals(original.queryKey, changed.queryKey)
            assertEquals(original.filters, changed.filters)
        }
    }

    @Test fun allModesUseOnlyTheirMetadataAndPutUnknownValuesLast() {
        val unknown = video(1)
        val oldLong = video(2, 90_000, 100)
        val recentShort = video(3, 30_000, 300)
        val middle = video(4, 60_000, 200)
        val state = state(unknown, oldLong, recentShort, middle)
        assertEquals(ids(unknown, oldLong, recentShort, middle), available(state, ResultSort.RELEVANCE))
        assertEquals(ids(recentShort, middle, oldLong, unknown), available(state, ResultSort.NEWEST))
        assertEquals(ids(recentShort, middle, oldLong, unknown), available(state, ResultSort.DURATION_SHORTEST))
        assertEquals(ids(oldLong, middle, recentShort, unknown), available(state, ResultSort.DURATION_LONGEST))
        assertNull(state.entries.first().result.durationMs)
        assertNull(state.entries.first().result.publishedAtEpochMillis)
    }

    @Test fun unknownIsPerSortFieldAndNeverReplacedWithOtherMetadata() {
        val durationOnly = video(1, 30_000)
        val dateOnly = video(2, published = 300)
        val unknown = video(3)
        val state = state(unknown, durationOnly, dateOnly)
        assertEquals(ids(dateOnly, unknown, durationOnly), available(state, ResultSort.NEWEST))
        listOf(ResultSort.DURATION_SHORTEST, ResultSort.DURATION_LONGEST).forEach { sort ->
            assertEquals(ids(durationOnly, unknown, dateOnly), available(state, sort))
        }
    }

    @Test fun metadataTiesAndAllUnknownTiesKeepArrivalOrderRegardlessOfEntryListOrder() {
        val first = video(1, 50, 100)
        val second = video(2, 50, 100)
        val third = video(3, 50, 100)
        val admitted = state(first, second, third)
        val reversed = admitted.copy(entries = admitted.entries.reversed())
        ResultSort.entries.forEach { sort ->
            assertEquals(ids(first, second, third), available(reversed, sort))
            val unknown = reversed.copy(entries = reversed.entries.map {
                it.copy(result = it.result.copy(durationMs = null, publishedAtEpochMillis = null))
            })
            assertEquals(ids(first, second, third), available(unknown, sort))
        }
    }

    @Test fun equalArrivalAndMetadataUseStableResultIdAsFinalTiebreak() {
        val first = video(1, 50, 100)
        val second = video(2, 50, 100)
        // Pool transitions normally assign unique arrival orders. The comparator still has
        // a deterministic final key for equivalent ordering inputs.
        val state = PoolState(listOf(PoolEntry(first, 1, 0), PoolEntry(second, 1, 0)), nextOrder = 1)
        ResultSort.entries.forEach { sort ->
            assertEquals(ids(first, second).sorted(), available(state, sort))
            assertEquals(available(state, sort), available(state.copy(entries = state.entries.reversed()), sort))
        }
    }

    @Test fun preferNewHistoryBucketWinsBeforeEveryMetadataSortIncludingUnknowns() {
        val shown = video(1, 1, Long.MAX_VALUE)
        val fresh = video(2, 50, 100)
        val savedUnknown = video(3)
        val state = state(shown, fresh, savedUnknown)
        val records = mapOf(
            shown.resultId to SeenRecord("profile", shown.resultId, 1, shownAt = 2),
            savedUnknown.resultId to SeenRecord("profile", savedUnknown.resultId, 1, savedAt = 2),
        )
        ResultSort.entries.forEach { sort ->
            assertEquals(ids(fresh, savedUnknown, shown), available(state, sort, RepeatPolicy.PREFER_NEW, records::get))
        }
    }

    @Test fun explicitSortAppliesWithinBothPreferNewHistoryBuckets() {
        val freshOlder = video(1, 90, 100)
        val shownOlder = video(2, 90, 100)
        val freshNewer = video(3, 30, 300)
        val shownNewer = video(4, 30, 300)
        val state = state(shownOlder, freshOlder, shownNewer, freshNewer)
        val shownIds = ids(shownOlder, shownNewer).toSet()
        fun seen(id: String) = if (id in shownIds) SeenRecord("profile", id, 1, shownAt = 2) else null
        listOf(ResultSort.NEWEST, ResultSort.DURATION_SHORTEST).forEach { sort ->
            assertEquals(ids(freshNewer, freshOlder, shownNewer, shownOlder), available(state, sort, RepeatPolicy.PREFER_NEW, ::seen))
        }
        listOf(ResultSort.RELEVANCE, ResultSort.DURATION_LONGEST).forEach { sort ->
            assertEquals(ids(freshOlder, freshNewer, shownOlder, shownNewer), available(state, sort, RepeatPolicy.PREFER_NEW, ::seen))
        }
    }

    @Test fun strictHistoryFilteringRemainsEffectiveForEverySortAndIdentityAlias() {
        val shown = video(1, 1, 300)
        val watched = video(2, 2, 200)
        val fresh = video(3, 3, 100)
        val state = state(shown, watched, fresh)
        val records = mapOf(
            shown.identityAliases.first { it != shown.fingerprint } to SeenRecord("profile", shown.fingerprint, 1, shownAt = 2),
            watched.resultId to SeenRecord("profile", watched.resultId, 1, shownAt = 2, openedAt = 3),
        )
        ResultSort.entries.forEach { sort ->
            assertEquals(ids(fresh), available(state, sort, RepeatPolicy.HIDE_SHOWN, records::get))
            assertEquals(ids(shown, fresh).toSet(), available(state, sort, RepeatPolicy.HIDE_WATCHED, records::get).toSet())
            assertEquals(ids(shown, watched, fresh).toSet(), available(state, sort, RepeatPolicy.SHOW_ALL, records::get).toSet())
        }
    }

    @Test fun materializedPagesSurviveNewResultsMetadataEnrichmentAndChangedSort() {
        val unknown = video(1)
        val old = video(2, 100, 100)
        val recent = video(3, 300, 300)
        val middle = video(4, 200, 200)
        val initial = state(unknown, old, recent, middle)
        val (allocated, page) = pool.materialize(initial, stamp, 0, 2, RepeatPolicy.SHOW_ALL, ResultSort.NEWEST) { null }
        assertEquals(ids(recent, middle), page!!.orderedResultIds)
        val enriched = unknown.copy(durationMs = 1, publishedAtEpochMillis = 1_000)
        val incoming = video(5, 2, 2_000)
        val updated = pool.admit(allocated, listOf(enriched, incoming), 2).state
        ResultSort.entries.forEach { sort ->
            val repeated = pool.materialize(updated, stamp, 0, 1, RepeatPolicy.HIDE_SHOWN, sort) {
                SeenRecord("profile", it, 1, shownAt = 2)
            }
            assertEquals(updated, repeated.first)
            assertEquals(page, repeated.second)
        }
        val next = pool.materialize(updated, stamp, 1, 2, RepeatPolicy.SHOW_ALL, ResultSort.NEWEST) { null }
        assertEquals(ids(incoming, enriched), next.second!!.orderedResultIds)
        assertEquals(page, next.first.pages.first())
        assertEquals(ids(old), available(next.first, ResultSort.NEWEST))
    }

    @Test fun optionalSortAndOriginalPositionalOrTrailingHistoryCallsRemainCompatible() {
        val first = video(1, 90, 100)
        val second = video(2, 30, 300)
        val state = state(first, second)
        val noHistory: (String) -> SeenRecord? = { null }
        assertEquals(ids(first, second), pool.available(state, RepeatPolicy.SHOW_ALL, noHistory).map { it.result.resultId })
        assertEquals(ids(first, second), pool.available(state, RepeatPolicy.SHOW_ALL) { null }.map { it.result.resultId })
        assertEquals(ids(first, second), pool.available(state, RepeatPolicy.SHOW_ALL, seen = noHistory).map { it.result.resultId })
        val positional = pool.materialize(state, stamp, 0, 1, RepeatPolicy.SHOW_ALL, noHistory)
        val trailing = pool.materialize(state, stamp, 0, 1, RepeatPolicy.SHOW_ALL) { null }
        val named = pool.materialize(state, stamp, 0, 1, RepeatPolicy.SHOW_ALL, seen = noHistory)
        assertEquals(positional, trailing)
        assertEquals(positional, named)
        assertEquals(ids(first), positional.second!!.orderedResultIds)
    }

    @Test fun extremalKnownLongValuesSortWithoutOverflowOrSentinelCollisions() {
        val low = video(1, 1, 0)
        val high = video(2, Long.MAX_VALUE, Long.MAX_VALUE)
        val unknown = video(3)
        val state = state(unknown, low, high)
        assertEquals(ids(high, low, unknown), available(state, ResultSort.NEWEST))
        assertEquals(ids(high, low, unknown), available(state, ResultSort.DURATION_LONGEST))
        assertEquals(ids(low, high, unknown), available(state, ResultSort.DURATION_SHORTEST))
    }
}
