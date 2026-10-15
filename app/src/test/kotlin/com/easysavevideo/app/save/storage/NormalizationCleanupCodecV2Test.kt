package com.easysavevideo.app.save.storage

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class NormalizationCleanupCodecV2Test {
    private val raw = NormalizationFingerprint(NORMALIZATION_MAX_BYTES, "a".repeat(64))
    private val canonical = NormalizationFingerprint(NORMALIZATION_MAX_BYTES, "b".repeat(64))
    private val key = NormalizationKey(NormalizationOwner("t".repeat(128), "11111111-1111-4111-8111-111111111111", "c".repeat(128)),
        "22222222-2222-4222-8222-222222222222")
    private val final = NormalizationCommitOutput("33333333-3333-4333-8333-333333333333", canonical)
    private fun row(plan: NormalizationCleanupPlan) = NormalizationWorkspace(key, Long.MAX_VALUE,
        NormalizationLifecycle.CLEANUP_PENDING, NormalizationDisposition.DISCARD_ONLY, raw, "c".repeat(64), canonical,
        "d".repeat(64), plan, NormalizationFailure.RELEASE_UNCONFIRMED)

    @Test fun maximumFieldV2PlansRemainWithinExistingSqlBlobLimitAndRoundTripExactly() {
        val id = NormalizationCleanupIdentity(Long.MAX_VALUE, Long.MAX_VALUE)
        val plans = listOf(
            NormalizationCleanupPlan(canonical, raw, canonical, identities = NormalizationCleanupIdentities(id, id.copy(inode = 2), id.copy(inode = 3), null)),
            NormalizationCleanupPlan(null, raw, null, final, NormalizationCleanupIdentities(null, id, null, id.copy(inode = 2))),
        )
        for (plan in plans) {
            val value = row(plan); val bytes = NormalizationWorkspaceCodec.encode(value)
            assertEquals(2, bytes[7].toInt()); assertTrue(bytes.size <= 2048)
            assertEquals(value, NormalizationWorkspaceCodec.decode(bytes))
            assertThrows(IllegalArgumentException::class.java) { NormalizationWorkspaceCodec.decode(bytes + byteArrayOf(0)) }
            for (length in bytes.indices) assertThrows(Exception::class.java) { NormalizationWorkspaceCodec.decode(bytes.copyOf(length)) }
        }
    }

    @Test fun authenticVersionOnePlansDecodeWithoutInventingFinalOrInodeAuthority() {
        val expected = row(NormalizationCleanupPlan(canonical, raw, null))
        val bytes = legacy(expected)
        val actual = NormalizationWorkspaceCodec.decode(bytes)
        assertEquals(expected, actual); assertNull(actual.cleanup!!.commitOutput); assertNull(actual.cleanup.identities)
        val rewritten = NormalizationWorkspaceCodec.decode(NormalizationWorkspaceCodec.encode(actual))
        assertEquals(actual, rewritten); assertNull(rewritten.cleanup!!.commitOutput); assertNull(rewritten.cleanup.identities)
    }

    @Test fun finalRoleCannotBeCombinedWithPrimaryOutputOrNonDiscardDisposition() {
        assertThrows(IllegalArgumentException::class.java) { NormalizationCleanupPlan(canonical, raw, null, final) }
        assertThrows(IllegalArgumentException::class.java) { NormalizationCleanupPlan(null, raw, canonical, final) }
        assertThrows(IllegalArgumentException::class.java) { NormalizationCommitOutput("../media", canonical) }
        assertThrows(IllegalArgumentException::class.java) { NormalizationCommitOutput(final.mediaId, raw.copy(size = 0)) }
        assertThrows(IllegalArgumentException::class.java) { row(NormalizationCleanupPlan(null, raw, null, final)).copy(disposition = NormalizationDisposition.CONTINUE) }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizationCleanupPlan(null, raw, null, final, NormalizationCleanupIdentities(null, null, null, NormalizationCleanupIdentity(1, 1)))
        }
    }

    @Test fun durablePlanAndItsIdentitiesAreImmutableAcrossSuccessors() {
        val plan = NormalizationCleanupPlan(null, raw, null, final,
            NormalizationCleanupIdentities(null, NormalizationCleanupIdentity(1, 1), null, NormalizationCleanupIdentity(1, 2)))
        val previous = row(plan).copy(revision = 10)
        previous.requireSuccessor(previous.copy(revision = 11, lifecycle = NormalizationLifecycle.CLEANED))
        assertThrows(IllegalArgumentException::class.java) {
            previous.requireSuccessor(previous.copy(revision = 11, cleanup = plan.copy(identities = plan.identities!!.copy(commitOutput = NormalizationCleanupIdentity(1, 3)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            previous.requireSuccessor(previous.copy(revision = 11, cleanup = plan.copy(commitOutput = final.copy(mediaId = key.normalizationId))))
        }
    }

    /** Independent v1 writer, deliberately has neither new extension byte nor current codec dependency. */
    private fun legacy(row: NormalizationWorkspace): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { o ->
            fun fingerprint(f: NormalizationFingerprint) { o.writeLong(f.size); o.writeUTF(f.sha256) }
            fun optional(f: NormalizationFingerprint?) { o.writeBoolean(f != null); f?.let(::fingerprint) }
            o.writeInt(0x45534e57); o.writeInt(1)
            o.writeUTF(row.key.owner.taskId); o.writeUTF(row.key.owner.artifactId); o.writeUTF(row.key.owner.commitId); o.writeUTF(row.key.normalizationId)
            o.writeLong(row.revision); o.writeUTF(row.lifecycle.name); o.writeUTF(row.disposition.name)
            fingerprint(row.raw); o.writeUTF(row.sourceTransferSha256); optional(row.output)
            o.writeBoolean(row.proofSha256 != null); row.proofSha256?.let(o::writeUTF)
            o.writeBoolean(row.cleanup != null); row.cleanup?.let { optional(it.primary); optional(it.raw); optional(it.output) }
            o.writeBoolean(row.failure != null); row.failure?.let { o.writeUTF(it.name) }
        }
    }.toByteArray()
}
