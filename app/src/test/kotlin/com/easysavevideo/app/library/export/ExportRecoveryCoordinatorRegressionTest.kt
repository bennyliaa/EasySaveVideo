package com.easysavevideo.app.library.export

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

/** Real unchanged ExportCoordinator; all journal/capability/stream ports are isolated in memory. */
class ExportRecoveryCoordinatorRegressionTest {
    private class Journal : ExportJournal {
        val rows = linkedMapOf<String, ExportRecord>()
        val requested = mutableListOf<ExportRequest>()
        override fun create(request: ExportRequest): ExportRecord {
            requested += request
            val prior = rows[request.operationId]
            require(prior == null || prior.request == request)
            return prior ?: ExportRecord(request).also { rows[request.operationId] = it }
        }
        override fun get(operationId: String) = rows[operationId]
        override fun put(record: ExportRecord) {
            val previous = rows.getValue(record.operationId)
            require(previous.request == record.request)
            require(!previous.cancelRequested || record.cancelRequested)
            rows[record.operationId] = record
        }
        override fun unfinished(limit: Int) = rows.values.filter { !it.phase.terminal }.take(limit)
    }
    private class Rig(kind: ExportKind = ExportKind.MEDIA_STORE) {
        val request = ExportRequest("accepted-original", MediaReadRef("original-item", 4, 2), ExportDestination(kind, "issued-original-grant"))
        val bytes = "isolated-media".toByteArray()
        val journal = Journal()
        val worker = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val operations = mutableListOf<ExportRequest>()
        val released = mutableListOf<ExportRecord>()
        var createCount = 0
        var writeCount = 0
        var sourceCount = 0
        var cleanupCount = 0
        var cleanupResult = ExportCleanup.REMOVED
        var stopOriginal = false
        var reviewFailure = false
        var recoveryCount = 0
        var pending = 0L
        var check = ExportRecoveryCheckState()
        var reconcile: ExportReconciliation = ExportReconciliation.Unknown
        val output = ExportOutput("accepted-original", kind)
        private var written = ByteArrayOutputStream()
        val port = object : ExportPort {
            private fun accepted(found: ExportRequest) {
                assertEquals(request, found)
                operations += found
            }
            override fun openSource(request: ExportRequest): ExportSource {
                accepted(request); sourceCount++
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                return ExportSource(request.source, ExportEvidence(bytes.size.toLong(), digest), input(bytes))
            }
            override fun create(record: ExportRecord): ExportOutput {
                accepted(record.request); createCount++
                assertEquals(ExportPhase.CREATE_INTENT, record.phase)
                return output
            }
            override fun openWrite(record: ExportRecord, output: ExportOutput): ExportWrite {
                accepted(record.request); assertEquals(this@Rig.output, output); writeCount++
                written = ByteArrayOutputStream()
                return object : ExportWrite {
                    override val stream = written
                    override fun close() = ExportRelease.RELEASED
                }
            }
            override fun openReadback(record: ExportRecord, output: ExportOutput): ExportReadback {
                accepted(record.request); assertEquals(this@Rig.output, output)
                return ExportReadback.Opened(input(written.toByteArray()))
            }
            override fun publish(record: ExportRecord, output: ExportOutput) {
                accepted(record.request); assertEquals(this@Rig.output, output)
            }
            override fun cleanup(record: ExportRecord, output: ExportOutput): ExportCleanup {
                accepted(record.request); assertEquals(this@Rig.output, output); cleanupCount++
                return cleanupResult
            }
            override fun reconcile(record: ExportRecord): ExportReconciliation {
                accepted(record.request)
                return reconcile
            }
        }
        val coordinator = ExportCoordinator(journal, port)
        val controller = ExportRecoveryCheckController(
            enqueue = { worker.add(it) }, post = { main.add(it) }, isRuntimeCurrent = { true },
            stopInterrupted = {
                if (stopOriginal) coordinator.cancel(request.operationId)
                stopOriginal
            },
            recoverPending = { recoveryCount++; coordinator.recoverPending() },
            releaseFinishedGrant = { assertEquals(request, it.request); released += it },
            readReviews = {
                if (reviewFailure) throw IllegalStateException("isolated review unavailable")
                journal.rows.values.count { !it.phase.terminal }.toLong()
            },
            onUpdate = { value, result, _ -> check = value; result?.let { pending = it.reviews } },
        )
        private fun input(value: ByteArray) = object : ExportInput {
            override val stream = ByteArrayInputStream(value)
            override fun close() = ExportRelease.RELEASED
        }
        fun pass() {
            assertTrue(controller.start()); assertFalse(controller.start())
            while (main.isNotEmpty()) main.removeFirst().invoke()
            worker.removeFirst().invoke()
            while (main.isNotEmpty()) main.removeFirst().invoke()
        }
        fun assertOnlyOriginalAuthority() {
            assertEquals(setOf(request.operationId), journal.rows.keys)
            assertTrue(journal.requested.all { it == request })
            assertTrue(operations.all { it == request })
            assertEquals(request, journal.get(request.operationId)!!.request)
        }
    }

