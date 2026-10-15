package com.easysavevideo.app.media.transform

import com.easysavevideo.core.model.OperationStopReason
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** Pure result/policy and source contracts; synthetic outcomes do not prove native release. */
class TransformerExtractionContractTest {
    private val base = "src/main/kotlin/com/easysavevideo/app/media/transform/"
    @Test fun everyDirectStopRetainsItsExactPrimaryAcrossBothCleanupStatesAndFutures() {
        for (reason in OperationStopReason.entries) for (release in TransformerRelease.entries) {
            val primary = TransformerStopped(reason)
            val unwrapped = transformerPrimaryFailure(ExecutionException(CompletionException(primary)))
            val result = TransformerOutcome.Failed(unwrapped, release)
            assertSame(primary, result.primary)
            assertEquals(reason, (result.primary as TransformerStopped).reason)
            assertEquals(release, result.release)
        }
        val arbitrary = IllegalStateException("technical")
        assertSame(arbitrary, transformerPrimaryFailure(ExecutionException(arbitrary)))
    }
    @Test fun knownCleanupDoesNotErasePrimaryContentFailure() {
        val failure = TransformerFailure(TransformerError.EXECUTION_FAILED)
        val result = TransformerOutcome.Failed(failure, TransformerRelease.CONFIRMED)
        assertSame(failure, result.primary)
        assertEquals(TransformerRelease.CONFIRMED, result.release)
    }
    @Test fun sharedFinalizationRetainsByteCapAndNeverRunsCancelledWork() {
        val policy = TransformerOutputIoPolicy(100)
        policy.activeCheckpoint { }; policy.range(0, 60)
        policy.beginFinalization()
        policy.activeCheckpoint { fail("Cleanup must not restart cancellation-sensitive work") }
        policy.range(60, 40)
        try { policy.range(60, 41); fail() } catch (_: TransformerFailure) { }
        policy.confirmedClosed()
        try { policy.range(0, 1); fail() } catch (_: TransformerFailure) { }
    }
    @Test fun directCannotBeAssembledOrReachAnyResourceBeforeTheExplicitGate() {
        val spec = File(base + "TransformerRunSpec.kt").readText()
        assertTrue(spec.contains("sealed class TransformerDirectLoaderBinding private constructor()"))
        assertTrue(spec.contains("class DirectVp8Opus(val binding: TransformerDirectLoaderBinding"))
        assertTrue(spec.contains("is TransformerRunSpec.DirectVp8Opus -> throw TransformerFailure(TransformerError.NOT_ISSUED)"))
        assertFalse(spec.contains("val extractorFactory:"))
        val engine = File(base + "TransformerEngine.kt").readText()
        val gate = engine.indexOf("val run = requireIssuedTransformerRun(spec)")
        assertTrue(gate >= 0)
        for (action in listOf("session.checkpoint()", "target.verifyIdentity()", "TransformerBoundedMuxer(", "TransformerSilentDiagnostics.begin", "configure(run"))
            assertTrue(action, engine.indexOf(action) > gate)
    }
    @Test fun editorSnapshotsAndRegisteredUrisCannotInjectASecondSource() {
        val spec = File(base + "TransformerRunSpec.kt").readText()
        assertTrue(spec.contains("Collections.unmodifiableList(ArrayList(items))"))
        assertTrue(spec.contains("item.address != \"esv-edit:///source-\$index.mp4\""))
        assertTrue(spec.contains("local.uri.toString() != item.address"))
        for (guard in listOf("local.drmConfiguration != null", "local.adsConfiguration != null", "local.subtitleConfigurations.isNotEmpty()", "local.streamKeys.isNotEmpty()", "local.customCacheKey != null", "local.tag != null"))
            assertTrue(guard, spec.contains(guard))
        val io = File(base + "TransformerDataSource.kt").readText()
        assertTrue(io.contains("sources[dataSpec.uri.toString()] ?: reject()"))
        assertTrue(io.contains("source.readAt(position, buffer, offset, requested)"))
        assertFalse(io.contains("DefaultDataSource")); assertFalse(io.contains("File("))
    }
    @Test fun actualNativeFactoriesBindOnlyTheExactSelectedCodecRoles() {
        val source = File(base + "TransformerNativeFactories.kt").readText()
        assertTrue(source.contains("sourceObserver?.videoDecoder(tracked)"))
        assertTrue(source.contains("sourceObserver?.audioDecoder(tracked, originalAudioFormat ?: reject())"))
        assertTrue(source.contains("sourceObserver?.audioEncoder(tracked)"))
        assertTrue(source.contains("Role.VIDEO_ENCODER -> tracked"))
        assertTrue(source.contains("TransformerInputCodecs.AVC_AAC) == (sourceObserver == null)"))
        assertTrue(source.contains("setEnableDecoderFallback(false)"))
        assertTrue(source.contains("setEnableFallback(false).setEnableFormatFallback(false)"))
    }
}
