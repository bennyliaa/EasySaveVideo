package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.*
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Reviewer-owned encoder. No production policy fixture/encoder is used by these vectors. */
internal object IndependentPolicyVectors {
    const val NOW = 1_800_000_000_000L
    const val KEY = "independent-policy-root"
    private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    fun verifier() = RepositoryPolicyVerifier(mapOf(KEY to pair.public))
    fun packageVerifierWithSameRoot() = ProviderPackageVerifier(mapOf(KEY to pair.public))
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun write(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { b -> DataOutputStream(b).use(block) }.toByteArray()
    private fun DataOutputStream.token(value: String) { val b = value.toByteArray(Charsets.US_ASCII); writeInt(b.size); write(b) }
    fun payload(version: Long = 1, enabled: Int = 1, minimum: Long = 0,
        revokedVersions: List<Long> = emptyList(), revokedKeys: List<String> = emptyList(),
        target: Long? = null, digest: ByteArray = ByteArray(32),
        issued: Long = NOW - 1000, expires: Long = NOW + 600_000,
        schema: Int = 1, provider: String = CommonsIdentity.PROVIDER_ID, providerVersion: String = CommonsIdentity.VERSION,
        rollbackFlag: Int = if (target == null) 0 else 1): ByteArray = write {
        writeInt(schema); token(provider); token(providerVersion); writeLong(version); writeLong(issued); writeLong(expires)
        writeByte(enabled); writeLong(minimum); writeInt(revokedVersions.size); revokedVersions.forEach(::writeLong)
        writeInt(revokedKeys.size); revokedKeys.forEach { token(it) }; writeByte(rollbackFlag)
        if (target != null) { writeLong(target); write(digest) }
    }
    fun envelope(payload: ByteArray, magic: Int = 0x45535653, format: Int = 1): ByteArray {
        val prefix = write { writeInt(magic); writeInt(format); token(KEY); writeInt(payload.size); write(payload) }
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(prefix); sign() }
        return write { write(prefix); writeInt(signature.size); write(signature) }
    }
    fun signed(version: Long = 1, enabled: Boolean = true, minimum: Long = 0,
        revokedVersions: List<Long> = emptyList(), revokedKeys: List<String> = emptyList(),
        target: Long? = null, digest: ByteArray = ByteArray(32),
        issued: Long = NOW - 1000, expires: Long = NOW + 600_000) =
        envelope(payload(version, if (enabled) 1 else 0, minimum, revokedVersions, revokedKeys, target, digest, issued, expires))
}

class IndependentRepositoryPolicyTest {
    private val now = IndependentPolicyVectors.NOW
    private fun temp() = Files.createTempDirectory("esv-independent-policy-").toFile()
    private fun repository(directory: File = temp(), clock: EpochClock = EpochClock { now }) =
        ProviderRepository(directory, ProviderPackageFixtures.verifier(), clock, policyVerifier = IndependentPolicyVectors.verifier())
    private fun bundle() = ProviderPolicy(0, 0, Long.MAX_VALUE, true, CommonsLimits())
    private fun ok(result: ProviderRepositoryResult) { assertTrue("Expected success: $result", result is ProviderRepositoryResult.Success) }
    private fun denied(result: ProviderRepositoryResult) { assertTrue("Expected denial: $result", result is ProviderRepositoryResult.Failure) }
    private fun pack(version: Long, enabled: Boolean = true) = ProviderPackageFixtures.signed(version = version, enabled = enabled)
    private fun healthy(repository: ProviderRepository, bytes: ByteArray, version: Long) { ok(repository.install(bytes)); ok(repository.confirmHealthy(version)) }
    private fun grant(version: Long, target: Long, bytes: ByteArray, enabled: Boolean = true) =
        IndependentPolicyVectors.signed(version, enabled, target = target, digest = IndependentPolicyVectors.digest(bytes))

