package com.easysavevideo.app.media.transform

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source wiring contracts only. Does not run Android handlers, codecs or a Direct conversion. */
class SharedTransformerRuntimeWiringTest {
    private val base = "src/main/kotlin/com/easysavevideo/app/"
    @Test fun editorRetainsItsProductWrapperAndCallsTheSharedMechanism() {
        val source = File(base + "editor/engine/Media3EditorEngine.kt").readText()
        assertTrue(source.contains("private val mechanism = TransformerEngine(context.applicationContext)"))
        assertTrue(source.contains("runTransformerSession(session.native)"))
        assertTrue(source.contains("mechanism.execute(spec(plan, session), EditorTransformerTarget(output), session.native)"))
        assertTrue(source.contains("EditorMetadataProbe.inspect"))
        assertTrue(source.contains("distinct.forEach { verifyHash(it.segment.source, session) }"))
        assertTrue(source.contains("setClippingConfiguration"))
        assertTrue(source.contains("Effects(emptyList(), effects)"))
        assertTrue(source.contains("outcome.release == TransformerRelease.UNCONFIRMED"))
        for (forbidden in listOf("ThreadPoolExecutor(", "EditorReleaseFence(", "HandlerThread(", "Handler(", "Transformer.Builder(", "setFrameRate("))
            assertFalse(forbidden, source.contains(forbidden))
    }
    @Test fun sharedRunnerUsesOnlyTheExistingSingletonAndKeepsCleanupSeparate() {
        val entry = File(base + "media/transform/TransformerSessionRunner.kt").readText()
        assertTrue(entry.contains("executeTransformerSession(SharedTransformerRuntime.laneOwner, session)"))
        val source = File(base + "media/transform/TransformerSessionExecution.kt").readText()
        assertTrue(source.contains("fence.claim(session)"))
        assertTrue(source.contains("fence.unconfirmed()"))
        assertFalse(source.contains("session.releaseAcknowledged()"))
        assertTrue(source.contains("val workerOutcome = if (result.isDone)"))
        assertTrue(source.contains("result.getNow(null) as? TransformerOutcome<*>"))
        assertTrue(source.contains("workerOutcome.release == TransformerRelease.CONFIRMED"))
        assertTrue(source.contains("val acknowledged = workerReleased && !session.cleanupFailed()"))
        val retired = source.indexOf("entry?.retire()")
        val completed = source.indexOf("result.complete(outcome)")
        assertTrue(retired >= 0); assertTrue(completed >= 0)
        assertTrue(retired < completed)
        assertTrue(source.contains("session.contentFailed()"))
        assertTrue(source.contains("transformerPrimaryFailure(failure)"))
        assertFalse(source.contains("EditFailure")); assertFalse(source.contains("SaveFailure"))
        assertFalse(source.contains("ThreadPoolExecutor(")); assertFalse(source.contains("TransformerLaneOwner("))
    }
    @Test fun runtimeStillHasOneEagerLaneAndSameLazyControlThread() {
        val source = File(base + "media/transform/SharedTransformerRuntime.kt").readText()
        assertTrue(source.contains("internal object SharedTransformerRuntime"))
        assertEquals(1, Regex("TransformerLaneOwner\\(\\)").findAll(source).count())
        assertTrue(source.contains("val laneOwner = TransformerLaneOwner()"))
        assertTrue(source.contains("private val applicationThread by lazy { HandlerThread(\"esv-edit-control\").apply { start() } }"))
        assertTrue(source.contains("val handler by lazy { Handler(applicationThread.looper) }"))
        assertFalse(source.contains(".editor."))
    }
}
