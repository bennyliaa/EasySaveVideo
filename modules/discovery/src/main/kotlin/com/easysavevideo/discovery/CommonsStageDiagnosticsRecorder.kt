package com.easysavevideo.discovery

import java.util.ArrayDeque
import java.util.Collections
import java.util.EnumMap
import java.util.EnumSet
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Only actual call-site observations. HTTP_EXCHANGE includes headers and the bounded body when read; denial/slowdown can be header-only. It is not TTFB. */
enum class CommonsStage {
    DNS, TCP_CONNECT, TLS_HANDSHAKE, REQUEST_WRITE, HTTP_EXCHANGE, HTTP_STATUS, JSON_PARSE,
    CACHE_HIT, METADATA_COOLDOWN, TRANSPORT_COOLDOWN, SERVER_COOLDOWN,
    CANCEL_SIGNAL, DEADLINE_SIGNAL, PROVIDER_FAILURE, OPERATION,
    CONNECT_BUDGET_SIGNAL,
}
enum class CommonsStageOutcome { IN_PROGRESS, SUCCESS, FAILURE, OBSERVED, CANCELLED, DEADLINE, INCOMPLETE }
enum class CommonsStageTiming { MEASURED, NOT_MEASURED, CLOCK_BACKWARDS, CLOCK_OVERFLOW }
/** Closed technical vocabulary. Never derive these values from exception messages or class names. */
enum class CommonsStageFailure {
    DNS_FAILURE, CONNECT_FAILURE, TLS_FAILURE, IO_TIMEOUT, IO_FAILURE, INTERRUPTED,
    INVALID_RESPONSE, RESPONSE_TOO_LARGE, UNSAFE_ADDRESS, REDIRECT_REJECTED, SERVICE_DENIED,
    RATE_LIMITED, BUSY, CANCELLED, DEADLINE_EXCEEDED, CONFIGURATION, INVALID_REQUEST,
    UNSUPPORTED_FILTER, SERVICE_FAILURE, OTHER,
    CONNECT_BUDGET_EXCEEDED,
}
data class CommonsStageSample(
    val sessionAlias: Long,
    val operationAlias: Long,
    val attempt: Int,
    val stage: CommonsStage,
    val outcome: CommonsStageOutcome,
    val elapsedNanos: Long?,
    val timing: CommonsStageTiming,
    val failure: CommonsStageFailure? = null,
    val httpStatus: Int? = null,
)
data class CommonsStageDiagnosticsSnapshot(
    val enabled: Boolean,
    val sessionAlias: Long?,
    val sessionOpen: Boolean,
    val capacity: Int,
    val samples: List<CommonsStageSample>,
    val pendingOperations: Int,
    val droppedOperations: Long,
    val droppedSamples: Long,
    val ignoredEvents: Long,
    val clockAnomalies: Long,
    val armed: Boolean = false,
    val activeStages: List<CommonsStageSample> = emptyList(),
) {
    val incomplete: Boolean get() = pendingOperations > 0 || droppedOperations > 0 || droppedSamples > 0 ||
        clockAnomalies > 0 || samples.any { it.outcome == CommonsStageOutcome.INCOMPLETE }
}

/**
 * Default-off, bounded local evidence. No strings, payloads, observers, injected clocks, I/O or logs.
 * The production clock is System.nanoTime only; explicit numeric ticks are internal offline-test seams.
 * Disabled/stale capabilities do not read that clock. All locks protect bounded in-memory work only.
 * Durations describe observed calls, never server cause, cancellation cleanup or publication latency.
 * No sample for a stage means NOT OBSERVED, never a zero-duration measurement.
 */
class CommonsStageCapture internal constructor() { override fun toString() = "CommonsStageCapture([opaque])" }

