package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsIdentity
import com.easysavevideo.commons.CommonsLimits
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature

/** Ephemeral test-only signing material. Never used by production code or shipped as trust roots. */
internal object ProviderPackageFixtures {
    const val NOW = 1_800_000_000_000L
    const val KEY_ID = "fixture-rsa-key"
    val keyPair: KeyPair by lazy { newRsaKeyPair() }

    fun newRsaKeyPair(bits: Int = 2_048): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
    fun verifier(target: ProviderTarget = ProviderTarget.LEGACY): ProviderPackageVerifier =
        ProviderPackageVerifier(mapOf(KEY_ID to keyPair.public), target)

    fun signed(
        version: Long = 1,
        enabled: Boolean = true,
        issued: Long = NOW - 1_000,
        expires: Long = NOW + 60_000,
        limits: CommonsLimits = CommonsLimits(),
        keyId: String = KEY_ID,
        signingKey: PrivateKey = keyPair.private,
    ): ByteArray = envelope(payload(version = version, issued = issued, expires = expires,
        enabledByte = if (enabled) 1 else 0, pageSize = limits.pageSize, responseBytes = limits.maxResponseBytes,
        deadline = limits.deadlineMillis, cacheTtl = limits.cacheTtlMillis, cacheEntries = limits.cacheEntries),
        keyId = keyId, signingKey = signingKey)

    /** V2 test-only signer. Original v1 helpers and default bytes remain unchanged. */
    fun signedRules(
        target: ProviderTarget,
        ruleBytes: ByteArray,
        version: Long = 1,
        enabled: Boolean = true,
        issued: Long = NOW - 1_000,
        expires: Long = NOW + 60_000,
        limits: CommonsLimits = target.localLimits,
        keyId: String = KEY_ID,
        signingKey: PrivateKey = keyPair.private,
    ): ByteArray = envelope(rulesPayload(target, ruleBytes, version = version, issued = issued, expires = expires,
        enabledByte = if (enabled) 1 else 0, pageSize = limits.pageSize, responseBytes = limits.maxResponseBytes,
        deadline = limits.deadlineMillis, cacheTtl = limits.cacheTtlMillis, cacheEntries = limits.cacheEntries),
        keyId = keyId, signingKey = signingKey, format = ProviderPackageProtocol.FORMAT_V2)

    /** Explicit kind2 signer; signedRules and rulesPayload retain their kind1 defaults. */
    fun signedHttpRules(
        target: ProviderTarget,
        ruleBytes: ByteArray,
        version: Long = 1,
        enabled: Boolean = true,
        issued: Long = NOW - 1_000,
        expires: Long = NOW + 60_000,
        limits: CommonsLimits = target.localLimits,
        keyId: String = KEY_ID,
        signingKey: PrivateKey = keyPair.private,
    ): ByteArray = envelope(rulesPayload(target, ruleBytes,
        kind = ProviderPackageProtocol.KIND_BOUNDED_HTTP_CANDIDATES_V1,
        version = version, issued = issued, expires = expires,
        enabledByte = if (enabled) 1 else 0, pageSize = limits.pageSize, responseBytes = limits.maxResponseBytes,
        deadline = limits.deadlineMillis, cacheTtl = limits.cacheTtlMillis, cacheEntries = limits.cacheEntries),
        keyId = keyId, signingKey = signingKey, format = ProviderPackageProtocol.FORMAT_V2)

