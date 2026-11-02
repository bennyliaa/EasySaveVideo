package com.easysavevideo.library.destination

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test

class FolderDestinationAdmissionTest {
    private val source = MediaReadRef("item", 7, 3)
    private fun admitted(id: String = "folder-selection") = OrdinaryDestinationAdmission(
        "creation", OrdinaryDestinationChoice.FOLDER_COPY, "operation", "operation-grant", folderGrantId = id)

    @Test fun folderCopyRequiresAnOpaqueSelectionExactlyForFolderChoice() {
        assertThrows(IllegalArgumentException::class.java) {
            OrdinaryDestinationAdmission("creation", OrdinaryDestinationChoice.FOLDER_COPY, "operation", "grant")
        }
        listOf(OrdinaryDestinationChoice.APP_LIBRARY, OrdinaryDestinationChoice.ALBUM_COPY).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                OrdinaryDestinationAdmission("creation", it, "operation", "grant", folderGrantId = "folder")
            }
        }
        listOf("", "content://provider/tree/private", "../folder", "x".repeat(129)).forEach {
            assertThrows(RuntimeException::class.java) { admitted(it) }
        }
    }
    @Test fun persistedFolderIdentityCannotBecomePerOperationExportGrant() {
        assertThrows(IllegalArgumentException::class.java) { admitted("operation-grant") }
        val bound = admitted().bindVerifiedSource(source)
        assertEquals("folder-selection", bound.folderGrantId)
        assertEquals(ExportRequest("operation", source, ExportDestination(ExportKind.SAF, "operation-grant")), bound.exportRequest())
    }
    @Test fun folderWaitsForVerifiedPrivatePublicationAndCannotRebindSource() {
        val row = admitted()
        assertNull(row.exportRequest())
        assertEquals(OrdinaryCopyStatus.AWAITING_PRIVATE, row.copyStatus(null))
        val bound = row.bindVerifiedSource(source)
        assertEquals(row.folderGrantId, bound.folderGrantId)
        assertEquals(OrdinaryCopyStatus.PENDING, bound.copyStatus(null))
        assertThrows(OrdinaryDestinationFailure::class.java) { bound.bindVerifiedSource(source.copy(revision = 8)) }
    }
    @Test fun folderOutcomesRemainIndependentFromPrivatePublication() {
        val row = admitted().bindVerifiedSource(source)
        mapOf(ExportPhase.FAILED to OrdinaryCopyStatus.FAILED, ExportPhase.CANCELLED to OrdinaryCopyStatus.CANCELLED,
            ExportPhase.COPIED_UNVERIFIED to OrdinaryCopyStatus.RECONCILIATION_REQUIRED,
            ExportPhase.CLEANUP_PENDING to OrdinaryCopyStatus.RECONCILIATION_REQUIRED,
            ExportPhase.RECONCILIATION_REQUIRED to OrdinaryCopyStatus.RECONCILIATION_REQUIRED).forEach { (phase, status) ->
            val request = requireNotNull(row.exportRequest())
            val record = if (phase == ExportPhase.COPIED_UNVERIFIED)
                ExportRecord(request, phase, ExportOutput("operation", ExportKind.SAF), ExportEvidence(16, "a".repeat(64)),
                    verification = ExportVerification.READBACK_UNSUPPORTED, error = ExportError.READBACK_UNSUPPORTED)
            else ExportRecord(request, phase)
            assertEquals(status, row.copyStatus(record))
            assertEquals(OrdinaryPrivateStatus.PUBLISHED, row.privateStatus)
            assertEquals(source, row.source)
        }
    }
    @Test fun folderSelectionSnapshotRequiresIdButOtherDefaultsMayRememberIt() {
        assertThrows(IllegalArgumentException::class.java) { OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.FOLDER_COPY) }
        OrdinaryDestinationPreference.entries.forEach {
            assertEquals("remembered", OrdinaryDestinationSelectionSnapshot(it, "remembered").folderGrantId)
        }
    }
    @Test fun settingsAndAdmissionDiagnosticsRedactRememberedFolder() {
        assertFalse(admitted().toString().contains("folder-selection"))
        assertFalse(OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.FOLDER_COPY, "private-folder").toString().contains("private-folder"))
    }
}
