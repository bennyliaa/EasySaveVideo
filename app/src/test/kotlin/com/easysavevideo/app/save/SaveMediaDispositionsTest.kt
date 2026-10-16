package com.easysavevideo.app.save

import com.easysavevideo.app.library.storage.requireLibraryRepresentationBinding
import com.easysavevideo.library.LibraryError
import com.easysavevideo.library.LibraryFailure
import com.easysavevideo.library.MediaDateKind
import com.easysavevideo.library.MediaItem
import com.easysavevideo.library.MediaLifecycle
import org.junit.Assert.*
import org.junit.Test

/** Real display helper and binding validator; no Android database or startup acceptance claim. */
class SaveMediaDispositionsTest {
    private fun item(id: String, lifecycle: MediaLifecycle = MediaLifecycle.READY) =
        MediaItem(id, 1L, 7L, null, "Video", 0L, MediaDateKind.SAVED,
            if (lifecycle == MediaLifecycle.DELETED) 0L else 100L, null, null, null, lifecycle)

    @Test fun aRecoveryFailureStaysUnknownAndLaterItemsAreStillRead() {
        val read = mutableListOf<String>()
        val result = readSaveMediaDispositions(listOf("first", "broken", "last")) { id ->
            read += id
            if (id == "broken") throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
            item(id)
        }
        assertEquals(listOf("first", "broken", "last"), read)
        assertEquals(mapOf("first" to MediaLifecycle.READY, "last" to MediaLifecycle.READY), result)
        assertFalse(result.containsKey("broken"))
    }

    @Test fun missingItemStaysUnknownWithoutInventingDeletedOrReady() {
        assertTrue(readSaveMediaDispositions(listOf("missing")) { null }.isEmpty())
    }

    @Test fun allKnownLibraryFailuresAreDisplayUnavailableOnly() {
        for (code in LibraryError.entries) {
            val result = readSaveMediaDispositions(listOf("broken", "healthy")) { id ->
                if (id == "broken") throw LibraryFailure(code)
                item(id)
            }
            assertEquals(mapOf("healthy" to MediaLifecycle.READY), result)
        }
    }

    @Test fun successfulLifecycleValuesArePreservedIncludingDeletedTombstones() {
        val expected = MediaLifecycle.entries.associate { it.name to it }
        val actual = readSaveMediaDispositions(expected.keys.toList()) { item(it, expected.getValue(it)) }
        assertEquals(expected, actual)
    }

    @Test fun aLaterFailedRefreshDoesNotRetainAnEarlierReadyEntry() {
        assertEquals(mapOf("item" to MediaLifecycle.READY), readSaveMediaDispositions(listOf("item")) { item(it) })
        assertTrue(readSaveMediaDispositions(listOf("item")) {
            throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        }.isEmpty())
    }

    @Test fun brokenBindingCannotBecomeReadyWhileOtherDisplayEntriesSurvive() {
        val result = readSaveMediaDispositions(listOf("broken", "healthy")) { id ->
            requireLibraryRepresentationBinding(id, "representation", 7L,
                "representation", if (id == "broken") "another-item" else id, 7L)
            item(id)
        }
        assertEquals(mapOf("healthy" to MediaLifecycle.READY), result)
    }

    @Test fun unexpectedProgrammingFailureIsNotSilentlyConvertedToUnknown() {
        val failure = IllegalStateException("synthetic-programming-failure")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            readSaveMediaDispositions(listOf("item")) { throw failure }
        })
    }

    @Test fun noReceiptsMeansNoLookupsOrFabricatedDispositions() {
        assertTrue(readSaveMediaDispositions(emptyList()) { fail("Unexpected lookup"); null }.isEmpty())
    }
}
