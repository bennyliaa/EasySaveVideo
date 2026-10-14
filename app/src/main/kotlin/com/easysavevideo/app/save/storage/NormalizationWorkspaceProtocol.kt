package com.easysavevideo.app.save.storage

import com.easysavevideo.save.DirectNormalizationProof
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal sealed interface NormalizationLookup {
    data object Absent : NormalizationLookup
    data class Present(val row: NormalizationWorkspace) : NormalizationLookup
    data object Unreadable : NormalizationLookup
}
internal data class NormalizationPendingEntry(val cursor: Long, val row: NormalizationWorkspace)
internal sealed interface NormalizationPendingPage {
    data class Readable(val entries: List<NormalizationPendingEntry>, val nextCursor: Long?) : NormalizationPendingPage
    data object Unreadable : NormalizationPendingPage
}
internal interface NormalizationLedger {
    /** Includes a historical CLEANED row; unreadable/mixed generations are never absence. */
    fun lookup(owner: NormalizationOwner): NormalizationLookup
    fun insert(row: NormalizationWorkspace): Boolean
    fun compareAndSet(expected: NormalizationWorkspace, next: NormalizationWorkspace): Boolean
    fun pending(after: Long = 0, limit: Int = 64): NormalizationPendingPage
}
internal enum class NormalizationTaskState { ACTIVE, FAILED, CANCELLED, SUCCEEDED }
internal enum class NormalizationParentState { STAGING, COMMIT_PENDING, PUBLISHED, DISCARDED }
internal data class NormalizationAuthority(
    val owner: NormalizationOwner,
    val taskState: NormalizationTaskState,
    val parentState: NormalizationParentState,
    val sourceTransferSha256: String?,
    val proofSha256: String?,
    val publishedOutput: NormalizationFingerprint? = null,
)
internal enum class NormalizationRole { PRIMARY, RAW, OUTPUT }
internal enum class NormalizationDirectory { STAGING, WORKSPACE, GENERATION }
internal data class NormalizationFilesSnapshot(
    val workspaceExists: Boolean, val generationExists: Boolean,
    val primary: NormalizationFingerprint?, val raw: NormalizationFingerprint?, val output: NormalizationFingerprint?,
) {
    operator fun get(role: NormalizationRole) = when (role) { NormalizationRole.PRIMARY -> primary; NormalizationRole.RAW -> raw; NormalizationRole.OUTPUT -> output }
}
/** No caller path enters this port. Every operation must check exact directory/file ownership and released handles. */
internal interface NormalizationFileOps {
    fun requireReleased(key: NormalizationKey)
    fun availableBytes(): Long
    fun snapshot(key: NormalizationKey): NormalizationFilesSnapshot
    fun createDirectory(key: NormalizationKey, directory: NormalizationDirectory)
    fun createEmptyOutput(key: NormalizationKey)
    fun syncOutput(key: NormalizationKey)
    fun syncDirectory(key: NormalizationKey, directory: NormalizationDirectory)
    fun moveNoReplace(key: NormalizationKey, source: NormalizationRole, destination: NormalizationRole, expected: NormalizationFingerprint)
    fun deleteExact(key: NormalizationKey, role: NormalizationRole, expected: NormalizationFingerprint)
    fun removeEmptyDirectory(key: NormalizationKey, directory: NormalizationDirectory)
}
internal enum class NormalizationLayout { RAW_ONLY, WRITING, READY, RAW_MOVED, ADOPTED, COMMIT_OWNED, CLEANUP_PENDING, CLEANED }
internal sealed interface NormalizationInspection {
    data object Absent : NormalizationInspection
    data class Present(val row: NormalizationWorkspace, val layout: NormalizationLayout, val discardOnly: Boolean) : NormalizationInspection
    data object Unreadable : NormalizationInspection
}
internal class NormalizationRecovery : IllegalStateException("NORMALIZATION_RECOVERY_REQUIRED")

/**
 * Not wired to production MOV admission. Explicit callers own the local execution claim/lane.
 * The owner lock must be the same lock used by private commit when an Android file adapter is installed.
 * Reads are passive; no resume, network, publication or generic recursive cleanup is hidden here.
 */
