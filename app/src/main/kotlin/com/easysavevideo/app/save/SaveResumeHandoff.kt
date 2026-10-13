package com.easysavevideo.app.save

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.SaveCoordinator
import com.easysavevideo.save.SaveRecord
import com.easysavevideo.save.SaveWaitReason
import com.easysavevideo.save.needsNetworkTransfer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Production Resume-only worker admission and local state repair. No worker is accepted merely
 * because coordinator.resume returned RUNNING. A successful submit OR Runnable entry permanently
 * consumes compensation authority, including while an accepted worker is still queued.
 */
internal class SaveResumeHandoff(
    private val coordinator: SaveCoordinator,
    private val commands: SaveTaskCommandGate,
    private val enqueue: (() -> Unit) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val publishRecords: () -> Unit,
    private val onChanged: () -> Unit,
    private val isRuntimeCurrent: () -> Boolean = { true },
) {
    internal class Ticket internal constructor(
        val expected: SaveRecord,
        internal val operation: OperationContext,
        internal val resumeAttempt: SaveTaskCommandGate.Attempt,
    ) {
        val taskId: String get() = expected.task.taskId
        internal val live = AtomicBoolean(true)
        internal val admission = AtomicReference(Admission.UNACCEPTED)
        internal val entered = AtomicBoolean(false)
        internal val pending = AtomicBoolean(false)
        @Volatile internal var expectedWaiting: SaveRecord? = null
        @Volatile internal var reason = SaveWaitReason.RECOVERY
    }

    internal enum class Admission { UNACCEPTED, ACCEPTED, SUPPRESSED }
    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val cancellationRetries = ConcurrentHashMap.newKeySet<String>()
    fun pendingCancellations(): Set<String> = cancellationRetries.toSet()
    fun cancellationConfirmed(taskId: String) { cancellationRetries.remove(taskId); changed() }
    fun observeTerminal(records: List<SaveRecord>) {
        records.filter { it.task.state.isTerminal }.forEach { cancellationRetries.remove(it.task.taskId) }
    }

    /** Only this list drives the fallback UI; it does not require a successful task-list read. */
    fun pending(): List<Ticket> = tickets.values.filter { owns(it) && it.pending.get() }.sortedBy { it.taskId }

    fun launch(
        expected: SaveRecord,
        operation: OperationContext,
        attempt: SaveTaskCommandGate.Attempt,
        foreground: () -> Boolean,
        networkAllowed: () -> Boolean,
        submit: (Runnable) -> Unit,
        work: () -> Unit,
        releaseUnaccepted: () -> Unit,
    ): Boolean {
        val ticket = Ticket(expected, operation, attempt)
        // A separately admitted new Resume supersedes an earlier unconfirmed Cancel affordance.
        if (cancellationRetries.remove(ticket.taskId)) changed()
        tickets.put(ticket.taskId, ticket)?.let(::retire)
        if (!owns(ticket)) { retire(ticket); return false }
        // Keep the genuine post-resume journal preflight inside the unaccepted failure boundary.
        val deferral = try {
            when {
                !foreground() || !commands.isCurrent(attempt) -> SaveWaitReason.BACKGROUND
                coordinator.record(ticket.taskId)?.needsNetworkTransfer() == true && !networkAllowed() -> SaveWaitReason.NETWORK
                else -> null
            }
        } catch (_: Exception) { SaveWaitReason.RECOVERY }
        if (deferral != null) {
            defer(ticket, deferral, foreground, networkAllowed, releaseUnaccepted)
            return false
        }
        if (!owns(ticket)) { retire(ticket); return false }
        try {
            submit(Runnable {
                // Unknown submission failure may have suppressed this queued Runnable before entry.
                if (admitted(ticket) && ticket.entered.compareAndSet(false, true)) work()
            })
            admitted(ticket) // Must precede every subsequent list/publication read.
            return true
        } catch (_: RejectedExecutionException) {
            if (ticket.admission.get() == Admission.ACCEPTED) return true
            defer(ticket, SaveWaitReason.RECOVERY, foreground, networkAllowed, releaseUnaccepted)
            return false
        } catch (failure: Exception) {
            // Outcome-ambiguous is never compensation authority. Atomically suppress a queued
            // wrapper before releasing its resources; if entry won, the worker keeps ownership.
            if (!ticket.admission.compareAndSet(Admission.UNACCEPTED, Admission.SUPPRESSED)) return true
            retire(ticket)
            changed()
            throw failure
        }
    }

    /** One explicit click claims the existing task command gate before any queue admission. */
    fun retry(ticket: Ticket, isPageCurrent: () -> Boolean): Boolean {
        if (!isRuntimeCurrent() || !isPageCurrent() || !owns(ticket) || !ticket.pending.get()) return false
        val command = commands.tryRepair(ticket.taskId) ?: return false
        changed()
        fun finish() = post {
            commands.release(command) // Never clears a newer Cancel/Resume command.
            if (isRuntimeCurrent()) onChanged()
        }
        try {
            enqueue {
                try {
                    if (isRuntimeCurrent() && isPageCurrent() && commands.isCurrent(command) && owns(ticket)) repair(ticket)
                } finally { finish() }
            }
        } catch (_: RejectedExecutionException) { finish() }
        return true
    }

    /** Memory-only and synchronous: late IO errors can never put a retired ticket back. */
    fun cancel(taskId: String) {
        tickets[taskId]?.let { ticket ->
            if (ticket.pending.get()) cancellationRetries.add(taskId)
            ticket.live.set(false)
            ticket.resumeAttempt.cancellation.cancel()
            tickets.remove(taskId, ticket)
        }
        changed()
    }

    private fun defer(ticket: Ticket, fallback: SaveWaitReason, foreground: () -> Boolean,
        networkAllowed: () -> Boolean, releaseUnaccepted: () -> Unit) {
        ticket.reason = try {
            when {
                !foreground() || !commands.isCurrent(ticket.resumeAttempt) -> SaveWaitReason.BACKGROUND
                fallback == SaveWaitReason.NETWORK -> SaveWaitReason.NETWORK
                ticket.expected.needsNetworkTransfer() && !networkAllowed() -> SaveWaitReason.NETWORK
                else -> fallback
            }
        } catch (_: Exception) { fallback }
        try { releaseUnaccepted() } finally { repair(ticket) }
    }

    private fun repair(ticket: Ticket) {
        if (!owns(ticket)) { retire(ticket); return }
        ticket.pending.set(true)
        try {
            val resolved = coordinator.recoverUnadmittedResume(ticket.expected, ticket.operation,
                ticket.expectedWaiting, ticket.reason, mayRepair = { owns(ticket) },
                preparedWaiting = { ticket.expectedWaiting = it })
            if (!resolved) { retire(ticket); return }
            // Keep the exact proposal if the following authoritative snapshot read fails.
            // A later explicit click recognizes that same WAITING row without another CAS.
            publishRecords()
            retire(ticket)
        } catch (_: Exception) {
            if (!owns(ticket)) retire(ticket)
        } finally { changed() }
    }

    private fun owns(ticket: Ticket): Boolean = ticket.live.get() && ticket.admission.get() == Admission.UNACCEPTED &&
        !ticket.operation.cancelToken.isCancelled && tickets[ticket.taskId] === ticket

    private fun admitted(ticket: Ticket): Boolean {
        if (!ticket.admission.compareAndSet(Admission.UNACCEPTED, Admission.ACCEPTED) &&
            ticket.admission.get() != Admission.ACCEPTED) return false
        retire(ticket)
        changed()
        return true
    }

    private fun retire(ticket: Ticket) {
        ticket.live.set(false)
        tickets.remove(ticket.taskId, ticket)
    }

    private fun changed() = post { if (isRuntimeCurrent()) onChanged() }
}

/** Disposal is permanent: reopening Tasks cannot revive an old page's captured repair callback. */
internal class SaveRecoveryRetryAction(private val isCurrent: () -> Boolean, private val retry: (SaveResumeHandoff.Ticket, () -> Boolean) -> Unit) {
    @Volatile private var attached = true
    fun invoke(ticket: SaveResumeHandoff.Ticket) {
        // Host route/overlay checks are main-thread admission only. Queued work uses the permanent
        // page token plus the runtime/command fences, never a NavController from a control thread.
        if (current()) retry(ticket) { attached }
    }
    fun cancel(taskId: String, action: (String) -> Unit) { if (current()) action(taskId) }
    private fun current(): Boolean = attached && isCurrent()
    fun close() { attached = false }
}
