package com.easysavevideo.app.vault

import org.junit.Assert.*
import org.junit.Test

/** Public test vectors only. Simulates abandoned composition without any Android execution. */
class VaultRecoveryInputOwnershipTest {
    @Test fun abandonedCompositionCannotStealDisplayHolderBeforeRetirement() {
        val holder = VaultRecoveryInput(CharArray(43) { 'A' })
        val request = VaultCredentialRequest(VaultCredentialMode.RECOVERY_DISPLAY, display = holder)
        // A speculative composition may read a temporary copy; it never takes ownership of holder.
        val copy = requireNotNull(request.copyDisplayCharacters())
        try { assertEquals(43, copy.size) } finally { copy.fill('\u0000') }
        // No onRetired registration and no onDispose: composition was abandoned before commit.
        request.retire()
        assertNull(request.copyDisplayCharacters())
        assertThrows(VaultOperationException::class.java) { holder.copyCharacters() }
        assertTrue(request.retiredCleanly()); assertTrue(copy.all { it == '\u0000' })
    }
    @Test fun displayClearRetiresSourceEvenBeforeWholeRequest() {
        val holder = VaultRecoveryInput(CharArray(43) { 'A' })
        val request = VaultCredentialRequest(VaultCredentialMode.RECOVERY_DISPLAY, display = holder)
        request.clearDisplayInput(); request.clearDisplayInput()
        assertNull(request.copyDisplayCharacters()); assertTrue(request.isLive())
        assertThrows(VaultOperationException::class.java) { holder.copyCharacters() }
        request.retire(); assertTrue(request.retiredCleanly())
    }
    @Test fun recoveryInputBoundsAndRedactsAndCopiesCannotMutateSource() {
        assertThrows(IllegalArgumentException::class.java) { VaultRecoveryInput(CharArray(44)) }
        val chars = CharArray(43) { 'A' }; val holder = VaultRecoveryInput(chars); chars.fill('B')
        val copy = holder.copyCharacters(); assertTrue(copy.all { it == 'A' }); copy.fill('C')
        assertFalse(holder.toString().contains("AAA")); holder.close(); holder.close()
        assertThrows(VaultOperationException::class.java) { holder.copyCharacters() }
    }
    @Test fun recoveryResetRequiresCanonicalCodeAndIndependentMatchingPasswordPair() {
        val draft = VaultCredentialDraft()
        draft.updateRecovery("A".repeat(43)); draft.updatePassword("public-password-fixture")
        draft.updateConfirmation("different-public-password")
        assertNull(draft.prepareRecoveryReset()); assertEquals(VaultCredentialDraftError.PASSWORD_MISMATCH, draft.error)
        draft.updateConfirmation("public-password-fixture")
        val result = requireNotNull(draft.prepareRecoveryReset())
        assertTrue(draft.retired); assertEquals("", draft.recoveryText()); assertEquals("", draft.passwordText())
        assertEquals("", draft.confirmationText()); result.close()
        assertThrows(VaultOperationException::class.java) { result.input.copyCharacters() }
        assertThrows(VaultOperationException::class.java) { result.password.copyCharacters() }
    }
    @Test fun confirmationRejectsWhitespaceNoncanonicalAndOverlongInput() {
        for (value in listOf("A".repeat(42), "A".repeat(42) + "B", " " + "A".repeat(43), "A".repeat(44))) {
            val draft = VaultCredentialDraft(); draft.updateRecovery(value)
            assertNull(draft.prepareRecoveryConfirmation()); assertEquals(VaultCredentialDraftError.RECOVERY_INVALID, draft.error)
            draft.close()
        }
    }
}
