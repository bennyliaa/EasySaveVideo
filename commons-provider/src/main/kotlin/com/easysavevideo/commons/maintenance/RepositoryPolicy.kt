package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsIdentity
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Collections

/** A grant for one exact complete signed package, never for the unsigned bundled default. */
class RepositoryRollbackAuthorization internal constructor(
    val targetPackageVersion: Long,
    targetPackageDigest: ByteArray,
) {
    private val digest = targetPackageDigest.copyOf()
    val targetPackageDigest: ByteArray get() = digest.copyOf()
    override fun toString(): String = "RepositoryRollbackAuthorization([redacted])"
}

/**
 * Declarative repository restrictions for the installed Commons implementation only.
 * Revoked key IDs identify PACKAGE signing keys, not repository-policy roots. Policy trust-root
 * rotation/revocation is deliberately absent. Collections and rollback digest are detached.
 */
class RepositoryPolicy internal constructor(
    val policyVersion: Long,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val enabled: Boolean,
    val minimumSafePackageVersion: Long,
    revokedPackageVersions: Set<Long>,
    revokedPackageKeyIds: Set<String>,
    val rollbackAuthorization: RepositoryRollbackAuthorization?,
) {
    val providerId: String get() = CommonsIdentity.PROVIDER_ID
    val providerVersion: String get() = CommonsIdentity.VERSION
    val revokedPackageVersions: Set<Long> = Collections.unmodifiableSet(LinkedHashSet(revokedPackageVersions))
    val revokedPackageKeyIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(revokedPackageKeyIds))
    override fun toString(): String = "RepositoryPolicy([redacted])"
}

/** Authenticated bytes are copied on input/export. This value alone grants no activation authority. */
class SignedRepositoryPolicy internal constructor(
    val policy: RepositoryPolicy,
    val keyId: String,
    encoded: ByteArray,
) {
    private val encoded = encoded.copyOf()
    fun encodedBytes(): ByteArray = encoded.copyOf()
    override fun toString(): String = "SignedRepositoryPolicy([redacted])"
}

/** No raw exceptions, attacker-controlled strings, keys, digests or payloads escape as failures. */
enum class RepositoryPolicyError {
    POLICY_TOO_LARGE, MALFORMED_POLICY, UNSUPPORTED_FORMAT, UNTRUSTED_KEY, UNSUPPORTED_KEY,
    INVALID_SIGNATURE, UNSUPPORTED_SCHEMA, TARGET_MISMATCH, INVALID_POLICY, INVALID_TIME,
    NOT_YET_VALID, EXPIRED, CANCELLED, VERIFICATION_UNAVAILABLE,
}

sealed class RepositoryPolicyResult {
    class Verified(val policyValue: SignedRepositoryPolicy) : RepositoryPolicyResult() {
        override fun toString(): String = "RepositoryPolicyResult.Verified([redacted])"
    }
    data class Rejected(val code: RepositoryPolicyError) : RepositoryPolicyResult()
}

/**
 * Canonical big-endian repository-policy wire protocol v1, closed to additional fields:
 *   int magic, int format, int keyIdLength, ASCII keyId, int payloadLength, payload,
 *   int signatureLength, signature.
 * Fixed SHA256withRSA authenticates ALL bytes from magic through the end of payload. ESVS magic
 * separates this signature domain from ESVP provider packages, even if an operator injects the
 * same RSA key into both verifiers. Signature length must equal the pinned RSA modulus length.
 *
 * Payload: int schema, length-prefixed ASCII Commons ID and installed version, long policyVersion,
 * long issued/expiry epoch millis, byte enabled (0/1), long minimumSafePackageVersion,
 * int revoked-version count then strictly ascending positive longs, int revoked-package-key count
 * then strictly ascending length-prefixed ASCII tokens, byte rollback-present (0/1), and, only
 * when present, long positive targetPackageVersion followed by EXACTLY 32 SHA-256 digest bytes.
 * The digest covers the complete encoded signed package, including envelope and signature.
 * Version 0 (bundled default) cannot be a rollback grant. No trailing bytes are accepted.
 *
 * Counts are bounded before collection allocation. Tokens use [A-Za-z0-9._-], with unsigned ASCII
 * byte ordering (identical to String ordering for this alphabet); duplicate/unsorted lists fail
 * closed rather than being normalized. Nothing can encode code, URLs, roots or executable rules.
 * Keys are independently injected; no production root, signer, service or network is provided.
 */