class CommonsStageDiagnosticsRecorder(enabled: Boolean = false, private val capacity: Int = 128) {
    init { require(capacity in 1..1024) }
    internal class Session internal constructor(val alias: Long)
    private class Pending(val trace: CommonsStageTrace, val alias: Long, val attempt: Int) {
        val active = EnumMap<CommonsStage, CommonsStageSpan>(CommonsStage::class.java)
        val seen = EnumSet.noneOf(CommonsStage::class.java)
    }
    private class State(val session: Session) {
        var open = true
        var sequence = 0L
        val pending = IdentityHashMap<CommonsStageTrace, Pending>()
        val samples = ArrayDeque<CommonsStageSample>()
        var droppedOperations = 0L
        var droppedSamples = 0L
        var ignored = 0L
        var anomalies = 0L
    }
    private var enabled = enabled
    private var armed: CommonsStageCapture? = if (enabled) CommonsStageCapture() else null
    private var generation = 0L
    private var state: State? = null

    /** Enable arms the next explicitly designated search. It never launches or attaches to work. */
    @Synchronized fun setEnabled(value: Boolean): CommonsStageCapture? {
        enabled = value
        state = null
        armed = if (value) CommonsStageCapture() else null
        return armed
    }
    @Synchronized fun captureAuthorization(): CommonsStageCapture? = armed
    /** Revoke capabilities, retain detached evidence, and stop collecting without cancelling work. */
    @Synchronized fun stop() { enabled = false; armed = null; state?.let(::seal) }

