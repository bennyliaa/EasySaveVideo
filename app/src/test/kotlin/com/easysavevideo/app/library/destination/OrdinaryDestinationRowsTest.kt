package com.easysavevideo.app.library.destination

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.destination.*
import org.junit.Assert.*
import org.junit.Test

/** Host unit tests exercise the row codec without constructing Android SQLite classes. */
class OrdinaryDestinationRowsTest {
    @Test fun exactSourceRoundTripsAllThreeIdentityFields() {
        val row = OrdinaryDestinationRows.admission("creation", "ALBUM_COPY", "operation", "grant", "item", 7L, 9L)
        assertEquals(MediaReadRef("item", 7, 9), row.source)
        assertEquals("operation", row.exportRequest()!!.operationId)
        assertEquals("grant", row.exportRequest()!!.destination.grantId)
    }
    @Test fun allPreferenceChoicesAreExplicit() {
        OrdinaryDestinationPreference.entries.forEach { assertEquals(it, OrdinaryDestinationRows.preference(it.name)) }
    }
    @Test fun malformedPreferenceNeverFallsBackToAlbumOrLibrary() {
        listOf("", "album_copy", " ALBUM_COPY", "ALBUM_COPY ", "VAULT", "x".repeat(100)).forEach {
            assertInvalid { OrdinaryDestinationRows.preference(it) }
        }
    }
    @Test fun askUnknownAndMalformedChoicesCannotAdmitCopies() {
        listOf("ASK", "", "album_copy", "VAULT").forEach {
            assertInvalid { OrdinaryDestinationRows.admission("creation", it, "operation", "grant", null, null, null) }
        }
    }
    @Test fun partiallyBoundRowsFailClosed() {
        listOf(Triple("item", null, null), Triple(null, 1L, 1L), Triple("item", 1L, null), Triple("item", null, 1L)).forEach { (id, revision, generation) ->
            assertInvalid { OrdinaryDestinationRows.admission("creation", "ALBUM_COPY", "operation", "grant", id, revision, generation) }
        }
    }
    @Test fun badIdentitiesAndNonpositiveVersionNumbersFailClosed() {
        listOf("", "../item", "x".repeat(129)).forEach { id ->
            assertInvalid { OrdinaryDestinationRows.admission(id, "ALBUM_COPY", "operation", "grant", null, null, null) }
            assertInvalid { OrdinaryDestinationRows.admission("creation", "ALBUM_COPY", "operation", "grant", id, 1, 1) }
        }
        listOf(0L, -1L).forEach {
            assertInvalid { OrdinaryDestinationRows.admission("creation", "ALBUM_COPY", "operation", "grant", "item", it, 1) }
            assertInvalid { OrdinaryDestinationRows.admission("creation", "ALBUM_COPY", "operation", "grant", "item", 1, it) }
        }
    }
    @Test fun unboundRowsStayUnbound() {
        val row = OrdinaryDestinationRows.admission("creation", "ALBUM_COPY", "operation", "grant", null, null, null)
        assertNull(row.source)
        assertNull(row.exportRequest())
    }
    private fun assertInvalid(block: () -> Unit) {
        assertEquals(OrdinaryDestinationError.INVALID_RECORD, assertThrows(OrdinaryDestinationFailure::class.java) { block() }.code)
    }
}
