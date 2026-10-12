package com.easysavevideo.library.export

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test

class DirectoryBatchCopyTest {
    private class Journal:DirectoryBatchJournal {
        var plan:DirectoryBatchPlan?=null;var stop=false
        val started=mutableSetOf<String>();val results=mutableMapOf<String,BatchTransferStatus>()
        var afterStart:()->Unit={}
        override fun admit(plan:DirectoryBatchPlan) {this.plan?.let{require(it.folderGrantId==plan.folderGrantId && it.requests==plan.requests)} ?: run{this.plan=plan}}
        override fun stopped(batchId:String)=stop
        override fun stop(batchId:String){stop=true}
        override fun outcome(operationId:String)=results[operationId]
        override fun started(operationId:String)=operationId in started
        override fun start(plan:DirectoryBatchPlan,operationId:String):Boolean {
            val accepted=!stop && started.add(operationId);if(accepted)afterStart();return accepted
        }
        override fun finish(plan:DirectoryBatchPlan,operationId:String,status:BatchTransferStatus){check(results.putIfAbsent(operationId,status)==null)}
        override fun complete(batchId:String){check(results.size==plan!!.requests.size)}
    }
    private val items=listOf(media("a"),media("b"),media("c"))
    private fun plan()=DirectoryBatchPlan.create("selected-folder",items.map{it.readRef()})
    @Test fun targetAndSourcesAreImmutableAndDefaultChangesCannotRedirectCopies() {
        val plan=plan();val j=Journal();var mutableDefault="original-default";val used=mutableListOf<String>()
        val rows=DirectoryBatchCopy(plan).run(j,{id->items.single{it.itemId==id}},{true},{false}) {request->
            mutableDefault="changed-default";used+=j.plan!!.folderGrantId;verified(request)
        }
        assertEquals("changed-default",mutableDefault);assertEquals(listOf("selected-folder","selected-folder","selected-folder"),used)
        assertTrue(rows.all{it.status==BatchTransferStatus.VERIFIED_COPY})
        assertEquals(3,plan.requests.map{it.operationId}.distinct().size)
        assertEquals(3,plan.requests.map{it.destination.grantId}.distinct().size)
    }
    @Test fun duplicateExecutionAndRetainedOperationReplayNeverRecreateDocuments() {
        val p=plan();val j=Journal();var copies=0;val runner=DirectoryBatchCopy(p)
        runner.run(j,{id->items.single{it.itemId==id}},{true},{false}) {copies++;verified(it)}
        try{runner.run(j,{null},{true},{false}){fail("Duplicate");verified(it)};fail("Consume twice")}catch(_:IllegalStateException){}
        DirectoryBatchCopy(p).run(j,{null},{true},{false}) {fail("Recreated historical output");verified(it)}
        assertEquals(3,copies)
        try{j.admit(DirectoryBatchPlan(p.batchId,"another-folder",p.requests));fail("Target rebind")}catch(_:IllegalArgumentException){}
    }
    @Test fun cancellationKeepsCompletedOutputAndSkipsUnstartedItems() {
        val p=plan();val j=Journal();var cancel=false
        val rows=DirectoryBatchCopy(p).run(j,{id->items.single{it.itemId==id}},{true},{cancel}){cancel=true;verified(it)}
        assertEquals(listOf(BatchTransferStatus.VERIFIED_COPY,BatchTransferStatus.CANCELLED_BEFORE_START,BatchTransferStatus.CANCELLED_BEFORE_START),rows.map{it.status})
        assertEquals(1,j.started.size)
    }
    @Test fun targetRevocationStopsTheBatchAndDoesNotUseNewGrantOrDefault() {
        val p=plan();val j=Journal();var available=true
        val rows=DirectoryBatchCopy(p).run(j,{id->items.single{it.itemId==id}},{available},{false}){available=false;verified(it)}
        assertEquals(listOf(BatchTransferStatus.VERIFIED_COPY,BatchTransferStatus.TARGET_UNAVAILABLE,BatchTransferStatus.CANCELLED_BEFORE_START),rows.map{it.status})
        assertTrue(j.stop);assertEquals(1,j.started.size)
    }
    @Test fun perItemFailureAndUnsupportedReadbackRemainDistinctAndDoNotDeleteSources() {
        val p=plan();val j=Journal();val source=items.toList()
        val rows=DirectoryBatchCopy(p).run(j,{id->items.single{it.itemId==id}},{true},{false}){request->when(request.source.itemId){
            "a"->verified(request)
            "b"->ExportRecord(request,phase=ExportPhase.COPIED_UNVERIFIED,output=ExportOutput(request.operationId,ExportKind.SAF),evidence=ExportEvidence(10,"a".repeat(64)),verification=ExportVerification.READBACK_UNSUPPORTED)
            else->throw IllegalStateException("private output")
        }}
        assertEquals(listOf(BatchTransferStatus.VERIFIED_COPY,BatchTransferStatus.COPIED_UNVERIFIED,BatchTransferStatus.FAILED_UNCONFIRMED),rows.map{it.status})
        assertEquals(source,items);assertFalse(rows.toString().contains("private output"))
    }
    @Test fun staleSnapshotNeverSelectsAdjacentItemAndCancellationDuringLookupFencesStart() {
        val p=plan();val j=Journal();var cancel=false
        val rows=DirectoryBatchCopy(p).run(j,{id->when(id){"a"->items[0].copy(representationGeneration=2);"b"->null;else->{cancel=true;items[2]}}},{true},{cancel}){fail("No admitted source");verified(it)}
        assertEquals(listOf(BatchTransferStatus.STALE,BatchTransferStatus.UNAVAILABLE,BatchTransferStatus.CANCELLED_BEFORE_START),rows.map{it.status})
        assertTrue(j.started.isEmpty())
    }
    @Test fun processDeathAfterStartNeverReopensUncertainOutputAndWaitingItemsDoNotResume() {
        class ProcessDeath:Error()
        val p=plan();val j=Journal()
        try{DirectoryBatchCopy(p).run(j,{id->items.single{it.itemId==id}},{true},{false}){throw ProcessDeath()};fail("Simulated process death")}catch(_:ProcessDeath){}
        j.stop(p.batchId)
        val rows=DirectoryBatchCopy(p).run(j,{null},{true},{false}){fail("Automatic resume/create");verified(it)}
        assertEquals(listOf(BatchTransferStatus.FAILED_UNCONFIRMED,BatchTransferStatus.CANCELLED_BEFORE_START,BatchTransferStatus.CANCELLED_BEFORE_START),rows.map{it.status})
        assertEquals(1,j.started.size)
    }
    @Test fun wrongOutputOperationCannotBecomeSuccessForThisSource() {
        val p=plan();val rows=DirectoryBatchCopy(p).run(Journal(),{id->items.single{it.itemId==id}},{true},{false}){verified(it.copy(operationId="wrong-operation"))}
        assertTrue(rows.all{it.status==BatchTransferStatus.FAILED_UNCONFIRMED})
    }
    @Test fun invalidAndMutableInputsCannotWidenAdmission() {
        val refs=items.map{it.readRef()}.toMutableList();val p=DirectoryBatchPlan.create("folder",refs);refs.clear();assertEquals(3,p.requests.size)
        for(input in listOf(emptyList(),listOf(items[0].readRef(),items[0].readRef()),(0..100).map{media("i$it").readRef()})) {
            try{DirectoryBatchPlan.create("folder",input);fail("Invalid plan")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun cancellationBetweenDurableStartFenceAndCopyDoesNotCreateOutput() {
        val p=plan();val j=Journal();var cancel=false;j.afterStart={cancel=true}
        val rows=DirectoryBatchCopy(p).run(j,{id->items.single{it.itemId==id}},{true},{cancel}){fail("Write after cancellation");verified(it)}
        assertTrue(rows.all{it.status==BatchTransferStatus.CANCELLED_BEFORE_START})
        assertEquals(1,j.started.size)
    }
    @Test fun partialSuccessRetainsUnconfirmedCleanupAndCannotClaimRolledBackBatch() {
        val p=plan();val rows=DirectoryBatchCopy(p).run(Journal(),{id->items.single{it.itemId==id}},{true},{false}){
            when(it.source.itemId) {
                "a"->verified(it)
                "b"->ExportRecord(it,phase=ExportPhase.CLEANUP_PENDING,cleanup=ExportCleanup.UNCONFIRMED,error=ExportError.RELEASE_UNCONFIRMED)
                else->ExportRecord(it,phase=ExportPhase.CANCELLED,cancelRequested=true,cleanup=ExportCleanup.REMOVED)
            }
        }
        assertEquals(listOf(BatchTransferStatus.VERIFIED_COPY,BatchTransferStatus.CLEANUP_PENDING,BatchTransferStatus.CANCELLED),rows.map{it.status})
    }
    companion object {
        private fun media(id:String)=MediaItem(id,1,1,null,id,1,MediaDateKind.SAVED,10,null,null,null)
        private fun verified(r:ExportRequest)=ExportRecord(r,phase=ExportPhase.SUCCEEDED,output=ExportOutput(r.operationId,ExportKind.SAF),evidence=ExportEvidence(10,"a".repeat(64)),verification=ExportVerification.DESTINATION_VERIFIED)
    }
}
