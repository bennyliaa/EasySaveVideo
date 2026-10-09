package com.easysavevideo.library.edit

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** No paths, native codecs, catalog mutations, or entitlement changes in planning. */
object EditPlanner {
    fun plan(request: EditRequest, metadata: List<EditSourceMetadata>): EditPlan {
        if (metadata.isEmpty() || metadata.size > request.maxSegments) throw EditFailure(EditError.SOURCE_LIMIT)
        // The exact ref, not itemId alone, is the lookup key. Conflicting/redundant metadata fails.
        val byRef = metadata.associateBy { it.ref }
        if (byRef.size != metadata.size) throw EditFailure(EditError.INVALID_REQUEST)
        val requestedRefs = request.segments.map { it.source }.toSet()
        if (byRef.keys != requestedRefs) throw EditFailure(EditError.STALE_SOURCE)
        metadata.forEach(::validateProfile)
        if (metadata.sumOf { it.bytes } > EditLimits.MAX_TOTAL_SOURCE_BYTES) throw EditFailure(EditError.SOURCE_LIMIT)
        var timelineUs = 0L
        var outputFrames = 0L
        var previousOutputPts = -1L
        var lastOutputPts = -1L
        val planned = request.segments.map { segment ->
            val source = byRef.getValue(segment.source)
            if (segment.endUsExclusive > source.durationUs) throw EditFailure(EditError.INVALID_REQUEST)
            val sourceTimeline = checkNotNull(source.frameTimeline)
            val trim = sourceTimeline.snapInward(segment.startUs, segment.endUsExclusive)
            // The admitted muxer needs preceding sample durations to give the tail a real extent.
            if (trim.frameCount < 3) throw EditFailure(EditError.INVALID_REQUEST)
            // Joining exact sample boundaries preserves CFR only if accumulated clock rounding
            // remains bounded. Reject a sequence that would produce an output we cannot re-edit.
            for (frame in 0 until trim.frameCount) {
                val pts = timelineUs + sourceTimeline.frameTimeUs(trim.firstFrameIndex + frame) - trim.startUs
                val nominal = (outputFrames + frame) * 1_000_000L / source.frameRate
                if (abs(pts - nominal) > EditLimits.MAX_PLANNED_FRAME_TIMESTAMP_DRIFT_US ||
                    (lastOutputPts >= 0 && abs((pts - lastOutputPts) - 1_000_000.0 / source.frameRate) >
                        EditLimits.MAX_PLANNED_FRAME_TIMESTAMP_DRIFT_US)) {
                    throw EditFailure(EditError.INCOMPATIBLE_SEGMENTS)
                }
                previousOutputPts = lastOutputPts
                lastOutputPts = pts
            }
            val display = EditGeometry.displayCrop(segment.crop, source.width, source.height, source.rotationDegrees)
            PlannedEditSegment(segment, source, display,
                EditGeometry.toSource(display, source.width, source.height, source.rotationDegrees), timelineUs,
                trim.startUs, trim.endUsExclusive, trim.frameCount)
                .also { timelineUs += trim.durationUs; outputFrames += trim.frameCount }
        }
        val first = planned.first()
        val nominalEndUs = outputFrames * 1_000_000L / first.metadata.frameRate
        // The admitted muxer duplicates the preceding frame interval for its last sample. Check
        // that actual behavior as well as the source-observed endpoint, including clock reserve.
        val predictedMuxerEndUs = lastOutputPts + (lastOutputPts - previousOutputPts)
        if (abs(timelineUs - nominalEndUs) > EditLimits.MAX_PLANNED_FRAME_TIMESTAMP_DRIFT_US ||
            abs(predictedMuxerEndUs - nominalEndUs) > EditLimits.MAX_PLANNED_FRAME_TIMESTAMP_DRIFT_US) {
            throw EditFailure(EditError.INCOMPATIBLE_SEGMENTS)
        }
        if (planned.any {
                it.metadata.frameRate != first.metadata.frameRate || it.metadata.audio != first.metadata.audio ||
                    it.displayCrop.width.toLong() * first.displayCrop.height !=
                    first.displayCrop.width.toLong() * it.displayCrop.height
            }) throw EditFailure(EditError.INCOMPATIBLE_SEGMENTS)
        // A merge normalizes to the smallest selected crop. No segment is upscaled.
        val smallest = planned.minBy { it.displayCrop.width.toLong() * it.displayCrop.height }.displayCrop
        val scale = if (request.compression == EditCompression.SAVE_SPACE) {
            min(1.0, EditLimits.SAVE_SPACE_MAX_DIMENSION.toDouble() / max(smallest.width, smallest.height))
        } else 1.0
        val width = (floor(smallest.width * scale / 2) * 2).toInt().coerceAtLeast(2)
        val height = (floor(smallest.height * scale / 2) * 2).toInt().coerceAtLeast(2)
        val frameRate = first.metadata.frameRate
        val frameToleranceUs = (1_000_000L + frameRate - 1) / frameRate
        val priming = when (val audio = first.metadata.audio) {
            EditAudioProfile.Silent -> 0L
            is EditAudioProfile.Aac -> (2_048_000_000L + audio.sampleRate - 1) / audio.sampleRate * planned.size
        }
        val expectation = EditOutputExpectation(timelineUs, width, height, frameRate, first.metadata.audio,
            frameToleranceUs * planned.size, priming, videoFrameCount = outputFrames,
            recordedSegmentCount = if (request.recordedInputs) planned.size else 0)
        val qualityFactor = if (request.compression == EditCompression.ORIGINAL_QUALITY) 0.16 else 0.07
        val videoBitrate = (width.toLong() * height * frameRate * qualityFactor).toInt().coerceIn(256_000, 20_000_000)
        val audioBitrate = when (val audio = first.metadata.audio) {
            EditAudioProfile.Silent -> 0
            is EditAudioProfile.Aac -> if (audio.channels == 1) 96_000 else 192_000
        }
        // Conservative preflight allowance plus bounded runtime output enforcement. Rate-control
        // output may differ: this is a storage requirement, never a promised final file size.
        val estimatedBytes = ((videoBitrate.toLong() + audioBitrate) * timelineUs / 8_000_000L)
        val reserveBytes = 32L * 1024 * 1024
        val requiredFreeBytes = min(EditLimits.MAX_OUTPUT_BYTES, estimatedBytes * 2 + reserveBytes)
        return EditPlan(request, planned, expectation, videoBitrate, audioBitrate, requiredFreeBytes)
    }

