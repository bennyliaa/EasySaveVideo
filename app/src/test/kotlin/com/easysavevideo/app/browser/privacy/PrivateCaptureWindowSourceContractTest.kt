package com.easysavevideo.app.browser.privacy

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Supplementary source wiring only. Production state transitions have separate executable tests. */
class PrivateCaptureWindowSourceContractTest {
    private fun source(path: String) = File("src/main/kotlin/com/easysavevideo/app/$path").readText()
    private val port get() = source("recording/OwnedPrivateRecordingPresentationPort.kt")
    private val guard get() = source("browser/privacy/PrivateWindowGuard.kt")
    private val root get() = source("browser/privacy/PrivateContentRoot.kt")
    private fun ordered(text: String, vararg parts: String) {
        var index = 0
        parts.forEach { part -> val next = text.indexOf(part, index)
            assertTrue("Missing/out of order: $part", next >= 0); index = next + part.length }
    }
    @Test fun continuousPolicyAndAcceptedLegacyRiskDoNotClearOtherAcceptanceGates() {
        assertTrue(port.contains("PrivateCaptureInteractionPolicy.CONTINUOUS_USER_AUTHORIZED"))
        assertTrue(port.contains("Build.VERSION.SDK_INT >= 31"))
        assertTrue(port.contains("PrivateCaptureRecentsProtection.LEGACY_RECENTS_RISK_ACCEPTED"))
        assertTrue(guard.contains("pendingGates.isEmpty()")); assertFalse(port.contains("setPendingGates("))
        assertFalse(port.contains("LEGACY_VALIDATED_ISOLATION"))
    }
    @Test fun setupEvidenceStillNeedsActualAppliedCompositionAndHardwareFrame() {
        val apply = port.substringAfter("fun compositionApplied(").substringBefore("private fun checkObserved")
        ordered(apply, "ui.stage != PrivateRecordingStage.ARMING", "value.state.acknowledgeComposition", "guard.acknowledgeCaptureComposition")
        val draw = root.substringAfter("override fun draw(canvas:").substringBefore("private fun hideContentAfterFailure")
        ordered(draw, "canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)", "protection.drawn(",
            "canvas.isHardwareAccelerated && isHardwareAccelerated", "onCoverSubmitting?.invoke(turn)",
            "val commit = Runnable", "main.post", "protection.commit(draw, currentIdentity", "observer.registerFrameCommitCallback(commit)")
    }
    @Test fun oneUseSetupOpeningAndContinuousOpeningBothRecheckBeforeFlagMutation() {
        val opening = guard.substringAfter("fun openCaptureException(").substringBefore("private fun captureSnapshot")
        ordered(opening, "value.capability !== capability", "value.consumed = true", "performPrivateCaptureOpening(",
            "safeCheck(stillAllowed)", "clearSecure = { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }")
        assertTrue(opening.contains("restore = { installProtectionBarrier() }"))
        val reveal = guard.substringAfter("fun revealProtectedPrivate(").substringBefore("fun hasVisiblePrivatePresentation")
        ordered(reveal, "val authorization = continuousAuthorization", "performPrivateCaptureOpening(",
            "continuousAuthorization === authorization && hasContinuousCaptureAuthorization", "clearSecure =")
    }
    @Test fun submittedWindowAttachmentNeverRetainsTheInitialBrowserTarget() {
        val attach = port.substringAfter("override fun attachContinuous(").substringBefore("override fun detachContinuous")
        ordered(attach, "isContinuousAuthorized(authorization)", "current?.let { end(it.id) }", "guard.attachContinuousCapture")
        assertFalse(attach.contains("target.session")); assertFalse(attach.contains("target.viewIdentity"))
        val invalidation = port.substringAfter("private fun invalidate(value:").substringBefore("private fun installObservers")
        ordered(invalidation, "protectIndependently()", "val submitted =", "privateCaptureSetupMayCancel(submitted)", "invalidateOwner(owner)")
        for (part in listOf("OnWindowFocusChangeListener", "OnLayoutChangeListener", "OnAttachStateChangeListener",
            "Lifecycle.Event.ON_PAUSE", "Lifecycle.Event.ON_STOP")) assertFalse(part, port.contains(part))
        assertTrue(port.contains("guard.afterOwnedCaptureProtectionSubmitted(value.identity)"))
    }
    @Test fun captureHasNormalInputWhileRetainedOldInputsStillRevokeOnTransitions() {
        val reveal = root.substringAfter("fun revealCapture(").substringBefore("fun revokeCaptureInput")
        assertTrue(reveal.contains("inputLifetime.publish(identity.presentation)"))
        assertTrue(root.contains("inputLifetime.revoke()")); assertTrue(root.contains("discardGestureRemainder"))
        for (old in listOf("captureBlockedView", "hitsCapturePage", "captureFocusBlocked")) assertFalse(old, root.contains(old))
        assertFalse(guard.contains("!insets.isVisible(WindowInsets.Type.ime())"))
    }
    @Test fun registeredDialogsInheritPixelAuthorityAndRestoreWithoutInventingNativeDrain() {
        assertTrue(guard.contains("registration.guard.attachContinuousCapture(value, isCurrent)"))
        assertTrue(guard.contains("registration.guard.detachContinuousCapture()"))
        assertTrue(guard.contains("child.attachContinuousCapture(authorization, isCurrent)"))
        assertTrue(guard.contains("continuousAuthorization = null; continuousAuthorizationCurrent = null"))
        assertFalse(guard.contains("captureDrained = true"))
        assertTrue(guard.contains("teardownIsComplete")); assertTrue(guard.contains("detachedEmptyDialogRoot"))
    }
    @Test fun repeatedAuthorizationAndDetachRecheckEveryRegisteredChildAndOwnHealth() {
        val attach = guard.substringAfter("fun attachContinuousCapture(").substringBefore("fun detachContinuousCapture(")
        assertFalse(attach.contains("hasContinuousCaptureAuthorization) return true"))
        ordered(attach, "dialogs.toList().map", "registration.guard.attachContinuousCapture(value, isCurrent)",
            "restorePrivateCaptureProtection(*actions.toTypedArray())",
            "protectionFailed = privateCaptureProtectionFailure(protectionFailed, restored)",
            "return restored && continuousAttachmentHealthy()")
        val detach = guard.substringAfter("fun detachContinuousCapture(").substringBefore("private var pendingGates")
        ordered(detach, "continuousAuthorization = null", "val actions: List<() -> Boolean>",
            "registration.guard.detachContinuousCapture()", "restorePrivateCaptureProtection(*actions.toTypedArray())",
            "protectionFailed = privateCaptureProtectionFailure(protectionFailed, restored)", "return restored && continuousAttachmentHealthy()")
    }

    @Test fun failedOwnershipReadbackStillAttemptsOldWindowIndependentProtection() {
        val barrier = guard.substringAfter("private fun installBarrier(").substringBefore("fun reservePrivatePresentation(")
        ordered(barrier, "try { ownedIdentity() } catch (_: Exception) { protectionFailed = true; null }",
            "identity ?: PrivateRootIdentity", "root.beginProtection(protectedIdentity)",
            "window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)", "ordinaryImportance.forEach",
            "activity.setRecentsScreenshotEnabled(false)")
    }

}
