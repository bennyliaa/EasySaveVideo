package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.OperationStopReason
import com.easysavevideo.save.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

internal enum class OwnedState { STAGING, INTENT, INDEXED, DISCARDED }
internal data class OwnedMedia(
    val taskId: String,
    val artifact: StagingArtifact,
    val mediaId: String,
    val state: OwnedState,
    val evidence: VerificationEvidence? = null,
) {
    fun receipt(): MediaCommitReceipt {
        val e = evidence ?: throw SaveFailure(SaveError.COMMIT_MISMATCH)
        return MediaCommitReceipt(artifact.commitId, artifact.artifactId, mediaId, e.sizeBytes, e.sha256)
    }
}
/** The Android implementation uses one SQLite database, separate from all M2 data. */
internal interface OwnedMediaCatalog {
    fun findOwned(commitId: String): OwnedMedia?
    fun createOwned(row: OwnedMedia): OwnedMedia
    fun persistIntent(row: OwnedMedia, evidence: VerificationEvidence): OwnedMedia
    fun indexProvisional(row: OwnedMedia)
    fun indexed(commitId: String): MediaCommitReceipt?
    /** Exact visible receipt backed by the same successful task, never a provisional index. */
    fun published(commitId: String): MediaCommitReceipt? = null
    fun discard(row: OwnedMedia)
}
internal interface DurableFileOps {
    fun syncDirectory(directory: File)
    /** Must fail if destination exists; source and destination must share a filesystem. */
    fun promoteNoReplace(source: File, destination: File)
    /** Only used to replace this task's owned staging inode, never a committed media file. */
    fun replaceOwnedStaging(source: File, destination: File)
}
internal enum class CommitWindow { STAGING_CREATED, FILE_SYNCED, INTENT_DURABLE, PROMOTED, DIRECTORY_SYNCED, STAGING_DIRECTORY_SYNCED, INDEX_DURABLE, RESTART_CREATED, RESTART_SYNCED, RESTART_RENAMED }
internal fun interface CommitFault { fun after(window: CommitWindow) }

/**
 * Restart-safe FS/SQL protocol. A media name is random and private, never supplied by a server.
 * Atomic publication uses same-filesystem rename under the adapter's exclusive directory-owner lock.
 * A random final name is checked absent before rename. SQL intent precedes publication; SQL index follows it.
 * The catalog index remains invisible until the task journal's SUCCEEDED CAS publishes both together.
 */
