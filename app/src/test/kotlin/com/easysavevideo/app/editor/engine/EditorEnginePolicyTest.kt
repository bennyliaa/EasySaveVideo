package com.easysavevideo.app.editor.engine

import com.easysavevideo.library.edit.EditFailure
import org.junit.Assert.*
import org.junit.Test

/** Host policy tests only. MediaCodec/Transformer/GL/OEM behavior is NOT_RUN. */
class EditorEnginePolicyTest {
    @Test fun outputBudgetIncludesHeaderAndFinalTableWrites() {
        assertTrue(EditorEnginePolicy.outputRangeAllowed(0, 100, 100))
        assertTrue(EditorEnginePolicy.outputRangeAllowed(100, 0, 100))
        assertFalse(EditorEnginePolicy.outputRangeAllowed(100, 1, 100))
        assertFalse(EditorEnginePolicy.outputRangeAllowed(-1, 1, 100))
        assertFalse(EditorEnginePolicy.outputRangeAllowed(1, -1, 100))
        assertFalse(EditorEnginePolicy.outputRangeAllowed(Long.MAX_VALUE, 1, Long.MAX_VALUE))
        assertFalse(EditorEnginePolicy.outputRangeAllowed(0, 0, 0))
    }
    @Test fun onlyOneOwnerCanClaimTheLane() {
        val fence = EditorReleaseFence(); val first = Any(); val other = Any()
        assertTrue(fence.claim(first)); assertFalse(fence.claim(other)); assertTrue(fence.occupied)
        fence.release(first); assertFalse(fence.occupied); assertTrue(fence.claim(other))
    }
    @Test fun timeoutNeverReleasesOrReplacesNativeOwner() {
        val fence = EditorReleaseFence(); val first = Any()
        assertTrue(fence.claim(first)); fence.unconfirmed(); fence.release(first)
        assertTrue(fence.requiresRestart); assertTrue(fence.occupied); assertFalse(fence.claim(Any()))
    }
    @Test(expected = IllegalStateException::class) fun anotherOwnerCannotReleaseTheLane() {
        val fence = EditorReleaseFence(); fence.claim(Any()); fence.release(Any())
    }
    @Test fun fullDisplayCropMapsToFullNdc() {
        assertArrayEquals(floatArrayOf(-1f, 1f, -1f, 1f), EditorEnginePolicy.ndc(0, 0, 1920, 1080, 1920, 1080), 0f)
    }
    @Test fun displayCropYIsInvertedExactlyOnce() {
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f, 1f), EditorEnginePolicy.ndc(0, 0, 540, 960, 1080, 1920), 0f)
    }
    @Test(expected = EditFailure::class) fun cropBeyondDisplayIsRejected() {
        EditorEnginePolicy.ndc(0, 0, 1082, 1920, 1080, 1920)
    }
    @Test fun integerCadenceIsObservedWithIntegerTimescaleRounding() {
        val times = LongArray(90) { it * 1_000_000L / 30 }
        assertEquals(30, EditorEnginePolicy.observedFrameRate(times, times.size, 3_000_000))
    }
    @Test fun bFrameDecodeOrderIsSortedForCadenceInspection() {
        assertEquals(25, EditorEnginePolicy.observedFrameRate(longArrayOf(0, 80_000, 40_000, 120_000), 4, 160_000))
    }
    @Test(expected = EditFailure::class) fun duplicatePtsFailClosed() {
        EditorEnginePolicy.observedFrameRate(longArrayOf(0, 40_000, 40_000, 120_000), 4, 160_000)
    }
    @Test(expected = EditFailure::class) fun vfrCannotUseAverageRateAsEvidence() {
        EditorEnginePolicy.observedFrameRate(longArrayOf(0, 20_000, 80_000, 120_000), 4, 160_000)
    }
    @Test(expected = EditFailure::class) fun fractionalCadenceIsNotRoundedIntoIntegerCfr() {
        val times = LongArray(90) { it * 1_001_000L / 30 }
        EditorEnginePolicy.observedFrameRate(times, times.size, 3_003_000)
    }
    @Test(expected = EditFailure::class) fun invalidExtentFailsCadence() {
        EditorEnginePolicy.observedFrameRate(longArrayOf(0, 40_000, 80_000, 120_000), 4, 500_000)
    }
    @Test(expected = EditFailure::class) fun oversizedSampleCountIsRejectedWithoutAllocation() {
        EditorEnginePolicy.observedFrameRate(longArrayOf(0, 40_000), Int.MAX_VALUE, 80_000)
    }
    @Test fun cancelledPartialOutputCanFinalizeCloseAndAdmitNextJob() {
        val fence = EditorReleaseFence(); val owner = Any(); assertTrue(fence.claim(owner))
        val io = EditorOutputIoPolicy(100)
        io.activeCheckpoint { }; io.range(0, 60) // Some samples were written before cancellation.
        try { io.activeCheckpoint { throw EditFailure(com.easysavevideo.library.edit.EditError.CANCELLED) }; fail() }
        catch (_: EditFailure) { }
        io.beginFinalization()
        io.activeCheckpoint { fail("Cancellation must not block bounded release IO") }
        io.range(60, 40); io.confirmedClosed()
        fence.release(owner); assertTrue(fence.claim(Any()))
    }
    @Test(expected = EditFailure::class) fun cancelledFinalizationStillEnforcesOutputByteCap() {
        val io = EditorOutputIoPolicy(100); io.beginFinalization(); io.range(60, 41)
    }
    @Test(expected = EditFailure::class) fun closedOutputCannotResumeWriting() {
        val io = EditorOutputIoPolicy(100); io.beginFinalization(); io.confirmedClosed(); io.range(0, 1)
    }

    @Test fun earlyRoundedSourceFramesArePreservedWithoutMaximumRateFilter() {
        assertEquals(25, EditorEnginePolicy.observedFrameRate(longArrayOf(0, 39_999, 80_000, 120_000), 4, 160_000))
    }
    @Test fun preallocationUnsupportedCodecIsDistinguishedFromUncertainConstruction() {
        assertTrue(EditorEnginePolicy.codecCreationProvenAbsent(true, false, false, false))
        assertTrue(EditorEnginePolicy.codecCreationProvenAbsent(false, true, true, false))
        assertFalse(EditorEnginePolicy.codecCreationProvenAbsent(false, true, true, true))
        assertFalse(EditorEnginePolicy.codecCreationProvenAbsent(false, true, false, false))
        assertFalse(EditorEnginePolicy.codecCreationProvenAbsent(false, false, true, false))
    }
    @Test fun codecsCannotBeReleasedBeforeAllQueuedGlCleanupActuallyFinishes() {
        val order = EditorNativeReleaseOrder(); order.registerGl(); order.registerGl()
        assertTrue(order.allowsCodecRelease()) // Normal sequence handoffs precede final teardown.
        order.beginGlRelease(); assertFalse(order.allowsCodecRelease())
        order.confirmGlReleased(); assertFalse(order.allowsCodecRelease())
        order.confirmGlReleased(); assertTrue(order.allowsCodecRelease())
    }
    @Test fun glReleaseTimeoutOrFailureDoesNotBecomePermissionToReleaseCodec() {
        val order = EditorNativeReleaseOrder(); order.registerGl(); order.beginGlRelease()
        assertFalse(order.allowsCodecRelease()) // Elapsed time does not change this predicate.
        order.glFailed(); order.confirmGlReleased(); assertFalse(order.allowsCodecRelease())
    }

    @Test fun nativeAacEncoderPrimingIsBoundedWithoutAllowingNegativeVideo() {
        assertTrue(EditorEnginePolicy.timestampAllowed(-33_333, null, false, 1_000_000, 33_334, 42_667))
        assertTrue(EditorEnginePolicy.timestampAllowed(-12_000, -33_333, false, 1_000_000, 33_334, 42_667))
        assertFalse(EditorEnginePolicy.timestampAllowed(-42_668, null, false, 1_000_000, 33_334, 42_667))
        assertFalse(EditorEnginePolicy.timestampAllowed(-1, null, true, 1_000_000, 33_334, 42_667))
        assertFalse(EditorEnginePolicy.timestampAllowed(-33_333, -12_000, false, 1_000_000, 33_334, 42_667))
    }

    @Test fun hiddenFutureErrorCannotProveGlCleanupSuccess() {
        val task = java.util.concurrent.FutureTask<Unit> { throw AssertionError("synthetic") }
        task.run()
        assertTrue(task.isDone)
        assertTrue(EditorTaskFailureProbe.failed(task, null))
        val order = EditorNativeReleaseOrder(); order.registerGl(); order.beginGlRelease()
        if (EditorTaskFailureProbe.failed(task, null)) order.glFailed()
        order.confirmGlReleased(); assertFalse(order.allowsCodecRelease())
    }
    @Test fun inspectingPendingGlFutureDoesNotBlock() {
        val task = java.util.concurrent.FutureTask<Unit> { }
        assertFalse(EditorTaskFailureProbe.failed(task, null))
        task.run(); assertFalse(EditorTaskFailureProbe.failed(task, null))
    }

}
