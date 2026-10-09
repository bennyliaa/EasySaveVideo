package com.easysavevideo.app.library.destination

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.destination.*
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synthetic bytes and in-memory adapters, exercising freshly compiled production ownership code.
 * This is not an Android storage, media decoder or MediaStore integration test. */
class IndependentDestinationReviewTest {
    private val source = MediaReadRef("published", 7, 3)
    private fun admission() = OrdinaryDestinationAdmission("creation", OrdinaryDestinationChoice.ALBUM_COPY,
        "operation", "grant").bindVerifiedSource(source)

    private class DestJournal(private val row: OrdinaryDestinationAdmission) : OrdinaryDestinationJournal {
        override fun preference() = error("Routing must never read current preference")
        override fun setPreference(value: OrdinaryDestinationPreference) = error("Not permitted")
        override fun admit(creationId: String, choice: OrdinaryDestinationChoice) = error("Routing must never mint admission")
        override fun get(creationId: String) = row.takeIf { it.creationId == creationId }
        override fun linkPublication(publicationId: String, creationId: String) = error("Not permitted")
        override fun pendingCopy(limit: Int) = listOf(row)
        override fun recent(limit: Int) = error("Routing must not browse history")
    }
    private class Journal : ExportJournal {
        private val records = linkedMapOf<String, ExportRecord>()
        var throwAfterFirstDurableCreate = false
        @Synchronized override fun create(request: ExportRequest): ExportRecord {
            records[request.operationId]?.let { require(it.request == request); return it }
            val record = ExportRecord(request); records[request.operationId] = record
            if (throwAfterFirstDurableCreate) { throwAfterFirstDurableCreate = false; error("SYNTHETIC_ACK_LOSS") }
            return record
        }
        @Synchronized override fun get(operationId: String) = records[operationId]
        @Synchronized override fun put(record: ExportRecord) { records[record.operationId] = record }
        @Synchronized override fun unfinished(limit: Int) = records.values.filter { !it.phase.terminal }.take(limit)
    }
    private class Port(private val expected: MediaReadRef) : ExportPort {
        val bytes = ByteArray(8193) { (it % 233).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val sourceEntered = CountDownLatch(1)
        var waitForSource: CountDownLatch? = null
        var failSource = false
        var wrongSource = false
        var creates = 0; var publishes = 0; var cleanups = 0
        private val output = ByteArrayOutputStream()
        private fun input(value: ByteArray) = object : ExportInput {
            override val stream = ByteArrayInputStream(value)
            override fun close() = ExportRelease.RELEASED
        }
        override fun openSource(request: ExportRequest): ExportSource {
            require(request.source == expected)
            sourceEntered.countDown()
            check(waitForSource?.await(5, TimeUnit.SECONDS) != false)
            if (failSource) throw ExportFailure(ExportError.SOURCE_MISMATCH)
            return ExportSource(if (wrongSource) expected.copy(revision=expected.revision+1) else expected,
                ExportEvidence(bytes.size.toLong(),hash),input(bytes))
        }
        override fun create(record: ExportRecord): ExportOutput { creates++; return ExportOutput(record.operationId, ExportKind.MEDIA_STORE) }
        override fun openWrite(record: ExportRecord, output: ExportOutput) = object : ExportWrite {
            override val stream = this@Port.output
            override fun close() = ExportRelease.RELEASED
        }
        override fun openReadback(record: ExportRecord, output: ExportOutput) = ExportReadback.Opened(input(this.output.toByteArray()))
        override fun publish(record: ExportRecord, output: ExportOutput) { publishes++ }
        override fun cleanup(record: ExportRecord, output: ExportOutput): ExportCleanup { cleanups++; return ExportCleanup.REMOVED }
        override fun reconcile(record: ExportRecord) = ExportReconciliation.Unknown
    }
    private fun router(row: OrdinaryDestinationAdmission, journal: Journal, coordinator: ExportCoordinator) =
        OrdinaryDestinationRouter(DestJournal(row), object : OrdinaryDestinationCopyPort {
            override fun existing(operationId: String) = journal.get(operationId)
            override fun run(request: ExportRequest) = coordinator.run(request)
        })

