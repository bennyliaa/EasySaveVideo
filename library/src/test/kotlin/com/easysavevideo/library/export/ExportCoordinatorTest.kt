package com.easysavevideo.library.export

import com.easysavevideo.library.MediaReadRef
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ExportCoordinatorTest {
    @Test fun verifiedMediaStoreOrdersDurableIntentCopyCloseReadbackPublish() {
        val rig = Rig()
        val result = rig.owner.run(rig.request)
        assertEquals(ExportPhase.SUCCEEDED, result.phase)
        assertEquals(ExportVerification.DESTINATION_VERIFIED, result.verification)
        assertArrayEquals(rig.bytes, rig.port.output.toByteArray())
        assertEquals(listOf("source", "create", "write", "flush", "write-close", "source-close", "readback", "readback-close", "publish"), rig.port.events)
        assertEquals(ExportPhase.CREATE_INTENT, rig.port.createPhase)
        assertEquals(ExportPhase.PUBLISH_INTENT, rig.port.publishPhase)
        assertTrue(rig.port.sourceClosed && rig.port.writerClosed && rig.port.readbackClosed)
        assertEquals(0, rig.port.cleanups)
    }

    @Test fun verifiedSafHasDistinctDestinationButNoSourceMutation() {
        val rig = Rig(ExportKind.SAF)
        assertEquals(ExportPhase.SUCCEEDED, rig.owner.run(rig.request).phase)
        assertArrayEquals("ordinary-media".toByteArray(), rig.bytes)
        assertEquals(1, rig.port.publishes)
    }

    @Test fun sameOperationReplaysWithoutDuplicatingOutputOrOpeningSource() {
        val rig = Rig()
        val first = rig.owner.run(rig.request)
        repeat(5) { assertEquals(first, rig.owner.run(rig.request)) }
        assertEquals(1, rig.port.creates)
        assertEquals(1, rig.port.sourceOpens)
    }

    @Test fun terminalHistoryBeyondOneThousandDoesNotConsumeActiveCapacityOrLoseReplay() {
        val rig = Rig()
        repeat(1_005) { n ->
            val request = rig.request.copy(operationId = "old-$n")
            rig.journal.records[request.operationId] = ExportRecord(request, phase = ExportPhase.FAILED)
        }
        val oldRequest = rig.journal.records.getValue("old-0").request
        assertEquals(ExportPhase.FAILED, rig.owner.run(oldRequest).phase)
        assertEquals(0, rig.port.sourceOpens)
        assertEquals(ExportPhase.SUCCEEDED, rig.owner.run(rig.request).phase)
        assertEquals(1, rig.port.creates)
        assertEquals(1_006, rig.journal.records.size)
        assertEquals(ExportPhase.FAILED, rig.owner.run(oldRequest).phase)
    }

    @Test fun intentionalNewOperationCreatesNewOutput() {
        val rig = Rig()
        rig.owner.run(rig.request)
        rig.port.output.reset()
        assertEquals(ExportPhase.SUCCEEDED, rig.owner.run(rig.request.copy(operationId = "next")).phase)
        assertEquals(2, rig.port.creates)
    }

    @Test fun operationFingerprintRejectsChangedSourceAndGrant() {
        val rig = Rig()
        rig.owner.run(rig.request)
        failure(ExportError.IDEMPOTENCY_CONFLICT) { rig.owner.run(rig.request.copy(source = MediaReadRef("other", 1, 1))) }
        failure(ExportError.IDEMPOTENCY_CONFLICT) { rig.owner.run(rig.request.copy(destination = ExportDestination(ExportKind.SAF, "otherGrant"))) }
        assertEquals(1, rig.port.creates)
    }

    @Test fun exactSourceRevisionAndGenerationAreRequired() {
        for (ref in listOf(MediaReadRef("other", 1, 1), MediaReadRef("item", 2, 1), MediaReadRef("item", 1, 2))) {
            val rig = Rig()
            rig.port.sourceRef = ref
            val result = rig.owner.run(rig.request)
            assertEquals(ExportPhase.FAILED, result.phase)
            assertEquals(ExportError.SOURCE_MISMATCH, result.error)
            assertEquals(0, rig.port.creates)
            assertTrue(rig.port.sourceClosed)
        }
    }

    @Test fun truncatedSourceFailsAndRemovesOnlyOwnedOutput() {
        val rig = Rig()
        rig.port.sourceBytes = rig.bytes.dropLast(1).toByteArray()
        assertFailedCleanup(rig, ExportError.SOURCE_MISMATCH)
    }

    @Test fun extraSourceByteFailsEvenAfterExpectedCount() {
        val rig = Rig()
        rig.port.sourceBytes = rig.bytes + byteArrayOf(1)
        assertFailedCleanup(rig, ExportError.SOURCE_MISMATCH)
        assertTrue(rig.port.output.size() <= rig.bytes.size)
    }

    @Test fun sameLengthWrongSourceHashFails() {
        val rig = Rig()
        rig.port.sourceBytes = rig.bytes.copyOf().also { it[0] = 0 }
        assertFailedCleanup(rig, ExportError.SOURCE_MISMATCH)
    }

    @Test fun sourceMustReachRealEofNotJustExpectedLength() {
        val rig = Rig()
        rig.port.sourceStream = object : ByteArrayInputStream(rig.bytes) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (available() == 0) throw IOException("secret-path")
                return super.read(bytes, offset, length)
            }
        }
        assertFailedCleanup(rig, ExportError.IO)
    }

    @Test fun zeroProgressSourceIsBoundedFailure() {
        val rig = Rig()
        var reads = 0
        rig.port.sourceStream = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { reads++; return 0 }
        }
        assertFailedCleanup(rig, ExportError.IO)
        assertEquals(8, reads)
    }

    @Test fun destinationTruncationCorruptionAndTrailingBytesNeverPublish() {
        val baseline = "ordinary-media".toByteArray()
        for (invalid in listOf(baseline.dropLast(1).toByteArray(), baseline + byteArrayOf(2), baseline.copyOf().also { it[0] = 3 })) {
            val rig = Rig()
            rig.port.readbackBytes = invalid
            assertFailedCleanup(rig, ExportError.DESTINATION_MISMATCH)
            assertTrue(rig.port.readbackClosed)
        }
    }

    @Test fun destinationMustReachFullEof() {
        val rig = Rig()
        rig.port.readbackStream = object : ByteArrayInputStream(rig.bytes) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (available() == 0) throw IOException("do-not-log")
                return super.read(bytes, offset, length)
            }
        }
        assertFailedCleanup(rig, ExportError.IO)
    }

    @Test fun safUnsupportedReadbackIsExplicitlyUnverifiedNotSuccess() {
        val rig = Rig(ExportKind.SAF)
        rig.port.unsupportedReadback = true
        val record = rig.owner.run(rig.request)
        assertEquals(ExportPhase.COPIED_UNVERIFIED, record.phase)
        assertEquals(ExportVerification.READBACK_UNSUPPORTED, record.verification)
        assertEquals(ExportError.READBACK_UNSUPPORTED, record.error)
        assertEquals(0, rig.port.publishes)
        assertEquals(0, rig.port.cleanups)
        assertEquals(record, rig.owner.run(rig.request))
    }

    @Test fun explicitUnverifiedSafCleanupRemovesOwnedCopyWithoutRewrite() {
        val rig = Rig(ExportKind.SAF)
        rig.port.unsupportedReadback = true
        val copied = rig.owner.run(rig.request)
        rig.port.reconciliation = ExportReconciliation.Owned(copied.output!!, false, true)
        val cleaned = rig.owner.cleanupUnverified("operation")
        assertEquals(ExportPhase.CANCELLED, cleaned.phase)
        assertEquals(ExportCleanup.REMOVED, cleaned.cleanup)
        assertEquals(1, rig.port.creates)
        assertEquals(1, rig.port.cleanups)
        assertEquals(0, rig.port.publishes)
    }

    @Test fun uncertainSafCleanupRemainsPendingAndCannotClaimRemoved() {
        val rig = Rig(ExportKind.SAF)
        rig.port.unsupportedReadback = true
        val copied = rig.owner.run(rig.request)
        rig.port.reconciliation = ExportReconciliation.Owned(copied.output!!, false, true)
        rig.port.cleanupResult = ExportCleanup.FAILED
        assertEquals(ExportPhase.CLEANUP_PENDING, rig.owner.cleanupUnverified("operation").phase)
        rig.port.cleanupResult = ExportCleanup.REMOVED
        assertEquals(ExportPhase.CANCELLED, rig.owner.cleanupUnverified("operation").phase)
        assertEquals(1, rig.port.creates)
    }

    @Test fun mediaStoreAndShareCannotTreatUnsupportedReadbackAsSuccess() {
        for (kind in listOf(ExportKind.MEDIA_STORE, ExportKind.SHARE_COPY)) {
            val rig = Rig(kind)
            rig.port.unsupportedReadback = true
            assertFailedCleanup(rig, ExportError.READBACK_UNSUPPORTED)
        }
    }

    @Test fun preStartCancellationCreatesNoSourceOrOutput() {
        val rig = Rig()
        rig.journal.create(rig.request)
        assertEquals(ExportCancellation.ACCEPTED, rig.owner.cancel("operation"))
        assertEquals(ExportPhase.CANCELLED, rig.owner.run(rig.request).phase)
        assertEquals(0, rig.port.creates)
        assertEquals(0, rig.port.sourceOpens)
    }

    @Test fun cancellationAtEveryCancellableIoBoundaryPreventsPublish() {
        for (stage in listOf("source", "create", "write", "flush", "write-close", "source-close", "readback", "readback-close")) {
            val rig = Rig()
            rig.port.hook = { current -> if (current == stage) assertEquals(ExportCancellation.ACCEPTED, rig.owner.cancel("operation")) }
            val record = rig.owner.run(rig.request)
            assertEquals("stage=$stage", ExportPhase.CANCELLED, record.phase)
            assertEquals("stage=$stage", 0, rig.port.publishes)
        }
    }

    @Test fun cancellationDuringUnsupportedSafReadbackStillCleans() {
        val rig = Rig(ExportKind.SAF)
        rig.port.unsupportedReadback = true
        rig.port.hook = { if (it == "readback") rig.owner.cancel("operation") }
        val result = rig.owner.run(rig.request)
        assertEquals(ExportPhase.CANCELLED, result.phase)
        assertEquals(1, rig.port.cleanups)
    }

    @Test fun concurrentCancelDuringFinalReadbackCloseIsAcceptedBeforeUnblock() {
        assertConcurrentCancellation(recoverPrepared = false, blockedStage = "readback-close")
    }

    @Test fun recoverPreparedDoesNotHoldCommitGateAcrossSourceOpening() {
        assertConcurrentCancellation(recoverPrepared = true, blockedStage = "source")
    }

    @Test fun cancelAfterDurablePublishDecisionReportsTooLate() {
        val rig = Rig()
        rig.port.hook = { if (it == "publish") {
            assertEquals(ExportPhase.PUBLISH_INTENT, rig.journal.get("operation")!!.phase)
            assertEquals(ExportCancellation.TOO_LATE, rig.owner.cancel("operation"))
        } }
        assertEquals(ExportPhase.SUCCEEDED, rig.owner.run(rig.request).phase)
        assertEquals(ExportCancellation.TOO_LATE, rig.owner.cancel("operation"))
        assertEquals(ExportCancellation.NOT_FOUND, rig.owner.cancel("missing"))
    }

    @Test fun blockedPublicationDoesNotBlockAnotherOperationsCancellation() {
        val rig = Rig()
        val other = rig.request.copy(operationId = "other")
        rig.journal.create(other)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        rig.port.hook = { if (it == "publish") { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val worker = Thread { try { rig.owner.run(rig.request) } catch (error: Throwable) { failure.set(error) } }
        worker.start()
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        val cancellation = AtomicReference<ExportCancellation>()
        val cancel = Thread { cancellation.set(rig.owner.cancel("other")); done.countDown() }
        cancel.start()
        try {
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(ExportCancellation.ACCEPTED, cancellation.get())
            assertEquals(ExportCancellation.TOO_LATE, rig.owner.cancel("operation"))
        } finally { release.countDown(); worker.join(5_000); cancel.join(5_000) }
        assertNull(failure.get())
        assertEquals(ExportPhase.SUCCEEDED, rig.journal.get("operation")!!.phase)
        assertTrue(rig.journal.get("other")!!.cancelRequested)
    }

    @Test fun concurrentSameOperationIsStatusReplayRatherThanSecondCreate() {
        val rig = Rig()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        rig.port.hook = { if (it == "source") { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val worker = Thread { try { rig.owner.run(rig.request) } catch (error: Throwable) { failure.set(error) } }
        worker.start()
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        try {
            assertEquals(ExportPhase.PREPARED, rig.owner.run(rig.request).phase)
            assertEquals(1, rig.port.sourceOpens)
        } finally { release.countDown(); worker.join(5_000) }
        assertNull(failure.get())
        assertEquals(1, rig.port.creates)
    }

    @Test fun cleanupFailureRemainsRetryableWithoutRecreate() {
        val rig = Rig()
        rig.port.sourceBytes = byteArrayOf(1)
        rig.port.cleanupResult = ExportCleanup.FAILED
        val failed = rig.owner.run(rig.request)
        assertEquals(ExportPhase.CLEANUP_PENDING, failed.phase)
        assertEquals(ExportCleanup.FAILED, failed.cleanup)
        rig.port.cleanupResult = ExportCleanup.REMOVED
        rig.port.reconciliation = ExportReconciliation.Owned(failed.output!!, false, true)
        assertEquals(ExportPhase.FAILED, rig.owner.recover("operation").phase)
        assertEquals(1, rig.port.creates)
        assertEquals(2, rig.port.cleanups)
    }

    @Test fun failedSafCleanupTruthfullyRetainsPartialOutput() {
        val rig = Rig(ExportKind.SAF)
        rig.port.readbackBytes = byteArrayOf(4)
        rig.port.cleanupResult = ExportCleanup.UNCONFIRMED
        val result = rig.owner.run(rig.request)
        assertEquals(ExportPhase.CLEANUP_PENDING, result.phase)
        assertEquals(ExportCleanup.UNCONFIRMED, result.cleanup)
        assertEquals(0, rig.port.publishes)
    }

    @Test fun everyUncertainDescriptorReleaseBlocksCleanupAndPublication() {
        for (stage in listOf("source-close", "write-close", "readback-close")) {
            val rig = Rig()
            rig.port.uncertainStage = stage
            val record = rig.owner.run(rig.request)
            assertEquals(ExportPhase.CLEANUP_PENDING, record.phase)
            assertEquals(ExportError.RELEASE_UNCONFIRMED, record.error)
            assertEquals(ExportCleanup.UNCONFIRMED, record.cleanup)
            assertEquals(0, rig.port.publishes)
            assertEquals(0, rig.port.cleanups)
            rig.port.reconciliation = ExportReconciliation.Owned(record.output!!, false, false)
            assertEquals(ExportPhase.CLEANUP_PENDING, rig.owner.recover("operation").phase)
            assertEquals(0, rig.port.cleanups)
            rig.port.reconciliation = ExportReconciliation.Owned(record.output, false, true)
            assertEquals(ExportPhase.FAILED, rig.owner.recover("operation").phase)
            assertEquals(1, rig.port.cleanups)
        }
    }

    @Test fun throwingCloseAlsoRetainsOwnership() {
        val rig = Rig()
        rig.port.throwStage = "write-close"
        val result = rig.owner.run(rig.request)
        assertEquals(ExportError.RELEASE_UNCONFIRMED, result.error)
        assertEquals(0, rig.port.cleanups)
        assertTrue(rig.port.sourceClosed)
    }

    @Test fun createThenThrowNeedsReconciliationAndCannotDuplicate() {
        val rig = Rig()
        rig.port.throwStage = "create"
        val record = rig.owner.run(rig.request)
        assertEquals(ExportPhase.RECONCILIATION_REQUIRED, record.phase)
        assertEquals(ExportError.CREATE_UNCERTAIN, record.error)
        assertEquals(0, rig.port.cleanups)
        assertEquals(record, rig.owner.run(rig.request))
        rig.port.reconciliation = ExportReconciliation.Unknown
        assertEquals(ExportPhase.RECONCILIATION_REQUIRED, rig.owner.recover("operation").phase)
        assertEquals(1, rig.port.creates)
    }

    @Test fun createdButUnrecordedJournalFailureCannotPretendAbsence() {
        val rig = Rig()
        rig.journal.failPhase = ExportPhase.COPYING
        val result = rig.owner.run(rig.request)
        assertEquals(ExportPhase.RECONCILIATION_REQUIRED, result.phase)
        assertNull(result.output)
        assertEquals(ExportError.CREATE_UNCERTAIN, result.error)
        assertEquals(0, rig.port.cleanups)
        rig.port.reconciliation = ExportReconciliation.Owned(ExportOutput("output-operation", ExportKind.MEDIA_STORE), false, true)
        assertEquals(ExportPhase.FAILED, rig.owner.recover("operation").phase)
        assertEquals(1, rig.port.creates)
        assertEquals(1, rig.port.cleanups)
    }

    @Test fun durableCreateIntentIsWrittenBeforeExternalOutput() {
        val rig = Rig()
        rig.journal.failPhase = ExportPhase.CREATE_INTENT
        assertEquals(ExportPhase.FAILED, rig.owner.run(rig.request).phase)
        assertEquals(0, rig.port.creates)
    }

    @Test fun recoveryOfAbsentCreationDoesNotInventSuccessOrCreateAgain() {
        val rig = Rig()
        rig.journal.create(rig.request)
        rig.journal.put(ExportRecord(rig.request, ExportPhase.CREATE_INTENT, evidence = rig.evidence))
        rig.port.reconciliation = ExportReconciliation.Absent
        val result = rig.owner.recover("operation")
        assertEquals(ExportPhase.FAILED, result.phase)
        assertEquals(ExportCleanup.REMOVED, result.cleanup)
        assertEquals(0, rig.port.creates)
    }

    @Test fun crashAfterPublishIsRecoveredOnlyWithDurableVerifiedDecision() {
        val rig = Rig()
        rig.port.throwStage = "publish"
        val result = rig.owner.run(rig.request)
        assertEquals(ExportPhase.RECONCILIATION_REQUIRED, result.phase)
        assertEquals(ExportError.PUBLISH_UNCERTAIN, result.error)
        assertEquals(0, rig.port.cleanups)
        assertEquals(ExportCancellation.TOO_LATE, rig.owner.cancel("operation"))
        rig.port.reconciliation = ExportReconciliation.Owned(result.output!!, true, true)
        assertEquals(ExportPhase.SUCCEEDED, rig.owner.recover("operation").phase)
        assertEquals(1, rig.port.publishes)
    }

    @Test fun safVerifiedCommitRecoveryDoesNotDeleteBecauseProviderHasNoPendingFlag() {
        val rig = Rig(ExportKind.SAF)
        rig.port.throwStage = "publish"
        val interrupted = rig.owner.run(rig.request)
        assertEquals(ExportError.PUBLISH_UNCERTAIN, interrupted.error)
        rig.port.reconciliation = ExportReconciliation.Owned(interrupted.output!!, false, true)
        assertEquals(ExportPhase.SUCCEEDED, rig.owner.recover("operation").phase)
        assertEquals(0, rig.port.cleanups)
        assertEquals(1, rig.port.publishes)
    }

    @Test fun preadoptedSafDocumentIsCleanedWhenSourceCannotOpen() {
        val rig = Rig(ExportKind.SAF)
        rig.journal.create(rig.request)
        rig.journal.put(ExportRecord(rig.request, output = ExportOutput("output-operation", ExportKind.SAF)))
        rig.port.throwStage = "source"
        val result = rig.owner.run(rig.request)
        assertEquals(ExportPhase.FAILED, result.phase)
        assertEquals(ExportCleanup.REMOVED, result.cleanup)
        assertEquals(1, rig.port.cleanups)
        assertEquals(0, rig.port.creates)
    }

    @Test fun preadoptedSafDocumentIsCleanedOnCancellationBeforeSourceOpen() {
        val rig = Rig(ExportKind.SAF)
        rig.journal.create(rig.request)
        rig.journal.put(ExportRecord(rig.request, output = ExportOutput("output-operation", ExportKind.SAF)))
        rig.owner.cancel("operation")
        assertEquals(ExportPhase.CANCELLED, rig.owner.run(rig.request).phase)
        assertEquals(1, rig.port.cleanups)
        assertEquals(0, rig.port.sourceOpens)
    }

    @Test fun uncertainPublishStillPendingCanBeCleanedWithoutSecondPublish() {
        val rig = Rig()
        rig.port.throwStage = "publish"
        val result = rig.owner.run(rig.request)
        rig.port.reconciliation = ExportReconciliation.Owned(result.output!!, false, true)
        assertEquals(ExportPhase.FAILED, rig.owner.recover("operation").phase)
        assertEquals(1, rig.port.cleanups)
        assertEquals(1, rig.port.publishes)
    }

    @Test fun unexpectedPublishedOutputWithoutVerifiedIntentIsNeverDeletedOrSucceeded() {
        val rig = Rig()
        rig.journal.create(rig.request)
        val output = ExportOutput("output-operation", ExportKind.MEDIA_STORE)
        rig.journal.put(ExportRecord(rig.request, ExportPhase.COPYING, output, rig.evidence))
        rig.port.reconciliation = ExportReconciliation.Owned(output, true, true)
        assertEquals(ExportPhase.RECONCILIATION_REQUIRED, rig.owner.recover("operation").phase)
        assertEquals(0, rig.port.cleanups)
    }

    @Test fun unrelatedRecoveredOutputCannotBeUsedOrDeleted() {
        val rig = Rig()
        rig.port.sourceBytes = byteArrayOf(1)
        rig.port.cleanupResult = ExportCleanup.FAILED
        rig.owner.run(rig.request)
        rig.port.reconciliation = ExportReconciliation.Owned(ExportOutput("unrelated", ExportKind.MEDIA_STORE), false, true)
        assertEquals(ExportPhase.RECONCILIATION_REQUIRED, rig.owner.recover("operation").phase)
        assertEquals(1, rig.port.cleanups)
    }

    @Test fun cancellationAndCleanupSurviveNewCoordinatorInstance() {
        val rig = Rig()
        rig.port.hook = { if (it == "flush") rig.owner.cancel("operation") }
        rig.port.cleanupResult = ExportCleanup.FAILED
        val cancelled = rig.owner.run(rig.request)
        rig.port.reconciliation = ExportReconciliation.Owned(cancelled.output!!, false, true)
        rig.port.cleanupResult = ExportCleanup.REMOVED
        val recovered = ExportCoordinator(rig.journal, rig.port).recover("operation")
        assertEquals(ExportPhase.CANCELLED, recovered.phase)
        assertTrue(recovered.cancelRequested)
        assertEquals(0, rig.port.publishes)
    }

    @Test fun publishedOutcomePersistsEvenIfTerminalJournalWriteThrowsAfterCommit() {
        val rig = Rig()
        rig.journal.failPhase = ExportPhase.SUCCEEDED
        rig.journal.failAfterPersist = true
        assertEquals(ExportPhase.SUCCEEDED, rig.owner.run(rig.request).phase)
        assertEquals(0, rig.port.cleanups)
    }

    @Test fun recoveryBatchesAreBounded() {
        val rig = Rig()
        assertThrows(IllegalArgumentException::class.java) { rig.owner.recoverPending(0) }
        assertThrows(IllegalArgumentException::class.java) { rig.owner.recoverPending(ExportLimits.MAX_RECOVERY_BATCH + 1) }
        rig.journal.create(rig.request)
        assertEquals(1, rig.owner.recoverPending(1).size)
    }

    private fun assertConcurrentCancellation(recoverPrepared: Boolean, blockedStage: String) {
        val rig = Rig()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelDone = CountDownLatch(1)
        val result = AtomicReference<ExportRecord>()
        val thrown = AtomicReference<Throwable>()
        rig.port.hook = { if (it == blockedStage) { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        if (recoverPrepared) rig.journal.create(rig.request)
        val worker = Thread {
            try { result.set(if (recoverPrepared) rig.owner.recover("operation") else rig.owner.run(rig.request)) }
            catch (failure: Throwable) { thrown.set(failure) }
        }
        val cancellation = AtomicReference<ExportCancellation>()
        worker.start()
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        val cancel = Thread { cancellation.set(rig.owner.cancel("operation")); cancelDone.countDown() }
        cancel.start()
        try {
            assertTrue("cancellation must persist while native IO is blocked", cancelDone.await(2, TimeUnit.SECONDS))
            assertEquals(ExportCancellation.ACCEPTED, cancellation.get())
        } finally { release.countDown(); worker.join(5_000); cancel.join(5_000) }
        assertFalse(worker.isAlive)
        assertNull(thrown.get())
        assertEquals(ExportPhase.CANCELLED, result.get().phase)
        assertEquals(0, rig.port.publishes)
    }

    private fun assertFailedCleanup(rig: Rig, error: ExportError) {
        val result = rig.owner.run(rig.request)
        assertEquals(ExportPhase.FAILED, result.phase)
        assertEquals(error, result.error)
        assertEquals(ExportCleanup.REMOVED, result.cleanup)
        assertEquals(0, rig.port.publishes)
        assertEquals(1, rig.port.cleanups)
        assertTrue(rig.port.sourceClosed && rig.port.writerClosed)
        assertArrayEquals("ordinary-media".toByteArray(), rig.bytes)
    }
    private fun failure(code: ExportError, action: () -> Unit) {
        assertEquals(code, assertThrows(ExportFailure::class.java, action).code)
    }

    private class Rig(kind: ExportKind = ExportKind.MEDIA_STORE) {
        val bytes = "ordinary-media".toByteArray()
        val evidence = ExportEvidence(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).exportHex())
        val request = ExportRequest("operation", MediaReadRef("item", 1, 1), ExportDestination(kind, "grant"))
        val journal = MemoryJournal()
        val port = FakePort(journal, bytes, evidence)
        val owner = ExportCoordinator(journal, port)
    }
    private class MemoryJournal : ExportJournal {
        val records = linkedMapOf<String, ExportRecord>()
        var failPhase: ExportPhase? = null
        var failAfterPersist = false
        override fun create(request: ExportRequest): ExportRecord {
            records[request.operationId]?.let {
                if (it.request != request) throw ExportFailure(ExportError.IDEMPOTENCY_CONFLICT)
                return it
            }
            if (records.values.count { !it.phase.terminal } >= ExportLimits.MAX_ACTIVE) throw ExportFailure(ExportError.CAPACITY)
            return ExportRecord(request).also { records[request.operationId] = it }
        }
        override fun get(operationId: String) = records[operationId]
        override fun put(record: ExportRecord) {
            val before = records.getValue(record.operationId)
            check(before.request == record.request && (!before.cancelRequested || record.cancelRequested))
            if (record.phase == failPhase) {
                failPhase = null
                if (failAfterPersist) records[record.operationId] = record
                throw IOException("not-logged")
            }
            records[record.operationId] = record
        }
        override fun unfinished(limit: Int) = records.values.filter { !it.phase.terminal }.take(limit)
    }
    private class FakePort(private val journal: MemoryJournal, bytes: ByteArray, val evidence: ExportEvidence) : ExportPort {
        var sourceRef = MediaReadRef("item", 1, 1)
        var sourceBytes = bytes.copyOf()
        var sourceStream: InputStream? = null
        var readbackBytes: ByteArray? = null
        var readbackStream: InputStream? = null
        var unsupportedReadback = false
        var throwStage: String? = null
        var uncertainStage: String? = null
        var cleanupResult = ExportCleanup.REMOVED
        var reconciliation: ExportReconciliation = ExportReconciliation.Unknown
        var hook: (String) -> Unit = {}
        val events = mutableListOf<String>()
        val output = ByteArrayOutputStream()
        var sourceOpens = 0
        var creates = 0
        var publishes = 0
        var cleanups = 0
        var sourceClosed = false
        var writerClosed = false
        var readbackClosed = false
        var createPhase: ExportPhase? = null
        var publishPhase: ExportPhase? = null
        private fun stage(name: String) { events += name; hook(name); if (throwStage == name) throw IOException("private-provider-information") }
        override fun openSource(request: ExportRequest): ExportSource {
            sourceOpens++
            stage("source")
            return ExportSource(sourceRef, evidence, object : ExportInput {
                override val stream = sourceStream ?: ByteArrayInputStream(sourceBytes)
                override fun close(): ExportRelease { stage("source-close"); sourceClosed = true; return status("source-close") }
            })
        }
        override fun create(record: ExportRecord): ExportOutput {
            creates++
            createPhase = journal.get(record.operationId)!!.phase
            stage("create")
            return ExportOutput("output-${record.operationId}", record.request.destination.kind)
        }
        override fun openWrite(record: ExportRecord, output: ExportOutput): ExportWrite {
            stage("write")
            return object : ExportWrite {
                override val stream = object : OutputStream() {
                    override fun write(value: Int) { this@FakePort.output.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { this@FakePort.output.write(bytes, offset, length) }
                    override fun flush() { stage("flush") }
                }
                override fun close(): ExportRelease { stage("write-close"); writerClosed = true; return status("write-close") }
            }
        }
        override fun openReadback(record: ExportRecord, output: ExportOutput): ExportReadback {
            stage("readback")
            if (unsupportedReadback) return ExportReadback.Unsupported
            return ExportReadback.Opened(object : ExportInput {
                override val stream = readbackStream ?: ByteArrayInputStream(readbackBytes ?: this@FakePort.output.toByteArray())
                override fun close(): ExportRelease { stage("readback-close"); readbackClosed = true; return status("readback-close") }
            })
        }
        override fun publish(record: ExportRecord, output: ExportOutput) {
            publishes++
            publishPhase = journal.get(record.operationId)!!.phase
            stage("publish")
        }
        override fun cleanup(record: ExportRecord, output: ExportOutput): ExportCleanup {
            check(record.output == output)
            cleanups++
            stage("cleanup")
            return cleanupResult
        }
        override fun reconcile(record: ExportRecord) = reconciliation
        private fun status(stage: String) = if (uncertainStage == stage) ExportRelease.UNCONFIRMED else ExportRelease.RELEASED
    }
}
