package com.easysavevideo.app.library.destination

import com.easysavevideo.library.destination.*
import com.easysavevideo.library.export.ExportKind
import org.junit.Assert.*
import org.junit.Test

/** Pure row validation only. Android persisted permissions are validated separately by the adapter. */
class FolderDestinationRowsTest {
    private val uri = "content://synthetic.documents/tree/root"
    @Test fun persistedFolderSnapshotDecodesIntoExistingSafExportKind() {
        val row = OrdinaryDestinationRows.admission("creation", "FOLDER_COPY", "operation", "operation-grant", "item", 7, 3, "folder-id")
        assertEquals("folder-id", row.folderGrantId)
        assertEquals(ExportKind.SAF, row.exportRequest()!!.destination.kind)
        assertEquals("operation-grant", row.exportRequest()!!.destination.grantId)
    }
    @Test fun folderAndNonfolderSelectionsMustHaveMatchingFields() {
        assertInvalid { OrdinaryDestinationRows.admission("creation", "FOLDER_COPY", "operation", "grant", null, null, null) }
        listOf("APP_LIBRARY", "ALBUM_COPY").forEach {
            assertInvalid { OrdinaryDestinationRows.admission("creation", it, "operation", "grant", null, null, null, "folder-id") }
        }
        assertInvalid { OrdinaryDestinationRows.admission("creation", "FOLDER_COPY", "operation", "folder-id", null, null, null, "folder-id") }
        assertInvalid { OrdinaryDestinationRows.selection("FOLDER_COPY", null) }
        assertInvalid { OrdinaryDestinationRows.selection("FOLDER_COPY", "content://private") }
        assertEquals("folder-id", OrdinaryDestinationRows.selection("ASK", "folder-id").folderGrantId)
    }
    @Test fun folderRowsRoundTripOnlyReadWriteFlagsAndRetirementState() {
        for (flags in 1..3) for (retired in 0..1) {
            val row = FolderGrantRows.decode("opaque-id", uri, flags, retired)
            assertEquals(uri, row.uri)
            assertEquals(flags, row.flags)
            assertEquals(retired == 1, row.retired)
        }
    }
    @Test fun malformedFolderRowIdentitiesUriAndRetirementFailClosed() {
        listOf("", "../folder", "x".repeat(129)).forEach { assertInvalid { FolderGrantRows.decode(it, uri, 3, 0) } }
        listOf("", "file:///private", "https://host/tree/a", "content://a\n", "content://a\u0000", "content://" + "x".repeat(8192),
            "content://" + "界".repeat(3000)).forEach { assertInvalid { FolderGrantRows.decode("id", it, 3, 0) } }
        listOf(-1, 0, 4, 64, 67, Int.MAX_VALUE).forEach { assertInvalid { FolderGrantRows.decode("id", uri, it, 0) } }
        listOf(-1, 2, Int.MAX_VALUE).forEach { assertInvalid { FolderGrantRows.decode("id", uri, 3, it) } }
    }
    @Test fun retiredRowsRemainDataButNeverHideTheirStateOrExposeUrisInText() {
        val row = FolderGrantRows.decode("private-folder-id", uri, 3, 1)
        assertTrue(row.retired)
        assertTrue(row.toString().contains("retired=true"))
        assertFalse(row.toString().contains(uri))
        assertFalse(row.toString().contains(row.id))
    }
    @Test fun invalidationIsDistinctFromRetirementAndAlwaysVisibleToAuthorityChecks() {
        for (retired in 0..1) for (invalidated in 0..1) {
            val row = FolderGrantRows.decode("opaque-id", uri, 3, retired, invalidated)
            assertEquals(retired == 1, row.retired)
            assertEquals(invalidated == 1, row.invalidated)
        }
        assertFalse(FolderGrantRows.decode("retired-only", uri, 3, 1).invalidated)
        assertTrue(FolderGrantRows.decode("authority-lost", uri, 3, 0, 1).invalidated)
    }
    @Test fun invalidationRejectsUnknownStateAndRedactsRetainedIdentity() {
        listOf(-1, 2, Int.MAX_VALUE).forEach { assertInvalid { FolderGrantRows.decode("id", uri, 3, 0, it) } }
        val row = FolderGrantRows.decode("private-id", uri, 3, 1, 1)
        assertTrue(row.toString().contains("invalidated=true"))
        assertFalse(row.toString().contains(uri))
        assertFalse(row.toString().contains(row.id))
    }
    @Test fun folderSourceTupleRequiresPositiveExactLongVersionsOrAllNull() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach {
            assertInvalid { OrdinaryDestinationRows.admission("creation", "FOLDER_COPY", "operation", "grant", "item", it, 3, "folder") }
            assertInvalid { OrdinaryDestinationRows.admission("creation", "FOLDER_COPY", "operation", "grant", "item", 7, it, "folder") }
        }
        assertInvalid { OrdinaryDestinationRows.admission("creation", "FOLDER_COPY", "operation", "grant", "item", null, 3, "folder") }
        assertInvalid { OrdinaryDestinationRows.admission("creation", "FOLDER_COPY", "operation", "grant", null, 7, 3, "folder") }
        assertNull(OrdinaryDestinationRows.admission("creation", "FOLDER_COPY", "operation", "grant", null, null, null, "folder").source)
        val row = OrdinaryDestinationRows.admission("creation", "FOLDER_COPY", "operation", "grant", "item", Long.MAX_VALUE, Long.MAX_VALUE, "folder")
        assertEquals(Long.MAX_VALUE, row.source!!.revision)
        assertEquals(Long.MAX_VALUE, row.source!!.representationGeneration)
    }
    @Test fun selectionRevisionRoundTripsAndLegacyCallersDefaultToZero() {
        assertEquals(0L, OrdinaryDestinationRows.selection("APP_LIBRARY", null).revision)
        assertEquals(42L, OrdinaryDestinationRows.selection("FOLDER_COPY", "folder", 42).revision)
        assertEquals(Long.MAX_VALUE, OrdinaryDestinationRows.selection("ASK", null, Long.MAX_VALUE).revision)
    }
    @Test fun selectionRevisionCannotBeNegative() {
        listOf(-1L, Long.MIN_VALUE).forEach {
            assertInvalid { OrdinaryDestinationRows.selection("APP_LIBRARY", null, it) }
            assertThrows(IllegalArgumentException::class.java) { OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.APP_LIBRARY, revision = it) }
        }
    }
    private fun assertInvalid(block: () -> Unit) {
        assertEquals(OrdinaryDestinationError.INVALID_RECORD, assertThrows(OrdinaryDestinationFailure::class.java) { block() }.code)
    }
}