    @Test fun policyEveryTruncationAndEverySingleByteMutationRejects() {
        val bytes = IndependentPolicyVectors.signed(); val verifier = IndependentPolicyVectors.verifier()
        assertTrue(verifier.verify(bytes, now) is RepositoryPolicyResult.Verified)
        for (size in 0 until bytes.size) assertTrue("truncation $size", verifier.verify(bytes.copyOf(size), now) is RepositoryPolicyResult.Rejected)
        for (offset in bytes.indices) {
            val mutated = bytes.copyOf(); mutated[offset] = (mutated[offset].toInt() xor 1).toByte()
            assertTrue("mutation $offset", verifier.verify(mutated, now) is RepositoryPolicyResult.Rejected)
        }
    }

    @Test fun validPolicySignaturesCannotAuthorizeNoncanonicalOrUnknownFields() {
        val vectors = listOf(
            IndependentPolicyVectors.payload(schema = 2), IndependentPolicyVectors.payload(provider = "other"),
            IndependentPolicyVectors.payload(providerVersion = "9.9.9"), IndependentPolicyVectors.payload(enabled = 2),
            IndependentPolicyVectors.payload(version = 0), IndependentPolicyVectors.payload(version = -1),
            IndependentPolicyVectors.payload(minimum = -1), IndependentPolicyVectors.payload(revokedVersions = listOf(1, 1)),
            IndependentPolicyVectors.payload(revokedVersions = listOf(2, 1)), IndependentPolicyVectors.payload(revokedVersions = listOf(0)),
            IndependentPolicyVectors.payload(revokedVersions = (1L..65).toList()),
            IndependentPolicyVectors.payload(revokedKeys = listOf("b", "a")), IndependentPolicyVectors.payload(revokedKeys = listOf("a", "a")),
            IndependentPolicyVectors.payload(revokedKeys = listOf("bad/key")),
            IndependentPolicyVectors.payload(revokedKeys = (1..33).map { "k%02d".format(it) }),
            IndependentPolicyVectors.payload(target = 0), IndependentPolicyVectors.payload(target = 1, digest = ByteArray(31)),
            IndependentPolicyVectors.payload(target = 1, digest = ByteArray(33)), IndependentPolicyVectors.payload(rollbackFlag = 2),
            IndependentPolicyVectors.payload(issued = -1), IndependentPolicyVectors.payload(expires = now - 1000),
            IndependentPolicyVectors.payload(issued = 0, expires = Long.MAX_VALUE), IndependentPolicyVectors.payload() + byteArrayOf(0)
        )
        val verifier = IndependentPolicyVectors.verifier()
        vectors.forEachIndexed { index, payload -> assertTrue("signed invalid $index", verifier.verify(IndependentPolicyVectors.envelope(payload), now) is RepositoryPolicyResult.Rejected) }
        assertTrue(verifier.verify(IndependentPolicyVectors.envelope(IndependentPolicyVectors.payload(), format = 2), now) is RepositoryPolicyResult.Rejected)
    }

    @Test fun emptyRootsAndCrossRoleSignaturesNeverCreateTrust() {
        val policy = IndependentPolicyVectors.signed()
        assertTrue(RepositoryPolicyVerifier().verify(policy, now) is RepositoryPolicyResult.Rejected)
        assertTrue(ProviderPackageVerifier().verify(pack(1), now) is ProviderPackageResult.Rejected)
        assertTrue(IndependentPolicyVectors.packageVerifierWithSameRoot().verify(policy, now) is ProviderPackageResult.Rejected)
        assertTrue(IndependentPolicyVectors.verifier().verify(pack(1), now) is RepositoryPolicyResult.Rejected)
        repository().use { denied(it.install(policy)); denied(it.installPolicy(pack(1))); assertEquals(0L, it.snapshot().highestAcceptedPolicyVersion) }
    }

