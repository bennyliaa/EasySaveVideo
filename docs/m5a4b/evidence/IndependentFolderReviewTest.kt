package com.easysavevideo.app.library.destination

import android.content.*
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.easysavevideo.app.library.export.AndroidExportJournal
import com.easysavevideo.app.library.export.AndroidExportPort
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.destination.*
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual production adapter/control-flow sources with narrow API doubles and synthetic files.
 * Not Android framework/provider/device acceptance. SQL and OS authority remain separate domains. */
class IndependentFolderReviewTest {
    private class Fixture : AutoCloseable {
        val directory=Files.createTempDirectory("independent-folder-").toFile()
        val bytes=ByteArray(8193) { (it%239).toByte() }
        val original=java.io.File(directory,"private-original").apply { writeBytes(bytes) }
        val resolver=ContentResolver(directory)
        val context=Context(resolver)
        val storage=AndroidSaveStorage(original)
        val registry=AndroidFolderGrantRegistry(context,storage)
        val tree=Uri.parse("content://review.documents/tree/root")
        val child=Uri.parse("content://review.documents/tree/root/document/child")
        val flags=1 or 2 or 64 or 128
        val journal=AndroidExportJournal()
        var id:String?=null
        fun accept():String=registry.accept(tree,flags) { true }.also { id=it }
        fun admit():OrdinaryDestinationAdmission {
            val folder=id ?: accept()
            val row=OrdinaryDestinationAdmission("creation",OrdinaryDestinationChoice.FOLDER_COPY,"operation","export-grant",folderGrantId=folder).bindVerifiedSource(storage.exactSource)
            storage.destinations.byOperation[row.operationId]=folder
            resolver.createResult=child
            return row
        }
        fun port()=AndroidExportPort(context,storage,journal)
        fun originalKept() { assertTrue(original.exists()); assertArrayEquals(bytes,original.readBytes()) }
        override fun close() { check(directory.deleteRecursively()) }
    }
    private fun rejected(block:()->Unit) { assertThrows(Exception::class.java) { block() } }
    private fun router(row:OrdinaryDestinationAdmission,journal:AndroidExportJournal,coordinator:ExportCoordinator):OrdinaryDestinationRouter {
        val owner=object:OrdinaryDestinationJournal {
            override fun preference():OrdinaryDestinationPreference=error("MUTABLE_SETTINGS_FORBIDDEN")
            override fun setPreference(value:OrdinaryDestinationPreference)=error("FORBIDDEN")
            override fun admit(creationId:String,choice:OrdinaryDestinationChoice):OrdinaryDestinationAdmission=error("NEW_ADMISSION_FORBIDDEN")
            override fun get(creationId:String)=row.takeIf { it.creationId==creationId }
            override fun linkPublication(publicationId:String,creationId:String)=error("FORBIDDEN")
            override fun pendingCopy(limit:Int)=listOf(row)
            override fun recent(limit:Int):List<OrdinaryDestinationAdmission> = error("HISTORY_SCAN_FORBIDDEN")
        }
        return OrdinaryDestinationRouter(owner,object:OrdinaryDestinationCopyPort {
            override fun existing(operationId:String)=journal.get(operationId)
            override fun run(request:ExportRequest)=coordinator.run(request)
        })
    }
    @Test fun registryPersistsOnlyOfferedReadWriteBitsAfterTracking() = Fixture().use { f ->
        f.storage.folderGrants.onIssue={ assertEquals(0,f.resolver.takes) }
        val id=f.accept()
        assertEquals(listOf("invalidate","issue"),f.storage.folderGrants.events)
        assertEquals(listOf("take:3","query"),f.resolver.events)
        assertEquals(f.tree,f.registry.requireTree(id)); assertEquals(3,f.storage.folderGrants.get(id)!!.flags)
        f.storage.folderGrants.onIssue=null; assertEquals(id,f.accept()); assertEquals(1,f.storage.folderGrants.rows.size)
    }
    @Test fun missingOfferedReadWriteOrPersistableNeverTakesGrant() {
        for(flags in listOf(0,1,2,3,64,65,66,128,129,130,131)) Fixture().use { f ->
            rejected { f.registry.accept(f.tree,flags) { true } }; assertEquals(0,f.resolver.takes); assertTrue(f.storage.folderGrants.rows.isEmpty())
        }
    }
    @Test fun uriTextMalformedOrNestedCannotBecomeRegistryAuthority() {
        for(uri in listOf("file:///tree/root","content://review.documents/document/root","content://review.documents/tree/root/document/child","content://review.documents/tree/root?token=x","content://review.documents/tree/root#fragment","content://review.documents/tree/","content://review.documents/tree/"+"a".repeat(8200))) Fixture().use { f ->
            rejected { f.registry.accept(Uri.parse(uri),f.flags) { true } }; assertEquals(0,f.resolver.takes)
        }
    }
    @Test fun grantStateAndPersistedStateAreIndependentlyRequired() {
        Fixture().use { f -> f.context.grant={_,_->false}; rejected { f.accept() }; assertEquals(0,f.resolver.takes) }
        Fixture().use { f -> f.resolver.persistOnTake=false; rejected { f.accept() }; assertEquals(1,f.resolver.takes); assertEquals(1,f.storage.folderGrants.rows.size) }
        Fixture().use { f -> val id=f.accept(); f.context.grant={_,_->false}; rejected { f.registry.requireTree(id) }; assertTrue(f.storage.folderGrants.get(id)!!.invalidated) }
        Fixture().use { f -> val id=f.accept(); f.resolver.persistedUriPermissions[0]=UriPermission(f.tree,true,false); rejected { f.registry.requireTree(id) }; assertTrue(f.storage.folderGrants.get(id)!!.invalidated) }
    }
    @Test fun staleTicketCannotTakeGrantEvenIfItChangesAfterTracking() = Fixture().use { f ->
        rejected { f.registry.accept(f.tree,f.flags) { false } }; assertEquals(0,f.resolver.takes)
        var current=true; f.storage.folderGrants.onIssue={current=false}
        rejected { f.registry.accept(f.tree,f.flags) { current } }; assertEquals(0,f.resolver.takes); assertEquals(1,f.storage.folderGrants.rows.size)
    }
    @Test fun boundedTrackingAndFailedAdoptionNeverSelectsOrBroadens() = Fixture().use { f ->
        f.storage.folderGrants.capacity=false
        assertThrows(FolderGrantCapacityFailure::class.java) { f.accept() }; assertEquals(0,f.resolver.takes)
        f.storage.folderGrants.capacity=true; f.resolver.takeFails=true
        rejected { f.accept() }; assertEquals(1,f.storage.folderGrants.rows.size); assertEquals(1,f.resolver.takes)
        assertTrue(f.storage.destinations.byOperation.isEmpty())
    }
    @Test fun revokedThenSameUriRegrantGetsNewGenerationAndNeverRevivesOldWork() = Fixture().use { f ->
        val old=f.accept(); f.resolver.persistedUriPermissions.clear()
        val newer=f.accept()
        assertNotEquals(old,newer); assertTrue(f.storage.folderGrants.get(old)!!.invalidated)
        rejected { f.registry.requireTree(old) }; assertEquals(f.tree,f.registry.requireTree(newer))
        assertEquals(2,f.storage.folderGrants.rows.size)
    }
    @Test fun invalidationSignalsOnlyAfterTheOldGenerationIsDurablyMarked() = Fixture().use { f ->
        val old=f.accept(); var signals=0
        val observed=AndroidFolderGrantRegistry(f.context,f.storage) { signals++; assertTrue(f.storage.folderGrants.get(old)!!.invalidated) }
        f.resolver.persistedUriPermissions.clear(); rejected { observed.requireTree(old) }
        assertEquals(1,signals); rejected { observed.requireTree(old) }; assertEquals(1,signals)
    }
    @Test fun asynchronousExportInvalidationNotifiesSettingsWithoutWaitingForRecoveryCompletion() = Fixture().use { f ->
        val row=f.admit(); var signals=0; f.resolver.persistedUriPermissions.clear()
        val port=AndroidExportPort(f.context,f.storage,f.journal) { signals++; assertTrue(f.storage.folderGrants.get(row.folderGrantId!!)!!.invalidated) }
        assertEquals(ExportPhase.FAILED,ExportCoordinator(f.journal,port).run(row.exportRequest()!!).phase)
        assertEquals(1,signals); assertEquals(0,f.resolver.createCalls); f.originalKept()
    }
    @Test fun observedRevocationRemainsInvalidEvenWhenPermissionLaterReturns() = Fixture().use { f ->
        val old=f.accept(); f.resolver.persistedUriPermissions.clear()
        rejected { f.registry.requireTree(old) }
        f.resolver.persistedUriPermissions+=UriPermission(f.tree,true,true)
        rejected { f.registry.requireTree(old) }; assertTrue(f.storage.folderGrants.get(old)!!.invalidated)
    }
    @Test fun retirementKeepsOldAdmittedCapabilityWithoutReactivatingSelection() = Fixture().use { f ->
        val old=f.accept(); f.storage.folderGrants.rows[old]=f.storage.folderGrants.get(old)!!.copy(retired=true)
        assertEquals(f.tree,f.registry.requireTree(old))
        val newer=f.accept(); assertNotEquals(old,newer); assertTrue(f.storage.folderGrants.get(old)!!.retired)
        assertEquals(1,f.resolver.persistedUriPermissions.size)
    }
    @Test fun childMustMatchExactAuthorityTreeAncestryAndCurrentGrant() = Fixture().use { f ->
        val id=f.accept(); assertEquals(f.child,f.registry.requireChild(id,f.child))
        for(uri in listOf("content://other.documents/tree/root/document/child","content://review.documents/tree/other/document/child","content://review.documents/document/child","content://review.documents/tree/root/document/root","content://review.documents/tree/root/document/child?x=1","content://review.documents/tree/root/document/child#x")) rejected { f.registry.requireChild(id,Uri.parse(uri)) }
        f.resolver.childAllowed=false; rejected { f.registry.requireChild(id,f.child) }
        f.resolver.childAllowed=true; f.context.grant={uri,_ -> uri!=f.child}; rejected { f.registry.requireChild(id,f.child) }
    }
    @Test fun directoryCreateSupportNeededForNewOutputButNotOwnedChildCleanup() = Fixture().use { f ->
        val id=f.accept(); f.resolver.directoryFlags=0
        rejected { f.registry.requireTree(id) }; assertEquals(f.child,f.registry.requireChild(id,f.child))
        assertFalse(f.storage.folderGrants.get(id)!!.invalidated)
    }
    @Test fun directoryWrongMimeMissingOrMultipleRowsFailsClosed() {
        for(mode in 0..2) Fixture().use { f ->
            when(mode) { 0->f.resolver.directoryMime="video/mp4"; 1->f.resolver.directoryRows=0; 2->f.resolver.directoryRows=2 }
            rejected { f.accept() }; assertEquals(1,f.storage.folderGrants.rows.size)
        }
    }
    private fun dispatch(activity:ComponentActivity,code:Int,result:Int=1)=AndroidFolderPickerHost.dispatchActivityResult(activity,code,result,null)
    @Test fun pickerCommitsUniqueCorrelationBeforeEveryLaunch() {
        val activity=ComponentActivity(); val host=AndroidFolderPickerHost(activity); val received=mutableListOf<Int>()
        activity.onLaunch={_,code->assertEquals(code+1,activity.preferences.getInt("next",-1))}
        repeat(200) { index ->
            host.launch(Intent()) { code,_->received+=code }
            val code=activity.launched.last().second
            assertEquals(FolderPickerRequestCodes.FIRST+index,code)
            rejected { host.launch(Intent()) { _,_-> } }
            assertTrue(dispatch(activity,code,index))
        }
        assertEquals((0 until 200).toList(),received); assertEquals(200,activity.preferences.commits)
        assertEquals(200,activity.launched.map { it.second }.distinct().size); host.close()
        rejected { host.launch(Intent()) { _,_-> } }
    }
    @Test fun recreatedHostNeverAdoptsOldResultAndKeepsOnlyOneOutstandingCorrelation() {
        val oldActivity=ComponentActivity(); val old=AndroidFolderPickerHost(oldActivity); var oldCalls=0; var newCalls=0
        old.launch(Intent()) { _,_->oldCalls++ }; val oldCode=oldActivity.launched.single().second; old.close()
        val nextActivity=ComponentActivity().apply { preferences=oldActivity.preferences }; val next=AndroidFolderPickerHost(nextActivity)
        rejected { next.launch(Intent()) { _,_->newCalls++ } }
        assertTrue(dispatch(nextActivity,oldCode)); assertEquals(0,oldCalls)
        next.launch(Intent()) { _,_->newCalls++ }; val nextCode=nextActivity.launched.single().second
        assertNotEquals(oldCode,nextCode); assertTrue(dispatch(nextActivity,oldCode)); assertEquals(0,newCalls)
        old.close(); assertTrue(dispatch(nextActivity,nextCode)); assertEquals(1,newCalls); next.close()
    }
    @Test fun reservedColdUnknownAndDuplicateResultsNeverReachAnotherConsumer() {
        val activity=ComponentActivity(); val host=AndroidFolderPickerHost(activity); var calls=0
        assertTrue(dispatch(activity,FolderPickerRequestCodes.FIRST+1000)); assertFalse(dispatch(activity,41320)); assertFalse(dispatch(activity,41321)); assertFalse(dispatch(activity,65536))
        host.launch(Intent()) { _,_->calls++ }; val code=activity.launched.single().second
        assertTrue(dispatch(activity,code+1)); assertEquals(0,calls)
        assertTrue(dispatch(activity,code)); assertTrue(dispatch(activity,code)); assertEquals(1,calls); host.close()
    }
    @Test fun pickerLaunchFailureConsumesCodeAndFreesOnlyItsPendingEntry() {
        val activity=ComponentActivity(); val host=AndroidFolderPickerHost(activity); var calls=0
        activity.throwLaunch=true; rejected { host.launch(Intent()) { _,_->calls++ } }; val oldCode=activity.launched.last().second
        activity.throwLaunch=false; host.launch(Intent()) { _,_->calls++ }; val newer=activity.launched.last().second
        assertEquals(oldCode+1,newer); assertTrue(dispatch(activity,oldCode)); assertEquals(0,calls)
        assertTrue(dispatch(activity,newer)); assertEquals(1,calls); host.close()
    }
    @Test fun failedMalformedOrExhaustedCounterNeverLaunchesPicker() {
        for(value in listOf<Any>(-1,FolderPickerRequestCodes.FIRST-1,FolderPickerRequestCodes.LAST+1,Int.MAX_VALUE,"not-an-int")) {
            val activity=ComponentActivity(); activity.preferences.values["next"]=value; val host=AndroidFolderPickerHost(activity)
            rejected { host.launch(Intent()) { _,_-> } }; assertTrue(activity.launched.isEmpty()); host.close()
        }
        for(durable in listOf(false,true)) {
            val activity=ComponentActivity(); val host=AndroidFolderPickerHost(activity); activity.preferences.commitSuccess=false; activity.preferences.persistOnFailure=durable
            rejected { host.launch(Intent()) { _,_-> } }; assertTrue(activity.launched.isEmpty())
            activity.preferences.commitSuccess=true; host.launch(Intent()) { _,_-> }
            val code=activity.launched.single().second
            assertEquals(FolderPickerRequestCodes.FIRST+if(durable) 1 else 0,code)
            assertTrue(dispatch(activity,code)); host.close()
        }
    }
    @Test fun finalCounterValueIsUsableOnceWithoutWraparound() {
        val activity=ComponentActivity(); activity.preferences.values["next"]=FolderPickerRequestCodes.LAST; val host=AndroidFolderPickerHost(activity)
        host.launch(Intent()) { _,_-> }; assertEquals(FolderPickerRequestCodes.LAST,activity.launched.single().second)
        assertTrue(dispatch(activity,FolderPickerRequestCodes.LAST)); rejected { host.launch(Intent()) { _,_-> } }
        assertEquals(1,activity.launched.size); assertEquals(FolderPickerRequestCodes.LAST+1,activity.preferences.getInt("next",-1)); host.close()
    }
    @Test fun pickerRequiresResumedOwnerAndClearsPendingBeforeInvokingCallback() {
        val activity=ComponentActivity(); val host=AndroidFolderPickerHost(activity)
        activity.lifecycle.currentState=Lifecycle.State.CREATED; rejected { host.launch(Intent()) { _,_-> } }; assertEquals(0,activity.preferences.commits)
        activity.lifecycle.currentState=Lifecycle.State.RESUMED; var calls=0
        host.launch(Intent()) { _,_->calls++; host.launch(Intent()) { _,_->calls++ } }
        assertTrue(dispatch(activity,activity.launched.single().second)); assertEquals(2,activity.launched.size)
        assertTrue(dispatch(activity,activity.launched.last().second)); assertEquals(2,calls); host.close()
    }
    @Test fun counterAllocationIsSerializedAcrossCompetingHosts() {
        val shared=SharedPreferences(); val first=ComponentActivity().apply { preferences=shared }; val second=ComponentActivity().apply { preferences=shared }
        val a=AndroidFolderPickerHost(first); val b=AndroidFolderPickerHost(second)
        val start=CountDownLatch(1); val done=CountDownLatch(2); val winners=java.util.concurrent.atomic.AtomicInteger()
        val threads=listOf(a,b).map { host -> Thread { start.await(); try { host.launch(Intent()) { _,_-> }; winners.incrementAndGet() } catch(_:IllegalStateException) {} finally { done.countDown() } }.also { it.start() } }
        start.countDown(); assertTrue(done.await(5,TimeUnit.SECONDS)); threads.forEach { it.join(5000) }
        assertEquals(1,winners.get()); assertEquals(1,shared.commits)
        val owner=if(first.launched.isNotEmpty()) first else second
        assertTrue(dispatch(owner,owner.launched.single().second)); a.close(); b.close()
    }
    @Test fun staleDuplicateDetachedAndUnreceivedTicketsCannotCommit() {
        val gate=FolderSelectionGate(); val owner=Any(); val old=gate.begin(owner)!!; var commits=0
        assertFalse(gate.commit(old) { commits++ }); assertTrue(gate.receive(old)); assertFalse(gate.receive(old)); assertTrue(gate.detach(owner))
        val next=gate.begin(Any())!!; assertFalse(gate.receive(old)); assertFalse(gate.cancel(old)); assertFalse(gate.commit(old) { commits++ })
        assertTrue(gate.receive(next)); assertTrue(gate.commit(next) { commits++ }); assertFalse(gate.commit(next) { commits++ }); assertEquals(1,commits)
    }
    @Test fun cancellationAndDurableCommitHaveOneSerialOrder() {
        val gate=FolderSelectionGate(); val ticket=gate.begin(Any())!!; gate.receive(ticket)
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val failure=AtomicReference<Throwable?>(); var cancelResult=true
        val writer=Thread { try { assertTrue(gate.commit(ticket) { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }) } catch(t:Throwable) { failure.set(t) } }
        writer.start(); assertTrue(entered.await(5,TimeUnit.SECONDS))
        val canceller=Thread { cancelResult=gate.cancel(ticket) }; canceller.start(); release.countDown(); writer.join(5000); canceller.join(5000)
        assertFalse(writer.isAlive); assertFalse(canceller.isAlive); failure.get()?.let { throw AssertionError(it) }; assertFalse(cancelResult)
    }
    @Test fun revisionOrderPreventsLateRevocationRefreshFromErasingNewSelection() {
        val order=FolderSettingsOrder()
        val old=OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.FOLDER_COPY,"old-folder",10)
        val revoked=OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.APP_LIBRARY,null,11)
        val replacement=OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.FOLDER_COPY,"new-folder",12)
        assertTrue(order.accept(old)); assertTrue(order.accept(replacement)); assertFalse(order.accept(revoked)); assertFalse(order.accept(old))
        assertTrue(order.accept(replacement)); assertFalse(order.accept(replacement.copy(folderGrantId="conflicting-folder")))
        assertTrue(order.accept(OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.APP_LIBRARY,null,13)))
        assertFalse(order.accept(replacement))
    }
    @Test fun settingsOrderHandlesEveryDeliveryPermutationAndRejectsInvalidRevision() {
        val snapshots=(0L..3L).map { OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.ASK,"folder-$it",it) }
        fun permutations(items:List<OrdinaryDestinationSelectionSnapshot>):List<List<OrdinaryDestinationSelectionSnapshot>> =
            if(items.isEmpty()) listOf(emptyList()) else items.flatMap { x -> permutations(items-x).map { listOf(x)+it } }
        for(delivery in permutations(snapshots)) {
            val order=FolderSettingsOrder(); var high=-1L
            for(snapshot in delivery) { assertEquals(snapshot.revision>=high,order.accept(snapshot)); high=maxOf(high,snapshot.revision) }
            assertTrue(order.accept(snapshots.last())); assertFalse(order.accept(snapshots.first()))
        }
        rejected { OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.APP_LIBRARY,null,-1) }
    }
    @Test fun choiceSnapshotsCannotReadNewFolderOrRebindNewSource() {
        val selection=OrdinaryDestinationSelection(); val ticket=selection.begin(OrdinaryDestinationPreference.FOLDER_COPY,"old-folder")!!
        assertEquals("old-folder",ticket.folderGrantId); assertNull(selection.begin(OrdinaryDestinationPreference.FOLDER_COPY,"new-folder"))
        assertTrue(selection.choose(ticket)); assertTrue(selection.finish(ticket))
        val row=OrdinaryDestinationAdmission("creation",OrdinaryDestinationChoice.FOLDER_COPY,"operation","grant",folderGrantId=ticket.folderGrantId).bindVerifiedSource(MediaReadRef("source",2,3))
        assertEquals(ExportKind.SAF,row.exportRequest()!!.destination.kind); assertEquals("grant",row.exportRequest()!!.destination.grantId)
        rejected { row.bindVerifiedSource(MediaReadRef("source",3,3)) }; assertEquals("old-folder",row.folderGrantId)
        assertFalse(row.toString().contains("old-folder"))
        rejected { OrdinaryDestinationAdmission("c",OrdinaryDestinationChoice.FOLDER_COPY,"o","same",folderGrantId="same") }
    }
    @Test fun realSafAdapterCopiesVerifiesAndBindsBeforeOpeningWriterExactlyOnce() = Fixture().use { f ->
        val row=f.admit(); val coordinator=ExportCoordinator(f.journal,f.port())
        f.resolver.afterCreate={ assertEquals(ExportPhase.CREATE_INTENT,f.journal.get(row.operationId)!!.phase) }
        f.resolver.beforeOpen={uri,mode-> if(mode=="wt") { assertEquals(f.child,uri); assertEquals(uri.toString(),f.journal.target(row.operationId)!!.uri); assertTrue("bound" in f.journal.events) } }
        val route=router(row,f.journal,coordinator)
        assertEquals(ExportPhase.SUCCEEDED,route.route(1).single().phase)
        repeat(10) { assertEquals(ExportPhase.SUCCEEDED,route.route(1).single().phase) }
        assertEquals(1,f.resolver.createCalls); assertEquals(1,f.resolver.writes); assertEquals(1,f.resolver.reads)
        assertArrayEquals(f.bytes,f.resolver.files.getValue(f.child).readBytes()); f.originalKept()
    }
    @Test fun adapterMissingExactUriAfterCreateStaysUnknownAndNeverGuessesOrRecreates() = Fixture().use { f ->
        val row=f.admit(); f.journal.failBindBefore=true; val coordinator=ExportCoordinator(f.journal,f.port())
        assertEquals(ExportPhase.RECONCILIATION_REQUIRED,coordinator.run(row.exportRequest()!!).phase)
        assertNull(f.journal.target(row.operationId)!!.uri); assertEquals(0,f.resolver.writes)
        f.journal.failBindBefore=false
        val restarted=ExportCoordinator(f.journal,f.port())
        repeat(5) { assertEquals(ExportPhase.RECONCILIATION_REQUIRED,restarted.recover(row.operationId).phase) }
        assertEquals(1,f.resolver.createCalls); assertEquals(0,f.resolver.deleteCalls); assertTrue(f.resolver.files.containsKey(f.child)); f.originalKept()
    }
    @Test fun adapterDurableUriAckLossAllowsExactCleanupOnlyAndNoSecondCreate() = Fixture().use { f ->
        val row=f.admit(); f.journal.failBindAfter=true; val coordinator=ExportCoordinator(f.journal,f.port())
        assertEquals(ExportPhase.RECONCILIATION_REQUIRED,coordinator.run(row.exportRequest()!!).phase)
        assertEquals(f.child.toString(),f.journal.target(row.operationId)!!.uri); assertEquals(0,f.resolver.writes)
        f.journal.failBindAfter=false
        assertEquals(ExportPhase.FAILED,ExportCoordinator(f.journal,f.port()).recover(row.operationId).phase)
        assertEquals(1,f.resolver.createCalls); assertEquals(1,f.resolver.deleteCalls); f.originalKept()
    }
    @Test fun nullCreateAndOutOfTreeChildNeverOpenAnyWriter() {
        for(outOfTree in listOf(false,true)) Fixture().use { f ->
            val row=f.admit(); f.resolver.createResult=if(outOfTree) Uri.parse("content://other/tree/root/document/child") else null
            val coordinator=ExportCoordinator(f.journal,f.port())
            assertEquals(ExportPhase.RECONCILIATION_REQUIRED,coordinator.run(row.exportRequest()!!).phase)
            repeat(3) { coordinator.recover(row.operationId) }; assertEquals(1,f.resolver.createCalls); assertEquals(0,f.resolver.writes); f.originalKept()
        }
    }
    @Test fun stalePrivateSourceOrRevokedTreeNeverCreatesOutput() {
        for(revoke in listOf(false,true)) Fixture().use { f ->
            val row=f.admit(); if(revoke) f.resolver.persistedUriPermissions.clear() else f.storage.exactSource=f.storage.exactSource.copy(revision=8)
            assertEquals(ExportPhase.FAILED,ExportCoordinator(f.journal,f.port()).run(row.exportRequest()!!).phase)
            assertEquals(0,f.resolver.createCalls); f.originalKept()
        }
    }
    @Test fun fullReadbackRejectsCorruptionTruncationAndExtraByteWithoutLosingOriginal() {
        for(mode in 0..2) Fixture().use { f ->
            val row=f.admit(); f.resolver.readbackTransform={ bytes -> when(mode) { 0->bytes.copyOf().also { it[0]=(it[0].toInt() xor 1).toByte() }; 1->bytes.copyOf(bytes.size-1); else->bytes+byteArrayOf(99) } }
            val result=ExportCoordinator(f.journal,f.port()).run(row.exportRequest()!!)
            assertEquals(ExportPhase.FAILED,result.phase); assertEquals(ExportError.DESTINATION_MISMATCH,result.error)
            assertEquals(1,f.resolver.deleteCalls); f.originalKept()
        }
    }
    @Test fun unsupportedReadbackIsNeverSuccessAndExplicitCleanupUsesSameOwnedChild() = Fixture().use { f ->
        val row=f.admit(); f.resolver.readbackUnsupported=true; val coordinator=ExportCoordinator(f.journal,f.port())
        assertEquals(ExportPhase.COPIED_UNVERIFIED,coordinator.run(row.exportRequest()!!).phase)
        assertEquals(1,f.resolver.createCalls); assertEquals(0,f.resolver.deleteCalls)
        assertEquals(ExportPhase.CANCELLED,coordinator.cleanupUnverified(row.operationId).phase)
        assertEquals(1,f.resolver.deleteCalls); assertEquals(1,f.resolver.createCalls); f.originalKept()
    }
    @Test fun acceptedCancellationAfterCreatePreventsWriteAndDeletesOnlyOwnedCopy() = Fixture().use { f ->
        val row=f.admit(); val coordinator=ExportCoordinator(f.journal,f.port())
        f.resolver.afterCreate={ assertEquals(ExportCancellation.ACCEPTED,coordinator.cancel(row.operationId)) }
        assertEquals(ExportPhase.CANCELLED,coordinator.run(row.exportRequest()!!).phase)
        assertEquals(0,f.resolver.writes); assertEquals(1,f.resolver.deleteCalls); f.originalKept()
    }
    @Test fun permissionLostAfterBindPreventsCompletionAndRetainsUnconfirmedCleanup() = Fixture().use { f ->
        val row=f.admit(); f.resolver.beforeOpen={_,mode-> if(mode=="wt") f.resolver.persistedUriPermissions.clear() }
        // Revocation occurs after validation but before the synthetic open; the fake open still succeeds.
        // Subsequent production readback/cleanup checks must observe loss and refuse completion.
        val result=ExportCoordinator(f.journal,f.port()).run(row.exportRequest()!!)
        assertEquals(ExportPhase.CLEANUP_PENDING,result.phase); assertEquals(ExportCleanup.UNCONFIRMED,result.cleanup)
        assertEquals(0,f.resolver.deleteCalls); assertTrue(f.storage.folderGrants.get(row.folderGrantId!!)!!.invalidated); f.originalKept()
    }
    @Test fun switchingOrForgettingDoesNotRedirectAlreadyAdmittedCopy() = Fixture().use { f ->
        val row=f.admit(); val old=row.folderGrantId!!
        f.storage.folderGrants.rows[old]=f.storage.folderGrants.get(old)!!.copy(retired=true)
        val newTree=Uri.parse("content://review.documents/tree/newroot"); val newer=f.registry.accept(newTree,f.flags) { true }
        assertNotEquals(old,newer)
        assertEquals(ExportPhase.SUCCEEDED,router(row,f.journal,ExportCoordinator(f.journal,f.port())).route(1).single().phase)
        assertTrue(f.journal.target(row.operationId)!!.uri!!.contains("/tree/root/")); f.originalKept()
    }
    @Test fun cancellationBeforeRouterStartsCreatesNoProviderOrSourceSideEffect() = Fixture().use { f ->
        val row=f.admit(); val request=row.exportRequest()!!; f.journal.create(request)
        val coordinator=ExportCoordinator(f.journal,f.port())
        assertEquals(ExportCancellation.ACCEPTED,coordinator.cancel(row.operationId))
        assertEquals(ExportPhase.CANCELLED,router(row,f.journal,coordinator).route(1).single().phase)
        assertEquals(0,f.storage.sourceOpens); assertEquals(0,f.resolver.createCalls); assertEquals(0,f.resolver.writes); f.originalKept()
    }
    @Test fun lossOfCreateSupportDoesNotBlockCleanupOfAnExistingExactChild() = Fixture().use { f ->
        val row=f.admit(); f.resolver.readbackUnsupported=true; val coordinator=ExportCoordinator(f.journal,f.port())
        assertEquals(ExportPhase.COPIED_UNVERIFIED,coordinator.run(row.exportRequest()!!).phase)
        f.resolver.directoryFlags=0
        assertEquals(ExportPhase.CANCELLED,coordinator.cleanupUnverified(row.operationId).phase)
        assertEquals(1,f.resolver.deleteCalls); f.originalKept()
    }
    @Test fun restartAfterDurableCreateIntentDoesNotCreateEvenIfProviderWasNeverCalled() = Fixture().use { f ->
        val row=f.admit(); f.journal.fatalAfterCreateIntent=true
        assertThrows(AssertionError::class.java) { ExportCoordinator(f.journal,f.port()).run(row.exportRequest()!!) }
        f.journal.fatalAfterCreateIntent=false
        val result=ExportCoordinator(f.journal,f.port()).recover(row.operationId)
        assertEquals(ExportPhase.RECONCILIATION_REQUIRED,result.phase); assertEquals(0,f.resolver.createCalls); f.originalKept()
    }
}
