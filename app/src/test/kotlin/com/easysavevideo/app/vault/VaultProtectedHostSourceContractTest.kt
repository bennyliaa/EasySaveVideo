package com.easysavevideo.app.vault

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural regression checks only; native frame/input proof requires device acceptance. */
class VaultProtectedHostSourceContractTest {
    private fun source(path: String): String {
        val app = File(System.getProperty("esv.vault.host.source") ?: "src/main/kotlin/com/easysavevideo/app").absoluteFile
        return app.resolve(path).readText()
    }
    @Test fun productionConstructionAndRouteStayGated() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("if (AndroidVaultRuntimeEntry.ENABLED) AndroidVaultRuntimeEntry.getOrCreate"))
        assertTrue(main.contains("destination == EsvDestination.VAULT && (!AndroidVaultRuntimeEntry.ENABLED"))
        assertTrue(main.contains("destination == EsvDestination.VAULT && vaultHost?.attach() != true"))
        assertTrue(main.contains("if (AndroidVaultRuntimeEntry.ENABLED && vaultPresentation)"))
    }
    @Test fun settingsCallbackReachesBothDetailLayouts() {
        val settings = source("ui/shell/EsvSettingsScreen.kt")
        assertEquals(2, "privacy,entitlements,saveDestination,onVault){notices=true}".toRegex(RegexOption.LITERAL).findAll(settings).count())
        assertTrue(settings.contains("onVault:(()->Unit)?,onNotices:()->Unit)"))
        assertTrue(settings.contains("VaultRuntimeAvailability(onVault)"))
    }
    @Test fun mainUsesSameAppliedSourceForSensitiveConsumers() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("val privateMode = presentation?.sensitive"))
        assertTrue(main.contains("LocalPrivatePresentation provides privateMode"))
        assertTrue(main.contains("PrivateInputBoundary(privateMode"))
        assertTrue(main.contains("CapturedPrivateActions(privateMode"))
        assertTrue(main.contains("privatePresentationOwner.isCurrentSource(presentation)"))
    }
    @Test fun vaultDispatchDoesNotUseBrowserOnlyRouteWhitelist() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("vaultPresentation && route != EsvDestination.VAULT"))
        assertTrue(main.contains("presentation?.claim != null && !canRetainPrivateRoute(route)"))
        assertFalse(main.contains("privateMode && !canRetainPrivateRoute(route)"))
    }
    @Test fun backgroundCoverDoesNotReuseForegroundRevealPredicate() {
        val applied = source("browser/privacy/PrivateMainPresentationOwner.kt")
        assertTrue(applied.contains("acknowledgeHostCover(turn) { coveredCompositionCurrent(value) }"))
        assertFalse(applied.substringAfter("private fun coveredCompositionCurrent").substringBefore("private fun appliedCurrent").contains("foreground()"))
        assertTrue(applied.substringAfter("private fun appliedCurrent").substringBefore("/**").contains("foreground()"))
    }
    @Test fun screenOffRequiresActualKeyguardLockedReadback() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("intent?.action == Intent.ACTION_SCREEN_OFF &&"))
        assertTrue(main.contains("?.isDeviceLocked == true"))
        assertTrue(main.contains("vaultLockObserver?.let { unregisterReceiver(it) }"))
    }
    @Test fun vaultHasNoBrowserCaptureException() {
        val applied = source("browser/privacy/PrivateMainPresentationOwner.kt")
        assertEquals(2, "capturePolicy = PrivateCapturePolicy.FORBIDDEN".toRegex(RegexOption.LITERAL).findAll(applied).count())
        assertTrue(applied.contains("trackInputRetirement = true"))
        assertTrue(source("vault/VaultProtectedHost.kt").contains("!guard.hasContinuousCaptureAuthorization"))
    }
    @Test fun capturePolicyDefaultPreservesBrowserPath() {
        assertTrue(source("browser/privacy/PrivateWindowGuard.kt").contains("capturePolicy: PrivateCapturePolicy = PrivateCapturePolicy.BROWSER_COMPATIBLE"))
        val dialog = source("ui/PrivateAppDialog.kt")
        assertTrue(dialog.contains("trackInputRetirement: Boolean = false"))
        assertTrue(dialog.contains("capturePolicy: PrivateCapturePolicy = PrivateCapturePolicy.BROWSER_COMPATIBLE"))
    }
    @Test fun coverReceiptWaitsRealInputAndDialogsButNotOrdinaryRelease() {
        val host = source("vault/VaultProtectedHost.kt")
        assertTrue(host.contains("!guard.hasRegisteredPrivateDialogs"))
        assertTrue(host.contains("rootTicket?.isRetired == true"))
        assertTrue(host.contains("coveredGeneration == generation && hostResourcesRetired()"))
        assertFalse(host.substringAfter("fun hierarchyChanged()").substringBefore("fun ordinaryReleaseAllowed()").contains("ordinaryReleaseAllowed()"))
        assertTrue(host.contains("root.isAttachedToWindow && root.windowToken == null"))
    }
    @Test fun activityDetachesWithoutClosingAppRuntime() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("vaultHost?.detach()"))
        assertFalse(main.contains("vaultHost?.runtime?.close"))
        assertFalse(source("vault/VaultProtectedHost.kt").contains("runtime.close()"))
    }
    @Test fun retiredRequestCallbacksAreMainSerialAndImmediate() {
        val request = source("vault/VaultCredentialRequest.kt")
        assertTrue(request.contains("Thread.currentThread() === ownerThread"))
        assertTrue(request.contains("if (live) cleanups += it else it.close()"))
        assertTrue(request.contains("cleanupFailed = true"))
    }
    @Test fun nativeDialogReceiptCannotUseRevokedBoolean() {
        val dialog = source("ui/PrivateAppDialog.kt").substringAfter("private fun teardownReadback()")
            .substringBefore("private fun releaseDetachedOwner()")
        assertTrue(dialog.contains("inputRetirement?.isRetired == true"))
        assertFalse(dialog.contains("isAdmitted"))
    }
    @Test fun submissionHasRealCleanupBeforeRuntimeDispatch() {
        val host = source("vault/VaultProtectedHost.kt")
        assertTrue(host.contains("pending != null && runtime.hostCleanupComplete()"))
        assertTrue(host.contains("SystemClock.elapsedRealtime() >= promptDeadline ||"))
        assertTrue(host.contains("runtime.setupPassword(submission.input, submission.choice)"))
        assertTrue(host.contains("runtime.unlockPassword(submission.input)"))
        assertTrue(host.contains("recoveryChoice !in setOf(VaultRecoveryChoice.DECLINED, VaultRecoveryChoice.OPTED_IN)"))
    }
    @Test fun vaultAuxiliaryDoesNotBecomeBottomRoot() {
        val destination = source("ui/shell/EsvDestination.kt")
        assertTrue(destination.contains("VAULT(\"vault\")"))
        assertTrue(destination.contains("EDITOR, VAULT -> LIBRARY"))
        assertTrue(destination.contains("val roots = listOf(DISCOVER, TASKS, RECORD, LIBRARY)"))
        assertTrue(source("ui/shell/navigation/RootNavigation.kt").contains("\"editor\", \"vault\""))
    }
}
