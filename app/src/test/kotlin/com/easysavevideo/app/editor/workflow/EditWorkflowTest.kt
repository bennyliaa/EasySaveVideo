package com.easysavevideo.app.editor.workflow

import com.easysavevideo.core.model.*
import com.easysavevideo.app.editor.*
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.RecordedSegmentReadRef
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.edit.*
import org.junit.Assert.*
import org.junit.Test

class EditWorkflowTest {
    private val source = MediaReadRef("source", 3, 2)
    private val request = EditRequest("operation", listOf(EditSegment(source, 0, 1_000_000)))
    private val output = EditOutputRef("operation", "artifact")
    private val published = MediaReadRef("derived", 1, 1)
    private class Harness {
        val cancellation = EditPublicationCancellation { 1 }
        val context = OperationContext("operation", 10_000, cancellation)
        val gate = EditNativeResourceGate()
        val calls = arrayListOf<String>()
    }
    private inner class Port(val h: Harness) : EditWorkPort {
        var executeAction: () -> Unit = {}
        var publicationAction: () -> Unit = {}
        var admittedAction: () -> Unit = {}
        var corruptProof = false
        var nativeUnknown = false
        var committed = false
        override fun prepare(request: EditRequest): EditOutputRef { h.calls += "prepare"; return output }
        override fun probe(source: MediaReadRef, context: OperationContext) = observed(source)
        override fun probe(source: RecordedSegmentReadRef, context: OperationContext) = observed(source)
        private fun observed(source: EditInputRef): EditSourceMetadata {
            h.calls += "probe"
            return EditSourceMetadata(source, 1_000_000, 320, 240, 0, 30, EditAudioProfile.Silent, 1024,
                avcProfile = EditAvcProfile.BASELINE, color = EditColorProfile.SDR, squarePixels = true,
                constantFrameRate = true, bitDepth = 8,
                frameTimeline = EditFrameTimeline(LongArray(30) { it * 1_000_000L / 30 }, 1_000_000))
        }
        override fun start(output: EditOutputRef, plan: EditPlan) { h.calls += "start" }
        override fun execute(output: EditOutputRef, plan: EditPlan, context: OperationContext) { h.calls += "execute"; executeAction() }
        override fun beginVerification(output: EditOutputRef) { h.calls += "verify-start" }
        override fun verify(output: EditOutputRef, expected: EditOutputExpectation, context: OperationContext): EditVerificationEvidence {
            h.calls += "verify"
            return EditVerificationEvidence(output, expected, 1024, "a".repeat(64), expected.durationUs,
                expected.width, expected.height, 0, "video/avc", null, null, null, 0, 966_666, 30, 0, 30, 0,
                videoOutputEos = !corruptProof, audioOutputEos = false, nativeReleaseConfirmed = true, containerExtentVerified = true)
        }
        override fun publish(output: EditOutputRef, evidence: EditVerificationEvidence, context: OperationContext): MediaReadRef {
            h.calls += "publish"; publicationAction()
            when (h.cancellation.admitPublication(context.deadlineEpochMillis)) {
                EditPublicationAdmission.ADMITTED -> Unit
                EditPublicationAdmission.DEADLINE_EXCEEDED -> throw EditPublicationAdmissionFailure(EditError.TIMEOUT)
                EditPublicationAdmission.CANCELLED -> throw EditPublicationAdmissionFailure(EditError.CANCELLED)
                else -> throw EditFailure(EditError.PUBLICATION_UNCERTAIN)
            }
            admittedAction(); committed = true; h.cancellation.committed(); return published
        }
        override fun publishedReference(output: EditOutputRef): MediaReadRef? { h.calls += "reconcile"; return if (committed) published else null }
        override fun abort(output: EditOutputRef, cancelled: Boolean, error: EditError) { h.calls += "abort:$cancelled" }
        override fun fence(output: EditOutputRef) { h.calls += "fence" }
        override fun nativeReleaseUnconfirmed() = nativeUnknown
    }
    @Test fun completeProductionWorkflowRequiresExecutionVerificationAndPublication() {
        val h = Harness(); val port = Port(h); val stages = arrayListOf<EditWorkStage>()
        val result = EditWorkflow(port, h.gate) { 1 }.run(request, h.context, stages::add)
        assertEquals(EditRunResult.Published(published), result)
        assertEquals(listOf("prepare", "probe", "start", "execute", "verify-start", "verify", "publish"), h.calls)
        assertEquals(EditWorkStage.entries, stages); assertFalse(h.gate.isOccupied)
    }
    @Test fun cancellationAfterConfirmedExecutionNeverPublishesAndCanRunAgain() {
        val h = Harness(); val port = Port(h); port.executeAction = { h.cancellation.cancel() }
        val result = EditWorkflow(port, h.gate) { 1 }.run(request, h.context) {}
        assertTrue(result is EditRunResult.Stopped && result.cancelled && !result.requiresRecovery)
        assertEquals("abort:true", h.calls.last()); assertFalse(h.calls.contains("publish")); assertFalse(h.gate.isOccupied)
        assertTrue(h.gate.claim(Any()))
    }
    @Test fun unconfirmedNativeReleaseRetainsOwnerAndPreventsCleanup() {
        val h = Harness(); val port = Port(h)
        port.executeAction = { port.nativeUnknown = true; throw EditFailure(EditError.RELEASE_UNCONFIRMED) }
        val result = EditWorkflow(port, h.gate) { 1 }.run(request, h.context) {}
        assertTrue(result is EditRunResult.Stopped && result.requiresRecovery)
        assertEquals("fence", h.calls.last()); assertFalse(h.calls.any { it.startsWith("abort") })
        assertTrue(h.gate.isOccupied); assertTrue(h.gate.requiresRestart)
    }
    @Test fun malformedIndependentProofCannotReachPublish() {
        val h = Harness(); val port = Port(h); port.corruptProof = true
        val result = EditWorkflow(port, h.gate) { 1 }.run(request, h.context) {}
        assertTrue(result is EditRunResult.Stopped); assertFalse(h.calls.contains("publish")); assertEquals("abort:false", h.calls.last())
    }
    @Test fun uncertainPublicationIsReconciledWithoutDeletingCandidate() {
        val h = Harness(); val port = Port(h); port.publicationAction = { throw IllegalStateException("SYNTHETIC_COMMIT_UNKNOWN") }
        val result = EditWorkflow(port, h.gate) { 1 }.run(request, h.context) {}
        assertTrue(result is EditRunResult.Stopped && result.requiresRecovery)
        assertEquals("reconcile", h.calls.last()); assertFalse(h.calls.any { it.startsWith("abort") })
    }
    @Test fun commitAcknowledgementLossPreservesConfirmedPublishedResult() {
        val h = Harness(); val port = Port(h); port.publicationAction = { port.committed = true; error("SYNTHETIC_ACK_LOST") }
        val result = EditWorkflow(port, h.gate) { 1 }.run(request, h.context) {}
        assertEquals(EditRunResult.Published(published), result); assertFalse(h.calls.any { it.startsWith("abort") })
    }
    @Test fun finalAdmissionCancellationRejectsWithoutCatalogAndCleansOwnedCandidate() {
        for (recorded in listOf(false, true)) {
            val h = Harness(); val port = Port(h); port.publicationAction = { h.cancellation.cancel() }
            val selectedRequest = if (recorded) EditRequest("operation", listOf(EditSegment(
                RecordedSegmentReadRef("session", "segment", 1, 0), 0, 1_000_000))) else request
            val result = EditWorkflow(port, h.gate) { 1 }.run(selectedRequest, h.context) {}
            assertTrue(result is EditRunResult.Stopped && result.cancelled && !result.requiresRecovery)
            assertFalse(port.committed); assertEquals("abort:true", h.calls.last())
            assertFalse(h.gate.isOccupied)
        }
    }

