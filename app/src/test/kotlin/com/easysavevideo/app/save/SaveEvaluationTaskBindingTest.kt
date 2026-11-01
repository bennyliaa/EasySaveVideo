package com.easysavevideo.app.save

import com.easysavevideo.core.model.*
import com.easysavevideo.discovery.*
import com.easysavevideo.library.destination.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test

class SaveEvaluationTaskBindingTest {
    private fun admission(id: String = "task") = OrdinaryDestinationAdmission(id, OrdinaryDestinationChoice.APP_LIBRARY,"destination-op","grant")
    private fun request(method: TaskMethod = TaskMethod.DIRECT) = SaveRequest("task","key",
        SelectedMediaResource("candidate","variant",SourceUrl("https://example.invalid/media")).asDurableReference(),method)
    private fun accepted(method: TaskMethod = TaskMethod.DIRECT) = SaveRecord(request(method),
        TaskSnapshot("task",0,TaskState.QUEUED,method),"commit","operation",10000,0)
    private fun terminal(state: TaskState = TaskState.FAILED, error: SaveError? = SaveError.INVALID_MEDIA,
        phase: SavePhase = SavePhase.VERIFYING, method: TaskMethod = TaskMethod.DIRECT): SaveRecord =
        accepted(method).copy(task=TaskSnapshot("task",2,state,method, waitingUser=
            if(state==TaskState.WAITING_USER) UserWait(1000,ResumeAction.RETRY) else null),
            phase=phase,error=error,waitReason=if(state==TaskState.WAITING_USER) SaveWaitReason.NETWORK else null)
    private fun committed(): SaveRecord {
        val proof=VerificationEvidence("artifact",100,"a".repeat(64),true,1,0,5000,1280,720,false,DecodePosition.entries.toSet(),true)
        return accepted().copy(task=TaskSnapshot("task",3,TaskState.SUCCEEDED,TaskMethod.DIRECT),phase=SavePhase.CLEANUP,
            artifact=StagingArtifact("artifact","commit"),transfer=TransferResult("artifact",100,"video/mp4",100),
            verification=proof,receipt=MediaCommitReceipt("commit","artifact","media",100,"a".repeat(64)))
    }
    private fun committedStream(): SaveRecord {
        val video=StreamSampleAccumulator(StreamSampleKind.AVC)
        repeat(20) { i -> video.add(i*100_000L,if(i%10==0) 1 else 0,byteArrayOf(0,0,1,if(i%10==0) 0x65 else 0x41,(i+1).toByte())) }
        val audio=StreamSampleAccumulator(StreamSampleKind.AAC,48000)
        repeat(94) { i -> audio.add(i*1_024_000_000L/48000,1,byteArrayOf(1,i.toByte(),3)) }
        val c=StreamConservationContract(video.finish(),audio.finish(),-10000,video.videoTail(),video.endpoint(),2)
        val proof=VerificationEvidence("artifact",100,"a".repeat(64),true,1,1,2000,16,16,false,DecodePosition.entries.toSet(),true,c,
            StreamTailProof(c.videoTail.precedingSync,c.video.lastSample,c.video.lastSample.presentationTimeUs,true))
        return committed().copy(request=request(TaskMethod.STREAM),task=TaskSnapshot("task",3,TaskState.SUCCEEDED,TaskMethod.STREAM),
            transfer=TransferResult("artifact",100,"video/mp4",100,streamExpectation=StreamArtifactExpectation(2000,1,1,16,16,c)),verification=proof)
    }
    private class Fixture(val method: TaskMethod = TaskMethod.DIRECT, capacity: Int = 100) {
        val links=SaveEvaluationTaskLinks(capacity)
        val session=DiscoverySaveEvaluationSession(SaveEvaluationEvidence.SYNTHETIC_FIXTURE)
        val page=PendingCandidatePolicy.normalize(CandidateUrl("https://example.invalid/page"),"source",SearchFilters())!!
        val admission=OrdinaryDestinationAdmission("task",OrdinaryDestinationChoice.APP_LIBRARY,"destination-op","grant")
        init { session.begin();session.register(page);session.beginPage(page.candidateId) }
        var handle: SaveEvaluationTaskBinding? = null
        fun arm()=links.arm(admission,method,session,page.candidateId).also { if(it != null) handle=it }
        fun counts()=session.snapshot().evaluation.overall
    }
    @Test fun exactVerifiedDirectAndConservedStreamCommitAreSuccess() {
        assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT,classifyEvaluationSave("task",TaskMethod.DIRECT,committed()))
        assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT,classifyEvaluationSave("task",TaskMethod.STREAM,committedStream()))
    }
    @Test fun wrongTaskMethodMissingRecordAndPrecommitAreUnknown() {
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("other",TaskMethod.DIRECT,committed()))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("task",TaskMethod.STREAM,committed()))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("task",TaskMethod.RECORD,committed()))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("task",TaskMethod.DIRECT,null))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("task",TaskMethod.DIRECT,accepted()))
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("task",TaskMethod.DIRECT,committed().copy(phase=SavePhase.COMMITTING)))
    }
    @Test fun receiptPresenceOrSuccessStateWithoutValidProofCannotBecomeFailure() {
        val provisional=committed().copy(task=TaskSnapshot("task",3,TaskState.FAILED,TaskMethod.DIRECT),error=SaveError.INVALID_MEDIA)
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("task",TaskMethod.DIRECT,provisional))
        val positions=DecodePosition.entries.toMutableSet()
        val forged=committed().let { it.copy(verification=it.verification!!.copy(decodedPositions=positions)) }
        positions.clear()
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("task",TaskMethod.DIRECT,forged))
    }
    @Test fun onlyNarrowExecutedTerminalFailuresCountAsFailure() {
        for (method in listOf(TaskMethod.DIRECT,TaskMethod.STREAM)) {
            assertEquals(SaveEvaluationTaskResult.DEFINITIVE_FAILURE,classifyEvaluationSave("task",method,terminal(method=method)))
            assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("task",method,terminal(phase=SavePhase.PREPARING,method=method)))
            assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("task",method,terminal(phase=SavePhase.COMMITTING,method=method)))
        }
    }
    @Test fun cancelledInterruptedWaitingUnsupportedAndPolicyCasesNeverCountFailure() {
        for (state in listOf(TaskState.CANCELLED,TaskState.INTERRUPTED,TaskState.WAITING_USER))
            assertEquals(SaveEvaluationTaskResult.BLOCKED,classifyEvaluationSave("task",TaskMethod.DIRECT,terminal(state=state)))
        for (error in listOf(SaveError.METHOD_UNSUPPORTED,SaveError.CANCELLED,SaveError.POLICY_BLOCKED,SaveError.PROTECTED_MEDIA,
            SaveError.AUTHENTICATION_REQUIRED,SaveError.WIFI_REQUIRED,SaveError.BUSY))
            assertEquals(SaveEvaluationTaskResult.BLOCKED,classifyEvaluationSave("task",TaskMethod.DIRECT,terminal(error=error)))
    }
    @Test fun recoveryUnreadableCommitAndUnknownErrorsAreUnknown() {
        for(error in listOf(SaveError.RECOVERY_REQUIRED,SaveError.COMMIT_MISMATCH,SaveError.RESOURCE_RELEASE_UNCONFIRMED,
            SaveError.JOURNAL_UNAVAILABLE,SaveError.UNKNOWN_TECHNICAL_ERROR))
            assertEquals(SaveEvaluationTaskResult.UNKNOWN,classifyEvaluationSave("task",TaskMethod.DIRECT,terminal(error=error)))
    }
    @Test fun noContextAndUnknownTaskDoNotReadAnything() {
        val f=Fixture()
        assertNull(f.links.afterExecution(f.handle,"task") { error("No read without evaluation") })
        assertNull(f.links.accepted(f.admission,accepted(),false))
        assertEquals(0,f.counts().save1.tested)
    }
    @Test fun freshAcceptanceAndExactCompletionCountOnce() {
        val f=Fixture(); assertNotNull(f.arm()); assertNotNull(f.links.accepted(f.admission,accepted(),false))
        var reads=0
        assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT,f.links.afterExecution(f.handle,"task") { reads++;committed() })
        assertNull(f.links.afterExecution(f.handle,"task") { error("Duplicate") });assertEquals(1,reads)
        assertEquals(1,f.counts().save1.succeeded);assertEquals(1,f.counts().actualTestPages)
    }
    @Test fun streamUsesOnlySave2Ticket() {
        val f=Fixture(TaskMethod.STREAM);f.arm();assertNotNull(f.links.accepted(f.admission,accepted(TaskMethod.STREAM),false))
        f.links.afterExecution(f.handle,"task") { committedStream() }
        assertEquals(1,f.counts().save2.succeeded);assertEquals(0,f.counts().save1.succeeded)
    }
    @Test fun copiedAdmissionCannotStealBindingEvenWithEqualFields() {
        val f=Fixture();f.arm()
        assertNull(f.links.accepted(f.admission.copy(),accepted(),false))
        assertNotNull(f.links.accepted(f.admission,accepted(),false))
    }
    @Test fun existingRestoredOrWrongMethodAcceptanceNeverBeginsAttempt() {
        for (record in listOf(committed(),accepted(TaskMethod.STREAM))) {
            val f=Fixture();f.arm();assertNull(f.links.accepted(f.admission,record,false))
            assertNull(f.links.afterExecution(f.handle,"task") { error("Rejected") })
        }
        val f=Fixture();f.arm();assertNull(f.links.accepted(f.admission,accepted(),true))
        assertEquals(0,f.session.snapshot().unfinishedMethodAttempts)
    }
    @Test fun wrongTaskCannotConsumeBindingOrCreateResult() {
        val f=Fixture();f.arm();f.links.accepted(f.admission,accepted(),false)
        assertNull(f.links.afterExecution(f.handle,"another") { error("Unrelated") })
        f.links.afterExecution(f.handle,"task") { committed() };assertEquals(1,f.counts().save1.succeeded)
    }
    @Test fun mismatchedCommitOrOperationRemainsUnknown() {
        val otherCommit=committed().let { it.copy(commitId="other",artifact=it.artifact!!.copy(commitId="other"),receipt=it.receipt!!.copy(commitId="other")) }
        for(record in listOf(otherCommit,committed().copy(operationId="other"))) {
            val f=Fixture();f.arm();f.links.accepted(f.admission,accepted(),false)
            assertEquals(SaveEvaluationTaskResult.UNKNOWN,f.links.afterExecution(f.handle,"task") { record })
            assertEquals(0,f.counts().save1.tested);assertTrue(f.session.finish().evaluation.overall.partial)
        }
    }
    @Test fun recordReadExceptionDoesNotEscapeOrInventFailure() {
        val f=Fixture();f.arm();f.links.accepted(f.admission,accepted(),false)
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,f.links.afterExecution(f.handle,"task") { error("sensitive exception text") })
        assertEquals(0,f.counts().save1.failed);assertEquals(1,f.session.finish().unfinishedMethodAttempts)
    }
    @Test fun sessionClosedBeforeLateCallbackNeverReadsOrChangesOutcome() {
        val f=Fixture();f.arm();f.links.accepted(f.admission,accepted(),false);val closed=f.session.finish()
        assertEquals(SaveEvaluationTaskResult.UNKNOWN,f.links.afterExecution(f.handle,"task") { error("Closed") })
        assertEquals(closed,f.session.snapshot())
    }
    @Test fun closeOrOpaqueAbandonFencesLateCallbacks() {
        val f=Fixture();val h=f.arm()!!;f.links.accepted(f.admission,accepted(),false);f.links.abandon(h)
        assertNull(f.links.afterExecution(f.handle,"task") { error("Abandoned") })
        val g=Fixture();g.arm();g.links.accepted(g.admission,accepted(),false);g.links.close(g.session)
        assertNull(g.links.afterExecution(g.handle,"task") { error("Closed") })
    }
    @Test fun refusedWorkerHandoffIsBlockedAndCannotUnlockFallback() {
        val f=Fixture();f.arm();f.links.accepted(f.admission,accepted(),false);f.links.notLaunched(f.handle,"task")
        assertEquals(1,f.counts().save1.blocked);assertEquals(0,f.counts().save1.failed)
        assertNull(f.links.afterExecution(f.handle,"task") { error("Never launched") })
        assertThrows(IllegalArgumentException::class.java) { f.session.beginMethod(f.page.candidateId,SaveEvaluationMethod.SAVE3) }
    }
    @Test fun duplicateArmAndAcceptanceCannotDoubleIssueTicket() {
        val f=Fixture();assertNotNull(f.arm());assertNull(f.arm())
        assertNotNull(f.links.accepted(f.admission,accepted(),false));assertNull(f.links.accepted(f.admission,accepted(),false))
        assertEquals(1,f.session.snapshot().unfinishedMethodAttempts)
    }
    @Test fun boundedArmingRejectsExtraEntriesAndClosedOrUnknownSession() {
        val f=Fixture(capacity=1);assertNotNull(f.arm())
        assertNull(f.links.arm(admission("other"),TaskMethod.DIRECT,f.session,f.page.candidateId))
        f.links.close(f.session);f.session.finish();assertNull(f.arm())
        val fresh=DiscoverySaveEvaluationSession(SaveEvaluationEvidence.SYNTHETIC_FIXTURE)
        assertNull(SaveEvaluationTaskLinks().arm(admission(),TaskMethod.DIRECT,fresh,f.page.candidateId))
    }
    @Test fun unstartedPageCannotGainMethodOutcome() {
        val s=DiscoverySaveEvaluationSession(SaveEvaluationEvidence.SYNTHETIC_FIXTURE);s.begin()
        val c=PendingCandidatePolicy.normalize(CandidateUrl("https://example.invalid/"),"source",SearchFilters())!!;s.register(c)
        val links=SaveEvaluationTaskLinks();val a=admission();links.arm(a,TaskMethod.DIRECT,s,c.candidateId)
        assertNull(links.accepted(a,accepted(),false));assertEquals(0,s.snapshot().evaluation.overall.actualTestPages)
    }
    @Test fun runtimeSeamIsRealDefaultEmptyAndDoesNotControlSaveLifecycle() {
        val source=java.io.File("src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt").readText()
        assertEquals(2,Regex("evaluationSaveLinks\\.accepted\\(admission, result\\.record, result\\.existing\\)").findAll(source).count())
        assertTrue(source.contains("evaluationSaveLinks.afterExecution(evaluationBinding, taskId) { if (executed) coordinator.record(taskId) else null }"))
        assertTrue(source.contains("private val evaluationSaveLinks = SaveEvaluationTaskLinks()"))
        assertFalse(source.contains("if (evaluationSaveLinks"))
    }
    @Test fun replayedExistingOrWrongAcceptanceCannotEraseBoundWorker() {
        val f=Fixture();f.arm();assertNotNull(f.links.accepted(f.admission,accepted(),false))
        assertNull(f.links.accepted(f.admission,accepted(),true))
        assertNull(f.links.accepted(f.admission,accepted(TaskMethod.STREAM),false))
        assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT,f.links.afterExecution(f.handle,"task") { committed() })
        assertEquals(1,f.counts().save1.succeeded)
        assertNull(f.links.afterExecution(f.handle,"task") { error("Already consumed") })
    }

    @Test fun oldCapabilityCannotReadOrConsumeRearmedSameTaskAcrossSessions() {
        for (abandon in listOf(false,true)) {
            val f=Fixture();val old=f.arm()!!;assertSame(old,f.links.accepted(f.admission,accepted(),false))
            if(abandon) f.links.abandon(old) else f.links.close(f.session)
            f.session.finish()
            val g=Fixture();val next=f.links.arm(g.admission,g.method,g.session,g.page.candidateId)!!
            assertSame(next,f.links.accepted(g.admission,accepted().copy(operationId="new-operation"),false))
            assertNull(f.links.afterExecution(old,"task") { error("Old callback must not read new row") })
            f.links.notLaunched(old,"task")
            assertEquals(1,g.session.snapshot().unfinishedMethodAttempts)
            assertEquals(SaveEvaluationTaskResult.VERIFIED_COMMIT,f.links.afterExecution(next,"task") {
                committed().copy(operationId="new-operation")
            })
            assertEquals(1,g.counts().save1.succeeded)
        }
    }
    @Test fun recordingCannotArmOrIssueAnEvaluationTicket() {
        val f=Fixture(TaskMethod.RECORD)
        assertNull(f.arm())
        assertNull(f.links.accepted(f.admission,accepted(),false))
        assertNull(f.links.afterExecution(f.handle,"task") { error("Recording must not be read") })
        assertEquals(0,f.session.snapshot().unfinishedMethodAttempts)
        assertEquals(0,f.counts().save3.tested)
    }
    @Test fun privateDurableAcceptanceIsRejectedWithoutTicketOrFinalRecordRead() {
        for(method in listOf(TaskMethod.DIRECT,TaskMethod.STREAM)) {
            val f=Fixture(method);f.arm()
            val privateSource=PrivateNetworkSourceSeed.create().bindTask("task")
            val resource=SelectedMediaResource("a".repeat(64),"b".repeat(64),SourceUrl("https://private.invalid/media"),
                requestContextRef=RequestContextRef("private-lease"),sourceIdentity=privateSource.sourceIdentity.encoded,
                privateSource=privateSource)
            val privateRequest=SaveRequest("task","key",resource,method).asDurableReference()
            assertTrue(privateRequest.isPrivateNetwork)
            assertNull(f.links.accepted(f.admission,accepted(method).copy(request=privateRequest),false))
            assertNull(f.links.afterExecution(f.handle,"task") { error("Private row must not be read") })
            assertEquals(0,f.session.snapshot().unfinishedMethodAttempts)
            assertEquals(0,f.counts().save1.tested);assertEquals(0,f.counts().save2.tested)
        }
    }
}