    @Test fun originalPreparedRequestContinuesOnceAndRepeatedRecoveryCreatesNoSecondCopy() {
        for (kind in listOf(ExportKind.MEDIA_STORE, ExportKind.SAF)) {
            val rig = Rig(kind); rig.journal.create(rig.request)
            rig.pass(); rig.pass()
            assertEquals(ExportPhase.SUCCEEDED, rig.journal.get(rig.request.operationId)!!.phase)
            assertEquals(1, rig.createCount); assertEquals(1, rig.writeCount); assertEquals(1, rig.sourceCount)
            assertEquals(0, rig.cleanupCount); assertEquals(1, rig.released.size)
            assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
            assertEquals(0L, rig.pending); assertEquals(2, rig.recoveryCount)
            rig.assertOnlyOriginalAuthority()
        }
    }

    @Test fun reviewFailureAfterPreparedCompletionCanRetryWithoutAnotherSourceOpenOrCopy() {
        val rig = Rig(); rig.journal.create(rig.request); rig.reviewFailure = true
        rig.pass(); assertEquals(ExportRecoveryStatus.FAILED, rig.check.status)
        assertEquals(1, rig.createCount); assertEquals(1, rig.writeCount)
        assertEquals(0L, rig.pending) // Count was unavailable, not synthesized from the exception.
        rig.reviewFailure = false; rig.pass()
        assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
        assertEquals(1, rig.createCount); assertEquals(1, rig.writeCount); assertEquals(1, rig.sourceCount)
        rig.assertOnlyOriginalAuthority()
    }

    @Test fun uncertainExistingOutputNeverRecreatesOrExpandsAuthorityAcrossRepeatedPasses() {
        for (kind in listOf(ExportKind.MEDIA_STORE, ExportKind.SAF)) {
            val rig = Rig(kind)
            rig.journal.create(rig.request)
            rig.journal.put(ExportRecord(rig.request, ExportPhase.CREATE_INTENT))
            rig.pass(); rig.pass()
            assertEquals(ExportPhase.RECONCILIATION_REQUIRED, rig.journal.get(rig.request.operationId)!!.phase)
            assertEquals(0, rig.createCount); assertEquals(0, rig.writeCount); assertEquals(0, rig.sourceCount)
            assertEquals(0, rig.cleanupCount); assertEquals(1L, rig.pending)
            assertEquals(ExportRecoveryStatus.CHECKED, rig.check.status)
            assertTrue(offerExportRecoveryRetry(rig.check, rig.pending))
            rig.assertOnlyOriginalAuthority()
        }
    }

    @Test fun ownedPendingOutputUsesExistingCleanupAndReleaseContractWithoutWritingAgain() {
        val rig = Rig(ExportKind.SAF)
        rig.journal.create(rig.request)
        rig.journal.put(ExportRecord(rig.request, ExportPhase.COPYING, output = rig.output))
        rig.reconcile = ExportReconciliation.Owned(rig.output, published = false, resourcesReleased = true)
        rig.pass(); rig.pass()
        assertEquals(ExportPhase.FAILED, rig.journal.get(rig.request.operationId)!!.phase)
        assertEquals(1, rig.cleanupCount); assertEquals(1, rig.released.size)
        assertEquals(0, rig.createCount); assertEquals(0, rig.writeCount); assertEquals(0, rig.sourceCount)
        rig.assertOnlyOriginalAuthority()
    }

    @Test fun unconfirmedCleanupRemainsRetryableAndDoesNotStartAnotherExport() {
        val rig = Rig(ExportKind.SAF)
        rig.journal.create(rig.request)
        rig.journal.put(ExportRecord(rig.request, ExportPhase.CLEANUP_PENDING, output = rig.output))
        rig.reconcile = ExportReconciliation.Owned(rig.output, published = false, resourcesReleased = true)
        rig.cleanupResult = ExportCleanup.FAILED
        rig.pass(); rig.pass()
        assertEquals(ExportPhase.CLEANUP_PENDING, rig.journal.get(rig.request.operationId)!!.phase)
        assertEquals(1L, rig.pending); assertTrue(offerExportRecoveryRetry(rig.check, rig.pending))
        assertEquals(2, rig.cleanupCount); assertEquals(0, rig.createCount); assertEquals(0, rig.writeCount)
        rig.assertOnlyOriginalAuthority()
    }

    @Test fun unrelatedReconciledOutputCannotBeAdoptedCleanedOrReplaced() {
        val rig = Rig(ExportKind.SAF)
        rig.journal.create(rig.request)
        rig.journal.put(ExportRecord(rig.request, ExportPhase.COPYING, output = rig.output))
        rig.reconcile = ExportReconciliation.Owned(ExportOutput("unrelated", ExportKind.SAF), false, true)
        rig.pass(); rig.pass()
        assertEquals(ExportPhase.RECONCILIATION_REQUIRED, rig.journal.get(rig.request.operationId)!!.phase)
        assertEquals(rig.output, rig.journal.get(rig.request.operationId)!!.output)
        assertEquals(0, rig.cleanupCount); assertEquals(0, rig.createCount); assertEquals(0, rig.writeCount)
        rig.assertOnlyOriginalAuthority()
    }

    @Test fun existingInterruptedBatchCancellationStillPrecedesPreparedRecovery() {
        val rig = Rig(); rig.journal.create(rig.request); rig.stopOriginal = true
        rig.pass(); rig.pass()
        assertEquals(ExportPhase.CANCELLED, rig.journal.get(rig.request.operationId)!!.phase)
        assertEquals(0, rig.createCount); assertEquals(0, rig.writeCount); assertEquals(0, rig.sourceCount)
        assertEquals(1, rig.released.size)
        rig.assertOnlyOriginalAuthority()
    }
}
