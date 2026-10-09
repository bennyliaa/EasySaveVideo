package com.easysavevideo.library

import org.junit.Assert.*
import org.junit.Test

class LibraryModelsTest {
    @Test fun readReferenceBindsExactRevisionAndGeneration() {
        val item = item()
        val ref = item.readRef()
        assertEquals(MediaReadRef("item", 1, 3), ref)
        LibraryItemPolicy.requireReadable(item, ref)
        fails(LibraryError.STALE_REVISION) { LibraryItemPolicy.requireReadable(item.copy(revision = 2), ref) }
        fails(LibraryError.STALE_REVISION) { LibraryItemPolicy.requireReadable(item.copy(representationGeneration = 4), ref) }
        fails(LibraryError.NOT_FOUND) { LibraryItemPolicy.requireReadable(item.copy(itemId = "other"), ref) }
    }

    @Test fun deletionTombstonesAreNotPlayable() {
        for (lifecycle in listOf(MediaLifecycle.DELETE_PENDING, MediaLifecycle.DELETED)) {
            val hidden = item().copy(lifecycle = lifecycle)
            fails(LibraryError.NOT_FOUND) { hidden.readRef() }
            fails(LibraryError.NOT_FOUND) { LibraryItemPolicy.requireReadable(hidden, item().readRef()) }
            fails(LibraryError.NOT_FOUND) { LibraryItemPolicy.requireExpectedRevision(hidden, 1) }
        }
    }

    @Test fun metadataCasRejectsStaleRevision() {
        LibraryItemPolicy.requireExpectedRevision(item(), 1)
        fails(LibraryError.STALE_REVISION) { LibraryItemPolicy.requireExpectedRevision(item(), 2) }
    }

    @Test fun unknownLegacyDimensionsAndDurationRemainUnknown() {
        val legacy = item().copy(dateKind = MediaDateKind.IMPORTED, durationMillis = null, width = null, height = null)
        assertNull(legacy.durationMillis)
        assertNull(legacy.width)
        assertEquals(MediaDateKind.IMPORTED, legacy.dateKind)
    }

    @Test fun storedNamesMustBeCanonicalNfc() {
        fails(LibraryError.INVALID_NAME) { item().copy(displayName = "Cafe\u0301") }
        fails(LibraryError.INVALID_NAME) { LibraryFolder("folder", 1, null, "Cafe\u0301") }
        assertEquals("Café", item().copy(displayName = LibraryNames.validate("Cafe\u0301")).displayName)
    }

    @Test fun invalidMetadataAndReferencesAreRejected() {
        listOf<() -> Unit>(
            { item().copy(revision = 0) }, { item().copy(representationGeneration = 0) },
            { item().copy(createdAtMillis = -1) }, { item().copy(sizeBytes = -1) },
            { item().copy(durationMillis = -1) }, { item().copy(width = 0) }, { item().copy(height = null) },
            { MediaReadRef("item", -1, 1) }, { MediaReadRef("item", 1, 0) },
            { LibraryFolder("folder", 0, null, "Folder") }, { PendingDeleteReport(-1, 0, 0) },
        ).forEach { assertThrows(IllegalArgumentException::class.java, it) }
    }

    @Test fun requestRequiresAnExplicitTrueConfirmation() {
        val unconfirmed = ConfirmedDeleteRequest(item().readRef(), "operation", false)
        fails(LibraryError.CONFIRMATION_REQUIRED) { unconfirmed.requireConfirmation() }
        unconfirmed.copy(confirmed = true).requireConfirmation()
    }

    @Test fun itemNameCollisionUsesCanonicalKeyAndOnlyReadySiblings() {
        val existing = item().copy(displayName = "Café")
        fails(LibraryError.ALREADY_EXISTS) { LibraryItemPolicy.requireUniqueName(null, "CAFE\u0301", listOf(existing)) }
        LibraryItemPolicy.requireUniqueName("folder", "CAFÉ", listOf(existing))
        LibraryItemPolicy.requireUniqueName(null, "CAFÉ", listOf(existing), existing.itemId)
        LibraryItemPolicy.requireUniqueName(null, "CAFÉ", listOf(existing.copy(lifecycle = MediaLifecycle.DELETED)))
    }

    @Test fun summariesNeverLogMetadata() {
        val item = item().copy(displayName = "Private label", folderId = "privateFolder")
        val values = listOf(item, item.readRef(), LibraryFolder("privateFolder", 1, null, "Private label"),
            LibraryQuery(text = "Private label"), ConfirmedDeleteRequest(item.readRef(), "privateOperation", true))
        values.forEach {
            assertFalse(it.toString().contains("Private label"))
            assertFalse(it.toString().contains("privateFolder"))
            assertFalse(it.toString().contains("privateOperation"))
        }
    }

    private fun item() = MediaItem("item", 1, 3, null, "Video", 123, MediaDateKind.SAVED, 100, 10, 640, 480)
    private fun fails(error: LibraryError, action: () -> Unit) {
        assertEquals(error, assertThrows(LibraryFailure::class.java, action).code)
    }
}
