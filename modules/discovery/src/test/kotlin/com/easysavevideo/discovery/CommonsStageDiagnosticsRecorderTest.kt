package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

class CommonsStageDiagnosticsRecorderTest {
    private fun session(recorder: CommonsStageDiagnosticsRecorder) = recorder.beginSession(recorder.captureAuthorization())

    @Test fun defaultOffHasNoSessionCapabilityOrMetadata() {
        val recorder = CommonsStageDiagnosticsRecorder()
        assertNull(recorder.captureAuthorization())
        assertNull(recorder.beginSession(null))
        assertNull(recorder.started(null, 0))
        assertFalse(recorder.snapshot().enabled)
        assertNull(recorder.snapshot().sessionAlias)
        assertTrue(recorder.snapshot().samples.isEmpty())
    }
    @Test fun exactOneUseArmRejectsStaleForeignAndRepeatedAuthorization() {
        val recorder = CommonsStageDiagnosticsRecorder()
        val old = recorder.setEnabled(true)
        val next = recorder.setEnabled(true)
        assertNull(recorder.beginSession(old))
        assertSame(next, recorder.captureAuthorization())
        assertNull(recorder.beginSession(CommonsStageDiagnosticsRecorder(true).captureAuthorization()))
        val active = recorder.beginSession(next)
        assertNotNull(active); assertNull(recorder.captureAuthorization())
        assertNull(recorder.beginSession(next))
        assertNotNull(recorder.started(active, 0))
        assertEquals(1, recorder.snapshot().pendingOperations)
    }
    @Test fun measuredSignedTicksAndClockAnomaliesNeverPublishNegativeOrWrappedDuration() {
        val recorder = CommonsStageDiagnosticsRecorder(true)
        val session = session(recorder)
        val trace = recorder.started(session, 0)!!
        val span = recorder.begin(trace, CommonsStage.DNS, -100)!!
        recorder.end(span, CommonsStageOutcome.SUCCESS, null, -90)
        val backward = recorder.begin(trace, CommonsStage.TCP_CONNECT, 10)!!
        recorder.end(backward, CommonsStageOutcome.SUCCESS, null, 9)
        val overflow = recorder.begin(trace, CommonsStage.TLS_HANDSHAKE, Long.MIN_VALUE)!!
        recorder.end(overflow, CommonsStageOutcome.SUCCESS, null, Long.MAX_VALUE)
        val samples = recorder.snapshot().samples
        assertEquals(listOf(10L, null, null), samples.map { it.elapsedNanos })
        assertEquals(listOf(CommonsStageTiming.MEASURED, CommonsStageTiming.CLOCK_BACKWARDS,
            CommonsStageTiming.CLOCK_OVERFLOW), samples.map { it.timing })
        assertEquals(2L, recorder.snapshot().clockAnomalies)
        assertTrue(recorder.snapshot().incomplete)
    }
    @Test fun signalMakesActiveStageIncompleteAndLateEndCannotReplaceIt() {
        val recorder = CommonsStageDiagnosticsRecorder(true)
        val trace = recorder.started(session(recorder), 0)!!
        val dns = recorder.begin(trace, CommonsStage.DNS, 100)!!
        trace.event(CommonsStage.DEADLINE_SIGNAL, CommonsStageFailure.DEADLINE_EXCEEDED)
        recorder.end(dns, CommonsStageOutcome.SUCCESS, null, 150)
        trace.finish(CommonsStageOutcome.DEADLINE)
        val incomplete = recorder.snapshot().samples.single { it.stage == CommonsStage.DNS }
        assertEquals(CommonsStageOutcome.INCOMPLETE, incomplete.outcome)
        assertNull(incomplete.elapsedNanos)
        assertEquals(CommonsStageTiming.NOT_MEASURED, incomplete.timing)
        assertEquals(1L, recorder.snapshot().ignoredEvents)
        assertEquals(0, recorder.snapshot().pendingOperations)
    }
    @Test fun duplicateStageAndTerminalAreIgnoredWithoutAffectingAnotherAttempt() {
        val recorder = CommonsStageDiagnosticsRecorder(true)
        val session = session(recorder)
        val first = recorder.started(session, 0)!!
        first.event(CommonsStage.CACHE_HIT); first.event(CommonsStage.CACHE_HIT)
        first.finish(CommonsStageOutcome.SUCCESS)
        val retry = recorder.started(session, 1)!!
        first.finish(CommonsStageOutcome.FAILURE)
        retry.event(CommonsStage.METADATA_COOLDOWN, CommonsStageFailure.RATE_LIMITED)
        retry.finish(CommonsStageOutcome.FAILURE)
        assertEquals(listOf(1L, 1L, 2L, 2L), recorder.snapshot().samples.map { it.operationAlias })
        assertEquals(listOf(0, 0, 1, 1), recorder.snapshot().samples.map { it.attempt })
        assertEquals(2L, recorder.snapshot().ignoredEvents)
    }
    @Test fun stoppedAndReplacedSessionsNeverAcceptLateEventsOrCloseTheNewSession() {
        val recorder = CommonsStageDiagnosticsRecorder(true)
        val old = session(recorder)
        val first = recorder.started(old, 0)!!
        val span = first.begin(CommonsStage.DNS)!!
        recorder.stop()
        val stopped = recorder.snapshot()
        assertFalse(stopped.enabled); assertFalse(stopped.sessionOpen)
        span.complete(); first.event(CommonsStage.CANCEL_SIGNAL)
        assertEquals(stopped, recorder.snapshot())
        val next = recorder.beginSession(recorder.setEnabled(true))
        recorder.endSession(old)
        first.event(CommonsStage.SERVER_COOLDOWN)
        assertNotNull(recorder.started(next, 0))
        assertTrue(recorder.snapshot().samples.isEmpty())
        assertNotEquals(old!!.alias, next!!.alias)
    }
    @Test fun boundedPendingAndSamplesExposeLossWithoutRetainingUnboundedIdentity() {
        val recorder = CommonsStageDiagnosticsRecorder(true, 2)
        val session = session(recorder)
        val first = recorder.started(session, 0)!!
        val second = recorder.started(session, 0)!!
        assertNull(recorder.started(session, 0))
        first.event(CommonsStage.CACHE_HIT); first.finish(CommonsStageOutcome.SUCCESS)
        second.event(CommonsStage.METADATA_COOLDOWN); second.finish(CommonsStageOutcome.FAILURE)
        assertEquals(2, recorder.snapshot().samples.size)
        assertEquals(1L, recorder.snapshot().droppedOperations)
        assertEquals(2L, recorder.snapshot().droppedSamples)
        assertTrue(recorder.snapshot().incomplete)
    }
    @Test fun snapshotsAreUnmodifiableDetachedAndContainOnlyClosedTechnicalValues() {
        val recorder = CommonsStageDiagnosticsRecorder(true)
        val trace = recorder.started(session(recorder), 0)!!
        trace.httpStatus(999); trace.httpStatus(503)
        val before = recorder.snapshot()
        assertEquals(503, before.samples.single().httpStatus)
        try { (before.samples as MutableList).clear(); fail("Snapshot must be immutable") }
        catch (_: UnsupportedOperationException) { }
        trace.event(CommonsStage.SERVER_COOLDOWN)
        assertEquals(1, before.samples.size)
        assertFalse(before.toString().contains("https"))
        assertFalse(before.toString().contains("Exception"))
    }
    @Test fun operationCopiesCannotInheritTracingCapability() {
        val recorder = CommonsStageDiagnosticsRecorder(true)
        val original = operation()
        original.commonsStageTrace = recorder.started(session(recorder), 0)
        assertNotNull(original.commonsStageTrace)
        assertNull(original.copy().commonsStageTrace)
        assertEquals(original, original.copy())
    }
    @Test fun deadlineSignalRetainsDeadlineClassificationWhenItsCancellationFlagArrives() {
        val recorder = CommonsStageDiagnosticsRecorder(true)
        val cancellation = DiscoveryCancellation()
        val trace = recorder.started(session(recorder), 0, cancellation)!!
        val span = trace.begin(CommonsStage.DNS)!!
        trace.event(CommonsStage.DEADLINE_SIGNAL, CommonsStageFailure.DEADLINE_EXCEEDED)
        cancellation.cancel()
        span.complete()
        trace.finish(CommonsStageOutcome.SUCCESS)
        val samples = recorder.snapshot().samples
        assertEquals(CommonsStageOutcome.INCOMPLETE, samples.single { it.stage == CommonsStage.DNS }.outcome)
        assertEquals(CommonsStageOutcome.DEADLINE, samples.single { it.stage == CommonsStage.OPERATION }.outcome)
        assertFalse(samples.any { it.stage == CommonsStage.CANCEL_SIGNAL })
    }