object RepositoryPolicyProtocol {
    const val MAGIC = 0x45535653 // ESVS, distinct from package ESVP and repository storage ESVR
    const val FORMAT = 1
    const val SCHEMA = 1
    const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    const val MAX_ENVELOPE_BYTES = 8_192
    const val MAX_PAYLOAD_BYTES = 4_096
    const val MAX_KEY_ID_BYTES = 64
    const val MAX_TARGET_ID_BYTES = 64
    const val MAX_TARGET_VERSION_BYTES = 32
    const val MAX_REVOKED_PACKAGE_VERSIONS = 64
    const val MAX_REVOKED_PACKAGE_KEY_IDS = 32
    const val PACKAGE_DIGEST_BYTES = 32
    const val MIN_RSA_BITS = 2_048
    const val MAX_RSA_BITS = 4_096
    const val MAX_LIFETIME_MILLIS = 7L * 24 * 60 * 60 * 1_000
    const val MAX_CLOCK_SKEW_MILLIS = 5L * 60 * 1_000
}

/**
 * Stateless authentication only. The persistent repository must separately enforce a strictly
 * increasing policyVersion high-water mark, a non-decreasing minimum safe package version, and
 * cumulative revocations that cannot be removed. A rollback grant never bypasses package expiry,
 * signature verification, the safe-version floor, revocations or the local user's admission.
 */
class RepositoryPolicyVerifier(trustedKeys: Map<String, PublicKey> = emptyMap()) {
    // Parameter snapshots prevent mutation of the input map or key from rotating trust later.
    private val trustedKeys: Map<String, RSAPublicKey?> = trustedKeys.entries
        .filter { isToken(it.key, RepositoryPolicyProtocol.MAX_KEY_ID_BYTES) }
        .associate { it.key to pinRsaKey(it.value) }

    /** Expiry is exclusive; only issuance permits up to five minutes of positive clock skew. */
    fun verify(
        bytes: ByteArray,
        nowMillis: Long,
        checkCancelled: () -> Unit = {},
    ): RepositoryPolicyResult = verifyInternal(bytes, nowMillis, checkCancelled)

    /**
     * Recovery only: proves signed historical anti-replay/restriction evidence after expiry.
     * Lifetime invariants still apply. This makes NO wall-clock validity or activation claim;
     * activation and rollback authorization must first pass [verify] with the current clock.
     */
    internal fun verifyAuthenticity(
        bytes: ByteArray,
        checkCancelled: () -> Unit = {},
    ): RepositoryPolicyResult = verifyInternal(bytes, null, checkCancelled)

