package com.easysavevideo.app.library.share

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ShareReadBudgetTest {
    @Test fun boundedOffsetReadsSupportSeeking() {
        val budget = ShareReadBudget(1_000)
        assertEquals(20, budget.reserve(980, 100, 100))
        assertEquals(100, budget.reserve(0, 100, 100))
        assertEquals(0, budget.reserve(1_000, 100, 100))
        assertEquals(0, budget.reserve(Long.MAX_VALUE, 100, 100))
    }
    @Test fun readsNeverExceedBoundedChunk() {
        assertEquals(SharePolicy.MAX_PROXY_REQUEST_BYTES, ShareReadBudget(1_000_000).reserve(0, SharePolicy.MAX_PROXY_REQUEST_BYTES, SharePolicy.MAX_PROXY_REQUEST_BYTES))
        fails { ShareReadBudget(1_000_000).reserve(0, SharePolicy.MAX_PROXY_REQUEST_BYTES + 1, SharePolicy.MAX_PROXY_REQUEST_BYTES + 1) }
    }
    @Test fun atMostEightTimesArtifactCanBeDeliveredPerDescriptor() {
        val budget = ShareReadBudget(10)
        repeat(8) { assertEquals(10, budget.reserve(0, 10, 10)) }
        fails { budget.reserve(0, 1, 1) }
    }
    @Test fun invalidOffsetsAndBufferRangesRejected() {
        val budget = ShareReadBudget(10)
        fails { budget.reserve(-1, 1, 1) }
        fails { budget.reserve(0, 11, 10) }
        fails { budget.reserve(0, -1, 10) }
    }
    @Test fun tinyReadsHaveFiniteCallBudget() {
        val budget = ShareReadBudget(1_000_000)
        repeat(ShareReadBudget.MAX_READ_CALLS) { assertEquals(1, budget.reserve(0, 1, 1)) }
        fails { budget.reserve(0, 1, 1) }
    }
    private fun fails(block: () -> Unit) { try { block(); fail("Expected bounded read failure") } catch (_: IOException) { } }
}
