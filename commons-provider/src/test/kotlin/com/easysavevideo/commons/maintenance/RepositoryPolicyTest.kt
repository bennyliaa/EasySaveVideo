package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsIdentity
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RepositoryPolicyTest {
    private val fixtures = RepositoryPolicyFixtures
    private val verifier by lazy { fixtures.verifier() }

    private fun verified(bytes: ByteArray, now: Long = RepositoryPolicyFixtures.NOW): SignedRepositoryPolicy {
        val result = verifier.verify(bytes, now)
        assertTrue(result.toString(), result is RepositoryPolicyResult.Verified)
        return (result as RepositoryPolicyResult.Verified).policyValue
    }

    private fun rejected(
        bytes: ByteArray,
        error: RepositoryPolicyError? = null,
        now: Long = RepositoryPolicyFixtures.NOW,
        using: RepositoryPolicyVerifier = verifier,
    ) {
        val result = using.verify(bytes, now)
        assertTrue(result.toString(), result is RepositoryPolicyResult.Rejected)
        if (error != null) assertEquals(error, (result as RepositoryPolicyResult.Rejected).code)
    }

    private fun payloadRejected(payload: ByteArray, error: RepositoryPolicyError) = rejected(fixtures.envelope(payload), error)
    private fun intAt(bytes: ByteArray, offset: Int, value: Int): ByteArray = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }
    private val payloadOffset: Int get() = 16 + fixtures.KEY_ID.length
    private val payloadLengthOffset: Int get() = payloadOffset - 4
    private val versionCountOffset: Int get() = 4 + 4 + CommonsIdentity.PROVIDER_ID.length +
        4 + CommonsIdentity.VERSION.length + 24 + 1 + 8

    @Test fun canonicalPolicyPinsBuiltInTargetAndPreservesEveryDeclarativeValue() {
        val digest = fixtures.digest(ProviderPackageFixtures.signed(version = 7))
        val bytes = fixtures.signed(version = 42, enabled = false, minimumSafePackageVersion = 5,
            revokedPackageVersions = listOf(6, 9, Long.MAX_VALUE),
            revokedPackageKeyIds = listOf("key-A", "key_B", "key.z").sorted(),
            rollbackTargetVersion = 7, rollbackTargetDigest = digest)
        val value = verified(bytes)
        val policy = value.policy
        assertEquals(42L, policy.policyVersion)
        assertEquals(fixtures.NOW - 1_000, policy.issuedAtEpochMillis)
        assertEquals(fixtures.NOW + 60_000, policy.expiresAtEpochMillis)
        assertEquals(CommonsIdentity.PROVIDER_ID, policy.providerId)
        assertEquals(CommonsIdentity.VERSION, policy.providerVersion)
        assertFalse(policy.enabled)
        assertEquals(5L, policy.minimumSafePackageVersion)
        assertEquals(setOf(6L, 9L, Long.MAX_VALUE), policy.revokedPackageVersions)
        assertEquals(setOf("key-A", "key_B", "key.z"), policy.revokedPackageKeyIds)
        assertEquals(7L, policy.rollbackAuthorization!!.targetPackageVersion)
        assertArrayEquals(digest, policy.rollbackAuthorization!!.targetPackageDigest)
        assertEquals(fixtures.KEY_ID, value.keyId)
        assertArrayEquals(bytes, value.encodedBytes())
        assertEquals(RepositoryPolicyProtocol.MAGIC, ByteBuffer.wrap(bytes).int)
        assertEquals(RepositoryPolicyProtocol.FORMAT, ByteBuffer.wrap(bytes).getInt(4))
    }

    @Test fun emptyRevocationsAndAbsentRollbackAreCanonicalWithoutGrantingBundledRollback() {
        val policy = verified(fixtures.signed()).policy
        assertTrue(policy.enabled)
        assertEquals(0L, policy.minimumSafePackageVersion)
        assertTrue(policy.revokedPackageVersions.isEmpty())
        assertTrue(policy.revokedPackageKeyIds.isEmpty())
        assertNull(policy.rollbackAuthorization)
    }

    @Test fun shippedTrustIsEmptyAndPolicyRootsAreIndependentOfPackageRoots() {
        rejected(fixtures.signed(), RepositoryPolicyError.UNTRUSTED_KEY, using = RepositoryPolicyVerifier())
        rejected(fixtures.signed(keyId = "unknown"), RepositoryPolicyError.UNTRUSTED_KEY)
        val empty = mutableMapOf<String, PublicKey>()
        val pinnedEmpty = RepositoryPolicyVerifier(empty)
        empty[fixtures.KEY_ID] = fixtures.keyPair.public
        rejected(fixtures.signed(), RepositoryPolicyError.UNTRUSTED_KEY, using = pinnedEmpty)
        // Having an admitted package key does not populate the separate policy verifier.
        assertTrue(ProviderPackageFixtures.verifier().verify(ProviderPackageFixtures.signed(), fixtures.NOW)
            is ProviderPackageResult.Verified)
        rejected(fixtures.signed(keyId = ProviderPackageFixtures.KEY_ID, signingKey = ProviderPackageFixtures.keyPair.private),
            RepositoryPolicyError.UNTRUSTED_KEY)
    }

    @Test fun trustStoreAndPublicKeyParametersAreSnapshotted() {
        val other = fixtures.newRsaKeyPair()
        val mutable = MutableRsaKey(fixtures.keyPair.public as RSAPublicKey)
        val trust = mutableMapOf<String, PublicKey>(fixtures.KEY_ID to mutable)
        val pinned = RepositoryPolicyVerifier(trust)
        trust.clear()
        mutable.current = other.public as RSAPublicKey
        assertTrue(pinned.verify(fixtures.signed(), fixtures.NOW) is RepositoryPolicyResult.Verified)
        rejected(fixtures.signed(signingKey = other.private), RepositoryPolicyError.INVALID_SIGNATURE, using = pinned)
    }

    @Test fun signaturesFromAnotherKeyOrAnotherAlgorithmAreRejected() {
        rejected(fixtures.signed(signingKey = fixtures.newRsaKeyPair().private), RepositoryPolicyError.INVALID_SIGNATURE)
        for (algorithm in listOf("SHA1withRSA", "SHA512withRSA"))
            rejected(fixtures.envelope(algorithm = algorithm), RepositoryPolicyError.INVALID_SIGNATURE)
    }

    @Test fun policyAndPackageSignatureDomainsAreDisjointEvenUnderTheSameInjectedKey() {
        assertNotEquals(ProviderPackageProtocol.MAGIC, RepositoryPolicyProtocol.MAGIC)
        val packageUsingSameRoot = ProviderPackageVerifier(mapOf(fixtures.KEY_ID to fixtures.keyPair.public))
        val packageBytes = ProviderPackageFixtures.signed(keyId = fixtures.KEY_ID, signingKey = fixtures.keyPair.private)
        assertTrue(packageUsingSameRoot.verify(packageBytes, fixtures.NOW) is ProviderPackageResult.Verified)
        rejected(packageBytes, RepositoryPolicyError.MALFORMED_POLICY)
        // Changing only the discriminator cannot transplant a valid signature to another domain.
        rejected(intAt(packageBytes, 0, RepositoryPolicyProtocol.MAGIC), RepositoryPolicyError.INVALID_SIGNATURE)
        val policyBytes = fixtures.signed()
        assertEquals(ProviderPackageResult.Rejected(ProviderPackageError.MALFORMED_PACKAGE),
            packageUsingSameRoot.verify(policyBytes, fixtures.NOW))
        assertEquals(ProviderPackageResult.Rejected(ProviderPackageError.INVALID_SIGNATURE),
            packageUsingSameRoot.verify(intAt(policyBytes, 0, ProviderPackageProtocol.MAGIC), fixtures.NOW))
        // Even a newly signed envelope cannot substitute the other protocol's payload.
        rejected(fixtures.envelope(ProviderPackageFixtures.payload()), RepositoryPolicyError.MALFORMED_POLICY)
    }

    @Test fun trustRequiresRsaBoundedModulusAndValidPublicExponent() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val small = fixtures.newRsaKeyPair(1_024)
        for (key in listOf(ec.public, small.public,
            ParameterRsaKey(BigInteger.ONE.shiftLeft(4_096).add(BigInteger.ONE), BigInteger.valueOf(65_537)),
            ParameterRsaKey(BigInteger.ONE.shiftLeft(2_047).add(BigInteger.ONE), BigInteger.TWO),
            ParameterRsaKey(BigInteger.ONE.shiftLeft(2_047).add(BigInteger.ONE), BigInteger.ONE),
            ParameterRsaKey(BigInteger.ONE.shiftLeft(2_047), BigInteger.valueOf(65_537)),
            ParameterRsaKey(BigInteger.ONE.negate(), BigInteger.valueOf(65_537)),
            ParameterRsaKey(BigInteger.ONE.shiftLeft(2_047).add(BigInteger.ONE), BigInteger.ONE.shiftLeft(2_048)),
            ParameterRsaKey((fixtures.keyPair.public as RSAPublicKey).modulus, BigInteger.valueOf(65_537), "EC")))
            rejected(fixtures.signed(), RepositoryPolicyError.UNSUPPORTED_KEY,
                using = RepositoryPolicyVerifier(mapOf(fixtures.KEY_ID to key)))
    }

    @Test fun upperBound4096BitRsaIsAcceptedAndSignatureLengthIsPinned() {
        val large = fixtures.newRsaKeyPair(4_096)
        val using = RepositoryPolicyVerifier(mapOf(fixtures.KEY_ID to large.public))
        assertTrue(using.verify(fixtures.signed(signingKey = large.private), fixtures.NOW) is RepositoryPolicyResult.Verified)
        rejected(fixtures.signed(), RepositoryPolicyError.INVALID_SIGNATURE, using = using)
    }

    @Test fun malformedTrustKeysCannotLeakTheirExceptionsOrBecomeRoots() {
        val secret = "private-key-error-https://secret.invalid/token"
        val broken = object : RSAPublicKey {
            override fun getAlgorithm(): String = throw IllegalStateException(secret)
            override fun getFormat(): String = throw IllegalStateException(secret)
            override fun getEncoded(): ByteArray = throw IllegalStateException(secret)
            override fun getModulus(): BigInteger = throw IllegalStateException(secret)
            override fun getPublicExponent(): BigInteger = throw IllegalStateException(secret)
        }
        val using = RepositoryPolicyVerifier(mapOf(fixtures.KEY_ID to broken))
        val result = using.verify(fixtures.signed(), fixtures.NOW)
        assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.UNSUPPORTED_KEY), result)
        assertFalse(result.toString().contains(secret))
    }

    @Test fun everySingleByteMutationAndEveryTruncationFailsClosed() {
        val bytes = fixtures.signed(version = 7, minimumSafePackageVersion = 2,
            revokedPackageVersions = listOf(3, 5), revokedPackageKeyIds = listOf("old-key", "retired-key"),
            rollbackTargetVersion = 4, rollbackTargetDigest = ByteArray(32) { it.toByte() })
        for (index in bytes.indices) {
            val changed = bytes.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            rejected(changed)
            rejected(bytes.copyOf(index))
        }
    }

    @Test fun allEnvelopeAndPayloadTrailingBytesAreRejectedEvenWhenSigned() {
        rejected(fixtures.signed() + byteArrayOf(0), RepositoryPolicyError.MALFORMED_POLICY)
        payloadRejected(fixtures.payload() + byteArrayOf(0), RepositoryPolicyError.MALFORMED_POLICY)
        for (extra in listOf("url=https://evil.invalid", "host=evil.invalid", "script=alert(1)",
            "dex=payload", "code=executable", "path=../../escape", "root=replace-key"))
            payloadRejected(fixtures.payload() + extra.toByteArray(), RepositoryPolicyError.MALFORMED_POLICY)
    }

    @Test fun unknownMagicFormatSchemaAndTargetFailClosed() {
        rejected(fixtures.envelope(magic = 0), RepositoryPolicyError.MALFORMED_POLICY)
        for (number in listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE)) {
            rejected(fixtures.envelope(format = number), RepositoryPolicyError.UNSUPPORTED_FORMAT)
            payloadRejected(fixtures.payload(schema = number), RepositoryPolicyError.UNSUPPORTED_SCHEMA)
        }
        for (target in listOf("other-provider", "Wikimedia-Commons", "wikimedia-commons."))
            payloadRejected(fixtures.payload(providerId = target), RepositoryPolicyError.TARGET_MISMATCH)
        for (target in listOf("0.0.0", "0.1.1", "0.1.0-test", "1.0.0"))
            payloadRejected(fixtures.payload(providerVersion = target), RepositoryPolicyError.TARGET_MISMATCH)
    }

    @Test fun lengthPrefixesAreBoundedBeforeAllocationOrDecode() {
        val bytes = fixtures.signed()
        val signatureLengthOffset = payloadOffset + fixtures.payload().size
        for (length in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            rejected(intAt(bytes, 8, length), RepositoryPolicyError.MALFORMED_POLICY)
            rejected(intAt(bytes, payloadLengthOffset, length), RepositoryPolicyError.MALFORMED_POLICY)
            rejected(intAt(bytes, signatureLengthOffset, length), RepositoryPolicyError.MALFORMED_POLICY)
            payloadRejected(intAt(fixtures.payload(), 4, length), RepositoryPolicyError.MALFORMED_POLICY)
            payloadRejected(intAt(fixtures.payload(), 8 + CommonsIdentity.PROVIDER_ID.length, length), RepositoryPolicyError.MALFORMED_POLICY)
        }
        rejected(intAt(bytes, 8, RepositoryPolicyProtocol.MAX_KEY_ID_BYTES + 1), RepositoryPolicyError.MALFORMED_POLICY)
        rejected(intAt(bytes, payloadLengthOffset, RepositoryPolicyProtocol.MAX_PAYLOAD_BYTES + 1), RepositoryPolicyError.MALFORMED_POLICY)
        for (length in listOf(1, 255, 513)) rejected(intAt(bytes, signatureLengthOffset, length), RepositoryPolicyError.MALFORMED_POLICY)
        payloadRejected(fixtures.payload(providerId = "a".repeat(65)), RepositoryPolicyError.MALFORMED_POLICY)
        payloadRejected(fixtures.payload(providerVersion = "a".repeat(33)), RepositoryPolicyError.MALFORMED_POLICY)
    }

    @Test fun totalEnvelopeAndPayloadBoundsCannotBeBypassedWithPadding() {
        rejected(ByteArray(RepositoryPolicyProtocol.MAX_ENVELOPE_BYTES + 1), RepositoryPolicyError.POLICY_TOO_LARGE)
        rejected(ByteArray(RepositoryPolicyProtocol.MAX_ENVELOPE_BYTES), RepositoryPolicyError.MALFORMED_POLICY)
        val canonical = fixtures.payload()
        payloadRejected(canonical.copyOf(RepositoryPolicyProtocol.MAX_PAYLOAD_BYTES), RepositoryPolicyError.MALFORMED_POLICY)
        rejected(fixtures.envelope(canonical.copyOf(RepositoryPolicyProtocol.MAX_PAYLOAD_BYTES + 1)), RepositoryPolicyError.MALFORMED_POLICY)
    }

    @Test fun tokenLengthsAndAsciiAlphabetAreStrictAndMaxKeyIdWorks() {
        val maximal = "a".repeat(RepositoryPolicyProtocol.MAX_KEY_ID_BYTES)
        val using = RepositoryPolicyVerifier(mapOf(maximal to fixtures.keyPair.public))
        assertTrue(using.verify(fixtures.signed(keyId = maximal), fixtures.NOW) is RepositoryPolicyResult.Verified)
        for (key in listOf("", maximal + "a", "bad key", "bad/key", "bad:key", "bad\u0000key", "ümlaut", "\uD800", "line\nkey")) {
            rejected(fixtures.signed(keyId = key), RepositoryPolicyError.MALFORMED_POLICY)
            payloadRejected(fixtures.payload(revokedPackageKeyIds = listOf(key)), RepositoryPolicyError.MALFORMED_POLICY)
        }
        for (target in listOf("", "https://evil.invalid", "../escape", "bad\u0000target", "wikimedia-commöns"))
            payloadRejected(fixtures.payload(providerId = target), RepositoryPolicyError.MALFORMED_POLICY)
        payloadRejected(fixtures.payload(providerVersion = ""), RepositoryPolicyError.MALFORMED_POLICY)
    }

    @Test fun booleansHaveExactlyTwoCanonicalRepresentations() {
        assertTrue(verified(fixtures.envelope(fixtures.payload(enabledByte = 1))).policy.enabled)
        assertFalse(verified(fixtures.envelope(fixtures.payload(enabledByte = 0))).policy.enabled)
        assertNull(verified(fixtures.envelope(fixtures.payload(rollbackPresentByte = 0))).policy.rollbackAuthorization)
        for (value in 2..255) {
            payloadRejected(fixtures.payload(enabledByte = value), RepositoryPolicyError.MALFORMED_POLICY)
            payloadRejected(fixtures.payload(rollbackPresentByte = value), RepositoryPolicyError.MALFORMED_POLICY)
        }
    }

    @Test fun policyVersionsArePositiveAndSafeFloorIsNonnegativeWithoutOverflow() {
        for (version in listOf(Long.MIN_VALUE, -1L, 0L))
            payloadRejected(fixtures.payload(version = version), RepositoryPolicyError.INVALID_POLICY)
        assertEquals(1L, verified(fixtures.signed(version = 1)).policy.policyVersion)
        assertEquals(Long.MAX_VALUE, verified(fixtures.signed(version = Long.MAX_VALUE)).policy.policyVersion)
        for (floor in listOf(Long.MIN_VALUE, -1L))
            payloadRejected(fixtures.payload(minimumSafePackageVersion = floor), RepositoryPolicyError.INVALID_POLICY)
        assertEquals(0L, verified(fixtures.signed()).policy.minimumSafePackageVersion)
        assertEquals(Long.MAX_VALUE, verified(fixtures.signed(minimumSafePackageVersion = Long.MAX_VALUE)).policy.minimumSafePackageVersion)
    }

    @Test fun revocationCountsHaveExactBoundsBeforeCollectionAllocation() {
        val versions = (1L..64L).toList()
        val keys = (1..32).map { it.toString().padStart(2, '0') + "a".repeat(62) }
        val policy = verified(fixtures.signed(revokedPackageVersions = versions, revokedPackageKeyIds = keys)).policy
        assertEquals(versions, policy.revokedPackageVersions.toList())
        assertEquals(keys, policy.revokedPackageKeyIds.toList())
        payloadRejected(fixtures.payload(revokedPackageVersions = (1L..65L).toList()), RepositoryPolicyError.MALFORMED_POLICY)
        payloadRejected(fixtures.payload(revokedPackageKeyIds = (1..33).map { it.toString().padStart(2, '0') }), RepositoryPolicyError.MALFORMED_POLICY)
        for (count in listOf(Int.MIN_VALUE, -1, Int.MAX_VALUE)) {
            payloadRejected(intAt(fixtures.payload(), versionCountOffset, count), RepositoryPolicyError.MALFORMED_POLICY)
            payloadRejected(intAt(fixtures.payload(), versionCountOffset + 4, count), RepositoryPolicyError.MALFORMED_POLICY)
        }
        // A bounded but unavailable count must not create fabricated elements.
        payloadRejected(intAt(fixtures.payload(), versionCountOffset, 64), RepositoryPolicyError.MALFORMED_POLICY)
        payloadRejected(intAt(fixtures.payload(), versionCountOffset + 4, 32), RepositoryPolicyError.MALFORMED_POLICY)
    }

    @Test fun revokedVersionsArePositiveStrictlyAscendingAndDistinct() {
        for (invalid in listOf(Long.MIN_VALUE, -1L, 0L))
            payloadRejected(fixtures.payload(revokedPackageVersions = listOf(invalid)), RepositoryPolicyError.INVALID_POLICY)
        for (invalid in listOf(listOf(1L, 1L), listOf(2L, 1L), listOf(1L, 3L, 2L), listOf(Long.MAX_VALUE, 1L)))
            payloadRejected(fixtures.payload(revokedPackageVersions = invalid), RepositoryPolicyError.MALFORMED_POLICY)
        assertEquals(setOf(1L, Long.MAX_VALUE), verified(fixtures.signed(revokedPackageVersions = listOf(1L, Long.MAX_VALUE))).policy.revokedPackageVersions)
    }

    @Test fun revokedPackageKeyIdsUseExactAsciiOrderingWithoutNormalization() {
        val ordered = listOf("-", ".", "0", "A", "_", "a")
        assertEquals(ordered, verified(fixtures.signed(revokedPackageKeyIds = ordered)).policy.revokedPackageKeyIds.toList())
        for (invalid in listOf(listOf("key", "key"), listOf("z", "a"), listOf("a", "A"), listOf("a", "c", "b")))
            payloadRejected(fixtures.payload(revokedPackageKeyIds = invalid), RepositoryPolicyError.MALFORMED_POLICY)
        // Package key IDs may happen to equal a policy-root ID; that does not revoke that root.
        assertTrue(verified(fixtures.signed(revokedPackageKeyIds = listOf(fixtures.KEY_ID)))
            .policy.revokedPackageKeyIds.contains(fixtures.KEY_ID))
    }

    @Test fun rollbackGrantRequiresPositiveSignedPackageVersionAndExactly32DigestBytes() {
        val completePackage = ProviderPackageFixtures.signed(version = 4)
        val digest = fixtures.digest(completePackage)
        val grant = verified(fixtures.signed(rollbackTargetVersion = 4, rollbackTargetDigest = digest)).policy.rollbackAuthorization!!
        assertEquals(4L, grant.targetPackageVersion)
        assertArrayEquals(fixtures.digest(completePackage), grant.targetPackageDigest)
        assertFalse(fixtures.digest(ProviderPackageFixtures.payload(version = 4)).contentEquals(grant.targetPackageDigest))
        for (invalid in listOf(Long.MIN_VALUE, -1L, 0L))
            payloadRejected(fixtures.payload(rollbackTargetVersion = invalid), RepositoryPolicyError.INVALID_POLICY)
        assertEquals(Long.MAX_VALUE, verified(fixtures.signed(rollbackTargetVersion = Long.MAX_VALUE))
            .policy.rollbackAuthorization!!.targetPackageVersion)
        for (size in listOf(0, 1, 31, 33, 64))
            payloadRejected(fixtures.payload(rollbackTargetVersion = 4, rollbackTargetDigest = ByteArray(size)), RepositoryPolicyError.MALFORMED_POLICY)
        payloadRejected(fixtures.payload(rollbackPresentByte = 1), RepositoryPolicyError.MALFORMED_POLICY)
        payloadRejected(fixtures.payload(rollbackPresentByte = 0, rollbackTargetVersion = 4), RepositoryPolicyError.MALFORMED_POLICY)
    }

    @Test fun statelessVerifierDoesNotSilentlyInventOrResetPersistentAdmissionState() {
        // Floor progression, cumulative revocations and policy-version admission belong to the
        // durable repository; this verifier authenticates exact claims without repairing them.
        verified(fixtures.signed(version = 9, minimumSafePackageVersion = 8,
            revokedPackageVersions = listOf(7), revokedPackageKeyIds = listOf("revoked-key")))
        val older = verified(fixtures.signed(version = 1)).policy
        assertEquals(1L, older.policyVersion)
        assertEquals(0L, older.minimumSafePackageVersion)
        assertTrue(older.revokedPackageVersions.isEmpty())
        assertTrue(older.revokedPackageKeyIds.isEmpty())
    }

    @Test fun negativeAndOverflowingTimeRangesAreRejectedBeforeClockComparisons() {
        for ((issued, expires) in listOf(-1L to fixtures.NOW, Long.MIN_VALUE to Long.MAX_VALUE,
            fixtures.NOW to fixtures.NOW, fixtures.NOW to fixtures.NOW - 1,
            0L to Long.MAX_VALUE, Long.MAX_VALUE to Long.MIN_VALUE,
            0L to RepositoryPolicyProtocol.MAX_LIFETIME_MILLIS + 1))
            payloadRejected(fixtures.payload(issued = issued, expires = expires), RepositoryPolicyError.INVALID_TIME)
        rejected(fixtures.signed(), RepositoryPolicyError.INVALID_TIME, now = -1)
        rejected(fixtures.signed(), RepositoryPolicyError.INVALID_TIME, now = Long.MIN_VALUE)
    }

    @Test fun packageAndRepositoryTimeWindowsRemainAlignedAndBoundaryChecksAreExact() {
        val lifetime = RepositoryPolicyProtocol.MAX_LIFETIME_MILLIS
        val skew = RepositoryPolicyProtocol.MAX_CLOCK_SKEW_MILLIS
        assertEquals(ProviderPackageProtocol.MAX_LIFETIME_MILLIS, lifetime)
        assertEquals(ProviderPackageProtocol.MAX_CLOCK_SKEW_MILLIS, skew)
        verified(fixtures.signed(issued = fixtures.NOW, expires = fixtures.NOW + lifetime))
        payloadRejected(fixtures.payload(issued = fixtures.NOW, expires = fixtures.NOW + lifetime + 1), RepositoryPolicyError.INVALID_TIME)
        verified(fixtures.signed(issued = fixtures.NOW + skew, expires = fixtures.NOW + skew + 1))
        rejected(fixtures.signed(issued = fixtures.NOW + skew + 1, expires = fixtures.NOW + skew + 2), RepositoryPolicyError.NOT_YET_VALID)
        verified(fixtures.signed(issued = fixtures.NOW - 1, expires = fixtures.NOW + 1))
        rejected(fixtures.signed(issued = fixtures.NOW - 1, expires = fixtures.NOW), RepositoryPolicyError.EXPIRED)
        rejected(fixtures.signed(issued = fixtures.NOW - 2, expires = fixtures.NOW - 1), RepositoryPolicyError.EXPIRED)
        verified(fixtures.signed(issued = 0, expires = 1), now = 0)
        verified(fixtures.signed(issued = Long.MAX_VALUE - 1, expires = Long.MAX_VALUE), now = Long.MAX_VALUE - 1)
        rejected(fixtures.signed(issued = Long.MAX_VALUE - 1, expires = Long.MAX_VALUE), RepositoryPolicyError.EXPIRED, now = Long.MAX_VALUE)
        rejected(fixtures.signed(issued = Long.MAX_VALUE - 1, expires = Long.MAX_VALUE), RepositoryPolicyError.NOT_YET_VALID, now = 0)
    }

    @Test fun recoveryAuthenticatesHistoricalRestrictionsWithoutApprovingExpiredActivation() {
        val expired = fixtures.signed(issued = fixtures.NOW - 2_000, expires = fixtures.NOW - 1_000,
            minimumSafePackageVersion = 2, revokedPackageVersions = listOf(3), revokedPackageKeyIds = listOf("revoked"),
            rollbackTargetVersion = 4)
        val historical = verifier.verifyAuthenticity(expired) as RepositoryPolicyResult.Verified
        assertEquals(2L, historical.policyValue.policy.minimumSafePackageVersion)
        assertEquals(setOf(3L), historical.policyValue.policy.revokedPackageVersions)
        assertEquals(setOf("revoked"), historical.policyValue.policy.revokedPackageKeyIds)
        rejected(expired, RepositoryPolicyError.EXPIRED)
        val future = fixtures.signed(issued = fixtures.NOW + 600_000, expires = fixtures.NOW + 601_000)
        assertTrue(verifier.verifyAuthenticity(future) is RepositoryPolicyResult.Verified)
        rejected(future, RepositoryPolicyError.NOT_YET_VALID)
        for (bytes in listOf(fixtures.signed(version = 0), fixtures.envelope(fixtures.payload(schema = 2)),
            fixtures.signed(issued = -1), fixtures.signed(expires = fixtures.NOW + RepositoryPolicyProtocol.MAX_LIFETIME_MILLIS),
            fixtures.signed(revokedPackageVersions = listOf(2, 1)), fixtures.signed(rollbackTargetVersion = 0)))
            assertTrue(verifier.verifyAuthenticity(bytes) is RepositoryPolicyResult.Rejected)
    }

    @Test fun inputAndEncodedExportsAreDefensivelyCopiedAcrossCancellationCallbacks() {
        val original = fixtures.signed()
        val input = original.copyOf()
        var calls = 0
        val result = verifier.verify(input, fixtures.NOW) {
            calls++
            if (calls == 2) input.fill(0)
        } as RepositoryPolicyResult.Verified
        assertEquals(4, calls)
        assertArrayEquals(original, result.policyValue.encodedBytes())
        result.policyValue.encodedBytes().fill(0)
        assertArrayEquals(original, result.policyValue.encodedBytes())
        assertEquals(1L, result.policyValue.policy.policyVersion)
    }

    @Test fun digestAndRevocationCollectionsAreDetachedAndCannotBeMutatedThroughCasts() {
        val versions = linkedSetOf(2L, 5L)
        val keys = linkedSetOf("key-A", "key-B")
        val digest = ByteArray(32) { it.toByte() }
        val originalDigest = digest.copyOf()
        val rollback = RepositoryRollbackAuthorization(4, digest)
        val policy = RepositoryPolicy(1, fixtures.NOW - 1, fixtures.NOW + 1,
            true, 0, versions, keys, rollback)
        versions.clear()
        keys.clear()
        digest.fill(0)
        assertEquals(setOf(2L, 5L), policy.revokedPackageVersions)
        assertEquals(setOf("key-A", "key-B"), policy.revokedPackageKeyIds)
        assertArrayEquals(originalDigest, policy.rollbackAuthorization!!.targetPackageDigest)
        policy.rollbackAuthorization!!.targetPackageDigest.fill(0)
        assertArrayEquals(originalDigest, policy.rollbackAuthorization!!.targetPackageDigest)
        for (mutate in listOf<() -> Unit>(
            { (policy.revokedPackageVersions as MutableSet<Long>).clear() },
            { (policy.revokedPackageKeyIds as MutableSet<String>).add("new-key") },
            { val iterator = policy.revokedPackageVersions.iterator() as MutableIterator<Long>; iterator.next(); iterator.remove() },
        )) {
            try { mutate(); fail("An authenticated collection must be immutable") }
            catch (_: UnsupportedOperationException) { }
        }
        val verifiedPolicy = verified(fixtures.signed(revokedPackageVersions = listOf(2), rollbackTargetVersion = 4,
            rollbackTargetDigest = originalDigest)).policy
        verifiedPolicy.rollbackAuthorization!!.targetPackageDigest.fill(0)
        assertArrayEquals(originalDigest, verifiedPolicy.rollbackAuthorization!!.targetPackageDigest)
        try { (verifiedPolicy.revokedPackageVersions as MutableSet<Long>).add(3); fail("Mutable authenticated revocations") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun cancellationAtEveryBoundaryIsRedactedAndCannotReturnSuccess() {
        val bytes = fixtures.signed()
        for (cancelAt in 1..4) {
            var calls = 0
            val result = verifier.verify(bytes, fixtures.NOW) {
                if (++calls == cancelAt) throw CancellationException("https://private.invalid/cancel-secret")
            }
            assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.CANCELLED), result)
            assertEquals(cancelAt, calls)
            assertFalse(result.toString().contains("private"))
        }
        val otherException = verifier.verify(bytes, fixtures.NOW) { throw IllegalStateException("private exception") }
        assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.CANCELLED), otherException)
        val historical = verifier.verifyAuthenticity(bytes) { throw CancellationException("private") }
        assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.CANCELLED), historical)
    }

    @Test fun threadInterruptionIsHonoredBeforeAndAfterCallbacksWithoutClearingTheInterrupt() {
        val bytes = fixtures.signed()
        try {
            Thread.currentThread().interrupt()
            assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.CANCELLED), verifier.verify(bytes, fixtures.NOW))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        try {
            val result = verifier.verify(bytes, fixtures.NOW) { Thread.currentThread().interrupt() }
            assertEquals(RepositoryPolicyResult.Rejected(RepositoryPolicyError.CANCELLED), result)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun toStringNeverPrintsKeysTargetsDigestsBytesOrSignedValues() {
        val result = verifier.verify(fixtures.signed(version = 987_654_321, rollbackTargetVersion = 123_456_789,
            rollbackTargetDigest = ByteArray(32) { 42 }), fixtures.NOW) as RepositoryPolicyResult.Verified
        for (text in listOf(result.toString(), result.policyValue.toString(), result.policyValue.policy.toString(),
            result.policyValue.policy.rollbackAuthorization.toString())) {
            for (privateValue in listOf(fixtures.KEY_ID, CommonsIdentity.PROVIDER_ID, "987654321", "123456789", fixtures.NOW.toString(), "[69,", "[42,"))
                assertFalse(text, text.contains(privateValue))
            assertTrue(text.contains("[redacted]"))
        }
    }

    private class MutableRsaKey(var current: RSAPublicKey) : RSAPublicKey {
        override fun getAlgorithm(): String = current.algorithm
        override fun getFormat(): String = current.format
        override fun getEncoded(): ByteArray = current.encoded
        override fun getModulus(): BigInteger = current.modulus
        override fun getPublicExponent(): BigInteger = current.publicExponent
    }

    private class ParameterRsaKey(
        private val modulus: BigInteger,
        private val exponent: BigInteger,
        private val algorithm: String = "RSA",
    ) : RSAPublicKey {
        override fun getAlgorithm(): String = algorithm
        override fun getFormat(): String = "X.509"
        override fun getEncoded(): ByteArray = byteArrayOf()
        override fun getModulus(): BigInteger = modulus
        override fun getPublicExponent(): BigInteger = exponent
    }
}
