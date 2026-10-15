package com.easysavevideo.app.editor.verification

import com.easysavevideo.app.media.verification.*
import com.easysavevideo.library.edit.*
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real file/read-owner cleanup tests with a controlled native body. No Android decode is executed. */
class EditBorrowedVerificationOwnerTest {
    private val output = EditOutputRef("verification-operation", "verification-artifact")
    private val expected = EditOutputExpectation(120_000, 16, 16, 25, EditAudioProfile.Silent, 40_000, 0)
    private val observed = AvcAacOutputObservations(120_000, 16, 16, 0, 0, 80_000, 3, 0, 3, 0, 0)

    private inner class Read(val file: File, val events: MutableList<String>) : EditOwnedOutputRead {
        override val output = this@EditBorrowedVerificationOwnerTest.output
        override val expectedBytes = file.length()
        override val input = FileInputStream(file)
        var closes = 0
        var retains = 0
        var failClose = false
        override fun closeAfterNativeRelease() {
            closes++; events.add("close")
            if (failClose) throw IOException("test close failure")
            input.close()
        }
        override fun retainForProcessRecovery() { retains++; events.add("retain") }
    }
    private fun fixture(action: (Read, MutableList<String>) -> Unit) {
        val file = File.createTempFile("esv-borrowed-owner", ".bin")
        file.writeBytes(ByteArray(128) { it.toByte() })
        val events = arrayListOf<String>()
        val read = Read(file, events)
        try { action(read, events) }
        finally { read.input.close(); file.delete() } // Test-only disposal after all ownership assertions.
    }
    private fun failure(code: EditError, action: () -> Unit) {
        try { action(); fail("Expected failure") }
        catch (actual: EditFailure) { assertEquals(code, actual.code) }
    }
    private fun owner(read: Read, body: (BorrowedAvcAacRead, AvcAacNativeResources, () -> Unit) -> AvcAacOutputObservations) =
        EditVerificationReadOwner({ read.events.add("open"); read }, { borrowed ->
            BorrowedAvcAacInspection(borrowed) { actualRead, resources, actualExpected, checkpoint ->
                assertSame(read.input.fd, actualRead.originalFd)
                assertSame(read.input.channel, actualRead.channel)
                assertEquals(read.expectedBytes, actualRead.extentBytes)
                assertEquals(expected.inspectionExpectation(), actualExpected)
                body(actualRead, resources, checkpoint)
            }
        })

    @Test fun actualOwnedReadReachesCoreThenNativeReleaseFinalHashAndSoleClose() = fixture { read, events ->
        var released = false
        val owner = owner(read) { borrowed, resources, _ ->
            events.add("inspect")
            assertTrue(borrowed.originalFd.valid())
            val codec = Any()
            resources.add(codec, stop = { events.add("stop") }, release = { events.add("native-release"); released = true })
            resources.started(codec)
            observed
        }
        val evidence = owner.inspect(output, expected) {
            if (released) { assertTrue(read.input.fd.valid()); events.add("final-check") }
        }
        assertFalse(evidence.nativeReleaseConfirmed)
        assertFalse(evidence.validFor(output, expected))
        assertEquals(0, read.closes)
        assertTrue(owner.releaseAll()); assertTrue(owner.releaseAll())
        assertEquals(1, read.closes); assertEquals(0, read.retains)
        assertFalse(read.input.fd.valid())
        assertTrue(events.indexOf("stop") < events.indexOf("native-release"))
        assertTrue(events.indexOf("native-release") < events.indexOf("final-check"))
        assertTrue(events.lastIndexOf("final-check") < events.indexOf("close"))
        assertTrue(evidence.copy(nativeReleaseConfirmed = true).validFor(output, expected))
    }

    @Test fun mutationDuringActualNativeReleaseIsCaughtByLaterOriginalFdHash() = fixture { read, events ->
        val owner = owner(read) { _, resources, _ ->
            resources.add(Any(), release = {
                events.add("native-release")
                RandomAccessFile(read.file, "rw").use { it.seek(0); it.write(99) }
            })
            observed
        }
        failure(EditError.VERIFICATION_FAILED) { owner.inspect(output, expected) {} }
        assertTrue(owner.releaseAll()); assertTrue(owner.releaseAll())
        assertEquals(listOf("open", "native-release", "close"), events)
        assertEquals(1, read.closes); assertEquals(0, read.retains)
    }