    /** Exact one-use arm identity prevents stale queued searches from consuming a newer UI arm. */
    @Synchronized internal fun beginSession(capture: CommonsStageCapture?): Session? {
        if (!enabled || capture == null || capture !== armed || generation == Long.MAX_VALUE) return null
        armed = null
        state?.let(::seal)
        return Session(++generation).also { state = State(it) }
    }
    @Synchronized internal fun endSession(session: Session?) {
        state?.takeIf { it.session === session }?.let(::seal)
    }
    @Synchronized internal fun started(session: Session?, attempt: Int, cancellation: DiscoveryCancellation? = null): CommonsStageTrace? {
        val current = state?.takeIf { enabled && it.open && it.session === session } ?: return null
        if (current.pending.size >= minOf(capacity, 64) || current.sequence == Long.MAX_VALUE) {
            current.droppedOperations = increment(current.droppedOperations); return null
        }
        val trace = CommonsStageTrace(this, current.session, cancellation?.cancellationSignal ?: AtomicBoolean(false))
        current.pending[trace] = Pending(trace, ++current.sequence, attempt)
        return trace
    }
    @Synchronized internal fun begin(trace: CommonsStageTrace, stage: CommonsStage, tick: Long? = null): CommonsStageSpan? {
        val (current, pending) = current(trace) ?: return null
        if (!pending.seen.add(stage)) { current.ignored = increment(current.ignored); return null }
        return CommonsStageSpan(this, trace, stage, tick ?: System.nanoTime()).also { pending.active[stage] = it }
    }
    @Synchronized internal fun end(span: CommonsStageSpan, outcome: CommonsStageOutcome, failure: CommonsStageFailure?, tick: Long? = null) {
        val (current, pending) = current(span.trace) ?: return
        if (pending.active[span.stage] !== span) { current.ignored = increment(current.ignored); return }
        pending.active.remove(span.stage)
        val end = tick ?: System.nanoTime()
        val elapsed = try { Math.subtractExact(end, span.started) } catch (_: ArithmeticException) { null }
        val timing = when { elapsed == null -> CommonsStageTiming.CLOCK_OVERFLOW
            elapsed < 0 -> CommonsStageTiming.CLOCK_BACKWARDS
            else -> CommonsStageTiming.MEASURED }
        if (timing != CommonsStageTiming.MEASURED) current.anomalies = increment(current.anomalies)
        append(current, pending, span.stage, outcome, elapsed?.takeIf { timing == CommonsStageTiming.MEASURED }, timing, failure)
    }
    @Synchronized internal fun event(trace: CommonsStageTrace, stage: CommonsStage, failure: CommonsStageFailure?) {
        val (current, pending) = current(trace, allowCancelled = stage == CommonsStage.CANCEL_SIGNAL ||
            stage == CommonsStage.DEADLINE_SIGNAL) ?: return
        if (!pending.seen.add(stage)) { current.ignored = increment(current.ignored); return }
        append(current, pending, stage, CommonsStageOutcome.OBSERVED, null, CommonsStageTiming.NOT_MEASURED, failure)
        if (stage == CommonsStage.CANCEL_SIGNAL || stage == CommonsStage.DEADLINE_SIGNAL ||
            stage == CommonsStage.CONNECT_BUDGET_SIGNAL) {
            pending.active.keys.forEach { append(current, pending, it, CommonsStageOutcome.INCOMPLETE,
                null, CommonsStageTiming.NOT_MEASURED, failure) }
            pending.active.clear()
        }
    }
    @Synchronized internal fun httpStatus(trace: CommonsStageTrace, status: Int) {
        if (status !in 200..599) return
        val (current, pending) = current(trace) ?: return
        if (!pending.seen.add(CommonsStage.HTTP_STATUS)) { current.ignored = increment(current.ignored); return }
        append(current, pending, CommonsStage.HTTP_STATUS, CommonsStageOutcome.OBSERVED,
            null, CommonsStageTiming.NOT_MEASURED, null, status)
    }
    @Synchronized internal fun finish(trace: CommonsStageTrace, outcome: CommonsStageOutcome, failure: CommonsStageFailure?) {
        val (current, pending) = current(trace, allowCancelled = true) ?: return
        val budgetObserved = CommonsStage.CONNECT_BUDGET_SIGNAL in pending.seen
        val terminal = when {
            outcome == CommonsStageOutcome.DEADLINE || CommonsStage.DEADLINE_SIGNAL in pending.seen -> CommonsStageOutcome.DEADLINE
            outcome == CommonsStageOutcome.CANCELLED || trace.cancellationSignal.get() ||
                CommonsStage.CANCEL_SIGNAL in pending.seen -> CommonsStageOutcome.CANCELLED
            budgetObserved -> CommonsStageOutcome.FAILURE
            else -> outcome
        }
        val terminalFailure = if (!budgetObserved) failure else when (terminal) {
            CommonsStageOutcome.DEADLINE -> CommonsStageFailure.DEADLINE_EXCEEDED
            CommonsStageOutcome.CANCELLED -> CommonsStageFailure.CANCELLED
            else -> CommonsStageFailure.CONNECT_BUDGET_EXCEEDED
        }
        finishPending(current, pending, terminal, terminalFailure)
        current.pending.remove(trace)
    }
    @Synchronized fun snapshot(): CommonsStageDiagnosticsSnapshot {
        val current = state
        current?.takeIf { it.open }?.pending?.values?.forEach { reconcileCancellation(current, it) }
        return CommonsStageDiagnosticsSnapshot(enabled, current?.session?.alias, current?.open == true, capacity,
            Collections.unmodifiableList(current?.samples?.toList().orEmpty()), current?.pending?.size ?: 0,
            current?.droppedOperations ?: 0, current?.droppedSamples ?: 0, current?.ignored ?: 0, current?.anomalies ?: 0,
            armed != null, Collections.unmodifiableList(current?.pending?.values?.flatMap { pending ->
                pending.active.keys.map { stage -> CommonsStageSample(current.session.alias, pending.alias, pending.attempt,
                    stage, CommonsStageOutcome.IN_PROGRESS, null, CommonsStageTiming.NOT_MEASURED) }
            }.orEmpty()))
    }
    private fun current(trace: CommonsStageTrace, allowCancelled: Boolean = false): Pair<State, Pending>? {
        val current = state ?: return null
        // An old generation must not mutate even the replacement generation's counters.
        if (current.session !== trace.session) return null
        val pending = current.pending[trace]
        if (!enabled || !current.open || pending == null) {
            if (current.open) current.ignored = increment(current.ignored)
            return null
        }
        if (reconcileCancellation(current, pending) && !allowCancelled) {
            current.ignored = increment(current.ignored)
            return null
        }
        return current to pending
    }
    /** Reads only local signal state and the shared atomic flag; never calls a token or cancellation hook. */
    private fun reconcileCancellation(current: State, pending: Pending): Boolean {
        // Budget observations fence ordinary cleanup noise, never a real owner cancellation or
        // explicit deadline. The shared flag is authoritative even if a transport CAS happened first.
        val budget = CommonsStage.CONNECT_BUDGET_SIGNAL in pending.seen
        val deadline = CommonsStage.DEADLINE_SIGNAL in pending.seen
        if (!pending.trace.cancellationSignal.get() && !deadline && CommonsStage.CANCEL_SIGNAL !in pending.seen) return budget
        val failure = if (deadline) CommonsStageFailure.DEADLINE_EXCEEDED else CommonsStageFailure.CANCELLED
        if (!deadline && pending.seen.add(CommonsStage.CANCEL_SIGNAL))
            append(current, pending, CommonsStage.CANCEL_SIGNAL, CommonsStageOutcome.OBSERVED,
                null, CommonsStageTiming.NOT_MEASURED, failure)
        pending.active.keys.forEach { append(current, pending, it, CommonsStageOutcome.INCOMPLETE,
            null, CommonsStageTiming.NOT_MEASURED, failure) }
        pending.active.clear()
        return true
    }
    private fun seal(current: State) {
        if (!current.open) return
        current.pending.values.forEach { finishPending(current, it, CommonsStageOutcome.INCOMPLETE, null) }
        current.pending.clear(); current.open = false
    }
    private fun finishPending(current: State, pending: Pending, outcome: CommonsStageOutcome, failure: CommonsStageFailure?) {
        pending.active.keys.forEach { append(current, pending, it, CommonsStageOutcome.INCOMPLETE,
            null, CommonsStageTiming.NOT_MEASURED, failure) }
        pending.active.clear()
        append(current, pending, CommonsStage.OPERATION, outcome, null, CommonsStageTiming.NOT_MEASURED, failure)
    }
    private fun append(current: State, pending: Pending, stage: CommonsStage, outcome: CommonsStageOutcome,
        elapsed: Long?, timing: CommonsStageTiming, failure: CommonsStageFailure?, httpStatus: Int? = null) {
        if (current.samples.size == capacity) { current.samples.removeFirst(); current.droppedSamples = increment(current.droppedSamples) }
        current.samples.addLast(CommonsStageSample(current.session.alias, pending.alias, pending.attempt, stage, outcome, elapsed, timing, failure, httpStatus))
    }
    private fun increment(value: Long) = if (value == Long.MAX_VALUE) value else value + 1
}

