package com.easysavevideo.app.editor.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class EditArtifactFilesTest {
    @Test fun actualSyntheticBytesPromoteWithoutTouchingOriginal() = temporary { root ->
        val media = File(root, "media").apply { mkdirs() }
        val original = File(media, "${UUID.randomUUID()}.mp4").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val syncs = ArrayList<String>(); val files = files(root, syncs)
        val artifact = id(); val output = id(); val bytes = ByteArray(1024) { (it % 251).toByte() }
        files.assertStagingAbsent(artifact); files.assertFinalAbsent(output)
        files.create(artifact).writeBytes(bytes); files.sync(artifact)
        files.promote(artifact, output, bytes.size.toLong(), hash(bytes))
        assertArrayEquals(bytes, File(media, "$output.mp4").readBytes())
        assertFalse(File(root, "edit-staging/$artifact.part").exists())
        assertArrayEquals(byteArrayOf(9, 8, 7), original.readBytes())
        assertTrue(syncs.containsAll(listOf("media", "edit-staging")))
    }
    @Test fun finalCollisionNeverOverwrittenOrCleaned() = temporary { root ->
        val files = files(root); val artifact = id(); val output = id(); val bytes = byteArrayOf(1, 2, 3)
        files.create(artifact).writeBytes(bytes)
        val collision = File(root, "media/$output.mp4").apply { writeBytes(bytes) }
        fails { files.promote(artifact, output, 3, hash(bytes)) }
        fails { files.cleanup(artifact, output, 3L to hash(bytes)) }
        assertArrayEquals(bytes, collision.readBytes()); assertTrue(files.staging(artifact).exists())
    }
    @Test fun absentStageAndDurableMatchingProofAllowOwnPromotedCleanup() = temporary { root ->
        val files = files(root); val artifact = id(); val output = id(); val bytes = byteArrayOf(4, 5, 6)
        files.create(artifact).writeBytes(bytes); files.promote(artifact, output, 3, hash(bytes))
        files.cleanup(artifact, output, 3L to hash(bytes))
        assertFalse(File(root, "media/$output.mp4").exists())
    }
    @Test fun finalWithoutProofOrWithWrongProofIsRetained() = temporary { root ->
        val files = files(root); val artifact = id(); val output = id(); val bytes = byteArrayOf(7, 8, 9)
        files.create(artifact).writeBytes(bytes); files.promote(artifact, output, 3, hash(bytes))
        fails { files.cleanup(artifact, output) }; fails { files.cleanup(artifact, output, 4L to hash(bytes)) }
        fails { files.cleanup(artifact, output, 3L to "0".repeat(64)) }
        assertArrayEquals(bytes, File(root, "media/$output.mp4").readBytes())
    }
    @Test fun stagingCollisionRejectedBeforeReuse() = temporary { root ->
        val files = files(root); val artifact = id(); files.create(artifact).writeBytes(byteArrayOf(1))
        fails { files.assertStagingAbsent(artifact) }; fails { files.create(artifact) }
        assertArrayEquals(byteArrayOf(1), files.staging(artifact).readBytes())
    }
    @Test fun cancellationDuringHashCannotPromote() = temporary { root ->
        val files = files(root); val artifact = id(); val output = id(); val bytes = ByteArray(131072) { 1 }
        files.create(artifact).writeBytes(bytes); var checks = 0
        fails { files.promote(artifact, output, bytes.size.toLong(), hash(bytes)) { if (++checks == 2) error("CANCELLED") } }
        assertFalse(File(root, "media/$output.mp4").exists()); assertTrue(files.staging(artifact).exists())
    }
    @Test fun symlinksAndTraversalNeverAdmitted() = temporary { root ->
        val files = files(root); fails { files.create("../input") }
        val source = File(root, "original").apply { writeText("retain") }; val artifact = id()
        Files.createSymbolicLink(File(root, "edit-staging/$artifact.part").toPath(), source.toPath())
        fails { files.staging(artifact) }; fails { files.cleanup(artifact, id()) }; assertEquals("retain", source.readText())
    }
    private fun files(root: File, syncs: MutableList<String> = arrayListOf()) = EditArtifactFiles(root,
        { syncs += it.name }, { source, target -> Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE); Unit })
    private fun id() = UUID.randomUUID().toString()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fails(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: Exception) { } }
    private fun temporary(action: (File) -> Unit) { val root = Files.createTempDirectory("esv-edit-files-").toFile(); try { action(root) } finally { root.deleteRecursively() } }
}
