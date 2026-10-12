package com.easysavevideo.app.library.share

import com.easysavevideo.library.*
import com.easysavevideo.library.export.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal interface ShareBatchPort<T> {
    fun current(id: String): MediaItem?
    fun prepare(ref: MediaReadRef, operationId: String, cancelled: () -> Boolean): T
    /** Denies future access; true means actual cleanup confirmed, not recalled recipient bytes. */
    fun revoke(operationId: String): Boolean
}

internal class PreparedShareBatch<T>(val payloads: List<T>, val rows: List<BatchTransferItem>, val cleanupPending: Boolean) {
    override fun toString() = "PreparedShareBatch(count=${rows.size},cleanupPending=$cleanupPending)"
}
internal enum class ShareLaunchResult { LAUNCHED, REJECTED, ALREADY_LAUNCHED }

/** All selected items or no chooser. A failed subset never receives silent sharing consent. */
internal class ShareBatch(items: List<MediaItem>) {
    val snapshot = items.toList()
    val operationId = UUID.randomUUID().toString()
    private val consumed = AtomicBoolean(false)
    private val published = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    init {
        require(snapshot.size in 1..SharePolicy.MAX_COPIES)
        require(snapshot.all { it.lifecycle == MediaLifecycle.READY && it.sizeBytes in 1..SharePolicy.MAX_COPY_BYTES })
        require(snapshot.map { it.itemId }.distinct().size == snapshot.size)
        require(snapshot.sumOf { it.sizeBytes } <= SharePolicy.MAX_TOTAL_BYTES)
    }
    fun cancel() { cancelled.set(true) }
    fun <T> prepare(port: ShareBatchPort<T>): PreparedShareBatch<T> {
        check(consumed.compareAndSet(false,true)) { "Batch already consumed" }
        val payloads = mutableListOf<T>()
        val rows = mutableListOf<BatchTransferItem>()
        var stopped = false
        for(item in snapshot) {
            val ref=item.readRef()
            val status=when {
                cancelled.get() -> BatchTransferStatus.CANCELLED_BEFORE_START
                stopped -> BatchTransferStatus.NOT_STARTED
                else -> try {
                    val current=port.current(ref.itemId)
                    when {
                        current==null || current.lifecycle!=MediaLifecycle.READY -> BatchTransferStatus.UNAVAILABLE
                        current.itemId!=ref.itemId || current.readRef()!=ref -> BatchTransferStatus.STALE
                        cancelled.get() -> BatchTransferStatus.CANCELLED_BEFORE_START
                        else -> { payloads += port.prepare(ref,operationId,cancelled::get);BatchTransferStatus.PREPARED }
                    }
                } catch (_: Exception) { BatchTransferStatus.FAILED_UNCONFIRMED }
            }
            if(status!=BatchTransferStatus.PREPARED) stopped=true
            rows += BatchTransferItem(ref,status)
        }
        if(!stopped && !cancelled.get()) snapshot.forEachIndexed { index,item ->
            val valid=try { port.current(item.itemId)?.let { it.lifecycle==MediaLifecycle.READY && it.readRef()==item.readRef() }==true } catch(_:Exception) { false }
            if(!valid) { rows[index]=BatchTransferItem(item.readRef(),BatchTransferStatus.STALE);stopped=true }
        }
        if(stopped || cancelled.get()) {
            val clean=try { port.revoke(operationId) } catch(_:Exception) { false }
            return PreparedShareBatch(emptyList(),rows.map {
                if(it.status==BatchTransferStatus.PREPARED)it.copy(status=BatchTransferStatus.AUTHORITY_REVOKED) else it
            },!clean)
        }
        return PreparedShareBatch(payloads.toList(),rows.toList(),false)
    }
    /** UI-only launch gate; revoke runs later on the control lane, never blocking main with IO. */
    fun <T> launch(prepared: PreparedShareBatch<T>, allowed: () -> Boolean, chooser: (List<T>) -> Unit): ShareLaunchResult {
        if(published.get()) return ShareLaunchResult.ALREADY_LAUNCHED
        val live=try { allowed() } catch(_:Exception) { false }
        if(prepared.payloads.size!=snapshot.size || cancelled.get() || !live) {
            cancel();return ShareLaunchResult.REJECTED
        }
        if(!published.compareAndSet(false,true)) return ShareLaunchResult.ALREADY_LAUNCHED
        return try { chooser(prepared.payloads);ShareLaunchResult.LAUNCHED } catch(_:Exception) { cancel();ShareLaunchResult.REJECTED }
    }
}