/** Capability can be issued only by the discovery coordinator; data-class operation copies omit it. */
class CommonsStageTrace internal constructor(private val owner: CommonsStageDiagnosticsRecorder,
    internal val session: CommonsStageDiagnosticsRecorder.Session, internal val cancellationSignal: AtomicBoolean) {
    fun begin(stage: CommonsStage): CommonsStageSpan? = owner.begin(this, stage)
    fun event(stage: CommonsStage, failure: CommonsStageFailure? = null) { owner.event(this, stage, failure) }
    fun httpStatus(status: Int) { owner.httpStatus(this, status) }
    internal fun finish(outcome: CommonsStageOutcome, failure: CommonsStageFailure? = null) { owner.finish(this, outcome, failure) }
    override fun toString() = "CommonsStageTrace([opaque])"
}
class CommonsStageSpan internal constructor(private val owner: CommonsStageDiagnosticsRecorder,
    internal val trace: CommonsStageTrace, internal val stage: CommonsStage, internal val started: Long) {
    fun complete() { owner.end(this, CommonsStageOutcome.SUCCESS, null) }
    fun failed(failure: CommonsStageFailure) { owner.end(this, CommonsStageOutcome.FAILURE, failure) }
    override fun toString() = "CommonsStageSpan([opaque])"
}