    @Test fun observedCancellationOrLocalDeadlineFencesNewStagesWithoutOriginalTokenCancellation() {
        for ((signal, terminal) in listOf(CommonsStage.CANCEL_SIGNAL to CommonsStageOutcome.CANCELLED,
            CommonsStage.DEADLINE_SIGNAL to CommonsStageOutcome.DEADLINE)) {
            val recorder = CommonsStageDiagnosticsRecorder(true)
            val cancellation = DiscoveryCancellation()
            val trace = recorder.started(session(recorder), 0, cancellation)!!
            val span = trace.begin(CommonsStage.DNS)!!
            trace.event(signal)
            assertFalse(cancellation.isCancelled)
            assertNull(trace.begin(CommonsStage.TCP_CONNECT))
            trace.httpStatus(200)
            span.complete()
            trace.finish(CommonsStageOutcome.SUCCESS)
            val samples = recorder.snapshot().samples
            assertEquals(CommonsStageOutcome.INCOMPLETE, samples.single { it.stage == CommonsStage.DNS }.outcome)
            assertFalse(samples.any { it.stage == CommonsStage.TCP_CONNECT || it.stage == CommonsStage.HTTP_STATUS })
            assertEquals(terminal, samples.single { it.stage == CommonsStage.OPERATION }.outcome)
        }
    }

