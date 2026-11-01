package com.easysavevideo.app.save

import com.easysavevideo.app.diagnostics.*
import com.easysavevideo.discovery.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class SealedEvaluationExportTest {
    private fun report(): SaveEvaluationSessionReport {
        val session=DiscoverySaveEvaluationSession(SaveEvaluationEvidence.SYNTHETIC_FIXTURE);session.begin()
        val c=PendingCandidatePolicy.normalize(CandidateUrl("https://example.invalid/private-query?token=secret"),"private-provider-id",SearchFilters())!!
        session.register(c);session.beginPage(c.candidateId)
        session.finishMethod(session.beginMethod(c.candidateId,SaveEvaluationMethod.SAVE1),SaveEvaluationOutcome.SUCCEEDED)
        return session.finish()
    }
    private class Queue:Executor {
        val work=ArrayDeque<Runnable>()
        override fun execute(command:Runnable) { work.add(command) }
        fun drain() { while(work.isNotEmpty())work.removeFirst().run() }
    }
    private inner class Harness(val fence:DiagnosticWorkFence=DiagnosticWorkFence(), enabled:Boolean=true) {
        var current:SaveEvaluationSessionReport?=report()
        val states=mutableListOf<DiagnosticExportState>()
        val queue=Queue();val choices=mutableListOf<String>();val dismissed=mutableListOf<String>()
        var time=0L
        val export=SealedEvaluationExport(enabled,{ current },queue,{ it() },states::add,choices::add,dismissed::add,fence,{ time })
        val controller get()=export.controller
        fun choose():String { assertTrue(export.begin(current));queue.drain();return choices.last() }
    }
    private open class Doc(initial:String="", var knownSize:Boolean=true):EvaluationAppendDocument {
        val bytes=ByteArrayOutputStream().apply { write(initial.toByteArray()) }
        var writes=0;var closes=0;var flushed=false
        override fun size():Long?=if(knownSize)bytes.size().toLong() else null
        override fun append(bytes:ByteArray,offset:Int,length:Int) { writes++;this.bytes.write(bytes,offset,length) }
        override fun flush() { flushed=true }
        override fun close() { closes++ }
    }
    @Test fun activeNullStaleAndReleaseDisabledCannotLaunchPicker() {
        val h=Harness();assertFalse(h.export.begin(null));assertFalse(h.export.begin(report()))
        h.current=h.current!!.copy(state=SaveEvaluationSessionState.ACTIVE);assertFalse(h.export.begin(h.current))
        val release=Harness(enabled=false);assertFalse(release.export.begin(release.current));assertTrue(release.choices.isEmpty())
        assertTrue(h.choices.isEmpty())
    }
    @Test fun explicitSealedChoiceExportsAliasedJsonOnlyAfterFlushAndClose() {
        val h=Harness();val id=h.choose();val doc=Doc()
        assertEquals(0,doc.writes);h.controller.selected(id,EvaluationEmptyDocumentPort { doc });h.queue.drain()
        assertEquals(DiagnosticExportPhase.SUCCEEDED,h.controller.state.phase);assertTrue(doc.flushed);assertEquals(1,doc.closes)
        val text=doc.bytes.toString("UTF-8")
        assertTrue(text.contains("\"state\":\"FINISHED\""));assertTrue(text.contains("source-1"))
        for(secret in listOf("example.invalid","private-query","private-provider-id","secret"))assertFalse(text.contains(secret))
        assertEquals(DiscoverySaveEvaluationJson.export(h.current!!),text)
    }
    @Test fun duplicateClickAndOldPickerNeverReplaceOrOpenNewPayload() {
        val h=Harness();val id=h.choose();assertFalse(h.export.begin(h.current))
        var opened=0;val port=DiagnosticExportPort { opened++;ByteArrayOutputStream() }
        h.controller.selected("old",port);assertEquals(0,opened)
        h.controller.selected(id,port);h.controller.selected(id,port);h.queue.drain();assertEquals(1,opened)
    }
    @Test fun bytesAreFrozenBeforeWorkerEvenIfCallerMutatesReportMap() {
        val h=Harness();val original=h.current!!;val mutable=original.evaluation.byProvider.toMutableMap()
        h.current=original.copy(evaluation=original.evaluation.copy(byProvider=mutable))
        val expected=DiscoverySaveEvaluationJson.export(h.current!!);assertTrue(h.export.begin(h.current));mutable.clear()
        h.queue.drain();val doc=Doc();h.controller.selected(h.choices.single(),EvaluationEmptyDocumentPort { doc });h.queue.drain()
        assertEquals(expected,doc.bytes.toString("UTF-8"))
    }
    @Test fun reportChangeDuringPreparationNeverOpensPicker() {
        val h=Harness();assertTrue(h.export.begin(h.current));h.current=report();h.queue.drain()
        assertTrue(h.choices.isEmpty());assertEquals(DiagnosticExportPhase.FAILED,h.controller.state.phase)
    }
    @Test fun reportChangeDuringPickerOrQueuedWriteNeverOpensTarget() {
        for (afterSelection in listOf(false,true)) {
            val h=Harness();val id=h.choose();var opened=0;val port=DiagnosticExportPort { opened++;ByteArrayOutputStream() }
            if(afterSelection)h.controller.selected(id,port)
            h.current=report()
            if(!afterSelection)h.controller.selected(id,port)
            h.queue.drain();assertEquals(0,opened);assertEquals(DiagnosticExportPhase.FAILED,h.controller.state.phase)
        }
    }
    @Test fun explicitNewRunCancellationCannotReviveOldPicker() {
        val h=Harness();val old=h.choose();h.export.reportChanged();h.current=report()
        val fresh=h.choose();assertNotEquals(old,fresh)
        h.controller.selected(old,DiagnosticExportPort { error("Stale picker") })
        val doc=Doc();h.controller.selected(fresh,EvaluationEmptyDocumentPort { doc });h.queue.drain()
        assertEquals(DiagnosticExportPhase.SUCCEEDED,h.controller.state.phase)
    }
    @Test fun cancelledPickerAndBackgroundWriteDoNotClaimSuccess() {
        val h=Harness();val id=h.choose();h.controller.selected(id,null)
        assertEquals(DiagnosticExportPhase.CANCELLED,h.controller.state.phase)
        val next=h.choose();var opens=0;h.controller.selected(next,DiagnosticExportPort { opens++;ByteArrayOutputStream() })
        h.controller.background();h.queue.drain();assertEquals(0,opens);assertEquals(DiagnosticExportPhase.CANCELLED,h.controller.state.phase)
    }
    @Test fun systemPickerPauseIsAllowedButCloseRevokesItsResult() {
        val h=Harness();val id=h.choose();h.controller.background();assertEquals(DiagnosticExportPhase.CHOOSING,h.controller.state.phase)
        h.export.close();h.controller.selected(id,DiagnosticExportPort { error("Closed picker") });assertFalse(h.export.begin(h.current))
    }
    @Test fun sharedFenceRejectsConcurrentActivityAndReleasesOnlyAfterDrain() {
        val fence=DiagnosticWorkFence();val a=Harness(fence);val b=Harness(fence)
        assertTrue(a.export.begin(a.current));assertFalse(b.export.begin(b.current));assertEquals(DiagnosticExportPhase.BLOCKED,b.controller.state.phase)
        a.export.close();assertFalse(b.export.begin(b.current));a.queue.drain();assertTrue(b.export.begin(b.current))
    }
    @Test fun noLongerCurrentOwnerStopsFurtherChunksButDoesNotRollbackAdmittedWrite() {
        val h=Harness();val id=h.choose();var writes=0
        val doc=object:Doc() {
            override fun append(bytes:ByteArray,offset:Int,length:Int) { super.append(bytes,offset,length);writes++;h.current=report() }
        }
        h.controller.selected(id,EvaluationEmptyDocumentPort { doc });h.queue.drain()
        assertEquals(1,writes);assertEquals(DiagnosticExportPhase.FAILED,h.controller.state.phase);assertEquals(1,doc.closes)
    }
    @Test fun nonemptyOrUnknownDescriptorNeverWritesOrTruncates() {
        for(doc in listOf(Doc("keep"),Doc(knownSize=false))) {
            val h=Harness();val id=h.choose();h.controller.selected(id,EvaluationEmptyDocumentPort { doc });h.queue.drain()
            assertEquals(0,doc.writes);assertEquals(1,doc.closes);assertEquals(DiagnosticExportPhase.FAILED,h.controller.state.phase)
            if(doc.knownSize)assertEquals("keep",doc.bytes.toString("UTF-8"))
        }
    }
    @Test fun racingWriterIsAppendedAfterNotOverwrittenAndFailsExtentVerification() {
        val h=Harness();val id=h.choose();val doc=object:Doc() {
            override fun append(bytes:ByteArray,offset:Int,length:Int) { this.bytes.write("external".toByteArray());super.append(bytes,offset,length) }
        }
        h.controller.selected(id,EvaluationEmptyDocumentPort { doc });h.queue.drain()
        assertTrue(doc.bytes.toString("UTF-8").startsWith("external"));assertEquals(DiagnosticExportPhase.FAILED,h.controller.state.phase)
    }
    @Test fun invalidDescriptorOrFlushFailureClosesWithoutSuccess() {
        for(doc in listOf(object:Doc(){ override fun size():Long?=error("Not regular") },object:Doc(){ override fun flush()=error("Flush") })) {
            val h=Harness();val id=h.choose();h.controller.selected(id,EvaluationEmptyDocumentPort { doc });h.queue.drain()
            assertEquals(1,doc.closes);assertEquals(DiagnosticExportPhase.FAILED,h.controller.state.phase)
        }
    }
    @Test fun closeUncertaintyKeepsSharedFenceAndNeverClaimsSuccess() {
        val fence=DiagnosticWorkFence();val h=Harness(fence);val id=h.choose()
        val doc=object:Doc(){ override fun close() { super.close();error("Close uncertain") } }
        h.controller.selected(id,EvaluationEmptyDocumentPort { doc });h.queue.drain()
        assertTrue(h.controller.state.releaseUnconfirmed);assertEquals(DiagnosticExportPhase.FAILED,h.controller.state.phase)
        val next=Harness(fence);assertFalse(next.export.begin(next.current))
    }
    @Test fun cancellationWhileWritingAllowsCleanupOnly() {
        val h=Harness();val id=h.choose();val doc=object:Doc(){ override fun append(bytes:ByteArray,offset:Int,length:Int) {
            super.append(bytes,offset,length);h.controller.cancel()
        } }
        h.controller.selected(id,EvaluationEmptyDocumentPort { doc });h.queue.drain()
        assertEquals(DiagnosticExportPhase.CANCELLED,h.controller.state.phase);assertEquals(1,doc.closes);assertFalse(doc.flushed)
    }
    @Test fun pickerTimeoutRejectsLateResult() {
        val h=Harness();val id=h.choose();h.time=DiagnosticExportController.PICKER_TIMEOUT_MILLIS
        h.controller.selected(id,DiagnosticExportPort { error("Expired") });h.queue.drain()
        assertEquals(DiagnosticExportPhase.TIMED_OUT,h.controller.state.phase)
    }
    @Test fun diagnosticDefaultLimitStays256KiBWhileReportCanUseBounded1MiB() {
        val large="a".repeat(DiagnosticSnapshotReader.MAX_BYTES+1)
        assertThrows(IllegalArgumentException::class.java) { DiagnosticSnapshot(large) }
        assertEquals(large,DiagnosticSnapshot(large,DiscoverySaveEvaluationJson.MAX_EXPORT_BYTES).text)
        assertThrows(IllegalArgumentException::class.java) { DiagnosticSnapshot("a",1_048_577) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticSnapshot("é",1_048_576) }
    }
    @Test fun emptyPortNeverWritesBeyondMaximumReportBytes() {
        val doc=Doc();val output=EvaluationEmptyDocumentPort { doc }.open()
        assertThrows(IllegalStateException::class.java) { output.write(ByteArray(1_048_577)) }
        output.close();assertEquals(0,doc.writes);assertEquals(1,doc.closes)
    }
    @Test fun exportedFixtureIsWrittenForIndependentStandardParser() {
        val h=Harness();val id=h.choose();val doc=Doc();h.controller.selected(id,EvaluationEmptyDocumentPort { doc });h.queue.drain()
        assertEquals(DiagnosticExportPhase.SUCCEEDED,h.controller.state.phase)
        java.io.File("build/evaluation-sealed-export-fixture.json").apply { parentFile.mkdirs();writeBytes(doc.bytes.toByteArray()) }
    }
    @Test fun actualAndroidSeamUsesNewLocalJsonDocumentAndNoTruncateOrPersistentGrant() {
        val source=java.io.File("src/main/kotlin/com/easysavevideo/app/save/AndroidEvaluationReportExport.kt").readText()
        for(required in listOf("Intent.ACTION_CREATE_DOCUMENT","application/json","Intent.EXTRA_LOCAL_ONLY,true","openFileDescriptor(uri,\"rw\")","OsConstants.O_APPEND","OsConstants.S_ISREG","esv-evaluation-","DiagnosticPickerDelivery"))assertTrue(required,source.contains(required))
        for(forbidden in listOf("takePersistableUriPermission","deleteDocument","openOutputStream","\"wt\"","\"rwt\"","HttpURLConnection"))assertFalse(forbidden,source.contains(forbidden))
        val activity=java.io.File("src/main/kotlin/com/easysavevideo/app/MainActivity.kt").readText()
        assertTrue(activity.contains("if (BuildConfig.DEBUG) evaluationExport ="));assertTrue(activity.contains("evaluationExport?.onPause()"));assertTrue(activity.contains("evaluationExport?.close()"))
    }
    @Test fun synchronousWorkerAcceptanceIsNotInferredFromPreparingPhase() {
        val current=report();var picked=false
        val export=SealedEvaluationExport(true,{ current },Executor { it.run() },{ it() },{}, { picked=true },{})
        assertTrue(export.begin(current));assertTrue(picked)
        assertEquals(DiagnosticExportPhase.CHOOSING,export.controller.state.phase)
    }
    @Test fun reportChangingDuringCloseCannotPublishStaleSuccess() {
        val h=Harness();val id=h.choose();val doc=object:Doc(){ override fun close() { super.close();h.current=report() } }
        h.controller.selected(id,EvaluationEmptyDocumentPort { doc });h.queue.drain()
        assertEquals(DiagnosticExportPhase.FAILED,h.controller.state.phase)
        assertEquals(DiagnosticExportPhase.IDLE,h.states.last().phase)
    }
    @Test fun oldSuccessIsClearedBeforeNewRunPreview() {
        val h=Harness();val id=h.choose();h.controller.selected(id,EvaluationEmptyDocumentPort { Doc() });h.queue.drain()
        assertEquals(DiagnosticExportPhase.SUCCEEDED,h.states.last().phase)
        h.export.reportChanged();h.current=report()
        assertEquals(DiagnosticExportPhase.IDLE,h.states.last().phase)
    }
    @Test fun finalExtentMismatchWithConfirmedCloseDoesNotPermanentlyHoldFence() {
        val fence=DiagnosticWorkFence();val h=Harness(fence);val id=h.choose()
        val doc=object:Doc(){ var checks=0;override fun size():Long? {
            checks++;if(checks==4)bytes.write(1)
            return super.size()
        } }
        h.controller.selected(id,EvaluationEmptyDocumentPort { doc });h.queue.drain()
        assertEquals(DiagnosticExportPhase.FAILED,h.controller.state.phase);assertFalse(h.controller.state.releaseUnconfirmed)
        assertEquals(1,doc.closes);assertTrue(Harness(fence).let { it.export.begin(it.current) })
    }
    @Test fun queuedOldSuccessCannotBeDeliveredToNewReportOwner() {
        var current=report();val worker=Queue();val ui=Queue();val choices=mutableListOf<String>()
        val states=mutableListOf<DiagnosticExportState>()
        val export=SealedEvaluationExport(true,{ current },worker,{ action -> ui.execute { action() } },states::add,choices::add,{})
        assertTrue(export.begin(current));worker.drain();ui.drain()
        export.controller.selected(choices.single(),EvaluationEmptyDocumentPort { Doc() });worker.drain()
        assertEquals(DiagnosticExportPhase.SUCCEEDED,export.controller.state.phase)
        export.reportChanged();current=report();assertTrue(export.begin(current))
        ui.drain()
        assertEquals(DiagnosticExportPhase.PREPARING,states.last().phase)
        assertFalse(states.any { it.phase==DiagnosticExportPhase.SUCCEEDED })
    }
}
