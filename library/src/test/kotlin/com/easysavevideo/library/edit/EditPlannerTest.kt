package com.easysavevideo.library.edit

import com.easysavevideo.library.LibraryFailure
import com.easysavevideo.library.MediaReadRef
import org.junit.Assert.*
import org.junit.Test

class EditPlannerTest {
    private val ref = EditFixtures.ref
    private val segment = EditSegment(ref, 1_000_000, 3_000_000)
    private val source = EditFixtures.source()
    private fun plan(source: EditSourceMetadata = this.source) =
        EditPlanner.plan(EditRequest("operation", listOf(segment)), listOf(source))

    @Test fun idsRejectPathsUrlsAndOversizedLocators() {
        for (bad in listOf("", "../file", "content://item", "https://host/x", "a".repeat(129))) {
            assertThrows(LibraryFailure::class.java) { EditRequest(bad, listOf(segment)) }
            assertThrows(LibraryFailure::class.java) { EditOutputRef("operation", bad) }
        }
    }

    @Test fun requestDefensivelyCopiesOrderedInputsAndRejectsMutation() {
        val inputs = mutableListOf(segment)
        val request = EditRequest("operation", inputs)
        val fingerprint = request.fingerprint()
        inputs.clear()
        assertEquals(1, request.segments.size); assertEquals(fingerprint, request.fingerprint())
        assertThrows(UnsupportedOperationException::class.java) { (request.segments as MutableList<*>).clear() }
    }

    @Test fun fingerprintBindsRevisionGenerationOrderBoundariesCropAndCompression() {
        val second = segment.copy(startUs = 4_000_000, endUsExclusive = 6_000_000)
        val base = EditRequest("operation", listOf(segment, second))
        val fingerprint = base.fingerprint()
        assertEquals(fingerprint, EditRequest("operation", listOf(segment, second)).fingerprint())
        for (change in listOf(
            EditRequest("next", base.segments),
            EditRequest("operation", base.segments, EditCompression.SAVE_SPACE),
            EditRequest("operation", listOf(second, segment)),
            EditRequest("operation", listOf(segment.copy(source = ref.copy(revision = 2)), second)),
            EditRequest("operation", listOf(segment.copy(source = ref.copy(representationGeneration = 2)), second)),
            EditRequest("operation", listOf(segment.copy(startUs = 1_100_000), second)),
            EditRequest("operation", listOf(segment.copy(endUsExclusive = 2_900_000), second)),
            EditRequest("operation", listOf(segment.copy(crop = NormalizedCrop(0.1, 0.0, 1.0, 1.0)), second)),
        )) assertNotEquals(fingerprint, change.fingerprint())
    }

    @Test fun negativeZeroCropHasCanonicalFingerprint() {
        val negative = segment.copy(crop = NormalizedCrop(-0.0, -0.0, 1.0, 1.0))
        assertEquals(EditRequest("operation", listOf(segment)).fingerprint(), EditRequest("operation", listOf(negative)).fingerprint())
    }

    @Test fun segmentRangeAndCountAreBoundedBeforeSumming() {
        for ((start, end) in listOf(-1L to 1_000_000L, 0L to 0L, 1_000_000L to 1L,
            0L to 249_999L, 0L to Long.MAX_VALUE)) {
            assertThrows(EditFailure::class.java) { EditSegment(ref, start, end) }
        }
        assertThrows(EditFailure::class.java) { EditRequest("operation", emptyList()) }
        assertThrows(EditFailure::class.java) { EditRequest("operation", List(9) { segment }) }
        val long = EditSegment(ref, 0, EditLimits.MAX_OUTPUT_DURATION_US)
        assertThrows(EditFailure::class.java) { EditRequest("operation", listOf(long, long)) }
        EditRequest("operation", List(EditLimits.MAX_SEGMENTS) { segment })
    }

    @Test fun trimAndRepeatedSegmentsKeepExactCallerOrderAndContinuousOutputTimeline() {
        val later = segment.copy(startUs = 6_000_000, endUsExclusive = 9_000_000)
        val request = EditRequest("operation", listOf(later, segment, later))
        val plan = EditPlanner.plan(request, listOf(source))
        assertEquals(listOf(6_000_000L, 1_000_000L, 6_000_000L), plan.segments.map { it.segment.startUs })
        assertEquals(listOf(0L, 3_000_000L, 5_000_000L), plan.segments.map { it.outputStartUs })
        assertEquals(listOf(3_000_000L, 5_000_000L, 8_000_000L), plan.segments.map { it.outputEndUsExclusive })
        assertEquals(8_000_000L, plan.expectation.durationUs)
        assertTrue(plan.retainsOriginals); assertTrue(plan.requiresTranscode)
    }

