package com.easysavevideo.library.destination

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test

/** In-memory durability/crash contracts. No Android or actual public-media IO is claimed. */
class OrdinaryDestinationRouterTest {
    private class DurableState {
        val admissions = linkedMapOf<String, OrdinaryDestinationAdmission>()
        val exports = linkedMapOf<String, ExportRecord>()
        val createdCopies = linkedMapOf<String, Int>()
        var preference = OrdinaryDestinationPreference.APP_LIBRARY
        var cursor = ""
    }
    private class Journal(val durable: DurableState) : OrdinaryDestinationJournal {
        var preferenceReads = 0
        var forcedBatch: List<OrdinaryDestinationAdmission>? = null
        var readOverride: ((String) -> OrdinaryDestinationAdmission?)? = null
        val requestedLimits = mutableListOf<Int>()
        override fun preference(): OrdinaryDestinationPreference { preferenceReads++; return durable.preference }
        override fun setPreference(value: OrdinaryDestinationPreference) { durable.preference = value }
        override fun admit(creationId: String, choice: OrdinaryDestinationChoice): OrdinaryDestinationAdmission {
            durable.admissions[creationId]?.let {
                if (it.choice != choice) throw OrdinaryDestinationFailure(OrdinaryDestinationError.IDEMPOTENCY_CONFLICT)
                return it
            }
            return OrdinaryDestinationAdmission(creationId, choice, "operation-$creationId", "grant-$creationId")
                .also { durable.admissions[creationId] = it }
        }
        fun published(id: String, choice: OrdinaryDestinationChoice = OrdinaryDestinationChoice.ALBUM_COPY): OrdinaryDestinationAdmission =
            admit(id, choice).bindVerifiedSource(MediaReadRef("item-$id", 7, 3)).also { durable.admissions[id] = it }
        override fun get(creationId: String): OrdinaryDestinationAdmission? {
            val customRead = readOverride
            return if (customRead != null) customRead(creationId) else durable.admissions[creationId]
        }
        override fun linkPublication(publicationId: String, creationId: String) = error("Router must not relink publication")
        override fun recent(limit: Int): List<OrdinaryDestinationAdmission> = durable.admissions.values.toList().takeLast(limit)
        override fun pendingCopy(limit: Int): List<OrdinaryDestinationAdmission> {
            require(limit in 1..OrdinaryDestinationLimits.MAX_BATCH)
            requestedLimits += limit
            forcedBatch?.let { return it }
            val eligible = durable.admissions.values.filter { admission ->
                admission.exportRequest() != null && durable.exports[admission.operationId]?.phase.let { it == null || it == ExportPhase.PREPARED }
            }.sortedBy { it.creationId }
            val page = eligible.filter { it.creationId > durable.cursor }.take(limit).ifEmpty { eligible.take(limit) }
            page.lastOrNull()?.let { durable.cursor = it.creationId }
            return page
        }
    }
    private enum class Fault { BEFORE_ADMISSION, AFTER_ADMISSION, AFTER_CREATE, AFTER_SUCCESS }
    private class CopyPort(val durable: DurableState) : OrdinaryDestinationCopyPort {
        val existingCalls = mutableListOf<String>()
        val runCalls = mutableListOf<ExportRequest>()
        val faults = mutableMapOf<String, Fault>()
        val rejected = mutableMapOf<String, ExportError>()
        var resultOverride: ((ExportRequest) -> ExportRecord)? = null
        override fun existing(operationId: String): ExportRecord? {
            existingCalls += operationId
            return durable.exports[operationId]
        }
        override fun run(request: ExportRequest): ExportRecord {
            runCalls += request
            rejected[request.operationId]?.let { throw ExportFailure(it) }
            val fault = faults.remove(request.operationId)
            if (fault == Fault.BEFORE_ADMISSION) throw ExportFailure(ExportError.IO)
            val previous = durable.exports.getOrPut(request.operationId) { ExportRecord(request) }
            if (previous.request != request) throw ExportFailure(ExportError.IDEMPOTENCY_CONFLICT)
            if (fault == Fault.AFTER_ADMISSION) throw ExportFailure(ExportError.IO)
            check(previous.phase == ExportPhase.PREPARED)
            durable.exports[request.operationId] = previous.copy(phase = ExportPhase.CREATE_INTENT)
            durable.createdCopies[request.operationId] = durable.createdCopies.getOrDefault(request.operationId, 0) + 1
            if (fault == Fault.AFTER_CREATE) throw ExportFailure(ExportError.CREATE_UNCERTAIN)
            val result = successful(request)
            durable.exports[request.operationId] = result
            if (fault == Fault.AFTER_SUCCESS) throw ExportFailure(ExportError.IO)
            return resultOverride?.invoke(request) ?: result
        }
    }
    private class Fixture(val durable: DurableState = DurableState()) {
        val journal = Journal(durable)
        val copies = CopyPort(durable)
        val router = OrdinaryDestinationRouter(journal, copies)
        fun restart() = Fixture(durable)
    }
    @Test fun onlyBoundAlbumAdmissionsRoute() {
        val f = Fixture()
        f.journal.admit("unpublished", OrdinaryDestinationChoice.ALBUM_COPY)
        f.journal.published("private", OrdinaryDestinationChoice.APP_LIBRARY)
        val bound = f.journal.published("album")
        assertEquals(listOf(bound.exportRequest()), f.router.route(32).map { it.request })
        assertEquals(listOf(bound.exportRequest()), f.copies.runCalls)
        assertNull(f.durable.admissions.getValue("unpublished").source)
    }
    @Test fun changedPreferencesCannotAlterAdmittedChoicesAndAreNeverRead() {
        val f = Fixture()
        val album = f.journal.published("album")
        f.journal.published("private", OrdinaryDestinationChoice.APP_LIBRARY)
        OrdinaryDestinationPreference.entries.forEach(f.journal::setPreference)
        f.journal.setPreference(OrdinaryDestinationPreference.APP_LIBRARY)
        f.router.route(32)
        assertEquals(listOf(album.exportRequest()), f.copies.runCalls)
        assertEquals(0, f.journal.preferenceReads)
        assertEquals(OrdinaryDestinationChoice.APP_LIBRARY, f.durable.admissions.getValue("private").choice)
    }
    @Test fun duplicatePublicationCallbacksDoNotCreateAnotherCopy() {
        val f = Fixture()
        val admission = f.journal.published("one")
        f.router.route(32)
        repeat(5) {
            assertSame(admission, f.journal.admit("one", OrdinaryDestinationChoice.ALBUM_COPY))
            assertTrue(f.router.route(32).isEmpty())
        }
        assertEquals(1, f.durable.createdCopies[admission.operationId])
        assertEquals(1, f.copies.runCalls.size)
    }
    @Test fun crashBeforeCopyAdmissionRetriesSameIdsAfterRestart() {
        val f = Fixture()
        val admission = f.journal.published("one")
        f.copies.faults[admission.operationId] = Fault.BEFORE_ADMISSION
        assertThrows(ExportFailure::class.java) { f.router.route(32) }
        assertTrue(f.durable.exports.isEmpty())
        assertTrue(f.durable.createdCopies.isEmpty())
        val reopened = f.restart()
        reopened.router.route(32)
        assertEquals(f.copies.runCalls.single(), reopened.copies.runCalls.single())
        assertEquals(admission, reopened.journal.get("one"))
        assertEquals(1, reopened.durable.createdCopies[admission.operationId])
    }
    @Test fun crashAfterDurableCopyAdmissionReusesPreparedRequestAfterRestart() {
        val f = Fixture()
        val admission = f.journal.published("one")
        f.copies.faults[admission.operationId] = Fault.AFTER_ADMISSION
        assertThrows(ExportFailure::class.java) { f.router.route(32) }
        assertEquals(ExportPhase.PREPARED, f.durable.exports.getValue(admission.operationId).phase)
        assertTrue(f.durable.createdCopies.isEmpty())
        val reopened = f.restart()
        reopened.router.route(32)
        assertEquals(f.copies.runCalls.single(), reopened.copies.runCalls.single())
        assertEquals(1, reopened.durable.createdCopies[admission.operationId])
        assertEquals(1, reopened.durable.exports.size)
    }
    @Test fun crashAfterCreateLeavesReconcileOnlyAndNeverCreatesAgain() {
        val f = Fixture()
        val admission = f.journal.published("one")
        f.copies.faults[admission.operationId] = Fault.AFTER_CREATE
        assertEquals(ExportError.CREATE_UNCERTAIN, assertThrows(ExportFailure::class.java) { f.router.route(32) }.code)
        val reopened = f.restart()
        repeat(4) { assertTrue(reopened.router.route(32).isEmpty()) }
        assertEquals(1, reopened.durable.createdCopies[admission.operationId])
        assertTrue(reopened.copies.runCalls.isEmpty())
        assertEquals(ExportPhase.CREATE_INTENT, reopened.durable.exports.getValue(admission.operationId).phase)
    }
    @Test fun crashAfterVerifiedSuccessDoesNotRepeatCompletedCopy() {
        val f = Fixture()
        val admission = f.journal.published("one")
        f.copies.faults[admission.operationId] = Fault.AFTER_SUCCESS
        assertThrows(ExportFailure::class.java) { f.router.route(32) }
        val reopened = f.restart()
        assertTrue(reopened.router.route(32).isEmpty())
        assertEquals(ExportPhase.SUCCEEDED, reopened.durable.exports.getValue(admission.operationId).phase)
        assertEquals(1, reopened.durable.createdCopies[admission.operationId])
    }
    @Test fun repeatedRestartPreservesOperationGrantAndSourceWithoutNewIds() {
        var f = Fixture()
        val admission = f.journal.published("one")
        repeat(4) {
            f.copies.rejected[admission.operationId] = ExportError.CAPACITY
            assertThrows(ExportFailure::class.java) { f.router.route(32) }
            assertEquals(admission.exportRequest(), f.copies.runCalls.last())
            f = f.restart()
        }
        f.router.route(32)
        assertEquals(admission.exportRequest(), f.copies.runCalls.single())
        assertEquals(1, f.durable.createdCopies[admission.operationId])
    }
    @Test fun staleExactReferenceFailureNeverRebindsToNewerSource() {
        val f = Fixture()
        val admission = f.journal.published("one")
        f.copies.rejected[admission.operationId] = ExportError.SOURCE_MISMATCH
        repeat(3) {
            assertEquals(ExportError.SOURCE_MISMATCH, assertThrows(ExportFailure::class.java) { f.router.route(32) }.code)
            assertEquals(admission, f.journal.get("one"))
        }
        assertTrue(f.copies.runCalls.all { it.source == MediaReadRef("item-one", 7, 3) })
        assertTrue(f.durable.createdCopies.isEmpty())
    }
    @Test fun concurrentAdmissionChangeFailsBeforeExternalAccess() {
        val f = Fixture()
        val admitted = f.journal.published("one")
        f.journal.readOverride = { admitted.copy(source = MediaReadRef("item-one", 8, 3)) }
        assertDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH) { f.router.route(32) }
        assertTrue(f.copies.existingCalls.isEmpty())
        assertTrue(f.copies.runCalls.isEmpty())
    }
    @Test fun disappearedAdmissionFailsBeforeExternalAccess() {
        val f = Fixture()
        f.journal.published("one")
        f.journal.readOverride = { null }
        assertDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH) { f.router.route(32) }
        assertTrue(f.copies.existingCalls.isEmpty())
        assertTrue(f.copies.runCalls.isEmpty())
    }
    @Test fun changedOperationGrantOrChoiceInCurrentJournalAlsoFailsClosed() {
        listOf<(OrdinaryDestinationAdmission) -> OrdinaryDestinationAdmission>(
            { it.copy(operationId = "different-operation") }, { it.copy(grantId = "different-grant") },
            { it.copy(choice = OrdinaryDestinationChoice.APP_LIBRARY) }).forEach { alter ->
            val f = Fixture()
            val admission = f.journal.published("one")
            f.journal.readOverride = { alter(admission) }
            assertDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH) { f.router.route(32) }
            assertTrue(f.copies.runCalls.isEmpty())
        }
    }
    @Test fun existingExportWithDifferentExactRequestIsNeverRunOrReplaced() {
        val mutations = listOf<(ExportRequest) -> ExportRequest>(
            { it.copy(operationId = "unrelated-operation") },
            { it.copy(source = it.source.copy(itemId = "different-item")) },
            { it.copy(source = it.source.copy(revision = 8)) },
            { it.copy(source = it.source.copy(representationGeneration = 4)) },
            { it.copy(destination = it.destination.copy(grantId = "other-grant")) },
            { it.copy(destination = it.destination.copy(kind = ExportKind.SAF)) })
        mutations.forEach { mutate ->
            val f = Fixture()
            val admission = f.journal.published("one")
            val wrong = ExportRecord(mutate(requireNotNull(admission.exportRequest())))
            f.durable.exports[admission.operationId] = wrong
            assertDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH) { f.router.route(32) }
            assertTrue(f.copies.runCalls.isEmpty())
            assertSame(wrong, f.durable.exports[admission.operationId])
        }
    }
    @Test fun mismatchedReturnedRequestFailsRatherThanReportingSuccess() {
        val f = Fixture()
        f.journal.published("one")
        f.copies.resultOverride = { successful(it.copy(operationId = "unrelated")) }
        assertDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH) { f.router.route(32) }
    }
    @Test fun staleBatchObservationOfTerminalOrAmbiguousExportNeverRuns() {
        val phases = listOf(ExportPhase.CREATE_INTENT, ExportPhase.COPYING, ExportPhase.VERIFYING,
            ExportPhase.PUBLISH_INTENT, ExportPhase.SUCCEEDED, ExportPhase.CLEANUP_PENDING,
            ExportPhase.FAILED, ExportPhase.CANCELLED, ExportPhase.RECONCILIATION_REQUIRED)
        phases.forEach { phase ->
            val f = Fixture()
            val admission = f.journal.published("one")
            val request = requireNotNull(admission.exportRequest())
            val record = if (phase == ExportPhase.SUCCEEDED) successful(request) else ExportRecord(request, phase)
            f.durable.exports[admission.operationId] = record
            f.journal.forcedBatch = listOf(admission)
            assertEquals(listOf(record), f.router.route(32))
            assertTrue(f.copies.runCalls.isEmpty())
            assertTrue(f.durable.createdCopies.isEmpty())
        }
    }
    @Test fun preparedExportIsResumedUsingItsExistingCapabilities() {
        val f = Fixture()
        val admission = f.journal.published("one")
        val request = requireNotNull(admission.exportRequest())
        f.durable.exports[request.operationId] = ExportRecord(request)
        assertEquals(ExportPhase.SUCCEEDED, f.router.route(32).single().phase)
        assertEquals(listOf(request), f.copies.runCalls)
        assertEquals(1, f.durable.exports.size)
    }
    @Test fun capacityAndBusyEquivalentErrorsPreserveAdmissionAndRetryIdentity() {
        listOf(ExportError.CAPACITY, ExportError.INVALID_STATE, ExportError.RELEASE_UNCONFIRMED).forEach { error ->
            val f = Fixture()
            val admission = f.journal.published("one")
            f.copies.rejected[admission.operationId] = error
            assertEquals(error, assertThrows(ExportFailure::class.java) { f.router.route(32) }.code)
            assertEquals(admission, f.journal.get("one"))
            assertTrue(f.durable.createdCopies.isEmpty())
            f.copies.rejected.clear()
            f.router.route(32)
            assertEquals(listOf(admission.exportRequest(), admission.exportRequest()), f.copies.runCalls)
            assertEquals(1, f.durable.createdCopies[admission.operationId])
        }
    }
    @Test fun invalidRequestedBatchLimitsFailBeforeJournalAccess() {
        val f = Fixture()
        listOf(-1, 0, 33, Int.MAX_VALUE).forEach {
            assertThrows(IllegalArgumentException::class.java) { f.router.route(it) }
        }
        assertTrue(f.journal.requestedLimits.isEmpty())
        assertTrue(f.copies.runCalls.isEmpty())
    }
    @Test fun duplicateOrOversizedJournalBatchCannotProduceAnyCopy() {
        val f = Fixture()
        val admission = f.journal.published("one")
        f.journal.forcedBatch = listOf(admission, admission)
        assertThrows(IllegalArgumentException::class.java) { f.router.route(2) }
        f.journal.forcedBatch = listOf(admission, f.journal.published("two"))
        assertThrows(IllegalArgumentException::class.java) { f.router.route(1) }
        assertTrue(f.copies.runCalls.isEmpty())
    }
    @Test fun malformedJournalBatchCannotRoutePrivateOrUnboundAdmissions() {
        listOf(false, true).forEach { isPrivate ->
            val f = Fixture()
            val admission = if (isPrivate) f.journal.published("one", OrdinaryDestinationChoice.APP_LIBRARY)
                else f.journal.admit("one", OrdinaryDestinationChoice.ALBUM_COPY)
            f.journal.forcedBatch = listOf(admission)
            assertDestinationFailure(OrdinaryDestinationError.INVALID_RECORD) { f.router.route(32) }
            assertTrue(f.copies.runCalls.isEmpty())
        }
    }
    @Test fun fairBoundedBatchesRouteAllSeventyFiveWithoutDuplicates() {
        val f = Fixture()
        repeat(75) { f.journal.published("item-${it.toString().padStart(3, '0')}") }
        assertEquals(32, f.router.route(32).size)
        assertEquals(32, f.restart().router.route(32).size)
        assertEquals(11, f.restart().router.route(32).size)
        assertEquals(75, f.durable.exports.size)
        assertTrue(f.durable.createdCopies.values.all { it == 1 })
    }
    @Test fun persistentEarlyFailureCannotStarveLaterRowsInSameBatch() {
        val f = Fixture()
        val failing = f.journal.published("000-failing")
        val later = (1..5).map { f.journal.published("00$it-later") }
        f.copies.rejected[failing.operationId] = ExportError.IO
        assertThrows(ExportFailure::class.java) { f.router.route(32) }
        assertEquals(later.map { it.operationId }.toSet(), f.durable.exports.keys)
        assertTrue(f.durable.createdCopies.values.all { it == 1 })
        assertEquals(failing, f.journal.get(failing.creationId))
        assertThrows(ExportFailure::class.java) { f.router.route(32) }
        assertEquals(5, f.durable.createdCopies.size)
    }
    @Test fun failedRowsAcrossBatchBoundaryCannotStarveFollowingPage() {
        val f = Fixture()
        repeat(65) { index ->
            val row = f.journal.published("item-${index.toString().padStart(3, '0')}")
            if (index == 0 || index == 32) f.copies.rejected[row.operationId] = ExportError.CAPACITY
        }
        assertThrows(ExportFailure::class.java) { f.router.route(32) }
        assertThrows(ExportFailure::class.java) { f.router.route(32) }
        assertEquals(1, f.router.route(32).size)
        assertEquals(63, f.durable.exports.size)
        assertTrue(f.durable.createdCopies.values.all { it == 1 })
    }
    companion object {
        private fun successful(request: ExportRequest) = ExportRecord(request, ExportPhase.SUCCEEDED,
            ExportOutput(request.operationId, request.destination.kind), ExportEvidence(32, "a".repeat(64)),
            verification = ExportVerification.DESTINATION_VERIFIED)
        private fun assertDestinationFailure(error: OrdinaryDestinationError, block: () -> Unit) {
            assertEquals(error, assertThrows(OrdinaryDestinationFailure::class.java) { block() }.code)
        }
    }
}
