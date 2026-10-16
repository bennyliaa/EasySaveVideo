package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.google.crypto.tink.KeysetHandle
import java.security.SecureRandom
import java.util.Base64

/** Optional recovery leaf; usable/confirmed status requires persisted root+catalog verification.
 * This leaf does not expose a boolean confirmation, repair authority, or session capability.
 */
internal object VaultRecoverySlot {
    /** Explicit opt-in/authorized protected display only. Caller clears chars after one display. */
    fun generateForProtectedDisplay(context: VaultOperationContext): CharArray {
        context.checkActive()
        val random = ByteArray(32)
        val encoded: ByteArray
        try { SecureRandom().nextBytes(random); encoded = Base64.getUrlEncoder().withoutPadding().encode(random) }
        finally { random.fill(0) }
        try { context.checkActive(); return CharArray(encoded.size) { encoded[it].toInt().toChar() } }
        finally { encoded.fill(0) }
    }
    fun wrap(root: KeysetHandle, identity: VaultPersistentIdentity, slotId: String, generation: Long,
        displayedMaterial: CharArray, context: VaultOperationContext): VaultCiphertext {
        context.checkActive()
        require(generation > 0) { "slot-generation" }
        return VaultJcaAead.fromOwnedBytes(VaultCryptoProfile.decodeRecovery(displayedMaterial)).use { recovery ->
            VaultCiphertext(VaultTinkKeysets.wrap(root, recovery,
                VaultCryptoWire.aad(identity, VaultAadPurpose.RECOVERY_ROOT, VaultSlotKind.RECOVERY, slotId, generation)))
                .also { context.checkActive() }
        }
    }
    /** Always takes re-entered input and a persisted envelope; never compares a retained code. */
    fun unwrapPersisted(identity: VaultPersistentIdentity, slotId: String, generation: Long,
        persistedEnvelope: VaultCiphertext, reenteredMaterial: CharArray, context: VaultOperationContext): KeysetHandle {
        context.checkActive()
        require(generation > 0) { "slot-generation" }
        return VaultJcaAead.fromOwnedBytes(VaultCryptoProfile.decodeRecovery(reenteredMaterial)).use { recovery ->
            VaultTinkKeysets.unwrap(persistedEnvelope.copyBytes(), recovery,
                VaultCryptoWire.aad(identity, VaultAadPurpose.RECOVERY_ROOT, VaultSlotKind.RECOVERY, slotId, generation))
                .also { context.checkActive() }
        }
    }
}
