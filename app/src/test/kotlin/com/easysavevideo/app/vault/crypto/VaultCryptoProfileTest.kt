package com.easysavevideo.app.vault.crypto

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Public fixed inputs only. Does not invoke random/encrypt/key generation or Android. */
class VaultCryptoProfileTest {
    private val password = VaultCryptoProfile.CredentialKind.PASSWORD
    private val pin = VaultCryptoProfile.CredentialKind.PIN
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    private fun normalized(input: String, kind: VaultCryptoProfile.CredentialKind = password) =
        VaultCryptoProfile.normalizeCredential(input.toCharArray(), kind)

    @Test fun preservesAsciiPinAndRejectsOtherDigits() {
        assertArrayEquals("000001".toCharArray(), normalized("000001", pin))
        assertEquals(12, normalized("000001234567", pin).size)
        for (bad in listOf("00000", "1234567890123", "１２３４５６", "١٢٣٤٥٦", " 123456", "12345a"))
            rejects { normalized(bad, pin) }
    }
    @Test fun unicodeNfcCodePointsAndUtf8Bounds() {
        assertArrayEquals("é".repeat(15).toCharArray(), normalized("e\u0301".repeat(15)))
        assertEquals(256, normalized("🔒".repeat(128)).size)
        rejects { normalized("🔒".repeat(129)) }
        rejects { normalized("a".repeat(14)) }
        assertEquals(128, normalized("a".repeat(128)).size)
        rejects { normalized("a".repeat(129)) }
        assertArrayEquals(" public-password-123 ".toCharArray(), normalized(" public-password-123 "))
    }
    @Test fun rejectsUnpairedSurrogatesNulAndLineBreaks() {
        for (bad in listOf("\uD800", "\uDC00", "\uD800x", "\u0000", "\n", "\r", "\u0085", "\u2028", "\u2029"))
            rejects { normalized("public-password-123$bad") }
    }
    @Test fun rejectsEveryUnsupportedKdfParameterBeforeWork() {
        rejects { VaultCryptoProfile.requireKdfProfile(2, 600_000, 16, 32) }
        rejects { VaultCryptoProfile.requireKdfProfile(1, 599_999, 16, 32) }
        rejects { VaultCryptoProfile.requireKdfProfile(1, Int.MAX_VALUE, 16, 32) }
        rejects { VaultCryptoProfile.requireKdfProfile(1, 600_000, 15, 32) }
        rejects { VaultCryptoProfile.requireKdfProfile(1, 600_000, 16, 16) }
    }
    @Test fun publicIndependentPythonUtf8Pbkdf2Vectors() {
        val vectors = listOf(
            "public-password-123" to "d202cc32771a687aa6f7b3e636e703f6a999c8efcbb37268c35f659d062b3dc4",
            "公开固定密码向量仅用于测试验证" to "4aa21e144f474e560e3e92db593f927a54c743bd8d589161a121c69a3b5c1be6",
            "e\u0301".repeat(15) to "4263695f145d0deb54af007e7bb6c0aa9fb1b310f62828402409c0d1923d2101",
            "🔒".repeat(15) to "19fa0e53d28c7f248c216f48ba0c1339a4e0ef13d5b7eb060a7dab990f249770",
            " public-password-123 " to "c2635da6854c7552d5b96f80b96179666ce326b3468386d35be8abd055a2d668",
        )
        for ((input, hex) in vectors) {
            val actual = VaultCryptoProfile.deriveWrappingKey(input.toCharArray(), password, ByteArray(16) { it.toByte() })
            assertEquals(hex, actual.joinToString("") { "%02x".format(it.toInt() and 255) })
            actual.fill(0)
        }
    }
    @Test fun canonicalRecoveryIsExactly32BytesAnd43AsciiChars() {
        val publicBytes = ByteArray(32) { it.toByte() }
        val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(publicBytes)
        assertArrayEquals(publicBytes, VaultCryptoProfile.decodeRecovery(canonical.toCharArray()))
        for (bad in listOf(canonical + "=", " $canonical", canonical.dropLast(1),
            canonical.dropLast(1) + "9", "+" + canonical.drop(1), "\n" + canonical.drop(1)))
            rejects { VaultCryptoProfile.decodeRecovery(bad.toCharArray()) }
    }
}

class VaultFixedGcmVectorTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // NIST SP800-38D AES-256-GCM public all-zero key/IV/plaintext test case.
    private val ciphertext = bytes("000000000000000000000000cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919")
    @Test fun nistAes256GcmDecryptsFixedPublicVectorWithoutRandomOrEncrypt() {
        val key = ByteArray(32)
        VaultJcaAead.fromOwnedBytes(key).use { aead ->
            assertArrayEquals(ByteArray(16), aead.decrypt(ciphertext, ByteArray(0)))
        }
        assertArrayEquals(ByteArray(32), key)
    }
    @Test fun wrongTagAndAadFailAndClosedCapabilityRejects() {
        val aead = VaultJcaAead.fromOwnedBytes(ByteArray(32))
        for ((cipher, aad) in listOf(
            ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } to ByteArray(0),
            ciphertext to byteArrayOf(1), ciphertext.copyOf(27) to ByteArray(0))) {
            try { aead.decrypt(cipher, aad); fail("Expected authentication failure") }
            catch (_: java.security.GeneralSecurityException) { }
            catch (_: IllegalArgumentException) { }
        }
        aead.close()
        try { aead.decrypt(ciphertext, ByteArray(0)); fail("Closed key must reject") }
        catch (_: java.security.GeneralSecurityException) { }
    }
}
