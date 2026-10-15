package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual ownership state machines with deterministic host syscalls; no Android/media acceptance. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class OwnedMovRemuxAttemptTest {
    private fun completed(f: NormalizationDescriptorFixture): OwnedMovRemuxAttempt {
        val attempt = f.prepare()
        val source = attempt.openSource()
        val output = attempt.openEmptyOutput()
        assertEquals(f.outputBytes.size, output.write(ByteBuffer.wrap(f.outputBytes)))
        output.close(); assertTrue(output.releaseConfirmed)
        val candidate = attempt.openFinalizedOutput()
        val bytes = ByteArray(f.outputBytes.size)
        candidate.readAt(0, bytes, 0, bytes.size); assertArrayEquals(f.outputBytes, bytes)
        candidate.close(); source.close()
        assertTrue(candidate.releaseConfirmed); assertTrue(source.releaseConfirmed)
        return attempt
    }

    @Test fun freshAttemptReadsPrimaryAndConsumesExactlyOneExistingEmptyOutputOpen() {
        val f = NormalizationDescriptorFixture(); val attempt = f.prepare()
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        assertTrue(f.attempts.hasActiveSession(f.owner.artifactId))
        assertFalse(f.io.exists(f.rawFile(attempt.key)))
        val source = attempt.openSource(); val bytes = ByteArray(f.rawBytes.size)
        source.readAt(0, bytes, 0, bytes.size); assertArrayEquals(f.rawBytes, bytes)
        val sink = attempt.openEmptyOutput(); sink.write(ByteBuffer.wrap(f.outputBytes)); sink.close()
        val reader = attempt.openFinalizedOutput(); reader.close(); source.close()
        assertEquals(1, f.io.calls.count { it.operation == "open" && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        assertTrue(f.io.calls.filter { it.operation == "open" && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING }
            .all { it.path == f.outputFile(attempt.key) })
        attempt.close(); assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
    }

    @Test fun recoveredWritingWithMissingEmptyOrPartialOutputNeverGrantsWriter() {
        for (bytes in listOf<ByteArray?>(null, byteArrayOf(), byteArrayOf(1, 2, 3))) {
            val f = NormalizationDescriptorFixture(); val key = f.seedWriting(bytes)
            assertThrows(NormalizationRecovery::class.java) { f.prepare() }
            assertEquals(bytes != null, f.io.exists(f.outputFile(key)))
            if (bytes != null) assertArrayEquals(bytes, f.io.bytes(f.outputFile(key)))
            assertFalse(f.io.calls.any { it.operation == "open" || it.operation == "truncate" })
            assertFalse(f.attempts.hasAnyLeases())
        }
    }

    @Test fun exactRawCompleteGenerationCanMintFreshGrant() {
        val f = NormalizationDescriptorFixture()
        f.ledger.row = NormalizationWorkspace(f.knownKey, 1, NormalizationLifecycle.RAW_COMPLETE,
            NormalizationDisposition.CONTINUE, f.raw, f.sourceDigest)
        val attempt = f.prepare()
        assertEquals(f.knownKey, attempt.key); assertEquals(2L, f.ledger.row!!.revision)
        attempt.close()
        assertThrows(NormalizationRecovery::class.java) { f.prepare() }
    }

    @Test fun failureAfterDurableWritingAndBeforeReturnedGrantNeverBecomesRestartPermission() {
        val f = NormalizationDescriptorFixture()
        f.ledger.afterWrite = { if (it.lifecycle == NormalizationLifecycle.WRITING) f.record = f.record!!.copy(operationId = "replacement") }
        assertThrows(NormalizationRecovery::class.java) { f.prepare() }
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        f.record = f.record!!.copy(operationId = f.context.operationId); f.ledger.afterWrite = {}
        assertThrows(NormalizationRecovery::class.java) { f.prepare() }
        assertFalse(f.io.calls.any { it.operation == "open" && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        assertFalse(f.attempts.hasAnyLeases())
    }

    @Test fun durableWritingCasWithLostReturnIsNotAnAcknowledgedFreshGrant() {
        val f = NormalizationDescriptorFixture()
        f.ledger.afterWrite = { if (it.lifecycle == NormalizationLifecycle.WRITING) throw IOException("HOST_LOST_CAS_RETURN") }
        assertThrows(Exception::class.java) { f.prepare() }
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
        assertEquals(0, f.io.bytes(f.outputFile(f.ledger.row!!.key)).size)
        f.ledger.afterWrite = {}
        assertThrows(NormalizationRecovery::class.java) { f.prepare() }
        assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
    }

    @Test fun wrongTaskArtifactCommitAndMixedLedgerOwnerNeverGrantAnAttempt() {
        val f = NormalizationDescriptorFixture()
        for (owner in listOf(f.owner.copy(taskId = "other"), f.owner.copy(commitId = "other"),
            f.owner.copy(artifactId = "33333333-3333-4333-8333-333333333333"))) {
            assertThrows(Exception::class.java) { f.attempts.prepare(owner, f.context, f.limits) }
        }
        f.ledger.row = NormalizationWorkspace(f.knownKey.copy(owner = f.owner.copy(taskId = "other")), 1,
            NormalizationLifecycle.RAW_COMPLETE, NormalizationDisposition.CONTINUE, f.raw, f.sourceDigest)
        assertThrows(Exception::class.java) { f.prepare() }
        assertFalse(f.io.calls.any { it.operation == "open" })
    }

    @Test fun historicalCleanedLedgerAndNonDirectRequestCannotBecomeFreshWriter() {
        NormalizationDescriptorFixture().let { f ->
            f.ledger.row = NormalizationWorkspace(f.knownKey, 3, NormalizationLifecycle.CLEANED,
                NormalizationDisposition.DISCARD_ONLY, f.raw, f.sourceDigest,
                cleanup = NormalizationCleanupPlan(f.raw, null, null))
            assertThrows(Exception::class.java) { f.prepare() }; assertFalse(f.io.calls.any { it.operation == "open" })
        }
        NormalizationDescriptorFixture().let { f ->
            f.record = f.record!!.let { it.copy(request = it.request.copy(method = TaskMethod.STREAM),
                task = it.task.copy(method = TaskMethod.STREAM),
                transfer = it.transfer!!.copy(streamExpectation = StreamArtifactExpectation(1000, 1, 0, 16, 16))) }
            assertThrows(Exception::class.java) { f.prepare() }; assertFalse(f.io.calls.any { it.operation == "open" })
        }
    }

    @Test fun preparationRequiresExactRunningNormalizingSourceOwnerAndOperation() {
        val changes: List<(NormalizationDescriptorFixture) -> Unit> = listOf(
            { it.record = null },
            { it.record = it.record!!.copy(phase = SavePhase.PREPARING) },
            { it.record = it.record!!.copy(phase = SavePhase.EXECUTING) },
            { it.record = it.record!!.copy(phase = SavePhase.VERIFYING) },
            { it.record = it.record!!.copy(phase = SavePhase.COMMITTING) },
            { it.record = it.record!!.copy(task = it.record!!.task.copy(state = TaskState.CANCELLED)) },
            { it.record = it.record!!.copy(task = it.record!!.task.copy(state = TaskState.INTERRUPTED)) },
            { it.record = it.record!!.copy(operationId = "stale") },
            { it.record = it.record!!.copy(transfer = null) },
            { it.record = it.record!!.copy(transfer = it.record!!.transfer!!.copy(expectedTotalBytes = it.raw.size + 1)) },
            { it.authority = it.authority.copy(owner = it.owner.copy(commitId = "wrong")) },
            { it.authority = it.authority.copy(parentState = NormalizationParentState.PUBLISHED) },
            { it.authority = it.authority.copy(sourceTransferSha256 = "b".repeat(64)) },
            { it.authorityUnreadable = true }, { it.priorHandles = true }, { it.ledger.unreadable = true },
        )
        for (change in changes) {
            val f = NormalizationDescriptorFixture(); change(f)
            assertThrows(Exception::class.java) { f.prepare() }
            assertFalse(f.io.calls.any { it.operation == "open" && it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
            assertFalse(f.attempts.hasAnyLeases())
        }
    }

    @Test fun simultaneousPreparationCannotShareAnArtifactAndCloseDoesNotGrantRestart() {
        val f = NormalizationDescriptorFixture(); val attempt = f.prepare()
        assertThrows(NormalizationRecovery::class.java) { f.prepare() }
        attempt.close(); assertFalse(f.attempts.hasActiveSession(f.owner.artifactId))
        assertThrows(NormalizationRecovery::class.java) { f.prepare() }
    }

    @Test fun outputCannotOpenBeforeSourceAndCandidateCannotOpenBeforePositiveSinkRelease() {
        NormalizationDescriptorFixture().let { f ->
            val attempt = f.prepare(); assertThrows(Exception::class.java) { attempt.openEmptyOutput() }; attempt.close()
            assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.WRITE_EXISTING })
        }
        NormalizationDescriptorFixture().let { f ->
            val attempt = f.prepare(); attempt.openSource(); attempt.openEmptyOutput()
            assertThrows(Exception::class.java) { attempt.openFinalizedOutput() }; attempt.close()
            assertFalse(f.attempts.hasAnyLeases())
        }
    }

    @Test fun sourceSinkAndFinalizedReaderEachOpenAtMostOnce() {
        for (which in 0..2) {
            val f = NormalizationDescriptorFixture(); val attempt = f.prepare()
            val source = attempt.openSource()
            when (which) {
                0 -> assertThrows(Exception::class.java) { attempt.openSource() }
                1 -> { attempt.openEmptyOutput(); assertThrows(Exception::class.java) { attempt.openEmptyOutput() } }
                else -> {
                    val sink = attempt.openEmptyOutput(); sink.write(ByteBuffer.wrap(f.outputBytes)); sink.close()
                    attempt.openFinalizedOutput(); assertThrows(Exception::class.java) { attempt.openFinalizedOutput() }
                }
            }
            attempt.close(); assertTrue(source.releaseConfirmed); assertFalse(f.attempts.hasAnyLeases())
        }
    }

    @Test fun shortPositionalReadsFillExactlyTheRequestedSliceWithBoundedSyscalls() {
        val f = NormalizationDescriptorFixture(150_123); val attempt = f.prepare(); val input = attempt.openSource()
        f.io.calls.clear(); f.io.maxRead = 7001
        val bytes = ByteArray(f.rawBytes.size + 6) { 99 }
        input.readAt(0, bytes, 3, f.rawBytes.size)
        assertArrayEquals(f.rawBytes, bytes.copyOfRange(3, 3 + f.rawBytes.size))
        assertArrayEquals(byteArrayOf(99, 99, 99), bytes.copyOfRange(0, 3))
        assertArrayEquals(byteArrayOf(99, 99, 99), bytes.copyOfRange(bytes.size - 3, bytes.size))
        val reads = f.io.calls.filter { it.operation == "pread" }
        assertTrue(reads.size > 2); assertTrue(reads.all { it.count in 1..65536 })
        assertEquals(f.rawBytes.size.toLong(), input.length)
        attempt.close(); assertFalse(f.attempts.hasAnyLeases())
    }

    @Test fun invalidReadSlicesAndSignedOverflowNeverReachSyscalls() {
        val requests = listOf(Triple(-1L, 0, 1), Triple(Long.MAX_VALUE, 0, 1), Triple(8192L, 0, 1),
            Triple(0L, -1, 1), Triple(0L, 0, -1), Triple(0L, Int.MAX_VALUE, 1), Triple(0L, 0, 5))
        for ((position, offset, length) in requests) {
            val f = NormalizationDescriptorFixture(); val attempt = f.prepare(); val input = attempt.openSource(); f.io.calls.clear()
            assertThrows(Exception::class.java) { input.readAt(position, ByteArray(4), offset, length) }
            assertFalse(f.io.calls.any { it.operation == "pread" }); attempt.close()
        }
    }

    @Test fun endpointZeroReadIsAllowedButPositiveZeroNegativeAndExcessProgressFail() {
        val f = NormalizationDescriptorFixture(); val attempt = f.prepare(); val source = attempt.openSource(); f.io.calls.clear()
        source.readAt(source.length, byteArrayOf(), 0, 0)
        assertFalse(f.io.calls.any { it.operation == "pread" }); attempt.close()
        for (result in listOf(0, -1, 5)) {
            val x = NormalizationDescriptorFixture(); val a = x.prepare(); val input = a.openSource(); x.io.readResult = result
            assertThrows(Exception::class.java) { input.readAt(0, ByteArray(4), 0, 4) }; a.close()
            assertTrue(input.releaseConfirmed); assertFalse(x.attempts.releaseUnconfirmed())
        }
    }

    @Test fun cancellationAndDeadlineAreCheckedImmediatelyAfterPartialReads() {
        for (deadline in listOf(false, true)) {
            val f = NormalizationDescriptorFixture(); val a = f.prepare(); val input = a.openSource()
            f.io.maxRead = 3; f.io.calls.clear()
            f.io.after = { if (it.operation == "pread") { if (deadline) f.now = f.context.deadlineEpochMillis else f.cancellation.cancel() } }
            val failure = assertThrows(SaveFailure::class.java) { input.readAt(0, ByteArray(10), 0, 10) }
            assertEquals(if (deadline) SaveError.DEADLINE_EXCEEDED else SaveError.CANCELLED, failure.code)
            assertEquals(1, f.io.calls.count { it.operation == "pread" }); a.close()
            assertTrue(input.releaseConfirmed); assertFalse(f.attempts.releaseUnconfirmed())
        }
    }

    @Test fun sameSizeMutationBetweenPartialReadsStopsBeforeTheNextChunk() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); val input = a.openSource()
        f.io.maxRead = 3; f.io.calls.clear()
        f.io.after = { if (it.operation == "pread") f.io.mutate(f.primary, f.rawBytes.copyOf().also { bytes -> bytes[0] = 99 }) }
        val bytes = ByteArray(10) { 77 }
        assertThrows(Exception::class.java) { input.readAt(0, bytes, 0, bytes.size) }
        assertEquals(1, f.io.calls.count { it.operation == "pread" })
        assertArrayEquals(f.rawBytes.copyOf(3), bytes.copyOf(3))
        assertTrue(bytes.drop(3).all { it == 77.toByte() }); a.close(); assertTrue(input.releaseConfirmed)
    }

    @Test fun synchronousCapabilityRejectsASecondWorkerThread() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); val input = a.openSource(); f.io.calls.clear()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val failure = worker.submit<Boolean> {
                try { input.readAt(0, ByteArray(1), 0, 1); false } catch (_: Exception) { true }
            }
            assertTrue(failure.get(2, TimeUnit.SECONDS)); assertFalse(f.io.calls.any { it.operation == "pread" })
        } finally { worker.shutdownNow(); a.close() }
    }

    @Test fun interruptedReadDoesNotGuessProgressOrRetry() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); val input = a.openSource(); f.io.calls.clear()
        f.io.before = { if (it.operation == "pread") throw InterruptedIOException("HOST_INTERRUPTED") }
        assertThrows(Exception::class.java) { input.readAt(0, ByteArray(10), 0, 10) }
        assertEquals(1, f.io.calls.count { it.operation == "pread" }); a.close(); assertTrue(input.releaseConfirmed)
    }

    @Test fun interruptedReadRechecksCancellationAndDeadlineBeforeReportingIoFailure() {
        for (deadline in listOf(false, true)) {
            val f = NormalizationDescriptorFixture(); val a = f.prepare(); val input = a.openSource(); f.io.calls.clear()
            f.io.before = {
                if (it.operation == "pread") {
                    if (deadline) f.now = f.context.deadlineEpochMillis else f.cancellation.cancel()
                    throw InterruptedIOException("HOST_INTERRUPTED_READ")
                }
            }
            val failure = assertThrows(SaveFailure::class.java) { input.readAt(0, ByteArray(10), 0, 10) }
            assertEquals(if (deadline) SaveError.DEADLINE_EXCEEDED else SaveError.CANCELLED, failure.code)
            assertEquals(1, f.io.calls.count { it.operation == "pread" })
            a.close(); assertTrue(input.releaseConfirmed)
            assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
        }
    }

    @Test fun interruptedWriteAfterConfirmedPartialProgressPrefersStopWithoutRetryingOrInventingBytes() {
        for (deadline in listOf(false, true)) {
            val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.openSource(); val sink = a.openEmptyOutput()
            f.io.maxWrite = 3; f.io.calls.clear(); var writes = 0
            f.io.before = {
                if (it.operation == "pwrite" && ++writes == 2) {
                    if (deadline) f.now = f.context.deadlineEpochMillis else f.cancellation.cancel()
                    throw InterruptedIOException("HOST_INTERRUPTED_WRITE")
                }
            }
            val buffer = ByteBuffer.wrap(ByteArray(10) { 7 })
            val failure = assertThrows(SaveFailure::class.java) { sink.write(buffer) }
            assertEquals(if (deadline) SaveError.DEADLINE_EXCEEDED else SaveError.CANCELLED, failure.code)
            assertEquals(3, buffer.position()); assertArrayEquals(byteArrayOf(7, 7, 7), f.io.bytes(f.outputFile(a.key)))
            assertEquals(2, writes); assertEquals(2, f.io.calls.count { it.operation == "pwrite" })
            a.close(); assertTrue(sink.releaseConfirmed)
            assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
        }
    }

    @Test fun temporaryPreparationHashAndSyncIoFailurePreferStopAfterConfirmedDescriptorClose() {
        for (operation in listOf("pread", "fsync")) for (deadline in listOf(false, true)) {
            val f = NormalizationDescriptorFixture()
            f.io.before = {
                if (it.operation == operation && it.path == f.primary) {
                    if (deadline) f.now = f.context.deadlineEpochMillis else f.cancellation.cancel()
                    throw IOException("HOST_TEMPORARY_IO")
                }
            }
            val failure = assertThrows(SaveFailure::class.java) { f.prepare() }
            assertEquals(if (deadline) SaveError.DEADLINE_EXCEEDED else SaveError.CANCELLED, failure.code)
            assertEquals(1, f.io.calls.count { it.operation == operation && it.path == f.primary })
            val handle = f.io.handles.single(); assertFalse(handle.active)
            assertEquals(1, f.io.calls.count { it.operation == "close" && it.descriptor === handle })
            assertNull(f.ledger.row); assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
        }
    }

    @Test fun uncertainTemporaryCloseOutranksSimultaneousCancellationOrDeadlineAndIsNeverRetried() {
        for (operation in listOf("pread", "fsync")) for (deadline in listOf(false, true)) {
            val f = NormalizationDescriptorFixture()
            f.io.before = {
                if (it.operation == operation && it.path == f.primary) {
                    if (deadline) f.now = f.context.deadlineEpochMillis else f.cancellation.cancel()
                    throw IOException("HOST_TEMPORARY_IO")
                }
            }
            f.io.closeMode = { MemoryNormalizationDescriptorIo.CloseMode.INVALIDATES_THEN_THROWS }
            val failure = assertThrows(SaveFailure::class.java) { f.prepare() }
            assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, failure.code)
            val handle = f.io.handles.single(); assertFalse(handle.active)
            assertTrue(f.attempts.hasAnyLeases()); assertTrue(f.attempts.releaseUnconfirmed())
            assertThrows(Exception::class.java) { f.prepare() }
            assertEquals(1, f.io.calls.count { it.operation == operation && it.path == f.primary })
            assertEquals(1, f.io.calls.count { it.operation == "close" && it.descriptor === handle })
        }
    }

    @Test fun partialWritesConsumeEntireOriginalBufferWithBoundedRequests() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.openSource(); val sink = a.openEmptyOutput()
        val bytes = ByteArray(150_123) { (it % 239).toByte() }; val buffer = ByteBuffer.allocate(bytes.size + 8)
        buffer.position(4); buffer.put(bytes); buffer.flip(); buffer.position(4)
        f.io.maxWrite = 7001; f.io.calls.clear()
        assertEquals(bytes.size, sink.write(buffer)); assertEquals(buffer.limit(), buffer.position())
        assertEquals(bytes.size.toLong(), sink.position); assertEquals(bytes.size.toLong(), sink.size)
        assertArrayEquals(bytes, f.io.bytes(f.outputFile(a.key)))
        val writes = f.io.calls.filter { it.operation == "pwrite" }
        assertTrue(writes.size > 2); assertTrue(writes.all { it.count in 1..65536 })
        a.close(); assertTrue(sink.releaseConfirmed)
    }

    @Test fun zeroNegativeAndImpossibleWriteProgressNeverReturnSuccess() {
        for (result in listOf(0, -1, 5)) {
            val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.openSource(); val sink = a.openEmptyOutput()
            f.io.writeResult = result; val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
            assertThrows(Exception::class.java) { sink.write(buffer) }; assertEquals(0, buffer.position())
            a.close(); assertTrue(sink.releaseConfirmed); assertFalse(f.attempts.releaseUnconfirmed())
        }
    }

    @Test fun cancelledPartialWriteAccountsOnlyConfirmedBytesAndMakesNoSecondSyscall() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.openSource(); val sink = a.openEmptyOutput()
        f.io.maxWrite = 3; f.io.calls.clear(); f.io.after = { if (it.operation == "pwrite") f.cancellation.cancel() }
        val buffer = ByteBuffer.wrap(ByteArray(10) { 7 })
        assertEquals(SaveError.CANCELLED, assertThrows(SaveFailure::class.java) { sink.write(buffer) }.code)
        assertEquals(3, buffer.position()); assertEquals(3, f.io.bytes(f.outputFile(a.key)).size)
        assertEquals(1, f.io.calls.count { it.operation == "pwrite" }); a.close(); assertTrue(sink.releaseConfirmed)
    }

    @Test fun exceptionAfterAnEarlierPartialWriteDoesNotRetryOrReportFullCompletion() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.openSource(); val sink = a.openEmptyOutput()
        f.io.maxWrite = 3; f.io.calls.clear(); var calls = 0
        f.io.before = { if (it.operation == "pwrite" && ++calls == 2) throw InterruptedIOException("HOST_WRITE_INTERRUPTED") }
        val buffer = ByteBuffer.wrap(ByteArray(10) { 7 })
        assertThrows(Exception::class.java) { sink.write(buffer) }
        assertEquals(3, buffer.position()); assertEquals(3, f.io.bytes(f.outputFile(a.key)).size); assertEquals(2, calls)
        a.close(); assertFalse(f.attempts.releaseUnconfirmed())
    }

    @Test fun operationBudgetMinusRawBoundsSeekAndWrites() {
        val f = NormalizationDescriptorFixture(); f.context = f.context.copy(resourceBudget = ResourceBudget(1, f.raw.size + 32))
        val a = f.prepare(); a.openSource(); val sink = a.openEmptyOutput()
        sink.setPosition(31); sink.write(ByteBuffer.wrap(byteArrayOf(9))); assertEquals(32L, sink.size)
        assertThrows(Exception::class.java) { sink.write(ByteBuffer.wrap(byteArrayOf(8))) }
        assertThrows(Exception::class.java) { sink.setPosition(33) }
        assertThrows(Exception::class.java) { sink.setPosition(Long.MAX_VALUE) }
        a.close(); assertEquals(32, f.io.bytes(f.outputFile(a.key)).size)
    }

    @Test fun insufficientRawOrRemainingOperationBudgetNeverCreatesOutput() {
        for (budget in listOf(1L, 8192L, 8192L + 31)) {
            val f = NormalizationDescriptorFixture(); f.context = f.context.copy(resourceBudget = ResourceBudget(1, budget))
            assertEquals(SaveError.BYTE_LIMIT_EXCEEDED, assertThrows(SaveFailure::class.java) { f.prepare() }.code)
            assertFalse(f.io.calls.any { it.mode == NormalizationDescriptorIo.Open.CREATE_EXCLUSIVE })
            assertFalse(f.attempts.hasAnyLeases())
        }
    }

    @Test fun writeRechecksLiveFreeSpaceAfterEachShortPositiveChunk() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.openSource(); val sink = a.openEmptyOutput()
        f.io.maxWrite = 3; f.io.calls.clear(); f.io.after = { if (it.operation == "pwrite") f.available = 0 }
        val bytes = ByteBuffer.wrap(ByteArray(10) { 7 })
        assertEquals(SaveError.STORAGE_FULL, assertThrows(SaveFailure::class.java) { sink.write(bytes) }.code)
        assertEquals(3, bytes.position()); assertEquals(1, f.io.calls.count { it.operation == "pwrite" })
        a.close(); assertTrue(sink.releaseConfirmed); assertFalse(f.attempts.releaseUnconfirmed())
    }

    @Test fun sparseGapCountsAgainstLiveFreeSpaceBeforeAnyWrite() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.openSource(); val sink = a.openEmptyOutput()
        f.available = 50; sink.setPosition(100); f.io.calls.clear()
        assertThrows(Exception::class.java) { sink.write(ByteBuffer.wrap(byteArrayOf(1))) }
        assertFalse(f.io.calls.any { it.operation == "pwrite" }); assertEquals(0, f.io.bytes(f.outputFile(a.key)).size)
        a.close()
    }

    @Test fun truncateShrinksAndClampsPositionWithoutAllowingExtentGrowth() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); a.openSource(); val sink = a.openEmptyOutput()
        sink.write(ByteBuffer.wrap(ByteArray(20) { 5 })); sink.truncate(7)
        assertEquals(7L, sink.size); assertEquals(7L, sink.position); assertEquals(7, f.io.bytes(f.outputFile(a.key)).size)
        f.io.calls.clear(); sink.truncate(8); assertEquals(7L, sink.size); assertEquals(7L, sink.position)
        assertFalse(f.io.calls.any { it.operation == "truncate" })
        assertThrows(Exception::class.java) { sink.truncate(-1) }
        a.close()
    }

    @Test fun closeRevokesEveryOperationAndPositivelyClosesAllHandlesIndependently() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); val source = a.openSource(); val sink = a.openEmptyOutput()
        a.close(); a.close()
        assertTrue(source.releaseConfirmed); assertTrue(sink.releaseConfirmed); assertFalse(f.attempts.hasAnyLeases())
        assertThrows(Exception::class.java) { source.readAt(0, ByteArray(1), 0, 1) }
        assertThrows(Exception::class.java) { source.validateIdentity() }
        assertThrows(Exception::class.java) { sink.write(ByteBuffer.wrap(byteArrayOf(1))) }
        assertThrows(Exception::class.java) { a.openSource() }; assertThrows(Exception::class.java) { a.openFinalizedOutput() }
    }

    @Test fun closeRequiresSuccessfulReturnAndInvalidDescriptorAndNeverRetriesEitherFailure() {
        for (mode in listOf(MemoryNormalizationDescriptorIo.CloseMode.RETURNS_VALID,
            MemoryNormalizationDescriptorIo.CloseMode.INVALIDATES_THEN_THROWS, MemoryNormalizationDescriptorIo.CloseMode.THROWS_VALID)) {
            val f = NormalizationDescriptorFixture(); val a = f.prepare(); val source = a.openSource(); val sink = a.openEmptyOutput()
            val rawHandle = f.io.handles.last { it.path == f.primary && it.active }
            f.io.closeMode = { if (it === rawHandle) mode else MemoryNormalizationDescriptorIo.CloseMode.NORMAL }
            assertThrows(Exception::class.java) { a.close() }
            try { a.close() } catch (_: Exception) { }
            assertFalse(source.releaseConfirmed); assertTrue(sink.releaseConfirmed)
            assertTrue(f.attempts.hasAnyLeases()); assertTrue(f.attempts.hasActiveSession(f.owner.artifactId)); assertTrue(f.attempts.releaseUnconfirmed())
            assertEquals(1, f.io.calls.count { it.operation == "close" && it.descriptor === rawHandle })
        }
    }

    @Test fun unconfirmedSinkCloseStillClosesTheIndependentRawSource() {
        val f = NormalizationDescriptorFixture(); val a = f.prepare(); val source = a.openSource(); val sink = a.openEmptyOutput()
        val outputHandle = f.io.handles.last { it.path == f.outputFile(a.key) && it.active }
        f.io.closeMode = { if (it === outputHandle) MemoryNormalizationDescriptorIo.CloseMode.THROWS_VALID else MemoryNormalizationDescriptorIo.CloseMode.NORMAL }
        assertThrows(Exception::class.java) { a.close() }
        assertFalse(sink.releaseConfirmed); assertTrue(source.releaseConfirmed); assertTrue(f.attempts.releaseUnconfirmed())
        try { a.close() } catch (_: Exception) { }
        assertEquals(1, f.io.calls.count { it.operation == "close" && it.descriptor === outputHandle })
    }

    @Test fun blockedCloseKeepsSessionAndRegistryLiveWithoutHoldingOwnerMonitor() {
        val f = NormalizationDescriptorFixture()
        val entered = CountDownLatch(1); val resume = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val attempt = java.util.concurrent.atomic.AtomicReference<OwnedMovRemuxAttempt>()
        val input = java.util.concurrent.atomic.AtomicReference<com.easysavevideo.app.save.normalization.MovRemuxInput>()
        val closing = pool.submit {
            val a = f.prepare(); attempt.set(a); val source = a.openSource(); input.set(source)
            f.io.before = { if (it.operation == "close" && it.path == f.primary) { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) } }
            a.close()
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val status = pool.submit<Boolean> { f.attempts.hasAnyLeases() && f.attempts.hasActiveSession(f.owner.artifactId) }
            assertTrue(status.get(1, TimeUnit.SECONDS)); assertFalse(input.get().releaseConfirmed)
            f.cancellation.cancel(); assertTrue(f.attempts.resourcesUnreleased()); assertFalse(input.get().releaseConfirmed)
        } finally { resume.countDown(); closing.get(3, TimeUnit.SECONDS); pool.shutdownNow() }
        assertTrue(input.get().releaseConfirmed); assertFalse(f.attempts.hasAnyLeases())
    }

    @Test fun finishBindsExactTransferAndSourceThenAdoptsOnlyAfterSaveAcknowledgesProof() {
        val f = NormalizationDescriptorFixture(); val a = completed(f); val transfer = f.record!!.transfer
        val proof = f.attempts.finish(a, f.evidence(), f.context)
        assertEquals(f.sourceDigest, proof.sourceTransferSha256); assertEquals(f.record!!.request.resource.sourceIdentity, proof.sourceIdentity)
        assertEquals(f.raw.size, proof.rawSizeBytes); assertEquals(f.output.size, proof.outputSizeBytes)
        assertEquals(transfer, f.record!!.transfer); assertEquals(NormalizationLifecycle.OUTPUT_READY, f.ledger.row!!.lifecycle)
        assertFalse(f.attempts.hasAnyLeases())
        assertThrows(Exception::class.java) { f.attempts.finish(a, f.evidence(), f.context) }
        assertThrows(Exception::class.java) { f.attempts.recordAdoptionIntent(a.key, proof, f.context) }
        f.authority = f.authority.copy(proofSha256 = proof.digest())
        f.record = f.record!!.copy(phase = SavePhase.ADOPTING, directNormalization = proof)
        f.attempts.recordAdoptionIntent(a.key, proof, f.context); f.attempts.adopt(a.key, f.context)
        assertArrayEquals(f.rawBytes, f.io.bytes(f.rawFile(a.key))); assertArrayEquals(f.outputBytes, f.io.bytes(f.primary))
        assertFalse(f.io.exists(f.outputFile(a.key))); assertEquals(NormalizationLifecycle.ADOPTED, f.ledger.row!!.lifecycle)
        a.close()
    }

    @Test fun finishRejectsForgedEvidenceAndConsumesAttemptEvenWhenValidationFails() {
        val changes: List<(com.easysavevideo.app.save.normalization.MovRemuxEvidence) -> com.easysavevideo.app.save.normalization.MovRemuxEvidence> = listOf(
            { it.copy(rawSizeBytes = it.rawSizeBytes - 1) }, { it.copy(rawSha256 = "b".repeat(64)) },
            { it.copy(outputSizeBytes = it.outputSizeBytes + 1) }, { it.copy(outputSha256 = "b".repeat(64)) })
        for (change in changes) {
            val f = NormalizationDescriptorFixture(); val a = completed(f)
            assertThrows(Exception::class.java) { f.attempts.finish(a, change(f.evidence()), f.context) }
            assertThrows(Exception::class.java) { f.attempts.finish(a, f.evidence(), f.context) }
            assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle); assertFalse(f.attempts.hasAnyLeases())
            assertFalse(f.attempts.releaseUnconfirmed()); a.close()
        }
    }

    @Test fun sameSizeContentMutationWithPreservedMetadataIsCaughtByFreshFinishHashes() {
        for (raw in listOf(true, false)) {
            val f = NormalizationDescriptorFixture(); val a = completed(f)
            val path = if (raw) f.primary else f.outputFile(a.key); val altered = f.io.bytes(path).also { it[0] = (it[0] + 1).toByte() }
            f.io.mutate(path, altered, preserveTimes = true)
            assertThrows(Exception::class.java) { f.attempts.finish(a, f.evidence(), f.context) }
            assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle); assertFalse(f.attempts.hasAnyLeases())
            assertFalse(f.attempts.releaseUnconfirmed())
        }
    }

    @Test fun closingBeforeFinishAndChangingAuthorityOrOperationCannotPublishRetainedEvidence() {
        val changes: List<(NormalizationDescriptorFixture, OwnedMovRemuxAttempt) -> Unit> = listOf(
            { _, a -> a.close() }, { f, _ -> f.record = f.record!!.copy(operationId = "replaced") },
            { f, _ -> f.record = f.record!!.copy(phase = SavePhase.VERIFYING) },
            { f, _ -> f.authority = f.authority.copy(taskState = NormalizationTaskState.CANCELLED) },
            { f, _ -> f.record = f.record!!.copy(transfer = f.record!!.transfer!!.copy(responseMimeType = "video/mp4")) })
        for (change in changes) {
            val f = NormalizationDescriptorFixture(); val a = completed(f); change(f, a)
            assertThrows(Exception::class.java) { f.attempts.finish(a, f.evidence(), f.context) }
            assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle); a.close()
        }
    }

    @Test fun failedOwnedFinishRevokesAttemptBeforeRestoredAuthorityCanRetry() {
        for (which in 0..2) {
            val f = NormalizationDescriptorFixture(); val a = completed(f); val original = f.record
            val finishContext = when (which) {
                0 -> { f.record = f.record!!.copy(phase = SavePhase.VERIFYING); f.context }
                1 -> { f.record = f.record!!.copy(operationId = "replacement"); f.context }
                else -> f.context.copy(operationId = "wrong-finish-operation")
            }
            assertThrows(Exception::class.java) { f.attempts.finish(a, f.evidence(), finishContext) }
            f.record = original
            assertFalse(f.attempts.hasActiveSession(f.owner.artifactId)); assertFalse(f.attempts.hasAnyLeases())
            assertFalse(f.attempts.releaseUnconfirmed())
            assertThrows(Exception::class.java) { f.attempts.finish(a, f.evidence(), f.context) }
            assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle); a.close()
        }
    }

    @Test fun foreignOwnerFinishCannotRevokeTheTrueOwnersExactAttempt() {
        val f = NormalizationDescriptorFixture(); val a = completed(f)
        // Same generated identifiers do not make a different owner registry authoritative.
        val other = NormalizationDescriptorFixture()
        assertThrows(Exception::class.java) { other.attempts.finish(a, f.evidence(), f.context) }
        assertTrue(f.attempts.hasActiveSession(f.owner.artifactId)); assertFalse(other.attempts.hasAnyLeases())
        assertFalse(other.io.calls.any { it.operation == "open" || it.operation == "pread" })
        val proof = f.attempts.finish(a, f.evidence(), f.context)
        assertEquals(a.key.normalizationId, proof.normalizationId)
        assertEquals(NormalizationLifecycle.OUTPUT_READY, f.ledger.row!!.lifecycle)
        assertFalse(f.attempts.hasAnyLeases()); a.close()
    }

    @Test fun inspectionWithPreexistingHandlesIsUnreadableWithoutOpeningOrHashingRoles() {
        val f = NormalizationDescriptorFixture(); f.seedWriting(); f.priorHandles = true; f.io.calls.clear()
        assertEquals(NormalizationInspection.Unreadable, f.attempts.inspect(f.owner))
        assertFalse(f.io.calls.any { it.operation == "open" || it.operation == "pread" })
        assertFalse(f.attempts.hasAnyLeases())
    }

    @Test fun failedOutputSyncWithConfirmedClosesIsOrdinaryFailureNotReleaseUncertainty() {
        val f = NormalizationDescriptorFixture(); val a = completed(f)
        f.io.before = { if (it.operation == "fsync" && it.path == f.outputFile(a.key)) throw IOException("HOST_FSYNC") }
        assertThrows(Exception::class.java) { f.attempts.finish(a, f.evidence(), f.context) }
        assertFalse(f.attempts.hasAnyLeases()); assertFalse(f.attempts.releaseUnconfirmed())
        assertEquals(NormalizationLifecycle.WRITING, f.ledger.row!!.lifecycle)
    }
}