    private fun verifyInternal(
        input: ByteArray,
        nowMillis: Long?,
        checkCancelled: () -> Unit,
    ): RepositoryPolicyResult {
        return try {
            cancelled(checkCancelled)
            if (input.size > RepositoryPolicyProtocol.MAX_ENVELOPE_BYTES) reject(RepositoryPolicyError.POLICY_TOO_LARGE)
            if (nowMillis != null && nowMillis < 0) reject(RepositoryPolicyError.INVALID_TIME)
            // Bound the full-envelope allocation before any copy, decode or attacker-sized allocation.
            val bytes = input.copyOf()
            val envelope = Reader(bytes)
            if (envelope.int() != RepositoryPolicyProtocol.MAGIC) reject(RepositoryPolicyError.MALFORMED_POLICY)
            if (envelope.int() != RepositoryPolicyProtocol.FORMAT) reject(RepositoryPolicyError.UNSUPPORTED_FORMAT)
            val keyId = envelope.token(RepositoryPolicyProtocol.MAX_KEY_ID_BYTES)
            val payloadLength = envelope.length(RepositoryPolicyProtocol.MAX_PAYLOAD_BYTES)
            val payloadStart = envelope.position
            envelope.skip(payloadLength)
            val signedLength = envelope.position
            val signatureLength = envelope.length(RepositoryPolicyProtocol.MAX_RSA_BITS / 8)
            if (signatureLength < RepositoryPolicyProtocol.MIN_RSA_BITS / 8) reject(RepositoryPolicyError.MALFORMED_POLICY)
            val signatureStart = envelope.position
            envelope.skip(signatureLength)
            envelope.finish()
            if (!trustedKeys.containsKey(keyId)) reject(RepositoryPolicyError.UNTRUSTED_KEY)
            val key = trustedKeys[keyId] ?: reject(RepositoryPolicyError.UNSUPPORTED_KEY)
            if (signatureLength != (key.modulus.bitLength() + 7) / 8) reject(RepositoryPolicyError.INVALID_SIGNATURE)
            cancelled(checkCancelled)
            val signature = Signature.getInstance(RepositoryPolicyProtocol.SIGNATURE_ALGORITHM)
            signature.initVerify(key)
            signature.update(bytes, 0, signedLength)
            val valid = try {
                signature.verify(bytes, signatureStart, signatureLength)
            } catch (_: SignatureException) {
                false
            }
            if (!valid) reject(RepositoryPolicyError.INVALID_SIGNATURE)
            cancelled(checkCancelled)
            val policy = readPolicy(Reader(bytes, payloadStart, payloadStart + payloadLength))
            if (nowMillis != null) {
                // Only subtract ordered nonnegative values, so these comparisons cannot overflow.
                if (policy.issuedAtEpochMillis > nowMillis &&
                    policy.issuedAtEpochMillis - nowMillis > RepositoryPolicyProtocol.MAX_CLOCK_SKEW_MILLIS)
                    reject(RepositoryPolicyError.NOT_YET_VALID)
                if (nowMillis >= policy.expiresAtEpochMillis) reject(RepositoryPolicyError.EXPIRED)
            }
            cancelled(checkCancelled)
            RepositoryPolicyResult.Verified(SignedRepositoryPolicy(policy, keyId, bytes))
        } catch (failure: ParseFailure) {
            RepositoryPolicyResult.Rejected(failure.code)
        } catch (_: GeneralSecurityException) {
            RepositoryPolicyResult.Rejected(RepositoryPolicyError.VERIFICATION_UNAVAILABLE)
        } catch (_: RuntimeException) {
            RepositoryPolicyResult.Rejected(RepositoryPolicyError.VERIFICATION_UNAVAILABLE)
        }
    }

    private fun readPolicy(payload: Reader): RepositoryPolicy {
        if (payload.int() != RepositoryPolicyProtocol.SCHEMA) reject(RepositoryPolicyError.UNSUPPORTED_SCHEMA)
        val providerId = payload.token(RepositoryPolicyProtocol.MAX_TARGET_ID_BYTES)
        val providerVersion = payload.token(RepositoryPolicyProtocol.MAX_TARGET_VERSION_BYTES)
        if (providerId != CommonsIdentity.PROVIDER_ID || providerVersion != CommonsIdentity.VERSION)
            reject(RepositoryPolicyError.TARGET_MISMATCH)
        val version = payload.long()
        val issued = payload.long()
        val expires = payload.long()
        val enabled = payload.boolean()
        val minimumSafePackageVersion = payload.long()
        val revokedVersionCount = payload.count(RepositoryPolicyProtocol.MAX_REVOKED_PACKAGE_VERSIONS)
        val revokedVersions = LinkedHashSet<Long>(revokedVersionCount)
        var previousVersion = 0L
        repeat(revokedVersionCount) {
            val revoked = payload.long()
            if (revoked <= 0) reject(RepositoryPolicyError.INVALID_POLICY)
            if (revoked <= previousVersion) reject(RepositoryPolicyError.MALFORMED_POLICY)
            revokedVersions.add(revoked)
            previousVersion = revoked
        }
        val revokedKeyCount = payload.count(RepositoryPolicyProtocol.MAX_REVOKED_PACKAGE_KEY_IDS)
        val revokedKeys = LinkedHashSet<String>(revokedKeyCount)
        var previousKey = ""
        repeat(revokedKeyCount) {
            val revoked = payload.token(RepositoryPolicyProtocol.MAX_KEY_ID_BYTES)
            if (revoked <= previousKey) reject(RepositoryPolicyError.MALFORMED_POLICY)
            revokedKeys.add(revoked)
            previousKey = revoked
        }
        val rollback = if (payload.boolean()) {
            val targetVersion = payload.long()
            if (targetVersion <= 0) reject(RepositoryPolicyError.INVALID_POLICY)
            RepositoryRollbackAuthorization(targetVersion, payload.bytes(RepositoryPolicyProtocol.PACKAGE_DIGEST_BYTES))
        } else null
        payload.finish()
        if (version <= 0 || minimumSafePackageVersion < 0) reject(RepositoryPolicyError.INVALID_POLICY)
        if (issued < 0 || expires <= issued || expires - issued > RepositoryPolicyProtocol.MAX_LIFETIME_MILLIS)
            reject(RepositoryPolicyError.INVALID_TIME)
        return RepositoryPolicy(version, issued, expires, enabled, minimumSafePackageVersion,
            revokedVersions, revokedKeys, rollback)
    }