    @Test fun twoDestinationPassesShareOneCoordinatorAndCannotCreateTwice() {
        val row = admission(); val journal = Journal(); val port = Port(source)
        val release = CountDownLatch(1); port.waitForSource = release
        val coordinator = ExportCoordinator(journal, port)
        val error = AtomicReference<Throwable?>()
        val thread = Thread { try { router(row,journal,coordinator).route(1) } catch (t:Throwable) { error.set(t) } }
        thread.start()
        try {
            assertTrue(port.sourceEntered.await(5,TimeUnit.SECONDS))
            val competing = router(row,journal,coordinator).route(1)
            assertEquals(ExportPhase.PREPARED,competing.single().phase)
            assertEquals(0,port.creates)
        } finally { release.countDown(); thread.join(5000) }
        assertFalse(thread.isAlive); error.get()?.let { throw AssertionError(it) }
        repeat(20) { assertEquals(ExportPhase.SUCCEEDED,router(row,journal,coordinator).route(1).single().phase) }
        assertEquals(1,port.creates); assertEquals(1,port.publishes); assertEquals(0,port.cleanups)
        assertEquals(row.exportRequest(),journal.get("operation")!!.request)
    }
    @Test fun durablePrepareAckLossThenRouterRestartReplaysExactCapabilitiesOnce() {
        val row = admission(); val journal = Journal().apply { throwAfterFirstDurableCreate = true }; val port = Port(source)
        val coordinator = ExportCoordinator(journal,port)
        assertThrows(IllegalStateException::class.java) { router(row,journal,coordinator).route(1) }
        assertEquals(0,port.creates)
        assertEquals(row.exportRequest(),journal.get("operation")!!.request)
        assertEquals(ExportPhase.SUCCEEDED,router(row,journal,coordinator).route(1).single().phase)
        repeat(10) { router(row,journal,coordinator).route(1) }
        assertEquals(1,port.creates); assertEquals(1,port.publishes)
    }
    @Test fun failedExternalSourceDoesNotChangePrivatePublicationOrMintReplacement() {
        val row = admission(); val journal = Journal(); val port = Port(source).apply { failSource = true }
        val coordinator = ExportCoordinator(journal,port)
        assertEquals(ExportPhase.FAILED,router(row,journal,coordinator).route(1).single().phase)
        port.failSource = false
        repeat(10) { router(row,journal,coordinator).route(1) }
        assertEquals(OrdinaryPrivateStatus.PUBLISHED,row.privateStatus)
        assertEquals(source,row.source); assertEquals(0,port.creates)
        assertEquals(OrdinaryCopyStatus.FAILED,row.copyStatus(journal.get("operation")))
    }
    @Test fun staleSourceReturnedByCopyPortIsRejectedBeforeExternalCreation() {
        val row = admission(); val journal = Journal(); val port = Port(source).apply { wrongSource = true }
        val result = router(row,journal,ExportCoordinator(journal,port)).route(1).single()
        assertEquals(ExportPhase.FAILED,result.phase); assertEquals(0,port.creates)
        assertEquals(source,result.request.source); assertEquals(OrdinaryPrivateStatus.PUBLISHED,row.privateStatus)
    }
    @Test fun staleSelectionFailureCannotCancelNewIntentAcrossEveryPreferencePair() {
        OrdinaryDestinationPreference.entries.forEach { oldPreference -> OrdinaryDestinationPreference.entries.forEach { newPreference ->
            val gate = OrdinaryDestinationSelection(); val old = gate.begin(oldPreference)!!
            assertTrue(gate.choose(old)); gate.cancel()
            val next = gate.begin(newPreference)!!
            assertFalse(gate.cancel(old)); assertFalse(gate.finish(old)); assertFalse(gate.choose(old))
            assertTrue(gate.active(next)); assertEquals(newPreference,next.preference)
            assertTrue(gate.choose(next)); assertTrue(gate.finish(next)); assertFalse(gate.finish(next))
        } }
    }
    @Test fun cancelledBeforePrivatePublicationHasNoExternalCopyRequest() {
        val original = OrdinaryDestinationAdmission("cancelled",OrdinaryDestinationChoice.ALBUM_COPY,"operation","grant")
        assertNull(original.exportRequest()); assertEquals(OrdinaryCopyStatus.AWAITING_PRIVATE,original.copyStatus(null))
        assertEquals(OrdinaryPrivateStatus.AWAITING_PUBLICATION,original.privateStatus)
    }
}
