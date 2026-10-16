package com.easysavevideo.app.vault

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring only. It makes no claim about actual system focus, secure pixels or Activity behavior. */
class VaultBiometricHostSourceContractTest {
    private fun source(path: String): String = File(System.getProperty("esv.vault.host.source") ?: "src/main/kotlin/com/easysavevideo/app")
        .resolve(path).readText()
    @Test fun gateAndSingleOwnerRemainClosedAndShared() {
        val entry = source("vault/AndroidVaultRuntimeEntry.kt")
        assertTrue(entry.contains("const val ENABLED: Boolean = false"))
        assertTrue(entry.indexOf("if (!ENABLED) return null") < entry.indexOf("val keystore = AndroidVaultKeystore()"))
        assertEquals(1, "AndroidVaultAuthentication(".toRegex(RegexOption.LITERAL).findAll(entry).count())
        assertTrue(entry.contains("AndroidVaultBiometric(activity, keystore, lane, timers, clock, platformExecutor = ui)"))
        assertTrue(entry.contains("owner?.let { return it }")); assertFalse(entry.contains("owner = null"))
    }
    @Test fun explicitHostButtonsAreOnlyUiPromptRoute() {
        val ui = source("vault/VaultRuntimeUi.kt")
        for (mode in listOf("BIOMETRIC_UNLOCK", "BIOMETRIC_ENROLL", "BIOMETRIC_REVOKE")) assertTrue(ui.contains("VaultCredentialMode.$mode"))
        assertFalse(ui.contains("BiometricPrompt")); assertFalse(ui.contains("prepareBiometric"))
        val host = source("vault/VaultProtectedHost.kt")
        assertTrue(host.contains("runtime.unlockBiometric(factory(VaultBiometricPurpose.UNLOCK, null))"))
        assertTrue(host.contains("runtime.unlockForBiometric(submission.input, biometric, factory)"))
        assertTrue(host.contains("pending != null && runtime.hostCleanupComplete()"))
        assertTrue(host.contains("request.biometricWorkflow?.let(runtime::cancelBiometricWorkflow)"))
    }
    @Test fun backgroundHostPredicateUsesAtomicsAndMainRechecksExactAttachment() {
        val host = source("vault/VaultProtectedHost.kt").substringAfter("private fun biometricHostBinding()")
        assertTrue(host.contains("if (!valid.get() || actionEpoch.get() != epoch) false"))
        assertTrue(host.contains("else if (Looper.myLooper() != Looper.getMainLooper()) true"))
        assertTrue(host.contains("claim === exact && generation == hostGeneration"))
        assertTrue(host.contains("root.isAttachedToWindow && root.windowToken != null"))
    }
    @Test fun nativeFocusWindowRequiresSubmittedExactPromptAndAlreadyCoveredContent() {
        val main = source("MainActivity.kt").substringAfter("override fun onWindowFocusChanged")
        assertTrue(main.contains("vaultHost?.windowFocusLost()"))
        assertTrue(main.contains("vaultHost?.canRetryPresentationAfterFocus() != false"))
        val host = source("vault/VaultProtectedHost.kt").substringAfter("fun windowFocusLost()").substringBefore("fun background()")
        assertTrue(host.contains("root.isCovered && owns(current)"))
        assertTrue(host.contains("runtime.systemPromptOwnsFocus(current, activity, generation)"))
        assertTrue(host.contains("surfaceCovered()"))
        val runtime = source("vault/VaultRuntime.kt").substringAfter("fun systemPromptOwnsFocus").substringBefore("fun biometricCleanupComplete")
        assertTrue(runtime.contains("record.binding.hostIdentity !== host"))
        assertTrue(runtime.contains("record.binding.platformHost !== activity"))
        assertTrue(runtime.contains("pending.ownsSystemPromptFocus()"))
    }
    @Test fun credentialWorkHoldsExistingCoverAcrossQueuedPrivateReveal() {
        val owner = source("browser/privacy/PrivateMainPresentationOwner.kt")
        assertTrue(owner.contains("if (vaultClaim.host.holdsBiometricCover()) return false"))
        assertTrue(owner.contains("value.vaultClaim?.host?.holdsBiometricCover() == true"))
        val runtime = source("vault/VaultRuntime.kt").substringAfter("fun biometricPrivateCoverRequired").substringBefore("fun systemPromptOwnsFocus")
        assertTrue(runtime.contains("biometricPhase != BiometricPhase.PASSWORD_PROMPT"))
        assertTrue(runtime.contains("biometricOperations.isNotEmpty()"))
    }
    @Test fun captureStillRejectsVaultAndNoOrdinaryRecordingIsStopped() {
        val host = source("vault/VaultProtectedHost.kt")
        assertTrue(host.contains("guard.hasContinuousCaptureAuthorization || !captureDrained()"))
        assertTrue(host.contains("!guard.hasContinuousCaptureAuthorization && captureDrained()"))
        assertFalse(host.contains("stopRecording(")); assertFalse(host.contains("stopCapture("))
    }
    @Test fun localizedPromptCopyIsBoundedAndAvailabilityHasFiniteExplanation() {
        val entry = source("vault/AndroidVaultRuntimeEntry.kt")
        for (name in listOf("enable", "verify", "unlock")) assertTrue(entry.contains("R.string.vault_biometric_prompt_$name"))
        val ui = source("vault/VaultRuntimeUi.kt")
        assertTrue(ui.contains("VaultBiometricAvailability.KEY_UNAVAILABLE -> R.string.vault_biometric_key_unavailable"))
        assertTrue(ui.contains("VaultBiometricAvailability.TEMPORARY_LOCKOUT -> R.string.vault_biometric_temporary_lockout"))
        assertTrue(ui.contains("VaultBiometricAvailability.PERMANENT_LOCKOUT -> R.string.vault_biometric_permanent_lockout"))
    }
}
