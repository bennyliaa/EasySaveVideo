package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.storage.OwnedDirectTechnicalDiagnostics.Stage as S
import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.media.transform.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real JVM diagnostic slot; no Android/native acceptance is inferred from these tests. */
class OwnedDirectTechnicalDiagnosticsTest {
    private fun observation() = TransformerSourceObservation(
        TransformerVideoObservation(25, 0, 960_000, 1_000_000, 25, 0),
        TransformerAudioObservation(0, 600_000, 28_800, 48_000, 48_000, 1, 6500, 80_000, 13_500))
    private fun found(source: String, marker: String): Int = source.indexOf(marker).also {
        assertTrue("Missing marker: $marker", it >= 0)
    }
    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("Expected rejected diagnostic control") } catch (_: IllegalStateException) { }
    }

    @Test fun oneActiveCaptureAndFreshGeneratedKeys() {
        assumeTrue(BuildConfig.DEBUG)
        val first = OwnedDirectTechnicalDiagnostics.begin()
        try {
            expectFailure { OwnedDirectTechnicalDiagnostics.begin() }
            OwnedDirectTechnicalDiagnostics.record(first, S.PREFLIGHT_STARTED)
        } finally {
            assertEquals(listOf(S.PREFLIGHT_STARTED), OwnedDirectTechnicalDiagnostics.end(first).stages)
        }
        val second = OwnedDirectTechnicalDiagnostics.begin()
        try { assertNotEquals(first, second) }
        finally { assertTrue(OwnedDirectTechnicalDiagnostics.end(second).stages.isEmpty()) }
    }

    @Test fun foreignEndCannotClearActiveCaptureAndForeignRecordIsIgnored() {
        assumeTrue(BuildConfig.DEBUG)
        val key = OwnedDirectTechnicalDiagnostics.begin()
        try {
            OwnedDirectTechnicalDiagnostics.record("foreign", S.SEALED, observation())
            expectFailure { OwnedDirectTechnicalDiagnostics.end("foreign") }
            OwnedDirectTechnicalDiagnostics.record(key, S.PREFLIGHT_STARTED)
        } finally {
            val actual = OwnedDirectTechnicalDiagnostics.end(key)
            assertEquals(listOf(S.PREFLIGHT_STARTED), actual.stages)
            assertNull(actual.observation)
        }
    }

    @Test fun snapshotIsDefensivelyCopiedAndStagesAreNotMutable() {
        assumeTrue(BuildConfig.DEBUG)
        val key = OwnedDirectTechnicalDiagnostics.begin()
        val original = observation()
        val snapshot = try {
            OwnedDirectTechnicalDiagnostics.record(key, S.SOURCE_EOS_OBSERVED, original)
            OwnedDirectTechnicalDiagnostics.end(key)
        } catch (failure: Throwable) {
            runCatching { OwnedDirectTechnicalDiagnostics.end(key) }; throw failure
        }
        assertEquals(original, snapshot.observation)
        assertNotSame(original, snapshot.observation)
        assertNotSame(original.video, snapshot.observation!!.video)
        assertNotSame(original.audio, snapshot.observation!!.audio)
        try { (snapshot.stages as MutableList<S>).add(S.SEALED); fail("Mutable snapshot") }
        catch (_: UnsupportedOperationException) { }
        OwnedDirectTechnicalDiagnostics.record(key, S.SEALED)
        assertEquals(listOf(S.SOURCE_EOS_OBSERVED), snapshot.stages)
    }

    @Test fun lateRecordAfterAtomicEndDoesNotEnterTheNextCapture() {
        assumeTrue(BuildConfig.DEBUG)
        val first = OwnedDirectTechnicalDiagnostics.begin()
        val ready = CountDownLatch(1); val proceed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                ready.countDown(); check(proceed.await(5, TimeUnit.SECONDS))
                OwnedDirectTechnicalDiagnostics.record(first, S.SEALED, observation())
            } catch (caught: Throwable) { failure.set(caught) }
        }.apply { isDaemon = true; start() }
        var second: String? = null
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertTrue(OwnedDirectTechnicalDiagnostics.end(first).stages.isEmpty())
            second = OwnedDirectTechnicalDiagnostics.begin()
            proceed.countDown(); thread.join(5000)
            assertFalse(thread.isAlive); assertNull(failure.get())
        } finally {
            proceed.countDown(); thread.join(5000)
            val current = second
            if (current != null) {
                val snapshot = OwnedDirectTechnicalDiagnostics.end(current)
                assertTrue(snapshot.stages.isEmpty()); assertNull(snapshot.observation)
            } else runCatching { OwnedDirectTechnicalDiagnostics.end(first) }
        }
    }

    @Test fun absentCaptureAndReleaseBuildNeverRequireAProducerCallback() {
        OwnedDirectTechnicalDiagnostics.record("absent", S.SEALED, observation())
        if (!BuildConfig.DEBUG) expectFailure { OwnedDirectTechnicalDiagnostics.begin() }
    }

    @Test fun diagnosticWiringDoesNotGrantRunFinishOrPublicAuthority() {
        val root = "src/main/kotlin/com/easysavevideo/app/"
        val attempt = File(root + "save/storage/OwnedDirectTranscodeAttempt.kt").readText()
        val references = attempt.lines().filter { it.contains("OwnedDirectTechnicalDiagnostics.") }
        assertEquals(6, references.size)
        assertTrue(references.all { it.trim().startsWith("OwnedDirectTechnicalDiagnostics.record(") })
        val sealed = found(attempt, "OwnedDirectTechnicalDiagnostics.Stage.SEALED")
        assertTrue(sealed > found(attempt, "val resources = sealResources()"))
        assertTrue(sealed > found(attempt, "resources.first.fingerprint != raw.fingerprint"))
        assertTrue(sealed > found(attempt, "runPhase = RunPhase.SEALED"))
        val reject = attempt.substring(found(attempt, "private fun requireVerifiedDirectOpusMapping"),
            found(attempt, "private fun matchesAudioObservation"))
        assertTrue(found(reject, "Stage.UNVERIFIED_MAPPING_REJECTED") > found(reject, "UNVERIFIED_HEAD_REMOVED_ONCE_ZERO_PCM_ORIGIN ->"))
        assertTrue(found(reject, "throw TransformerFailure(TransformerError.UNSUPPORTED_PROFILE)") > found(reject, "Stage.UNVERIFIED_MAPPING_REJECTED"))
        assertFalse(File(root + "media/transform/TransformerEngine.kt").readText().contains("OwnedDirectTechnicalDiagnostics"))
        assertTrue(File(root + "save/AndroidSaveRuntime.kt").readText().contains("conversionExecutionEnabled = false"))
        val diagnostics = File(root + "save/storage/OwnedDirectTechnicalDiagnostics.kt").readText()
        assertTrue(diagnostics.contains("catch (_: Throwable)"))
        for (live in listOf("FileDescriptor", "TransformerSession", "OwnedDirectTranscodeAttempt", "() ->", "ByteBuffer", "MediaCodec"))
            assertFalse(live, diagnostics.contains(live))
    }

    @Test fun existingBrowserBodiesAreByteIdenticalAndMediaDispatchPrecedesServer() {
        val source = File("src/androidTest/kotlin/com/easysavevideo/app/validation/OwnedVerificationInstrumentation.kt").readText()
        assertTrue(source.contains("phase=args?.getString(\"phase\")?:\"b\""))
        assertTrue(source.contains("setOf(\"b\",\"ab\",\"opus-technical\",\"evaluation-export\")"))
        val dispatch = found(source, "if (phase == \"opus-technical\") { OpusTechnicalValidation.run(this); return }")
        assertTrue(dispatch < found(source, "val server=ServerSocket"))
        val unchanged = source.substring(found(source, "        val report=JSONObject();"))
        val hash = MessageDigest.getInstance("SHA-256").digest(unchanged.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("d6b80527dfda650d04914c642eefb160c4d568acd57bbf5c6ab10873c9047f42", hash)
        val harness = File("src/androidTest/kotlin/com/easysavevideo/app/validation/OpusTechnicalValidation.kt").readText()
        assertTrue(harness.contains("UNEXPECTED_PREFERENCES"))
        assertEquals(2, Regex("override fun openOrCreateDatabase").findAll(harness).count())
        assertTrue(harness.contains("override fun getApplicationContext(): Context = this"))
        assertTrue(harness.contains("actual.directConversion == null && actual.directNormalization == null"))
        assertTrue(harness.contains("actual.verification == null && actual.receipt == null && storage.visibleMedia(100).isEmpty()"))
        assertFalse(harness.contains("deleteRecursively")); assertFalse(harness.contains("getDeclaredField"))
        val releaseAttempt = found(harness, "try { released = claim?.let(admission::release) ?: true }")
        val releaseCatch = found(harness, "catch (cleanup: Throwable) { releaseFailure = cleanup.javaClass.simpleName }")
        val closeAttempt = found(harness, "try { storage.close(); storageClosed = true }")
        val retain = found(harness, "unclosedCase = RetainedCase(storage, admission, claim, nativeGate, batchOwner, batchBeforeRelease,")
        assertTrue(releaseCatch > releaseAttempt && closeAttempt > releaseCatch && retain > closeAttempt)
        assertTrue(harness.contains("if (!released || !storageClosed) {"))
        assertTrue(harness.contains("retainedBridge, retainedComponents, retainedCoordinator)"))
        assertTrue(harness.contains("if (unclosedCase != null || !row.optBoolean(\"claim_released\"))"))
    }
}
