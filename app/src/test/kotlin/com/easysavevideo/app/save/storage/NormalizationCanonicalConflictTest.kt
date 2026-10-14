package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Production remux owner paths with injected canonical reservation; not Android SQL/FD tests. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class NormalizationCanonicalConflictTest {
    private class Fixture {
        val f = NormalizationDescriptorFixture()
        var reader = false
        val owner = NormalizationAttemptOwner(f.ledger, f.files, { f.authority }, { f.record },
            f.lock, { f.storageClosed }, {}, f::checkOperation,
            ensureNoCanonicalReaders = { if (reader) throw SaveFailure(SaveError.BUSY) })
        fun proof(key: NormalizationKey) = DirectNormalizationProof(key.normalizationId, f.owner.taskId,
            f.record!!.request.networkResource!!.sourceIdentity, f.owner.artifactId, f.owner.commitId,
            f.raw.size, f.raw.sha256, f.sourceDigest, f.output.size, f.output.sha256, normalizationTestConservation())
        fun complete(): OwnedMovRemuxAttempt {
            val attempt = owner.prepare(f.owner, f.context, f.limits)
            val source = attempt.openSource(); val sink = attempt.openEmptyOutput()
            sink.write(ByteBuffer.wrap(f.outputBytes)); sink.close()
            attempt.openFinalizedOutput().close(); source.close()
            return attempt
        }
    }

    @Test fun readerReservationRejectsPrepareBeforeAnyFileOperation() {
        val x = Fixture(); x.reader = true; val calls = x.f.io.calls.size
        assertEquals(SaveError.BUSY, assertThrows(SaveFailure::class.java) {
            x.owner.prepare(x.f.owner, x.f.context, x.f.limits)
        }.code)
        assertEquals(calls, x.f.io.calls.size); assertNull(x.f.ledger.row)
    }

    @Test fun readerReservationMakesInspectionUnreadableWithoutReopeningPrimary() {
        val x = Fixture(); x.f.seedWriting(); x.reader = true
        val calls = x.f.io.calls.size
        assertEquals(NormalizationInspection.Unreadable, x.owner.inspect(x.f.owner))
        assertEquals(calls, x.f.io.calls.size)
    }

    @Test fun everyDedicatedMutationChecksReaderBeforeProtocolWork() {
        for (method in 0..2) {
            val x = Fixture(); val key = x.f.seedWriting(); val row = x.f.ledger.row!!
            x.reader = true; val calls = x.f.io.calls.size
            val failure = assertThrows(SaveFailure::class.java) {
                when (method) {
                    0 -> x.owner.recordAdoptionIntent(key, x.proof(key), x.f.context)
                    1 -> x.owner.adopt(key, x.f.context)
                    else -> x.owner.cleanup(key, row.revision)
                }
            }
            assertEquals(SaveError.BUSY, failure.code)
            assertEquals(calls, x.f.io.calls.size); assertEquals(row, x.f.ledger.row)
        }
    }

    @Test fun finishCannotHashOrPublishOutputWhileCanonicalReadIsReserved() {
        val x = Fixture(); val attempt = x.complete(); val row = x.f.ledger.row
        val calls = x.f.io.calls.size; x.reader = true
        assertEquals(SaveError.BUSY, assertThrows(SaveFailure::class.java) {
            x.owner.finish(attempt, x.f.evidence(), x.f.context)
        }.code)
        assertEquals(calls, x.f.io.calls.size); assertEquals(row, x.f.ledger.row)
        assertFalse(x.owner.hasAnyLeases()); assertFalse(x.owner.releaseUnconfirmed())
    }

    @Test fun terminalCleanupWaitsForReaderThenUsesExistingExactCleanupProtocol() {
        val x = Fixture(); val key = x.f.seedWriting(); val revision = x.f.ledger.row!!.revision
        x.f.record = x.f.record!!.copy(task = x.f.record!!.task.copy(state = TaskState.CANCELLED))
        x.f.authority = x.f.authority.copy(taskState = NormalizationTaskState.CANCELLED)
        x.reader = true
        assertEquals(SaveError.BUSY, assertThrows(SaveFailure::class.java) { x.owner.cleanup(key, revision) }.code)
        assertTrue(x.f.io.exists(x.f.primary))
        x.reader = false
        x.owner.cleanup(key, revision)
        assertEquals(NormalizationLifecycle.CLEANED, x.f.ledger.row!!.lifecycle)
        assertFalse(x.f.io.exists(x.f.primary)); assertFalse(x.f.io.exists(x.f.workspace))
    }
}
