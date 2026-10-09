package com.easysavevideo.app.recording.segments

import android.content.Context
import com.easysavevideo.app.editor.engine.*
import com.easysavevideo.app.editor.verification.AndroidEditOutputVerifier
import com.easysavevideo.app.editor.workflow.*
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.RecordedSegmentReadRef
import com.easysavevideo.library.edit.*
import com.easysavevideo.recording.RecordingSessionId

/**
 * Parent owns admission, foreground/entitlement policy, the global native lane and cancellation.
 * Temporary sources never enter Save or Library. No fallback publishes uncropped/unmerged raws.
 */
internal class RecordedSegmentEditorBridge(context: Context, private val storage: AndroidSaveStorage) {
    private val segments get() = storage.recordedSegments
    private val engine = Media3EditorEngine(context, object : EditorInputPort {
        override fun open(reference: MediaReadRef) = throw EditFailure(EditError.INVALID_REQUEST)
        override fun retainNativeReader(reference: MediaReadRef) = throw EditFailure(EditError.INVALID_REQUEST)
        override fun openRecorded(reference: RecordedSegmentReadRef) = segments.openSource(reference)
        override fun retainRecorded(reference: RecordedSegmentReadRef) = segments.retainSource(reference)
    })
    private val rawVerifier = AndroidEditOutputVerifier(segments::openVerification)
    private val resultVerifier = AndroidEditOutputVerifier(storage.edits::openVerification)
    private val nativeGate = EditNativeResourceGate()
    fun requiresProcessRecovery(): Boolean = engine.requiresProcessRecovery() || rawVerifier.requiresProcessRecovery() ||
        resultVerifier.requiresProcessRecovery() || segments.hasUnconfirmedOwners() || storage.edits.hasUnconfirmedOwners()

    fun verifyAndPublish(session: RecordingSessionId, context: OperationContext, crop: NormalizedCrop = NormalizedCrop.FULL,
        compression: EditCompression = EditCompression.ORIGINAL_QUALITY, stage: (EditWorkStage) -> Unit = {}): EditRunResult {
        try {
            segments.snapshot(session).publishedReference?.let { return EditRunResult.Published(it) }
            stage(EditWorkStage.VERIFYING)
            for (index in segments.candidates(session)) {
                val target = segments.verificationTarget(session, index)
                val proof = rawVerifier.verify(target.first, target.second, context.copy(operationId = target.first.operationId))
                segments.confirmVerified(session, index, proof, context)
            }
            val sources = segments.verifiedSources(session)
            val request = EditRequest(context.operationId, sources.map { EditSegment(it, 0, segments.sourceDuration(it), crop) }, compression)
            segments.processing(session, context.operationId)
            val port = object : EditWorkPort {
                override fun prepare(request: EditRequest) = storage.edits.prepare(request)
                override fun probe(source: MediaReadRef, context: OperationContext) = throw EditFailure(EditError.INVALID_REQUEST)
                override fun probe(source: RecordedSegmentReadRef, context: OperationContext) = engine.probe(source, context)
                override fun start(output: EditOutputRef, plan: EditPlan) = storage.edits.start(output, plan)
                override fun execute(output: EditOutputRef, plan: EditPlan, context: OperationContext) { engine.execute(plan, storage.edits.engineOutput(output), context) }
                override fun beginVerification(output: EditOutputRef) = storage.edits.beginVerification(output)
                override fun verify(output: EditOutputRef, expected: EditOutputExpectation, context: OperationContext) = resultVerifier.verify(output, expected, context)
                override fun publish(output: EditOutputRef, evidence: EditVerificationEvidence, context: OperationContext) = storage.edits.publish(output, evidence, context)
                override fun publishedReference(output: EditOutputRef) = storage.edits.publishedReference(output)
                override fun abort(output: EditOutputRef, cancelled: Boolean, error: EditError) = storage.edits.finishUnpublished(output, cancelled, error)
                override fun fence(output: EditOutputRef) = storage.edits.fence(output)
                override fun nativeReleaseUnconfirmed() = requiresProcessRecovery()
            }
            val result = EditWorkflow(port, nativeGate).run(request, context, stage)
            when (result) {
                is EditRunResult.Published -> segments.published(session, request.operationId)
                is EditRunResult.Stopped -> if (result.requiresRecovery) segments.fence(session) else segments.retainAfterFailure(session)
            }
            return result
        } catch (failure: Exception) {
            val committed = runCatching { segments.snapshot(session).publishedReference }.getOrNull()
            if (committed != null) return EditRunResult.Published(committed)
            val unknown = requiresProcessRecovery()
            if (unknown) segments.fence(session) else segments.retainAfterFailure(session)
            return EditRunResult.Stopped(if (unknown) EditError.RELEASE_UNCONFIRMED else (failure as? EditFailure)?.code ?: EditError.VERIFICATION_FAILED,
                context.cancelToken.isCancelled, unknown)
        }
    }
}