    @Test fun replayFloorAndCumulativeRevocationsRemainProtectedAfterRestart() {
        val dir = temp(); val original = IndependentPolicyVectors.signed(version = 8, minimum = 7, revokedVersions = listOf(2, 4), revokedKeys = listOf("retired-key"))
        repository(dir).use { ok(it.installPolicy(original)) }
        repository(dir).use { r ->
            denied(r.installPolicy(original)); denied(r.installPolicy(IndependentPolicyVectors.signed(version = 7)))
            denied(r.installPolicy(IndependentPolicyVectors.signed(version = 9, minimum = 6, revokedVersions = listOf(2, 4), revokedKeys = listOf("retired-key"))))
            denied(r.installPolicy(IndependentPolicyVectors.signed(version = 9, minimum = 7, revokedVersions = listOf(4), revokedKeys = listOf("retired-key"))))
            denied(r.installPolicy(IndependentPolicyVectors.signed(version = 9, minimum = 7, revokedVersions = listOf(2, 4))))
            assertEquals(8L, r.snapshot().highestAcceptedPolicyVersion); assertEquals(7L, r.snapshot().minimumSafePackageVersion)
            ok(r.installPolicy(IndependentPolicyVectors.signed(version = 9, minimum = 8, revokedVersions = listOf(2, 4, 6), revokedKeys = listOf("older-key", "retired-key"))))
        }
        repository(dir).use { r -> assertEquals(9L, r.snapshot().highestAcceptedPolicyVersion); assertEquals(8L, r.snapshot().minimumSafePackageVersion); denied(r.installPolicy(original)) }
    }

    @Test fun signedGlobalDisableCannotBeBypassedByPendingRejectionRollbackOrNewPackage() {
        val dir = temp(); val old = pack(10)
        repository(dir).use { r ->
            healthy(r, old, 10); ok(r.install(pack(20)))
            ok(r.installPolicy(grant(1, 10, old, enabled = false)))
            assertFalse(r.snapshot().policy.enabled); denied(r.confirmHealthy(20)); denied(r.rollback()); denied(r.install(pack(30)))
            ok(r.rejectCandidate(20)); assertFalse(r.snapshot().policy.enabled); denied(r.rollback()); denied(r.install(pack(30)))
        }
        repository(dir).use { r -> assertFalse(r.snapshot().policy.enabled); denied(r.install(pack(30))); denied(r.rollback()) }
    }

    @Test fun policyOnlyDisableAndExpiredPolicyNeverRestoreBundledAuthority() {
        val dir = temp(); var time = now
        repository(dir, EpochClock { time }).use { r ->
            ok(r.installPolicy(IndependentPolicyVectors.signed(enabled = false, expires = now + 10)))
            assertFalse(r.snapshot().policy.enabled); time += 10; assertFalse(r.snapshot().policy.enabled)
            denied(r.install(pack(1))); denied(r.rollback())
            ok(r.installPolicy(IndependentPolicyVectors.signed(version = 2, issued = time - 1, expires = time + 1000)))
            assertFalse(r.snapshot().policy.enabled)
        }
        repository(dir, EpochClock { time }).use { r -> assertFalse(r.snapshot().policy.enabled); ok(r.install(pack(1))); assertTrue(r.snapshot().policy.enabled) }
    }

    @Test fun rollbackGrantBindsExactVersionAndCompleteSignedEnvelope() {
        val old = pack(10); val dir = temp()
        repository(dir).use { r ->
            healthy(r, old, 10); healthy(r, pack(20), 20)
            denied(r.rollback())
            ok(r.installPolicy(grant(1, 11, old))); denied(r.rollback())
            val sameVersionDifferentEnvelope = ProviderPackageFixtures.signed(version = 10, issued = now - 2000)
            ok(r.installPolicy(grant(2, 10, sameVersionDifferentEnvelope))); denied(r.rollback())
            ok(r.installPolicy(grant(3, 10, old))); assertTrue(r.snapshot().canRollback); ok(r.rollback())
            assertEquals(10L, r.snapshot().policy.packageVersion); assertTrue(r.snapshot().policy.enabled); assertEquals(20L, r.snapshot().highestAcceptedVersion)
        }
        repository(dir).use { r -> assertTrue(r.snapshot().policy.enabled); assertEquals(20L, r.snapshot().highestAcceptedVersion); denied(r.install(pack(20))) }
    }

