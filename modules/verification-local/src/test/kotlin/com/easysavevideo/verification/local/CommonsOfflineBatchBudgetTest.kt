package com.easysavevideo.verification.local

import org.junit.Assert.*
import org.junit.Test

/** Pure state-machine rehearsal; reserves simulated requests, never performs I/O. */
internal class CommonsOfflineBatchBudget {
    enum class Admission { RESERVED, WAIT, BUSY, STOPPED, OVER_BUDGET }
    private var requests = 0
    private var pages = 0
    private var lastStart: Long? = null
    private var active = false
    private var stopped = false
    fun reserve(nowMillis: Long, page: Boolean): Admission {
        if (stopped) return Admission.STOPPED
        if (active) return Admission.BUSY
        if (nowMillis < 0 || requests >= 12 || page && pages >= 4) { stopped = true; return Admission.OVER_BUDGET }
        if (lastStart?.let { nowMillis < it || nowMillis - it < 5000 } == true) return Admission.WAIT
        requests++; if (page) pages++
        lastStart = nowMillis; active = true
        return Admission.RESERVED
    }
    fun complete(outcome: DevelopmentCommonsStaticFixturePolicy.Outcome) {
        check(active)
        active = false
        if (outcome !in setOf(DevelopmentCommonsStaticFixturePolicy.Outcome.PAGE_EVIDENCE,
                DevelopmentCommonsStaticFixturePolicy.Outcome.UNKNOWN)) stopped = true
    }
    fun cancel() { stopped = true; active = false }
}

class CommonsOfflineBatchBudgetTest {
    private val ok = DevelopmentCommonsStaticFixturePolicy.Outcome.UNKNOWN
    @Test fun fourPagesWithRobotsFirstRespectSingleSlotAndFiveSecondSpacing() {
        val budget = CommonsOfflineBatchBudget()
        assertEquals(CommonsOfflineBatchBudget.Admission.RESERVED,budget.reserve(0,false)) // robots included
        assertEquals(CommonsOfflineBatchBudget.Admission.BUSY,budget.reserve(5000,true))
        budget.complete(ok)
        assertEquals(CommonsOfflineBatchBudget.Admission.WAIT,budget.reserve(4999,true))
        for (i in 1..4) {
            assertEquals(CommonsOfflineBatchBudget.Admission.RESERVED,budget.reserve(i*5000L,true))
            budget.complete(ok)
        }
        assertEquals(CommonsOfflineBatchBudget.Admission.OVER_BUDGET,budget.reserve(25000,true))
        assertEquals(CommonsOfflineBatchBudget.Admission.STOPPED,budget.reserve(30000,false))
    }
    @Test fun everyPreflightAndHypotheticalRetryConsumesSameGlobalRequestBudget() {
        val budget = CommonsOfflineBatchBudget()
        for (i in 0..11) {
            assertEquals(CommonsOfflineBatchBudget.Admission.RESERVED,budget.reserve(i*5000L,false))
            budget.complete(ok)
        }
        assertEquals(CommonsOfflineBatchBudget.Admission.OVER_BUDGET,budget.reserve(60000,false))
    }
    @Test fun refusalRateLimitServiceErrorCancellationAndTimeoutStopEntireSource() {
        for (outcome in listOf(DevelopmentCommonsStaticFixturePolicy.Outcome.DENIED,
            DevelopmentCommonsStaticFixturePolicy.Outcome.RATE_LIMITED,
            DevelopmentCommonsStaticFixturePolicy.Outcome.SERVICE_STOP,
            DevelopmentCommonsStaticFixturePolicy.Outcome.CANCELLED,
            DevelopmentCommonsStaticFixturePolicy.Outcome.TIMEOUT,
            DevelopmentCommonsStaticFixturePolicy.Outcome.OVER_BUDGET)) {
            val budget=CommonsOfflineBatchBudget()
            assertEquals(CommonsOfflineBatchBudget.Admission.RESERVED,budget.reserve(0,true))
            budget.complete(outcome)
            assertEquals(CommonsOfflineBatchBudget.Admission.STOPPED,budget.reserve(5000,true))
        }
        val cancelled=CommonsOfflineBatchBudget();cancelled.cancel()
        assertEquals(CommonsOfflineBatchBudget.Admission.STOPPED,cancelled.reserve(0,true))
    }
}
