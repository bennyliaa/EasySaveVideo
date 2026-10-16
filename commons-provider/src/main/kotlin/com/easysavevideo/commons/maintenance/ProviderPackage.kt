package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsLimits
import com.easysavevideo.commons.rules.DeclarativeJsonRule
import com.easysavevideo.commons.rules.CandidateRulePlan
import com.easysavevideo.commons.rules.BoundedHttpRule
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

/** Declarative restrictions for one host-admitted installed target; this value is not authority. */
@ConsistentCopyVisibility
data class ProviderPolicy internal constructor(
    val packageVersion: Long,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val enabled: Boolean,
    val limits: CommonsLimits,
    val target: ProviderTarget = ProviderTarget.LEGACY,
) {
    val providerId: String get() = target.providerId
    val providerVersion: String get() = target.providerVersion
    override fun toString(): String = "ProviderPolicy([redacted])"
}

/** Authenticated bytes are detached on input and on every export. This class grants no authority. */
class SignedProviderPackage internal constructor(
    val policy: ProviderPolicy,
    val keyId: String,
    encoded: ByteArray,
    val candidatePlan: CandidateRulePlan? = null,
    ruleDigest: ByteArray? = null,
) {
    val kind: ProviderPackageKind get() = policy.target.kind
    /** Compatibility view of kind1 only; HTTP JSON is intentionally not a legacy plan. */
    val compiledRule: DeclarativeJsonRule? get() = candidatePlan as? DeclarativeJsonRule
    private val digest = ruleDigest?.copyOf()
    val ruleDigest: ByteArray? get() = digest?.copyOf()
    private val encoded = encoded.copyOf()
    fun encodedBytes(): ByteArray = encoded.copyOf()
    override fun toString(): String = "SignedProviderPackage([redacted])"
}

/** No exceptions, attacker-controlled strings, key material, or payloads escape in failures. */
enum class ProviderPackageError {
    PACKAGE_TOO_LARGE, MALFORMED_PACKAGE, UNSUPPORTED_FORMAT, UNTRUSTED_KEY, UNSUPPORTED_KEY,
    INVALID_SIGNATURE, UNSUPPORTED_SCHEMA, TARGET_MISMATCH, INVALID_POLICY, INVALID_TIME,
    NOT_YET_VALID, EXPIRED, CANCELLED, VERIFICATION_UNAVAILABLE, INCOMPATIBLE_RUNTIME, INVALID_RULES,
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
 * V2 has explicit pure-JSON and bounded-HTTP candidate kinds and target-bound compatibility/limits followed by
 * rule schema/version, complete SHA-256, length and original rule bytes. The legacy branch and
 * limits are unchanged; see SIGNED_DECLARATIVE_JSON_CONNECTION.md for the closed v2 field order.
 *
 * Keys are supplied by the application/operator. There are deliberately no shipped trust keys,
 * trust-on-first-use, key URLs, production signing helpers, executable fields, or network access.
 */
object ProviderPackageProtocol {
    const val MAGIC = 0x45535650 // ESVP
    const val FORMAT = 1
    const val SCHEMA = 1
    const val FORMAT_V2 = 2
    const val SCHEMA_V2 = 2
    const val KIND_DECLARATIVE_JSON_CANDIDATES_V1 = 1
    const val KIND_BOUNDED_HTTP_CANDIDATES_V1 = 2
    const val MAX_V2_ENVELOPE_BYTES = 24_576
    const val MAX_V2_PAYLOAD_BYTES = 20_480
    const val MAX_RULE_BYTES = 16_384
    const val RULE_DIGEST_BYTES = 32
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
class ProviderPackageVerifier(
    trustedKeys: Map<String, PublicKey> = emptyMap(),
    val target: ProviderTarget = ProviderTarget.LEGACY,
) {
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
            if (input.size > target.maxPackageBytes) reject(ProviderPackageError.PACKAGE_TOO_LARGE)
            if (nowMillis != null && nowMillis < 0) reject(ProviderPackageError.INVALID_TIME)
            // Bound the one full-envelope allocation before copying or decoding any input.
            val bytes = input.copyOf()
            val envelope = Reader(bytes)
            if (envelope.int() != ProviderPackageProtocol.MAGIC) reject(ProviderPackageError.MALFORMED_PACKAGE)
            val format = envelope.int()
            val admittedFormat = when (target.kind) {
                ProviderPackageKind.COMMONS_PARAMETERS_V1 -> ProviderPackageProtocol.FORMAT
                ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1, ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1 ->
                    ProviderPackageProtocol.FORMAT_V2
            }
            if (format != admittedFormat) reject(ProviderPackageError.UNSUPPORTED_FORMAT)
            val keyId = envelope.token(ProviderPackageProtocol.MAX_KEY_ID_BYTES)
            val payloadLength = envelope.length(target.maxPackagePayloadBytes)
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
            val decoded = readPolicy(Reader(bytes, payloadStart, payloadStart + payloadLength), format, checkCancelled)
            val policy = decoded.policy
            if (nowMillis != null) {
                // Subtract only ordered, nonnegative values: neither comparison can overflow.
                if (policy.issuedAtEpochMillis > nowMillis &&
                    policy.issuedAtEpochMillis - nowMillis > ProviderPackageProtocol.MAX_CLOCK_SKEW_MILLIS)
                    reject(ProviderPackageError.NOT_YET_VALID)
                if (nowMillis >= policy.expiresAtEpochMillis) reject(ProviderPackageError.EXPIRED)
            }
            cancelled(checkCancelled)
            ProviderPackageResult.Verified(SignedProviderPackage(policy, keyId, bytes, decoded.rule, decoded.ruleDigest))
        } catch (failure: ParseFailure) {
            ProviderPackageResult.Rejected(failure.code)
        } catch (_: GeneralSecurityException) {
            ProviderPackageResult.Rejected(ProviderPackageError.VERIFICATION_UNAVAILABLE)
        } catch (_: RuntimeException) {
            ProviderPackageResult.Rejected(ProviderPackageError.VERIFICATION_UNAVAILABLE)
        }
    }