    private fun pinRsaKey(key: PublicKey): RSAPublicKey? = try {
        if (key !is RSAPublicKey || key.algorithm != "RSA") null
        else {
            val modulus = key.modulus
            val exponent = key.publicExponent
            if (modulus.signum() <= 0 || modulus.bitLength() !in RepositoryPolicyProtocol.MIN_RSA_BITS..RepositoryPolicyProtocol.MAX_RSA_BITS ||
                !modulus.testBit(0) || exponent < BigInteger.valueOf(3) || exponent >= modulus || !exponent.testBit(0)) null
            else KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as? RSAPublicKey
        }
    } catch (_: GeneralSecurityException) { null
    } catch (_: RuntimeException) { null }

    private fun cancelled(check: () -> Unit) {
        if (Thread.currentThread().isInterrupted) reject(RepositoryPolicyError.CANCELLED)
        try { check() } catch (_: Exception) { reject(RepositoryPolicyError.CANCELLED) }
        if (Thread.currentThread().isInterrupted) reject(RepositoryPolicyError.CANCELLED)
    }

    private class Reader(private val input: ByteArray, start: Int = 0, private val end: Int = input.size) {
        var position: Int = start
            private set
        private fun need(count: Int) {
            if (count < 0 || count > end - position) reject(RepositoryPolicyError.MALFORMED_POLICY)
        }
        fun byte(): Int { need(1); return input[position++].toInt() and 0xff }
        fun boolean(): Boolean = when (byte()) {
            0 -> false
            1 -> true
            else -> reject(RepositoryPolicyError.MALFORMED_POLICY)
        }
        fun int(): Int {
            need(4)
            return (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        }
        fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xffffffffL)
        fun length(max: Int): Int {
            val value = int()
            if (value !in 1..max) reject(RepositoryPolicyError.MALFORMED_POLICY)
            need(value)
            return value
        }
        fun count(max: Int): Int {
            val value = int()
            if (value !in 0..max) reject(RepositoryPolicyError.MALFORMED_POLICY)
            return value
        }
        fun token(max: Int): String {
            val length = length(max)
            val start = position
            repeat(length) {
                if (!tokenByte(byte())) reject(RepositoryPolicyError.MALFORMED_POLICY)
            }
            return String(input, start, length, Charsets.US_ASCII)
        }
        fun bytes(count: Int): ByteArray {
            need(count)
            return input.copyOfRange(position, position + count).also { position += count }
        }
        fun skip(count: Int) { need(count); position += count }
        fun finish() { if (position != end) reject(RepositoryPolicyError.MALFORMED_POLICY) }
    }

    private class ParseFailure(val code: RepositoryPolicyError) : RuntimeException(code.name, null, false, false)

    private companion object {
        fun reject(code: RepositoryPolicyError): Nothing = throw ParseFailure(code)
        fun tokenByte(value: Int): Boolean = value in 65..90 || value in 97..122 || value in 48..57 || value == 45 || value == 46 || value == 95
        fun isToken(value: String, max: Int): Boolean = value.length in 1..max && value.all { tokenByte(it.code) }
    }
}