    @Test fun grantingRollbackNeverOverridesSecurityFloorRevocationsOrExpiry() {
        for (restriction in 0..3) {
            var time = now
            repository(clock = EpochClock { time }).use { r ->
                val old = pack(10); healthy(r, old, 10); healthy(r, pack(20), 20)
                ok(r.installPolicy(IndependentPolicyVectors.signed(target = 10, digest = IndependentPolicyVectors.digest(old),
                    minimum = if (restriction == 0) 11 else 0,
                    revokedVersions = if (restriction == 1) listOf(10) else emptyList(),
                    revokedKeys = if (restriction == 2) listOf(ProviderPackageFixtures.KEY_ID) else emptyList())))
                if (restriction == 3) time += 60_000
                denied(r.rollback()); assertFalse(r.snapshot().canRollback)
            }
        }
    }

    @Test fun latestPolicyWithdrawalImmediatelyInvalidatesAnAlreadyRolledBackPackage() {
        val old = pack(10); val dir = temp()
        repository(dir).use { r ->
            healthy(r, old, 10); healthy(r, pack(20), 20); ok(r.installPolicy(grant(1, 10, old))); ok(r.rollback())
            assertTrue(r.snapshot().policy.enabled)
            ok(r.installPolicy(IndependentPolicyVectors.signed(version = 2))); assertFalse(r.snapshot().policy.enabled)
            denied(r.installPolicy(grant(1, 10, old)))
        }
        repository(dir).use { assertFalse(it.snapshot().policy.enabled); assertEquals(2L, it.snapshot().highestAcceptedPolicyVersion) }
    }

    @Test fun historicalPackageDisableBarrierCannotBeOverruledByANewerRollbackGrant() {
        val dir = temp(); val old = pack(10)
        repository(dir).use { r ->
            healthy(r, old, 10); ok(r.install(pack(20, false))); ok(r.installPolicy(grant(1, 10, old)))
            denied(r.rollback()); assertFalse(r.snapshot().policy.enabled)
            ok(r.install(pack(30))); assertTrue(r.snapshot().policy.enabled); ok(r.rejectCandidate(30)); assertFalse(r.snapshot().policy.enabled)
        }
        repository(dir).use { r -> denied(r.rollback()); assertFalse(r.snapshot().policy.enabled); ok(r.install(pack(40))); assertTrue(r.snapshot().policy.enabled) }
    }