    @Test fun exactCatalogIdentityRequiredAndUnrequestedMetadataRejected() {
        for (changed in listOf(source.copy(ref = ref.copy(revision = 2)),
            source.copy(ref = ref.copy(representationGeneration = 2)),
            source.copy(ref = MediaReadRef("other", 1, 1)))) {
            assertEquals(EditError.STALE_SOURCE, assertThrows(EditFailure::class.java) { plan(changed) }.code)
        }
        assertThrows(EditFailure::class.java) { EditPlanner.plan(EditFixtures.request(), listOf(source, source)) }
        assertThrows(EditFailure::class.java) { EditPlanner.plan(EditFixtures.request(), emptyList()) }
        assertThrows(EditFailure::class.java) { EditPlanner.plan(EditFixtures.request(),
            listOf(source, source.copy(ref = MediaReadRef("unused", 1, 1)))) }
    }

    @Test fun unknownHdrVfrNonSquareOddAndUnusualProfilesFailClosed() {
        val unsupported = listOf(source.copy(videoCodec = EditVideoCodec.UNSUPPORTED),
            source.copy(avcProfile = EditAvcProfile.UNSUPPORTED), source.copy(color = EditColorProfile.UNKNOWN),
            source.copy(color = EditColorProfile.HDR), source.copy(constantFrameRate = false),
            source.copy(squarePixels = false), source.copy(bitDepth = 10), source.copy(width = 639),
            source.copy(height = 359), source.copy(rotationDegrees = 45), source.copy(frameRate = 0),
            source.copy(frameRate = 61), source.copy(frameTimeline = null))
        for (candidate in unsupported) assertEquals(EditError.UNSUPPORTED_PROFILE,
            assertThrows(EditFailure::class.java) { plan(candidate) }.code)
        for (profile in listOf(EditAvcProfile.BASELINE, EditAvcProfile.MAIN, EditAvcProfile.HIGH)) {
            plan(source.copy(avcProfile = profile))
        }
    }

