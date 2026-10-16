package com.easysavevideo.app.library.storage

import com.easysavevideo.library.LibraryError
import com.easysavevideo.library.LibraryFailure
import com.easysavevideo.library.MediaDateKind
import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.MediaLifecycle
import org.junit.Assert.*
import org.junit.Test

/** Pure joined-identity validation only; these fixtures do not execute SQLite or media IO. */
class LibraryRepresentationBindingTest {
    private fun binding(
        representationId: String? = "representation",
        itemId: String? = "item",
        generation: Long? = 7L,
    ) = requireLibraryRepresentationBinding("item", "representation", 7L, representationId, itemId, generation)

    private fun recovery(block: () -> Unit) {
        val failure = assertThrows(LibraryFailure::class.java, block)
        assertEquals(LibraryError.RECOVERY_REQUIRED, failure.code)
        assertEquals("RECOVERY_REQUIRED", failure.message)
        assertNull(failure.cause)
    }

    @Test fun exactRepresentationItemAndGenerationAreAccepted() { binding() }

    @Test fun missingRepresentationRowRequiresRecovery() { recovery { binding(null, null, null) } }

    @Test fun missingAnyJoinedIdentityRequiresRecovery() {
        recovery { binding(representationId = null) }
        recovery { binding(itemId = null) }
        recovery { binding(generation = null) }
    }

    @Test fun wrongRepresentationIdRequiresRecovery() { recovery { binding(representationId = "another-representation") } }

    @Test fun representationOwnedByAnotherItemRequiresRecovery() { recovery { binding(itemId = "another-item") } }

    @Test fun oldOrFutureRepresentationGenerationRequiresRecovery() {
        for (generation in listOf(0L, 1L, 6L, 8L, Long.MAX_VALUE)) recovery { binding(generation = generation) }
    }

    @Test fun exactLargeGenerationIsNotTruncated() {
        requireLibraryRepresentationBinding("item", "representation", Long.MAX_VALUE,
            "representation", "item", Long.MAX_VALUE)
    }

    @Test fun validDeletedTombstoneDoesNotNeedPlayableMediaMetadata() {
        binding()
        // The existing cursor decoder supplies these defaults for cleared tombstone metadata.
        val deleted = MediaItem("item", 3L, 7L, null, "Deleted item", 0L,
            MediaDateKind.IMPORTED, 0L, null, null, null, MediaLifecycle.DELETED)
        assertEquals(MediaLifecycle.DELETED, deleted.lifecycle)
        assertEquals(0L, deleted.sizeBytes)
        assertNull(deleted.durationMillis)
        assertEquals(LibraryError.NOT_FOUND, assertThrows(LibraryFailure::class.java) { deleted.readRef() }.code)
    }

    @Test fun aDeletedTombstoneStillNeedsItsRepresentationBinding() {
        recovery { binding(null, null, null) }
    }
}
