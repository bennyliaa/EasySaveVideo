package com.easysavevideo.app.library.share

import com.easysavevideo.library.*
import com.easysavevideo.library.export.BatchTransferStatus
import org.junit.Assert.*
import org.junit.Test

class ShareBatchTest {
    private class Port(items:List<MediaItem>):ShareBatchPort<String> {
        val items=items.associateBy { it.itemId }.toMutableMap()
        val prepared=mutableListOf<String>();val revoked=mutableListOf<String>()
        val live=mutableSetOf<String>();var cleanupConfirmed=true
        var onPrepare:((MediaReadRef)->Unit)?=null
        override fun current(id:String)=items[id]
        override fun prepare(ref:MediaReadRef,operationId:String,cancelled:()->Boolean):String {
            check(!cancelled());onPrepare?.invoke(ref)
            prepared+=operationId;live+=operationId
            return "private-capability-${ref.itemId}"
        }
        override fun revoke(operationId:String):Boolean {revoked+=operationId;live-=operationId;return cleanupConfirmed}
    }
    @Test fun preparesWholeGroupWithOneScopedIdAndDoesNotClaimDelivery() {
        val items=listOf(media("a"),media("b"));val p=Port(items);val b=ShareBatch(items)
        val ready=b.prepare(p)
        assertEquals(2,ready.payloads.size);assertEquals(setOf(b.operationId),p.prepared.toSet())
        assertTrue(ready.rows.all { it.status==BatchTransferStatus.PREPARED })
        assertFalse(ready.toString().contains("private-capability"))
        var launches=0
        assertEquals(ShareLaunchResult.LAUNCHED,b.launch(ready,{true}){launches++})
        assertEquals(ShareLaunchResult.ALREADY_LAUNCHED,b.launch(ready,{true}){launches++})
        assertEquals(1,launches);assertTrue(p.revoked.isEmpty())
    }
    @Test fun rejectsOverBudgetAndDuplicateInputWithoutPreparation() {
        for(items in listOf(emptyList(),List(5){media("i$it")},listOf(media("a"),media("a")),
            listOf(media("a").copy(sizeBytes=SharePolicy.MAX_COPY_BYTES+1)),
            List(3){media("i$it").copy(sizeBytes=SharePolicy.MAX_COPY_BYTES)})) {
            try {ShareBatch(items);fail("Invalid group")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun partialPreparationFailureRevokesOnlyGroupAndNeverLaunchesSubset() {
        val items=listOf(media("a"),media("b"),media("c"));val p=Port(items);val b=ShareBatch(items)
        p.live+="unrelated";p.onPrepare={if(it.itemId=="b")throw IllegalStateException("private-uri")}
        val ready=b.prepare(p)
        assertTrue(ready.payloads.isEmpty());assertEquals(listOf(BatchTransferStatus.AUTHORITY_REVOKED,BatchTransferStatus.FAILED_UNCONFIRMED,BatchTransferStatus.NOT_STARTED),ready.rows.map{it.status})
        assertEquals(setOf("unrelated"),p.live)
        assertEquals(ShareLaunchResult.REJECTED,b.launch(ready,{true}){fail("Subset launch")})
    }
    @Test fun cancelBeforeStartDoesNotPrepareAndCannotLaunch() {
        val items=listOf(media("a"));val p=Port(items);val b=ShareBatch(items);b.cancel()
        val ready=b.prepare(p);assertTrue(p.prepared.isEmpty())
        assertEquals(BatchTransferStatus.CANCELLED_BEFORE_START,ready.rows.single().status)
        assertEquals(ShareLaunchResult.REJECTED,b.launch(ready,{true}){fail("Cancelled launch")})
    }
    @Test fun cancellationDuringPreparationDeniesAllCapabilitiesEvenWhenCleanupWaits() {
        val items=listOf(media("a"),media("b"));val p=Port(items);val b=ShareBatch(items)
        p.cleanupConfirmed=false;p.onPrepare={b.cancel()}
        val ready=b.prepare(p)
        assertTrue(ready.payloads.isEmpty());assertTrue(ready.cleanupPending);assertTrue(p.live.isEmpty())
        assertEquals(listOf(BatchTransferStatus.AUTHORITY_REVOKED,BatchTransferStatus.CANCELLED_BEFORE_START),ready.rows.map{it.status})
    }
    @Test fun staleAndMissingSourcesNeverRebaseToAdjacentItems() {
        for(missing in listOf(false,true)) {
            val item=media("a");val p=Port(listOf(item,media("other")));val b=ShareBatch(listOf(item))
            if(missing)p.items.remove("a") else p.items["a"]=item.copy(representationGeneration=2)
            val ready=b.prepare(p);assertTrue(p.prepared.isEmpty())
            assertEquals(if(missing)BatchTransferStatus.UNAVAILABLE else BatchTransferStatus.STALE,ready.rows.single().status)
        }
    }
    @Test fun sourceChangedAfterCopyIsRejectedBeforeChooser() {
        val a=media("a");val p=Port(listOf(a));p.onPrepare={p.items["a"]=a.copy(revision=2)}
        val b=ShareBatch(listOf(a));val ready=b.prepare(p)
        assertEquals(BatchTransferStatus.STALE,ready.rows.single().status);assertTrue(ready.payloads.isEmpty());assertTrue(p.live.isEmpty())
    }
    @Test fun backgroundOrExpiredTargetAfterPreparationCannotLaunch() {
        for(throwing in listOf(false,true)) {
            val items=listOf(media("a"));val p=Port(items);val b=ShareBatch(items);val ready=b.prepare(p)
            val outcome=b.launch(ready,{if(throwing)throw IllegalStateException("target revoked") else false}){fail("Unavailable target")}
            assertEquals(ShareLaunchResult.REJECTED,outcome)
            p.revoke(b.operationId);assertTrue(p.live.isEmpty())
        }
    }
    @Test fun cancellationAfterPreparationAndLaunchFailureRequireRevocationNotDeliverySuccess() {
        val items=listOf(media("a"));val p=Port(items);val b=ShareBatch(items);val ready=b.prepare(p)
        assertEquals(ShareLaunchResult.REJECTED,b.launch(ready,{true}){throw IllegalStateException("unknown launch")})
        p.revoke(b.operationId);assertTrue(p.live.isEmpty())
        val other=ShareBatch(items);val staged=other.prepare(p);other.cancel()
        assertEquals(ShareLaunchResult.REJECTED,other.launch(staged,{true}){fail("Background launch")})
    }
    @Test fun consumedSnapshotCannotPrepareAgainAndInputIsCopied() {
        val items=mutableListOf(media("a"));val p=Port(items);val b=ShareBatch(items);items.clear()
        b.prepare(p)
        try {b.prepare(p);fail("Repeated prepare")}catch(_:IllegalStateException){}
        assertEquals(1,p.prepared.size)
    }
    private fun media(id:String)=MediaItem(id,1,1,null,id,1,MediaDateKind.SAVED,10,null,null,null)
}
