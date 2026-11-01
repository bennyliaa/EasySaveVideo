package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test

class CommonsStageDiagnosticsPresentationTest {
    private fun sample(index: Long, stage: CommonsStage = CommonsStage.DNS) = CommonsStageSample(
        1, index, 1, stage, CommonsStageOutcome.SUCCESS, 125, CommonsStageTiming.MEASURED)
    private fun snapshot(samples: List<CommonsStageSample> = emptyList(), open: Boolean = false,
        alias: Long? = 1, droppedSamples: Long = 0, droppedOperations: Long = 0,
        activeStages: List<CommonsStageSample> = emptyList()) = CommonsStageDiagnosticsSnapshot(
        enabled = open, sessionAlias = alias, sessionOpen = open, capacity = 128, samples = samples,
        pendingOperations = 0, droppedOperations = droppedOperations, droppedSamples = droppedSamples,
        ignoredEvents = 0, clockAnomalies = 0, activeStages = activeStages)

    @Test fun previewShowsOnlyLatest32AndReportsTruncationWithoutChangingRecorderSamples() {
        val trace = snapshot((1L..100L).map(::sample))
        val rows = CommonsStageDiagnosticsPresentation.rows(trace)
        assertEquals(32, rows.size)
        assertEquals(69L, rows.first().operationAlias)
        assertEquals(100L, rows.last().operationAlias)
        assertEquals(100, trace.samples.size)
        assertTrue(CommonsStageDiagnosticsPresentation.truncated(trace))
    }

    @Test fun absentStagesStayNotObservedRatherThanBecomingInventedZeroDurations() {
        val trace = snapshot(listOf(sample(1, CommonsStage.CACHE_HIT).copy(
            outcome = CommonsStageOutcome.OBSERVED, elapsedNanos = null, timing = CommonsStageTiming.NOT_MEASURED)))
        val missing = CommonsStageDiagnosticsPresentation.unobservedStages(trace)
        assertTrue(CommonsStage.DNS in missing)
        assertTrue(CommonsStage.TCP_CONNECT in missing)
        assertTrue(CommonsStage.TLS_HANDSHAKE in missing)
        assertFalse(CommonsStage.CACHE_HIT in missing)
        assertNull(CommonsStageDiagnosticsPresentation.rows(trace).single().elapsedNanos)
    }

    @Test fun stagePresenceUsesAllRetainedRowsEvenIfItsRowIsOutsideUiCap() {
        val trace = snapshot(listOf(sample(1, CommonsStage.TLS_HANDSHAKE)) + (2L..40L).map(::sample))
        assertFalse(CommonsStage.TLS_HANDSHAKE in CommonsStageDiagnosticsPresentation.unobservedStages(trace))
        assertTrue(CommonsStageDiagnosticsPresentation.truncated(trace))
    }

    @Test fun droppedEvidenceAndLiveOrUnstartedTraceRemainExplicit() {
        assertTrue(CommonsStageDiagnosticsPresentation.truncated(snapshot(droppedSamples = 1)))
        assertTrue(CommonsStageDiagnosticsPresentation.truncated(snapshot(droppedOperations = 1)))
        assertTrue(CommonsStageDiagnosticsPresentation.partial(snapshot(alias = null)))
        assertTrue(CommonsStageDiagnosticsPresentation.partial(snapshot(open = true)))
        assertTrue(CommonsStageDiagnosticsPresentation.partial(snapshot(droppedSamples = 1)))
        assertFalse(CommonsStageDiagnosticsPresentation.partial(snapshot(listOf(sample(1)))))
        assertFalse(CommonsStageDiagnosticsPresentation.truncated(snapshot(listOf(sample(1)))))
    }

    @Test fun activeStageIsInProgressRatherThanNotObservedOrInventedZero() {
        val active = sample(2, CommonsStage.TLS_HANDSHAKE).copy(outcome = CommonsStageOutcome.IN_PROGRESS,
            elapsedNanos = null, timing = CommonsStageTiming.NOT_MEASURED)
        val trace = snapshot((1L..40L).map(::sample), open = true, activeStages = listOf(active))
        assertFalse(CommonsStage.TLS_HANDSHAKE in CommonsStageDiagnosticsPresentation.unobservedStages(trace))
        assertEquals(active, CommonsStageDiagnosticsPresentation.rows(trace).last())
        assertEquals(32, CommonsStageDiagnosticsPresentation.rows(trace).size)
        assertNull(CommonsStageDiagnosticsPresentation.rows(trace).last().elapsedNanos)
        assertTrue(CommonsStageDiagnosticsPresentation.partial(trace))
        assertTrue(CommonsStageDiagnosticsPresentation.truncated(trace))
    }

    @Test fun safeHttpStatusAndFailureEnumArePreservedAsObserved() {
        val status = sample(1, CommonsStage.HTTP_STATUS).copy(httpStatus = 429, elapsedNanos = null,
            timing = CommonsStageTiming.NOT_MEASURED, outcome = CommonsStageOutcome.OBSERVED,
            failure = CommonsStageFailure.RATE_LIMITED)
        val row = CommonsStageDiagnosticsPresentation.rows(snapshot(listOf(status))).single()
        assertEquals(429, row.httpStatus)
        assertEquals(CommonsStageFailure.RATE_LIMITED, row.failure)
        assertNull(row.elapsedNanos)
    }
}
