package com.easysavevideo.app.vault.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.KeyStatus
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.EncryptedKeyset
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig

/** One fixed raw streaming profile. Handles/primitives remain inside the authentication owner.
 * No caller-selected template, cleartext keyset export, AndroidKeysetManager or fallback.
 */
internal object VaultTinkStreamingKeysets {
    private fun configuration(): com.google.crypto.tink.Configuration {
        StreamingAeadConfig.register()
        return RegistryConfiguration.get()
    }

    fun fresh(): KeysetHandle {
        configuration()
        return KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB)
            .also(::requireProfile)
    }

    fun wrap(handle: KeysetHandle, wrapper: Aead, aad: ByteArray): ByteArray {
        requireProfile(handle)
        return canonicalEncrypted(TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, wrapper, aad, configuration()))
            .also { require(it.size in 1..VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "media-keyset-size" } }
    }

    fun unwrap(bytes: ByteArray, wrapper: Aead, aad: ByteArray): KeysetHandle {
        require(bytes.size in 1..VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "media-keyset-size" }
        require(bytes.contentEquals(canonicalEncrypted(bytes))) { "media-keyset-format" }
        return TinkProtoKeysetFormat.parseEncryptedKeyset(bytes, wrapper, aad, configuration()).also(::requireProfile)
    }

    fun primitive(handle: KeysetHandle): StreamingAead {
        requireProfile(handle)
        return handle.getPrimitive(configuration(), StreamingAead::class.java)
    }

    private fun canonicalEncrypted(bytes: ByteArray): ByteArray {
        require(bytes.size in 1..VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "media-keyset-size" }
        val parsed = BinaryKeysetReader.withBytes(bytes).readEncrypted()
        require(parsed.encryptedKeyset.size() in 28..VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "media-keyset-size" }
        return EncryptedKeyset.newBuilder().setEncryptedKeyset(parsed.encryptedKeyset).build().toByteArray()
    }

    private fun requireProfile(handle: KeysetHandle) {
        require(handle.size() == 1) { "media-keyset-profile" }
        val entry = handle.getAt(0)
        require(entry.status == KeyStatus.ENABLED && entry.isPrimary && handle.primary.id == entry.id &&
            entry.key.parameters == PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB) { "media-keyset-profile" }
    }
}
