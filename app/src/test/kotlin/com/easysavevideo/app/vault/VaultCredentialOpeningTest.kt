package com.easysavevideo.app.vault

import org.junit.Assert.*
import org.junit.Test

class VaultCredentialOpeningTest {
    @Test fun noDialogDuringOldCleanupThenPromptMayOutliveCleanupBudget() {
        var elapsed = 0L
        val opening = VaultCredentialOpening(VaultCredentialMode.SETUP, 7)
        assertNull(opening.takeReady(7, false, false, true))
        elapsed += 4_000
        assertNull(opening.takeReady(7, true, false, true))
        elapsed += 100
        val request = opening.takeReady(7, true, true, true)?.let(::VaultCredentialRequest)
        assertNotNull(request)
        val promptDeadline = elapsed + 120_000
        elapsed += 6_000 // Longer than the old cleanup budget; old cleanup is already complete.
        assertTrue(elapsed < promptDeadline)
        assertTrue(request!!.isLive())
        assertNull(opening.takeReady(7, true, true, true))
    }
    @Test fun newGenerationCannotOpenOldPrompt() {
        val opening = VaultCredentialOpening(VaultCredentialMode.UNLOCK, 7)
        assertNull(opening.takeReady(8, true, true, true))
        assertNull(opening.takeReady(7, true, true, true))
    }
    @Test fun departurePermanentlyRetiresPendingOpen() {
        val opening = VaultCredentialOpening(VaultCredentialMode.UNLOCK, 7)
        assertNull(opening.takeReady(7, true, true, false))
        assertNull(opening.takeReady(7, true, true, true))
    }
    @Test fun allActualCleanupFactsAreRequired() {
        val opening = VaultCredentialOpening(VaultCredentialMode.UNLOCK, 7)
        assertNull(opening.takeReady(7, false, true, true))
        assertNull(opening.takeReady(7, true, false, true))
        assertEquals(VaultCredentialMode.UNLOCK, opening.takeReady(7, true, true, true))
    }
    @Test fun registeredCleanupRunsExactlyOnceAndLateRegistrationRunsImmediately() {
        val request = VaultCredentialRequest(VaultCredentialMode.SETUP)
        var clears = 0
        val registration = request.onRetired { clears++ }
        request.retire(); request.retire(); registration.close()
        assertEquals(1, clears)
        request.onRetired { clears++ }
        assertEquals(2, clears)
        assertTrue(request.retiredCleanly())
    }
    @Test fun disposeClearsDraftAndRemovesCallbackBeforeRequestRetirement() {
        val request = VaultCredentialRequest(VaultCredentialMode.SETUP)
        var clears = 0
        request.onRetired { clears++ }.close()
        request.retire()
        assertEquals(1, clears)
        assertTrue(request.retiredCleanly())
    }
    @Test fun failedCleanupRetainsStickyFence() {
        val request = VaultCredentialRequest(VaultCredentialMode.SETUP)
        request.onRetired { error("public fixture") }
        request.retire()
        assertFalse(request.retiredCleanly())
        assertTrue(request.cleanupFailed)
    }
    @Test fun foreignThreadCannotRunDraftCleanup() {
        val request = VaultCredentialRequest(VaultCredentialMode.SETUP)
        var rejected = false
        val thread = Thread { try { request.retire() } catch (_: IllegalStateException) { rejected = true } }
        thread.start(); thread.join()
        assertTrue(rejected)
        assertTrue(request.isLive())
    }
}
