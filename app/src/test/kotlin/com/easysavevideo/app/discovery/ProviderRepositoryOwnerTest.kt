package com.easysavevideo.app.discovery

import com.easysavevideo.commons.maintenance.ProviderRepository
import com.easysavevideo.commons.maintenance.ProviderRepositoryError
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderRepositoryOwnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun constructionFailureIsAttemptedOnlyOnceAndDoesNotResetEvidence() {
        val directory = temporary.newFolder()
        val evidence = directory.resolve("retained-evidence").apply { writeText("retained") }
        var attempts = 0
        val owner = ProviderRepositoryOwner { attempts++; throw IllegalStateException("PRIVATE_DETAIL") }
        assertNull(owner.open(directory))
        repeat(5) { assertNull(owner.open(directory)) }
        assertEquals(1, attempts)
        assertEquals("retained", evidence.readText())
    }

    @Test fun repeatedInspectionKeepsSameRepositoryOwnerAndSnapshot() {
        val directory = temporary.newFolder()
        var attempts = 0
        val owner = ProviderRepositoryOwner { attempts++; ProviderRepository(it) }
        val first = owner.open(directory)!!
        try {
            assertSame(first, owner.open(directory))
            assertSame(first, owner.current)
            assertEquals(1, attempts)
            assertNull(first.snapshot().error)
        } finally { first.close() }
    }

    @Test fun busyRepositoryIsRetainedEvenAfterOtherOwnerCloses() {
        val directory = temporary.newFolder()
        val other = ProviderRepository(directory)
        var attempts = 0
        val owner = ProviderRepositoryOwner { attempts++; ProviderRepository(it) }
        val failed = owner.open(directory)!!
        other.close()
        try {
            assertEquals(ProviderRepositoryError.BUSY, failed.snapshot().error)
            assertSame(failed, owner.open(directory))
            assertEquals(1, attempts)
            assertEquals(ProviderRepositoryError.BUSY, owner.current?.snapshot()?.error)
        } finally { failed.close() }
    }
}