    @Test fun sourceByteDurationAndPixelBudgetsRejectOverflowCandidates() {
        for (bytes in listOf(-1L, 0L, EditLimits.MAX_SOURCE_BYTES + 1, Long.MAX_VALUE)) {
            assertEquals(EditError.SOURCE_LIMIT, assertThrows(EditFailure::class.java) { plan(source.copy(bytes = bytes)) }.code)
        }
        for (duration in listOf(-1L, 0L, EditLimits.MAX_SOURCE_DURATION_US + 1, Long.MAX_VALUE)) {
            assertEquals(EditError.DURATION_LIMIT, assertThrows(EditFailure::class.java) { plan(source.copy(durationUs = duration)) }.code)
        }
        for ((width, height) in listOf(3840 to 2160, 1920 to 1920, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertEquals(EditError.PIXEL_LIMIT, assertThrows(EditFailure::class.java) { plan(source.copy(width = width, height = height)) }.code)
        }
        assertThrows(EditFailure::class.java) { plan(source.copy(durationUs = 2_000_000)) }
    }

    @Test fun totalSourceBytesCountUniqueInputsRatherThanRepeatedSegments() {
        val large = source.copy(bytes = EditLimits.MAX_SOURCE_BYTES)
        EditPlanner.plan(EditRequest("operation", List(8) { segment }), listOf(large))
        val sources = List(3) { index -> large.copy(ref = MediaReadRef("source_$index", 1, 1)) }
        val request = EditRequest("operation", sources.map { EditSegment(it.ref, 0, 1_000_000) })
        assertEquals(EditError.SOURCE_LIMIT, assertThrows(EditFailure::class.java) { EditPlanner.plan(request, sources) }.code)
    }

    @Test fun saveSpaceDownscalesAndReducesBitrateWithoutCallingOriginalLossless() {
        val hd = source.copy(width = 1920, height = 1080)
        val original = EditPlanner.plan(EditRequest("operation", listOf(segment)), listOf(hd))
        val compact = EditPlanner.plan(EditRequest("operation", listOf(segment), EditCompression.SAVE_SPACE), listOf(hd))
        assertEquals(1920, original.expectation.width); assertEquals(1080, original.expectation.height)
        assertEquals(1280, compact.expectation.width); assertEquals(720, compact.expectation.height)
        assertTrue(compact.videoBitrate < original.videoBitrate)
        assertTrue(original.requiresTranscode); assertTrue(compact.requiresTranscode)
        assertTrue(compact.requiredFreeBytes in 1..EditLimits.MAX_OUTPUT_BYTES)
    }

    @Test fun mergeNormalizesSameAspectToSmallestCropWithoutUpscaling() {
        val small = source.copy(ref = MediaReadRef("small", 1, 1))
        val big = source.copy(width = 1920, height = 1080)
        val request = EditRequest("operation", listOf(segment, segment.copy(source = small.ref)))
        val plan = EditPlanner.plan(request, listOf(big, small))
        assertEquals(640, plan.expectation.width); assertEquals(360, plan.expectation.height)
    }

    @Test fun incompatibleAspectRateAndAudioLayoutsAreExplicitRejections() {
        val next = source.copy(ref = MediaReadRef("next", 1, 1))
        val request = EditRequest("operation", listOf(segment, segment.copy(source = next.ref)))
        for (different in listOf(next.copy(width = 640, height = 480), next.copy(frameRate = 60, frameTimeline = EditFixtures.timeline(next.durationUs, 60)),
            next.copy(audio = EditAudioProfile.Aac(48_000, 2)))) {
            assertEquals(EditError.INCOMPATIBLE_SEGMENTS,
                assertThrows(EditFailure::class.java) { EditPlanner.plan(request, listOf(source, different)) }.code)
        }
        val audio = source.copy(audio = EditAudioProfile.Aac(48_000, 2))
        for (layout in listOf(EditAudioProfile.Aac(44_100, 2), EditAudioProfile.Aac(48_000, 1))) {
            assertThrows(EditFailure::class.java) { EditPlanner.plan(request, listOf(audio, next.copy(audio = layout))) }
        }
    }

    @Test fun unsupportedAudioRatesAndSurroundCannotEnterAcceptedMetadata() {
        for (rate in listOf(0, 8_000, 96_000, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { EditAudioProfile.Aac(rate, 2) }
        for (channels in listOf(0, 3, 6, 8)) assertThrows(IllegalArgumentException::class.java) { EditAudioProfile.Aac(48_000, channels) }
    }

    @Test fun quarterTurnCropUsesDisplayDimensionsAndCorrectSourceRegion() {
        val rotated = source.copy(width = 360, height = 640, rotationDegrees = 90)
        val clipped = segment.copy(crop = NormalizedCrop(0.0, 0.0, 0.5, 1.0))
        val plan = EditPlanner.plan(EditRequest("operation", listOf(clipped)), listOf(rotated))
        assertEquals(PixelCrop(0, 0, 320, 360), plan.segments.single().displayCrop)
        assertEquals(PixelCrop(0, 320, 360, 640), plan.segments.single().sourceCrop)
        assertEquals(320, plan.expectation.width); assertEquals(360, plan.expectation.height)
        assertEquals(0, plan.expectation.rotationDegrees)
    }

    @Test fun quantizationAndAacPrimingTolerancesScaleWithSegmentsAndVerifierBudgetIsBounded() {
        val request = EditRequest("operation", List(8) { segment })
        val plan = EditPlanner.plan(request, listOf(source.copy(audio = EditAudioProfile.Aac(48_000, 2))))
        assertEquals(33_334L * 8, plan.expectation.durationToleranceUs)
        assertEquals(42_667L * 8, plan.expectation.audioPrimingToleranceUs)
        assertTrue(EditLimits.verificationTimeoutMillis(2_000_000) < EditLimits.verificationTimeoutMillis(100_000_000))
        assertEquals(EditLimits.MAX_VERIFY_TIMEOUT_MILLIS, EditLimits.verificationTimeoutMillis(EditLimits.MAX_OUTPUT_DURATION_US))
    }
}
