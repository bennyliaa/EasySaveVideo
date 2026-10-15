package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.normalization.MovProfileProbe
import com.easysavevideo.app.save.normalization.OriginalMovFixtures
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.SavePhase
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real owner/protocol/descriptor adapter, deterministic host syscalls; not Android acceptance. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class NormalizationOffLockIoTest {
    private data class Barrier(val name: String, val matches: (MemoryNormalizationDescriptorIo.Call, NormalizationDescriptorFixture) -> Boolean)
    private val preparationBarriers = listOf(
        Barrier("initial-stat") { c, f -> c.operation == "lstat" && c.path == f.root },
        Barrier("initial-open") { c, f -> c.operation == "open" && c.path == f.primary },
        Barrier("raw-fsync") { c, f -> c.operation == "fsync" && c.path == f.primary },
        Barrier("raw-pread") { c, f -> c.operation == "pread" && c.path == f.primary },
        Barrier("raw-close") { c, f -> c.operation == "close" && c.path == f.primary },
        Barrier("directory-enumeration-open") { c, _ -> c.operation == "children" },
        Barrier("directory-enumeration-close") { c, _ -> c.operation == "childrenClose" },
        Barrier("output-create-open") { c, _ -> c.operation == "open" && c.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE },
        Barrier("directory-open") { c, _ -> c.operation == "open" && c.mode == NormalizationDescriptorIo.Open.DIRECTORY },
        Barrier("directory-fsync") { c, _ -> c.operation == "fsync" && c.descriptor?.mode == NormalizationDescriptorIo.Open.DIRECTORY },
        Barrier("directory-close") { c, _ -> c.operation == "close" && c.descriptor?.mode == NormalizationDescriptorIo.Open.DIRECTORY },
    )

    @Test fun everyPreparationBlockingIoRetainsReservationButFreesStorageMonitorAndRejectsLateSuccess() {
        for (barrier in preparationBarriers) {
            val f = NormalizationDescriptorFixture(); val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            val stoppedOnce = AtomicBoolean(); val success = AtomicBoolean(); val failure = AtomicReference<Throwable>()
            f.io.before = { call ->
                assertFalse("${barrier.name}: syscall ran under global storage monitor", Thread.holdsLock(f.lock))
                if (barrier.matches(call, f) && stoppedOnce.compareAndSet(false, true)) {
                    entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
                }
            }
            val pool = Executors.newFixedThreadPool(2)
            val worker = pool.submit {
                try { f.prepare().use { success.set(true) } } catch (t: Throwable) { failure.set(t) }
            }
            try {
                assertTrue(barrier.name, entered.await(2, TimeUnit.SECONDS))
                assertTrue(f.attempts.hasAnyLeases()); assertTrue(f.attempts.hasActiveSession(f.owner.artifactId))
                assertFalse("Healthy active work must not block itself", f.attempts.resourcesUnreleased())
                val probe = pool.submit<Boolean> {
                    // Simulates another task's bounded durable checkpoint using the SAME monitor.
                    synchronized(f.lock) {
                        f.files.reservations.requireLegacyAvailable(taskId = "other", artifactId = "other", commitId = "other")
                    }
                    f.cancellation.cancel()
                    f.attempts.resourcesUnreleased()
                }
                assertTrue(barrier.name, probe.get(1, TimeUnit.SECONDS))
                assertEquals(SaveError.BUSY, assertThrows(SaveFailure::class.java) { f.files.reservations.requireNoReservations() }.code)
                for (guard in listOf<() -> Unit>(
                    { f.files.reservations.requireLegacyAvailable(taskId = f.owner.taskId) },
                    { f.files.reservations.requireLegacyAvailable(artifactId = f.owner.artifactId) },
                    { f.files.reservations.requireLegacyAvailable(commitId = f.owner.commitId) },
                )) assertThrows(NormalizationRecovery::class.java) { guard() }
            } finally { resume.countDown(); worker.get(3, TimeUnit.SECONDS); pool.shutdownNow() }
            assertFalse(barrier.name, success.get()); assertNotNull(failure.get())
            assertFalse(barrier.name, f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
            assertTrue(f.io.handles.all { !it.active })
            assertTrue(f.io.handles.all { fd -> f.io.calls.count { it.operation == "close" && it.descriptor === fd } == 1 })
            assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        }
    }

    @Test fun absentLedgerRawProbeOpenHashAndCloseAreOffLockAndCannotHandoffAfterCancellation() {
        for (blocked in listOf("open", "pread", "close")) {
            val f = NormalizationDescriptorFixture(); val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            val token = f.files.reservations.reserve(f.owner, NormalizationIoReservations.Stage.RAW_PROBE, { f.checkOperation(f.context) })
            val pool = Executors.newFixedThreadPool(2); val probeEntered = AtomicBoolean(); val once = AtomicBoolean(); val success = AtomicBoolean()
            f.io.before = {
                assertFalse(Thread.holdsLock(f.lock))
                if (it.operation == blocked && it.path == f.primary && (blocked != "close" || probeEntered.get()) && once.compareAndSet(false, true)) {
                    entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
                }
            }
            val worker = pool.submit {
                try {
                    f.attempts.probeRaw(f.owner, f.context, token) { input ->
                        probeEntered.set(true); input.readAt(0, ByteArray(4), 0, 4); true
                    }
                    success.set(true)
                } catch (_: Exception) { }
                finally { f.files.reservations.retire(token) }
            }
            try {
                assertTrue(blocked, entered.await(2, TimeUnit.SECONDS)); assertNull(f.ledger.row)
                assertTrue(pool.submit<Boolean> { synchronized(f.lock) { true } }.get(1, TimeUnit.SECONDS))
                f.cancellation.cancel(); assertTrue(f.attempts.resourcesUnreleased())
                assertThrows(NormalizationRecovery::class.java) { f.files.reservations.requireLegacyAvailable(artifactId = f.owner.artifactId) }
            } finally { resume.countDown(); worker.get(3, TimeUnit.SECONDS); pool.shutdownNow() }
            assertFalse(success.get()); assertFalse(f.attempts.hasAnyLeases())
            assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE })
        }
    }

    @Test fun rawProbeToPrepareRetainsSameTokenAndOnlyBindsOneGeneration() {
        val f = NormalizationDescriptorFixture()
        val token = f.files.reservations.reserve(f.owner, NormalizationIoReservations.Stage.RAW_PROBE, { f.checkOperation(f.context) })
        val actual = f.attempts.probeRaw(f.owner, f.context, token) { input -> ByteArray(5).also { input.readAt(0, it, 0, it.size) } }
        assertArrayEquals(f.rawBytes.copyOf(5), actual); assertNull(f.ledger.row); assertNull(token.key)
        assertTrue(f.io.handles.all { !it.active }); assertTrue(f.attempts.hasAnyLeases())
        assertThrows(NormalizationRecovery::class.java) { f.files.reservations.requireLegacyAvailable(artifactId = f.owner.artifactId) }
        val attempt = f.attempts.prepare(f.owner, f.context, f.limits, token)
        assertSame(token, attempt.reservation); assertEquals(attempt.key, token.key)
        assertThrows(NormalizationRecovery::class.java) { f.files.reservations.bindGeneration(token, f.knownKey) }
        attempt.close(); assertFalse(f.attempts.hasAnyLeases())
    }

    @Test fun selectedRawIdentityAndHashMustStillMatchBeforeAnyOutputCreation() {
        for (replacement in listOf("new-inode", "changed-bytes", "changed-during-probe")) {
            val f = NormalizationDescriptorFixture()
            val token = f.files.reservations.reserve(f.owner, NormalizationIoReservations.Stage.RAW_PROBE, { f.checkOperation(f.context) })
            if (replacement == "changed-during-probe") {
                assertThrows(Exception::class.java) {
                    f.attempts.probeRaw(f.owner, f.context, token) {
                        f.io.mutate(f.primary, f.rawBytes.copyOf().also { bytes -> bytes[0] = 99 }, preserveTimes = true)
                    }
                }
                f.attempts.closeRawSelection(token)
            } else {
                f.attempts.probeRaw(f.owner, f.context, token) { it.length }
                if (replacement == "new-inode") f.io.file(f.primary, f.rawBytes)
                else f.io.mutate(f.primary, f.rawBytes.copyOf().also { bytes -> bytes[0] = 99 }, preserveTimes = true)
                assertThrows(Exception::class.java) { f.attempts.prepare(f.owner, f.context, f.limits, token) }
                f.attempts.closeRawSelection(token)
            }
            assertNull(f.ledger.row); assertFalse(f.attempts.hasAnyLeases())
            assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE })
        }
    }

    private fun recoveredRawComplete(emptyOutput: Boolean = false): NormalizationDescriptorFixture {
        val bytes = OriginalMovFixtures.build()
        return NormalizationDescriptorFixture(rawSize = bytes.size).also { f ->
            f.io.file(f.primary, bytes)
            f.ledger.row = NormalizationWorkspace(f.knownKey, 1, NormalizationLifecycle.RAW_COMPLETE,
                NormalizationDisposition.CONTINUE, normalizationTestFingerprint(bytes), f.sourceDigest)
            if (emptyOutput) { f.io.directory(f.generation(f.knownKey)); f.io.file(f.outputFile(f.knownKey), byteArrayOf()) }
        }
    }
    private fun recoveredToken(f: NormalizationDescriptorFixture) = f.files.reservations.reserve(f.owner,
        NormalizationIoReservations.Stage.PREPARE, { f.checkOperation(f.context) }, f.knownKey)
    private fun strictRecoveredProbe(f: NormalizationDescriptorFixture, token: NormalizationIoReservations.Token) =
        f.attempts.probeRaw(f.owner, f.context, token) { input -> MovProfileProbe.probeQuickTime(input) { f.checkOperation(f.context) } }

    @Test fun exactRawCompleteRecoveryStrictProbeRetainsGenerationAndRequiresFreshAcknowledgedWriterCas() {
        for (emptyOutput in listOf(false, true)) {
            val f = recoveredRawComplete(emptyOutput); val token = recoveredToken(f); val initial = f.ledger.row
            val profile = strictRecoveredProbe(f, token)
            assertEquals(3, profile.samples.size); assertEquals(initial, f.ledger.row)
            assertTrue(f.io.handles.all { !it.active })
            assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE || it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
            val attempt = f.attempts.prepare(f.owner, f.context, f.limits, token)
            assertSame(token, attempt.reservation); assertEquals(f.knownKey, attempt.key)
            assertEquals(2L, f.ledger.row!!.revision); assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
            f.attempts.closeRawSelection(token); token.requireLive()
            val source = attempt.openSource(); attempt.openEmptyOutput().close(); source.close(); attempt.close()
            assertFalse(f.attempts.hasAnyLeases())
            assertEquals(1, f.io.calls.count { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        }
    }

    @Test fun recoveryRequiresCompletedProbeOfExactRawCompleteRowBeforeAnyOutputCreation() {
        for (change in listOf("no-probe", "row-revision", "new-inode", "changed-bytes", "wrong-durable-hash")) {
            val f = recoveredRawComplete(); val token = recoveredToken(f)
            if (change == "wrong-durable-hash") {
                f.ledger.row = f.ledger.row!!.copy(raw = f.ledger.row!!.raw.copy(sha256 = "f".repeat(64)))
                assertThrows(NormalizationRecovery::class.java) { strictRecoveredProbe(f, token) }
            } else {
                if (change != "no-probe") strictRecoveredProbe(f, token)
                when (change) {
                    "row-revision" -> f.ledger.row = f.ledger.row!!.copy(revision = 2)
                    "new-inode" -> f.io.file(f.primary, f.io.bytes(f.primary))
                    "changed-bytes" -> f.io.mutate(f.primary, f.io.bytes(f.primary).also { it[it.lastIndex] = (it.last() + 1).toByte() }, preserveTimes = true)
                }
                assertThrows(NormalizationRecovery::class.java) { f.attempts.prepare(f.owner, f.context, f.limits, token) }
            }
            f.attempts.closeRawSelection(token)
            assertFalse(f.attempts.hasAnyLeases()); assertEquals(NormalizationLifecycle.RAW_COMPLETE, f.ledger.row!!.lifecycle)
            assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE || it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        }
    }

    @Test fun recoveredWritingNeverRunsProbeOrReissuesWriterEvenWithPrepareReservation() {
        for (output in listOf<ByteArray?>(null, byteArrayOf(), byteArrayOf(1, 2, 3))) {
            val f = NormalizationDescriptorFixture(); f.seedWriting(output); val token = recoveredToken(f)
            assertThrows(NormalizationRecovery::class.java) { strictRecoveredProbe(f, token) }
            assertThrows(NormalizationRecovery::class.java) { f.attempts.prepare(f.owner, f.context, f.limits, token) }
            f.attempts.closeRawSelection(token)
            assertTrue(f.io.calls.isEmpty()); assertFalse(f.attempts.hasAnyLeases())
            assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        }
    }

    @Test fun recoveredRawProbeOpenHashAndCloseAreOffLockAndCannotHandoffAfterCancellation() {
        for (blocked in listOf("open", "pread", "close")) {
            val f = recoveredRawComplete(); val initial = f.ledger.row; val token = recoveredToken(f)
            val entered = CountDownLatch(1); val resume = CountDownLatch(1); val probeEntered = AtomicBoolean()
            val once = AtomicBoolean(); val success = AtomicBoolean(); val pool = Executors.newFixedThreadPool(2)
            f.io.before = { c ->
                assertFalse(Thread.holdsLock(f.lock))
                if (c.operation == blocked && c.path == f.primary && (blocked != "close" || probeEntered.get()) && once.compareAndSet(false, true)) {
                    entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
                }
            }
            val worker = pool.submit {
                try {
                    f.attempts.probeRaw(f.owner, f.context, token) { input -> probeEntered.set(true); MovProfileProbe.probeQuickTime(input) }
                    success.set(true)
                } catch (_: Exception) { }
                finally { f.attempts.closeRawSelection(token) }
            }
            try {
                assertTrue(blocked, entered.await(2, TimeUnit.SECONDS))
                assertTrue(pool.submit<Boolean> { synchronized(f.lock) { true } }.get(1, TimeUnit.SECONDS))
                f.cancellation.cancel(); assertTrue(f.attempts.resourcesUnreleased())
                assertThrows(NormalizationRecovery::class.java) { f.files.reservations.requireLegacyAvailable(artifactId = f.owner.artifactId) }
            } finally { resume.countDown(); worker.get(3, TimeUnit.SECONDS); pool.shutdownNow() }
            assertFalse(success.get()); assertEquals(initial, f.ledger.row); assertFalse(f.attempts.hasAnyLeases())
            assertTrue(f.io.handles.all { !it.active })
            assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE || it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        }
    }

    @Test fun closingRecoveredSelectorRetiresOnlyBeforeOwnershipHandoffEvenWhilePrepareIoIsBlocked() {
        recoveredRawComplete().let { f ->
            val token = recoveredToken(f); strictRecoveredProbe(f, token)
            f.attempts.closeRawSelection(token); f.attempts.closeRawSelection(token)
            assertFalse(f.attempts.hasAnyLeases()); assertEquals(NormalizationLifecycle.RAW_COMPLETE, f.ledger.row!!.lifecycle)
        }
        val f = recoveredRawComplete(); val token = recoveredToken(f); strictRecoveredProbe(f, token)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1); val once = AtomicBoolean(); val success = AtomicBoolean()
        val pool = Executors.newSingleThreadExecutor()
        f.io.before = { c ->
            assertFalse(Thread.holdsLock(f.lock))
            if (c.operation == "fsync" && c.path == f.primary && once.compareAndSet(false, true)) {
                entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
            }
        }
        val worker = pool.submit { f.attempts.prepare(f.owner, f.context, f.limits, token).use { success.set(true) } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); f.attempts.closeRawSelection(token); f.attempts.closeRawSelection(token)
            token.requireLive(); assertTrue(f.attempts.hasAnyLeases()); assertFalse(f.attempts.resourcesUnreleased())
            assertThrows(NormalizationRecovery::class.java) { recoveredToken(f) }
        } finally { resume.countDown(); worker.get(3, TimeUnit.SECONDS); pool.shutdownNow() }
        assertTrue(success.get()); assertFalse(f.attempts.hasAnyLeases())
    }

    @Test fun recoveredFreshWritingCasWithUnknownReturnNeverIssuesWriterOrRestartPermission() {
        val f = recoveredRawComplete(); val token = recoveredToken(f); strictRecoveredProbe(f, token)
        f.ledger.afterWrite = { if (it.lifecycle == NormalizationLifecycle.WRITING) throw IOException("HOST_LOST_CAS_RETURN") }
        assertThrows(NormalizationRecovery::class.java) { f.attempts.prepare(f.owner, f.context, f.limits, token) }
        f.attempts.closeRawSelection(token)
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        assertEquals(0, f.io.bytes(f.outputFile(f.knownKey)).size)
        f.ledger.afterWrite = {}; val retry = recoveredToken(f); val calls = f.io.calls.size
        assertThrows(NormalizationRecovery::class.java) { strictRecoveredProbe(f, retry) }
        f.attempts.closeRawSelection(retry)
        assertEquals(calls, f.io.calls.size); assertFalse(f.attempts.hasAnyLeases())
        assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
    }

    @Test fun mutableOperationCallbackCannotBeOverwrittenByAnotherArtifact() {
        val f = NormalizationDescriptorFixture(); val second = f.owner.copy(taskId = "second", artifactId = "33333333-3333-4333-8333-333333333333", commitId = "second-commit")
        val secondKey = NormalizationKey(second, f.knownKey.normalizationId)
        f.io.file(File(f.staging, second.artifactId + ".part"), f.rawBytes)
        val stopped = AtomicBoolean(); val first = f.files.reservations.reserve(f.owner, NormalizationIoReservations.Stage.RAW_PROBE,
            { if (stopped.get()) throw SaveFailure(SaveError.CANCELLED) })
        val other = f.files.reservations.reserve(second, NormalizationIoReservations.Stage.RAW_PROBE, {})
        val entered = CountDownLatch(1); val resume = CountDownLatch(1); val pool = Executors.newSingleThreadExecutor()
        val firstFailure = AtomicReference<Throwable>()
        f.io.before = { if (it.operation == "pread" && it.path == f.primary) { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) } }
        val task = pool.submit {
            try { f.files.forOperation({}, first).fingerprint(f.knownKey, NormalizationRole.PRIMARY) }
            catch (t: Throwable) { firstFailure.set(t) }
            finally { f.files.reservations.retire(first) }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); stopped.set(true)
            assertEquals(f.raw, f.files.forOperation({}, other).fingerprint(secondKey, NormalizationRole.PRIMARY).fingerprint)
            assertEquals(NormalizationIoReservations.Stage.RAW_PROBE, other.stage)
        } finally { resume.countDown(); task.get(3, TimeUnit.SECONDS); f.files.reservations.retire(other); pool.shutdownNow() }
        assertEquals(SaveError.CANCELLED, (firstFailure.get() as SaveFailure).code)
        assertFalse(f.files.hasAnyLeases()); assertFalse(f.files.releaseUnconfirmed())
    }

    @Test fun foreignCloseOnlyRevokesAndOriginalWorkerClosesEachDescriptorOnce() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); val source = a.openSource(); val output = a.openEmptyOutput()
        val pool = Executors.newSingleThreadExecutor(); f.io.calls.clear()
        try {
            val failure = pool.submit<SaveError> {
                assertThrows(SaveFailure::class.java) { a.close() }.code
            }.get(2, TimeUnit.SECONDS)
            assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, failure)
            assertFalse(f.io.calls.any { it.operation == "close" })
            assertTrue(f.attempts.resourcesUnreleased()); assertFalse(f.attempts.releaseUnconfirmed())
            assertFalse(source.releaseConfirmed); assertFalse(output.releaseConfirmed)
            a.close()
            assertTrue(source.releaseConfirmed); assertTrue(output.releaseConfirmed)
            assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.resourcesUnreleased())
            assertEquals(2, f.io.calls.count { it.operation == "close" })
            assertTrue(f.io.calls.filter { it.operation == "close" }.all { it.thread === Thread.currentThread() })
        } finally { pool.shutdownNow() }
    }

    @Test fun outputReadyProofHandoffRetainsExactReservationUntilTypedCasOwnerClosesIt() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); val raw = a.openSource(); val sink = a.openEmptyOutput()
        sink.write(ByteBuffer.wrap(f.outputBytes)); sink.close(); a.openFinalizedOutput().close(); raw.close()
        val completion = f.attempts.finishOwned(a, f.evidence(), f.context)
        completion.requireOwnedBy(f.attempts)
        assertEquals(NormalizationIoReservations.Stage.PROOF_HANDOFF, completion.reservation.stage)
        assertTrue(f.attempts.hasAnyLeases()); assertFalse(f.attempts.resourcesUnreleased())
        assertTrue(f.io.handles.all { !it.active })
        a.close() // Closing the consumed writer cannot revoke its transferred completion.
        completion.requireOwnedBy(f.attempts)
        assertThrows(NormalizationRecovery::class.java) { f.files.reservations.requireLegacyAvailable(artifactId = f.owner.artifactId) }
        f.record = f.record!!.copy(directNormalization = completion.proof, checkpoint = null)
        assertThrows(NormalizationRecovery::class.java) { completion.requireOwnedBy(f.attempts) }
        completion.close(); completion.close()
        assertFalse(f.attempts.hasAnyLeases())
        assertThrows(NormalizationRecovery::class.java) { completion.requireOwnedBy(f.attempts) }
    }

    @Test fun explicitNoHandleOpenRejectionPreservesSpaceFailureAndCanReleaseWithoutPoisoning() {
        for (atOutput in listOf(false, true)) {
            val f = NormalizationDescriptorFixture()
            f.io.before = { c ->
                if (c.operation == "open" && (if (atOutput) c.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE else c.path == f.primary))
                    throw NormalizationOpenRejected(SaveFailure(SaveError.STORAGE_FULL))
            }
            assertEquals(SaveError.STORAGE_FULL, assertThrows(SaveFailure::class.java) { f.prepare() }.code)
            assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
            assertTrue(f.io.handles.all { !it.active })
            if (atOutput) assertEquals(NormalizationLifecycle.RAW_COMPLETE, f.ledger.row!!.lifecycle) else assertNull(f.ledger.row)
            f.io.before = {}
            f.prepare().close()
            assertFalse(f.attempts.hasAnyLeases())
        }
    }

    @Test fun cancellationAfterKnownOpenRejectionReleasesPendingObligationWithoutCreatingAHandle() {
        val f = NormalizationDescriptorFixture()
        f.io.before = { c ->
            if (c.operation == "open" && c.path == f.primary) {
                f.cancellation.cancel()
                throw NormalizationOpenRejected(IOException("KNOWN_NO_HANDLE"))
            }
        }
        assertEquals(SaveError.CANCELLED, assertThrows(SaveFailure::class.java) { f.prepare() }.code)
        assertTrue(f.io.handles.isEmpty()); assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
        assertEquals(0, f.io.calls.count { it.operation == "close" })
    }

    @Test fun thrownOpenAfterConstructionRetainsUnknownObligationAndCannotBeReplaced() {
        val f = NormalizationDescriptorFixture()
        f.io.after = { if (it.operation == "open" && it.path == f.primary) throw IOException("UNKNOWN_CONSTRUCTION") }
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, assertThrows(SaveFailure::class.java) { f.prepare() }.code)
        assertNull(f.ledger.row); assertTrue(f.attempts.hasAnyLeases()); assertTrue(f.attempts.resourcesUnreleased())
        assertTrue(f.attempts.releaseUnconfirmed()); assertTrue(f.io.handles.single().active)
        f.io.after = {}
        assertThrows(Exception::class.java) { f.prepare() }
        assertEquals(1, f.io.calls.count { it.operation == "open" }); assertEquals(0, f.io.calls.count { it.operation == "close" })
    }

    @Test fun finishHashCloseAndAdoptionRenameSyncAreOffLockAndDoNotPromoteAfterStop() {
        for (phase in listOf("finish-hash", "finish-close", "adopt-sync")) {
            val f = NormalizationDescriptorFixture(); val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            val armed = AtomicBoolean(); val first = AtomicBoolean(); val success = AtomicBoolean()
            val pool = Executors.newFixedThreadPool(2)
            f.io.before = { c ->
                assertFalse(Thread.holdsLock(f.lock))
                val matches = when (phase) {
                    "finish-hash" -> c.operation == "pread" && c.path.name == "output.mp4"
                    "finish-close" -> c.operation == "close" && c.path.name == "output.mp4"
                    else -> c.operation == "fsync" && c.descriptor?.mode == NormalizationDescriptorIo.Open.DIRECTORY
                }
                if (armed.get() && matches && first.compareAndSet(false, true)) { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            }
            val worker = pool.submit {
                val a = f.prepare()
                try {
                    val raw = a.openSource(); val output = a.openEmptyOutput(); output.write(ByteBuffer.wrap(f.outputBytes)); output.close()
                    a.openFinalizedOutput().close(); raw.close()
                    if (phase.startsWith("finish")) armed.set(true)
                    val proof = f.attempts.finish(a, f.evidence(), f.context)
                    if (phase == "adopt-sync") {
                        f.record = f.record!!.copy(phase = SavePhase.ADOPTING, directNormalization = proof)
                        f.authority = f.authority.copy(proofSha256 = proof.digest())
                        f.attempts.recordAdoptionIntent(a.key, proof, f.context)
                        armed.set(true); f.attempts.adopt(a.key, f.context)
                    }
                    success.set(true)
                } catch (_: Exception) { }
                finally { a.close() }
            }
            try {
                assertTrue(phase, entered.await(2, TimeUnit.SECONDS))
                assertTrue(pool.submit<Boolean> { synchronized(f.lock) { true } }.get(1, TimeUnit.SECONDS))
                f.cancellation.cancel(); assertTrue(f.attempts.resourcesUnreleased())
            } finally { resume.countDown(); worker.get(3, TimeUnit.SECONDS); pool.shutdownNow() }
            assertFalse(phase, success.get()); assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
            assertNotEquals(NormalizationLifecycle.ADOPTED, f.ledger.row!!.lifecycle)
        }
    }

    @Test fun cleanupUnlinkAndDirectorySyncNeverHoldStorageMonitorAndStopOnExactRecordChange() {
        for (operation in listOf("unlink", "fsync", "childrenClose")) {
            val f = NormalizationDescriptorFixture(); val attempt = f.prepare(); val key = attempt.key; attempt.close()
            f.record = f.record!!.copy(task = f.record!!.task.copy(state = TaskState.CANCELLED))
            f.authority = f.authority.copy(taskState = NormalizationTaskState.CANCELLED)
            val entered = CountDownLatch(1); val resume = CountDownLatch(1); val once = AtomicBoolean(); val pool = Executors.newFixedThreadPool(2)
            f.io.before = { c ->
                assertFalse(Thread.holdsLock(f.lock))
                if (c.operation == operation && once.compareAndSet(false, true)) { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            }
            val worker = pool.submit { try { f.attempts.cleanup(key, f.ledger.row!!.revision) } catch (_: Exception) { } }
            try {
                assertTrue(operation, entered.await(2, TimeUnit.SECONDS))
                assertTrue(pool.submit<Boolean> { synchronized(f.lock) { f.record = f.record!!.let { it.copy(task = it.task.copy(revision = it.task.revision + 1)) }; true } }.get(1, TimeUnit.SECONDS))
                assertTrue(f.attempts.hasAnyLeases())
            } finally { resume.countDown(); worker.get(3, TimeUnit.SECONDS); pool.shutdownNow() }
            assertFalse(f.attempts.hasAnyLeases()); assertNotEquals(NormalizationLifecycle.CLEANED, f.ledger.row!!.lifecycle)
        }
    }
}
