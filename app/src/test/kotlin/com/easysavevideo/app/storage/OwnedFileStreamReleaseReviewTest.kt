package com.easysavevideo.app.storage

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Complementary close-path checks: nested failures, callback accounting, and read-only bytes. */
class OwnedFileStreamReleaseReviewTest {
    @Test fun nestedParentCloseReleasesOnceWithoutHidingAnOuterPlatformFailure() {
        val lock = Any()
        var descriptorClosed = false
        var releases = 0
        val failure = IOException("synthetic-outer-close-failure")
        val release = OwnedFileStreamRelease(lock, {
            assertTrue(Thread.holdsLock(lock))
            assertTrue(descriptorClosed)
            releases++
        }, { throw AssertionError("Descriptor was actually closed") })

        try {
            release.close({
                assertFalse(Thread.holdsLock(lock))
                assertEquals(0, releases)
                // Model FileChannel calling its parent stream after marking itself closed.
                release.close({}, {
                    assertFalse(Thread.holdsLock(lock))
                    descriptorClosed = true
                }, { descriptorClosed })
                assertEquals(1, releases)
            }, {
                assertFalse(Thread.holdsLock(lock))
                throw failure
            }, { descriptorClosed })
            fail("A nested successful close must not hide the outer close exception")
        } catch (actual: IOException) { assertSame(failure, actual) }
        assertEquals(1, releases)
    }

    @Test fun ownershipCallbackFailurePropagatesWithoutRepeatingAccounting() {
        val lock = Any()
        var releases = 0
        val failure = IllegalStateException("synthetic-owner-callback-failure")
        val release = OwnedFileStreamRelease(lock, {
            assertTrue(Thread.holdsLock(lock))
            releases++
            throw failure
        }, { throw AssertionError("Descriptor was actually closed") })

        try {
            release.close({}, {}, { true })
            fail("The first close must propagate the ownership callback failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        repeat(3) { release.close({}, {}, { true }) }
        assertEquals(1, releases)
    }

    @Test fun ordinaryReaderClosePreservesTheSourceAndAllowsAFullReopen() {
        val file = Files.createTempFile("esv-read-close-review-", ".bin").toFile()
        val bytes = ByteArray(1027) { (it * 31).toByte() }
        try {
            file.writeBytes(bytes)
            var releases = 0
            val input = OwnedFileInputStream(file, Any(), { releases++ }, {
                throw AssertionError("Descriptor release was not confirmed")
            })
            val descriptor = input.fd
            input.use {
                it.channel.position(513)
                assertEquals(bytes[513].toInt() and 255, it.read())
            }
            assertFalse(descriptor.valid())
            assertEquals(1, releases)
            assertTrue(file.isFile)
            assertEquals(bytes.size.toLong(), file.length())
            assertArrayEquals(bytes, file.readBytes())
        } finally { Files.deleteIfExists(file.toPath()) }
    }
}