    /** All fields are overrideable so tests can sign incompatible, malformed or cross-target data. */
    fun rulesPayload(
        target: ProviderTarget,
        ruleBytes: ByteArray,
        schema: Int = ProviderPackageProtocol.SCHEMA_V2,
        kind: Int = ProviderPackageProtocol.KIND_DECLARATIVE_JSON_CANDIDATES_V1,
        providerId: String = target.providerId,
        providerVersion: String = target.providerVersion,
        version: Long = 1,
        issued: Long = NOW - 1_000,
        expires: Long = NOW + 60_000,
        enabledByte: Int = 1,
        pageSize: Int = target.localLimits.pageSize,
        responseBytes: Long = target.localLimits.maxResponseBytes,
        deadline: Long = target.localLimits.deadlineMillis,
        cacheTtl: Long = target.localLimits.cacheTtlMillis,
        cacheEntries: Int = target.localLimits.cacheEntries,
        specVersion: Int = target.specVersion,
        runtimeVersion: Int = target.runtimeVersion,
        minimumAppVersionCode: Int = target.appVersionCode,
        ruleSchemaVersion: Int = 1,
        ruleVersion: Long = 1,
        ruleDigest: ByteArray = MessageDigest.getInstance("SHA-256").digest(ruleBytes),
        ruleByteLength: Int = ruleBytes.size,
    ): ByteArray = write {
        writeInt(schema)
        writeInt(kind)
        string(providerId)
        string(providerVersion)
        writeLong(version)
        writeLong(issued)
        writeLong(expires)
        writeByte(enabledByte)
        writeInt(pageSize)
        writeLong(responseBytes)
        writeLong(deadline)
        writeLong(cacheTtl)
        writeInt(cacheEntries)
        writeInt(specVersion)
        writeInt(runtimeVersion)
        writeInt(minimumAppVersionCode)
        writeInt(ruleSchemaVersion)
        writeLong(ruleVersion)
        write(ruleDigest)
        writeInt(ruleByteLength)
        write(ruleBytes)
    }

    /** Raw fields permit adversarial signed values that CommonsLimits cannot construct. */
    fun payload(
        schema: Int = ProviderPackageProtocol.SCHEMA,
        providerId: String = CommonsIdentity.PROVIDER_ID,
        providerVersion: String = CommonsIdentity.VERSION,
        version: Long = 1,
        issued: Long = NOW - 1_000,
        expires: Long = NOW + 60_000,
        enabledByte: Int = 1,
        pageSize: Int = 5,
        responseBytes: Long = 2L * 1_024 * 1_024,
        deadline: Long = 15_000,
        cacheTtl: Long = 300_000,
        cacheEntries: Int = 16,
    ): ByteArray = write {
        writeInt(schema)
        string(providerId)
        string(providerVersion)
        writeLong(version)
        writeLong(issued)
        writeLong(expires)
        writeByte(enabledByte)
        writeInt(pageSize)
        writeLong(responseBytes)
        writeLong(deadline)
        writeLong(cacheTtl)
        writeInt(cacheEntries)
    }

    fun envelope(
        payload: ByteArray = payload(),
        keyId: String = KEY_ID,
        signingKey: PrivateKey = keyPair.private,
        format: Int = ProviderPackageProtocol.FORMAT,
        magic: Int = ProviderPackageProtocol.MAGIC,
        algorithm: String = ProviderPackageProtocol.SIGNATURE_ALGORITHM,
    ): ByteArray {
        val prefix = write {
            writeInt(magic)
            writeInt(format)
            string(keyId)
            writeInt(payload.size)
            write(payload)
        }
        val signature = Signature.getInstance(algorithm).run {
            initSign(signingKey)
            update(prefix)
            sign()
        }
        return write { write(prefix); writeInt(signature.size); write(signature) }
    }

    fun write(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use(block)
    }.toByteArray()

    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}


/** Legacy tests retain their setup convenience; production contains no version-only promotion API. */
internal fun ProviderRepository.confirmHealthy(expectedVersion: Long,
    checkCancelled: () -> Unit = {}): ProviderRepositoryResult {
    val snapshot = snapshot()
    if (snapshot.pendingVersion == null) return ProviderRepositoryResult.Failure(ProviderRepositoryError.NO_CANDIDATE)
    if (snapshot.pendingVersion != expectedVersion) return ProviderRepositoryResult.Failure(ProviderRepositoryError.STALE_CANDIDATE)
    snapshot.error?.let { return ProviderRepositoryResult.Failure(it) }
    var result: ProviderRepositoryResult = ProviderRepositoryResult.Success(snapshot)
    repeat(ProviderHealthLimits.REQUIRED_SUCCESSES) {
        if (snapshot().pendingVersion == null) return result
        val token = beginObservation() ?: return ProviderRepositoryResult.Failure(ProviderRepositoryError.STALE_OBSERVATION)
        result = completeObservation(token, ProviderHealthOutcome.SUCCESS, checkCancelled)
        if (result is ProviderRepositoryResult.Failure) return result
    }
    return result
}
