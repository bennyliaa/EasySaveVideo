package com.easysavevideo.library.destination

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test

/** In-memory replay contracts; the real on-disk journal invariants have a separate SQLite gate. */
class FolderDestinationRouterTest {
    private class DurableState {
        val admissions = linkedMapOf<String, OrdinaryDestinationAdmission>()
        val exports = linkedMapOf<String, ExportRecord>()
        val createdCopies = linkedMapOf<String, Int>()
        var preference = OrdinaryDestinationPreference.APP_LIBRARY
    }
    private class Journal(val state: DurableState) : OrdinaryDestinationJournal {
        var preferenceReads = 0
        var readOverride: ((String) -> OrdinaryDestinationAdmission?)? = null
        override fun preference(): OrdinaryDestinationPreference { preferenceReads++; return state.preference }
        override fun setPreference(value: OrdinaryDestinationPreference) { state.preference = value }
        override fun admit(creationId: String, choice: OrdinaryDestinationChoice): OrdinaryDestinationAdmission = admit(creationId, choice, null)
        override fun admit(creationId: String, choice: OrdinaryDestinationChoice, folderGrantId: String?): OrdinaryDestinationAdmission {
            state.admissions[creationId]?.let {
                if (it.choice != choice || it.folderGrantId != folderGrantId) throw OrdinaryDestinationFailure(OrdinaryDestinationError.IDEMPOTENCY_CONFLICT)
                return it
            }
            return OrdinaryDestinationAdmission(creationId, choice, "operation-$creationId", "grant-$creationId", folderGrantId = folderGrantId)
                .also { state.admissions[creationId] = it }
        }
        override fun get(creationId: String): OrdinaryDestinationAdmission? = readOverride?.invoke(creationId) ?: state.admissions[creationId]
        override fun linkPublication(publicationId: String, creationId: String) = error("Unexpected relink")
        override fun recent(limit: Int) = state.admissions.values.toList().takeLast(limit)
        override fun pendingCopy(limit: Int) = state.admissions.values.filter {
            it.exportRequest() != null && state.exports[it.operationId]?.phase.let { phase -> phase == null || phase == ExportPhase.PREPARED }
        }.take(limit)
    }
    private enum class Fault { AFTER_ADMISSION, AFTER_CREATE }
    private class CopyPort(val state: DurableState) : OrdinaryDestinationCopyPort {
        val existingCalls = mutableListOf<String>()
        val runCalls = mutableListOf<ExportRequest>()
        var fault: Fault? = null
        override fun existing(operationId: String): ExportRecord? {
            existingCalls += operationId
            return state.exports[operationId]
        }
        override fun run(request: ExportRequest): ExportRecord {
            runCalls += request
            val previous = state.exports.getOrPut(request.operationId) { ExportRecord(request) }
            check(previous.request == request && previous.phase == ExportPhase.PREPARED)
            if (fault == Fault.AFTER_ADMISSION) throw ExportFailure(ExportError.IO)
            state.exports[request.operationId] = previous.copy(phase = ExportPhase.CREATE_INTENT)
            state.createdCopies[request.operationId] = state.createdCopies.getOrDefault(request.operationId, 0) + 1
            if (fault == Fault.AFTER_CREATE) throw ExportFailure(ExportError.CREATE_UNCERTAIN)
            val completed = ExportRecord(request, ExportPhase.SUCCEEDED, ExportOutput(request.operationId, ExportKind.SAF),
                ExportEvidence(16, "a".repeat(64)), verification = ExportVerification.DESTINATION_VERIFIED)
            state.exports[request.operationId] = completed
            return completed
        }
    }
    private class Fixture(val state: DurableState = DurableState()) {
        val journal = Journal(state)
        val copies = CopyPort(state)
        val router = OrdinaryDestinationRouter(journal, copies)
        fun publish(): OrdinaryDestinationAdmission = journal.admit("folder", OrdinaryDestinationChoice.FOLDER_COPY, "selected-folder")
            .bindVerifiedSource(MediaReadRef("item-folder", 7, 3)).also { state.admissions[it.creationId] = it }
        fun restart() = Fixture(state)
    }
    @Test fun replayKeepsOriginalSelectionAndSeparateOperationGrantAcrossRestart() {
        val f = Fixture()
        val admission = f.publish()
        f.copies.fault = Fault.AFTER_ADMISSION
        assertThrows(ExportFailure::class.java) { f.router.route(32) }
        f.journal.setPreference(OrdinaryDestinationPreference.APP_LIBRARY)
        val restarted = f.restart()
        restarted.router.route(32)
        assertEquals(0, restarted.journal.preferenceReads)
        assertEquals("selected-folder", restarted.journal.get("folder")!!.folderGrantId)
        assertEquals(ExportKind.SAF, restarted.copies.runCalls.single().destination.kind)
        assertEquals("grant-folder", restarted.copies.runCalls.single().destination.grantId)
        assertEquals(admission.exportRequest(), restarted.copies.runCalls.single())
        assertEquals(1, f.state.createdCopies[admission.operationId])
    }
    @Test fun createUncertaintyNeverRetriesOrUsesAnotherSelection() {
        val f = Fixture()
        val admission = f.publish()
        f.copies.fault = Fault.AFTER_CREATE
        assertThrows(ExportFailure::class.java) { f.router.route(32) }
        val restarted = f.restart()
        assertTrue(restarted.router.route(32).isEmpty())
        assertTrue(restarted.copies.runCalls.isEmpty())
        assertEquals(admission, restarted.journal.get("folder"))
        assertEquals(1, f.state.createdCopies[admission.operationId])
    }
    @Test fun changedCurrentFolderSnapshotFailsBeforeExternalAccess() {
        val f = Fixture()
        val admission = f.publish()
        f.journal.readOverride = { admission.copy(folderGrantId = "new-folder") }
        assertEquals(OrdinaryDestinationError.SOURCE_MISMATCH,
            assertThrows(OrdinaryDestinationFailure::class.java) { f.router.route(32) }.code)
        assertTrue(f.copies.existingCalls.isEmpty())
        assertTrue(f.copies.runCalls.isEmpty())
    }
}