    private class Decoded(val policy: ProviderPolicy, val rule: CandidateRulePlan?, val ruleDigest: ByteArray?)

    private fun readPolicy(payload: Reader, format: Int, checkCancelled: () -> Unit): Decoded {
        val rulesFormat = format == ProviderPackageProtocol.FORMAT_V2
        val schema = if (rulesFormat) ProviderPackageProtocol.SCHEMA_V2 else ProviderPackageProtocol.SCHEMA
        if (payload.int() != schema) reject(ProviderPackageError.UNSUPPORTED_SCHEMA)
        if (rulesFormat) {
            val expectedKind = when (target.kind) {
                ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1 -> ProviderPackageProtocol.KIND_DECLARATIVE_JSON_CANDIDATES_V1
                ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1 -> ProviderPackageProtocol.KIND_BOUNDED_HTTP_CANDIDATES_V1
                ProviderPackageKind.COMMONS_PARAMETERS_V1 -> reject(ProviderPackageError.UNSUPPORTED_SCHEMA)
            }
            if (payload.int() != expectedKind) reject(ProviderPackageError.UNSUPPORTED_SCHEMA)
        }
        val providerId = payload.token(ProviderPackageProtocol.MAX_TARGET_ID_BYTES)
        val providerVersion = payload.token(ProviderPackageProtocol.MAX_TARGET_VERSION_BYTES)
        if (providerId != target.providerId || providerVersion != target.providerVersion)
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
        if (!rulesFormat) payload.finish()
        if (version <= 0) reject(ProviderPackageError.INVALID_POLICY)
        if (issued < 0 || expires <= issued || expires - issued > ProviderPackageProtocol.MAX_LIFETIME_MILLIS)
            reject(ProviderPackageError.INVALID_TIME)
        val defaults = target.localLimits
        if (pageSize !in 1..defaults.pageSize || responseBytes !in 1..defaults.maxResponseBytes ||
            deadline !in 1..defaults.deadlineMillis || cacheTtl !in 0..defaults.cacheTtlMillis ||
            cacheEntries !in 0..defaults.cacheEntries) reject(ProviderPackageError.INVALID_POLICY)
        val policy = ProviderPolicy(version, issued, expires, enabled,
            CommonsLimits(pageSize, responseBytes, deadline, cacheTtl, cacheEntries), target)
        if (!rulesFormat) return Decoded(policy, null, null)
        val specVersion = payload.int()
        val runtimeVersion = payload.int()
        val minimumAppVersionCode = payload.int()
        if (specVersion != target.specVersion || runtimeVersion != target.runtimeVersion ||
            minimumAppVersionCode !in 1..target.appVersionCode) reject(ProviderPackageError.INCOMPATIBLE_RUNTIME)
        val ruleSchema = payload.int()
        val ruleVersion = payload.long()
        val expectedRuleSchema = when (target.kind) {
            ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1 -> DeclarativeJsonRule.SCHEMA_VERSION
            ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1 -> BoundedHttpRule.SCHEMA_VERSION
            ProviderPackageKind.COMMONS_PARAMETERS_V1 -> reject(ProviderPackageError.UNSUPPORTED_SCHEMA)
        }
        if (ruleSchema != expectedRuleSchema) reject(ProviderPackageError.UNSUPPORTED_SCHEMA)
        if (ruleVersion <= 0) reject(ProviderPackageError.INVALID_RULES)
        val ruleDigest = payload.bytes(ProviderPackageProtocol.RULE_DIGEST_BYTES)
        val ruleBytes = payload.bytes(payload.length(ProviderPackageProtocol.MAX_RULE_BYTES))
        payload.finish()
        cancelled(checkCancelled)
        if (!MessageDigest.isEqual(ruleDigest, MessageDigest.getInstance("SHA-256").digest(ruleBytes)))
            reject(ProviderPackageError.INVALID_RULES)
        val rule = try {
            when (target.kind) {
                ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1 ->
                    DeclarativeJsonRule.compile(ruleBytes) { cancelled(checkCancelled) }
                ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1 -> BoundedHttpRule.compile(ruleBytes,
                    target.httpEndpoint ?: reject(ProviderPackageError.INVALID_RULES)) { cancelled(checkCancelled) }
                ProviderPackageKind.COMMONS_PARAMETERS_V1 -> reject(ProviderPackageError.UNSUPPORTED_SCHEMA)
            }
        }
        catch (failure: DiscoveryFailure) {
            reject(if (failure.code == DiscoveryError.CANCELLED) ProviderPackageError.CANCELLED
                else ProviderPackageError.INVALID_RULES)
        }
        if (rule.ruleVersion != ruleVersion) reject(ProviderPackageError.INVALID_RULES)
        return Decoded(policy, rule, ruleDigest)
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
        fun bytes(count: Int): ByteArray {
            need(count)
            return bytes.copyOfRange(position, position + count).also { position += count }
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
