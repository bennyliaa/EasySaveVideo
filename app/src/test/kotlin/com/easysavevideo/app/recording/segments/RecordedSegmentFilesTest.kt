package com.easysavevideo.app.recording.segments

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class RecordedSegmentFilesTest {
    @Test fun privateRandomIdentityAndExactHashSurviveFreshOwner() = temporary { root ->
        val syncs = mutableListOf<String>(); val files = RecordedSegmentFiles(root, { syncs += it.name }); val id = id()
        val (file, identity) = files.create(id); file.writeBytes(byteArrayOf(3, 4, 5))
        val hash = files.seal(id, identity, 3)
        assertEquals(hash, RecordedSegmentFiles(root, {}).hash(id, identity, 3))
        assertTrue(syncs.contains("recording-segments")); assertFalse(File(root, "media").exists())
        files.remove(id, identity, 3, hash); assertFalse(file.exists())
    }
    @Test fun inodeReplacementWithSameBytesNeverPassesOrDeletesReplacement() = temporary { root ->
        val files = RecordedSegmentFiles(root, {}); val id = id(); val (file, identity) = files.create(id)
        file.writeBytes(byteArrayOf(1, 2)); val hash = files.seal(id, identity, 2)
        val held = File(root, "old"); assertTrue(file.renameTo(held)); file.writeBytes(byteArrayOf(1, 2))
        rejects { files.resolve(id, identity) }; rejects { files.remove(id, identity, 2, hash) }
        assertArrayEquals(byteArrayOf(1, 2), file.readBytes())
    }
    @Test fun appendedTruncatedAndMutatedRawIsRetained() = temporary { root ->
        val files = RecordedSegmentFiles(root, {}); val id = id(); val (file, identity) = files.create(id)
        file.writeBytes(byteArrayOf(1, 2, 3)); val hash = files.seal(id, identity, 3)
        for (bytes in listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(1, 2), byteArrayOf(3, 2, 1))) {
            file.writeBytes(bytes); rejects { files.remove(id, identity, 3, hash) }; assertTrue(file.exists())
        }
    }
    @Test fun unknownCrashIdentityNeverAuthorizesDeletion() = temporary { root ->
        val files = RecordedSegmentFiles(root, {}); val id = id(); val (file, _) = files.create(id)
        rejects { files.remove(id, null, null, null) }; assertTrue(file.exists())
    }
    @Test fun symlinkAndTraversalNeverTouchOutsideFiles() = temporary { root ->
        val files = RecordedSegmentFiles(root, {}); val original = File(root, "original").apply { writeText("retain") }
        val id = id(); Files.createSymbolicLink(File(root, "recording-segments/$id.mp4").toPath(), original.toPath())
        rejects { files.create("../original") }; rejects { files.remove(id, "anything", null, null) }
        assertEquals("retain", original.readText())
    }
    @Test fun failedDirectorySyncDoesNotPretendCleanupCompleted() = temporary { root ->
        var failSync = false; val files = RecordedSegmentFiles(root, { if (failSync) error("SYNC") })
        val id = id(); val (file, identity) = files.create(id); file.writeBytes(byteArrayOf(1)); val hash = files.seal(id, identity, 1)
        failSync = true; rejects { files.remove(id, identity, 1, hash) }
        failSync = false; files.remove(id, identity, 1, hash); assertFalse(file.exists())
    }
    @Test fun unconfirmedHelperReadClosePinsFileUntilProcessRecovery() = temporary { root ->
        var release: (() -> Unit)? = null
        val files = RecordedSegmentFiles(root, {}, { file -> object : java.io.FileInputStream(file) {
            override fun close() { /* synthetic uncooperative close */ }
            fun actualClose() { super.close() }
        }.also { release = it::actualClose } })
        val id = id(); val (file, identity) = files.create(id); file.writeBytes(byteArrayOf(1))
        rejects { files.hash(id, identity, 1) }; assertTrue(files.requiresRecovery)
        rejects { files.remove(id, identity, null, null) }; assertTrue(file.exists())
        // Test-only release of the genuinely opened descriptor; production retains it on failure.
        requireNotNull(release).invoke()
        assertTrue(files.requiresRecovery)
    }
    private fun id() = UUID.randomUUID().toString()
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) { } }
    private fun temporary(block: (File) -> Unit) { val root = Files.createTempDirectory("esv-segment-files-").toFile(); try { block(root) } finally { root.deleteRecursively() } }
}