    @Test fun connectBudgetSignalFencesCleanupAndRetainsItsCauseThroughRuntimeFinish() {
        val recorder = CommonsStageDiagnosticsRecorder(true)
        val cancellation = DiscoveryCancellation()
        val trace = recorder.started(session(recorder), 0, cancellation)!!
        val dns = recorder.begin(trace, CommonsStage.DNS, 10)!!
        recorder.end(dns, CommonsStageOutcome.SUCCESS, null, 20)
        val tls = recorder.begin(trace, CommonsStage.TLS_HANDSHAKE, 30)!!
        trace.event(CommonsStage.CONNECT_BUDGET_SIGNAL, CommonsStageFailure.CONNECT_BUDGET_EXCEEDED)
        recorder.end(tls, CommonsStageOutcome.FAILURE, CommonsStageFailure.IO_FAILURE, 40)
        trace.event(CommonsStage.PROVIDER_FAILURE, CommonsStageFailure.IO_FAILURE)
        assertNull(trace.begin(CommonsStage.REQUEST_WRITE))
        trace.httpStatus(200)
        trace.finish(CommonsStageOutcome.SUCCESS, CommonsStageFailure.SERVICE_FAILURE)
        val snapshot = recorder.snapshot()
        assertEquals(listOf(CommonsStage.DNS, CommonsStage.CONNECT_BUDGET_SIGNAL,
            CommonsStage.TLS_HANDSHAKE, CommonsStage.OPERATION), snapshot.samples.map { it.stage })
        val budget = snapshot.samples[1]
        assertEquals(CommonsStageOutcome.OBSERVED, budget.outcome)
        assertEquals(CommonsStageTiming.NOT_MEASURED, budget.timing); assertNull(budget.elapsedNanos)
        val incomplete = snapshot.samples[2]
        assertEquals(CommonsStageOutcome.INCOMPLETE, incomplete.outcome)
        assertEquals(CommonsStageFailure.CONNECT_BUDGET_EXCEEDED, incomplete.failure)
        assertNull(incomplete.elapsedNanos)
        assertEquals(CommonsStageOutcome.FAILURE, snapshot.samples.last().outcome)
        assertEquals(CommonsStageFailure.CONNECT_BUDGET_EXCEEDED, snapshot.samples.last().failure)
        assertEquals(0, snapshot.pendingOperations); assertTrue(snapshot.activeStages.isEmpty())
    }

    @Test fun laterRealCancellationOrDeadlineRetainsPriorityWithoutErasingEarlierBudgetEvidence() {
        for (terminal in listOf("flag", "cancel", "deadline", "cancel-outcome", "deadline-outcome")) {
            val recorder = CommonsStageDiagnosticsRecorder(true)
            val cancellation = DiscoveryCancellation()
            val trace = recorder.started(session(recorder), 0, cancellation)!!
            trace.begin(CommonsStage.TLS_HANDSHAKE)
            trace.event(CommonsStage.CONNECT_BUDGET_SIGNAL, CommonsStageFailure.CONNECT_BUDGET_EXCEEDED)
            when (terminal) {
                "flag" -> cancellation.cancel()
                "cancel" -> trace.event(CommonsStage.CANCEL_SIGNAL, CommonsStageFailure.CANCELLED)
                "deadline" -> trace.event(CommonsStage.DEADLINE_SIGNAL, CommonsStageFailure.DEADLINE_EXCEEDED)
                else -> Unit
            }
            trace.event(CommonsStage.PROVIDER_FAILURE, CommonsStageFailure.IO_FAILURE)
            trace.finish(when (terminal) {
                "cancel-outcome" -> CommonsStageOutcome.CANCELLED
                "deadline-outcome" -> CommonsStageOutcome.DEADLINE
                else -> CommonsStageOutcome.FAILURE
            }, CommonsStageFailure.SERVICE_FAILURE)
            val samples = recorder.snapshot().samples
            assertEquals(1, samples.count { it.stage == CommonsStage.CONNECT_BUDGET_SIGNAL })
            val tls = samples.single { it.stage == CommonsStage.TLS_HANDSHAKE }
            assertEquals(CommonsStageOutcome.INCOMPLETE, tls.outcome)
            assertEquals(CommonsStageFailure.CONNECT_BUDGET_EXCEEDED, tls.failure)
            assertNull(tls.elapsedNanos)
            val operation = samples.single { it.stage == CommonsStage.OPERATION }
            val deadline = terminal.startsWith("deadline")
            assertEquals(if (deadline) CommonsStageOutcome.DEADLINE else CommonsStageOutcome.CANCELLED, operation.outcome)
            assertEquals(if (deadline) CommonsStageFailure.DEADLINE_EXCEEDED else CommonsStageFailure.CANCELLED, operation.failure)
            assertFalse(samples.any { it.stage == CommonsStage.PROVIDER_FAILURE })
        }
    }

