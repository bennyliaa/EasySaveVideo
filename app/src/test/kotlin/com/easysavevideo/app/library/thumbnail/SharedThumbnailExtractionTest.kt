package com.easysavevideo.app.library.thumbnail

import android.media.MediaDataSource
import com.easysavevideo.library.thumbnail.ThumbnailFrame
import com.easysavevideo.library.thumbnail.ThumbnailReadResources
import org.junit.Assert.*
import org.junit.Test
import java.io.FileInputStream

class SharedThumbnailExtractionTest {
    private class Frame : ThumbnailFrame {
        override val width = 256; override val height = 144; override val allocationBytes = 147_456L
        var closes = 0; override fun close() { closes++ }
    }
    private class Retriever : SharedThumbnailRetriever {
        val frame = Frame(); var decodes = 0; var closes = 0; var failClose = false
        override fun publishedInput(input: FileInputStream) = Unit
        override fun controlledInput(input: MediaDataSource) = Unit
        override fun singleFrame(): ThumbnailFrame { decodes++; return frame }
        override fun close() { closes++; if (failClose) error("fixed") }
    }
    @Test fun exactSingleFrameTransfersOnlyAfterActualRelease() {
        val native = Retriever(); val x = SharedThumbnailExtraction { native }; x.decode({ false }) {}
        try { x.takeFrame(); fail() } catch (_: IllegalStateException) { }
        x.close(); assertSame(native.frame, x.takeFrame()); assertNull(x.takeFrame())
        x.close(); assertEquals(1, native.decodes); assertEquals(1, native.closes)
    }
    @Test fun constructorFailureIsRetainedUnknownRatherThanNoAcquisition() {
        var attempts = 0; val x = SharedThumbnailExtraction { attempts++; error("fixed") }
        x.decode({ false }) {}; repeat(2) { try { x.close(); fail() } catch (_: IllegalStateException) { } }
        assertFalse(x.released); assertEquals(1, attempts)
    }
    @Test fun setupFailureStillNeedsActualNativeRelease() {
        val native = Retriever(); val x = SharedThumbnailExtraction { native }; x.decode({ false }) { error("fixed") }
        assertTrue(x.unavailable); assertEquals(0, native.decodes); x.close(); assertTrue(x.released); assertNull(x.takeFrame())
    }
    @Test fun releaseFailureRetainsFrameAndNeverSpeculativelyRetries() {
        val native = Retriever().apply { failClose = true }; val x = SharedThumbnailExtraction { native }; x.decode({ false }) {}
        repeat(2) { try { x.close(); fail() } catch (_: IllegalStateException) { } }
        assertFalse(x.released); assertEquals(1, native.closes); assertEquals(0, native.frame.closes)
    }
    @Test fun cancelledBeforeAcquisitionDoesNotCallConstructor() {
        var calls = 0; val x = SharedThumbnailExtraction { calls++; Retriever() }; x.decode({ true }) {}
        x.close(); assertTrue(x.released); assertEquals(0, calls); assertNull(x.takeFrame())
    }
    @Test fun independentlyHeldExtractionRetainsFrameWhenLaterOrdinaryInputCloseFails() {
        val native = Retriever(); val x = SharedThumbnailExtraction { native }; val resources = ThumbnailReadResources()
        resources.nativeReader = x; resources.input = AutoCloseable { error("fixed") }; x.decode({ false }) {}
        assertFalse(resources.releaseConfirmed()); assertNull(resources.nativeReader)
        assertTrue(x.released); assertSame(native.frame, x.takeFrame()); assertEquals(0, native.frame.closes)
    }
}
