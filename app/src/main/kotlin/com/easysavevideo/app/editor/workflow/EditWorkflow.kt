package com.easysavevideo.app.editor.workflow

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.app.editor.EditPublicationAdmissionFailure
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.RecordedSegmentReadRef
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.edit.*

internal enum class EditWorkStage { PREPARING, PROBING, PROCESSING, VERIFYING, PUBLISHING }
internal sealed class EditRunResult {
    data class Published(val reference: MediaReadRef) : EditRunResult()
    data class Stopped(val error: EditError, val cancelled: Boolean, val requiresRecovery: Boolean) : EditRunResult()
}

/** Platform implementation owns the actual native/filesystem operations. No source-delete port. */
internal interface EditWorkPort {
    fun prepare(request: EditRequest): EditOutputRef
    fun probe(source: MediaReadRef, context: OperationContext): EditSourceMetadata
    fun probe(source: RecordedSegmentReadRef, context: OperationContext): EditSourceMetadata = throw EditFailure(EditError.INVALID_REQUEST)
    fun probe(source: EditInputRef, context: OperationContext): EditSourceMetadata = when (source) {
        is MediaReadRef -> probe(source, context)
        is RecordedSegmentReadRef -> probe(source, context)
    }
    fun start(output: EditOutputRef, plan: EditPlan)
    fun execute(output: EditOutputRef, plan: EditPlan, context: OperationContext)
    fun beginVerification(output: EditOutputRef)
    fun verify(output: EditOutputRef, expected: EditOutputExpectation, context: OperationContext): EditVerificationEvidence
    fun publish(output: EditOutputRef, evidence: EditVerificationEvidence, context: OperationContext): MediaReadRef
    fun publishedReference(output: EditOutputRef): MediaReadRef?
    fun abort(output: EditOutputRef, cancelled: Boolean, error: EditError)
    fun fence(output: EditOutputRef)
    fun nativeReleaseUnconfirmed(): Boolean
}

/** Production stage ordering, cancellation and publication fences, tested without native codecs. */
internal class EditWorkflow(private val port: EditWorkPort, private val nativeGate: EditNativeResourceGate,
    private val now: () -> Long = System::currentTimeMillis) {
    fun run(request: EditRequest, context: OperationContext, stage: (EditWorkStage) -> Unit): EditRunResult {
        var output: EditOutputRef? = null
        var lifecycle: EditLifecycle? = null
        var nativeOwner: Any? = null
        var verifying = false
        var publishing = false
        try {
            checkContext(context); stage(EditWorkStage.PREPARING)
            output = port.prepare(request)
            stage(EditWorkStage.PROBING)
            val sources = request.segments.map { it.source }.distinct().map { source -> checkContext(context); port.probe(source, context) }
            checkContext(context)
            val plan = EditPlanner.plan(request, sources)
            lifecycle = EditLifecycle(plan, nativeGate)
            port.start(output, plan)
            val execution = Any(); nativeOwner = execution
            if (!lifecycle.beginExecution(execution, output)) throw EditFailure(EditError.CAPACITY)
            stage(EditWorkStage.PROCESSING)
            port.execute(output, plan, context)
            if (port.nativeReleaseUnconfirmed()) throw EditFailure(EditError.RELEASE_UNCONFIRMED)
            lifecycle.executionFinished(execution, true, EditRelease.RELEASED); nativeOwner = null
            checkContext(context)
            port.beginVerification(output)
            val verifier = Any(); nativeOwner = verifier; verifying = true
            if (!lifecycle.beginVerification(verifier)) throw EditFailure(EditError.CAPACITY)
            stage(EditWorkStage.VERIFYING)
            val evidence = port.verify(output, plan.expectation, context)
            if (port.nativeReleaseUnconfirmed()) throw EditFailure(EditError.RELEASE_UNCONFIRMED)
            lifecycle.verificationFinished(verifier, evidence, EditRelease.RELEASED); nativeOwner = null
            checkContext(context)
            val proof = lifecycle.beginPublication()
            publishing = true
            stage(EditWorkStage.PUBLISHING)
            val reference = port.publish(output, proof, context)
            check(lifecycle.publicationSucceeded())
            return EditRunResult.Published(reference)
        } catch (failure: Exception) {
            val error = (failure as? EditPublicationAdmissionFailure)?.error ?: (failure as? EditFailure)?.code ?: EditError.EXECUTION_FAILED
            val cancelled = context.cancelToken.isCancelled || error == EditError.CANCELLED
            val releaseUnknown = port.nativeReleaseUnconfirmed() || error == EditError.RELEASE_UNCONFIRMED
            if (failure is EditPublicationAdmissionFailure && publishing) {
                lifecycle?.publicationAdmissionRejected(cancelled, error); publishing = false
            }
            if (cancelled) lifecycle?.requestCancel()
            nativeOwner?.let { owner ->
                if (verifying) lifecycle?.verificationFinished(owner, null, if (releaseUnknown) EditRelease.UNCONFIRMED else EditRelease.RELEASED)
                else lifecycle?.executionFinished(owner, false, if (releaseUnknown) EditRelease.UNCONFIRMED else EditRelease.RELEASED)
            }
            if (!cancelled) lifecycle?.fail(error)
            if (publishing) {
                lifecycle?.publicationUncertain()
                val committed = output?.let { runCatching { port.publishedReference(it) }.getOrNull() }
                if (committed != null && !releaseUnknown) return EditRunResult.Published(committed)
                return EditRunResult.Stopped(EditError.PUBLICATION_UNCERTAIN, cancelled, true)
            }
            if (releaseUnknown) {
                output?.let { runCatching { port.fence(it) } }
                return EditRunResult.Stopped(EditError.RELEASE_UNCONFIRMED, cancelled, true)
            }
            if (output != null) {
                try { port.abort(output, cancelled, error); lifecycle?.cleanupConfirmed() }
                catch (_: Exception) { return EditRunResult.Stopped(EditError.PUBLICATION_UNCERTAIN, cancelled, true) }
            }
            return EditRunResult.Stopped(error, cancelled, false)
        }
    }
    private fun checkContext(context: OperationContext) {
        if (context.cancelToken.isCancelled) throw EditFailure(EditError.CANCELLED)
        if (now() >= context.deadlineEpochMillis) throw EditFailure(EditError.TIMEOUT)
    }
}
