package com.easysavevideo.app.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.easysavevideo.app.vault.VaultFailure
import com.easysavevideo.app.vault.VaultOperationException
import com.google.crypto.tink.Aead
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** No load, generation or authentication is performed by construction. No get-or-create path. */
internal class AndroidVaultKeystore {
    enum class SecurityLevel { TEE, STRONGBOX, SOFTWARE, UNKNOWN }
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasExistingVaultAliases(): Boolean {
        val aliases = store().aliases()
        var visited = 0
        while (aliases.hasMoreElements()) {
            if (++visited > 4096) throw VaultOperationException(VaultFailure.UNAVAILABLE)
            if (aliases.nextElement().startsWith(VaultCryptoWire.ALIAS_PREFIX)) return true
        }
        return false
    }

    /** Requires the exact alias to be durably registered by INITIALIZING and current user setup. */
    fun createDeviceKey(alias: String, requireHardware: Boolean): SecurityLevel =
        createFresh(alias, biometric = false, requireHardware = requireHardware)

    /** Separate explicit opt-in step; never called by password setup or application startup. */
    fun createBiometricKey(alias: String): SecurityLevel = createFresh(alias, biometric = true, requireHardware = false)

    private fun createFresh(alias: String, biometric: Boolean, requireHardware: Boolean): SecurityLevel {
        requireAlias(alias, biometric)
        if (store().containsAlias(alias)) throw VaultOperationException(VaultFailure.INCOMPLETE)
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(biometric)
        if (biometric) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
        // Deliberately no setUnlockedDeviceRequired and no user-security-setting mutation.
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(builder.build()); generateKey()
        }
        // A failed/missing/unsupported result preserves the registered alias for reconciliation.
        return securityLevel(alias, biometric).also {
            if (requireHardware && it !in listOf(SecurityLevel.TEE, SecurityLevel.STRONGBOX))
                throw VaultOperationException(VaultFailure.UNSUPPORTED)
        }
    }

    fun securityLevel(alias: String, biometric: Boolean = false): SecurityLevel {
        val key = existing(alias, biometric)
        val info = keyInfo(key)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TEE
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> SecurityLevel.SOFTWARE
            else -> SecurityLevel.UNKNOWN
        }
    }

    fun deviceAead(alias: String, requireHardware: Boolean = false): Aead {
        val key = existing(alias, biometric = false)
        if (requireHardware && securityLevel(alias) !in listOf(SecurityLevel.TEE, SecurityLevel.STRONGBOX))
            throw VaultOperationException(VaultFailure.UNSUPPORTED)
        return object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                require(plaintext.size <= 16_384 - 28 && associatedData.size <= 16_384) { "envelope-size" }
                val cipher = freshEncryptionCipher(key)
                cipher.updateAAD(associatedData)
                return cipher.iv + cipher.doFinal(plaintext)
            }
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
                require(ciphertext.size in 28..16_384 && associatedData.size <= 16_384) { "envelope-size" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12))
                cipher.updateAAD(associatedData)
                return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
            }
        }
    }

    /** Only the platform-prompt leaf may use these operation-scoped biometric Ciphers. */
    fun biometricEncryptCipher(alias: String): Cipher = freshEncryptionCipher(existing(alias, biometric = true))
    fun biometricDecryptCipher(alias: String, ciphertext: ByteArray): Cipher {
        require(ciphertext.size in 28..16_384) { "envelope-size" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, existing(alias, biometric = true), GCMParameterSpec(128, ciphertext, 0, 12))
        }
    }

    private fun existing(alias: String, biometric: Boolean): SecretKey {
        requireAlias(alias, biometric)
        val entry = store().getEntry(alias, null) as? KeyStore.SecretKeyEntry
            ?: throw VaultOperationException(VaultFailure.UNAVAILABLE)
        val key = entry.secretKey
        val info = keyInfo(key)
        if (key.algorithm != "AES" || info.keySize != 256 || info.keystoreAlias != alias ||
            info.purposes != (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) ||
            info.blockModes.toSet() != setOf(KeyProperties.BLOCK_MODE_GCM) ||
            info.encryptionPaddings.toSet() != setOf(KeyProperties.ENCRYPTION_PADDING_NONE) ||
            info.isUserAuthenticationRequired != biometric)
            throw VaultOperationException(VaultFailure.UNSUPPORTED)
        if (biometric && (info.userAuthenticationType != KeyProperties.AUTH_BIOMETRIC_STRONG ||
                info.userAuthenticationValidityDurationSeconds != -1 || !info.isInvalidatedByBiometricEnrollment))
            throw VaultOperationException(VaultFailure.UNSUPPORTED)
        return key
    }
    private fun keyInfo(key: SecretKey): KeyInfo = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
        .getKeySpec(key, KeyInfo::class.java) as KeyInfo
    private fun freshEncryptionCipher(key: SecretKey): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, key) // Caller supplies no IV. Randomized-encryption protection remains enabled.
        if (iv?.size != 12 || parameters.getParameterSpec(GCMParameterSpec::class.java).tLen != 128)
            throw VaultOperationException(VaultFailure.UNSUPPORTED)
    }
    private fun requireAlias(alias: String, biometric: Boolean) {
        require(alias.length <= 160 && alias.startsWith(VaultCryptoWire.ALIAS_PREFIX) &&
            alias.all { it.isLetterOrDigit() && it.code < 128 || it in "_.-" } &&
            alias.endsWith(if (biometric) ".biometric" else ".device")) { "alias-format" }
    }
}