internal class PrivateCommitProtocol(
    root: File,
    private val catalog: OwnedMediaCatalog,
    private val files: DurableFileOps,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val fault: CommitFault = CommitFault {},
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {
    private val root = checkedDirectory(root)
    private val staging = checkedDirectory(File(this.root, "staging"))
    private val media = checkedDirectory(File(this.root, "media"))
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    init { files.syncDirectory(this.root) }

    fun prepare(taskId: String, commitId: String, context: OperationContext): StagingArtifact {
        checkContext(context)
        var row = catalog.findOwned(commitId)
        if (row == null) {
            if (availableBytes() <= 0) throw SaveFailure(SaveError.STORAGE_FULL)
            row = catalog.createOwned(OwnedMedia(taskId, StagingArtifact(UUID.randomUUID().toString(), commitId),
                UUID.randomUUID().toString(), OwnedState.STAGING))
        }
        if (row.taskId != taskId || row.state != OwnedState.STAGING) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        val path = stagePath(row)
        if (!path.exists()) {
            if (!path.createNewFile()) throw SaveFailure(SaveError.STORAGE_FAILURE)
            FileOutputStream(path, true).use { it.fd.sync() }
            files.syncDirectory(staging)
            fault.after(CommitWindow.STAGING_CREATED)
        }
        checkedRegular(path)
        return row.artifact
    }
    fun stagingFile(artifact: StagingArtifact): File {
        val row = owned(artifact)
        if (row.state != OwnedState.STAGING) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        return stagePath(row).also(::checkedRegular)
    }
    fun verificationFile(artifact: StagingArtifact): File {
        val row = owned(artifact)
        if (row.state == OwnedState.DISCARDED) throw SaveFailure(SaveError.COMMIT_MISMATCH)
        val path = if (row.state == OwnedState.STAGING) stagePath(row)
            else mediaPath(row).takeIf { it.exists() } ?: stagePath(row)
        return path.also(::checkedRegular)
    }
    fun availableBytes(): Long = (freeSpace(root) - JOURNAL_RESERVE_BYTES).coerceAtLeast(0)
    fun restartStaging(artifact: StagingArtifact): File {
        val current = stagingFile(artifact)
        val replacement = child(staging, artifact.artifactId, ".restart")
        // This exact sibling is a reserved slot in the artifact ownership contract, never server-controlled.
        // Before rename it must be an empty regular file. Reuse only that recoverable crash state.
        if (!replacement.exists()) {
            if (!replacement.createNewFile()) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            fault.after(CommitWindow.RESTART_CREATED)
        }
        checkedRegular(replacement)
        if (replacement.length() != 0L) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        FileOutputStream(replacement, true).use { it.fd.sync() }
        fault.after(CommitWindow.RESTART_SYNCED)
        files.replaceOwnedStaging(replacement, current)
        fault.after(CommitWindow.RESTART_RENAMED)
        files.syncDirectory(staging)
        return current.also(::checkedRegular)
    }
    /** Private scratch namespace is derived only from the durable artifact ownership row. */
    fun streamDirectory(artifact: StagingArtifact): File {
        stagingFile(artifact)
        return child(staging, artifact.artifactId, ".hls").also {
            checkedDirectory(it)
            files.syncDirectory(staging)
        }
    }
    fun adoptStreamOutput(artifact: StagingArtifact, output: File, context: OperationContext) {
        checkContext(context)
        val destination = stagingFile(artifact)
        val parent = streamDirectory(artifact)
        if (output.name != "output.mp4" || output.canonicalFile.parentFile != parent.canonicalFile)
            throw SaveFailure(SaveError.COMMIT_MISMATCH)
        checkedRegular(output)
        if (output.length() <= 0) throw SaveFailure(SaveError.INVALID_MEDIA)
        FileOutputStream(output, true).use { it.fd.sync() }
        checkContext(context)
        files.replaceOwnedStaging(output, destination)
        files.syncDirectory(parent); files.syncDirectory(staging)
    }
    fun cleanupStream(artifact: StagingArtifact) {
        owned(artifact)
        val directory = child(staging, artifact.artifactId, ".hls")
        if (!directory.exists()) return
        if (!directory.isDirectory) throw SaveFailure(SaveError.STORAGE_FAILURE)
        val children = directory.listFiles() ?: throw SaveFailure(SaveError.STORAGE_FAILURE)
        if (children.size > 10_001) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        // Never recursively follow/delete unknown files or names supplied by remote media.
        for (file in children) {
            if (file.name != "output.mp4" && !Regex("[0-9a-f-]{36}\\.ts").matches(file.name))
                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            if (file.canonicalFile.parentFile != directory.canonicalFile) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            checkedRegular(file)
        }
        for (file in children) if (!file.delete()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        if (!directory.delete()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        files.syncDirectory(staging)
    }
    fun syncStaging(artifact: StagingArtifact) {
        FileOutputStream(stagingFile(artifact), true).use { it.fd.sync() }
    }
    fun commit(artifact: StagingArtifact, evidence: VerificationEvidence, context: OperationContext): MediaCommitReceipt {
        checkContext(context)
        var row = owned(artifact)
        var sourceVerified = false
        if (row.state == OwnedState.DISCARDED) throw SaveFailure(SaveError.COMMIT_MISMATCH)
        if (evidence.artifactId != artifact.artifactId || evidence.sizeBytes <= 0 ||
            !evidence.sha256.matches(Regex("[a-f0-9]{64}"))) throw SaveFailure(SaveError.COMMIT_MISMATCH)
        if (context.resourceBudget.maxBytes?.let { evidence.sizeBytes > it } == true)
            throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
        if (row.evidence != null && row.evidence != evidence) throw SaveFailure(SaveError.COMMIT_MISMATCH)
        if (row.state == OwnedState.STAGING) {
            val source = stagePath(row).also(::checkedRegular)
            if (!contentMatches(source, evidence.sizeBytes, evidence.sha256, context)) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            sourceVerified = true
            FileOutputStream(source, true).use { it.fd.sync() }
            fault.after(CommitWindow.FILE_SYNCED)
            checkContext(context)
            if (availableBytes() <= 0) throw SaveFailure(SaveError.STORAGE_FULL)
            row = catalog.persistIntent(row, evidence)
            fault.after(CommitWindow.INTENT_DURABLE)
        }
        return reconcile(row, context, sourceAlreadyVerified = sourceVerified) ?: throw SaveFailure(SaveError.COMMIT_MISMATCH)
    }
    fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? {
        checkContext(context)
        val row = catalog.findOwned(commitId) ?: return null
        if (row.state != OwnedState.INTENT && row.state != OwnedState.INDEXED) return null
        val receipt = reconcile(row, context) ?: return null
        // Fresh re-read and fresh digest; neither the intent nor a cached success is proof.
        val indexed = catalog.indexed(commitId)
        val target = mediaPath(row)
        val present = isRegular(target)
        val matches = present && target.length() == receipt.sizeBytes // reconcile just computed a fresh full digest.
        return CommitInspection(receipt, present, indexed == receipt, matches)
    }
    private fun reconcile(row: OwnedMedia, context: OperationContext, sourceAlreadyVerified: Boolean = false): MediaCommitReceipt? {
        val receipt = row.receipt()
        val source = stagePath(row); val target = mediaPath(row)
        checkContext(context)
        var promotedVerifiedSource = false
        if (!target.exists()) {
            if (!isRegular(source) || (!sourceAlreadyVerified && !contentMatches(source, receipt.sizeBytes, receipt.sha256, context))) return null
            checkContext(context)
            files.promoteNoReplace(source, target) // Sole directory owner; EEXIST/EXDEV fail closed, no copy fallback.
            promotedVerifiedSource = true
            fault.after(CommitWindow.PROMOTED)
            files.syncDirectory(media)
            fault.after(CommitWindow.DIRECTORY_SYNCED)
            files.syncDirectory(staging)
            fault.after(CommitWindow.STAGING_DIRECTORY_SYNCED)
        }
        checkedRegular(target)
        if (!promotedVerifiedSource && !contentMatches(target, receipt.sizeBytes, receipt.sha256, context)) throw SaveFailure(SaveError.COMMIT_MISMATCH)
        files.syncDirectory(media)
        if (source.exists()) {
            checkedRegular(source)
            // Another inode at the staging path is not ours to remove, even when the digest happens to match.
            if (!Files.isSameFile(source.toPath(), target.toPath())) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            if (!source.delete()) throw SaveFailure(SaveError.STORAGE_FAILURE)
            files.syncDirectory(staging)
            fault.after(CommitWindow.STAGING_DIRECTORY_SYNCED)
        }
        catalog.indexProvisional(row)
        fault.after(CommitWindow.INDEX_DURABLE)
        return receipt
    }
    fun cleanup(artifact: StagingArtifact, committed: Boolean) {
        val row = catalog.findOwned(artifact.commitId) ?: return
        if (row.artifact != artifact) return
        // Once intent is durable, a crash may already have promoted the final name. Preserve all material.
        if (row.state != OwnedState.STAGING || committed) return
        val source = stagePath(row)
        val replacement = child(staging, row.artifact.artifactId, ".restart")
        if (replacement.exists()) {
            checkedRegular(replacement)
            if (replacement.length() != 0L) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        }
        if (source.exists()) {
            checkedRegular(source)
            if (!source.delete()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        }
        if (replacement.exists() && !replacement.delete()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        files.syncDirectory(staging)
        catalog.discard(row)
    }
    internal fun mediaFile(receipt: MediaCommitReceipt): File {
        val row = catalog.findOwned(receipt.commitId) ?: throw SaveFailure(SaveError.COMMIT_MISMATCH)
        if (row.receipt() != receipt) throw SaveFailure(SaveError.COMMIT_MISMATCH)
        return mediaPath(row).also(::checkedRegular)
    }
    /** Read-only preview entry point. It cannot resolve staging, intent, or an unpublished index. */
    fun publishedMediaFile(receipt: MediaCommitReceipt): File {
        val row = catalog.findOwned(receipt.commitId) ?: throw SaveFailure(SaveError.COMMIT_MISMATCH)
        if (row.state != OwnedState.INDEXED || row.receipt() != receipt ||
            catalog.indexed(receipt.commitId) != receipt || catalog.published(receipt.commitId) != receipt)
            throw SaveFailure(SaveError.COMMIT_MISMATCH)
        return mediaPath(row).also {
            checkedRegular(it)
            if (it.length() != receipt.sizeBytes) throw SaveFailure(SaveError.COMMIT_MISMATCH)
        }
    }
    private fun owned(artifact: StagingArtifact): OwnedMedia = catalog.findOwned(artifact.commitId)?.also {
        if (it.artifact != artifact) throw SaveFailure(SaveError.COMMIT_MISMATCH)
    } ?: throw SaveFailure(SaveError.COMMIT_MISMATCH)
    private fun stagePath(row: OwnedMedia) = child(staging, row.artifact.artifactId, ".part")
    private fun mediaPath(row: OwnedMedia) = child(media, row.mediaId, ".mp4")
    private fun child(parent: File, id: String, suffix: String): File {
        if (!uuid.matches(id)) throw SaveFailure(SaveError.COMMIT_MISMATCH)
        val file = File(parent, id + suffix)
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != parent.canonicalFile)
            throw SaveFailure(SaveError.COMMIT_MISMATCH)
        return file
    }
    private fun contentMatches(file: File, bytes: Long, sha: String, context: OperationContext): Boolean {
        checkedRegular(file)
        if (file.length() != bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkContext(context)
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("NO_PROGRESS")
                total += count
                if (total > bytes) return false
                digest.update(buffer, 0, count)
            }
        }
        checkContext(context)
        return total == bytes && digest.digest().joinToString("") { "%02x".format(it) } == sha
    }
    private fun checkContext(context: OperationContext) {
        when (context.stopReason(nowMillis())) {
            OperationStopReason.CANCELLED -> throw SaveFailure(SaveError.CANCELLED)
            OperationStopReason.DEADLINE_EXCEEDED -> throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
            OperationStopReason.RETRY_EXHAUSTED -> throw SaveFailure(SaveError.RETRY_EXHAUSTED)
            null -> Unit
        }
    }
    private fun isRegular(file: File) = !Files.isSymbolicLink(file.toPath()) && file.isFile
    private fun checkedRegular(file: File) { if (!isRegular(file)) throw SaveFailure(SaveError.COMMIT_MISMATCH) }
    private fun checkedDirectory(file: File): File {
        if (Files.isSymbolicLink(file.toPath())) throw SaveFailure(SaveError.STORAGE_FAILURE)
        if (!file.isDirectory && !file.mkdirs()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        if (!file.isDirectory) throw SaveFailure(SaveError.STORAGE_FAILURE)
        return file.canonicalFile
    }
    companion object { const val JOURNAL_RESERVE_BYTES = 1024L * 1024 }
}
