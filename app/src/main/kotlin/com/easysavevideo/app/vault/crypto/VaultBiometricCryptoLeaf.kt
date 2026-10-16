package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.library.export.ExportRelease
import com.google.crypto.tink.KeysetHandle

/** Narrow existing crypto leaf seam. Production uses AndroidVaultBiometric; no Root authority. */
internal interface VaultBiometricCryptoLeaf {
    fun availability(): VaultBiometricAvailability
    fun prepareWrapRoot(identity: VaultPersistentIdentity, slotId: String, generation: Long,
        root: KeysetHandle, title: CharSequence, cancelText: CharSequence, context: VaultOperationContext,
        binding: VaultBiometricBinding, completed: (AndroidVaultBiometric.Outcome<VaultCiphertext>) -> Unit): VaultBiometricPending
    fun prepareUnwrapRoot(identity: VaultPersistentIdentity, slotId: String, generation: Long,
        persistedEnvelope: VaultCiphertext, title: CharSequence, cancelText: CharSequence,
        context: VaultOperationContext, binding: VaultBiometricBinding,
        completed: (AndroidVaultBiometric.Outcome<KeysetHandle>) -> Unit): VaultBiometricPending
}
internal interface VaultBiometricPending : AutoCloseable {
    fun ownsSystemPromptFocus(): Boolean = false
    fun start()
    fun observeDrain(): ExportRelease
    fun onDrain(listener: () -> Unit)
}
