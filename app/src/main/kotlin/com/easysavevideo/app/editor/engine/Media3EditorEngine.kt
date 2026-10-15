package com.easysavevideo.app.editor.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import com.easysavevideo.app.media.transform.*
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.edit.*
import java.security.MessageDigest
import java.util.concurrent.ExecutionException

/** Editor product wrapper: original plan/probe/hash/effects, one shared native mechanism. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
class Media3EditorEngine(context: Context, private val inputs: EditorInputPort) {
    private val mechanism = TransformerEngine(context.applicationContext)
    fun requiresProcessRecovery(): Boolean = SharedTransformerRuntime.laneOwner.fence.requiresRestart

    fun probe(reference: EditInputRef, context: OperationContext): EditSourceMetadata =
        bounded(context, EditLimits.PREPARE_TIMEOUT_MILLIS) { session -> EditorMetadataProbe.inspect(reference, session) }

    fun execute(plan: EditPlan, output: EditorOwnedOutput, context: OperationContext): EditorEngineEvidence =
        bounded(context, EditLimits.EXECUTION_TIMEOUT_MILLIS) { session ->
            if (plan.request.operationId != context.operationId || plan.segments.size !in 1..plan.request.maxSegments) fail(EditError.INVALID_REQUEST)
            output.verifyIdentity()
            if (output.file.length() != 0L || output.availableBytes() < plan.requiredFreeBytes) fail(EditError.INSUFFICIENT_SPACE)
            val distinct = plan.segments.distinctBy { it.segment.source }
            distinct.forEach {
                if (EditorMetadataProbe.inspect(it.segment.source, session) != it.metadata) fail(EditError.STALE_SOURCE)
            }
            val facts = try { mechanism.execute(spec(plan, session), EditorTransformerTarget(output), session.native) }
            catch (_: ExecutionException) { fail(EditError.EXECUTION_FAILED) } // Preserve Editor's existing export-future mapping.
            distinct.forEach { verifyHash(it.segment.source, session) }
            output.verifyIdentity()
            EditorEngineEvidence(facts.bytesWritten, facts.videoSamples, facts.audioSamples)
        }

    private fun spec(plan: EditPlan, session: EditorSession): TransformerRunSpec.EditorAvcAac {
        val items = plan.segments.mapIndexed { index, planned ->
            val metadata = planned.metadata
            val displayWidth = if (metadata.rotationDegrees % 180 == 0) metadata.width else metadata.height
            val displayHeight = if (metadata.rotationDegrees % 180 == 0) metadata.height else metadata.width
            val crop = planned.displayCrop
            val coordinates = EditorEnginePolicy.ndc(crop.left, crop.top, crop.right, crop.bottom, displayWidth, displayHeight)
            val effects: List<Effect> = listOf(Crop(coordinates[0], coordinates[1], coordinates[2], coordinates[3]),
                Presentation.createForWidthAndHeight(plan.expectation.width, plan.expectation.height, Presentation.LAYOUT_SCALE_TO_FIT))
            val media = MediaItem.Builder().setUri(Uri.parse(EditorCatalogDataSource.address(index))).setMimeType(MimeTypes.VIDEO_MP4)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(planned.executionStartUs)
                    .setEndPositionUs(planned.executionEndUsExclusive).setStartsAtKeyFrame(false).build()).build()
            TransformerEditorItem(session.source(planned.segment.source), EditorCatalogDataSource.address(index),
                TransformerInputProfile(metadata.width, metadata.height, metadata.rotationDegrees, audio(metadata.audio)),
                EditedMediaItem.Builder(media).setEffects(Effects(emptyList(), effects)).build())
        }
        val expected = plan.expectation
        return TransformerRunSpec.EditorAvcAac(items, TransformerOutputSpec(expected.width, expected.height,
            expected.frameRate, expected.durationUs, expected.durationToleranceUs, expected.audioPrimingToleranceUs,
            expected.videoFrameCount, audio(expected.audio), expected.maxBytes, plan.requiredFreeBytes,
            plan.videoBitrate, plan.audioBitrate))
    }
    private fun audio(profile: EditAudioProfile): TransformerAudioFormat? = when (profile) {
        EditAudioProfile.Silent -> null
        is EditAudioProfile.Aac -> TransformerAudioFormat(profile.sampleRate, profile.channels)
    }

    private fun verifyHash(reference: EditInputRef, session: EditorSession) {
        val input = session.openInput(reference)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (count < input.sizeBytes) {
                session.checkpoint()
                val read = input.stream.read(buffer, 0, minOf(buffer.size.toLong(), input.sizeBytes - count).toInt())
                if (read <= 0) fail(EditError.STALE_SOURCE)
                digest.update(buffer, 0, read); count += read
            }
            if (input.stream.read() != -1 || digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) } != input.sha256)
                fail(EditError.STALE_SOURCE)
        } finally { session.closeInput(input) }
    }

    private fun <T> bounded(context: OperationContext, timeoutMillis: Long, work: (EditorSession) -> T): T {
        val session = EditorSession(inputs, context, timeoutMillis)
        return when (val outcome = runTransformerSession(session.native) { work(session) }) {
            is TransformerOutcome.Completed -> outcome.value
            is TransformerOutcome.Failed -> {
                if (outcome.release == TransformerRelease.UNCONFIRMED) fail(EditError.RELEASE_UNCONFIRMED)
                throw editorTransformerFailure(outcome.primary)
            }
        }
    }
    private fun fail(code: EditError): Nothing = throw EditFailure(code)
}
