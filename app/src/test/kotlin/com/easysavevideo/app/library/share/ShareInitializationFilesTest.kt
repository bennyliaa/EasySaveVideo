package com.easysavevideo.app.library.share

import com.easysavevideo.app.diagnostics.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ShareInitializationFilesTest {
    @Test fun occupiedDirectoryFailsClosedAndPreservesBlocker() = withRoot { root ->
        val blocked = File(root, "share").apply { writeText("retain") }
        val p = InitializationProbe()
        expectFailure { initializeShareDirectory(blocked, p) }
        assertEquals(InitializationStage.SHARE_DIRECTORY_CREATE, p.failure?.stage)
        assertEquals("retain", blocked.readText())
    }

    @Test fun nonCanonicalAncestorRemainsRejected() = withRoot { root ->
        val directory = File(root, "child/../share")
        File(root, "child").mkdirs()
        val p = InitializationProbe()
        expectFailure { initializeShareDirectory(directory, p) }
        assertEquals(InitializationStage.SHARE_DIRECTORY_CANONICAL, p.failure?.stage)
    }

    @Test fun ownerOpenFailureDoesNotAcquireLockOrChangeBlockingDirectory() = withRoot { root ->
        val blocker = File(root, ".owner").apply { mkdir() }
        val p = InitializationProbe(); var retained = false
        expectFailure { acquireShareOwner(root, p) { retained = true } }
        assertEquals(InitializationStage.SHARE_OWNER_OPEN, p.failure?.stage)
        assertTrue(blocker.isDirectory); assertFalse(retained)
    }

    @Test fun overlappingOwnerLockKeepsExactFailureAndDoesNotReleaseOriginalOwner() = withRoot { root ->
        RandomAccessFile(File(root, ".owner"), "rw").use { original ->
            original.channel.lock().use { originalLock ->
                val p = InitializationProbe(); var retained = false
                try { acquireShareOwner(root, p) { retained = true }; fail() }
                catch (_: OverlappingFileLockException) { }
                assertEquals(InitializationStage.SHARE_OWNER_LOCK, p.failure?.stage)
                assertEquals(InitializationErrorKind.LOCK, p.failure?.kind)
                assertTrue(originalLock.isValid); assertFalse(retained)
            }
        }
        val p = InitializationProbe()
        val recovered = acquireShareOwner(root, p) { fail("unexpected unconfirmed owner") }
        try { assertTrue(recovered.lock.isValid); assertNull(p.failure) }
        finally { recovered.lock.release(); recovered.file.close() }
    }

    @Test fun successfulDirectoryAndOwnerAreReadyWithoutDiagnostic() = withRoot { root ->
        val directory = File(root, "share"); val p = InitializationProbe()
        initializeShareDirectory(directory, p)
        val lease = acquireShareOwner(directory, p) { fail("unexpected unconfirmed owner") }
        try { assertTrue(directory.isDirectory); assertTrue(lease.lock.isValid); assertNull(p.failure) }
        finally { lease.lock.release(); lease.file.close() }
    }

    @Test fun symbolicLinkDirectoryAndOwnerRemainRejected() = withRoot { root ->
        val target = File(root, "target").apply { mkdir() }
        val directory = File(root, "share")
        Files.createSymbolicLink(directory.toPath(), target.toPath())
        val directoryProbe = InitializationProbe()
        expectFailure { initializeShareDirectory(directory, directoryProbe) }
        assertEquals(InitializationStage.SHARE_DIRECTORY_CHECK, directoryProbe.failure?.stage)
        val retained = File(root, "retained").apply { writeText("retain") }
        Files.createSymbolicLink(File(target, ".owner").toPath(), retained.toPath())
        val ownerProbe = InitializationProbe()
        expectFailure { acquireShareOwner(target, ownerProbe) { fail("must not open") } }
        assertEquals(InitializationStage.SHARE_OWNER_CHECK, ownerProbe.failure?.stage)
        assertEquals("retain", retained.readText())
    }

    @Test fun unconfirmedCloseRetainsFirstLockDiagnosticAcrossLaterDenials() = withRoot { root ->
        val file = File(root, ".owner")
        RandomAccessFile(file, "rw").use { original ->
            original.channel.lock().use {
                val probe = InitializationProbe()
                var retained: UnconfirmedShareOwner? = null
                val failingClose = object : RandomAccessFile(file, "rw") {
                    private var injectCloseFailure = true
                    override fun close() {
                        if (injectCloseFailure) throw java.io.IOException("PRIVATE_CLOSE")
                        super.close()
                    }
                    fun forceClose() { injectCloseFailure = false; close() }
                }
                try {
                    try {
                        initializeShareOwner(probe) {
                            acquireShareOwner(root, probe, { failingClose }) { retained = UnconfirmedShareOwner(it, probe.failure) }
                        }
                        fail()
                    } catch (failure: ShareFailure) {
                        assertEquals(ShareError.INITIALIZATION_FAILED, failure.code)
                        assertEquals(InitializationStage.SHARE_OWNER_LOCK, failure.diagnostic?.stage)
                    }
                    assertSame(failingClose, retained?.file)
                    repeat(2) {
                        val failure = retained!!.failure()
                        assertEquals(ShareError.RELEASE_UNCONFIRMED, failure.code)
                        assertEquals(InitializationStage.SHARE_OWNER_LOCK, failure.diagnostic?.stage)
                        assertEquals(InitializationErrorKind.LOCK, failure.diagnostic?.kind)
                        assertNull(failure.cause)
                        assertFalse(failure.toString().contains("PRIVATE"))
                    }
                } finally { failingClose.forceClose() }
            }
        }
    }

    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("expected failure") } catch (_: Exception) { }
    }
    private fun withRoot(action: (File) -> Unit) {
        val root = Files.createTempDirectory("esv-share-init-owned-").toFile().canonicalFile
        try { action(root) } finally { root.deleteRecursively() }
    }
}
