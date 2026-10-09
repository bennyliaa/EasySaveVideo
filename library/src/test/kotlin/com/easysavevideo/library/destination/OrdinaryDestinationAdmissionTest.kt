package com.easysavevideo.library.destination

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test

class OrdinaryDestinationAdmissionTest {
    private val source = MediaReadRef("item", 7, 3)
    private fun admitted(choice: OrdinaryDestinationChoice = OrdinaryDestinationChoice.ALBUM_COPY) =
        OrdinaryDestinationAdmission("creation", choice, "operation", "grant")

    @Test fun unboundAdmissionCannotIssueExternalCopy() {
        val row = admitted()
        assertNull(row.exportRequest())
        assertEquals(OrdinaryPrivateStatus.AWAITING_PUBLICATION, row.privateStatus)
        assertEquals(OrdinaryCopyStatus.AWAITING_PRIVATE, row.copyStatus(null))
    }
    @Test fun ordinaryLibraryNeverIssuesAnExternalRequest() {
        val row = admitted(OrdinaryDestinationChoice.APP_LIBRARY).bindVerifiedSource(source)
        assertEquals(OrdinaryPrivateStatus.PUBLISHED, row.privateStatus)
        assertNull(row.exportRequest())
        assertEquals(OrdinaryCopyStatus.NOT_REQUESTED, row.copyStatus(null))
    }
    @Test fun publicationBindsExactRevisionAndGenerationWithStableCapabilities() {
        val original = admitted()
        val bound = original.bindVerifiedSource(source)
        assertEquals(original.operationId, bound.operationId)
        assertEquals(original.grantId, bound.grantId)
        assertEquals(ExportRequest("operation", source, ExportDestination(ExportKind.MEDIA_STORE, "grant")), bound.exportRequest())
        assertNull(original.source)
        assertSame(bound, bound.bindVerifiedSource(source))
    }
    @Test fun publishedSourceCannotBeReboundByIdentityRevisionOrGeneration() {
        val bound = admitted().bindVerifiedSource(source)
        listOf(source.copy(itemId = "other"), source.copy(revision = 8), source.copy(representationGeneration = 4)).forEach {
            assertEquals(OrdinaryDestinationError.SOURCE_MISMATCH,
                assertThrows(OrdinaryDestinationFailure::class.java) { bound.bindVerifiedSource(it) }.code)
        }
    }
    @Test fun privateSuccessAndExternalPendingAreIndependent() {
        val bound = admitted().bindVerifiedSource(source)
        assertEquals(OrdinaryPrivateStatus.PUBLISHED, bound.privateStatus)
        assertEquals(OrdinaryCopyStatus.PENDING, bound.copyStatus(null))
        assertEquals(OrdinaryCopyStatus.PENDING, bound.copyStatus(ExportRecord(requireNotNull(bound.exportRequest()))))
    }
    @Test fun externalFailedCancelledAndUnknownNeverBecomeCopySuccess() {
        val bound = admitted().bindVerifiedSource(source)
        val request = requireNotNull(bound.exportRequest())
        mapOf(ExportPhase.FAILED to OrdinaryCopyStatus.FAILED, ExportPhase.CANCELLED to OrdinaryCopyStatus.CANCELLED,
            ExportPhase.CLEANUP_PENDING to OrdinaryCopyStatus.RECONCILIATION_REQUIRED,
            ExportPhase.RECONCILIATION_REQUIRED to OrdinaryCopyStatus.RECONCILIATION_REQUIRED).forEach { (phase, status) ->
            assertEquals(status, bound.copyStatus(ExportRecord(request, phase)))
            assertEquals(OrdinaryPrivateStatus.PUBLISHED, bound.privateStatus)
        }
    }
    @Test fun successRequiresExistingExportVerifiedSuccess() {
        val bound = admitted().bindVerifiedSource(source)
        val exported = ExportRecord(requireNotNull(bound.exportRequest()), ExportPhase.SUCCEEDED,
            ExportOutput("operation", ExportKind.MEDIA_STORE), ExportEvidence(32, "a".repeat(64)),
            verification = ExportVerification.DESTINATION_VERIFIED)
        assertEquals(OrdinaryCopyStatus.SUCCEEDED, bound.copyStatus(exported))
    }
    @Test fun progressKeepsEveryExternalSideEffectPhaseDistinctFromSuccess() {
        val bound = admitted().bindVerifiedSource(source)
        listOf(ExportPhase.CREATE_INTENT, ExportPhase.COPYING, ExportPhase.VERIFYING, ExportPhase.PUBLISH_INTENT).forEach {
            assertEquals(OrdinaryCopyStatus.COPYING, bound.copyStatus(ExportRecord(requireNotNull(bound.exportRequest()), it)))
        }
    }
    @Test fun mismatchedExportOperationGrantSourceOrKindFailsClosed() {
        val bound = admitted().bindVerifiedSource(source)
        val request = requireNotNull(bound.exportRequest())
        listOf(request.copy(operationId = "other"), request.copy(source = source.copy(revision = 8)),
            request.copy(destination = request.destination.copy(grantId = "other")),
            request.copy(destination = request.destination.copy(kind = ExportKind.SAF))).forEach {
            assertEquals(OrdinaryDestinationError.SOURCE_MISMATCH,
                assertThrows(OrdinaryDestinationFailure::class.java) { bound.copyStatus(ExportRecord(it)) }.code)
        }
    }
    @Test fun unboundAndPrivateOnlyRowsRejectAnyExternalRecord() {
        val record = ExportRecord(requireNotNull(admitted().bindVerifiedSource(source).exportRequest()))
        assertThrows(OrdinaryDestinationFailure::class.java) { admitted().copyStatus(record) }
        assertThrows(OrdinaryDestinationFailure::class.java) { admitted(OrdinaryDestinationChoice.APP_LIBRARY).bindVerifiedSource(source).copyStatus(record) }
    }
    @Test fun askCannotBePersistedAsAResolvedChoice() {
        assertEquals(listOf("APP_LIBRARY", "ALBUM_COPY", "FOLDER_COPY"), OrdinaryDestinationChoice.entries.map { it.name })
        assertEquals(4, OrdinaryDestinationPreference.entries.size)
    }
    @Test fun diagnosticTextDoesNotExposeCapabilitiesOrSource() {
        val text = admitted().bindVerifiedSource(source).toString()
        listOf("creation", "operation", "grant", "item").forEach { assertFalse(text.contains(it)) }
    }
}