    @Test fun policyChangeCannotApproveARecoveredUnconfirmedCandidate() {
        val dir = temp()
        repository(dir).use { ok(it.install(pack(10))) }
        repository(dir).use { r ->
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, r.snapshot().error)
            ok(r.installPolicy(IndependentPolicyVectors.signed())); assertFalse(r.snapshot().policy.enabled); denied(r.confirmHealthy(10))
            ok(r.rejectCandidate(10)); assertFalse(r.snapshot().policy.enabled); ok(r.install(pack(11))); assertTrue(r.snapshot().policy.enabled)
        }
    }

    @Test fun policyCancellationAndFaultAtEveryDurabilityStageStayDisabledAfterRestart() {
        for (stage in ProviderWriteStage.entries) for (cancel in listOf(false, true)) {
            val dir = temp(); var cancelled = false
            ProviderRepository(dir, ProviderPackageFixtures.verifier(), EpochClock { now }, bundle(), IndependentPolicyVectors.verifier(), { current ->
                if (current == stage) { if (cancel) cancelled = true else error("fixture") }
            }).use { r ->
                denied(r.installPolicy(IndependentPolicyVectors.signed(enabled = false)) { check(!cancelled) })
                assertFalse("$stage cancel=$cancel", r.snapshot().policy.enabled)
            }
            repository(dir).use { assertFalse("reopen $stage cancel=$cancel", it.snapshot().policy.enabled) }
        }
    }

    @Test fun corruptionMissingStateAndLegacyFormatNeverResetPolicyHighWater() {
        for (damage in 0..2) {
            val dir = temp(); repository(dir).use { ok(it.installPolicy(IndependentPolicyVectors.signed(version = 9, enabled = false))) }
            val state = dir.resolve("provider-state.bin")
            when (damage) {
                0 -> state.writeBytes(byteArrayOf(1))
                1 -> check(state.delete())
                else -> {
                    // A genuinely well-framed old format-1 state, not merely a bad checksum.
                    val pack = pack(10, false)
                    val raw = ByteArrayOutputStream().also { b -> DataOutputStream(b).use { o ->
                        o.writeInt(0x45535652); o.writeInt(1); o.writeLong(1); o.writeBoolean(false); o.writeBoolean(false)
                        listOf(pack, pack, null, pack).forEach { p -> o.writeInt(p?.size ?: 0); if (p != null) o.write(p) }
                    } }.toByteArray()
                    state.writeBytes(raw + IndependentPolicyVectors.digest(raw))
                }
            }
            val retained = if (state.exists()) state.readBytes() else null
            repository(dir).use { r -> assertFalse(r.snapshot().policy.enabled); denied(r.installPolicy(IndependentPolicyVectors.signed(version = 10))); denied(r.install(pack(11))) }
            if (retained != null) assertArrayEquals(retained, state.readBytes()) else assertFalse(state.exists())
        }
    }

    @Test fun committedClockLowerBoundBlocksRollbackAcrossRestart() {
        val dir = temp(); var time = now
        repository(dir, EpochClock { time }).use { r -> ok(r.installPolicy(IndependentPolicyVectors.signed())); healthy(r, pack(10), 10) }
        time--
        repository(dir, EpochClock { time }).use { r -> assertFalse(r.snapshot().policy.enabled); denied(r.installPolicy(IndependentPolicyVectors.signed(version = 2))); denied(r.install(pack(11))) }
        time = now
        repository(dir, EpochClock { time }).use { assertTrue(it.snapshot().policy.enabled) }
    }

    @Test fun everyExpirationObservationIsPartOfTheInProcessClockLowerBound() {
        for (policyExpiry in listOf(false, true)) {
            var scripted = false; var samples = 0; var rollback = false
            val expiry = now + 60_000
            val clock = EpochClock {
                if (rollback) expiry - 1
                else if (!scripted) now
                else if (samples++ == 0) expiry - 1 else expiry
            }
            repository(clock = clock).use { r ->
                if (policyExpiry) ok(r.installPolicy(IndependentPolicyVectors.signed(expires = expiry)))
                healthy(r, ProviderPackageFixtures.signed(version = 10, expires = if (policyExpiry) expiry + 10_000 else expiry), 10)
                scripted = true
                var expired = r.snapshot()
                // A single-time-snapshot implementation observes expiry on the following call.
                if (expired.policy.enabled) expired = r.snapshot()
                assertFalse("expiry must disable", expired.policy.enabled)
                rollback = true
                assertFalse("an observed expiry cannot be undone by a lower clock", r.snapshot().policy.enabled)
            }
        }
    }

    @Test fun policyDisableCancelsAnExistingMaintenanceLease() {
        repository().use { r ->
            val cancellation = DiscoveryCancellation(); val cancelled = CountDownLatch(1)
            cancellation.onCancel { cancelled.countDown() }
            RepositoryCommonsMaintenance(r).acquire(cancellation).use { lease ->
                ok(r.installPolicy(IndependentPolicyVectors.signed(enabled = false)))
                assertTrue(cancelled.await(2, TimeUnit.SECONDS)); assertTrue(cancellation.isCancelled)
                try { lease.check(); fail("disabled lease") } catch (expected: CommonsFailure) { assertEquals(CommonsError.DISABLED, expected.code) }
            }
        }
    }

    @Test fun policyUpdateRuntimeRetainsPhysicalSlotDuringCancelledBlockedStorage() {
        val dir = temp(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val callback = CountDownLatch(1)
        val result = AtomicReference<ProviderUpdateOutcome?>()
        val r = ProviderRepository(dir, ProviderPackageFixtures.verifier(), EpochClock { now }, bundle(), IndependentPolicyVectors.verifier(), { stage ->
            if (stage == ProviderWriteStage.AFTER_DATA_SYNC) { entered.countDown(); while (release.count > 0) try { release.await() } catch (_: InterruptedException) {} }
        })
        val runtime = ProviderUpdateRuntime(r, ProviderUpdateSource { object : ProviderUpdateCall {
            override fun execute() = ByteArrayInputStream(IndependentPolicyVectors.signed(enabled = false))
            override fun cancel() {}
        } }, EpochClock { now }, kind = ProviderUpdateKind.REPOSITORY_POLICY)
        try {
            val handle = runtime.execute(now + 10_000) { result.set(it); callback.countDown() }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); handle.cancel(); assertTrue(callback.await(2, TimeUnit.SECONDS))
            assertEquals(ProviderUpdateOutcome.Failure(ProviderUpdateError.CANCELLED), result.get())
            val retry = AtomicReference<ProviderUpdateOutcome?>(); runtime.execute(now + 10_000) { retry.set(it) }
            assertEquals(ProviderUpdateOutcome.Failure(ProviderUpdateError.BUSY), retry.get())
        } finally { release.countDown(); runtime.close(); r.close() }
        repository(dir).use { assertFalse(it.snapshot().policy.enabled) }
    }
    @Test fun expiredSignedPolicyKeepsReplayAndCumulativeSecurityEvidenceAfterReopen() {
        val dir = temp(); var time = now
        val expired = IndependentPolicyVectors.signed(version = 9, minimum = 5, revokedVersions = listOf(3), revokedKeys = listOf("old-key"), expires = now + 10)
        repository(dir, EpochClock { time }).use { ok(it.installPolicy(expired)) }
        time += 20
        repository(dir, EpochClock { time }).use { r ->
            assertEquals(9L, r.snapshot().highestAcceptedPolicyVersion); assertFalse(r.snapshot().policy.enabled)
            denied(r.installPolicy(IndependentPolicyVectors.signed(version = 9, minimum = 5, revokedVersions = listOf(3), revokedKeys = listOf("old-key"))))
            denied(r.installPolicy(IndependentPolicyVectors.signed(version = 10, minimum = 4, revokedVersions = listOf(3), revokedKeys = listOf("old-key"))))
            denied(r.installPolicy(IndependentPolicyVectors.signed(version = 10, minimum = 5)))
            ok(r.installPolicy(IndependentPolicyVectors.signed(version = 10, minimum = 5, revokedVersions = listOf(3), revokedKeys = listOf("old-key"))))
            assertEquals(10L, r.snapshot().highestAcceptedPolicyVersion); assertFalse(r.snapshot().policy.enabled)
            denied(r.install(pack(4))); ok(r.install(pack(5))); assertTrue(r.snapshot().policy.enabled)
        }
    }

    @Test fun maximalRevocationListsPersistAndCannotShrinkToMakeSpace() {
        val dir = temp(); val versions = (1L..64).toList(); val keys = (1..32).map { "retired-%02d".format(it) }
        val policy = IndependentPolicyVectors.signed(version = 1, revokedVersions = versions, revokedKeys = keys)
        repository(dir).use { ok(it.installPolicy(policy)) }
        repository(dir).use { r ->
            denied(r.install(pack(64))); ok(r.install(pack(65))); ok(r.confirmHealthy(65))
            denied(r.installPolicy(IndependentPolicyVectors.signed(version = 2, revokedVersions = (2L..65).toList(), revokedKeys = keys)))
            denied(r.installPolicy(IndependentPolicyVectors.signed(version = 2, revokedVersions = versions, revokedKeys = keys.drop(1))))
            assertEquals(1L, r.snapshot().highestAcceptedPolicyVersion); assertTrue(r.snapshot().policy.enabled)
        }
    }

    @Test fun explicitRollbackToDisabledPackageStillNeedsGrantButRejectedCandidateCanStayDisabled() {
        val disabled = pack(10, false)
        repository().use { r ->
            ok(r.install(disabled)); healthy(r, pack(20), 20)
            assertFalse(r.snapshot().canRollback); denied(r.rollback())
            ok(r.installPolicy(grant(1, 10, disabled))); assertTrue(r.snapshot().canRollback); ok(r.rollback())
            assertFalse(r.snapshot().policy.enabled); assertEquals(10L, r.snapshot().policy.packageVersion)
            assertEquals(20L, r.snapshot().highestAcceptedVersion)
        }
        repository().use { r ->
            ok(r.install(disabled)); ok(r.install(pack(20))); ok(r.rejectCandidate(20))
            assertFalse(r.snapshot().policy.enabled); assertEquals(10L, r.snapshot().policy.packageVersion)
            assertEquals(20L, r.snapshot().highestAcceptedVersion)
        }
    }

}
