package com.easysavevideo.app.save.storage

import com.easysavevideo.app.media.transform.*
import com.easysavevideo.app.save.conversion.WebmCallerFailure
import com.easysavevideo.app.save.conversion.WebmProbeException
import com.easysavevideo.app.save.conversion.WebmProbeFailure
import com.easysavevideo.app.save.normalization.MovProbeInput
import com.easysavevideo.core.model.OperationStopReason
import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Bounded routing and product-boundary tests; no WebM/native acceptance is asserted. */
class DirectConvertedBoundaryPolicyTest {
    @Test fun ebmlSignatureIsOnlyBoundedCandidateSelectionEvenForMalformedOrAudioUnknownInput() {
        for (expectedAudio in listOf(false, true)) for (extent in listOf(4L, 4096L, 2L * 1024 * 1024 * 1024)) {
            var reads = 0; var checkpoints = 0
            val input = object : MovProbeInput {
                override val length = extent
                override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int) {
                    assertEquals(0L, offset); assertEquals(4, length); reads++
                    byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()).copyInto(target, targetOffset)
                }
            }
            assertEquals(DirectRawKind.WEBM_CANDIDATE, DirectRawFormatSelector.select(input, expectedAudio) { checkpoints++ })
            assertEquals(1, reads); assertEquals(1, checkpoints)
        }
    }
    @Test fun stoppedSignatureProbeCannotReadAnyBytes() {
        val stop = SaveFailure(SaveError.CANCELLED)
        val input = object : MovProbeInput {
            override val length = 4096L
            override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Unit = error("NO_READ")
        }
        assertSame(stop, assertThrows(SaveFailure::class.java) { DirectRawFormatSelector.select(input, false) { throw stop } })
    }
    @Test fun existingPrimaryFailuresArePreservedBeforeCleanupOrLaterStop() {
        for (code in listOf(SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED, SaveError.RESOURCE_CHANGED,
            SaveError.STORAGE_FAILURE, SaveError.RESOURCE_RELEASE_UNCONFIRMED, SaveError.UNSUPPORTED_MEDIA)) {
            val primary = SaveFailure(code)
            primary.addSuppressed(IOException("SYNTHETIC_CLEANUP_FAILURE"))
            assertSame(primary, directConversionFailure(primary))
            assertSame(primary, directConversionFailure(WebmCallerFailure(primary)))
        }
        assertEquals(SaveError.RESOURCE_CHANGED, directConversionFailure(SaveFailure(SaveError.INCOMPLETE_TRANSFER)).code)
    }
    @Test fun alreadySelectedIoPrimarySurvivesCancellationDuringCleanupAndBoundarySelectedStopIsPreserved() {
        val cancellation = CancellationSource()
        val originalIo = IOException("SYNTHETIC_SELECTED_IO")
        // The actual producer has already selected IO; cleanup now observes a later cancellation.
        val selectedBeforeCleanup: Exception = originalIo
        cancellation.cancel()
        assertTrue(cancellation.isCancelled)
        assertEquals(SaveError.STORAGE_FAILURE, directConversionFailure(selectedBeforeCleanup).code)
        assertEquals(SaveError.STORAGE_FAILURE, directConversionFailure(WebmCallerFailure(originalIo)).code)
        for (code in listOf(SaveError.CANCELLED, SaveError.DEADLINE_EXCEEDED)) {
            val stopSelectedAtActualBoundary = SaveFailure(code)
            stopSelectedAtActualBoundary.addSuppressed(IOException("SYNTHETIC_LATER_CLEANUP_IO"))
            assertSame(stopSelectedAtActualBoundary, directConversionFailure(stopSelectedAtActualBoundary))
        }
        assertEquals(SaveError.STORAGE_FAILURE, directConversionFailure(SecurityException()).code)
        assertEquals(SaveError.RECOVERY_REQUIRED, directConversionFailure(NormalizationRecovery()).code)
    }
    @Test fun typedProducerAndWebmErrorsStayDistinctAndNeverBecomeRetryableHttp() {
        val transform = mapOf(TransformerError.CAPACITY to SaveError.BUSY,
            TransformerError.INVALID_REQUEST to SaveError.RECOVERY_REQUIRED,
            TransformerError.INVALID_STATE to SaveError.RECOVERY_REQUIRED,
            TransformerError.NOT_ISSUED to SaveError.RECOVERY_REQUIRED,
            TransformerError.SOURCE_LIMIT to SaveError.BYTE_LIMIT_EXCEEDED,
            TransformerError.UNSUPPORTED_PROFILE to SaveError.UNSUPPORTED_MEDIA,
            TransformerError.EXECUTION_FAILED to SaveError.ENCODER_FAILED,
            TransformerError.INSUFFICIENT_SPACE to SaveError.STORAGE_FULL)
        transform.forEach { (from, to) -> assertEquals(to, directConversionFailure(TransformerFailure(from)).code) }
        val webm = mapOf(WebmProbeFailure.PROTECTED_MEDIA to SaveError.PROTECTED_MEDIA,
            WebmProbeFailure.IDENTITY to SaveError.RESOURCE_CHANGED, WebmProbeFailure.IO_BUDGET to SaveError.BYTE_LIMIT_EXCEEDED,
            WebmProbeFailure.CANCELLED to SaveError.CANCELLED, WebmProbeFailure.DEADLINE to SaveError.DEADLINE_EXCEEDED,
            WebmProbeFailure.TRACK_PROFILE to SaveError.UNSUPPORTED_MEDIA, WebmProbeFailure.TRUNCATED to SaveError.UNSUPPORTED_MEDIA)
        webm.forEach { (from, to) -> assertEquals(to, directConversionFailure(WebmProbeException(from)).code) }
        for (stop in OperationStopReason.entries) assertEquals(when (stop) {
            OperationStopReason.CANCELLED -> SaveError.CANCELLED
            OperationStopReason.DEADLINE_EXCEEDED -> SaveError.DEADLINE_EXCEEDED
            OperationStopReason.RETRY_EXHAUSTED -> SaveError.RETRY_EXHAUSTED
        }, directConversionFailure(TransformerStopped(stop)).code)
    }
}
