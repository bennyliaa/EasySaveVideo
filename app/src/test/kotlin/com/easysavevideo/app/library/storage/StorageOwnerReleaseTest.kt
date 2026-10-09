package com.easysavevideo.app.library.storage

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test

class StorageOwnerReleaseTest {
    @Test fun successfulCloseReleasesDatabaseBeforeDirectoryOwnership() {
        val calls = mutableListOf<String>()
        StorageOwnerRelease.close(AutoCloseable { calls += "database" }, AutoCloseable { calls += "owner" }) { _, _ -> calls += "retain" }
        assertEquals(listOf("database", "owner"), calls)
    }

    @Test fun throwingDatabaseCloseRetainsBothOwnersWithoutReleasingDirectory() {
        val calls = mutableListOf<String>()
        val database = AutoCloseable { calls += "database"; throw IllegalStateException("SYNTHETIC_CLOSE_FAILURE") }
        val owner = AutoCloseable { calls += "owner" }
        val failure = assertThrows(SaveFailure::class.java) {
            StorageOwnerRelease.close(database, owner) { retainedDatabase, retainedOwner ->
                assertSame(database, retainedDatabase); assertSame(owner, retainedOwner); calls += "retain"
            }
        }
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, failure.code)
        assertEquals(listOf("database", "retain"), calls)
    }

    @Test fun throwingDirectoryCloseAlsoCannotReportConfirmedRelease() {
        val calls = mutableListOf<String>()
        val database = AutoCloseable { calls += "database" }
        val owner = AutoCloseable { calls += "owner"; throw IllegalStateException("SYNTHETIC_OWNER_FAILURE") }
        val failure = assertThrows(SaveFailure::class.java) {
            StorageOwnerRelease.close(database, owner) { retainedDatabase, retainedOwner ->
                assertSame(database, retainedDatabase); assertSame(owner, retainedOwner); calls += "retain"
            }
        }
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, failure.code)
        assertEquals(listOf("database", "owner", "retain"), calls)
    }

    @Test fun failureBeforeDatabaseConstructionStillReleasesDirectoryOwner() {
        var released = false
        StorageOwnerRelease.close(null, AutoCloseable { released = true }) { _, _ -> fail("Unexpected uncertainty") }
        assertTrue(released)
    }
}