internal class NormalizationWorkspaceProtocol(
    private val ledger: NormalizationLedger,
    private val files: NormalizationFileOps,
    private val authority: (NormalizationOwner) -> NormalizationAuthority,
    private val ownerLock: Any,
    private val checkOperation: () -> Unit = {},
) {
    fun inspect(owner: NormalizationOwner): NormalizationInspection = synchronized(ownerLock) {
        try {
            when (val lookup = ledger.lookup(owner)) {
                NormalizationLookup.Absent -> NormalizationInspection.Absent
                NormalizationLookup.Unreadable -> NormalizationInspection.Unreadable
                is NormalizationLookup.Present -> {
                    val row = lookup.row; val auth = boundAuthority(row)
                    files.requireReleased(row.key)
                    NormalizationInspection.Present(row, layout(row, files.snapshot(row.key), auth), discard(row, auth))
                }
            }
        } catch (_: Exception) { NormalizationInspection.Unreadable }
    }

    /**
     * RAW_COMPLETE is the durable creation intent, checked for collisions before insertion.
     * Existing WRITING returns status only: a nonempty candidate is preserved, never opened or
     * truncated. This slice offers terminal cleanup/new task, not a remux-restart authorization.
     */
    fun begin(key: NormalizationKey, raw: NormalizationFingerprint, sourceTransferSha256: String): NormalizationWorkspace = synchronized(ownerLock) {
        checkOperation(); files.requireReleased(key)
        val requested = NormalizationWorkspace(key, 1, NormalizationLifecycle.RAW_COMPLETE, NormalizationDisposition.CONTINUE, raw, sourceTransferSha256)
        var row = when (val found = ledger.lookup(key.owner)) {
            NormalizationLookup.Unreadable -> fail()
            is NormalizationLookup.Present -> found.row.also { if (it.key != key || it.raw != raw || it.sourceTransferSha256 != sourceTransferSha256) fail() }
            NormalizationLookup.Absent -> {
                continuing(requested)
                val snapshot = files.snapshot(key)
                if (snapshot.primary != raw || snapshot.workspaceExists || snapshot.generationExists || snapshot.raw != null || snapshot.output != null) fail()
                admission()
                try { if (!ledger.insert(requested)) fail() } catch (_: Exception) { if (current(key) != requested) fail() }
                current(key).also { if (it != requested) fail() }
            }
        }
        continuing(row)
        if (row.lifecycle == NormalizationLifecycle.WRITING) {
            if (layout(row, files.snapshot(key), boundAuthority(row)) != NormalizationLayout.WRITING) fail()
            return@synchronized row
        }
        if (row.lifecycle != NormalizationLifecycle.RAW_COMPLETE) fail()
        val snapshot = files.snapshot(key)
        if (snapshot.primary != raw || snapshot.raw != null || (snapshot.output != null && snapshot.output.size != 0L)) fail()
        admission() // Always the full 2 GiB, including retry of an interrupted empty-output creation.
        if (!snapshot.workspaceExists) { continuing(row); files.createDirectory(key, NormalizationDirectory.WORKSPACE); files.syncDirectory(key, NormalizationDirectory.STAGING) }
        if (!snapshot.generationExists) { continuing(row); files.createDirectory(key, NormalizationDirectory.GENERATION); files.syncDirectory(key, NormalizationDirectory.WORKSPACE) }
        if (snapshot.output == null) { continuing(row); files.createEmptyOutput(key) }
        files.syncOutput(key)
        // A previous mkdir may have completed before throwing. Existence is not durability.
        files.syncDirectory(key, NormalizationDirectory.GENERATION)
        files.syncDirectory(key, NormalizationDirectory.WORKSPACE)
        files.syncDirectory(key, NormalizationDirectory.STAGING)
        val final = files.snapshot(key)
        if (final.primary != raw || final.raw != null || final.output?.size != 0L) fail()
        continuing(row)
        row = persist(row, row.copy(revision = row.revision + 1, lifecycle = NormalizationLifecycle.WRITING))
        row
    }

    /** Caller closed/released the candidate. Fresh hashes and fsync precede OUTPUT_READY. */
    fun finishOutput(key: NormalizationKey, proof: DirectNormalizationProof): NormalizationWorkspace = synchronized(ownerLock) {
        var row = current(key); continuing(row); files.requireReleased(key); requireProof(row, proof)
        if (row.lifecycle != NormalizationLifecycle.WRITING) fail()
        files.syncOutput(key); files.syncDirectory(key, NormalizationDirectory.GENERATION)
        val snapshot = files.snapshot(key)
        val expected = NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256)
        if (snapshot.primary != row.raw || snapshot.raw != null || snapshot.output != expected) fail()
        continuing(row)
        row = persist(row, row.copy(revision = row.revision + 1, lifecycle = NormalizationLifecycle.OUTPUT_READY, output = expected, proofSha256 = proof.digest()))
        row
    }

    /** Save proof CAS must already be acknowledged. This method alone authorizes neither rename nor publication. */
    fun recordAdoptionIntent(key: NormalizationKey, proof: DirectNormalizationProof): NormalizationWorkspace = synchronized(ownerLock) {
        val row = current(key); continuing(row); files.requireReleased(key); requireProof(row, proof)
        if (row.output != NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) || row.proofSha256 != proof.digest()) fail()
        val auth = boundAuthority(row)
        if (auth.proofSha256 != row.proofSha256) fail()
        if (row.lifecycle == NormalizationLifecycle.ADOPT_INTENT) return@synchronized row
        if (row.lifecycle != NormalizationLifecycle.OUTPUT_READY || layout(row, files.snapshot(key), auth) != NormalizationLayout.READY) fail()
        persist(row, row.copy(revision = row.revision + 1, lifecycle = NormalizationLifecycle.ADOPT_INTENT))
    }

    /** Two separately durable no-replace moves. Exceptions are never treated as a failed/absent move. */
    fun adopt(key: NormalizationKey): NormalizationWorkspace = synchronized(ownerLock) {
        var row = current(key); continuing(row); files.requireReleased(key)
        if (row.lifecycle !in setOf(NormalizationLifecycle.ADOPT_INTENT, NormalizationLifecycle.ADOPTED)) fail()
        var state = layout(row, files.snapshot(key), boundAuthority(row))
        if (state == NormalizationLayout.READY) {
            continuing(row)
            files.moveNoReplace(key, NormalizationRole.PRIMARY, NormalizationRole.RAW, row.raw)
            files.syncDirectory(key, NormalizationDirectory.GENERATION); files.syncDirectory(key, NormalizationDirectory.STAGING)
            state = layout(row, files.snapshot(key), boundAuthority(row))
        }
        if (state == NormalizationLayout.RAW_MOVED) {
            continuing(row)
            files.moveNoReplace(key, NormalizationRole.OUTPUT, NormalizationRole.PRIMARY, requireNotNull(row.output))
            files.syncDirectory(key, NormalizationDirectory.GENERATION); files.syncDirectory(key, NormalizationDirectory.STAGING)
            state = layout(row, files.snapshot(key), boundAuthority(row))
        }
        if (state != NormalizationLayout.ADOPTED) fail()
        // A prior rename may have completed before throwing; repeat only directory durability, never the move.
        files.syncDirectory(key, NormalizationDirectory.GENERATION); files.syncDirectory(key, NormalizationDirectory.STAGING)
        continuing(row)
        if (row.lifecycle != NormalizationLifecycle.ADOPTED) row = persist(row, row.copy(revision = row.revision + 1, lifecycle = NormalizationLifecycle.ADOPTED))
        row
    }

    /** Exact temporary roles only; terminal Save/visible receipt authority is reread at every mutation. */
    fun cleanup(key: NormalizationKey, expectedRevision: Long): NormalizationWorkspace = synchronized(ownerLock) {
        var row = current(key)
        if (row.revision != expectedRevision) fail()
        files.requireReleased(key)
        if (row.lifecycle == NormalizationLifecycle.CLEANED) {
            layout(row, files.snapshot(key), boundAuthority(row))
            return@synchronized row
        }
        var auth = boundAuthority(row)
        requireTerminal(auth)
        if (row.cleanup == null) {
            val snapshot = files.snapshot(key)
            val successful = auth.taskState == NormalizationTaskState.SUCCEEDED
            if (successful) {
                if (row.disposition == NormalizationDisposition.DISCARD_ONLY || auth.parentState != NormalizationParentState.PUBLISHED || auth.publishedOutput != row.output || row.output == null || auth.proofSha256 != row.proofSha256 || snapshot.primary != null || snapshot.output != null || (snapshot.raw != null && snapshot.raw != row.raw)) fail()
            } else {
                if (auth.parentState !in setOf(NormalizationParentState.STAGING, NormalizationParentState.DISCARDED)) fail()
                // This also rejects ambiguous/duplicated source/canonical representations before recording deletion authority.
                layout(row, snapshot, auth)
            }
            val plan = NormalizationCleanupPlan(if (successful) null else snapshot.primary, snapshot.raw, snapshot.output)
            row = persist(row, row.copy(revision = row.revision + 1, lifecycle = NormalizationLifecycle.CLEANUP_PENDING,
                disposition = if (successful) row.disposition else NormalizationDisposition.DISCARD_ONLY, cleanup = plan))
        }
        for (role in NormalizationRole.entries) {
            auth = boundAuthority(row); requireTerminal(auth)
            if (auth.taskState == NormalizationTaskState.SUCCEEDED && (row.disposition == NormalizationDisposition.DISCARD_ONLY || row.cleanup!!.primary != null || row.cleanup.output != null || auth.parentState != NormalizationParentState.PUBLISHED || auth.publishedOutput != row.output)) fail()
            val expected = when (role) { NormalizationRole.PRIMARY -> row.cleanup!!.primary; NormalizationRole.RAW -> row.cleanup!!.raw; NormalizationRole.OUTPUT -> row.cleanup!!.output }
            val observed = files.snapshot(key)[role]
            if (expected == null) {
                if (observed != null) fail()
            } else if (observed != null) {
                if (observed != expected) fail()
                files.deleteExact(key, role, expected)
                files.syncDirectory(key, if (role == NormalizationRole.PRIMARY) NormalizationDirectory.STAGING else NormalizationDirectory.GENERATION)
            }
        }
        val snapshot = files.snapshot(key)
        if (snapshot.generationExists) { files.removeEmptyDirectory(key, NormalizationDirectory.GENERATION); files.syncDirectory(key, NormalizationDirectory.WORKSPACE) }
        if (snapshot.workspaceExists) { files.removeEmptyDirectory(key, NormalizationDirectory.WORKSPACE); files.syncDirectory(key, NormalizationDirectory.STAGING) }
        // An unlink/rmdir may have completed before throwing, including P-only creation intent.
        // Always restore the parent-directory durability barrier before acknowledging CLEANED.
        files.syncDirectory(key, NormalizationDirectory.STAGING)
        val cleaned = files.snapshot(key)
        if (cleaned.primary != null || cleaned.raw != null || cleaned.output != null || cleaned.workspaceExists || cleaned.generationExists) fail()
        auth = boundAuthority(row); requireTerminal(auth)
        persist(row, row.copy(revision = row.revision + 1, lifecycle = NormalizationLifecycle.CLEANED))
    }

    private fun layout(row: NormalizationWorkspace, s: NormalizationFilesSnapshot, auth: NormalizationAuthority): NormalizationLayout {
        if (row.lifecycle == NormalizationLifecycle.CLEANED) {
            if (s.workspaceExists || s.generationExists || s.primary != null || s.raw != null || s.output != null) fail()
            return NormalizationLayout.CLEANED
        }
        if (row.lifecycle == NormalizationLifecycle.CLEANUP_PENDING) {
            val plan = requireNotNull(row.cleanup)
            if ((s.primary != null && s.primary != plan.primary) || (s.raw != null && s.raw != plan.raw) || (s.output != null && s.output != plan.output)) fail()
            return NormalizationLayout.CLEANUP_PENDING
        }
        if (row.lifecycle == NormalizationLifecycle.RECOVERY_REQUIRED) fail()
        if (row.lifecycle == NormalizationLifecycle.RAW_COMPLETE && s.primary == row.raw && s.raw == null && (s.output == null || s.output.size == 0L)) return NormalizationLayout.RAW_ONLY
        if (row.lifecycle == NormalizationLifecycle.WRITING && s.primary == row.raw && s.raw == null && s.generationExists) return NormalizationLayout.WRITING
        if (row.output != null) {
            if (s.primary == row.raw && s.raw == null && s.output == row.output && row.lifecycle in setOf(NormalizationLifecycle.OUTPUT_READY, NormalizationLifecycle.ADOPT_INTENT)) return NormalizationLayout.READY
            if (row.lifecycle in setOf(NormalizationLifecycle.ADOPT_INTENT, NormalizationLifecycle.ADOPTED) && auth.proofSha256 == row.proofSha256) {
                if (s.primary == null && s.raw == row.raw && s.output == row.output && row.lifecycle == NormalizationLifecycle.ADOPT_INTENT) return NormalizationLayout.RAW_MOVED
                if (s.primary == row.output && s.raw == row.raw && s.output == null) return NormalizationLayout.ADOPTED
                if (s.primary == null && s.raw == row.raw && s.output == null && auth.parentState in setOf(NormalizationParentState.COMMIT_PENDING, NormalizationParentState.PUBLISHED)) return NormalizationLayout.COMMIT_OWNED
            }
        }
        // Existing generic failure cleanup can remove P and mark the exact parent DISCARDED.
        if (auth.parentState == NormalizationParentState.DISCARDED && discard(row, auth) && s.primary == null &&
            (s.raw == null || s.raw == row.raw) && (row.output == null || s.output == null || s.output == row.output)) return NormalizationLayout.WRITING
        fail()
    }
    private fun requireProof(row: NormalizationWorkspace, proof: DirectNormalizationProof) {
        with(row.key) { if (proof.normalizationId != normalizationId || proof.taskId != owner.taskId || proof.artifactId != owner.artifactId || proof.commitId != owner.commitId) fail() }
        if (proof.rawSizeBytes != row.raw.size || proof.rawSha256 != row.raw.sha256 || proof.sourceTransferSha256 != row.sourceTransferSha256) fail()
    }
    private fun boundAuthority(row: NormalizationWorkspace): NormalizationAuthority = authority(row.key.owner).also {
        if (it.owner != row.key.owner || it.sourceTransferSha256 != row.sourceTransferSha256) fail()
        if ((row.lifecycle in setOf(NormalizationLifecycle.ADOPT_INTENT, NormalizationLifecycle.ADOPTED) ||
            (it.taskState == NormalizationTaskState.SUCCEEDED && row.output != null)) && it.proofSha256 != row.proofSha256) fail()
    }
    private fun discard(row: NormalizationWorkspace, auth: NormalizationAuthority) = row.disposition == NormalizationDisposition.DISCARD_ONLY || auth.taskState in setOf(NormalizationTaskState.FAILED, NormalizationTaskState.CANCELLED)
    private fun continuing(row: NormalizationWorkspace) {
        checkOperation()
        val auth = boundAuthority(row)
        if (discard(row, auth) || auth.taskState != NormalizationTaskState.ACTIVE || auth.parentState != NormalizationParentState.STAGING) fail()
    }
    private fun requireTerminal(auth: NormalizationAuthority) { if (auth.taskState == NormalizationTaskState.ACTIVE) fail() }
    private fun admission() { if (files.availableBytes() < NORMALIZATION_MAX_BYTES) fail() }
    private fun current(key: NormalizationKey): NormalizationWorkspace = (ledger.lookup(key.owner) as? NormalizationLookup.Present)?.row?.takeIf { it.key == key } ?: fail()
    private fun persist(prior: NormalizationWorkspace, next: NormalizationWorkspace): NormalizationWorkspace {
        prior.requireSuccessor(next)
        try { if (!ledger.compareAndSet(prior, next)) fail() } catch (_: Exception) { if (current(prior.key) != next) fail() }
        return current(prior.key).also { if (it != next) fail() }
    }
    private fun fail(): Nothing = throw NormalizationRecovery()
}

