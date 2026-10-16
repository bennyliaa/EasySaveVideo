package com.easysavevideo.app.vault.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.proto.EncryptedKeyset
import com.google.crypto.tink.KeyStatus
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters

/** The sole keyset leaf. No AndroidKeysetManager, cleartext serialization or secret export. */
internal object VaultTinkKeysets {
    private fun configuration(): com.google.crypto.tink.Configuration {
        AeadConfig.register()
        return RegistryConfiguration.get()
    }

    fun freshAes256Gcm(): KeysetHandle {
        configuration()
        return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).also(::requireProfile)
    }
    fun wrap(handle: KeysetHandle, wrapper: Aead, aad: ByteArray): ByteArray {
        requireProfile(handle)
        return canonicalEncrypted(TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, wrapper, aad, configuration())).also {
            require(it.isNotEmpty() && it.size <= VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "keyset-envelope-size" }
        }
    }
    fun unwrap(bytes: ByteArray, wrapper: Aead, aad: ByteArray): KeysetHandle {
        require(bytes.isNotEmpty() && bytes.size <= VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "keyset-envelope-size" }
        requireCanonicalEncrypted(bytes)
        return TinkProtoKeysetFormat.parseEncryptedKeyset(bytes, wrapper, aad, configuration()).also(::requireProfile)
    }
    /** Persist only the canonical ciphertext field; untrusted KeysetInfo is unnecessary.
     * This rejects unknown fields, duplicate fields, overlong varints and appended bytes.
     */
    fun requireCanonicalEncrypted(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "keyset-envelope-size" }
        require(bytes.contentEquals(canonicalEncrypted(bytes))) { "keyset-envelope-format" }
    }
    private fun canonicalEncrypted(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "keyset-envelope-size" }
        val parsed = BinaryKeysetReader.withBytes(bytes).readEncrypted()
        require(parsed.encryptedKeyset.size() in 28..VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "keyset-envelope-size" }
        return EncryptedKeyset.newBuilder().setEncryptedKeyset(parsed.encryptedKeyset).build().toByteArray()
    }
    fun primitive(handle: KeysetHandle): Aead {
        requireProfile(handle)
        return handle.getPrimitive(configuration(), Aead::class.java)
    }
    private fun requireProfile(handle: KeysetHandle) {
        require(handle.size() == 1) { "keyset-profile" }
        val entry = handle.getAt(0)
        require(entry.status == KeyStatus.ENABLED && entry.isPrimary && handle.primary.id == entry.id &&
            entry.key.parameters == PredefinedAeadParameters.AES256_GCM) { "keyset-profile" }
    }
}
