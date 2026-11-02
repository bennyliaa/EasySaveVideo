package com.easysavevideo.discovery

import java.util.ArrayDeque
import java.util.IdentityHashMap

/** Trusted local, nonblocking monotonic nanosecond clock. Signed ticks, including negatives, are valid.
 * This clock is independent of the epoch clock used for discovery deadlines and admission. */
fun interface ProviderLatencyTickClock { fun nowNanos(): Long }

/** These describe runtime reports, not accepted pages, remote-server causes or worker release. */
enum class ProviderCallbackTerminal {
    SUCCESS_REPORTED, FAILURE_REPORTED, CANCELLED_REPORTED, DEADLINE_REPORTED, BUSY_REPORTED,
    CANCELLED_INCOMPLETE, EXECUTE_FAILED_INCOMPLETE,
}

enum class ProviderLatencyTiming { MEASURED, CLOCK_READ_FAILED, CLOCK_BACKWARDS, CLOCK_OVERFLOW, NOT_MEASURED }

/** Aliases have meaning only within this recorder's current session. No raw identities or ticks escape. */
data class ProviderCallbackLatencySample(
    val sourceAlias: Int,
    val operationAlias: Long,
    val attempt: Int,
    val terminal: ProviderCallbackTerminal,
    val elapsedNanos: Long?,
    val timing: ProviderLatencyTiming,
)

data class ProviderCallbackLatencySnapshot(
    val enabled: Boolean,
    val capacity: Int,
    val samples: List<ProviderCallbackLatencySample>,
    val pendingAttempts: Int,
    val callbacksObserved: Long,
    val cancelledUnmeasured: Long,
    val executeFailedWithoutCallback: Long,
    val droppedAttempts: Long,
    val evictedSamples: Long,
    val clockReadFailures: Long,
    val invalidDurations: Long,
) {
    val incomplete: Boolean get() = pendingAttempts > 0 || cancelledUnmeasured > 0 ||
        executeFailedWithoutCallback > 0 || droppedAttempts > 0 || evictedSamples > 0 ||
        clockReadFailures > 0 || invalidDurations > 0
}

/**
 * Optional local diagnostics, disabled unless both explicitly enabled and injected into a coordinator.
 * Nothing is persisted, exported, logged, or sent to observers. Disabled instances allocate no session
 * collection and never read their clock. Each session replaces all prior samples and identity mappings.
 *
 * A measured interval runs from immediately before runtime.execute to the first callback observation, before waiting
 * for the coordinator lock. It is NOT worker-slot release, network latency, validation/publication
 * completion or first-visible-result latency. Retired attempts without a measured callback have no duration; callbacks discarded behind an immediate
 * suspension fence also belong to this incomplete count.
 *
 * All work is bounded: at most [capacity] pending identities, [capacity] retained samples and 32 source
 * aliases. Overflow drops new attempts; the sample ring evicts oldest completions, with explicit counts.
 * The injected clock must be trusted, prompt and free of I/O; there is no arbitrary observer callback.
 */
