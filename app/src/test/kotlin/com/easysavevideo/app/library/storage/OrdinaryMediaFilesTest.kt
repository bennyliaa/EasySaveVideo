package com.easysavevideo.app.library.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.file.Files

/** Synthetic temporary bytes only. Exercises the production path/delete policy, not Android fsync. */
class OrdinaryMediaFilesTest {
    private val id = "55b4e1b1-4a74-4a0e-b985-41605c5ba4f7"
    private fun root() = Files.createTempDirectory("esv-library-files-test-").toFile()

    @Test fun exactOpaqueReadUsesReadOnlyDescriptorAndChecksSize() {
        val root = root()
        try {
            val file = File(root, "$id.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val policy = OrdinaryMediaFiles(root, {})
            assertEquals(file, policy.readable(id, 3))
            FileInputStream(policy.readable(id, 3)).use { input ->
                assertArrayEquals(byteArrayOf(1, 2, 3), input.readBytes())
                assertThrows(NonWritableChannelException::class.java) { input.channel.write(ByteBuffer.wrap(byteArrayOf(9))) }
            }
            assertThrows(IOException::class.java) { policy.readable(id, 4) }
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun traversalDisplayNamesAndForeignFilesNeverBecomeDeleteAuthority() {
        val root = root()
        try {
            val unknown = File(root, "keep-me.mp4").apply { writeText("preserve") }
            val policy = OrdinaryMediaFiles(root, {})
            for (value in listOf("../keep-me", "/tmp/file", "name", "$id/..", "$id.mp4", id.uppercase())) {
                assertThrows(IOException::class.java) { policy.remove(value) }
            }
            assertEquals("preserve", unknown.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun symbolicMediaAndDirectoryLinksAreNeverFollowed() {
        val root = root(); val outside = root()
        try {
            val other = File(outside, "$id.mp4").apply { writeText("preserve") }
            val link = File(root, "$id.mp4")
            Files.createSymbolicLink(link.toPath(), other.toPath())
            val policy = OrdinaryMediaFiles(root, {})
            assertThrows(IOException::class.java) { policy.readable(id, 8) }
            assertThrows(IOException::class.java) { policy.remove(id) }
            Files.delete(link.toPath())
            Files.createSymbolicLink(link.toPath(), File(outside, "missing").toPath())
            assertThrows(IOException::class.java) { policy.remove(id) }
            val linkedDirectory = File(root, "linked")
            Files.createSymbolicLink(linkedDirectory.toPath(), outside.toPath())
            assertThrows(IOException::class.java) { OrdinaryMediaFiles(linkedDirectory, {}).remove(id) }
            assertEquals("preserve", other.readText())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun failedUnlinkRemainsPresentAndDoesNotPretendToSync() {
        val root = root()
        try {
            val file = File(root, "$id.mp4").apply { writeText("synthetic") }
            var syncs = 0
            val policy = OrdinaryMediaFiles(root, { syncs++ }, unlink = { false })
            assertThrows(IOException::class.java) { policy.remove(id) }
            assertEquals("synthetic", file.readText()); assertEquals(0, syncs)
        } finally { root.deleteRecursively() }
    }

    @Test fun crashAfterUnlinkStillRequiresDirectoryFenceBeforeRecoveryCompletes() {
        val root = root()
        try {
            val file = File(root, "$id.mp4").apply { writeText("synthetic") }
            var syncs = 0
            val policy = OrdinaryMediaFiles(root, { syncs++; if (syncs == 1) throw IOException("SYNTHETIC_FSYNC_FAILURE") })
            assertThrows(IOException::class.java) { policy.remove(id) }
            assertFalse(file.exists())
            policy.remove(id)
            assertEquals(2, syncs)
        } finally { root.deleteRecursively() }
    }

    @Test fun refusesDirectoriesAndUnexpectedReplacementWithoutRecursiveCleanup() {
        val root = root()
        try {
            val directory = File(root, "$id.mp4").apply { mkdir() }
            val unknown = File(directory, "unrelated").apply { writeText("preserve") }
            assertThrows(IOException::class.java) { OrdinaryMediaFiles(root, {}).remove(id) }
            assertEquals("preserve", unknown.readText())
        } finally { root.deleteRecursively() }
    }
}
