package com.easysavevideo.app.vault

import com.easysavevideo.core.model.VaultRecoveryChoice
import org.junit.Assert.*
import org.junit.Test

/** Fixed public strings only. No runtime startup, entropy, key creation or authentication. */
class VaultCredentialDraftTest {
    private val password = "public-fixture-password"
    private fun draft() = VaultCredentialDraft().also { it.updatePassword(password); it.updateConfirmation(password) }
    private fun submitSetup(value: VaultCredentialDraft): VaultCredentialSubmission? = value.prepare(setup = true)

    @Test fun recoveryStartsUnsetAndSetupCannotSilentlyDecline() {
        val value = draft()
        assertNull(value.recoveryChoice)
        assertNull(submitSetup(value))
        assertEquals(VaultCredentialDraftError.RECOVERY_CHOICE_REQUIRED, value.error)
        assertFalse(value.retired)
        value.close()
    }
    @Test fun explicitRecoveryOptInPassesUnchangedAndDraftDoesNotClaimActivation() {
        val value = draft(); value.chooseRecovery(VaultRecoveryChoice.OPTED_IN)
        val submission = requireNotNull(submitSetup(value))
        try { assertEquals(VaultRecoveryChoice.OPTED_IN, submission.recoveryChoice); assertTrue(value.retired) }
        finally { submission.input.close() }
    }
    @Test fun explicitDeclineCreatesOnlyAnOwnedInputAndClearsDraft() {
        val value = draft(); value.chooseRecovery(VaultRecoveryChoice.DECLINED)
        val result = requireNotNull(submitSetup(value))
        try {
            assertEquals(VaultRecoveryChoice.DECLINED, result.recoveryChoice)
            assertEquals(VaultCredentialKind.PASSWORD, result.input.kind)
            val copy = result.input.copyCharacters()
            try { assertArrayEquals(password.toCharArray(), copy) } finally { copy.fill('\u0000') }
            assertTrue(value.retired); assertEquals("", value.passwordText()); assertEquals("", value.confirmationText())
            assertNull(value.recoveryChoice)
        } finally { result.input.close() }
    }
    @Test fun handoffAndRetirementCannotBeReusedForRepeatedClick() {
        val value = draft(); value.chooseRecovery(VaultRecoveryChoice.DECLINED)
        requireNotNull(submitSetup(value)).input.close()
        assertNull(submitSetup(value))
        value.updatePassword(password); value.updateConfirmation(password); value.chooseRecovery(VaultRecoveryChoice.DECLINED)
        assertNull(submitSetup(value)); assertEquals("", value.passwordText()); assertNull(value.recoveryChoice)
    }
    @Test fun unlockDoesNotInventARecoveryChoiceOrRequireConfirmation() {
        val value = VaultCredentialDraft(); value.updatePassword(password)
        val result = requireNotNull(value.prepare(setup = false))
        try { assertEquals(VaultRecoveryChoice.DECLINED, result.recoveryChoice); assertTrue(value.retired) }
        finally { result.input.close() }
    }
    @Test fun mismatchKeepsTheRequestRetryableAndNeverCreatesInput() {
        val value = draft(); value.chooseRecovery(VaultRecoveryChoice.DECLINED); value.updateConfirmation("other-public-fixture")
        assertNull(submitSetup(value)); assertEquals(VaultCredentialDraftError.PASSWORD_MISMATCH, value.error)
        assertFalse(value.retired)
        value.updateConfirmation(password)
        requireNotNull(submitSetup(value)).input.close()
    }
    @Test fun invalidProfileDoesNotBecomeSuccessAndCanBeCorrected() {
        val value = VaultCredentialDraft(); value.chooseRecovery(VaultRecoveryChoice.DECLINED)
        value.updatePassword("short"); value.updateConfirmation("short")
        assertNull(submitSetup(value)); assertEquals(VaultCredentialDraftError.PASSWORD_INVALID, value.error)
        assertFalse(value.retired)
        value.updatePassword(password); value.updateConfirmation(password)
        requireNotNull(submitSetup(value)).input.close()
    }
    @Test fun oversizedPasswordClearsTheOldFieldBeforeCopyAndCannotReuseIt() {
        for (size in listOf(257, 1_000_000)) {
            val value = draft(); value.chooseRecovery(VaultRecoveryChoice.DECLINED)
            value.updatePassword("a".repeat(size))
            assertEquals("", value.passwordText())
            assertEquals(VaultCredentialDraftError.PASSWORD_INVALID, value.error)
            assertNull(value.prepare(setup = true)); assertNull(value.prepare(setup = false))
            value.updateConfirmation(password)
            assertEquals(VaultCredentialDraftError.PASSWORD_INVALID, value.error)
            assertNull(value.prepare(setup = true))
            value.updatePassword(password)
            requireNotNull(value.prepare(setup = true)).input.close()
        }
    }
    @Test fun oversizedConfirmationClearsTheOldFieldAndCannotSubmitEarlierValidConfirmation() {
        for (size in listOf(257, 1_000_000)) {
            val value = draft(); value.chooseRecovery(VaultRecoveryChoice.DECLINED)
            value.updateConfirmation("a".repeat(size))
            assertEquals("", value.confirmationText())
            assertEquals(VaultCredentialDraftError.PASSWORD_INVALID, value.error)
            assertNull(value.prepare(setup = true))
            value.updatePassword(password)
            assertEquals(VaultCredentialDraftError.PASSWORD_INVALID, value.error)
            assertNull(value.prepare(setup = true))
            value.updateConfirmation(password)
            requireNotNull(value.prepare(setup = true)).input.close()
        }
    }
    @Test fun exactUtf16BoundIsKeptForBothSetupFieldsAndCryptoProfileStillDecides() {
        val value = VaultCredentialDraft(); val text = "\uD83D\uDD12".repeat(128)
        assertEquals(VaultCredentialInput.MAX_PASSWORD_CHARS, text.length)
        value.updatePassword(text); value.updateConfirmation(text); value.chooseRecovery(VaultRecoveryChoice.DECLINED)
        assertEquals(text, value.passwordText()); assertEquals(text, value.confirmationText())
        requireNotNull(value.prepare(setup = true)).input.close()
    }
    @Test fun completeProfileRejectsForbiddenLineBreaksAndMalformedSurrogates() {
        for (text in listOf("public-fixture\npassword", "public-fixture\u2028password", "public-fixture\uD800password")) {
            val value = VaultCredentialDraft(); value.updatePassword(text)
            assertNull(value.prepare(setup = false)); assertEquals(VaultCredentialDraftError.PASSWORD_INVALID, value.error)
            value.close()
        }
    }
    @Test fun fullSupplementaryProfileBoundaryIsNotAnIndependentUtf16LengthRule() {
        val value = VaultCredentialDraft(); val text = "\uD83D\uDD12".repeat(128)
        value.updatePassword(text)
        requireNotNull(value.prepare(setup = false)).input.close()
    }
    @Test fun normalizedProfileDoesNotSilentlyRewriteTheSubmittedInput() {
        val value = VaultCredentialDraft(); val text = "e\u0301".repeat(15)
        value.updatePassword(text)
        val result = requireNotNull(value.prepare(setup = false))
        try {
            val copy = result.input.copyCharacters()
            try { assertArrayEquals(text.toCharArray(), copy) } finally { copy.fill('\u0000') }
        } finally { result.input.close() }
    }
    @Test fun cancelTimeoutAndOldRequestRetirementAreIdempotent() {
        val old = draft(); old.close(); old.close()
        assertTrue(old.retired); assertEquals("", old.passwordText()); assertEquals("", old.confirmationText())
        assertNull(old.prepare(setup = false))
        val next = draft()
        old.updatePassword("late-public-fixture"); old.close()
        assertEquals(password, next.passwordText()); assertFalse(next.retired)
        next.close()
    }
    @Test fun oldInputCannotReadAfterHostClosesIt() {
        val value = draft(); val result = requireNotNull(value.prepare(setup = false))
        result.input.close(); result.input.close()
        try { result.input.copyCharacters(); fail("closed input must reject") }
        catch (failure: VaultOperationException) { assertEquals(VaultFailure.CANCELLED, failure.category) }
    }
    @Test fun diagnosticsNeverIncludeDraftOrInputContents() {
        val value = draft()
        assertFalse(value.toString().contains(password))
        val result = requireNotNull(value.prepare(setup = false))
        try { assertEquals("VaultCredentialInput(REDACTED)", result.input.toString()) }
        finally { result.input.close() }
    }
}
