package com.easysavevideo.app.vault

import org.junit.Assert.*
import org.junit.Test

/** Deterministic public fake identities only. No Android/media or security acceptance implied. */
class VaultThumbnailViewportTest {
    private fun row(index: Int, generation: Long = 1) = VaultThumbnailViewportRow(
        VaultMediaReadRef("public_fixture", "fixed_$index", generation, generation))

    @Test fun onlyActualViewportMarkersFromExactCurrentCatalogAreAdmitted() {
        val first = row(1); val second = row(2); val composedOffscreen = row(3)
        assertEquals(listOf(second.ref), vaultVisibleThumbnailRefs(listOf(first, second, composedOffscreen),
            listOf("header", null, second, "footer")))
    }
    @Test fun copiedFieldsAndOldLayoutDoNotReconstructMembership() {
        val current = row(1); val oldLayout = row(1)
        assertEquals(emptyList<VaultMediaReadRef>(), vaultVisibleThumbnailRefs(listOf(current), listOf(oldLayout)))
    }
    @Test fun generationReplacementRejectsOldMeasuredMarkers() {
        val old = row(1, 1); val current = row(1, 2)
        assertEquals(emptyList<VaultMediaReadRef>(), vaultVisibleThumbnailRefs(listOf(current), listOf(old)))
    }
    @Test fun viewportOrderIsStableAndBoundedToSixteen() {
        val rows = (1..22).map { row(it) }
        assertEquals(rows.reversed().take(16).map { it.ref }, vaultVisibleThumbnailRefs(rows, rows.reversed()))
    }
    @Test fun duplicateVisibleEntriesDoNotConsumeAdditionalSlots() {
        val rows = (1..17).map { row(it) }
        val visible = listOf(rows[0], rows[0]) + rows.drop(1)
        assertEquals(rows.take(16).map { it.ref }, vaultVisibleThumbnailRefs(rows, visible))
    }
    @Test fun emptyAndOverWireBoundCatalogDoNotAdmitWork() {
        assertTrue(vaultVisibleThumbnailRefs(emptyList(), listOf(row(1))).isEmpty())
        val rows = (1..298).map { row(it) }
        assertTrue(vaultVisibleThumbnailRefs(rows, rows).isEmpty())
    }
    @Test fun markerTextNeverContainsFixtureIdentity() {
        assertEquals("VaultThumbnailViewportRow(REDACTED)", row(1).toString())
    }
}
