package com.easysavevideo.app.vault.crypto

import org.junit.Assert.*
import org.junit.Test

/** Project public deterministic fixtures built by independent Python cryptography AESGCM.
 * Test executes only decrypt; no Tink key generation or randomized encryption is called.
 */
class VaultTinkFixedVectorTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val keysetAad = "ESV-PUBLIC-FIXED-KEYSET-VECTOR".toByteArray()
    private val keyset = hex("128201000102030405060708090a0b80193a4061f7d7f3f20c2b95982db9633b0bc4ffdeede685e1a88b66a25260008b9bd98b2f15488c4cbfe3fec7d3252c7591e4bd84e0c6bbd5eb7f59328ab2a48330efd5944813a64dd7a49bfe48ad256ce1f216b91dcd5a2cceb27333c68006a0186928f4b21de8bcb85e920d76231ec7a282d79baa")
    @Test fun authenticatedFixedEncryptedKeysetProducesExpectedAes256GcmPrimitive() {
        VaultJcaAead.fromOwnedBytes(ByteArray(32)).use { wrapper ->
            val root = VaultTinkKeysets.unwrap(keyset, wrapper, keysetAad)
            val plaintext = VaultTinkKeysets.primitive(root).decrypt(hex("01000004d2202122232425262728292a2ba24fc41c05fb376b770c36b7ec7b958db12583fbdeab01fcdee433463feee486bb31022a"),
                "ESV-PUBLIC-FIXED-CATALOG-VECTOR".toByteArray())
            assertArrayEquals("public-empty-catalog".toByteArray(), plaintext)
            plaintext.fill(0)
        }
    }
    @Test fun wrongAadTamperedCipherAndNonProfileKeysetsReject() {
        VaultJcaAead.fromOwnedBytes(ByteArray(32)).use { wrapper ->
            for ((bytes, aad) in listOf(keyset to byteArrayOf(1),
                (keyset + byteArrayOf(24, 1)) to keysetAad,
                (keyset + byteArrayOf(26, 0)) to keysetAad,
                keyset.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } to keysetAad,
                hex("1272000102030405060708090a0b80193a4051f7c7f3f20c2b95982db9633b0bc4ffdeede685e1a88b66a25260008b9bd98b2f15488c4cbfe3fec7d3252c7591e4bd84e0c6bbd5eb4f59028ab2a48330efd5944813a64dd7a49bfe40bd277eed35098e044cc6e30249be9598e0c3c5a217d708c1") to keysetAad, hex("128201000102030405060708090a0b80193a4061f7d7f3f20c2b95982db9633b0bc4ffdeede685e1a88b66a25260008b9bd98b2f15488c4cbfe3fec7d3252c7591e4bd84e0c6bbd5eb7f59328ab2a48330efd5944813a64dd7a49bfe48ad256ce1f216b91dcd5a2cceb27333c68006a3186928f4b2d99c31bf553049e0abe1c9441070f6ef") to keysetAad)) {
                try { VaultTinkKeysets.unwrap(bytes, wrapper, aad); fail("Expected reject") }
                catch (_: java.security.GeneralSecurityException) { }
                catch (_: IllegalArgumentException) { }
            }
        }
    }
}
