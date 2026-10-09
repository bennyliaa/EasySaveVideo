package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Real temporary files and fsync/atomic-rename operations, with an in-memory SQL contract double. NOT an Android SQLite test. */
class PrivateCommitProtocolTest {
    private val data = ByteArray(192 * 1024) { (it % 251).toByte() }
    private val context = OperationContext("test-operation", Long.MAX_VALUE, CancellationSource())
    private fun root() = Files.createTempDirectory("esv-private-test-").toFile()
    private fun evidence(a: StagingArtifact, bytes: ByteArray = data) = VerificationEvidence(a.artifactId, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        true, 1, 0, 1000, 16, 16, false, DecodePosition.entries.toSet(), true)
    @Test fun streamOutputMovesWithinOwnedNamespaceAndLeavesOriginalContent() {
        val root = root()
        try {
            val p = PrivateCommitProtocol(root, Catalog(), Ops)
            val a = p.prepare("task", "commit", context)
            val dir = p.streamDirectory(a)
            val input = File(dir, "12345678-1234-1234-1234-123456789abc.ts").apply { writeBytes(data) }
            val output = File(dir, "output.mp4").apply { writeBytes(data) }
            p.adoptStreamOutput(a, output, context)
            assertArrayEquals(data, p.stagingFile(a).readBytes())
            assertFalse(output.exists()); assertTrue(input.exists())
            p.cleanupStream(a); assertFalse(dir.exists())
            assertArrayEquals(data, p.stagingFile(a).readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun streamCleanupPreservesUnknownAndSymlinkedFiles() {
        val root = root()
        try {
            val p = PrivateCommitProtocol(root, Catalog(), Ops); val a = p.prepare("task", "commit", context)
            val dir = p.streamDirectory(a); val unknown = File(dir, "unrelated").apply { writeText("preserve") }
            assertThrows(SaveFailure::class.java) { p.cleanupStream(a) }
            assertEquals("preserve", unknown.readText())
            val outside = File(root, "outside.mp4").apply { writeBytes(data) }
            assertThrows(SaveFailure::class.java) { p.adoptStreamOutput(a, outside, context) }
            assertArrayEquals(data, outside.readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun everyCommitCrashWindowReconcilesOnceWithoutDuplicateMedia() {
        for (window in CommitWindow.entries.filter { it != CommitWindow.STAGING_CREATED && !it.name.startsWith("RESTART_") }) {
            val root = root()
            try {
                val catalog = Catalog()
                val first = PrivateCommitProtocol(root, catalog, Ops, fault = CommitFault { if (it == window) throw PowerLoss() })
                val artifact = first.prepare("task", "commit", context)
                first.stagingFile(artifact).writeBytes(data)
                assertThrows(PowerLoss::class.java) { first.commit(artifact, evidence(artifact), context) }
                val restored = PrivateCommitProtocol(root, catalog, Ops)
                val expected = restored.commit(artifact, evidence(artifact), context)
                repeat(3) {
                    assertEquals(expected, restored.commit(artifact, evidence(artifact), context))
                    assertTrue(restored.inspectCommitted("commit", context)!!.confirms(expected))
                }
                assertEquals(1, File(root, "media").listFiles()!!.size)
                assertEquals(0, File(root, "staging").listFiles()!!.size)
                assertArrayEquals(data, restored.mediaFile(expected).readBytes())
                assertEquals(1, catalog.index.size)
            } finally { root.deleteRecursively() }
        }
    }
    @Test fun prepareCrashIsIdempotentAndUsesRandomOwnedNames() {
        val root = root(); val catalog = Catalog()
        try {
            val first = PrivateCommitProtocol(root, catalog, Ops, fault = CommitFault { throw PowerLoss() })
            assertThrows(PowerLoss::class.java) { first.prepare("task", "commit", context) }
            val restored = PrivateCommitProtocol(root, catalog, Ops)
            val one = restored.prepare("task", "commit", context)
            assertEquals(one, restored.prepare("task", "commit", context))
            assertTrue(one.artifactId.matches(Regex("[0-9a-f-]{36}")))
            assertEquals(1, File(root, "staging").listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun neverOverwritesPreexistingFinalOrDeletesItAfterFailure() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data)
            val target = File(File(root, "media"), catalog.rows.getValue("commit").mediaId + ".mp4")
            target.writeText("existing media must survive")
            assertThrows(SaveFailure::class.java) { p.commit(a, evidence(a), context) }
            p.cleanup(a, false)
            assertEquals("existing media must survive", target.readText())
            assertTrue(File(root, "staging").listFiles()!!.isNotEmpty())
            assertTrue(catalog.index.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun renameFailurePreservesIntentAndRecoversOnSameFilesystemWithoutFallback() {
        val root = root(); val catalog = Catalog()
        try {
            val failingOps = object : DurableFileOps by Ops {
                override fun promoteNoReplace(source: File, destination: File) { throw IOException("TEST_EXDEV_OR_DISK_FAILURE") }
            }
            val first = PrivateCommitProtocol(root, catalog, failingOps)
            val a = first.prepare("task", "commit", context); first.stagingFile(a).writeBytes(data)
            assertThrows(IOException::class.java) { first.commit(a, evidence(a), context) }
            first.cleanup(a, false)
            assertEquals(OwnedState.INTENT, catalog.rows.getValue("commit").state)
            val restored = PrivateCommitProtocol(root, catalog, Ops)
            assertTrue(restored.inspectCommitted("commit", context)!!.contentMatches)
        } finally { root.deleteRecursively() }
    }
    @Test fun digestAndIndexAreIndependentlyRechecked() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data)
            val receipt = p.commit(a, evidence(a), context)
            val original = p.mediaFile(receipt)
            original.writeBytes(data.copyOf().also { it[100] = 99 })
            assertThrows(SaveFailure::class.java) { p.inspectCommitted("commit", context) }
            assertTrue(original.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun forgedOwnershipAndSymlinkCannotDeleteUnrelatedFiles() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); val stage = p.stagingFile(a)
            p.cleanup(a.copy(artifactId = "unknown"), false)
            assertTrue(stage.exists())
            val outside = File(root, "unrelated").apply { writeText("untouched") }
            stage.delete(); Files.createSymbolicLink(stage.toPath(), outside.toPath())
            assertThrows(SaveFailure::class.java) { p.cleanup(a, false) }
            assertEquals("untouched", outside.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationBeforeIntentNeverPublishesAndOwnedStagingCanBeCleaned() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data)
            val cancelled = context.copy(cancelToken = CancellationSource().also { it.cancel() })
            assertThrows(SaveFailure::class.java) { p.commit(a, evidence(a), cancelled) }
            p.cleanup(a, false)
            assertEquals(0, File(root, "media").listFiles()!!.size)
            assertEquals(0, File(root, "staging").listFiles()!!.size)
            assertEquals(OwnedState.DISCARDED, catalog.rows.getValue("commit").state)
        } finally { root.deleteRecursively() }
    }
    @Test fun restartUsesNewInodeAndNeverAppendsOldBytes() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); val original = p.stagingFile(a); original.writeText("old")
            FileInputStream(original).use { openOld ->
                val replaced = p.restartStaging(a); replaced.writeText("new")
                assertEquals("old", openOld.readBytes().toString(Charsets.UTF_8))
                assertEquals("new", replaced.readText())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun truncatedStagingCannotCreateIntentOrIndex() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data.copyOf(32))
            assertThrows(SaveFailure::class.java) { p.commit(a, evidence(a), context) }
            assertEquals(OwnedState.STAGING, catalog.rows.getValue("commit").state)
            assertTrue(catalog.index.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun recoveryVerifierCanReadPromotedArtifactButWriterCannot() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data)
            p.commit(a, evidence(a), context)
            assertArrayEquals(data, p.verificationFile(a).readBytes())
            assertThrows(SaveFailure::class.java) { p.stagingFile(a) }
            assertThrows(SaveFailure::class.java) { p.restartStaging(a) }
        } finally { root.deleteRecursively() }
    }
    @Test fun directoryLeaseExcludesSecondWriterAndReallyReleasesOwnership() {
        val root = root()
        try {
            val first = PrivateDirectoryLease(root)
            assertThrows(SaveFailure::class.java) { PrivateDirectoryLease(root) }
            first.close(); first.close()
            PrivateDirectoryLease(root).use { assertTrue(File(root, ".owner.lock").exists()) }
        } finally { root.deleteRecursively() }
    }
    @Test fun directoryLeaseRejectsUnknownSymlinkWithoutTouchingTarget() {
        val root = root()
        try {
            val unknown = File(root, "unrelated").apply { writeText("untouched") }
            Files.createSymbolicLink(File(root, ".owner.lock").toPath(), unknown.toPath())
            assertThrows(SaveFailure::class.java) { PrivateDirectoryLease(root) }
            assertEquals("untouched", unknown.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun lowSpacePreflightPreservesDataAndNeverCreatesCommitIntent() {
        val root = root(); val catalog = Catalog()
        try {
            var free = 0L
            val p = PrivateCommitProtocol(root, catalog, Ops, freeSpace = { free })
            val error = assertThrows(SaveFailure::class.java) { p.prepare("task", "commit", context) }
            assertEquals(SaveError.STORAGE_FULL, error.code)
            assertTrue(catalog.rows.isEmpty())
            free = Long.MAX_VALUE
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data)
            free = 0
            assertEquals(SaveError.STORAGE_FULL, assertThrows(SaveFailure::class.java) { p.commit(a, evidence(a), context) }.code)
            assertEquals(OwnedState.STAGING, catalog.rows.getValue("commit").state)
            assertArrayEquals(data, p.stagingFile(a).readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun operationByteBudgetIsEnforcedBeforeCommit() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data)
            val bounded = context.copy(resourceBudget = ResourceBudget(maxBytes = 32))
            assertEquals(SaveError.BYTE_LIMIT_EXCEEDED, assertThrows(SaveFailure::class.java) { p.commit(a, evidence(a), bounded) }.code)
            assertEquals(OwnedState.STAGING, catalog.rows.getValue("commit").state)
        } finally { root.deleteRecursively() }
    }
    @Test fun mismatchedIndexIsNeverAcceptedOrSilentlyReplaced() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data)
            val receipt = p.commit(a, evidence(a), context)
            val wrong = receipt.copy(sha256 = "0".repeat(64))
            catalog.index["commit"] = wrong
            assertThrows(IllegalStateException::class.java) { p.inspectCommitted("commit", context) }
            assertEquals(wrong, catalog.index["commit"])
            assertArrayEquals(data, p.mediaFile(receipt).readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun restartCrashWindowsReuseOnlyTheOwnedEmptyReplacement() {
        for (window in CommitWindow.entries.filter { it.name.startsWith("RESTART_") }) {
            val root = root(); val catalog = Catalog()
            try {
                val first = PrivateCommitProtocol(root, catalog, Ops, fault = CommitFault { if (it == window) throw PowerLoss() })
                val a = first.prepare("task", "commit", context); first.stagingFile(a).writeBytes(data)
                assertThrows(PowerLoss::class.java) { first.restartStaging(a) }
                val restored = PrivateCommitProtocol(root, catalog, Ops)
                assertEquals(0L, restored.restartStaging(a).length())
                assertEquals(1, File(root, "staging").listFiles()!!.size)
            } finally { root.deleteRecursively() }
        }
    }
    @Test fun restartAndCleanupPreserveUnknownNonemptyReplacement() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data)
            val unknown = File(File(root, "staging"), a.artifactId + ".restart").apply { writeText("preserve") }
            assertThrows(SaveFailure::class.java) { p.restartStaging(a) }
            assertThrows(SaveFailure::class.java) { p.cleanup(a, false) }
            assertEquals("preserve", unknown.readText())
            assertArrayEquals(data, p.stagingFile(a).readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun previewRejectsStagingProvisionalAndMismatchedVisibleReceipt() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data)
            val staged = catalog.rows.getValue("commit")
            val hypothetical = staged.copy(evidence = evidence(a)).receipt()
            assertThrows(SaveFailure::class.java) { p.publishedMediaFile(hypothetical) }
            val receipt = p.commit(a, evidence(a), context)
            assertThrows(SaveFailure::class.java) { p.publishedMediaFile(receipt) }
            catalog.visible["commit"] = receipt.copy(sha256 = "b".repeat(64))
            assertThrows(SaveFailure::class.java) { p.publishedMediaFile(receipt) }
            catalog.visible["commit"] = receipt
            assertArrayEquals(data, p.publishedMediaFile(receipt).readBytes())
            assertThrows(SaveFailure::class.java) { p.publishedMediaFile(receipt.copy(mediaId = "staging")) }
            catalog.index["commit"] = receipt.copy(sizeBytes = receipt.sizeBytes + 1)
            assertThrows(SaveFailure::class.java) { p.publishedMediaFile(receipt) }
        } finally { root.deleteRecursively() }
    }
    @Test fun previewRejectsChangedLengthSymlinkAndDiscardedOwnership() {
        val root = root(); val catalog = Catalog()
        try {
            val p = PrivateCommitProtocol(root, catalog, Ops)
            val a = p.prepare("task", "commit", context); p.stagingFile(a).writeBytes(data)
            val receipt = p.commit(a, evidence(a), context); catalog.visible["commit"] = receipt
            val file = p.publishedMediaFile(receipt)
            file.writeBytes(data.copyOf(8))
            assertThrows(SaveFailure::class.java) { p.publishedMediaFile(receipt) }
            file.writeBytes(data)
            val other = File(root, "unrelated").apply { writeBytes(data) }
            file.delete(); Files.createSymbolicLink(file.toPath(), other.toPath())
            assertThrows(SaveFailure::class.java) { p.publishedMediaFile(receipt) }
            assertArrayEquals(data, other.readBytes())
            file.delete(); file.writeBytes(data)
            catalog.rows["commit"] = catalog.rows.getValue("commit").copy(state = OwnedState.DISCARDED)
            assertThrows(SaveFailure::class.java) { p.publishedMediaFile(receipt) }
        } finally { root.deleteRecursively() }
    }
    private class PowerLoss : Error()
    private class Catalog : OwnedMediaCatalog {
        val rows = mutableMapOf<String, OwnedMedia>()
        val index = mutableMapOf<String, MediaCommitReceipt>()
        val visible = mutableMapOf<String, MediaCommitReceipt>()
        override fun findOwned(commitId: String) = rows[commitId]
        override fun createOwned(row: OwnedMedia) = rows.getOrPut(row.artifact.commitId) { row }
        override fun persistIntent(row: OwnedMedia, evidence: VerificationEvidence): OwnedMedia = row.copy(
            state = OwnedState.INTENT, evidence = evidence).also { rows[row.artifact.commitId] = it }
        override fun indexProvisional(row: OwnedMedia) {
            val receipt = row.receipt(); val old = index.putIfAbsent(row.artifact.commitId, receipt)
            check(old == null || old == receipt)
            rows[row.artifact.commitId] = row.copy(state = OwnedState.INDEXED)
        }
        override fun indexed(commitId: String) = index[commitId]
        override fun published(commitId: String) = visible[commitId]
        override fun discard(row: OwnedMedia) { rows[row.artifact.commitId] = row.copy(state = OwnedState.DISCARDED) }
    }
    private object Ops : DurableFileOps {
        override fun syncDirectory(directory: File) { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
        override fun promoteNoReplace(source: File, destination: File) {
            check(!Files.exists(destination.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        override fun replaceOwnedStaging(source: File, destination: File) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
