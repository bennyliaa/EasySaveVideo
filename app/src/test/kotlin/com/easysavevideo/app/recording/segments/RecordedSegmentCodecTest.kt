package com.easysavevideo.app.recording.segments

import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test

class RecordedSegmentCodecTest {
    private val geometry = CaptureDisplayGeometry(1080, 1920, 90, CaptureInsets(0, 20, 0, 20))
    private val recipe = RegionCaptureRecipe(RegionNormalizedRect(0.2, 0.1, 0.8, 0.9), geometry, CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY, 1234)
    private val profile = RecordingProfile(recipe.encodedWidth, recipe.encodedHeight)
    private val descriptor = CaptureDescriptor("session", 7, RecordingAudioChoice.NONE, profile, recipe)
    @Test fun nonFullRegionConfigPreservesDescriptorRecipeAndSelectionTime() {
        val value = RecordedCaptureConfig(descriptor, geometry, recipe)
        assertEquals(value, RecordedSegmentCodec.config(RecordedSegmentCodec.config(value)))
    }
    @Test fun actualGeometryObservationSurvivesRestart() {
        val value = CaptureGeometryObservation(geometry, recipe.scopeEvidence, CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK, 4567)
        assertEquals(value, RecordedSegmentCodec.observation(RecordedSegmentCodec.observation(value)))
    }
    @Test fun rawActualTimingAndRegionSurviveRestartWithoutNominalReplacement() {
        val value = RecordingArtifactExpectation(descriptor, profile, RecordingAudioOutcome.NO_AUDIO_REQUESTED, RecordingStopReason.USER_STOP,
            RecordingTrackEvidence(10, 0, 345000, 37000, 1, true), null, 382000, 1000)
        assertEquals(value, RecordedSegmentCodec.capture(RecordedSegmentCodec.capture(value)))
    }
    @Test fun malformedAndUnboundedRecordsRejected() {
        val value = RecordedSegmentCodec.config(RecordedCaptureConfig(descriptor, geometry, recipe))
        for (bytes in listOf(value + 0, value.copyOf(value.size - 1), value.clone().apply { this[3] = 99 }, ByteArray(2049))) {
            try { RecordedSegmentCodec.config(bytes); fail("Expected rejection") } catch (_: Exception) { }
        }
    }
}
