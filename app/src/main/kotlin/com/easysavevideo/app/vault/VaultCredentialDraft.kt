package com.easysavevideo.app.vault

import com.easysavevideo.app.vault.crypto.VaultCryptoProfile
import com.easysavevideo.core.model.VaultRecoveryChoice

internal enum class VaultCredentialDraftError { PASSWORD_INVALID, PASSWORD_MISMATCH, RECOVERY_CHOICE_REQUIRED, RECOVERY_UNAVAILABLE, RECOVERY_INVALID }

/** Ownership passes to the host, which closes the input on rejection or hands it to the runtime. */
internal class VaultCredentialSubmission(val input: VaultCredentialInput, val recoveryChoice: VaultRecoveryChoice)

internal class VaultRecoveryResetSubmission(val input: VaultRecoveryInput, val password: VaultCredentialInput) : AutoCloseable {
    override fun close() { input.close(); password.close() }
    override fun toString(): String = "VaultRecoveryResetSubmission(REDACTED)"
}

/**
 * Main-thread-only, request-local, single-use credential draft. No Android, persistence, saved state or clipboard.
 * Owned arrays are cleared synchronously on retirement, cancellation and successful handoff.
 * Compose/IME/JVM String copies cannot be proven erased; these values are never saved or logged.
 */
internal class VaultCredentialDraft : AutoCloseable {
    private var recovery = CharArray(0)
    private var recoveryOverLimit = false
    private var password = CharArray(0)
    private var confirmation = CharArray(0)
    private var passwordOverLimit = false
    private var confirmationOverLimit = false
    var recoveryChoice: VaultRecoveryChoice? = null; private set
    var error: VaultCredentialDraftError? = null; private set
    var retired: Boolean = false; private set

    fun passwordText(): String = if (retired) "" else String(password)
    fun recoveryText(): String = if (retired) "" else String(recovery)
    fun confirmationText(): String = if (retired) "" else String(confirmation)

    fun updateRecovery(value: String) {
        if (retired) return
        recovery.fill('\u0000'); recovery = CharArray(0)
        recoveryOverLimit = value.length > 43
        if (!recoveryOverLimit) recovery = value.toCharArray()
        error = if (recoveryOverLimit) VaultCredentialDraftError.RECOVERY_INVALID else null
    }
    fun updatePassword(value: String) {
        if (retired) return
        password.fill('\u0000'); password = CharArray(0)
        passwordOverLimit = value.length > VaultCredentialInput.MAX_PASSWORD_CHARS
        if (!passwordOverLimit) password = value.toCharArray()
        error = if (passwordOverLimit || confirmationOverLimit) VaultCredentialDraftError.PASSWORD_INVALID else null
    }
    fun updateConfirmation(value: String) {
        if (retired) return
        confirmation.fill('\u0000'); confirmation = CharArray(0)
        confirmationOverLimit = value.length > VaultCredentialInput.MAX_PASSWORD_CHARS
        if (!confirmationOverLimit) confirmation = value.toCharArray()
        error = if (passwordOverLimit || confirmationOverLimit) VaultCredentialDraftError.PASSWORD_INVALID else null
    }
    fun chooseRecovery(choice: VaultRecoveryChoice) {
        if (retired) return
        recoveryChoice = choice
        error = if (choice == VaultRecoveryChoice.UNDECIDED) VaultCredentialDraftError.RECOVERY_CHOICE_REQUIRED else null
    }

    /** Same crypto-owned complete profile, without derivation or independently invented UI rules. */
    fun prepare(setup: Boolean): VaultCredentialSubmission? = preparePassword(setup, setup)
    private fun preparePassword(confirm: Boolean, choose: Boolean): VaultCredentialSubmission? {
        if (retired) return null
        if (passwordOverLimit || confirm && confirmationOverLimit) {
            error = VaultCredentialDraftError.PASSWORD_INVALID; return null
        }
        if (choose && recoveryChoice == null) {
            error = VaultCredentialDraftError.RECOVERY_CHOICE_REQUIRED; return null
        }
        if (choose && recoveryChoice !in setOf(VaultRecoveryChoice.DECLINED, VaultRecoveryChoice.OPTED_IN)) {
            error = VaultCredentialDraftError.RECOVERY_CHOICE_REQUIRED; return null
        }
        if (confirm && !password.contentEquals(confirmation)) {
            error = VaultCredentialDraftError.PASSWORD_MISMATCH; return null
        }
        val input = try { VaultCredentialInput(VaultCredentialKind.PASSWORD, password) }
        catch (_: IllegalArgumentException) { error = VaultCredentialDraftError.PASSWORD_INVALID; return null }
        try {
            val copy = input.copyCharacters()
            try {
                val normalized = VaultCryptoProfile.normalizeCredential(copy, VaultCryptoProfile.CredentialKind.PASSWORD)
                try { /* Profile validation only; runtime remains the authoritative validator. */ }
                finally { normalized.fill('\u0000') }
            } finally { copy.fill('\u0000') }
        } catch (_: Exception) {
            input.close(); error = VaultCredentialDraftError.PASSWORD_INVALID; return null
        }
        val choice = if (choose) checkNotNull(recoveryChoice) else VaultRecoveryChoice.DECLINED
        close()
        return VaultCredentialSubmission(input, choice)
    }

    private fun validatedRecovery(): VaultRecoveryInput? {
        if (retired) return null
        if (recoveryOverLimit) { error = VaultCredentialDraftError.RECOVERY_INVALID; return null }
        try { VaultCryptoProfile.decodeRecovery(recovery).fill(0) }
        catch (_: Exception) { error = VaultCredentialDraftError.RECOVERY_INVALID; return null }
        return VaultRecoveryInput(recovery)
    }
    fun prepareRecoveryConfirmation(): VaultRecoveryInput? {
        val result = validatedRecovery() ?: return null
        close(); return result
    }
    fun prepareRecoveryReset(): VaultRecoveryResetSubmission? {
        val code = validatedRecovery() ?: return null
        val password = preparePassword(confirm = true, choose = false)
        if (password == null) { code.close(); return null }
        return VaultRecoveryResetSubmission(code, password.input)
    }
    override fun close() {
        recovery.fill('\u0000'); recovery = CharArray(0); recoveryOverLimit = false
        password.fill('\u0000'); confirmation.fill('\u0000')
        password = CharArray(0); confirmation = CharArray(0)
        passwordOverLimit = false; confirmationOverLimit = false
        recoveryChoice = null; error = null; retired = true
    }
    override fun toString(): String = "VaultCredentialDraft(REDACTED)"
}