    @Test fun earlierCancelFlagOrTerminalSignalRejectsLateConnectBudgetObservation() {
        for (first in listOf("flag", "cancel", "deadline")) {
            val recorder = CommonsStageDiagnosticsRecorder(true)
            val cancellation = DiscoveryCancellation()
            val trace = recorder.started(session(recorder), 0, cancellation)!!
            val tls = trace.begin(CommonsStage.TLS_HANDSHAKE)!!
            when (first) {
                "flag" -> cancellation.cancel()
                "cancel" -> trace.event(CommonsStage.CANCEL_SIGNAL, CommonsStageFailure.CANCELLED)
                else -> trace.event(CommonsStage.DEADLINE_SIGNAL, CommonsStageFailure.DEADLINE_EXCEEDED)
            }
            trace.event(CommonsStage.CONNECT_BUDGET_SIGNAL, CommonsStageFailure.CONNECT_BUDGET_EXCEEDED)
            tls.failed(CommonsStageFailure.IO_FAILURE)
            trace.finish(CommonsStageOutcome.FAILURE)
            val samples = recorder.snapshot().samples
            assertFalse(samples.any { it.stage == CommonsStage.CONNECT_BUDGET_SIGNAL })
            assertEquals(if (first == "deadline") CommonsStageOutcome.DEADLINE else CommonsStageOutcome.CANCELLED,
                samples.single { it.stage == CommonsStage.OPERATION }.outcome)
        }
    }

    @Test fun connectBudgetDoesNotReplaceCompletedPhasesOrInventUnstartedPhases() {
        for (completedTls in listOf(false, true)) {
            val recorder = CommonsStageDiagnosticsRecorder(true)
            val trace = recorder.started(session(recorder), 0)!!
            if (completedTls) {
                val tls = recorder.begin(trace, CommonsStage.TLS_HANDSHAKE, 10)!!
                recorder.end(tls, CommonsStageOutcome.SUCCESS, null, 25)
            }
            trace.event(CommonsStage.CONNECT_BUDGET_SIGNAL, CommonsStageFailure.CONNECT_BUDGET_EXCEEDED)
            trace.finish(CommonsStageOutcome.FAILURE)
            val tls = recorder.snapshot().samples.filter { it.stage == CommonsStage.TLS_HANDSHAKE }
            if (completedTls) {
                assertEquals(CommonsStageOutcome.SUCCESS, tls.single().outcome)
                assertEquals(15L, tls.single().elapsedNanos)
            } else assertTrue(tls.isEmpty())
        }
    }

    @Test fun disabledAndReplacedCapturesRejectConnectBudgetEventsWithoutMutatingEvidence() {
        val recorder = CommonsStageDiagnosticsRecorder(true)
        val trace = recorder.started(session(recorder), 0)!!
        recorder.stop()
        val stopped = recorder.snapshot()
        trace.event(CommonsStage.CONNECT_BUDGET_SIGNAL, CommonsStageFailure.CONNECT_BUDGET_EXCEEDED)
        assertEquals(stopped, recorder.snapshot())
        val next = recorder.beginSession(recorder.setEnabled(true))
        recorder.started(next, 0)
        val replacement = recorder.snapshot()
        trace.event(CommonsStage.CONNECT_BUDGET_SIGNAL, CommonsStageFailure.CONNECT_BUDGET_EXCEEDED)
        assertEquals(replacement, recorder.snapshot())
    }

}
