package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsIdentity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature

/** Ephemeral policy-specific test keys; no test key is a production or package trust root. */
internal object RepositoryPolicyFixtures {
    const val NOW = 1_800_000_000_000L
    const val KEY_ID = "fixture-repository-root"
    val keyPair: KeyPair by lazy { newRsaKeyPair() }

    fun newRsaKeyPair(bits: Int = 2_048): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
    fun verifier(): RepositoryPolicyVerifier = RepositoryPolicyVerifier(mapOf(KEY_ID to keyPair.public))
    fun digest(packageBytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(packageBytes)

    fun signed(
        version: Long = 1,
        enabled: Boolean = true,
        issued: Long = NOW - 1_000,
        expires: Long = NOW + 60_000,
        minimumSafePackageVersion: Long = 0,
        revokedPackageVersions: List<Long> = emptyList(),
        revokedPackageKeyIds: List<String> = emptyList(),
        rollbackTargetVersion: Long? = null,
        rollbackTargetDigest: ByteArray = ByteArray(RepositoryPolicyProtocol.PACKAGE_DIGEST_BYTES),
        keyId: String = KEY_ID,
        signingKey: PrivateKey = keyPair.private,
    ): ByteArray = envelope(payload(version = version, issued = issued, expires = expires,
        enabledByte = if (enabled) 1 else 0, minimumSafePackageVersion = minimumSafePackageVersion,
        revokedPackageVersions = revokedPackageVersions, revokedPackageKeyIds = revokedPackageKeyIds,
        rollbackTargetVersion = rollbackTargetVersion, rollbackTargetDigest = rollbackTargetDigest),
        keyId = keyId, signingKey = signingKey)

    /** Raw values and ordering intentionally permit malformed signed test cases. */
    fun payload(
        schema: Int = RepositoryPolicyProtocol.SCHEMA,
        providerId: String = CommonsIdentity.PROVIDER_ID,
        providerVersion: String = CommonsIdentity.VERSION,
        version: Long = 1,
        issued: Long = NOW - 1_000,
        expires: Long = NOW + 60_000,
        enabledByte: Int = 1,
        minimumSafePackageVersion: Long = 0,
        revokedPackageVersions: List<Long> = emptyList(),
        revokedPackageKeyIds: List<String> = emptyList(),
        rollbackTargetVersion: Long? = null,
        rollbackPresentByte: Int = if (rollbackTargetVersion != null) 1 else 0,
        rollbackTargetDigest: ByteArray = ByteArray(RepositoryPolicyProtocol.PACKAGE_DIGEST_BYTES),
    ): ByteArray = write {
        writeInt(schema)
        string(providerId)
        string(providerVersion)
        writeLong(version)
        writeLong(issued)
        writeLong(expires)
        writeByte(enabledByte)
        writeLong(minimumSafePackageVersion)
        writeInt(revokedPackageVersions.size)
        revokedPackageVersions.forEach { writeLong(it) }
        writeInt(revokedPackageKeyIds.size)
        revokedPackageKeyIds.forEach { string(it) }
        writeByte(rollbackPresentByte)
        if (rollbackTargetVersion != null) {
            writeLong(rollbackTargetVersion)
            write(rollbackTargetDigest)
        }
    }

    fun envelope(
        payload: ByteArray = payload(),
        keyId: String = KEY_ID,
        signingKey: PrivateKey = keyPair.private,
        format: Int = RepositoryPolicyProtocol.FORMAT,
        magic: Int = RepositoryPolicyProtocol.MAGIC,
        algorithm: String = RepositoryPolicyProtocol.SIGNATURE_ALGORITHM,
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
