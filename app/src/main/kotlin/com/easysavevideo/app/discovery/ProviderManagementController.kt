package com.easysavevideo.app.discovery

import com.easysavevideo.commons.maintenance.ProviderRepositoryError
import com.easysavevideo.commons.maintenance.ProviderRepositoryResult
import com.easysavevideo.commons.maintenance.ProviderRepositorySnapshot
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** This screen only inspects local state and requests repository-defined recovery operations. */
interface ProviderManagementBackend {
    fun inspect(): ProviderRepositorySnapshot
    fun rejectCandidate(expectedVersion: Long, checkCancelled: () -> Unit): ProviderRepositoryResult
    fun rollback(expectedGeneration: Long, checkCancelled: () -> Unit): ProviderRepositoryResult
}

enum class ProviderManagementOperation { INSPECT, REJECT_CANDIDATE, ROLLBACK }
enum class ProviderManagementProblem { WORKER_BUSY, LOCAL_UNAVAILABLE, ACTION_REJECTED }

data class ProviderManagementState(
    val active: Boolean = false,
    val busy: Boolean = false,
    val snapshot: ProviderRepositorySnapshot? = null,
    val operation: ProviderManagementOperation? = null,
    /** Success means a local transaction returned, not that effective authority is enabled. */
    val completed: ProviderManagementOperation? = null,
    val problem: ProviderManagementProblem? = null,
    val repositoryError: ProviderRepositoryError? = null,
    val observedAtEpochMillis: Long? = null,
) {
    override fun toString(): String = "ProviderManagementState([redacted])"
}

/** One process-owned physical worker; no queued retries and no replacement for a hung operation. */
internal class ProviderManagementWorker : Executor {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { work -> Thread(work, "esv-provider-management").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    override fun execute(command: Runnable) = executor.execute(command)
}

/**
 * Public methods and dispatched completions belong to the UI lane. A hidden/closed session invalidates
 * every old callback, including when the same screen reopens before an old physical worker returns.
 * Closing requests cancellation; it does not undo a transaction that already committed. The shared
 * worker remains occupied until the old call actually exits. No future is cancelled to fake release.
 */
class ProviderManagementController(
    private val backend: ProviderManagementBackend,
    private val worker: Executor,
    private val dispatch: (() -> Unit) -> Unit,
    private val publish: (ProviderManagementState) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    var state = ProviderManagementState()
        private set
    private var closed = false
    private var epoch = 0L
    private var cancellation: AtomicBoolean? = null

    fun setActive(active: Boolean) {
        if (closed || state.active == active) return
        invalidate()
        update(ProviderManagementState(active = active))
        if (active) inspect()
    }

    fun inspect() = submit(ProviderManagementOperation.INSPECT) { _ -> backend.inspect() to null }

    fun refreshAutomatically() {
        if (state.snapshot != null) submit(ProviderManagementOperation.INSPECT, automatic = true) { backend.inspect() to null }
    }

    fun rejectCandidate() {
        val current = state.snapshot ?: return
        val version = current.pendingVersion ?: return
        if (!current.canRejectCandidate) return
        submit(ProviderManagementOperation.REJECT_CANDIDATE) { checkCancelled ->
            result(backend.rejectCandidate(version, checkCancelled))
        }
    }

    fun rollback() {
        val snapshot = state.snapshot ?: return
        if (!snapshot.canRollback) return
        submit(ProviderManagementOperation.ROLLBACK) { checkCancelled -> result(backend.rollback(snapshot.generation, checkCancelled)) }
    }

    private fun result(result: ProviderRepositoryResult): Pair<ProviderRepositorySnapshot, ProviderRepositoryError?> = when (result) {
        is ProviderRepositoryResult.Success -> result.snapshot to null
        is ProviderRepositoryResult.Failure -> backend.inspect() to result.code
    }

    private fun submit(operation: ProviderManagementOperation, automatic: Boolean = false,
        action: (() -> Unit) -> Pair<ProviderRepositorySnapshot, ProviderRepositoryError?>) {
        if (closed || !state.active || state.busy) return
        val previous = state
        val ticket = ++epoch
        val cancelled = AtomicBoolean(false)
        cancellation = cancelled
        update(state.copy(busy = true, operation = operation, completed = null, problem = null, repositoryError = null))
        val checkCancelled = { if (cancelled.get()) throw CancellationException("CANCELLED") }
        try {
            worker.execute {
                var snapshot: ProviderRepositorySnapshot? = null
                var error: ProviderRepositoryError? = null
                var problem: ProviderManagementProblem? = null
                var observedAt: Long? = null
                try {
                    checkCancelled()
                    val result = action(checkCancelled)
                    snapshot = result.first
                    observedAt = now()
                    error = result.second
                    if (error != null) problem = ProviderManagementProblem.ACTION_REJECTED
                    checkCancelled()
                } catch (_: CancellationException) {
                    // Hidden sessions have no result to publish. A committed mutation is not undone.
                    if (!cancelled.get()) {
                        problem = ProviderManagementProblem.ACTION_REJECTED
                        error = ProviderRepositoryError.CANCELLED
                    }
                } catch (_: Exception) {
                    problem = ProviderManagementProblem.LOCAL_UNAVAILABLE
                } catch (_: LinkageError) {
                    problem = ProviderManagementProblem.LOCAL_UNAVAILABLE
                }
                val completedSnapshot = snapshot
                val completedError = error
                val completedProblem = problem
                val completedAt = observedAt
                dispatch {
                    if (!closed && state.active && epoch == ticket && !cancelled.get()) {
                        cancellation = null
                        update(ProviderManagementState(active = true, snapshot = completedSnapshot,
                            completed = if (completedProblem == null) { if (automatic) previous.completed else operation } else null,
                            problem = completedProblem ?: if (automatic && previous.problem == ProviderManagementProblem.ACTION_REJECTED) previous.problem else null,
                            repositoryError = completedError ?: if (automatic && previous.problem == ProviderManagementProblem.ACTION_REJECTED) previous.repositoryError else null,
                            observedAtEpochMillis = completedAt))
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (epoch == ticket) {
                cancellation = null
                update(state.copy(busy = false, operation = null, problem = ProviderManagementProblem.WORKER_BUSY))
            }
        }
    }

    private fun invalidate() { epoch++; cancellation?.set(true); cancellation = null }
    private fun update(value: ProviderManagementState) { state = value; publish(value) }
    override fun close() {
        if (closed) return
        closed = true
        invalidate()
        update(ProviderManagementState())
    }
}
