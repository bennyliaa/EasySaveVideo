package com.easysavevideo.app.library.export

import com.easysavevideo.library.export.ExportRecord
import java.util.concurrent.RejectedExecutionException

/** Completion describes one bounded recovery pass, never the absence of unresolved exports. */
enum class ExportRecoveryStatus { UNKNOWN, CHECKING, FAILED, CHECKED }
enum class ExportRecoveryFailure { INCOMPLETE, QUEUE_BUSY }
data class ExportRecoveryCheckState(
    val status: ExportRecoveryStatus = ExportRecoveryStatus.UNKNOWN,
    val failure: ExportRecoveryFailure? = null,
)

internal fun offerExportRecoveryRetry(check: ExportRecoveryCheckState, recoverable: Long): Boolean =
    check.status != ExportRecoveryStatus.CHECKED || recoverable > 0

internal fun exportRecoveryReviewUnavailable(countsAvailable: Boolean, reviewsAvailable: Boolean): Boolean =
    !countsAvailable || !reviewsAvailable

internal data class ExportRecoveryResult<Reviews>(val reviews: Reviews, val interrupted: Boolean)

/**
 * App-scoped admission/publication fence around the existing journal-driven recovery contract.
 * Page leave/background do not cancel admitted work or enqueue a second pass. The controller has
 * no picker, grant-acquisition or new-request port. PREPARED requests retain coordinator semantics.
 */
internal class ExportRecoveryCheckController<Reviews>(
    private val enqueue: (() -> Unit) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val isRuntimeCurrent: () -> Boolean,
    private val stopInterrupted: () -> Boolean,
    private val recoverPending: () -> List<ExportRecord>,
    private val releaseFinishedGrant: (ExportRecord) -> Unit,
    private val readReviews: () -> Reviews,
    private val onUpdate: (ExportRecoveryCheckState, ExportRecoveryResult<Reviews>?, ownsBusy: Boolean) -> Unit,
) : AutoCloseable {
    private class Attempt { var started = false; var invalidated = false }
    private var active: Attempt? = null
    private var closed = false
    private var snapshot = ExportRecoveryCheckState()

    @Synchronized fun checking(): Boolean = active != null

    fun start(): Boolean {
        val attempt = synchronized(this) {
            if (closed || !isRuntimeCurrent() || active != null) return false
            Attempt().also {
                active = it
                // The last failure remains visible until the whole next pass succeeds.
                snapshot = snapshot.copy(status = ExportRecoveryStatus.CHECKING)
            }
        }
        post { synchronized(this) { if (owns(attempt)) onUpdate(snapshot, null, true) } }
        try {
            enqueue {
                if (!claim(attempt)) return@enqueue
                try {
                    val interrupted = stopInterrupted()
                    if (!current(attempt)) return@enqueue
                    val records = recoverPending()
                    for (record in records) {
                        if (!current(attempt)) return@enqueue
                        releaseFinishedGrant(record)
                    }
                    if (!current(attempt)) return@enqueue
                    val reviews = readReviews()
                    complete(attempt, ExportRecoveryResult(reviews, interrupted), null)
                } catch (_: Exception) {
                    complete(attempt, null, ExportRecoveryFailure.INCOMPLETE)
                }
            }
        } catch (_: RejectedExecutionException) {
            complete(attempt, null, ExportRecoveryFailure.QUEUE_BUSY)
        }
        return true
    }

    /** Ordinary operation/refresh failures cannot manufacture a journal count or prove recovery. */
    @Synchronized fun requireRecovery() {
        if (closed || !isRuntimeCurrent()) return
        active?.invalidated = true
        snapshot = ExportRecoveryCheckState(
            if (active == null) ExportRecoveryStatus.FAILED else ExportRecoveryStatus.CHECKING,
            ExportRecoveryFailure.INCOMPLETE,
        )
        onUpdate(snapshot, null, false)
    }

    private fun complete(attempt: Attempt, result: ExportRecoveryResult<Reviews>?, failure: ExportRecoveryFailure?) {
        post {
            synchronized(this) {
                if (!owns(attempt)) return@post
                val failed = failure ?: if (attempt.invalidated) ExportRecoveryFailure.INCOMPLETE else null
                snapshot = ExportRecoveryCheckState(
                    if (failed == null) ExportRecoveryStatus.CHECKED else ExportRecoveryStatus.FAILED,
                    failed,
                )
                active = null
                onUpdate(snapshot, if (failed == null) result else null, true)
            }
        }
    }

    @Synchronized private fun claim(attempt: Attempt): Boolean {
        if (!owns(attempt) || attempt.started) return false
        attempt.started = true
        return true
    }
    @Synchronized private fun current(attempt: Attempt): Boolean = owns(attempt)
    private fun owns(attempt: Attempt): Boolean = !closed && isRuntimeCurrent() && active === attempt

    @Synchronized override fun close() { closed = true; active = null }
}

/** A disposed page's callback never becomes valid again when another Library page is opened. */
internal class ExportRecoveryRetryAction(private val isCurrent: () -> Boolean, private val retry: () -> Unit) {
    private var attached = true
    fun invoke() { if (attached && isCurrent()) retry() }
    fun close() { attached = false }
}