    @Test fun stopSuccessReleaseFailureIsStickyAndRetainsOriginalReadWithoutCloseOrFinalHash() = fixture { read, events ->
        var afterNative = false
        val owner = owner(read) { _, resources, _ ->
            val codec = Any()
            resources.add(codec, stop = { events.add("stop") }, release = {
                events.add("native-release"); afterNative = true; throw IOException("test native failure")
            })
            resources.started(codec)
            observed
        }
        failure(EditError.RELEASE_UNCONFIRMED) {
            owner.inspect(output, expected) { assertFalse("No final hash after uncertain release", afterNative) }
        }
        assertFalse(owner.releaseAll()); assertFalse(owner.releaseAll())
        assertTrue(read.input.fd.valid())
        assertEquals(0, read.closes); assertEquals(1, read.retains)
        assertEquals(listOf("open", "stop", "native-release", "retain"), events)
    }

    @Test fun nativeReleaseConfirmedButReadCloseFailureRetainsAndNeverRetriesClose() = fixture { read, events ->
        read.failClose = true
        val owner = owner(read) { _, resources, _ ->
            resources.add(Any(), release = { events.add("native-release") }); observed
        }
        val evidence = owner.inspect(output, expected) {}
        assertFalse(owner.releaseAll()); assertFalse(owner.releaseAll())
        assertFalse(evidence.nativeReleaseConfirmed)
        assertEquals(1, read.closes); assertEquals(1, read.retains)
        assertEquals(listOf("open", "native-release", "close", "retain"), events)
        assertTrue(read.input.fd.valid())
    }

    @Test fun ordinaryInspectionFailureStillReleasesNativeThenClosesExactlyOnce() = fixture { read, events ->
        val owner = owner(read) { _, resources, _ ->
            resources.add(Any(), release = { events.add("native-release") })
            throw AvcAacVerificationFailure(AvcAacVerificationError.UNSUPPORTED_PROFILE)
        }
        failure(EditError.UNSUPPORTED_PROFILE) { owner.inspect(output, expected) {} }
        assertTrue(owner.releaseAll()); assertTrue(owner.releaseAll())
        assertEquals(listOf("open", "native-release", "close"), events)
        assertEquals(1, read.closes); assertEquals(0, read.retains)
    }

    @Test fun stopFailureWithSuccessfulReleasePreservesVerificationFailureWithoutRecovery() = fixture { read, events ->
        val owner = owner(read) { _, resources, _ ->
            val codec = Any()
            resources.add(codec, stop = { events.add("stop"); throw IOException("test stop failure") },
                release = { events.add("native-release") })
            resources.started(codec)
            resources.release(codec)
            observed
        }
        failure(EditError.VERIFICATION_FAILED) { owner.inspect(output, expected) {} }
        assertTrue(owner.releaseAll())
        assertEquals(listOf("open", "stop", "native-release", "close"), events)
        assertEquals(1, read.closes); assertEquals(0, read.retains)
    }

    @Test fun finallyOnlyStopFailureStillClosesEditorReadOnceWithoutPoisoningOwnership() = fixture { read, events ->
        val owner = owner(read) { _, resources, _ ->
            val codec = Any()
            resources.add(codec, stop = { events.add("stop"); throw IOException("test stop failure") },
                release = { events.add("native-release") })
            resources.started(codec)
            observed
        }
        failure(EditError.VERIFICATION_FAILED) { owner.inspect(output, expected) {} }
        assertTrue(owner.releaseAll()); assertTrue(owner.releaseAll())
        assertEquals(listOf("open", "stop", "native-release", "close"), events)
        assertEquals(1, read.closes); assertEquals(0, read.retains)
    }

    @Test fun runningCancellationReleasesNativeThenClosesWithoutSuccessfulEvidence() = fixture { read, events ->
        lateinit var worker: EditVerificationReadOwner
        worker = owner(read) { _, resources, checkpoint ->
            resources.add(Any(), release = { events.add("native-release") })
            worker.abandoned = true
            checkpoint()
            observed
        }
        failure(EditError.CANCELLED) { worker.inspect(output, expected) {} }
        assertTrue(worker.releaseAll())
        assertEquals(listOf("open", "native-release", "close"), events)
        assertEquals(1, read.closes); assertEquals(0, read.retains)
    }

    @Test fun cancellationAfterReleaseStillClosesAndCannotReturnEvidence() = fixture { read, events ->
        lateinit var worker: EditVerificationReadOwner
        worker = owner(read) { _, resources, _ ->
            resources.add(Any(), release = { events.add("native-release"); worker.abandoned = true })
            observed
        }
        failure(EditError.CANCELLED) { worker.inspect(output, expected) {} }
        assertTrue(worker.releaseAll())
        assertEquals(listOf("open", "native-release", "close"), events)
    }

