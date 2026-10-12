package com.easysavevideo.library.export

import com.easysavevideo.library.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class BatchTransferStatus {
    VERIFIED_COPY, COPIED_UNVERIFIED, STALE, UNAVAILABLE, FAILED_UNCONFIRMED,
    CANCELLED_BEFORE_START, CANCELLED, CLEANUP_PENDING, PREPARED,
    CHOOSER_OPENED, AUTHORITY_REVOKED, NOT_STARTED, TARGET_UNAVAILABLE,
}

data class BatchTransferItem(val ref: MediaReadRef, val status: BatchTransferStatus) {
    override fun toString() = "BatchTransferItem(status=$status)"
}

/** Independent per-item journal operations, never a group atomicity or rollback promise. */
class BatchGalleryCopy(refs: List<MediaReadRef>) {
    val references = refs.toList()
    private val consumed = AtomicBoolean(false)
    private val requests = references.map {
        ExportRequest(UUID.randomUUID().toString(), it, ExportDestination(ExportKind.MEDIA_STORE, UUID.randomUUID().toString()))
    }
    init {
        require(references.size in 1..LibraryLimits.MAX_PAGE_SIZE)
        require(references.map { it.itemId }.distinct().size == references.size)
    }
    fun run(current: (String) -> MediaItem?, cancelled: () -> Boolean,
        copy: (ExportRequest) -> ExportRecord): List<BatchTransferItem> {
        check(consumed.compareAndSet(false,true)) { "Batch already consumed" }
        return requests.map { request ->
            val ref = request.source
            val status = if (cancelled()) BatchTransferStatus.CANCELLED_BEFORE_START else try {
                val item = current(ref.itemId)
                when {
                    item == null || item.lifecycle != MediaLifecycle.READY -> BatchTransferStatus.UNAVAILABLE
                    item.itemId != ref.itemId || item.readRef() != ref -> BatchTransferStatus.STALE
                    cancelled() -> BatchTransferStatus.CANCELLED_BEFORE_START
                    else -> {
                        val record = copy(request)
                        if (record.request != request) BatchTransferStatus.FAILED_UNCONFIRMED else when(record.phase) {
                            ExportPhase.SUCCEEDED -> BatchTransferStatus.VERIFIED_COPY
                            ExportPhase.COPIED_UNVERIFIED -> BatchTransferStatus.COPIED_UNVERIFIED
                            ExportPhase.CANCELLED -> BatchTransferStatus.CANCELLED
                            ExportPhase.CLEANUP_PENDING -> BatchTransferStatus.CLEANUP_PENDING
                            else -> BatchTransferStatus.FAILED_UNCONFIRMED
                        }
                    }
                }
            } catch (_: Exception) { BatchTransferStatus.FAILED_UNCONFIRMED }
            BatchTransferItem(ref,status)
        }
    }
}