    @Test fun admittedCommitWinsLateCancelForOrdinaryAndRecordedRecipes() {
        for (recorded in listOf(false, true)) {
            val h = Harness(); val port = Port(h)
            port.admittedAction = { assertEquals(EditCancelResult.TOO_LATE, h.cancellation.cancel()) }
            val selected = if (recorded) EditRequest("operation", listOf(EditSegment(
                RecordedSegmentReadRef("session", "segment", 1, 0), 0, 1_000_000))) else request
            val result = EditWorkflow(port, h.gate) { 1 }.run(selected, h.context) {}
            assertEquals(EditRunResult.Published(published), result); assertFalse(h.cancellation.isCancelled)
            assertTrue(port.committed); assertFalse(h.calls.any { it.startsWith("abort") })
        }
    }
    @Test fun admittedTransactionFailureRemainsUncertainWithoutDiscardOrReuse() {
        val h = Harness(); val port = Port(h); port.admittedAction = { error("SYNTHETIC_COMMIT_FAILURE") }
        val result = EditWorkflow(port, h.gate) { 1 }.run(request, h.context) {}
        assertTrue(result is EditRunResult.Stopped && result.requiresRecovery && !result.cancelled)
        assertFalse(port.committed); assertEquals(EditCancelResult.TOO_LATE, h.cancellation.cancel())
        assertFalse(h.calls.any { it.startsWith("abort") })
    }

    @Test fun cancelledBeforePreparationDoesNotAllocateArtifact() {
        val h = Harness(); h.cancellation.cancel(); val port = Port(h)
        val result = EditWorkflow(port, h.gate) { 1 }.run(request, h.context) {}
        assertTrue(result is EditRunResult.Stopped && result.cancelled); assertTrue(h.calls.isEmpty())
    }
}
