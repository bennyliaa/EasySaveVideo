package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.RepeatPolicy
import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryQualityPresentationTest {
    private fun progress(state: ProviderDiscoveryState, error: DiscoveryError? = null,
        continued: Boolean = false, success: Long? = null) =
        ProviderDiscoveryProgress("source", state, continued, success, null, 0, error)

    @Test fun everySortHasDistinctTruthfulLabel() {
        assertEquals(4, ResultSort.entries.map(DiscoveryPresentationPolicy::sortLabel).toSet().size)
        assertEquals("来源顺序", DiscoveryPresentationPolicy.sortLabel(ResultSort.RELEVANCE))
    }
    @Test fun sortDoesNotChangeFunctionalHistoryOrSourceQueryIdentity() {
        val base = DiscoveryPresentationPolicy.profile("cats", "en", SearchFilters(), RepeatPolicy.PREFER_NEW, false)
        ResultSort.entries.forEach {
            assertEquals(base.profileId, base.copy(sortOrder = it).profileId)
            assertEquals(base.queryKey, base.copy(sortOrder = it).queryKey)
        }
    }
    @Test fun progressNeverInventsCompletionPercentageOrPageCount() {
        ProviderDiscoveryState.entries.forEach {
            val text = DiscoveryPresentationPolicy.providerProgressText(progress(it))
            assertTrue(text.startsWith("来源 source：")); assertFalse(text.contains("%"))
            assertFalse(text.contains("有已提交结果页")); assertFalse(text.contains("续页已保存"))
        }
    }
    @Test fun savedCursorAndCommittedResponseAreIndependentFacts() {
        val text = DiscoveryPresentationPolicy.providerProgressText(progress(ProviderDiscoveryState.READY, continued = true, success = 100))
        assertTrue(text.contains("有已提交结果页")); assertTrue(text.contains("续页已保存"))
        val exhausted = DiscoveryPresentationPolicy.providerProgressText(progress(ProviderDiscoveryState.EXHAUSTED, success = 100))
        assertTrue(exhausted.contains("已无后续页")); assertFalse(exhausted.contains("续页已保存"))
    }
    @Test fun blockedStatesExplainUserActionRatherThanPretendRetry() {
        assertTrue(DiscoveryPresentationPolicy.providerProgressText(progress(ProviderDiscoveryState.BLOCKED, DiscoveryError.UNSUPPORTED_FILTER)).contains("不支持当前筛选"))
        assertTrue(DiscoveryPresentationPolicy.providerProgressText(progress(ProviderDiscoveryState.BLOCKED, DiscoveryError.INVALID_QUERY)).contains("关键词不受支持"))
        assertTrue(DiscoveryPresentationPolicy.providerProgressText(progress(ProviderDiscoveryState.BLOCKED, DiscoveryError.SOURCE_CONFIGURATION)).contains("来源配置不可用"))
    }
    @Test fun invalidLowSurrogateSurvivesBoundingAndIsRejected() {
        assertFalse(DiscoveryPresentationPolicy.validQuery(DiscoveryPresentationPolicy.boundedQuery("bad\uDC00query")))
        assertFalse(DiscoveryPresentationPolicy.validQuery(DiscoveryPresentationPolicy.boundedQuery("bad\uD800query")))
        assertTrue(DiscoveryPresentationPolicy.validQuery(DiscoveryPresentationPolicy.boundedQuery("valid\uD83D\uDE00")))
    }
    @Test fun progressToStringDoesNotExportFunctionalFacts() {
        assertEquals("ProviderDiscoveryProgress([redacted])", progress(ProviderDiscoveryState.READY, continued = true, success = 100).toString())
    }
}
