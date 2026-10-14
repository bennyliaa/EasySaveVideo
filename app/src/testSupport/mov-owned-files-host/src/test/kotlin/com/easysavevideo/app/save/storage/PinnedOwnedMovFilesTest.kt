package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.normalization.*
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test

/** Actual pinned remux + production file/session/protocol; syscalls and SQL are deterministic fakes. */
class PinnedOwnedMovFilesTest {
    private fun fixture(options: OriginalMovFixtures.Options = OriginalMovFixtures.Options()): Pair<NormalizationDescriptorFixture, ByteArray> {
        val raw = OriginalMovFixtures.build(options)
        return NormalizationDescriptorFixture(raw.size).also { it.io.mutate(it.primary, raw) } to raw
    }
    private fun engine() = MovRemuxEngine(EpochClock { 0 })
    private fun adopt(f: NormalizationDescriptorFixture, key: NormalizationKey, proof: DirectNormalizationProof) {
        f.record = f.record!!.copy(directNormalization = proof, phase = SavePhase.ADOPTING)
        f.authority = f.authority.copy(proofSha256 = proof.digest())
        f.attempts.recordAdoptionIntent(key, proof, f.context)
        f.attempts.adopt(key, f.context)
    }

    @Test fun realMuxerThroughShortSyscallsDurablyFinishesAndAdoptsWithoutOverwritingRaw() {
        val (f, raw) = fixture(OriginalMovFixtures.Options(count = 7, allIdr = true)); f.io.maxRead = 1_024; f.io.maxWrite = 1_024
        val attempt = f.prepare()
        val evidence = engine().remux(attempt, f.context, f.limits)
        assertTrue("At least one source read must actually be short", f.io.calls.any {
            it.operation == "pread" && it.count > f.io.maxRead && it.position + f.io.maxRead < raw.size
        })
        assertTrue("At least one output write must actually be short", f.io.calls.any {
            it.operation == "pwrite" && it.count > f.io.maxWrite
        })
        val candidate = f.io.bytes(f.outputFile(attempt.key))
        val proof = f.attempts.finish(attempt, evidence, f.context)
        assertEquals(NormalizationLifecycle.OUTPUT_READY, f.ledger.row!!.lifecycle)
        assertArrayEquals(raw, f.io.bytes(f.primary)); assertFalse(f.io.exists(f.rawFile(attempt.key)))
        assertNull(f.record!!.directNormalization)
        assertFalse(f.attempts.hasAnyLeases())
        adopt(f, attempt.key, proof)
        assertEquals(NormalizationLifecycle.ADOPTED, f.ledger.row!!.lifecycle)
        assertArrayEquals(raw, f.io.bytes(f.rawFile(attempt.key)))
        assertArrayEquals(candidate, f.io.bytes(f.primary)); assertFalse(f.io.exists(f.outputFile(attempt.key)))
        assertEquals(evidence.conservation, MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(candidate)).conservation)
        assertTrue(f.io.handles.none { it.active }); assertFalse(f.attempts.releaseUnconfirmed())
        assertEquals(TaskState.RUNNING, f.record!!.task.state) // Adoption never publishes.
    }

    @Test fun realMuxCancellationLeavesWritingAndOnlyExplicitTerminalCleanupRemovesIt() {
        val (f, raw) = fixture(); val attempt = f.prepare()
        f.io.after = { if (it.operation == "pwrite") f.cancellation.cancel() }
        val failure = assertThrows(SaveFailure::class.java) { engine().remux(attempt, f.context, f.limits) }
        assertEquals(SaveError.CANCELLED, failure.code); attempt.close()
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        assertArrayEquals(raw, f.io.bytes(f.primary)); assertFalse(f.attempts.hasAnyLeases())
        f.record = f.record!!.copy(task = f.record!!.task.copy(state = TaskState.CANCELLED))
        f.authority = f.authority.copy(taskState = NormalizationTaskState.CANCELLED)
        f.attempts.cleanup(attempt.key, f.ledger.row!!.revision)
        assertEquals(NormalizationLifecycle.CLEANED, f.ledger.row!!.lifecycle)
        assertFalse(f.io.exists(f.primary)); assertFalse(f.io.exists(f.workspace))
        assertTrue(f.io.handles.none { it.active })
    }

    @Test fun audioRejectsBeforeOpeningAWriterAndCannotProduceReadyProof() {
        val (f, raw) = fixture(OriginalMovFixtures.Options(audio = true)); val attempt = f.prepare()
        val failure = assertThrows(SaveFailure::class.java) { engine().remux(attempt, f.context, f.limits) }
        assertEquals(SaveError.UNSUPPORTED_MEDIA, failure.code); attempt.close()
        assertFalse(f.io.calls.any { it.operation == "open" && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        assertArrayEquals(raw, f.io.bytes(f.primary)); assertFalse(f.attempts.hasAnyLeases())
    }

    @Test fun outputMutationAfterRealRemuxCannotFinishOrAdopt() {
        val (f, _) = fixture(); val attempt = f.prepare()
        val evidence = engine().remux(attempt, f.context, f.limits)
        val changed = f.io.bytes(f.outputFile(attempt.key)); changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        f.io.mutate(f.outputFile(attempt.key), changed, preserveTimes = true)
        assertThrows(NormalizationRecovery::class.java) { f.attempts.finish(attempt, evidence, f.context) }
        attempt.close(); assertNull(f.record!!.directNormalization)
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        assertFalse(f.attempts.hasAnyLeases())
    }

    @Test fun actualMuxSinkCloseUncertaintySuppressesProofAndRetainsStorageOwner() {
        val (f, _) = fixture(); val attempt = f.prepare()
        f.io.closeMode = { if (it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING)
            MemoryNormalizationDescriptorIo.CloseMode.INVALIDATES_THEN_THROWS else MemoryNormalizationDescriptorIo.CloseMode.NORMAL }
        val engine = engine()
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED,
            assertThrows(SaveFailure::class.java) { engine.remux(attempt, f.context, f.limits) }.code)
        assertThrows(SaveFailure::class.java) { attempt.close() }
        assertTrue(engine.requiresProcessRecovery()); assertTrue(f.attempts.releaseUnconfirmed()); assertTrue(f.attempts.hasAnyLeases())
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle); assertNull(f.record!!.directNormalization)
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED,
            assertThrows(SaveFailure::class.java) { f.prepare() }.code)
    }

    @Test fun readyWithoutSaveProofCanBeInspectedButRestartNeverRemuxesIt() {
        val (f, _) = fixture(); val attempt = f.prepare()
        val evidence = engine().remux(attempt, f.context, f.limits)
        f.attempts.finish(attempt, evidence, f.context)
        assertNull(f.record!!.directNormalization)
        val restoredFiles = AndroidNormalizationFiles(f.root, f.durable, f.lock, { f.available }, { f.storageClosed }, f.io)
        val restored = NormalizationAttemptOwner(f.ledger, restoredFiles, { f.authority }, { f.record }, f.lock,
            { f.storageClosed }, {}, f::checkOperation)
        val beforeWrites = f.io.calls.count { it.operation == "pwrite" }
        assertThrows(NormalizationRecovery::class.java) { restored.prepare(f.owner, f.context, f.limits) }
        val inspected = restored.inspect(f.owner) as NormalizationInspection.Present
        assertEquals(NormalizationLayout.READY, inspected.layout)
        assertEquals(beforeWrites, f.io.calls.count { it.operation == "pwrite" }); assertFalse(restored.hasAnyLeases())
    }
}

/** Emits exact test progress; fixture trace collection is not a throughput benchmark. */
object PinnedOwnedMovFilesRunner {
    @JvmStatic fun main(args: Array<String>) {
        val core = org.junit.runner.JUnitCore()
        core.addListener(object : org.junit.runner.notification.RunListener() {
            override fun testStarted(description: org.junit.runner.Description) { println("START " + description.methodName) }
            override fun testFinished(description: org.junit.runner.Description) { println("FINISH " + description.methodName) }
        })
        val result = core.run(PinnedOwnedMovFilesTest::class.java)
        result.failures.forEach { println(it.trace) }
        println("RESULT tests=${result.runCount} failures=${result.failureCount} ignored=${result.ignoreCount}")
        if (!result.wasSuccessful()) kotlin.system.exitProcess(1)
    }
}
