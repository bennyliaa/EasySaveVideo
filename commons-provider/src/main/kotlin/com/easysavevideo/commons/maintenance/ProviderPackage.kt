package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsIdentity
import com.easysavevideo.commons.CommonsLimits
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

/** Only declarative restrictions of the installed, built-in Commons provider are representable. */
@ConsistentCopyVisibility
data class ProviderPolicy internal constructor(
    val packageVersion: Long,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val enabled: Boolean,
    val limits: CommonsLimits,
) {
    val providerId: String get() = CommonsIdentity.PROVIDER_ID
    val providerVersion: String get() = CommonsIdentity.VERSION
    override fun toString(): String = "ProviderPolicy([redacted])"
}

/** Authenticated bytes are detached on input and on every export. This class grants no authority. */
class SignedProviderPackage internal constructor(
    val policy: ProviderPolicy,
    val keyId: String,
    encoded: ByteArray,
) {
    private val encoded = encoded.copyOf()
    fun encodedBytes(): ByteArray = encoded.copyOf()
    override fun toString(): String = "SignedProviderPackage([redacted])"
}

/** No exceptions, attacker-controlled strings, key material, or payloads escape in failures. */
enum class ProviderPackageError {
    PACKAGE_TOO_LARGE, MALFORMED_PACKAGE, UNSUPPORTED_FORMAT, UNTRUSTED_KEY, UNSUPPORTED_KEY,
    INVALID_SIGNATURE, UNSUPPORTED_SCHEMA, TARGET_MISMATCH, INVALID_POLICY, INVALID_TIME,
    NOT_YET_VALID, EXPIRED, CANCELLED, VERIFICATION_UNAVAILABLE,
}

sealed class ProviderPackageResult {
    class Verified(val packageValue: SignedProviderPackage) : ProviderPackageResult() {
        override fun toString(): String = "ProviderPackageResult.Verified([redacted])"
    }
    data class Rejected(val code: ProviderPackageError) : ProviderPackageResult()
}

/**
 * Canonical wire protocol v1 (big-endian, no optional or extensible fields):
 *   int magic, int format, int keyIdLength, ASCII keyId, int payloadLength, payload,
 *   int signatureLength, signature.
 * The signature is SHA256withRSA over every byte from magic through the end of payload. Its
 * length must match the pinned RSA modulus exactly; no algorithm is selected by the envelope.
 * The payload is int schema, length-prefixed ASCII target ID and installed version, long package
 * version, long issued/expiry epoch millis, one byte enabled (exactly 0/1), int pageSize,
 * long responseBytes/deadlineMillis/cacheTtlMillis, and int cacheEntries. No trailing data is legal.
 *
 * Keys are supplied by the application/operator. There are deliberately no shipped trust keys,
 * trust-on-first-use, key URLs, production signing helpers, executable fields, or network access.
 */
object ProviderPackageProtocol {
    const val MAGIC = 0x45535650 // ESVP
    const val FORMAT = 1
    const val SCHEMA = 1
    const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    const val MAX_ENVELOPE_BYTES = 8_192
    const val MAX_PAYLOAD_BYTES = 4_096
    const val MAX_KEY_ID_BYTES = 64
    const val MAX_TARGET_ID_BYTES = 64
    const val MAX_TARGET_VERSION_BYTES = 32
    const val MIN_RSA_BITS = 2_048
    const val MAX_RSA_BITS = 4_096
    const val MAX_LIFETIME_MILLIS = 7L * 24 * 60 * 60 * 1_000
    const val MAX_CLOCK_SKEW_MILLIS = 5L * 60 * 1_000
}

/** Stateless authentication. The repository separately enforces monotonically increasing versions. */
class ProviderPackageVerifier(trustedKeys: Map<String, PublicKey> = emptyMap()) {
    // Copy parameters into JCA-owned keys, so later map or supplied PublicKey mutation cannot rotate
    // trust. Invalid entries stay unavailable rather than turning initialization into a raw error.
    private val trustedKeys: Map<String, RSAPublicKey?> = trustedKeys.entries
        .filter { isToken(it.key, ProviderPackageProtocol.MAX_KEY_ID_BYTES) }
        .associate { it.key to pinRsaKey(it.value) }

