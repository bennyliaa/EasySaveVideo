package com.easysavevideo.app.browser.privacy

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Source wiring checks ONLY. These do not execute Android View/Compose/IME/WindowManager and do
 * not establish Autofill provider exclusion, frame ordering, owner lookup, or device acceptance.
 */
class PrivateWindowRootSourceContractTest {
    private fun source(name: String) = File("src/main/kotlin/com/easysavevideo/app/$name").readText()
    private val root get() = source("browser/privacy/PrivateContentRoot.kt")
    private val guard get() = source("browser/privacy/PrivateWindowGuard.kt")
    private val activity get() = source("MainActivity.kt")
    private fun ordered(value: String, vararg pieces: String) {
        var after = 0
        pieces.forEach { piece ->
            val at = value.indexOf(piece, after)
            assertTrue("Missing/out of order: $piece", at >= 0)
            after = at + piece.length
        }
    }
    @Test fun protectedStructureOverridesDoNotTraverseChildrenEvenWithIncludeNotImportant() {
        val autofill = root.substringAfter("override fun dispatchProvideAutofillStructure").substringBefore("override fun dispatchProvideStructure")
        ordered(autofill, "if (!protection.suppressStructure)", "super.dispatchProvideAutofillStructure(structure, flags); return",
            "structure.setAutofillId(autofillId)", "structure.setAutofillType(View.AUTOFILL_TYPE_NONE)", "structure.setChildCount(0)")
        assertFalse(autofill.contains("flags and")); assertFalse(autofill.contains("setText("))
        val assist = root.substringAfter("override fun dispatchProvideStructure").substringBefore("override fun draw")
        ordered(assist, "if (!protection.suppressStructure)", "super.dispatchProvideStructure(structure); return", "structure.setChildCount(0)")
    }
    @Test fun realCoverDrawPrecedesHardwareRegistrationAndCommitIsOwnerThreadFenced() {
        val draw = root.substringAfter("override fun draw(canvas:").substringBefore("private fun hideContentAfterFailure")
        ordered(draw, "canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)", "protection.drawn(turn, identity, isAttachedToWindow",
            "canvas.isHardwareAccelerated && isHardwareAccelerated", "val commit = Runnable", "main.post",
            "protection.commit(draw, currentIdentity, isAttachedToWindow, isHardwareAccelerated)", "observer.registerFrameCommitCallback(commit)")
        assertFalse(draw.contains("OnPreDrawListener")); assertTrue(draw.contains("protection.registrationUnavailable(draw)"))
        assertTrue(root.contains("protection.invalidate(); cancelFrameCallback(); revokeLocalInput()"))
        assertTrue(root.contains("protection.close(); cancelFrameCallback(); revokeLocalInput()"))
    }
    @Test fun inputEpochIsRevokedBeforeCancellingGesturesAndClearingFocus() {
        val begin = root.substringAfter("fun beginProtection(").substringBefore("fun releaseInitialOrdinary")
        ordered(begin, "inputLifetime.revoke()", "protection.begin(identity)", "revokeLocalInput()", "onInputRevoked?.invoke(protection.generation)",
            "cancelPendingInputEvents()", "MotionEvent.ACTION_CANCEL", "super.dispatchTouchEvent(cancel)", "findFocus()?.clearFocus()")
        assertTrue(root.contains("descendantFocusability = FOCUS_BLOCK_DESCENDANTS"))
        assertTrue(root.contains("discardGestureRemainder")); assertTrue(root.contains("blockedKeys.addAll(heldKeys)"))
        for (name in listOf("dispatchGenericMotionEvent", "dispatchTrackballEvent", "dispatchHoverEvent", "dispatchKeyEventPreIme", "dispatchKeyShortcutEvent")) {
            assertTrue(name, root.contains("override fun $name"))
        }
        assertTrue(guard.contains("hideSoftInputFromWindow(decor.windowToken, 0)"))
        assertTrue(guard.contains("HOST_INPUT_PREDICATE, RETAINED_INPUT_CONNECTION"))
    }
    @Test fun exactRealWindowHierarchyAndIndependentPlatformControlsAreUsed() {
        for (part in listOf("activity.window !== window", "window.decorView !== decor", "root.parent !== content",
            "content.getChildAt(0) !== root", "root.windowToken === decor.windowToken", "listOf(decor, content, root)",
            "it.view.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS",
            "it.view.importantForContentCapture == View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS",
            "captureSetting.protect { captureManager.setContentCaptureEnabled(it) }", "activity.setRecentsScreenshotEnabled(false)",
            "PrivateRecentsCapability.LEGACY_EVIDENCE_UNAVAILABLE", "AutofillManager::class.java)?.cancel()")) assertTrue(part, guard.contains(part))
        assertFalse(guard.contains("LEGACY_VALIDATED_ISOLATION")); assertFalse(guard.contains("setExcludeFromRecents"))
        assertFalse(guard.contains("removeData("))
        assertFalse(guard.contains("isContentCaptureEnabled"))
        assertTrue(activity.contains("ordinaryContentCaptureOptIn = true"))
        assertTrue(guard.contains("captureSetting.restoreOrdinary { captureManager?.setContentCaptureEnabled(it) }"))
    }
    @Test fun rootInstallationKeepsOneComposeViewPublicOwnersInsetsAndDisposal() {
        ordered(activity, "WindowCompat.setDecorFitsSystemWindows(window, false)", "val privateContentRoot = PrivateContentRoot(this)",
            "appComposeView = ComposeView(this)", "ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed",
            "privateContentRoot.addView(appComposeView", "setContentView(privateContentRoot)",
            "privateWindowGuard = PrivateWindowGuard", "appComposeView.setContent {")
        assertFalse(activity.contains("import androidx.activity.compose.setContent"))
        assertFalse(activity.contains("Recomposer("))
        assertTrue(root.contains("fitsSystemWindows = false"))
        for (owner in listOf("findViewTreeLifecycleOwner()", "findViewTreeViewModelStoreOwner()", "findViewTreeSavedStateRegistryOwner()")) assertTrue(guard.contains(owner))
        val destroy = activity.substringAfter("override fun onDestroy()")
        ordered(destroy, "privateRecording.close()", "browser.close()", "privateWindowGuard.close()", "appComposeView.disposeComposition()", "super.onDestroy()")
    }
    @Test fun ordinaryStartupAndRealDepartureRemainSeparateWhileCaptureExceptionStaysDisabled() {
        assertTrue(guard.contains("private var pendingGates = PrivateWindowPendingGate.entries.toSet()"))
        val reserve = guard.substringAfter("fun reservePrivatePresentation(").substringBefore("/** Local draw")
        ordered(reserve, "privateContentReserved = true", "ordinaryCurrent = null", "root.reservePrivate()", "installProtectionBarrier()")
        val startup = guard.substringAfter("private fun releaseInitialOrdinaryIfSafe()").substringBefore("fun setPendingGates")
        ordered(startup, "ordinaryReleased || privateContentReserved || protectionFailed", "ordinaryCurrent?.invoke() == true",
            "root.releaseInitialOrdinary", "restoreOrdinaryPlatformPolicy()")
        val departure = guard.substringAfter("fun releaseOrdinaryPresentation(").substringBefore("private fun restoreOrdinaryPlatformPolicy")
        ordered(departure, "hasRegisteredPrivateDialogs", "proof.readback()", "readback.complete",
            "root.releasePresentedOrdinary", "restoreOrdinaryPlatformPolicy()")
        val exception = guard.substringAfter("fun openException()").substringBefore("fun lifecycleReadiness()")
        ordered(exception, "installProtectionBarrier()", "return false")
        assertFalse(exception.contains("clearFlags"))
    }
    @Test fun privateRevealKeepsProtectionAndAllowsOnlyExplicitContinuousPixelAuthority() {
        val reveal = guard.substringAfter("fun revealProtectedPrivate(").substringBefore("fun hasVisiblePrivatePresentation")
        for (readback in listOf("targetIdentity !== claim.target", "presentationIdentity !== claim.presentation",
            "safeCheck(claim.isCurrent)", "safeCheck(claim.compositionIsCurrent)",
            "safeCheck(claim.captureExceptionInactive)", "expectedAttachment(claim)", "root.revealProtectedPrivate")) {
            assertTrue(readback, reveal.contains(readback))
        }
        assertTrue(reveal.contains("continuousAuthorization === authorization && hasContinuousCaptureAuthorization"))
        assertTrue(reveal.contains("performPrivateCaptureOpening(")); assertFalse(reveal.contains("restoreOrdinary"))
        assertFalse(reveal.contains("PrivateCaptureSurfaceEvidence"))
        val rootReveal = root.substringAfter("fun revealProtectedPrivate(").substringBefore("fun releasePresentedOrdinary")
        ordered(rootReveal, "protection.revealProtectedPrivate", "PrivateRevealResult.REVEALED", "descendantFocusability = ordinaryDescendantFocusability",
            "inputLifetime.publish(identity.presentation)")
    }
    @Test fun registeredDialogsHaveExactOwnedWindowAndTheirOwnFocusAndInputLifetime() {
        val register = guard.substringAfter("fun registerPrivateDialog(").substringBefore("fun isRegisteredPrivateDialog")
        ordered(register, "dialogRoot.childCount != 0", "PrivateWindowGuard(activity, dialogRoot",
            "ownedDialogWindow = dialogWindow", "child.reservePrivatePresentation(request, request)", "dialogs.add(it)")
        assertTrue(guard.contains("decor.hasWindowFocus()"))
        assertTrue(root.contains("val inputLifetime = PrivateInputLifetime(this)"))
        assertTrue(guard.contains("attempt(it.onOwnerClosed)"))
        assertTrue(guard.contains("registration.owner === this && registration in dialogs"))
    }
    @Test fun failedOrAttachedDialogTeardownRemainsQuarantinedAndAllChildrenAreAttempted() {
        val unregister = guard.substringAfter("fun unregisterPrivateDialog(").substringBefore("private val closeComplete")
        ordered(unregister, "safeCheck(registration.teardownIsComplete)", "registration.guard::detachedEmptyDialogRoot",
            "registration.guard.close()", "!registration.guard.closeComplete", "val removed = dialogs.remove(registration)",
            "if (removed) attempt { onOwnedHierarchyChanged?.invoke() }", "return removed")
        val detached = guard.substringAfter("private fun detachedEmptyDialogRoot()").substringBefore("fun unregisterPrivateDialog")
        for (part in listOf("window.decorView === decor", "root.parent === content", "!decor.isAttachedToWindow",
            "decor.windowToken == null", "!root.isAttachedToWindow", "root.windowToken == null", "root.childCount == 0"))
            assertTrue(part, detached.contains(part))
        val close = guard.substringAfter("override fun close()")
        assertTrue(close.contains("attempt(it.onOwnerClosed)"))
        assertTrue(close.contains("attempt { it.guard.close() }"))
        assertTrue(close.contains("attempt { unregisterPrivateDialog(it) }"))
        assertTrue(close.contains("rootClosed = attempt { root.close() }"))
        assertFalse(close.contains("dialogs.clear()"))
        assertFalse(close.contains("if (disposed) return"))
    }

}
