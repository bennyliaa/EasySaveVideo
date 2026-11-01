package com.easysavevideo.app.save

import com.easysavevideo.discovery.*
import com.easysavevideo.core.model.*
import com.easysavevideo.library.destination.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test

class DebugSaveEvaluationOwnerTest {
    private class Fixture {
        var time = 0L
        val owner = DebugSaveEvaluationOwner(true) { time }
        val candidate = PendingCandidatePolicy.normalize(CandidateUrl("https://example.invalid/page"),"source",SearchFilters())!!
        val request=Any();val view=Any()
        init { assertTrue(owner.begin(true)) }
        fun open() = owner.opened(candidate,true,request,view)
        fun ready() { assertTrue(open());owner.documentStarted(request,view);owner.documentFinished(request,view,7) }
        fun lease() = owner.beginPage(request,view,7)
        fun counts()=owner.snapshot()!!.evaluation.overall
    }
    @Test fun operatorDefaultIsJointAndThirdMethodHasNoPrimaryFailurePrerequisite() {
        val f=Fixture(); f.ready(); val lease=requireNotNull(f.lease())
        assertEquals(SaveEvaluationMode.JOINT,f.owner.snapshot()!!.mode)
        lease.session.finishMethod(lease.session.beginMethod(lease.pageId,SaveEvaluationMethod.SAVE3),SaveEvaluationOutcome.SUCCEEDED)
        val counts=f.counts(); assertEquals(1,counts.save3.succeeded); assertEquals(0,counts.fallbackSave3.succeeded)
        assertEquals(1,counts.save1.notTested); assertTrue(counts.partial)
    }
    @Test fun explicitIndependentRecordSkipsPrimariesAndNeverCreatesFallbackSuccess() {
        val owner=DebugSaveEvaluationOwner(true); assertTrue(owner.begin(true,SaveEvaluationMode.INDEPENDENT_RECORD))
        val candidate=PendingCandidatePolicy.normalize(CandidateUrl("https://example.invalid/record"),"source",SearchFilters())!!
        val request=Any(); val view=Any(); assertTrue(owner.opened(candidate,true,request,view))
        owner.documentStarted(request,view); owner.documentFinished(request,view,1)
        val lease=requireNotNull(owner.beginPage(request,view,1))
        lease.session.finishMethod(lease.session.beginMethod(lease.pageId,SaveEvaluationMethod.SAVE3),SaveEvaluationOutcome.SUCCEEDED)
        val counts=owner.snapshot()!!.evaluation.overall
        assertEquals(1,counts.save1.skipped); assertEquals(1,counts.save2.skipped)
        assertEquals(1,counts.independentRecord.succeeded); assertNull(counts.fallbackShare); assertFalse(counts.partial)
    }
    @Test fun releaseCannotBeginRegisterOrCollect() {
        val owner=DebugSaveEvaluationOwner(false);assertFalse(owner.begin(false));assertFalse(owner.active())
        val f=Fixture();assertFalse(owner.opened(f.candidate,true,Any(),Any()));assertNull(owner.snapshot())
    }
    @Test fun newOwnerIsOffAndRepeatedBeginCannotReplaceActiveRun() {
        val owner=DebugSaveEvaluationOwner(true);assertFalse(owner.active());assertNull(owner.snapshot())
        assertTrue(owner.begin(false));assertFalse(owner.begin(true))
        assertEquals(SaveEvaluationEvidence.OBSERVED_SESSION,owner.snapshot()!!.evaluation.evidence)
    }
    @Test fun openingAndFinishingDocumentDoNotStartPageTest() {
        val f=Fixture();f.ready();assertEquals(0,f.counts().actualTestPages);assertEquals(1,f.counts().notStartedPages)
        assertNotNull(f.lease());assertEquals(1,f.counts().actualTestPages);assertTrue(f.counts().partial)
    }
    @Test fun pageBeginRequiresExactlyAcceptedRequestViewAndDocument() {
        val f=Fixture();f.ready()
        assertNull(f.owner.beginPage(Any(),f.view,7));assertNull(f.owner.beginPage(f.request,Any(),7))
        assertNull(f.owner.beginPage(f.request,f.view,8));assertEquals(0,f.counts().actualTestPages)
        assertNotNull(f.lease())
    }
    @Test fun startAndFinishMustBothBeAcceptedBeforeBegin() {
        val f=Fixture();f.open();f.owner.documentFinished(f.request,f.view,7);assertNull(f.lease())
        f.owner.documentStarted(f.request,f.view);assertNull(f.lease())
        f.owner.documentFinished(f.request,f.view,7);assertNotNull(f.lease())
    }
    @Test fun initialRedirectSecondStartConservativelyRevokes() {
        val f=Fixture();f.open();f.owner.documentStarted(f.request,f.view);f.owner.documentStarted(f.request,f.view)
        f.owner.documentFinished(f.request,f.view,7);assertNull(f.lease());assertEquals(0,f.counts().actualTestPages)
    }
    @Test fun secondNavigationAfterReadyRevokesEvenSameRequestOrUrl() {
        val f=Fixture();f.ready();val lease=f.lease()!!
        f.owner.documentStarted(f.request,f.view);assertFalse(lease.current());assertNull(f.lease())
    }
    @Test fun newRequestOnSameViewCannotClaimOldCandidate() {
        val f=Fixture();f.open();f.owner.documentStarted(Any(),f.view)
        f.owner.documentFinished(f.request,f.view,7);assertNull(f.lease())
    }
    @Test fun staleFinishCannotPromoteNewNavigation() {
        val f=Fixture();f.open();val next=Any();assertTrue(f.owner.opened(f.candidate,true,next,f.view))
        f.owner.documentStarted(next,f.view);f.owner.documentFinished(f.request,f.view,7)
        assertNull(f.owner.beginPage(next,f.view,7));f.owner.documentFinished(next,f.view,9)
        assertNotNull(f.owner.beginPage(next,f.view,9));assertNull(f.lease())
    }
    @Test fun documentInvalidationRetainsOnlyPendingInitialStart() {
        val f=Fixture();f.open();f.owner.documentStarted(f.request,f.view);f.owner.documentInvalidated(f.view)
        f.owner.documentFinished(f.request,f.view,7);val lease=f.lease()!!
        f.owner.documentInvalidated(f.view);assertFalse(lease.current())
    }
    @Test fun removedViewRevokesPendingAndReadyAuthority() {
        for(ready in listOf(false,true)) {
            val f=Fixture();if(ready) f.ready() else f.open()
            f.owner.viewRemoved(f.view);f.owner.documentStarted(f.request,f.view);f.owner.documentFinished(f.request,f.view,7)
            assertNull(f.lease())
        }
    }
    @Test fun unrelatedViewCannotConsumePendingNavigation() {
        val f=Fixture();f.open();f.owner.documentStarted(Any(),Any());f.owner.viewRemoved(Any())
        f.owner.documentStarted(f.request,f.view);f.owner.documentFinished(f.request,f.view,7);assertNotNull(f.lease())
    }
    @Test fun navigationPauseCloseAndTabReturnCannotReviveLease() {
        val f=Fixture();f.ready();val lease=f.lease()!!;f.owner.invalidate()
        f.owner.documentFinished(f.request,f.view,7);assertFalse(lease.current());assertNull(f.lease())
    }
    @Test fun monotonicDeadlineExpiresPendingAndReadyLeases() {
        val f=Fixture();f.ready();val lease=f.lease()!!;f.time=119999;assertTrue(lease.current())
        f.time=120000;assertFalse(lease.current());assertNull(f.lease())
        val g=Fixture();g.open();g.time=120000;g.owner.documentStarted(g.request,g.view);g.owner.documentFinished(g.request,g.view,7);assertNull(g.lease())
    }
    @Test fun fixtureAndObservedCannotMixAndWrongEvidenceRevokesPriorPage() {
        val f=Fixture();f.ready();val lease=f.lease()!!
        assertFalse(f.owner.opened(f.candidate,false,Any(),Any()));assertFalse(lease.current())
        assertEquals(SaveEvaluationEvidence.SYNTHETIC_FIXTURE,f.owner.snapshot()!!.evaluation.evidence)
    }
    @Test fun repeatedClickIsIdempotentForPageAndLease() {
        val f=Fixture();f.ready();val lease=f.lease();assertSame(lease,f.lease());assertEquals(1,f.counts().actualTestPages)
    }
    @Test fun samePageMultipleProvidersMergeWithoutDoubleDenominator() {
        val f=Fixture();f.ready();f.lease();f.owner.invalidate()
        assertTrue(f.owner.opened(f.candidate.copy(provenance=setOf("other")),true,f.request,f.view))
        f.owner.documentStarted(f.request,f.view);f.owner.documentFinished(f.request,f.view,7);assertNotNull(f.lease())
        assertEquals(1,f.counts().actualTestPages);assertEquals(setOf("source","other"),f.owner.snapshot()!!.evaluation.byProvider.keys)
    }
    @Test fun finishSealsPartialAndLaterSessionCannotReviveOldLease() {
        val f=Fixture();f.ready();val old=f.lease()!!
        val sealed=f.owner.finish { it.finish() }!!;assertTrue(sealed.evaluation.overall.partial);assertFalse(old.current())
        assertSame(sealed,f.owner.finish { error("Already finished") });assertTrue(f.owner.begin(true));assertFalse(old.current())
        assertEquals(0,f.owner.snapshot()!!.evaluation.overall.actualTestPages)
    }
    private fun accepted()=SaveRecord(SaveRequest("task","key",SelectedMediaResource("candidate","variant",SourceUrl("https://example.invalid/media")).asDurableReference()),
        TaskSnapshot("task",0,TaskState.QUEUED,TaskMethod.DIRECT),"commit","operation",10000,0)
    @Test fun navigationDuringQueuedAdmissionRejectsTicketWithoutReadingFinalRecord() {
        val f=Fixture();f.ready();val lease=f.lease()!!;val links=SaveEvaluationTaskLinks()
        val a=OrdinaryDestinationAdmission("task",OrdinaryDestinationChoice.APP_LIBRARY,"op","grant")
        val binding=links.arm(a,TaskMethod.DIRECT,lease.session,lease.pageId,lease::current)!!
        f.owner.invalidate();assertNull(links.accepted(a,accepted(),false))
        assertNull(links.afterExecution(binding,"task") { error("Must not read") })
        assertEquals(0,f.owner.snapshot()!!.unfinishedMethodAttempts)
    }
    @Test fun alreadyAcceptedTaskKeepsAttributionAfterDocumentRevocation() {
        val f=Fixture();f.ready();val lease=f.lease()!!;val links=SaveEvaluationTaskLinks()
        val a=OrdinaryDestinationAdmission("task",OrdinaryDestinationChoice.APP_LIBRARY,"op","grant")
        val binding=links.arm(a,TaskMethod.DIRECT,lease.session,lease.pageId,lease::current)!!
        assertSame(binding,links.accepted(a,accepted(),false));f.owner.invalidate()
        assertEquals(SaveEvaluationTaskResult.DEFINITIVE_FAILURE,links.afterExecution(binding,"task") {
            accepted().copy(task=TaskSnapshot("task",2,TaskState.FAILED,TaskMethod.DIRECT),phase=SavePhase.VERIFYING,error=SaveError.INVALID_MEDIA)
        });assertEquals(1,f.counts().save1.failed);assertEquals(0,f.counts().save3.tested)
    }
    @Test fun productionSeamsPreserveExactSelectionAndReleaseHasNoControls() {
        fun source(path:String)=java.io.File("src/$path").readText()
        val runtime=source("main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt")
        assertTrue(runtime.contains("result.selection.evaluationLease = evaluationLease"))
        assertTrue(runtime.contains("armSelectionEvaluation(captured, admission, TaskMethod.DIRECT)"))
        assertTrue(runtime.contains("armSelectionEvaluation(captured, admission, TaskMethod.STREAM)"))
        assertTrue(runtime.contains("lease.session, lease.pageId, lease::current"))
        val browser=source("main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt")
        assertTrue(browser.indexOf("evaluationAdmission(entry.view)") < browser.indexOf("entry.view.loadUrl(address.value)"))
        assertTrue(browser.contains("evaluationOwner?.documentStarted(current(entry), view)"))
        assertTrue(browser.contains("evaluationOwner?.documentFinished(request, view, entry.inspectionDocument.revision)"))
        val release=source("release/kotlin/com/easysavevideo/app/ui/SaveEvaluationPanel.kt")
        assertFalse(release.contains("EsvButton"));assertFalse(release.contains("owner.begin"))
    }
    @Test fun olderFinishingRunCannotOverwriteNewerSealedReport() {
        val f=Fixture();f.ready();f.lease()
        val old=f.owner.finish { run ->
            assertTrue(f.owner.begin(false))
            f.owner.finish { it.finish() }
            run.finish()
        }!!
        assertEquals(SaveEvaluationEvidence.SYNTHETIC_FIXTURE,old.evaluation.evidence)
        assertEquals(SaveEvaluationEvidence.OBSERVED_SESSION,f.owner.snapshot()!!.evaluation.evidence)
    }
    @Test fun redirectAndRejectedQueuesHaveExplicitRevocationSeams() {
        val browser=java.io.File("src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt").readText()
        assertTrue(browser.contains("if (request.isRedirect) { discoveryOrigins.invalidate(entry.context); evaluationOwner?.viewRemoved(view) }"))
        val runtime=java.io.File("src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt").readText()
        assertEquals(4,Regex("evaluationArm\\?\\.let\\(::abandonSaveEvaluation\\)").findAll(runtime).count())
        val stream=runtime.substringAfter("private fun chooseHlsVariantSelected(").substringBefore("fun cancel(")
        assertTrue(stream.indexOf("stream.select(prepared, identity, binding)")<stream.indexOf("val evaluationArm"))
    }
    @Test fun expiryPruningClearsViewOwnerAndLeaseKeepsOnlyOpaqueIdentity() {
        val f=Fixture();f.ready();val lease=f.lease()!!
        assertEquals(Any::class.java,lease.navigationIdentity.javaClass)
        f.time=120000;f.owner.prune()
        val field=DebugSaveEvaluationOwner::class.java.getDeclaredField("navigation").apply { isAccessible=true }
        assertNull(field.get(f.owner));assertFalse(lease.current())
    }
}