    /** Expiry is exclusive. Only issuance tolerates up to five minutes of positive clock skew. */
    fun verify(
        bytes: ByteArray,
        nowMillis: Long,
        checkCancelled: () -> Unit = {},
    ): ProviderPackageResult = verifyInternal(bytes, nowMillis, checkCancelled)

    /**
     * Repository recovery only: authenticate historical high-water evidence even after it expires.
     * All signed lifetime invariants still apply. A recovered package MUST go through [verify]
     * before being activated; this method deliberately makes no wall-clock validity claim.
     */
    internal fun verifyAuthenticity(
        bytes: ByteArray,
        checkCancelled: () -> Unit = {},
    ): ProviderPackageResult = verifyInternal(bytes, null, checkCancelled)

    private fun verifyInternal(
        input: ByteArray,
        nowMillis: Long?,
        checkCancelled: () -> Unit,
    ): ProviderPackageResult {
        return try {
            cancelled(checkCancelled)
            if (input.size > ProviderPackageProtocol.MAX_ENVELOPE_BYTES) reject(ProviderPackageError.PACKAGE_TOO_LARGE)
            if (nowMillis != null && nowMillis < 0) reject(ProviderPackageError.INVALID_TIME)
            // Bound the one full-envelope allocation before copying or decoding any input.
            val bytes = input.copyOf()
            val envelope = Reader(bytes)
            if (envelope.int() != ProviderPackageProtocol.MAGIC) reject(ProviderPackageError.MALFORMED_PACKAGE)
            if (envelope.int() != ProviderPackageProtocol.FORMAT) reject(ProviderPackageError.UNSUPPORTED_FORMAT)
            val keyId = envelope.token(ProviderPackageProtocol.MAX_KEY_ID_BYTES)
            val payloadLength = envelope.length(ProviderPackageProtocol.MAX_PAYLOAD_BYTES)
            val payloadStart = envelope.position
            envelope.skip(payloadLength)
            val signedLength = envelope.position
            val signatureLength = envelope.length(ProviderPackageProtocol.MAX_RSA_BITS / 8)
            if (signatureLength < ProviderPackageProtocol.MIN_RSA_BITS / 8) reject(ProviderPackageError.MALFORMED_PACKAGE)
            val signatureStart = envelope.position
            envelope.skip(signatureLength)
            envelope.finish()
            if (!trustedKeys.containsKey(keyId)) reject(ProviderPackageError.UNTRUSTED_KEY)
            val key = trustedKeys[keyId] ?: reject(ProviderPackageError.UNSUPPORTED_KEY)
            if (signatureLength != (key.modulus.bitLength() + 7) / 8) reject(ProviderPackageError.INVALID_SIGNATURE)
            cancelled(checkCancelled)
            val signature = Signature.getInstance(ProviderPackageProtocol.SIGNATURE_ALGORITHM)
            signature.initVerify(key)
            signature.update(bytes, 0, signedLength)
            val valid = try {
                signature.verify(bytes, signatureStart, signatureLength)
            } catch (_: SignatureException) {
                false
            }
            if (!valid) reject(ProviderPackageError.INVALID_SIGNATURE)
            cancelled(checkCancelled)
            val policy = readPolicy(Reader(bytes, payloadStart, payloadStart + payloadLength))
            if (nowMillis != null) {
                // Subtract only ordered, nonnegative values: neither comparison can overflow.
                if (policy.issuedAtEpochMillis > nowMillis &&
                    policy.issuedAtEpochMillis - nowMillis > ProviderPackageProtocol.MAX_CLOCK_SKEW_MILLIS)
                    reject(ProviderPackageError.NOT_YET_VALID)
                if (nowMillis >= policy.expiresAtEpochMillis) reject(ProviderPackageError.EXPIRED)
            }
            cancelled(checkCancelled)
            ProviderPackageResult.Verified(SignedProviderPackage(policy, keyId, bytes))
        } catch (failure: ParseFailure) {
            ProviderPackageResult.Rejected(failure.code)
        } catch (_: GeneralSecurityException) {
            ProviderPackageResult.Rejected(ProviderPackageError.VERIFICATION_UNAVAILABLE)
        } catch (_: RuntimeException) {
            ProviderPackageResult.Rejected(ProviderPackageError.VERIFICATION_UNAVAILABLE)
        }
    }