    @Test fun queuedCancellationAcknowledgesExistingGateWithoutOpeningAnyRead() {
        var opens = 0
        val owner = EditVerificationReadOwner({ opens++; error("Queued cancellation must not open") })
        val gate = EditVerificationNativeGate()
        assertTrue(gate.acquire(owner))
        gate.abandon(owner); owner.abandoned = true
        assertTrue(gate.recoveryRequired)
        assertFalse(gate.start(owner))
        val confirmed = owner.releaseAll()
        gate.finish(owner, confirmed); owner.cleanup.finished(confirmed)
        assertTrue(owner.cleanup.awaitConfirmed(1))
        assertEquals(0, opens); assertFalse(gate.occupied)
    }

    @Test fun actuallyQueuedCancellationKeepsReadUnopenedUntilWorkerAcknowledges() {
        val lane = Executors.newSingleThreadExecutor()
        val occupying = CountDownLatch(1)
        val started = CountDownLatch(1)
        var opens = 0
        val owner = EditVerificationReadOwner({ opens++; error("Abandoned queued worker must not open") })
        val gate = EditVerificationNativeGate()
        try {
            lane.submit { started.countDown(); occupying.await() }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(gate.acquire(owner))
            val future = lane.submit<Boolean> {
                val allowed = gate.start(owner)
                try {
                    if (allowed) owner.inspect(output, expected) {}
                } finally {
                    val confirmed = owner.releaseAll()
                    gate.finish(owner, confirmed); owner.cleanup.finished(confirmed)
                }
                allowed
            }
            gate.abandon(owner); owner.abandoned = true
            assertTrue(gate.occupied); assertTrue(gate.recoveryRequired)
            assertFalse(future.isDone); assertEquals(0, opens)
            occupying.countDown()
            assertFalse(future.get(1, TimeUnit.SECONDS))
            assertTrue(owner.cleanup.awaitConfirmed(1))
            assertFalse(gate.occupied); assertEquals(0, opens)
        } finally { occupying.countDown(); lane.shutdownNow(); lane.awaitTermination(1, TimeUnit.SECONDS) }
    }

    @Test fun failedReleaseKeepsExistingGateFencedEvenAfterWorkerFinishes() = fixture { read, _ ->
        val owner = owner(read) { _, resources, _ ->
            resources.add(Any(), release = { throw IOException("test release failure") }); observed
        }
        val gate = EditVerificationNativeGate()
        assertTrue(gate.acquire(owner)); assertTrue(gate.start(owner))
        failure(EditError.RELEASE_UNCONFIRMED) { owner.inspect(output, expected) {} }
        val confirmed = owner.releaseAll()
        gate.finish(owner, confirmed); owner.cleanup.finished(confirmed)
        assertFalse(owner.cleanup.awaitConfirmed(1))
        assertTrue(gate.recoveryRequired); assertFalse(gate.acquire(Any()))
        assertEquals(0, read.closes); assertEquals(1, read.retains)
    }

    @Test fun expectationMappingKeepsAllExistingNumbersAndRecordedPrimingUntouched() {
        val recorded = expected.copy(audio = EditAudioProfile.Aac(48_000, 2), durationToleranceUs = 7_654,
            audioPrimingToleranceUs = 900_000, maxBytes = 123_456, videoFrameCount = 2, recordedSegmentCount = 32)
        val mapped = recorded.inspectionExpectation()
        assertEquals(recorded.durationUs, mapped.durationUs)
        assertEquals(recorded.width, mapped.width); assertEquals(recorded.height, mapped.height)
        assertEquals(recorded.frameRate, mapped.frameRate); assertEquals(recorded.videoFrameCount, mapped.videoFrameCount)
        assertEquals(recorded.durationToleranceUs, mapped.durationToleranceUs)
        assertEquals(recorded.audioPrimingToleranceUs, mapped.audioPrimingToleranceUs)
        assertEquals(recorded.maxBytes, mapped.maxBytes)
        assertEquals(AvcAacAudioProfile.Aac(48_000, 2), mapped.audio)
        assertSame(AvcAacAudioProfile.Silent, expected.inspectionExpectation().audio)
    }

    @Test fun everyNeutralTechnicalCodeKeepsItsEditorCode() {
        val codes = mapOf(AvcAacVerificationError.VERIFICATION_FAILED to EditError.VERIFICATION_FAILED,
            AvcAacVerificationError.UNSUPPORTED_PROFILE to EditError.UNSUPPORTED_PROFILE,
            AvcAacVerificationError.TIMEOUT to EditError.TIMEOUT,
            AvcAacVerificationError.RELEASE_UNCONFIRMED to EditError.RELEASE_UNCONFIRMED)
        codes.forEach { (neutral, editor) -> failure(editor) { editVerification { throw AvcAacVerificationFailure(neutral) } } }
        val cancellation = EditFailure(EditError.CANCELLED)
        try { editVerification { throw cancellation } } catch (actual: EditFailure) { assertSame(cancellation, actual) }
    }
}
