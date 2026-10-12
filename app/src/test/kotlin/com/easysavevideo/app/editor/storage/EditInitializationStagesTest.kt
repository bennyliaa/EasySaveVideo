package com.easysavevideo.app.editor.storage
import com.easysavevideo.app.diagnostics.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class EditInitializationStagesTest {
    @Test fun occupiedDirectoryDoesNotBecomeReadyOrAlterTheBlockingFile() {
        val root=Files.createTempDirectory("esv-init-owned-").toFile()
        try {
            val blocker=File(root,"edit-staging").apply{writeText("retain")};val p=InitializationProbe()
            try{EditArtifactFiles(root,{}, {_,_->},p);fail()}catch(_:IOException){}
            assertEquals(InitializationStage.EDIT_STAGING_CREATE,p.failure?.stage);assertEquals("retain",blocker.readText())
            assertFalse(File(root,"media").exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun fsyncFailureIsNotSwallowedOrReplacedBySuccess() {
        val root=Files.createTempDirectory("esv-init-owned-").toFile()
        try {
            val original=IOException("private");val p=InitializationProbe()
            try{EditArtifactFiles(root,{throw original},{_,_->},p);fail()}catch(failure:IOException){assertSame(original,failure)}
            assertEquals(InitializationStage.EDIT_ROOT_FSYNC,p.failure?.stage)
        }finally{root.deleteRecursively()}
    }

    @Test fun mediaBlockerRecordsItsOwnStageAndRetainsContent() {
        val root = Files.createTempDirectory("esv-init-owned-").toFile().canonicalFile
        try {
            val blocker = File(root, "media").apply { writeText("retain") }; val p = InitializationProbe()
            try { EditArtifactFiles(root, {}, { _, _ -> }, p); fail() } catch (_: IOException) { }
            assertEquals(InitializationStage.EDIT_MEDIA_CREATE, p.failure?.stage)
            assertEquals("retain", blocker.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun nonCanonicalAncestorIsRejectedBeforeFsync() {
        val root = Files.createTempDirectory("esv-init-owned-").toFile().canonicalFile
        try {
            File(root, "child").mkdir(); var synced = false; val p = InitializationProbe()
            try { EditArtifactFiles(File(root, "child/.."), { synced = true }, { _, _ -> }, p); fail() } catch (_: IOException) { }
            assertEquals(InitializationStage.EDIT_STAGING_CANONICAL, p.failure?.stage)
            assertFalse(synced)
        } finally { root.deleteRecursively() }
    }
    @Test fun symbolicLinkDirectoryIsRejectedWithoutTouchingItsTarget() {
        val root = Files.createTempDirectory("esv-init-owned-").toFile().canonicalFile
        try {
            val target = File(root, "target").apply { mkdir() }
            val retained = File(target, "retained").apply { writeText("retain") }
            Files.createSymbolicLink(File(root, "edit-staging").toPath(), target.toPath())
            val p = InitializationProbe()
            try { EditArtifactFiles(root, {}, { _, _ -> }, p); fail() } catch (_: IOException) { }
            assertEquals(InitializationStage.EDIT_STAGING_CHECK, p.failure?.stage)
            assertEquals("retain", retained.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun successfulInitializationHasNoFailureAndSyncsRootOnce() {
        val root = Files.createTempDirectory("esv-init-owned-").toFile().canonicalFile
        try {
            val p = InitializationProbe(); val synced = mutableListOf<File>()
            EditArtifactFiles(root, { synced += it }, { _, _ -> }, p)
            assertNull(p.failure); assertEquals(listOf(root), synced)
            assertTrue(File(root, "edit-staging").isDirectory); assertTrue(File(root, "media").isDirectory)
        } finally { root.deleteRecursively() }
    }
}