/** Owned fixture/reference adapter. Production muxer descriptors are a later, separately reviewed adapter. */
internal class OwnedNormalizationFiles(
    private val root: File,
    private val durable: DurableFileOps,
    private val released: (NormalizationKey) -> Unit,
    private val freeBytes: () -> Long,
    private val checkOperation: () -> Unit = {},
) : NormalizationFileOps {
    override fun requireReleased(key: NormalizationKey) = released(key)
    override fun availableBytes() = freeBytes()
    private fun directory(key: NormalizationKey, role: NormalizationDirectory): File {
        val staging = File(root, "staging")
        val workspace = File(staging, key.owner.artifactId + ".normalization")
        return when (role) { NormalizationDirectory.STAGING -> staging; NormalizationDirectory.WORKSPACE -> workspace; NormalizationDirectory.GENERATION -> File(workspace, key.normalizationId) }
    }
    private fun path(key: NormalizationKey, role: NormalizationRole): File = when (role) {
        NormalizationRole.PRIMARY -> File(directory(key, NormalizationDirectory.STAGING), key.owner.artifactId + ".part")
        NormalizationRole.RAW -> File(directory(key, NormalizationDirectory.GENERATION), "raw.bin")
        NormalizationRole.OUTPUT -> File(directory(key, NormalizationDirectory.GENERATION), "output.mp4")
    }
    private fun attributes(file: File): BasicFileAttributes? = try { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: java.nio.file.NoSuchFileException) { null }
    private fun checkedDirectory(file: File, required: Boolean): Boolean {
        val attrs = attributes(file) ?: if (!required) return false else throw NormalizationRecovery()
        if (!attrs.isDirectory || attrs.isSymbolicLink) throw NormalizationRecovery()
        return true
    }
    private fun fingerprint(file: File): NormalizationFingerprint? {
        val before = attributes(file) ?: return null
        if (!before.isRegularFile || before.isSymbolicLink || before.size() !in 0..NORMALIZATION_MAX_BYTES ||
            (Files.getAttribute(file.toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() != 1L) throw NormalizationRecovery()
        val hash = MessageDigest.getInstance("SHA-256"); var count = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { checkOperation(); val n = input.read(buffer); if (n < 0) break; if (n == 0) throw NormalizationRecovery(); count += n; if (count > NORMALIZATION_MAX_BYTES || count > before.size()) throw NormalizationRecovery(); hash.update(buffer, 0, n) }
        }
        val after = attributes(file) ?: throw NormalizationRecovery()
        if (before.fileKey() == null || before.fileKey() != after.fileKey() || count != before.size() || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
            (Files.getAttribute(file.toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() != 1L) throw NormalizationRecovery()
        return NormalizationFingerprint(count, hash.digest().joinToString("") { "%02x".format(it) })
    }
    override fun snapshot(key: NormalizationKey): NormalizationFilesSnapshot {
        requireReleased(key); checkedDirectory(root, true); checkedDirectory(directory(key, NormalizationDirectory.STAGING), true)
        val workspace = directory(key, NormalizationDirectory.WORKSPACE)
        val generation = directory(key, NormalizationDirectory.GENERATION)
        val hasWorkspace = checkedDirectory(workspace, false)
        if (hasWorkspace) boundedChildren(workspace, setOf(key.normalizationId))
        val hasGeneration = hasWorkspace && checkedDirectory(generation, false)
        if (hasGeneration) boundedChildren(generation, setOf("raw.bin", "output.mp4"))
        return NormalizationFilesSnapshot(hasWorkspace, hasGeneration, fingerprint(path(key, NormalizationRole.PRIMARY)),
            if (hasGeneration) fingerprint(path(key, NormalizationRole.RAW)) else null, if (hasGeneration) fingerprint(path(key, NormalizationRole.OUTPUT)) else null)
    }
    private fun boundedChildren(dir: File, allowed: Set<String>) {
        Files.newDirectoryStream(dir.toPath()).use { entries -> var count = 0; for (child in entries) { if (++count > allowed.size || child.fileName.toString() !in allowed) throw NormalizationRecovery() } }
    }
    override fun createDirectory(key: NormalizationKey, directory: NormalizationDirectory) {
        require(directory != NormalizationDirectory.STAGING); snapshot(key); Files.createDirectory(directory(key, directory).toPath())
    }
    override fun createEmptyOutput(key: NormalizationKey) { val s = snapshot(key); if (!s.generationExists || s.output != null) throw NormalizationRecovery(); Files.createFile(path(key, NormalizationRole.OUTPUT).toPath()) }
    override fun syncOutput(key: NormalizationKey) { if (snapshot(key).output == null) throw NormalizationRecovery(); FileOutputStream(path(key, NormalizationRole.OUTPUT), true).use { it.fd.sync() } }
    override fun syncDirectory(key: NormalizationKey, directory: NormalizationDirectory) { snapshot(key); checkedDirectory(directory(key, directory), true); durable.syncDirectory(directory(key, directory)) }
    override fun moveNoReplace(key: NormalizationKey, source: NormalizationRole, destination: NormalizationRole, expected: NormalizationFingerprint) {
        require((source == NormalizationRole.PRIMARY && destination == NormalizationRole.RAW) || (source == NormalizationRole.OUTPUT && destination == NormalizationRole.PRIMARY))
        val snapshot = snapshot(key); if (snapshot[source] != expected || snapshot[destination] != null) throw NormalizationRecovery()
        durable.promoteNoReplace(path(key, source), path(key, destination))
    }
    override fun deleteExact(key: NormalizationKey, role: NormalizationRole, expected: NormalizationFingerprint) { if (snapshot(key)[role] != expected) throw NormalizationRecovery(); Files.delete(path(key, role).toPath()) }
    override fun removeEmptyDirectory(key: NormalizationKey, directory: NormalizationDirectory) {
        require(directory != NormalizationDirectory.STAGING); snapshot(key)
        Files.newDirectoryStream(directory(key, directory).toPath()).use { if (it.iterator().hasNext()) throw NormalizationRecovery() }
        Files.delete(directory(key, directory).toPath())
    }
}
