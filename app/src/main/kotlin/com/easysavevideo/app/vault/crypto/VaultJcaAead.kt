package com.easysavevideo.app.vault.crypto

import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Single owned secret; never persist or return the SecretKey or Cipher. */
internal class VaultJcaAead private constructor(private var key: SecretKey?) : Aead, AutoCloseable {
    companion object {
        fun fromOwnedBytes(bytes: ByteArray): VaultJcaAead {
            require(bytes.size == VaultCryptoProfile.KEY_BYTES) { "key-size" }
            return try { VaultJcaAead(SecretKeySpec(bytes, "AES")) } finally { bytes.fill(0) }
        }
    }

    /** Cipher chooses a fresh 96-bit IV; callers cannot provide or derive encryption IVs. */
    @Synchronized override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        require(plaintext.size <= VaultCryptoProfile.MAX_CATALOG_BYTES - 28) { "plaintext-size" }
        require(associatedData.size <= VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "aad-size" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireOpen())
        val iv = cipher.iv ?: throw GeneralSecurityException("missing-iv")
        if (iv.size != VaultCryptoProfile.IV_BYTES) throw GeneralSecurityException("provider-profile-mismatch")
        val parameters = cipher.parameters.getParameterSpec(GCMParameterSpec::class.java)
        if (parameters.tLen != VaultCryptoProfile.TAG_BYTES * 8) throw GeneralSecurityException("provider-profile-mismatch")
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    @Synchronized override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        require(ciphertext.size in 28..VaultCryptoProfile.MAX_CATALOG_BYTES) { "ciphertext-size" }
        require(associatedData.size <= VaultCryptoProfile.MAX_ENVELOPE_BYTES) { "aad-size" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, requireOpen(), GCMParameterSpec(128, ciphertext, 0, 12))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }

    private fun requireOpen(): SecretKey = key ?: throw GeneralSecurityException("secret-closed")

    @Synchronized override fun close() {
        val previous = key
        key = null
        // SecretKeySpec/provider may retain internal copies; no physical-erasure assertion.
        try { previous?.destroy() } catch (_: javax.security.auth.DestroyFailedException) { }
    }
}
