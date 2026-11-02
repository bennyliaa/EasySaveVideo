package com.easysavevideo.library

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class BatchMoveStatus { MOVED, ALREADY_AT_TARGET, STALE, UNAVAILABLE, FAILED_UNCONFIRMED, CANCELLED_BEFORE_START }

data class BatchMoveItemResult(val ref: MediaReadRef, val status: BatchMoveStatus) {
    override fun toString() = "BatchMoveItemResult(status=$status)"
}

/** One explicit consent snapshot, one execution. Each move is its own durable CAS operation.
 * Cancellation stops subsequent items; it never interrupts or rolls back an accepted mutation.
 * Exceptions after a write may hide a committed result, so they are never reported as unchanged.
 * The caller owns a worker lane; no file access, readers, metadata logging or UI dispatch here.
 */
class LibraryBatchMove(refs: List<MediaReadRef>, val destination: String?) {
    val references: List<MediaReadRef> = refs.toList()
    private val executed = AtomicBoolean(false)
    private val operationIds = references.map { UUID.randomUUID().toString() }

    init {
        require(references.size in 1..LibraryLimits.MAX_PAGE_SIZE)
        require(references.map { it.itemId }.distinct().size == references.size)
        destination?.let(LibraryIds::requireValid)
    }

    fun execute(repository: LibraryRepository, cancelled: () -> Boolean): List<BatchMoveItemResult> {
        check(executed.compareAndSet(false, true)) { "Batch already consumed" }
        return references.mapIndexed { index, ref ->
            val status = if (cancelled()) BatchMoveStatus.CANCELLED_BEFORE_START else {
                try {
                    val current = repository.item(ref.itemId)
                    when {
                        current == null || current.lifecycle != MediaLifecycle.READY -> BatchMoveStatus.UNAVAILABLE
                        current.itemId != ref.itemId || current.readRef() != ref -> BatchMoveStatus.STALE
                        cancelled() -> BatchMoveStatus.CANCELLED_BEFORE_START
                        current.folderId == destination -> BatchMoveStatus.ALREADY_AT_TARGET
                        else -> {
                            val moved = repository.moveItem(ref.itemId, ref.revision, destination, operationIds[index])
                            if (moved.itemId == ref.itemId && moved.lifecycle == MediaLifecycle.READY &&
                                moved.folderId == destination && moved.representationGeneration == ref.representationGeneration &&
                                moved.revision > ref.revision) BatchMoveStatus.MOVED else BatchMoveStatus.FAILED_UNCONFIRMED
                        }
                    }
                } catch (failure: LibraryFailure) {
                    when (failure.code) {
                        LibraryError.STALE_REVISION -> BatchMoveStatus.STALE
                        LibraryError.NOT_FOUND -> BatchMoveStatus.UNAVAILABLE
                        else -> BatchMoveStatus.FAILED_UNCONFIRMED
                    }
                } catch (_: Exception) { BatchMoveStatus.FAILED_UNCONFIRMED }
            }
            BatchMoveItemResult(ref, status)
        }
    }

    override fun toString() = "LibraryBatchMove(count=${references.size})"
}
