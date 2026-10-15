package com.easysavevideo.app.save.storage

import com.easysavevideo.app.media.transform.*
import com.easysavevideo.app.save.conversion.OwnedWebmInputBinding
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production owner/session/writer with a deterministic syscall seam. No native/media acceptance. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class OwnedDirectTranscodeOutputTest {
    private class Bound(val h: OwnedTranscodeTestFixture) {
        val attempt = h.prepare()
        val binding = attempt.bindWebmInputOnce()
        val session = h.session()
        val source = attempt.ownedSource(binding)
        init { attempt.bindSession(binding, session) }
        fun enter(body: (TransformerOwnedTarget, TransformerWriter) -> Unit): TransformerOutcome<Unit> = h.entered(session) {
            session.retain(source)
            val target = attempt.ownedTarget(binding)
            val writer = target.openEmptyWriter()
            session.retainWriter(target)
            body(target, writer)
        }
        fun finish(writer: TransformerWriter) { writer.beginFinalization(); writer.force(); writer.close(); assertTrue(writer.releaseConfirmed) }
        fun outputHandle() = h.f.io.handles.single { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING }
        fun checkNoProof() {
            assertEquals(NormalizationLifecycle.WRITING, h.f.ledger.row!!.lifecycle)
            assertNull(h.f.record!!.directConversion); assertNull(h.f.record!!.directNormalization)
            assertNull(h.f.ledger.row!!.output); assertNull(h.f.ledger.row!!.proofSha256)
        }
    }
    @Test fun registeredTargetAndBothHashesUseOnlyTheirOriginalAnchors() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h); val bytes = ByteArray(131073) { (it % 239).toByte() }
        val raw = h.f.io.handles.single { it.active }
        assertTrue(b.enter { target, writer ->
            assertSame(target, b.attempt.ownedTarget(b.binding))
            assertEquals(0L, target.sizeBytes())
            assertEquals(bytes.size, writer.write(ByteBuffer.wrap(bytes)))
            b.finish(writer)
            assertTrue(raw.active); assertTrue(b.outputHandle().active)
            assertThrows(TransformerFailure::class.java) { b.session.requireWriterRelease(target) }
        } is TransformerOutcome.Completed)
        assertTrue(raw.active); assertTrue(b.outputHandle().active)
        val opens = h.f.io.calls.count { it.operation == "open" }
        h.f.io.calls.clear()
        val observations = b.attempt.sealResources()
        assertEquals(h.f.raw, observations.first.fingerprint)
        assertEquals(normalizationTestFingerprint(bytes), observations.second.fingerprint)
        assertTrue(h.f.io.calls.filter { it.operation == "pread" }.all { it.descriptor === raw || it.descriptor === b.outputHandle() })
        assertFalse(h.f.io.calls.any { it.operation == "open" })
        assertTrue(opens > 0)
        assertFalse(raw.active); assertFalse(b.outputHandle().active)
        assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === raw })
        assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === b.outputHandle() })
        assertFalse(h.f.files.hasLeases(b.attempt.key)); assertTrue(h.token.registered)
        assertEquals(NormalizationIoReservations.Stage.TRANSCODE, h.token.stage)
        assertThrows(Exception::class.java) { b.attempt.sealResources() }
        assertThrows(Exception::class.java) { b.attempt.borrowedWebmInput(b.binding) }
        b.checkNoProof(); b.attempt.close(); assertFalse(h.f.attempts.hasAnyLeases())
    }
    @Test fun unenteredForeignAndRepeatedWriterAreRejectedWithoutReopen() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h); val opens = h.f.io.calls.count { it.operation == "open" }
        assertThrows(TransformerFailure::class.java) { b.attempt.ownedTarget(b.binding) }
        assertThrows(NormalizationRecovery::class.java) { b.attempt.ownedTarget(OwnedWebmInputBinding.createUnregistered(b.attempt)) }
        assertEquals(opens, h.f.io.calls.count { it.operation == "open" })
        b.enter { target, writer ->
            writer.write(ByteBuffer.wrap(byteArrayOf(1))); writer.truncate(0)
            assertThrows(Exception::class.java) { target.openEmptyWriter() }
            writer.close()
        }
        assertEquals(1, h.f.io.calls.count { it.operation == "open" && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        b.attempt.close(); b.checkNoProof()
    }
    @Test fun exactExtentCapIncludesSparsePositionsAndRejectsOverflowBeforeMutation() {
        for (tooMuch in listOf(false, true)) OwnedTranscodeTestFixture(32).also { fixture ->
            fixture.beforePrepare = { fixture.f.limits = fixture.f.limits.copy(maxArtifactBytes = 32) }
        }.run { h, _ ->
            val b = Bound(h); assertEquals(32L, b.attempt.outputMaximum)
            b.enter { _, writer ->
                writer.position = 31
                if (tooMuch) {
                    val writes = h.f.io.calls.count { it.operation == "pwrite" }
                    assertThrows(Exception::class.java) { writer.write(ByteBuffer.wrap(byteArrayOf(1, 2))) }
                    assertEquals(writes, h.f.io.calls.count { it.operation == "pwrite" }); writer.close()
                } else {
                    writer.write(ByteBuffer.wrap(byteArrayOf(1))); assertEquals(32L, writer.size); b.finish(writer)
                }
            }
            if (!tooMuch) assertEquals(32L, b.attempt.sealResources().second.fingerprint.size)
            b.attempt.close(); b.checkNoProof()
        }
        for (position in listOf(-1L, Long.MAX_VALUE)) OwnedTranscodeTestFixture().run { h, _ ->
            val b = Bound(h)
            b.enter { _, writer ->
                val mutations = h.f.io.calls.count { it.operation == "pwrite" || it.operation == "truncate" }
                assertThrows(Exception::class.java) { writer.position = position }
                assertEquals(mutations, h.f.io.calls.count { it.operation == "pwrite" || it.operation == "truncate" }); writer.close()
            }
            b.attempt.close()
        }
    }
    @Test fun forgedOutputAndAuthenticRawObligationCannotEnterOutputCleanup() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        b.enter { _, writer ->
            val output = b.outputHandle()
            val identity = h.f.files.capture(b.attempt.key, NormalizationRole.OUTPUT, empty = true)
            val forged = h.f.files.AsyncOutputDescriptor(b.attempt.key, output, identity,
                NormalizationIoReservations.Obligation.issue(h.token), h.token, b.attempt, {})
            val rawAnchor = requireNotNull(field(b.attempt, "anchor"))
            val rawObligation = requireNotNull(rawAnchor.javaClass.superclass).getDeclaredField("obligation").apply { isAccessible = true }.get(rawAnchor) as NormalizationIoReservations.Obligation
            val actualOutput = field(b.attempt, "outputAnchor") as AndroidNormalizationFiles.AsyncOutputDescriptor
            val count = h.f.io.calls.size
            assertThrows(Exception::class.java) { forged.verifyIdentity(OwnedDirectTranscodeAttempt.OutputPhase.ACTIVE) }
            assertThrows(Exception::class.java) { forged.requestClose() }
            assertThrows(Exception::class.java) { actualOutput.requireFinalizationObligation(rawObligation) }
            assertThrows(Exception::class.java) { h.f.attempts.reservations.withTranscodeFinalizationIo(forged, rawObligation) { error("UNAUTHORIZED_IO") } }
            assertEquals(count, h.f.io.calls.size); assertTrue(output.active)
            writer.write(ByteBuffer.wrap(byteArrayOf(1))); b.finish(writer)
        }
        b.attempt.sealResources(); b.attempt.close(); assertFalse(h.f.attempts.releaseUnconfirmed())
    }
    @Test fun directLowLevelOpenCannotMintSecondAnchorFromGenuineAttemptOrToken() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        val empty = field(b.attempt, "emptyOutput") as NormalizationFileIdentity
        val count = h.f.io.calls.size
        assertThrows(Exception::class.java) {
            h.f.files.forOperation({}, h.token).openAsyncOutput(b.attempt.key, empty, b.attempt, Any(), {})
        }
        assertEquals(count, h.f.io.calls.size)
        b.enter { _, writer ->
            val before = h.f.io.calls.size
            assertThrows(Exception::class.java) {
                h.f.files.forOperation({}, h.token).openAsyncOutput(b.attempt.key, empty, b.attempt, Any(), {})
            }
            assertEquals(before, h.f.io.calls.size)
            writer.write(ByteBuffer.wrap(byteArrayOf(1))); b.finish(writer)
        }
        b.attempt.sealResources(); b.attempt.close()
        assertEquals(1, h.f.io.calls.count { it.operation == "open" && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
    }
    @Test fun shortWritesAdvanceExactlyAndMatchFullWriteFingerprint() {
        val bytes = ByteArray(257) { (it % 251).toByte() }
        for (short in listOf(1, 7, Int.MAX_VALUE)) OwnedTranscodeTestFixture().run { h, _ ->
            val b = Bound(h); h.f.io.maxWrite = short
            assertTrue(b.enter { _, writer ->
                val buffer = ByteBuffer.wrap(bytes)
                assertEquals(bytes.size, writer.write(buffer)); assertEquals(bytes.size, buffer.position())
                assertEquals(bytes.size.toLong(), writer.position); assertEquals(bytes.size.toLong(), writer.size)
                b.finish(writer)
            } is TransformerOutcome.Completed)
            assertEquals(normalizationTestFingerprint(bytes), b.attempt.sealResources().second.fingerprint)
            assertArrayEquals(bytes, h.f.io.bytes(h.f.outputFile(b.attempt.key)))
            b.attempt.close(); b.checkNoProof()
        }
    }
    @Test fun zeroWriteDoesNoSyscallButConsumesOneWorkEvent() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        b.enter { _, writer ->
            val before = budgetLong(b.attempt, "activeOutputBudget", "events")
            h.f.io.calls.clear(); assertEquals(0, writer.write(ByteBuffer.allocate(0)))
            assertTrue(h.f.io.calls.isEmpty())
            assertEquals(before + 1, budgetLong(b.attempt, "activeOutputBudget", "events"))
            writer.write(ByteBuffer.wrap(byteArrayOf(7))); b.finish(writer)
        }
        b.attempt.close()
    }
    @Test fun truncateDoesNotExtendAndShrinkClampsCursor() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        b.enter { _, writer ->
            writer.write(ByteBuffer.wrap(ByteArray(32))); writer.position = 31
            writer.truncate(8); assertEquals(8L, writer.position); assertEquals(8L, writer.size)
            val truncates = h.f.io.calls.count { it.operation == "truncate" }
            writer.truncate(100); assertEquals(8L, writer.size)
            assertEquals(truncates, h.f.io.calls.count { it.operation == "truncate" }); b.finish(writer)
        }
        b.attempt.close()
    }
    @Test fun sparseGrowthAndOverwriteRequireIndependentHeadroom() {
        for (sparse in listOf(false, true)) OwnedTranscodeTestFixture().run { h, _ ->
            val b = Bound(h)
            b.enter { _, writer ->
                writer.write(ByteBuffer.wrap(ByteArray(16)))
                writer.position = if (sparse) 1000 else 0
                h.f.available = if (sparse) 900 else 3
                val writes = h.f.io.calls.count { it.operation == "pwrite" }
                val failure = assertThrows(SaveFailure::class.java) { writer.write(ByteBuffer.wrap(ByteArray(4))) }
                assertEquals(SaveError.STORAGE_FULL, failure.code)
                assertEquals(writes, h.f.io.calls.count { it.operation == "pwrite" }); writer.close()
            }
            b.attempt.close(); assertFalse(h.f.attempts.releaseUnconfirmed())
        }
    }
    @Test fun impossibleProgressNeverReportsSuccessfulWrite() {
        for (result in listOf(0, -1, 5)) OwnedTranscodeTestFixture().run { h, _ ->
            val b = Bound(h)
            b.enter { _, writer ->
                h.f.io.writeResult = result
                val buffer = ByteBuffer.wrap(ByteArray(4))
                assertThrows(IOException::class.java) { writer.write(buffer) }
                assertEquals(0, buffer.position())
                if (result != 0) {
                    val boundary = h.f.io.calls.size
                    assertThrows(Exception::class.java) { writer.beginFinalization() }
                    assertThrows(Exception::class.java) { writer.force() }
                    assertThrows(Exception::class.java) { writer.size }
                    assertEquals(boundary, h.f.io.calls.size)
                }
                writer.close()
            }
            assertThrows(Exception::class.java) { b.attempt.sealResources() }
            if (result == 0) b.attempt.close() else assertThrows(SaveFailure::class.java) { b.attempt.close() }
            assertFalse(b.outputHandle().active)
            assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === b.outputHandle() })
            assertEquals(result != 0, h.f.attempts.releaseUnconfirmed()); b.checkNoProof()
        }
    }
    @Test fun exceptionalMutationNeverProbesRetriesOrWashesUncertainty() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        b.enter { _, writer ->
            val output = b.outputHandle()
            h.f.io.after = { if (it.operation == "pwrite" && it.descriptor === output) throw IOException("HOST_AFTER_MUTATION") }
            assertThrows(IOException::class.java) { writer.write(ByteBuffer.wrap(byteArrayOf(1, 2))) }
            val boundary = h.f.io.calls.size
            assertThrows(Exception::class.java) { writer.force() }
            assertThrows(Exception::class.java) { writer.beginFinalization() }
            writer.close(); assertThrows(SaveFailure::class.java) { b.attempt.close() }
            assertTrue(output.active) // Exact native pin still owns it, even with zero logical calls.
            assertFalse(h.f.io.calls.drop(boundary).any { it.descriptor === output && it.operation != "close" })
        }
        val output = b.outputHandle()
        assertFalse(output.active)
        assertEquals(1, h.f.io.calls.count { it.operation == "pwrite" && it.descriptor === output })
        assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === output })
        assertTrue(h.f.attempts.releaseUnconfirmed()); assertTrue(h.token.registered)
        assertThrows(Exception::class.java) { b.attempt.sealResources() }
        b.checkNoProof()
    }
    @Test fun forceFailureInvalidatesContentButPositiveCloseIsNotUncertain() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        b.enter { _, writer ->
            writer.write(ByteBuffer.wrap(byteArrayOf(1))); writer.beginFinalization()
            h.f.io.before = { if (it.operation == "fsync" && it.descriptor === b.outputHandle()) throw IOException("HOST_FORCE_FAILURE") }
            assertThrows(IOException::class.java) { writer.force() }; writer.close()
        }
        assertThrows(Exception::class.java) { b.attempt.sealResources() }
        b.attempt.close(); assertFalse(b.outputHandle().active); assertFalse(h.f.attempts.releaseUnconfirmed())
        b.checkNoProof()
    }
    @Test fun forceGenerationMustCoverLastMutationAndSessionContentFailureBlocksSealing() {
        for (sessionFailure in listOf(false, true)) OwnedTranscodeTestFixture().run { h, _ ->
            val b = Bound(h)
            b.enter { _, writer ->
                writer.write(ByteBuffer.wrap(byteArrayOf(1))); writer.beginFinalization(); writer.force()
                if (sessionFailure) b.session.muxerContentFailed.set(true) else writer.write(ByteBuffer.wrap(byteArrayOf(2)))
                writer.close()
            }
            assertThrows(Exception::class.java) { b.attempt.sealResources() }; b.attempt.close()
            assertFalse(h.f.attempts.releaseUnconfirmed()); b.checkNoProof()
        }
    }
    @Test fun apiPhysicalAndEventCountersAreIndependentAndFinalizationCannotBorrow() {
        for (field in listOf("requestBytes", "physicalBytes", "events")) OwnedTranscodeTestFixture().run { h, _ ->
            val b = Bound(h)
            b.enter { _, writer ->
                writer.write(ByteBuffer.wrap(byteArrayOf(1)))
                exhaust(b.attempt, "activeOutputBudget", field)
                val writes = h.f.io.calls.count { it.operation == "pwrite" }
                assertThrows(SaveFailure::class.java) { writer.write(ByteBuffer.wrap(byteArrayOf(2))) }
                assertEquals(writes, h.f.io.calls.count { it.operation == "pwrite" })
                assertEquals(0L, budgetLong(b.attempt, "finalOutputBudget", "requestBytes"))
                writer.beginFinalization(); writer.write(ByteBuffer.wrap(byteArrayOf(3))); writer.force()
                assertEquals(1L, budgetLong(b.attempt, "finalOutputBudget", "requestBytes"))
                writer.close()
            }
            assertThrows(Exception::class.java) { b.attempt.sealResources() }; b.attempt.close()
        }
        for (field in listOf("requestBytes", "physicalBytes", "events")) OwnedTranscodeTestFixture().run { h, _ ->
            val b = Bound(h)
            b.enter { _, writer ->
                writer.write(ByteBuffer.wrap(byteArrayOf(1))); writer.beginFinalization()
                exhaust(b.attempt, "finalOutputBudget", field)
                val writes = h.f.io.calls.count { it.operation == "pwrite" }
                assertThrows(SaveFailure::class.java) { writer.write(ByteBuffer.wrap(byteArrayOf(2))) }
                val calls = h.f.io.calls.size
                assertThrows(Exception::class.java) { writer.force() }
                assertEquals(writes, h.f.io.calls.count { it.operation == "pwrite" })
                assertEquals(calls, h.f.io.calls.size); writer.close()
            }
            b.attempt.close(); assertFalse(h.f.attempts.releaseUnconfirmed())
        }
    }
    @Test fun activeRequestOverQuotaRejectsBeforeAnyMetadataOrAllocation() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        b.enter { _, writer ->
            exhaust(b.attempt, "activeOutputBudget", "requestBytes")
            h.f.io.calls.clear()
            assertThrows(SaveFailure::class.java) { writer.write(ByteBuffer.wrap(byteArrayOf(1))) }
            assertTrue(h.f.io.calls.isEmpty()); writer.close()
        }
        b.attempt.close()
    }
    @Test fun finalizationDeadlineIsFrozenAndLateCallsNeverEnterSyscall() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        b.enter { _, writer ->
            writer.write(ByteBuffer.wrap(byteArrayOf(1))); writer.beginFinalization()
            val original = field(b.attempt, "finalizationDeadlineNanos")
            writer.beginFinalization(); assertEquals(original, field(b.attempt, "finalizationDeadlineNanos"))
            setField(b.attempt, "finalizationDeadlineNanos", System.nanoTime() - 1)
            val count = h.f.io.calls.size
            assertThrows(IOException::class.java) { writer.force() }
            assertEquals(count, h.f.io.calls.size); writer.close()
        }
        b.attempt.close(); assertFalse(h.f.attempts.releaseUnconfirmed())
    }
    @Test fun sealingReservesAreFixedSeparateAndUnusedByLoaderOrOutput() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        val expectedRaw = 2 * h.f.rawBytes.size.toLong() + TransformerMechanismPolicy.OUTPUT_RESERVE
        val expectedOutput = 2 * b.attempt.outputMaximum + TransformerMechanismPolicy.OUTPUT_RESERVE
        assertEquals(expectedRaw, budgetLong(b.attempt, "rawSealBudget", "bytesLimit"))
        assertEquals(expectedOutput, budgetLong(b.attempt, "outputSealBudget", "bytesLimit"))
        val loader = b.attempt.webmBudget(b.binding)
        loader.request(b.attempt.webmLimits(b.binding).maxIoBytes.toInt())
        b.enter { _, writer -> writer.write(ByteBuffer.wrap(byteArrayOf(1))); b.finish(writer) }
        assertEquals(0L, budgetLong(b.attempt, "rawSealBudget", "requestBytes"))
        assertEquals(0L, budgetLong(b.attempt, "outputSealBudget", "requestBytes"))
        b.attempt.sealResources()
        assertEquals(h.f.rawBytes.size.toLong(), budgetLong(b.attempt, "rawSealBudget", "requestBytes"))
        assertEquals(1L, budgetLong(b.attempt, "outputSealBudget", "requestBytes")); b.attempt.close()
    }
    @Test fun eachSealReserveExhaustionIsOneShotAndClosesBothAnchors() {
        for (budget in listOf("rawSealBudget", "outputSealBudget")) for (counter in listOf("requestBytes", "physicalBytes", "events"))
            OwnedTranscodeTestFixture().run { h, _ ->
                val b = Bound(h)
                b.enter { _, writer -> writer.write(ByteBuffer.wrap(byteArrayOf(1))); b.finish(writer) }
                exhaust(b.attempt, budget, counter)
                assertThrows(SaveFailure::class.java) { b.attempt.sealResources() }
                val reads = h.f.io.calls.count { it.operation == "pread" }
                assertThrows(Exception::class.java) { b.attempt.sealResources() }
                assertEquals(reads, h.f.io.calls.count { it.operation == "pread" })
                assertFalse(h.f.io.handles.any { it.active }); b.attempt.close(); b.checkNoProof()
            }
    }
    @Test fun equalLengthSameStatRawMutationFailsDigestButOutputFirstDigestIsObservation() {
        for (mutateRaw in listOf(true, false)) OwnedTranscodeTestFixture().run { h, _ ->
            val b = Bound(h); val original = byteArrayOf(1, 2, 3)
            b.enter { _, writer -> writer.write(ByteBuffer.wrap(original)); b.finish(writer) }
            if (mutateRaw) {
                val changed = h.f.rawBytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
                h.f.io.mutate(h.f.primary, changed, preserveTimes = true)
                assertThrows(Exception::class.java) { b.attempt.sealResources() }
            } else {
                val changed = byteArrayOf(3, 2, 1)
                h.f.io.mutate(h.f.outputFile(b.attempt.key), changed, preserveTimes = true)
                assertEquals(normalizationTestFingerprint(changed), b.attempt.sealResources().second.fingerprint)
            }
            b.attempt.close(); b.checkNoProof()
        }
    }
    @Test fun cancellationDuringRetainedHashNeverPublishesLateObservationAndDrainsOnce() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        b.enter { _, writer -> writer.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3))); b.finish(writer) }
        val raw = h.f.io.handles.single { it.active && it.path == h.f.primary }
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        h.f.io.before = { if (it.operation == "pread" && it.descriptor === raw) { blocked.countDown(); await(release) } }
        val hashing = start { runCatching { b.attempt.sealResources() } }; await(blocked)
        h.f.cancellation.cancel(); b.attempt.close()
        assertTrue(raw.active)
        release.countDown()
        val failure = hashing.get(5, TimeUnit.SECONDS).exceptionOrNull()
        assertEquals(SaveError.CANCELLED, (failure as SaveFailure).code)
        assertNull(field(b.attempt, "resourceObservations"))
        assertFalse(h.f.io.handles.any { it.active })
        assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === raw })
        assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === b.outputHandle() })
        b.checkNoProof()
    }
    @Test fun replacedOutputPathCannotBeHiddenByGoodOldFd() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h); val bytes = byteArrayOf(1, 2, 3)
        b.enter { _, writer -> writer.write(ByteBuffer.wrap(bytes)); b.finish(writer) }
        h.f.io.file(h.f.outputFile(b.attempt.key), bytes)
        assertThrows(Exception::class.java) { b.attempt.sealResources() }
        b.attempt.close(); assertFalse(h.f.io.handles.any { it.active }); b.checkNoProof()
    }
    @Test fun throwingOrStillValidCloseNeverRetriesAndOtherAnchorStillCloses() {
        for (mode in MemoryNormalizationDescriptorIo.CloseMode.entries.filter { it != MemoryNormalizationDescriptorIo.CloseMode.NORMAL })
            for (output in listOf(false, true)) OwnedTranscodeTestFixture().run { h, _ ->
                val b = Bound(h)
                b.enter { _, writer -> writer.write(ByteBuffer.wrap(byteArrayOf(1))); b.finish(writer) }
                val selected = if (output) b.outputHandle() else h.f.io.handles.single { it.active && it.path == h.f.primary }
                h.f.io.closeMode = { if (it === selected) mode else MemoryNormalizationDescriptorIo.CloseMode.NORMAL }
                assertThrows(SaveFailure::class.java) { b.attempt.sealResources() }
                assertThrows(Exception::class.java) { b.attempt.close() }
                assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === selected })
                assertEquals(1, h.f.io.handles.count { it.active || it === selected })
                assertTrue(h.f.attempts.releaseUnconfirmed()); assertTrue(h.token.registered); b.checkNoProof()
            }
    }
    @Test fun queuedOutputCallsSerializeAndIdentityPollingDoesNotSeeHalfMutation() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h); val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        b.enter { target, writer ->
            val output = b.outputHandle()
            h.f.io.before = {
                assertFalse(Thread.holdsLock(h.f.lock))
                if (it.operation == "pwrite" && it.descriptor === output) { blocked.countDown(); await(release) }
            }
            val write = start { writer.write(ByteBuffer.wrap(byteArrayOf(1, 2))) }; await(blocked)
            val poll = start { target.verifyIdentity() }; val seek = start { writer.position = 1 }
            val truncate = start { writer.truncate(1) }
            release.countDown(); assertEquals(2, write.get(5, TimeUnit.SECONDS))
            poll.get(5, TimeUnit.SECONDS); seek.get(5, TimeUnit.SECONDS); truncate.get(5, TimeUnit.SECONDS)
            assertEquals(1L, writer.size); b.finish(writer)
        }
        b.attempt.close(); assertFalse(h.f.attempts.releaseUnconfirmed())
    }
    @Test fun queuedWriterAfterLogicalCloseDoesNoSyscallEvenBeforeFirstCallFinishes() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h); val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        b.enter { _, writer ->
            val output = b.outputHandle()
            h.f.io.before = { if (it.operation == "pwrite" && it.descriptor === output) { blocked.countDown(); await(release) } }
            val first = start { runCatching { writer.write(ByteBuffer.wrap(byteArrayOf(1))) } }; await(blocked)
            val queued = start { runCatching { writer.write(ByteBuffer.wrap(byteArrayOf(2))) } }
            // The second real output operation is counted before waiting for the output IO mutex.
            val accepted = CountDownLatch(1)
            val waiter = start {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                val lock = requireNotNull(field(b.attempt, "lock"))
                fun count() = synchronized(lock) { field(b.attempt, "outputCalls") as Int }
                while (count() < 2 && System.nanoTime() < deadline) Thread.yield()
                if (count() >= 2) accepted.countDown()
            }
            await(accepted); waiter.get(5, TimeUnit.SECONDS)
            writer.close(); assertFalse(writer.releaseConfirmed)
            val before = h.f.io.calls.size
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS).isFailure); assertTrue(queued.get(5, TimeUnit.SECONDS).isFailure)
            assertTrue(writer.releaseConfirmed)
            assertEquals(1, h.f.io.calls.count { it.operation == "pwrite" && it.descriptor === output })
            assertFalse(h.f.io.calls.drop(before).any { it.operation != "close" })
            assertTrue(output.active)
        }
        b.attempt.close(); assertFalse(h.f.attempts.releaseUnconfirmed())
    }
    @Test fun blockedWriteLogicalCloseAndRealLateNativeCleanupKeepExactPin() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h); val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val bodyReady = CountDownLatch(1); val allowCleanup = CountDownLatch(1)
        lateinit var output: MemoryNormalizationDescriptorIo.Descriptor
        val run = start { b.enter { target, writer ->
            output = b.outputHandle()
            h.f.io.before = { if (it.operation == "pwrite" && it.descriptor === output) { blocked.countDown(); await(release) } }
            val writing = start { runCatching { writer.write(ByteBuffer.wrap(byteArrayOf(1, 2))) } }
            await(blocked); b.attempt.close(); writer.close()
            assertFalse(writer.releaseConfirmed); assertTrue(output.active)
            release.countDown(); assertTrue(writing.get(5, TimeUnit.SECONDS).isFailure)
            assertTrue(writer.releaseConfirmed); assertTrue(output.active)
            assertThrows(TransformerFailure::class.java) { b.session.requireWriterRelease(target) }
            assertTrue(h.session().finish()); assertTrue(output.active)
            assertThrows(Exception::class.java) { b.attempt.sealResources() }
            assertFalse(h.f.io.calls.any { it.operation == "close" && it.descriptor === output })
            bodyReady.countDown(); await(allowCleanup)
        } }
        await(bodyReady); assertTrue(output.active); assertTrue(h.token.registered)
        allowCleanup.countDown(); run.get(5, TimeUnit.SECONDS)
        assertFalse(output.active); assertFalse(h.token.registered)
        assertEquals(1, h.f.io.calls.count { it.operation == "close" && it.descriptor === output }); b.checkNoProof()
    }
    @Test fun cancelledActiveWriteMayOnlyEnterExactBoundedFinalizationThenClose() = OwnedTranscodeTestFixture().run { h, _ ->
        val b = Bound(h)
        b.enter { _, writer ->
            writer.write(ByteBuffer.wrap(byteArrayOf(1))); b.attempt.close()
            val writes = h.f.io.calls.count { it.operation == "pwrite" }
            assertThrows(Exception::class.java) { writer.write(ByteBuffer.wrap(byteArrayOf(2))) }
            assertEquals(writes, h.f.io.calls.count { it.operation == "pwrite" })
            writer.beginFinalization(); writer.write(ByteBuffer.wrap(byteArrayOf(3))); writer.force(); writer.close()
            assertTrue(b.outputHandle().active)
        }
        assertFalse(b.outputHandle().active); assertFalse(h.token.registered); b.checkNoProof()
    }
    @Test fun outputOpenCancellationClosesReturnedExactHandleAndUnknownOpenPoisons() {
        for (unknown in listOf(false, true)) OwnedTranscodeTestFixture().run { h, _ ->
            val b = Bound(h)
            h.entered(b.session) {
                b.session.retain(b.source)
                h.f.io.after = { if (it.operation == "open" && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING) {
                    if (unknown) throw IOException("HOST_UNKNOWN_OUTPUT_OPEN") else b.attempt.close()
                } }
                assertThrows(Exception::class.java) { b.attempt.ownedTarget(b.binding) }
            }
            assertEquals(unknown, h.f.attempts.releaseUnconfirmed())
            assertEquals(unknown, h.f.io.handles.any { it.active })
            assertEquals(1, h.f.io.calls.count { it.operation == "open" && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
            b.checkNoProof()
        }
    }
    /** Test-only counter positioning exercises the real registered call site, without new authority. */
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun setField(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    private fun budgetLong(attempt: OwnedDirectTranscodeAttempt, budget: String, name: String): Long = field(requireNotNull(field(attempt, budget)), name) as Long
    private fun exhaust(attempt: OwnedDirectTranscodeAttempt, name: String, counter: String) {
        val budget = requireNotNull(field(attempt, name)); val limit = field(budget, if (counter == "events") "eventsLimit" else "bytesLimit") as Long
        setField(budget, counter, limit)
    }
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }
    private fun <T> start(action: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { result ->
        Thread { try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) } }.apply { isDaemon = true; start() }
    }
}
