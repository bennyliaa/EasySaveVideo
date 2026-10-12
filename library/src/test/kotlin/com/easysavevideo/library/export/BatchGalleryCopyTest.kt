package com.easysavevideo.library.export

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test

class BatchGalleryCopyTest {
    @Test fun independentlyVerifiedCopiesUseUniqueOperationAndTargetIds() {
        val items=listOf(media("a"),media("b"));val lookup=items.associateBy{it.itemId};val requests=mutableListOf<ExportRequest>()
        val rows=BatchGalleryCopy(items.map{it.readRef()}).run(lookup::get,{false}) { requests+=it;verified(it) }
        assertTrue(rows.all{it.status==BatchTransferStatus.VERIFIED_COPY})
        assertEquals(2,requests.map{it.operationId}.distinct().size);assertEquals(2,requests.map{it.destination.grantId}.distinct().size)
        assertTrue(requests.all{it.destination.kind==ExportKind.MEDIA_STORE})
    }
    @Test fun mixedFailureAndUncertainPublicationNeverClaimWholeGroupSuccess() {
        val items=listOf(media("a"),media("b"),media("c"));val lookup=items.associateBy{it.itemId}
        val rows=BatchGalleryCopy(items.map{it.readRef()}).run(lookup::get,{false}) {
            when(it.source.itemId) {"a"->verified(it);"b"->ExportRecord(it,phase=ExportPhase.CLEANUP_PENDING,cleanup=ExportCleanup.PENDING);else->throw IllegalStateException("private locator")}
        }
        assertEquals(listOf(BatchTransferStatus.VERIFIED_COPY,BatchTransferStatus.CLEANUP_PENDING,BatchTransferStatus.FAILED_UNCONFIRMED),rows.map{it.status})
        assertFalse(rows.toString().contains("private locator"))
    }
    @Test fun cancelledCurrentCopyAndUnstartedItemsRemainDistinct() {
        val items=listOf(media("a"),media("b"));val lookup=items.associateBy{it.itemId};var cancel=false
        val rows=BatchGalleryCopy(items.map{it.readRef()}).run(lookup::get,{cancel}) {cancel=true;ExportRecord(it,phase=ExportPhase.CANCELLED,cancelRequested=true)}
        assertEquals(listOf(BatchTransferStatus.CANCELLED,BatchTransferStatus.CANCELLED_BEFORE_START),rows.map{it.status})
    }
    @Test fun cancellationAfterPublishDoesNotPretendToRollBackCompletedCopy() {
        val items=listOf(media("a"),media("b"));val lookup=items.associateBy{it.itemId};var cancel=false
        val rows=BatchGalleryCopy(items.map{it.readRef()}).run(lookup::get,{cancel}) {cancel=true;verified(it)}
        assertEquals(listOf(BatchTransferStatus.VERIFIED_COPY,BatchTransferStatus.CANCELLED_BEFORE_START),rows.map{it.status})
    }
    @Test fun staleMissingAndDeletedReferencesCreateNoOutputs() {
        val original=listOf(media("a"),media("b"),media("c"));val lookup=mapOf("a" to original[0].copy(revision=2),"b" to original[1].copy(lifecycle=MediaLifecycle.DELETED))
        val rows=BatchGalleryCopy(original.map{it.readRef()}).run(lookup::get,{false}) {fail("Stale copy");verified(it)}
        assertEquals(listOf(BatchTransferStatus.STALE,BatchTransferStatus.UNAVAILABLE,BatchTransferStatus.UNAVAILABLE),rows.map{it.status})
    }
    @Test fun cancellationDuringLookupStopsJournalCreation() {
        val a=media("a");var cancelled=false
        val rows=BatchGalleryCopy(listOf(a.readRef())).run({cancelled=true;a},{cancelled}) {fail("Cancelled copy");verified(it)}
        assertEquals(BatchTransferStatus.CANCELLED_BEFORE_START,rows.single().status)
    }
    @Test fun mismatchedOperationResultIsNeverCreditedToThisCopy() {
        val a=media("a")
        val rows=BatchGalleryCopy(listOf(a.readRef())).run({a},{false}) {verified(it.copy(operationId="other-operation"))}
        assertEquals(BatchTransferStatus.FAILED_UNCONFIRMED,rows.single().status)
    }
    @Test fun consumedSnapshotAndInvalidInputAreRejected() {
        val a=media("a");val refs=mutableListOf(a.readRef());val batch=BatchGalleryCopy(refs);refs.clear()
        batch.run({a},{false}){verified(it)}
        try {batch.run({a},{false}){verified(it)};fail("Duplicate group")}catch(_:IllegalStateException){}
        for(input in listOf(emptyList(),listOf(a.readRef(),a.readRef()),(0..100).map{media("i$it").readRef()})) {
            try {BatchGalleryCopy(input);fail("Invalid snapshot")}catch(_:IllegalArgumentException){}
        }
    }
    private fun media(id:String)=MediaItem(id,1,1,null,id,1,MediaDateKind.SAVED,10,null,null,null)
    private fun verified(request:ExportRequest)=ExportRecord(request,phase=ExportPhase.SUCCEEDED,
        output=ExportOutput(request.operationId,ExportKind.MEDIA_STORE),evidence=ExportEvidence(10,"a".repeat(64)),verification=ExportVerification.DESTINATION_VERIFIED)
}
