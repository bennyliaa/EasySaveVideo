package com.easysavevideo.library.export

import com.easysavevideo.library.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** No URI/path, mutable default or pre-creation identity enters a library export admission. */
class DirectoryBatchPlan(val batchId:String,val folderGrantId:String,requests:List<ExportRequest>) {
    val requests=requests.toList()
    init {
        LibraryIds.requireValid(batchId);LibraryIds.requireValid(folderGrantId)
        require(this.requests.size in 1..LibraryLimits.MAX_PAGE_SIZE)
        require(this.requests.all{it.destination.kind==ExportKind.SAF})
        require(this.requests.map{it.source.itemId}.distinct().size==this.requests.size)
        require(this.requests.map{it.operationId}.distinct().size==this.requests.size)
        require(this.requests.map{it.destination.grantId}.distinct().size==this.requests.size)
    }
    override fun toString()="DirectoryBatchPlan(count=${requests.size})"
    companion object {
        fun create(folderGrantId:String,refs:List<MediaReadRef>)=DirectoryBatchPlan(UUID.randomUUID().toString(),folderGrantId,
            refs.map{ExportRequest(UUID.randomUUID().toString(),it,ExportDestination(ExportKind.SAF,UUID.randomUUID().toString()))})
    }
}

/** Durable exact plan and per-item start fences. Existing rows can never be rebound/restarted. */
interface DirectoryBatchJournal {
    fun admit(plan:DirectoryBatchPlan)
    fun stopped(batchId:String):Boolean
    fun stop(batchId:String)
    fun outcome(operationId:String):BatchTransferStatus?
    fun started(operationId:String):Boolean
    fun start(plan:DirectoryBatchPlan,operationId:String):Boolean
    fun finish(plan:DirectoryBatchPlan,operationId:String,status:BatchTransferStatus)
    fun complete(batchId:String)
}

class DirectoryBatchCopy(private val plan:DirectoryBatchPlan) {
    private val consumed=AtomicBoolean(false)
    fun run(journal:DirectoryBatchJournal,current:(String)->MediaItem?,targetAvailable:()->Boolean,
        cancelled:()->Boolean,copy:(ExportRequest)->ExportRecord):List<BatchTransferItem> {
        check(consumed.compareAndSet(false,true)) { "Batch already consumed" }
        journal.admit(plan)
        val rows=plan.requests.map { request ->
            val previous=journal.outcome(request.operationId)
            if(previous!=null)return@map BatchTransferItem(request.source,previous)
            if(journal.started(request.operationId)) {
                journal.finish(plan,request.operationId,BatchTransferStatus.FAILED_UNCONFIRMED)
                return@map BatchTransferItem(request.source,BatchTransferStatus.FAILED_UNCONFIRMED)
            }
            val status=try {
                when {
                    cancelled() || journal.stopped(plan.batchId) -> BatchTransferStatus.CANCELLED_BEFORE_START
                    !targetAvailable() -> { journal.stop(plan.batchId);BatchTransferStatus.TARGET_UNAVAILABLE }
                    else -> {
                        val item=current(request.source.itemId)
                        when {
                            item==null || item.lifecycle!=MediaLifecycle.READY -> BatchTransferStatus.UNAVAILABLE
                            item.itemId!=request.source.itemId || item.readRef()!=request.source -> BatchTransferStatus.STALE
                            cancelled() -> BatchTransferStatus.CANCELLED_BEFORE_START
                            !journal.start(plan,request.operationId) -> BatchTransferStatus.FAILED_UNCONFIRMED
                            cancelled() -> BatchTransferStatus.CANCELLED_BEFORE_START
                            else -> {
                                val record=copy(request)
                                if(record.request!=request)BatchTransferStatus.FAILED_UNCONFIRMED else when(record.phase) {
                                    ExportPhase.SUCCEEDED -> BatchTransferStatus.VERIFIED_COPY
                                    ExportPhase.COPIED_UNVERIFIED -> BatchTransferStatus.COPIED_UNVERIFIED
                                    ExportPhase.CANCELLED -> BatchTransferStatus.CANCELLED
                                    ExportPhase.CLEANUP_PENDING -> BatchTransferStatus.CLEANUP_PENDING
                                    else -> BatchTransferStatus.FAILED_UNCONFIRMED
                                }
                            }
                        }
                    }
                }
            }catch(_:Exception){BatchTransferStatus.FAILED_UNCONFIRMED}
            journal.finish(plan,request.operationId,status)
            BatchTransferItem(request.source,status)
        }
        journal.complete(plan.batchId)
        return rows
    }
}