    private fun validateProfile(source: EditSourceMetadata) {
        if (source.bytes !in 1..EditLimits.MAX_SOURCE_BYTES) throw EditFailure(EditError.SOURCE_LIMIT)
        if (source.durationUs !in 1..EditLimits.MAX_SOURCE_DURATION_US) throw EditFailure(EditError.DURATION_LIMIT)
        if (source.width !in 2..EditLimits.MAX_DIMENSION || source.height !in 2..EditLimits.MAX_DIMENSION ||
            source.width.toLong() * source.height > EditLimits.MAX_PIXELS) throw EditFailure(EditError.PIXEL_LIMIT)
        if (source.width % 2 != 0 || source.height % 2 != 0 || source.rotationDegrees !in setOf(0, 90, 180, 270) ||
            source.frameRate !in 1..EditLimits.MAX_FRAME_RATE || !source.constantFrameRate || !source.squarePixels ||
            source.bitDepth != 8 || source.videoCodec != EditVideoCodec.AVC ||
            source.avcProfile == EditAvcProfile.UNSUPPORTED || source.color != EditColorProfile.SDR ||
            source.frameTimeline == null || source.frameTimeline.endUsExclusive != source.durationUs ||
            !source.frameTimeline.matchesFrameRate(source.frameRate)) {
            throw EditFailure(EditError.UNSUPPORTED_PROFILE)
        }
    }
}
