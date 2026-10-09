package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsIdentity
import com.easysavevideo.commons.CommonsLimits
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProviderPackageTest {
    private val fixtures = ProviderPackageFixtures
    private val verifier by lazy { fixtures.verifier() }

    private fun verified(bytes: ByteArray, now: Long = ProviderPackageFixtures.NOW): SignedProviderPackage {
        val result = verifier.verify(bytes, now)
        assertTrue(result.toString(), result is ProviderPackageResult.Verified)
        return (result as ProviderPackageResult.Verified).packageValue
    }

    private fun rejected(
        bytes: ByteArray,
        error: ProviderPackageError? = null,
        now: Long = ProviderPackageFixtures.NOW,
        using: ProviderPackageVerifier = verifier,
    ) {
        val result = using.verify(bytes, now)
        assertTrue(result.toString(), result is ProviderPackageResult.Rejected)
        if (error != null) assertEquals(error, (result as ProviderPackageResult.Rejected).code)
    }

    private fun payloadRejected(payload: ByteArray, error: ProviderPackageError) = rejected(fixtures.envelope(payload), error)
    private fun intAt(bytes: ByteArray, offset: Int, value: Int): ByteArray = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }
    private val payloadOffset: Int get() = 16 + fixtures.KEY_ID.length
    private val payloadLengthOffset: Int get() = payloadOffset - 4

    @Test fun canonicalPackagePinsBuiltInTargetAndPreservesEveryDeclarativeValue() {
        val limits = CommonsLimits(pageSize = 3, maxResponseBytes = 8_192, deadlineMillis = 1_500,
            cacheTtlMillis = 2_000, cacheEntries = 2)
        val bytes = fixtures.signed(version = 42, enabled = false, limits = limits)
        val result = verified(bytes)
        assertEquals(42L, result.policy.packageVersion)
        assertEquals(fixtures.NOW - 1_000, result.policy.issuedAtEpochMillis)
        assertEquals(fixtures.NOW + 60_000, result.policy.expiresAtEpochMillis)
        assertEquals(CommonsIdentity.PROVIDER_ID, result.policy.providerId)
        assertEquals(CommonsIdentity.VERSION, result.policy.providerVersion)
        assertFalse(result.policy.enabled)
        assertEquals(limits, result.policy.limits)
        assertEquals(fixtures.KEY_ID, result.keyId)
        assertArrayEquals(bytes, result.encodedBytes())
        assertEquals(ProviderPackageProtocol.MAGIC, ByteBuffer.wrap(bytes).int)
        assertEquals(ProviderPackageProtocol.FORMAT, ByteBuffer.wrap(bytes).getInt(4))
    }

    @Test fun shippedTrustIsEmptyAndUnknownKeysNeverBecomeTrusted() {
        rejected(fixtures.signed(), ProviderPackageError.UNTRUSTED_KEY, using = ProviderPackageVerifier())
        rejected(fixtures.signed(keyId = "untrusted"), ProviderPackageError.UNTRUSTED_KEY)
        val empty = mutableMapOf<String, PublicKey>()
        val pinnedEmpty = ProviderPackageVerifier(empty)
        empty[fixtures.KEY_ID] = fixtures.keyPair.public
        rejected(fixtures.signed(), ProviderPackageError.UNTRUSTED_KEY, using = pinnedEmpty)
    }

    @Test fun trustStoreAndPublicKeyParametersAreSnapshotted() {
        val other = fixtures.newRsaKeyPair()
        val mutable = MutableRsaKey(fixtures.keyPair.public as RSAPublicKey)
        val trust = mutableMapOf<String, PublicKey>(fixtures.KEY_ID to mutable)
        val pinned = ProviderPackageVerifier(trust)
        trust.clear()
        mutable.current = other.public as RSAPublicKey
        assertTrue(pinned.verify(fixtures.signed(), fixtures.NOW) is ProviderPackageResult.Verified)
        rejected(fixtures.signed(signingKey = other.private), ProviderPackageError.INVALID_SIGNATURE, using = pinned)
    }

    @Test fun aValidSignatureFromAnotherKeyOrAnotherAlgorithmIsRejected() {
        rejected(fixtures.signed(signingKey = fixtures.newRsaKeyPair().private), ProviderPackageError.INVALID_SIGNATURE)
        for (algorithm in listOf("SHA1withRSA", "SHA512withRSA"))
            rejected(fixtures.envelope(algorithm = algorithm), ProviderPackageError.INVALID_SIGNATURE)
    }

    @Test fun trustRequiresRsaAndBoundedModulusAndValidPublicExponent() {
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
            rejected(fixtures.signed(), ProviderPackageError.UNSUPPORTED_KEY,
                using = ProviderPackageVerifier(mapOf(fixtures.KEY_ID to key)))
    }

    @Test fun upperBound4096BitRsaIsAcceptedWithoutPermittingLargerSignatures() {
        val large = fixtures.newRsaKeyPair(4_096)
        val bytes = fixtures.signed(signingKey = large.private)
        val largeVerifier = ProviderPackageVerifier(mapOf(fixtures.KEY_ID to large.public))
        assertTrue(largeVerifier.verify(bytes, fixtures.NOW) is ProviderPackageResult.Verified)
        // Signature length remains pinned to this key, rather than accepting any bounded length.
        rejected(fixtures.signed(), ProviderPackageError.INVALID_SIGNATURE, using = largeVerifier)
    }

    @Test fun malformedTrustKeyCannotLeakItsExceptionOrBecomeAKey() {
        val secret = "private-key-error-https://secret.invalid/token"
        val broken = object : RSAPublicKey {
            override fun getAlgorithm(): String = throw IllegalStateException(secret)
            override fun getFormat(): String = throw IllegalStateException(secret)
            override fun getEncoded(): ByteArray = throw IllegalStateException(secret)
            override fun getModulus(): BigInteger = throw IllegalStateException(secret)
            override fun getPublicExponent(): BigInteger = throw IllegalStateException(secret)
        }
        val using = ProviderPackageVerifier(mapOf(fixtures.KEY_ID to broken))
        val result = using.verify(fixtures.signed(), fixtures.NOW)
        assertEquals(ProviderPackageResult.Rejected(ProviderPackageError.UNSUPPORTED_KEY), result)
        assertFalse(result.toString().contains(secret))
    }

    @Test fun everySingleByteMutationAndEveryTruncationFailsClosed() {
        val bytes = fixtures.signed()
        for (index in bytes.indices) {
            val changed = bytes.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            rejected(changed)
            rejected(bytes.copyOf(index))
        }
    }

    @Test fun allEnvelopeAndPayloadTrailingBytesAreRejectedEvenWhenSigned() {
        rejected(fixtures.signed() + byteArrayOf(0), ProviderPackageError.MALFORMED_PACKAGE)
        payloadRejected(fixtures.payload() + byteArrayOf(0), ProviderPackageError.MALFORMED_PACKAGE)
        for (extra in listOf("url=https://evil.invalid", "host=evil.invalid", "script=alert(1)",
            "dex=payload", "code=executable", "path=../../escape"))
            payloadRejected(fixtures.payload() + extra.toByteArray(), ProviderPackageError.MALFORMED_PACKAGE)
    }

    @Test fun unknownMagicFormatSchemaAndTargetFailClosed() {
        rejected(fixtures.envelope(magic = 0), ProviderPackageError.MALFORMED_PACKAGE)
        for (number in listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE)) {
            rejected(fixtures.envelope(format = number), ProviderPackageError.UNSUPPORTED_FORMAT)
            payloadRejected(fixtures.payload(schema = number), ProviderPackageError.UNSUPPORTED_SCHEMA)
        }
        for (target in listOf("other-provider", "Wikimedia-Commons", "wikimedia-commons."))
            payloadRejected(fixtures.payload(providerId = target), ProviderPackageError.TARGET_MISMATCH)
        for (target in listOf("0.0.0", "0.1.1", "0.1.0-test", "1.0.0"))
            payloadRejected(fixtures.payload(providerVersion = target), ProviderPackageError.TARGET_MISMATCH)
    }

    @Test fun lengthPrefixesAreBoundedBeforeAnyAllocationOrDecode() {
        val bytes = fixtures.signed()
        val signatureLengthOffset = payloadOffset + fixtures.payload().size
        for (length in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            rejected(intAt(bytes, 8, length), ProviderPackageError.MALFORMED_PACKAGE)
            rejected(intAt(bytes, payloadLengthOffset, length), ProviderPackageError.MALFORMED_PACKAGE)
            rejected(intAt(bytes, signatureLengthOffset, length), ProviderPackageError.MALFORMED_PACKAGE)
            payloadRejected(intAt(fixtures.payload(), 4, length), ProviderPackageError.MALFORMED_PACKAGE)
            payloadRejected(intAt(fixtures.payload(), 8 + CommonsIdentity.PROVIDER_ID.length, length), ProviderPackageError.MALFORMED_PACKAGE)
        }
        rejected(intAt(bytes, 8, ProviderPackageProtocol.MAX_KEY_ID_BYTES + 1), ProviderPackageError.MALFORMED_PACKAGE)
        rejected(intAt(bytes, payloadLengthOffset, ProviderPackageProtocol.MAX_PAYLOAD_BYTES + 1), ProviderPackageError.MALFORMED_PACKAGE)
        for (length in listOf(1, 255, 513)) rejected(intAt(bytes, signatureLengthOffset, length), ProviderPackageError.MALFORMED_PACKAGE)
        payloadRejected(fixtures.payload(providerId = "a".repeat(65)), ProviderPackageError.MALFORMED_PACKAGE)
        payloadRejected(fixtures.payload(providerVersion = "a".repeat(33)), ProviderPackageError.MALFORMED_PACKAGE)
    }

    @Test fun totalEnvelopeAndPayloadBoundsCannotBeBypassedWithPadding() {
        rejected(ByteArray(ProviderPackageProtocol.MAX_ENVELOPE_BYTES + 1), ProviderPackageError.PACKAGE_TOO_LARGE)
        rejected(ByteArray(ProviderPackageProtocol.MAX_ENVELOPE_BYTES), ProviderPackageError.MALFORMED_PACKAGE)
        val canonical = fixtures.payload()
        payloadRejected(canonical.copyOf(ProviderPackageProtocol.MAX_PAYLOAD_BYTES), ProviderPackageError.MALFORMED_PACKAGE)
        rejected(fixtures.envelope(canonical.copyOf(ProviderPackageProtocol.MAX_PAYLOAD_BYTES + 1)), ProviderPackageError.MALFORMED_PACKAGE)
    }

    @Test fun tokenLengthsAndAsciiAlphabetAreStrictAndMaxKeyIdWorks() {
        val maximal = "a".repeat(ProviderPackageProtocol.MAX_KEY_ID_BYTES)
        val using = ProviderPackageVerifier(mapOf(maximal to fixtures.keyPair.public))
        assertTrue(using.verify(fixtures.signed(keyId = maximal), fixtures.NOW) is ProviderPackageResult.Verified)
        for (key in listOf("", maximal + "a", "bad key", "bad/key", "bad:key", "bad\u0000key", "ümlaut", "\uD800", "line\nkey"))
            rejected(fixtures.signed(keyId = key), ProviderPackageError.MALFORMED_PACKAGE)
        for (target in listOf("", "https://evil.invalid", "../escape", "bad\u0000target", "wikimedia-commöns"))
            payloadRejected(fixtures.payload(providerId = target), ProviderPackageError.MALFORMED_PACKAGE)
        payloadRejected(fixtures.payload(providerVersion = ""), ProviderPackageError.MALFORMED_PACKAGE)
    }

    @Test fun booleansHaveExactlyTwoCanonicalRepresentations() {
        assertTrue(verified(fixtures.envelope(fixtures.payload(enabledByte = 1))).policy.enabled)
        assertFalse(verified(fixtures.envelope(fixtures.payload(enabledByte = 0))).policy.enabled)
        for (value in 2..255) payloadRejected(fixtures.payload(enabledByte = value), ProviderPackageError.MALFORMED_PACKAGE)
    }

    @Test fun signedVersionsAreStrictlyPositiveWithoutNumericOverflow() {
        for (version in listOf(Long.MIN_VALUE, -1L, 0L))
            payloadRejected(fixtures.payload(version = version), ProviderPackageError.INVALID_POLICY)
        assertEquals(1L, verified(fixtures.signed(version = 1)).policy.packageVersion)
        assertEquals(Long.MAX_VALUE, verified(fixtures.signed(version = Long.MAX_VALUE)).policy.packageVersion)
    }

    @Test fun everyBudgetIsNonExpandingAndLowerBoundsAreExact() {
        assertEquals(CommonsLimits(), verified(fixtures.signed()).policy.limits)
        val minimum = CommonsLimits(1, 1, 1, 0, 0)
        assertEquals(minimum, verified(fixtures.signed(limits = minimum)).policy.limits)
        for (value in listOf(Int.MIN_VALUE, -1, 0, 6, 10, Int.MAX_VALUE))
            payloadRejected(fixtures.payload(pageSize = value), ProviderPackageError.INVALID_POLICY)
        for (value in listOf(Long.MIN_VALUE, -1, 0, 2L * 1_024 * 1_024 + 1, Long.MAX_VALUE))
            payloadRejected(fixtures.payload(responseBytes = value), ProviderPackageError.INVALID_POLICY)
        for (value in listOf(Long.MIN_VALUE, -1, 0, 15_001, Long.MAX_VALUE))
            payloadRejected(fixtures.payload(deadline = value), ProviderPackageError.INVALID_POLICY)
        for (value in listOf(Long.MIN_VALUE, -1, 300_001, Long.MAX_VALUE))
            payloadRejected(fixtures.payload(cacheTtl = value), ProviderPackageError.INVALID_POLICY)
        for (value in listOf(Int.MIN_VALUE, -1, 17, Int.MAX_VALUE))
            payloadRejected(fixtures.payload(cacheEntries = value), ProviderPackageError.INVALID_POLICY)
    }

    @Test fun negativeAndOverflowingTimeRangesAreRejectedBeforeClockComparisons() {
        for ((issued, expires) in listOf(-1L to fixtures.NOW, Long.MIN_VALUE to Long.MAX_VALUE,
            fixtures.NOW to fixtures.NOW, fixtures.NOW to fixtures.NOW - 1,
            0L to Long.MAX_VALUE, Long.MAX_VALUE to Long.MIN_VALUE,
            0L to ProviderPackageProtocol.MAX_LIFETIME_MILLIS + 1))
            payloadRejected(fixtures.payload(issued = issued, expires = expires), ProviderPackageError.INVALID_TIME)
        rejected(fixtures.signed(), ProviderPackageError.INVALID_TIME, now = -1)
        rejected(fixtures.signed(), ProviderPackageError.INVALID_TIME, now = Long.MIN_VALUE)
    }

    @Test fun lifetimeAndClockSkewBoundariesAreInclusiveButExpiryIsExclusive() {
        val lifetime = ProviderPackageProtocol.MAX_LIFETIME_MILLIS
        val skew = ProviderPackageProtocol.MAX_CLOCK_SKEW_MILLIS
        verified(fixtures.signed(issued = fixtures.NOW, expires = fixtures.NOW + lifetime))
        payloadRejected(fixtures.payload(issued = fixtures.NOW, expires = fixtures.NOW + lifetime + 1), ProviderPackageError.INVALID_TIME)
        verified(fixtures.signed(issued = fixtures.NOW + skew, expires = fixtures.NOW + skew + 1))
        rejected(fixtures.signed(issued = fixtures.NOW + skew + 1, expires = fixtures.NOW + skew + 2), ProviderPackageError.NOT_YET_VALID)
        verified(fixtures.signed(issued = fixtures.NOW - 1, expires = fixtures.NOW + 1))
        rejected(fixtures.signed(issued = fixtures.NOW - 1, expires = fixtures.NOW), ProviderPackageError.EXPIRED)
        rejected(fixtures.signed(issued = fixtures.NOW - 2, expires = fixtures.NOW - 1), ProviderPackageError.EXPIRED)
        verified(fixtures.signed(issued = 0, expires = 1), now = 0)
        verified(fixtures.signed(issued = Long.MAX_VALUE - 1, expires = Long.MAX_VALUE), now = Long.MAX_VALUE - 1)
        rejected(fixtures.signed(issued = Long.MAX_VALUE - 1, expires = Long.MAX_VALUE), ProviderPackageError.EXPIRED, now = Long.MAX_VALUE)
        rejected(fixtures.signed(issued = Long.MAX_VALUE - 1, expires = Long.MAX_VALUE), ProviderPackageError.NOT_YET_VALID, now = 0)
    }

    @Test fun historicalAuthenticationPreservesEvidenceWithoutAuthorizingExpiredActivation() {
        val expired = fixtures.signed(issued = fixtures.NOW - 2_000, expires = fixtures.NOW - 1_000)
        assertTrue(verifier.verifyAuthenticity(expired) is ProviderPackageResult.Verified)
        rejected(expired, ProviderPackageError.EXPIRED)
        val future = fixtures.signed(issued = fixtures.NOW + 600_000, expires = fixtures.NOW + 601_000)
        assertTrue(verifier.verifyAuthenticity(future) is ProviderPackageResult.Verified)
        rejected(future, ProviderPackageError.NOT_YET_VALID)
        for (bytes in listOf(fixtures.signed(version = 0), fixtures.envelope(fixtures.payload(schema = 2)),
            fixtures.signed(issued = -1), fixtures.signed(expires = fixtures.NOW + ProviderPackageProtocol.MAX_LIFETIME_MILLIS)))
            assertTrue(verifier.verifyAuthenticity(bytes) is ProviderPackageResult.Rejected)
    }

    @Test fun inputAndExportsAreDefensivelyCopiedEvenAcrossCancellationCallbacks() {
        val original = fixtures.signed()
        val input = original.copyOf()
        var calls = 0
        val result = verifier.verify(input, fixtures.NOW) {
            calls++
            if (calls == 2) input.fill(0)
        } as ProviderPackageResult.Verified
        assertEquals(4, calls)
        assertArrayEquals(original, result.packageValue.encodedBytes())
        result.packageValue.encodedBytes().fill(0)
        assertArrayEquals(original, result.packageValue.encodedBytes())
        assertEquals(1L, result.packageValue.policy.packageVersion)
    }

    @Test fun cancellationAtEveryBoundaryIsRedactedAndCannotReturnSuccess() {
        val bytes = fixtures.signed()
        for (cancelAt in 1..4) {
            var calls = 0
            val result = verifier.verify(bytes, fixtures.NOW) {
                if (++calls == cancelAt) throw CancellationException("https://private.invalid/cancel-secret")
            }
            assertEquals(ProviderPackageResult.Rejected(ProviderPackageError.CANCELLED), result)
            assertEquals(cancelAt, calls)
            assertFalse(result.toString().contains("private"))
        }
        val otherException = verifier.verify(bytes, fixtures.NOW) { throw IllegalStateException("private exception") }
        assertEquals(ProviderPackageResult.Rejected(ProviderPackageError.CANCELLED), otherException)
        val historical = verifier.verifyAuthenticity(bytes) { throw CancellationException("private") }
        assertEquals(ProviderPackageResult.Rejected(ProviderPackageError.CANCELLED), historical)
    }

    @Test fun threadInterruptionIsHonoredWithoutClearingTheInterrupt() {
        val bytes = fixtures.signed()
        try {
            Thread.currentThread().interrupt()
            val result = verifier.verify(bytes, fixtures.NOW)
            assertEquals(ProviderPackageResult.Rejected(ProviderPackageError.CANCELLED), result)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun toStringNeverPrintsKeysBytesTargetsOrSignedValues() {
        val bytes = fixtures.signed(version = 987_654_321)
        val result = verifier.verify(bytes, fixtures.NOW) as ProviderPackageResult.Verified
        for (text in listOf(result.toString(), result.packageValue.toString(), result.packageValue.policy.toString())) {
            for (privateValue in listOf(fixtures.KEY_ID, CommonsIdentity.PROVIDER_ID, "987654321", fixtures.NOW.toString(), "[69,"))
                assertFalse(text, text.contains(privateValue))
            assertTrue(text.contains("[redacted]"))
        }
        for (code in ProviderPackageError.entries)
            assertEquals("Rejected(code=${code.name})", ProviderPackageResult.Rejected(code).toString())
    }

    private class MutableRsaKey(var current: RSAPublicKey) : RSAPublicKey {
        override fun getAlgorithm(): String = "RSA"
        override fun getFormat(): String = "X.509"
        override fun getEncoded(): ByteArray = current.encoded
        override fun getModulus(): BigInteger = current.modulus
        override fun getPublicExponent(): BigInteger = current.publicExponent
    }

    private class ParameterRsaKey(
        private val modulus: BigInteger,
        private val exponent: BigInteger,
        private val name: String = "RSA",
    ) : RSAPublicKey {
        override fun getAlgorithm(): String = name
        override fun getFormat(): String = "X.509"
        override fun getEncoded(): ByteArray = byteArrayOf()
        override fun getModulus(): BigInteger = modulus
        override fun getPublicExponent(): BigInteger = exponent
    }
}