    private fun readPolicy(payload: Reader): ProviderPolicy {
        if (payload.int() != ProviderPackageProtocol.SCHEMA) reject(ProviderPackageError.UNSUPPORTED_SCHEMA)
        val providerId = payload.token(ProviderPackageProtocol.MAX_TARGET_ID_BYTES)
        val providerVersion = payload.token(ProviderPackageProtocol.MAX_TARGET_VERSION_BYTES)
        if (providerId != CommonsIdentity.PROVIDER_ID || providerVersion != CommonsIdentity.VERSION)
            reject(ProviderPackageError.TARGET_MISMATCH)
        val version = payload.long()
        val issued = payload.long()
        val expires = payload.long()
        val enabled = when (payload.byte()) {
            0 -> false
            1 -> true
            else -> reject(ProviderPackageError.MALFORMED_PACKAGE)
        }
        val pageSize = payload.int()
        val responseBytes = payload.long()
        val deadline = payload.long()
        val cacheTtl = payload.long()
        val cacheEntries = payload.int()
        payload.finish()
        if (version <= 0) reject(ProviderPackageError.INVALID_POLICY)
        if (issued < 0 || expires <= issued || expires - issued > ProviderPackageProtocol.MAX_LIFETIME_MILLIS)
            reject(ProviderPackageError.INVALID_TIME)
        val defaults = CommonsLimits()
        if (pageSize !in 1..defaults.pageSize || responseBytes !in 1..defaults.maxResponseBytes ||
            deadline !in 1..defaults.deadlineMillis || cacheTtl !in 0..defaults.cacheTtlMillis ||
            cacheEntries !in 0..defaults.cacheEntries) reject(ProviderPackageError.INVALID_POLICY)
        return ProviderPolicy(version, issued, expires, enabled,
            CommonsLimits(pageSize, responseBytes, deadline, cacheTtl, cacheEntries))
    }

    private fun pinRsaKey(key: PublicKey): RSAPublicKey? = try {
        if (key !is RSAPublicKey || key.algorithm != "RSA") null
        else {
            val modulus = key.modulus
            val exponent = key.publicExponent
            if (modulus.signum() <= 0 || modulus.bitLength() !in ProviderPackageProtocol.MIN_RSA_BITS..ProviderPackageProtocol.MAX_RSA_BITS ||
                !modulus.testBit(0) || exponent < BigInteger.valueOf(3) || exponent >= modulus || !exponent.testBit(0)) null
            else KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as? RSAPublicKey
        }
    } catch (_: GeneralSecurityException) { null
    } catch (_: RuntimeException) { null }

    private fun cancelled(check: () -> Unit) {
        if (Thread.currentThread().isInterrupted) reject(ProviderPackageError.CANCELLED)
        try { check() } catch (_: Exception) { reject(ProviderPackageError.CANCELLED) }
        if (Thread.currentThread().isInterrupted) reject(ProviderPackageError.CANCELLED)
    }

    private class Reader(private val bytes: ByteArray, start: Int = 0, private val end: Int = bytes.size) {
        var position: Int = start
            private set
        private fun need(count: Int) {
            if (count < 0 || count > end - position) reject(ProviderPackageError.MALFORMED_PACKAGE)
        }
        fun byte(): Int { need(1); return bytes[position++].toInt() and 0xff }
        fun int(): Int {
            need(4)
            return (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        }
        fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xffffffffL)
        fun length(max: Int): Int {
            val value = int()
            if (value !in 1..max) reject(ProviderPackageError.MALFORMED_PACKAGE)
            need(value)
            return value
        }
        fun token(max: Int): String {
            val length = length(max)
            val start = position
            repeat(length) {
                if (!tokenByte(byte())) reject(ProviderPackageError.MALFORMED_PACKAGE)
            }
            return String(bytes, start, length, Charsets.US_ASCII)
        }
        fun skip(count: Int) { need(count); position += count }
        fun finish() { if (position != end) reject(ProviderPackageError.MALFORMED_PACKAGE) }
    }

    private class ParseFailure(val code: ProviderPackageError) : RuntimeException(code.name, null, false, false)

    private companion object {
        fun reject(code: ProviderPackageError): Nothing = throw ParseFailure(code)
        fun tokenByte(value: Int): Boolean = value in 65..90 || value in 97..122 || value in 48..57 || value == 45 || value == 46 || value == 95
        fun isToken(value: String, max: Int): Boolean = value.length in 1..max && value.all { tokenByte(it.code) }
    }
}
