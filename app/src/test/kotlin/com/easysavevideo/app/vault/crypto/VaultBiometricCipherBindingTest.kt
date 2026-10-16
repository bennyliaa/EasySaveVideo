package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.google.crypto.tink.BinaryKeysetReader
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Public fixed AES-256-GCM/NIST data and the repository's existing public Tink fixture only.
 * No entropy generation, Android classes, platform key provider, user credential or prompt.
 */
class VaultBiometricCipherBindingTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val publicNistCiphertext = ByteArray(12) + hex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919")
    private fun cipher(mode: Int, aad: ByteArray = byteArrayOf()): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(ByteArray(32), "AES"), GCMParameterSpec(128, ByteArray(12)))
        updateAAD(aad)
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("unexpected acceptance") }
        catch (_: IllegalArgumentException) { }
        catch (_: GeneralSecurityException) { }
    }
    @Test fun encryptUsesActualCipherExactlyOnceAndHasNoDecryptDirection() {
        val aead = VaultBiometricCipherBinding.encrypt(cipher(Cipher.ENCRYPT_MODE), byteArrayOf())
        reject { aead.decrypt(publicNistCiphertext, byteArrayOf()) }
        assertArrayEquals(publicNistCiphertext, aead.encrypt(ByteArray(16), byteArrayOf()))
        reject { aead.encrypt(ByteArray(16), byteArrayOf()) }
    }
    @Test fun decryptBindsExactCiphertextAndUsesActualCipherExactlyOnce() {
        val aead = VaultBiometricCipherBinding.decrypt(cipher(Cipher.DECRYPT_MODE), byteArrayOf(), publicNistCiphertext)
        reject { aead.encrypt(ByteArray(16), byteArrayOf()) }
        val other = publicNistCiphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        reject { aead.decrypt(other, byteArrayOf()) }
        assertArrayEquals(ByteArray(16), aead.decrypt(publicNistCiphertext, byteArrayOf()))
        reject { aead.decrypt(publicNistCiphertext, byteArrayOf()) }
    }
    @Test fun aadAndCiphertextAreImmutableCopiesAndWrongAadNeverRunsDecrypt() {
        val aad = byteArrayOf(1, 2, 3)
        val encrypted = VaultBiometricCipherBinding.encrypt(cipher(Cipher.ENCRYPT_MODE, aad), aad)
            .encrypt(ByteArray(16), aad)
        val original = encrypted.copyOf()
        val prepared = VaultBiometricCipherBinding.decrypt(cipher(Cipher.DECRYPT_MODE, aad), aad, encrypted)
        encrypted.fill(7); aad.fill(7)
        reject { prepared.decrypt(original, aad) }
        assertArrayEquals(ByteArray(16), prepared.decrypt(original, byteArrayOf(1, 2, 3)))
    }
    @Test fun identityAliasSlotGenerationAndPurposeAadCannotBeSubstituted() {
        val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init",
            "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
        fun aad(id: VaultPersistentIdentity = identity, slot: String = "biometric-v1", generation: Long = 2,
            purpose: VaultAadPurpose = VaultAadPurpose.BIOMETRIC_ROOT) = VaultCryptoWire.aad(id, purpose,
                VaultSlotKind.BIOMETRIC, slot, generation)
        val original = aad()
        val encrypt = VaultBiometricCipherBinding.encrypt(cipher(Cipher.ENCRYPT_MODE, original), original)
        val variants = listOf(aad(identity.copy(vaultId = "other-vault")), aad(identity.copy(installationId = "other-install")),
            aad(identity.copy(biometricKeyAlias = "esv.vault.v1.other-init.biometric")), aad(slot = "other-slot"),
            aad(generation = 3), aad(purpose = VaultAadPurpose.CATALOG))
        variants.forEach { changed -> reject { encrypt.encrypt(ByteArray(16), changed) } }
        assertTrue(encrypt.encrypt(ByteArray(16), original).isNotEmpty())
    }
    @Test fun actualTinkWrapOnlyNeedsOneEncryptAndIndependentUnwrapUsesNewCipher() {
        val publicKeyset = hex("128201000102030405060708090a0b80193a4061f7d7f3f20c2b95982db9633b0bc4ffdeede685e1a88b66a25260008b9bd98b2f15488c4cbfe3fec7d3252c7591e4bd84e0c6bbd5eb7f59328ab2a48330efd5944813a64dd7a49bfe48ad256ce1f216b91dcd5a2cceb27333c68006a0186928f4b21de8bcb85e920d76231ec7a282d79baa")
        val root = VaultJcaAead.fromOwnedBytes(ByteArray(32)).use {
            VaultTinkKeysets.unwrap(publicKeyset, it, "ESV-PUBLIC-FIXED-KEYSET-VECTOR".toByteArray())
        }
        val aad = "ESV-PUBLIC-BIOMETRIC-BINDING".toByteArray()
        val wrapCipher = cipher(Cipher.ENCRYPT_MODE, aad)
        val wrapped = VaultTinkKeysets.wrap(root, VaultBiometricCipherBinding.encrypt(wrapCipher, aad), aad)
        VaultTinkKeysets.requireCanonicalEncrypted(wrapped)
        val ciphertext = BinaryKeysetReader.withBytes(wrapped).readEncrypted().encryptedKeyset.toByteArray()
        val verifyCipher = cipher(Cipher.DECRYPT_MODE, aad)
        assertNotSame(wrapCipher, verifyCipher)
        val verified = VaultTinkKeysets.unwrap(wrapped, VaultBiometricCipherBinding.decrypt(verifyCipher, aad, ciphertext), aad)
        val publicChallenge = hex("01000004d2202122232425262728292a2ba24fc41c05fb376b770c36b7ec7b958db12583fbdeab01fcdee433463feee486bb31022a")
        assertArrayEquals(VaultTinkKeysets.primitive(root).decrypt(publicChallenge, "ESV-PUBLIC-FIXED-CATALOG-VECTOR".toByteArray()),
            VaultTinkKeysets.primitive(verified).decrypt(publicChallenge, "ESV-PUBLIC-FIXED-CATALOG-VECTOR".toByteArray()))
    }
}
