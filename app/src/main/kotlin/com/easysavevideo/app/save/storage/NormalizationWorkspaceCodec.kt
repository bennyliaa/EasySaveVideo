package com.easysavevideo.app.save.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

internal const val NORMALIZATION_MAX_BYTES = 2L * 1024 * 1024 * 1024
internal fun normalizationId(value: String) { require(UUID.fromString(value).toString() == value) }
internal fun normalizationDigest(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
internal data class NormalizationOwner(val taskId: String, val artifactId: String, val commitId: String) {
    init { require(taskId.matches(Regex("[A-Za-z0-9_.-]{1,128}"))); normalizationId(artifactId); require(commitId.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) }
}
internal data class NormalizationKey(val owner: NormalizationOwner, val normalizationId: String) {
    init { normalizationId(normalizationId) }
}
internal data class NormalizationFingerprint(val size: Long, val sha256: String) {
    init { require(size in 0..NORMALIZATION_MAX_BYTES); normalizationDigest(sha256) }
}
internal enum class NormalizationLifecycle { RAW_COMPLETE, WRITING, OUTPUT_READY, ADOPT_INTENT, ADOPTED, CLEANUP_PENDING, CLEANED, RECOVERY_REQUIRED }
internal enum class NormalizationDisposition { CONTINUE, DISCARD_ONLY }
internal enum class NormalizationFailure { IO_UNCERTAIN, OWNERSHIP_CONFLICT, AUTHORITY_UNREADABLE, CONTENT_MISMATCH, RELEASE_UNCONFIRMED }
/** Exact owned media role. This grants no permission outside a durable terminal cleanup plan. */
internal data class NormalizationCommitOutput(val mediaId: String, val fingerprint: NormalizationFingerprint) {
    init { normalizationId(mediaId); require(fingerprint.size > 0) }
}
internal data class NormalizationCleanupIdentity(val device: Long, val inode: Long) {
    init { require(device >= 0 && inode > 0) }
}
internal data class NormalizationCleanupIdentities(
    val primary: NormalizationCleanupIdentity?, val raw: NormalizationCleanupIdentity?,
    val output: NormalizationCleanupIdentity?, val commitOutput: NormalizationCleanupIdentity?,
)
internal data class NormalizationCleanupPlan(
    val primary: NormalizationFingerprint?, val raw: NormalizationFingerprint?, val output: NormalizationFingerprint?,
    val commitOutput: NormalizationCommitOutput? = null,
    val identities: NormalizationCleanupIdentities? = null,
) {
    init {
        if (commitOutput != null) require(primary == null && output == null)
        identities?.let {
            require((primary == null) == (it.primary == null) && (raw == null) == (it.raw == null) &&
                (output == null) == (it.output == null) && (commitOutput == null) == (it.commitOutput == null))
        }
    }
}
/** File lifecycle only. Task state, source transfer, semantic proof and publication remain in Save. */
internal data class NormalizationWorkspace(
    val key: NormalizationKey,
    val revision: Long,
    val lifecycle: NormalizationLifecycle,
    val disposition: NormalizationDisposition,
    val raw: NormalizationFingerprint,
    val sourceTransferSha256: String,
    val output: NormalizationFingerprint? = null,
    val proofSha256: String? = null,
    val cleanup: NormalizationCleanupPlan? = null,
    val failure: NormalizationFailure? = null,
) {
    init {
        require(revision > 0 && raw.size > 0); normalizationDigest(sourceTransferSha256)
        require((output == null) == (proofSha256 == null)); proofSha256?.let(::normalizationDigest)
        require(output == null || output.size > 0)
        if (lifecycle in setOf(NormalizationLifecycle.OUTPUT_READY, NormalizationLifecycle.ADOPT_INTENT, NormalizationLifecycle.ADOPTED)) require(output != null)
        require((lifecycle in setOf(NormalizationLifecycle.CLEANUP_PENDING, NormalizationLifecycle.CLEANED)) == (cleanup != null))
        if (lifecycle in setOf(NormalizationLifecycle.RAW_COMPLETE, NormalizationLifecycle.WRITING)) require(output == null)
        cleanup?.let {
            require(it.raw == null || it.raw == raw)
            require(it.primary == null || it.primary == raw || it.primary == output)
            if (output != null) require(it.output == null || it.output == output)
            it.commitOutput?.let { committed ->
                require(disposition == NormalizationDisposition.DISCARD_ONLY && output != null && committed.fingerprint == output)
            }
        }
    }
    fun requireSuccessor(next: NormalizationWorkspace) {
        require(revision < Long.MAX_VALUE && next.key == key && next.revision == revision + 1)
        require(next.raw == raw && next.sourceTransferSha256 == sourceTransferSha256)
        require(lifecycle != NormalizationLifecycle.CLEANED)
        if (output != null) require(next.output == output && next.proofSha256 == proofSha256)
        if (cleanup != null) require(next.cleanup == cleanup)
        if (disposition == NormalizationDisposition.DISCARD_ONLY) require(next.disposition == disposition)
        val permitted = when (lifecycle) {
            NormalizationLifecycle.RAW_COMPLETE -> setOf(lifecycle, NormalizationLifecycle.WRITING)
            NormalizationLifecycle.WRITING -> setOf(lifecycle, NormalizationLifecycle.OUTPUT_READY)
            NormalizationLifecycle.OUTPUT_READY -> setOf(lifecycle, NormalizationLifecycle.ADOPT_INTENT)
            NormalizationLifecycle.ADOPT_INTENT -> setOf(lifecycle, NormalizationLifecycle.ADOPTED)
            NormalizationLifecycle.ADOPTED -> setOf(lifecycle)
            NormalizationLifecycle.CLEANUP_PENDING -> setOf(lifecycle, NormalizationLifecycle.CLEANED)
            NormalizationLifecycle.RECOVERY_REQUIRED -> setOf(lifecycle)
            NormalizationLifecycle.CLEANED -> emptySet()
        }
        require(next.lifecycle in permitted || (cleanup == null && next.lifecycle in setOf(NormalizationLifecycle.CLEANUP_PENDING, NormalizationLifecycle.RECOVERY_REQUIRED)))
    }
}

/** Small bounded binary record; no paths, filenames, URLs, sample arrays or unbounded strings. */
internal object NormalizationWorkspaceCodec {
    const val MAX_BYTES = 2048
    private const val MAGIC = 0x45534e57
    fun encode(row: NormalizationWorkspace): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { o ->
            o.writeInt(MAGIC); o.writeInt(2)
            with(row.key) { o.writeUTF(owner.taskId); o.writeUTF(owner.artifactId); o.writeUTF(owner.commitId); o.writeUTF(normalizationId) }
            o.writeLong(row.revision); o.writeUTF(row.lifecycle.name); o.writeUTF(row.disposition.name)
            o.fingerprint(row.raw); o.writeUTF(row.sourceTransferSha256)
            o.optional(row.output) { o.fingerprint(it) }; o.optional(row.proofSha256) { o.writeUTF(it) }
            o.optional(row.cleanup) { o.optional(it.primary) { f -> o.fingerprint(f) }; o.optional(it.raw) { f -> o.fingerprint(f) }; o.optional(it.output) { f -> o.fingerprint(f) }; o.optional(it.commitOutput) { c -> o.writeUTF(c.mediaId); o.fingerprint(c.fingerprint) }
                o.optional(it.identities) { ids ->
                    for (id in listOf(ids.primary, ids.raw, ids.output, ids.commitOutput)) o.optional(id) { v -> o.writeLong(v.device); o.writeLong(v.inode) }
                }
            }
            o.optional(row.failure) { o.writeUTF(it.name) }
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES) }
    fun decode(bytes: ByteArray): NormalizationWorkspace {
        require(bytes.size in 8..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readInt() == MAGIC); val version = i.readInt(); require(version in 1..2)
            val key = NormalizationKey(NormalizationOwner(i.readUTF(), i.readUTF(), i.readUTF()), i.readUTF())
            NormalizationWorkspace(key, i.readLong(), NormalizationLifecycle.valueOf(i.readUTF()), NormalizationDisposition.valueOf(i.readUTF()),
                i.fingerprint(), i.readUTF(), i.optional { i.fingerprint() }, i.optional { i.readUTF() },
                i.optional { NormalizationCleanupPlan(i.optional { i.fingerprint() }, i.optional { i.fingerprint() }, i.optional { i.fingerprint() },
                    if (version >= 2) i.optional { NormalizationCommitOutput(i.readUTF(), i.fingerprint()) } else null,
                    if (version >= 2) i.optional { NormalizationCleanupIdentities(i.identity(), i.identity(), i.identity(), i.identity()) } else null) },
                i.optional { NormalizationFailure.valueOf(i.readUTF()) }).also { require(i.available() == 0) }
        }
    }
    private fun DataOutputStream.fingerprint(f: NormalizationFingerprint) { writeLong(f.size); writeUTF(f.sha256) }
    private fun DataInputStream.identity(): NormalizationCleanupIdentity? = optional { NormalizationCleanupIdentity(readLong(), readLong()) }
    private fun DataInputStream.fingerprint() = NormalizationFingerprint(readLong(), readUTF())
    private inline fun <T> DataOutputStream.optional(value: T?, write: (T) -> Unit) { writeBoolean(value != null); if (value != null) write(value) }
    private inline fun <T> DataInputStream.optional(read: () -> T): T? = if (readBoolean()) read() else null
}