class ProviderCallbackLatencyRecorder(
    private val enabled: Boolean = false,
    private val clock: ProviderLatencyTickClock = ProviderLatencyTickClock(System::nanoTime),
    private val capacity: Int = 128,
) {
    init { require(capacity in 1..1024) }

    internal class Session internal constructor()
    private data class Pending(val sourceAlias: Int, val operationAlias: Long, val attempt: Int, val started: Long?)
    private class State(val session: Session) {
        var open = true
        val aliases = mutableMapOf<String, Int>()
        val pending = IdentityHashMap<Any, Pending>()
        val samples = ArrayDeque<ProviderCallbackLatencySample>()
        var sequence = 0L
        var callbacks = 0L
        var cancelled = 0L
        var executeFailed = 0L
        var dropped = 0L
        var evicted = 0L
        var clockFailures = 0L
        var invalidDurations = 0L
    }
    private var state: State? = null

    @Synchronized internal fun beginSession(): Session? {
        if (!enabled) return null
        return Session().also { state = State(it) }
    }

    @Synchronized internal fun started(session: Session?, operationIdentity: Any, sourceIdentity: String, attempt: Int) {
        val current = state?.takeIf { it.session === session && it.open } ?: return
        if (current.pending.containsKey(operationIdentity)) return
        if (current.pending.size >= capacity || current.sequence == Long.MAX_VALUE ||
            sourceIdentity !in current.aliases && current.aliases.size >= 32) {
            current.dropped = increment(current.dropped)
            return
        }
        val alias = current.aliases.getOrPut(sourceIdentity) { current.aliases.size + 1 }
        current.pending[operationIdentity] = Pending(alias, ++current.sequence, attempt, readTick(current))
    }

    /** Exact object identity is required; structurally equal operations cannot finish each other. */
    @Synchronized internal fun callback(operationIdentity: Any, terminal: ProviderCallbackTerminal) {
        val current = state ?: return
        val pending = current.pending.remove(operationIdentity) ?: return
        current.callbacks = increment(current.callbacks)
        val end = if (pending.started == null) null else readTick(current)
        val elapsed = if (pending.started == null || end == null) null else try {
            Math.subtractExact(end, pending.started)
        } catch (_: ArithmeticException) { null }
        val timing = when {
            pending.started == null || end == null -> ProviderLatencyTiming.CLOCK_READ_FAILED
            elapsed == null -> ProviderLatencyTiming.CLOCK_OVERFLOW
            elapsed < 0 -> ProviderLatencyTiming.CLOCK_BACKWARDS
            else -> ProviderLatencyTiming.MEASURED
        }
        if (timing != ProviderLatencyTiming.MEASURED) current.invalidDurations = increment(current.invalidDurations)
        append(current, pending, terminal, elapsed?.takeIf { timing == ProviderLatencyTiming.MEASURED }, timing)
    }

    @Synchronized internal fun cancelled(operationIdentity: Any) {
        val current = state ?: return
        val pending = current.pending.remove(operationIdentity) ?: return
        current.cancelled = increment(current.cancelled)
        append(current, pending, ProviderCallbackTerminal.CANCELLED_INCOMPLETE, null, ProviderLatencyTiming.NOT_MEASURED)
    }

    @Synchronized internal fun executeFailed(operationIdentity: Any) {
        val current = state ?: return
        val pending = current.pending.remove(operationIdentity) ?: return
        current.executeFailed = increment(current.executeFailed)
        append(current, pending, ProviderCallbackTerminal.EXECUTE_FAILED_INCOMPLETE, null, ProviderLatencyTiming.NOT_MEASURED)
    }

    /** Retain only detached technical evidence after close, never raw aliases or operation objects.
     * An old coordinator sharing this recorder cannot close the replacement coordinator's session. */
    @Synchronized internal fun endSession(session: Session?) {
        val current = state?.takeIf { it.session === session && it.open } ?: return
        current.open = false
        current.pending.values.forEach { pending ->
            current.cancelled = increment(current.cancelled)
            append(current, pending, ProviderCallbackTerminal.CANCELLED_INCOMPLETE, null, ProviderLatencyTiming.NOT_MEASURED)
        }
        current.pending.clear()
        current.aliases.clear()
    }

    @Synchronized fun snapshot(): ProviderCallbackLatencySnapshot {
        val current = state
        return ProviderCallbackLatencySnapshot(enabled, capacity, current?.samples?.toList().orEmpty(),
            current?.pending?.size ?: 0, current?.callbacks ?: 0, current?.cancelled ?: 0,
            current?.executeFailed ?: 0, current?.dropped ?: 0, current?.evicted ?: 0,
            current?.clockFailures ?: 0, current?.invalidDurations ?: 0)
    }

    private fun readTick(current: State): Long? = try { clock.nowNanos() }
        catch (_: Exception) { current.clockFailures = increment(current.clockFailures); null }
        catch (_: LinkageError) { current.clockFailures = increment(current.clockFailures); null }

    private fun append(current: State, pending: Pending, terminal: ProviderCallbackTerminal, elapsed: Long?, timing: ProviderLatencyTiming) {
        if (current.samples.size == capacity) {
            current.samples.removeFirst()
            current.evicted = increment(current.evicted)
        }
        current.samples.addLast(ProviderCallbackLatencySample(pending.sourceAlias, pending.operationAlias,
            pending.attempt, terminal, elapsed, timing))
    }

    private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1
}
