package com.easysavevideo.app.save

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Real local descriptor/hash behavior with a separately injected descriptor-identity observation. */
class FixedDirectOutputInputTest {
    private inline fun withInput(body: (File, RandomAccessFile, FixedDirectOutputInput) -> Unit) {
        val file = File.createTempFile("direct-owned-", ".bin")
        try {
            file.writeBytes(ByteArray(131_073) { (it % 251).toByte() })
            RandomAccessFile(file, "r").use { input ->
                val captured = DirectFileIdentity(7, 11, input.length())
                val fixed = FixedDirectOutputInput(input, captured, { captured.copy(size = input.length()) }, {})
                body(file, input, fixed)
            }
        } finally { file.delete() }
    }
    private fun failure(code: SaveError, body: () -> Unit) {
        assertEquals(code, assertThrows(SaveFailure::class.java, body).code)
    }
    @Test fun hashesEveryByteFromFixedDescriptorAndDoesNotCloseCaller() = withInput { file, input, fixed ->
        val expected = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, fixed.requireHash(expected))
        assertTrue(input.fd.valid())
        assertEquals(131_073L, fixed.length)
    }
    @Test fun sameSizeMutationCannotReuseExpectedHash() = withInput { file, _, fixed ->
        val before = fixed.sha256()
        RandomAccessFile(file, "rw").use { it.seek(70_000); it.writeByte(255) }
        failure(SaveError.RESOURCE_CHANGED) { fixed.requireHash(before) }
    }
    @Test fun truncationAndExpansionNeverAdoptLiveExtent() = withInput { file, _, fixed ->
        val original = fixed.length
        RandomAccessFile(file, "rw").use { it.setLength(original - 1) }
        failure(SaveError.RESOURCE_CHANGED) { fixed.readAt(0, ByteArray(1), 0, 1) }
        assertEquals(original, fixed.length)
        RandomAccessFile(file, "rw").use { it.setLength(original + 1) }
        failure(SaveError.RESOURCE_CHANGED) { fixed.sha256() }
        assertEquals(original, fixed.length)
    }
    @Test fun injectedDescriptorIdentityReplacementRejectsSameLength() = withInput { _, input, _ ->
        val captured = DirectFileIdentity(7, 11, input.length())
        var actual = captured
        val fixed = FixedDirectOutputInput(input, captured, { actual }, {})
        actual = actual.copy(inode = 12)
        failure(SaveError.RESOURCE_CHANGED) { fixed.readAt(0, ByteArray(1), 0, 1) }
    }
    @Test fun boundsRejectOverflowAndOutOfRangeBeforeReading() = withInput { _, _, fixed ->
        failure(SaveError.INVALID_MEDIA) { fixed.readAt(Long.MAX_VALUE, ByteArray(1), 0, 1) }
        failure(SaveError.INVALID_MEDIA) { fixed.readAt(fixed.length, ByteArray(1), 0, 1) }
        failure(SaveError.INVALID_MEDIA) { fixed.readAt(0, ByteArray(1), 1, 1) }
        failure(SaveError.INVALID_MEDIA) { fixed.readAt(-1, ByteArray(1), 0, 1) }
    }
    @Test fun ownershipIdentityIsCheckedBothBeforeAndAfterEachRead() = withInput { _, input, _ ->
        val captured = DirectFileIdentity(7, 11, input.length())
        var checks = 0
        val fixed = FixedDirectOutputInput(input, captured, { checks++; captured }, {})
        fixed.readAt(0, ByteArray(100), 0, 100)
        assertEquals(2, checks)
    }
    @Test fun mutationBetweenBeforeAndAfterReadCannotEscape() = withInput { _, input, _ ->
        val captured = DirectFileIdentity(7, 11, input.length())
        var checks = 0
        val fixed = FixedDirectOutputInput(input, captured, { if (++checks == 1) captured else captured.copy(inode = 99) }, {})
        failure(SaveError.RESOURCE_CHANGED) { fixed.readAt(0, ByteArray(100), 0, 100) }
    }
    @Test fun replacedPathIsNeverImplicitlyReopenedByFixedView() = withInput { file, input, fixed ->
        val before = fixed.sha256()
        val moved = File(file.parentFile, file.name + ".moved")
        try {
            assertTrue(file.renameTo(moved))
            file.writeBytes(ByteArray(fixed.length.toInt()) { 42 })
            assertEquals(before, fixed.sha256())
            assertTrue(input.fd.valid())
            // The separately required owner revalidate must reject pathname-generation replacement.
        } finally { moved.delete() }
    }
    @Test fun localReadFailureCannotBecomeIncompleteHttpTransfer() = withInput { _, input, fixed ->
        input.close()
        failure(SaveError.STORAGE_FAILURE) { fixed.sha256() }
    }
    @Test fun cancellationIsNotRemappedToStorageOrMediaFailure() = withInput { _, input, _ ->
        val captured = DirectFileIdentity(7, 11, input.length())
        val stopped = FixedDirectOutputInput(input, captured, { captured }, { throw SaveFailure(SaveError.CANCELLED) })
        failure(SaveError.CANCELLED) { stopped.sha256() }
        assertTrue(input.fd.valid())
    }
}
