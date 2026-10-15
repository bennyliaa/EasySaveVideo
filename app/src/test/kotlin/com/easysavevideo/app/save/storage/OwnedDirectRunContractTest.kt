package com.easysavevideo.app.save.storage

import com.easysavevideo.app.media.transform.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Owner and wiring negatives only; actual pinned callbacks have a separate host runner. */
class OwnedDirectRunContractTest {
    @Test fun inactiveConcreteCompositionConsumesAttemptWithoutProbeOrOutputIo() = OwnedTranscodeTestFixture().run { h, _ ->
        val attempt = h.prepare()
        val reads = h.f.io.calls.count { it.operation == "pread" }
        val opens = h.f.io.calls.count { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING }
        val failure = assertThrows(TransformerFailure::class.java) { h.f.attempts.runOwnedTranscode(attempt) }
        assertEquals(TransformerError.NOT_ISSUED, failure.code)
        assertEquals(reads, h.f.io.calls.count { it.operation == "pread" })
        assertEquals(opens, h.f.io.calls.count { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        assertThrows(Exception::class.java) { h.f.attempts.runOwnedTranscode(attempt) }
        assertFalse(h.f.attempts.hasAnyLeases())
        assertNull(h.f.record!!.directConversion)
        assertEquals(NormalizationLifecycle.WRITING, h.f.ledger.row!!.lifecycle)
    }
    @Test fun staleSaveSnapshotRejectsBeforeAnyRunOrOutput() = OwnedTranscodeTestFixture().run { h, _ ->
        val attempt = h.prepare()
        val before = h.f.io.calls.size
        h.f.record = h.f.record!!.copy(bytesTransferred = h.f.record!!.bytesTransferred + 1)
        assertThrows(NormalizationRecovery::class.java) { h.f.attempts.runOwnedTranscode(attempt) }
        assertEquals(before, h.f.io.calls.size)
        attempt.close()
    }
    @Test fun staleWritingSnapshotAndExitedScopeCannotRun() {
        for (scopeEnded in listOf(false, true)) OwnedTranscodeTestFixture().run { h, scope ->
            val attempt = h.prepare()
            if (scopeEnded) scope.close() else h.f.ledger.row = h.f.ledger.row!!.copy(revision = h.f.ledger.row!!.revision + 1)
            val before = h.f.io.calls.size
            assertThrows(Exception::class.java) { h.f.attempts.runOwnedTranscode(attempt) }
            assertEquals(before, h.f.io.calls.size)
            attempt.close()
        }
    }
    @Test fun immutableDirectDataStillHasNoIssuedBranch() {
        val output = TransformerOutputSpec(16, 16, 30, 100_000, 34_334, 0, 3, null, 1_000_000, 0, 256_000, 0)
        val error = assertThrows(TransformerFailure::class.java) {
            requireIssuedTransformerRun(TransformerRunSpec.DirectVp8Opus(TransformerDirectLoaderBinding.Unissued, output))
        }
        assertEquals(TransformerError.NOT_ISSUED, error.code)
    }
    @Test fun actualReturnedOutcomeAndActiveObservationsHaveOnlyTheOnePrivateProducer() {
        val root = "src/main/kotlin/com/easysavevideo/app/"
        val attempt = File(root + "save/storage/OwnedDirectTranscodeAttempt.kt").readText()
        assertEquals(1, Regex("actualRunOutcome = returned").findAll(attempt).count())
        assertTrue(attempt.contains("val returned = runTransformerSession(session)"))
        val capture = attempt.substringAfter("internal fun directCompletedTransport(").substringBefore("internal fun directMediaSource(")
        assertEquals(1, Regex("pair\\.completedTransportFacts\\(\\)").findAll(attempt).count())
        assertTrue(capture.contains("requireEngine(engine)"))
        assertTrue(capture.contains("runCompletedTransport = it"))
        assertTrue(capture.contains("sameFinishedTransport(runTransport ?: reject(), completed)"))
        val returnedOutcome = attempt.indexOf("actualRunOutcome = returned")
        val sealing = attempt.indexOf("val resources = sealResources()")
        assertTrue(returnedOutcome >= 0); assertTrue(sealing >= 0)
        assertTrue(returnedOutcome < sealing)
        assertTrue(attempt.contains("WebmDirectCandidatePolicy.select(transport, outputMaximum)"))
        assertFalse(attempt.contains("recordCompleted(")); assertFalse(attempt.contains("recordFailure("))
        val engine = File(root + "media/transform/TransformerEngine.kt").readText()
        assertTrue(engine.contains("attempt.claimOwnedDirect(this)"))
        assertTrue(engine.contains("TransformerInputCodecs.VP8_OPUS, observer"))
        assertTrue(engine.contains("val completed = attempt.directCompletedTransport(this)"))
        assertTrue(engine.contains("val observation = observer.finish(completed)"))
        assertTrue(engine.indexOf("val completed = attempt.directCompletedTransport(this)") <
            engine.indexOf("val observation = observer.finish(completed)"))
        assertTrue(attempt.contains("if (heard != null) requireVerifiedDirectOpusMapping(heard)"))
        assertTrue(attempt.contains("policyRevision = if (heard == null) 1 else 2"))
        assertTrue(engine.contains("factories.sourcePcm(format, listener.onOutputFormat(format))"))
        assertFalse(engine.contains("setFrameRate(")); assertFalse(engine.contains("getDeclaredField("))
        val runtime = File(root + "save/AndroidSaveRuntime.kt").readText()
        assertFalse(runtime.contains("runOwnedTranscode"))
    }
}
