package com.easysavevideo.app.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Local lifetime/source wiring checks, not real Window, IME, pixel or privacy acceptance. */
class PrivateAppDialogContractTest {
    private fun source(): String {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return File(root, "kotlin/com/easysavevideo/app/ui/PrivateAppDialog.kt").readText()
    }

    @Test fun aNeverShownOwnerStillClosesOnce() {
        val lifetime = PrivateDialogLifetime()
        assertFalse(lifetime.isClosed)
        assertTrue(lifetime.beginClose())
        assertTrue(lifetime.isClosed)
        assertFalse(lifetime.beginClose())
    }

    @Test fun reentrantNativeDismissCannotRepeatCleanup() {
        val lifetime = PrivateDialogLifetime()
        var calls = 0
        fun close() {
            if (!lifetime.beginClose()) return
            calls++
            assertTrue(lifetime.isClosed)
            assertFalse(lifetime.beginClose())
        }
        close(); close()
        assertEquals(1, calls)
    }

    @Test fun replacingAnOwnerCannotReopenTheOldLifetime() {
        val old = PrivateDialogLifetime()
        val next = PrivateDialogLifetime()
        assertTrue(old.beginClose())
        assertFalse(next.isClosed)
        assertFalse(old.beginClose())
        assertTrue(next.beginClose())
        assertTrue(old.isClosed)
    }

    @Test fun initialProtectionAndEmptyRegistrationPrecedeSensitiveAttachment() {
        val prepare = source().substringAfter("fun prepare(").substringBefore("fun update(")
        fun before(left: String, right: String) {
            assertTrue("Missing $left", prepare.contains(left))
            assertTrue("Missing $right", prepare.contains(right))
            assertTrue("$left must precede $right", prepare.indexOf(left) < prepare.indexOf(right))
        }
        before("environment.protectForModal(request)", "OwnedPrivateComponentDialog(environment.activity")
        before("window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)", "ownedDialog.setContentView")
        before("registerPrivateDialog(", "ownedRoot.addView(provider")
        before("setParentCompositionContext(parentComposition)", "compose.setContent")
        before("registerPrivateDialog(", "compose.setContent")
        assertTrue(prepare.contains("onOwnerClosed = { close() }"))
        assertFalse(prepare.contains("SecureFlagPolicy.Inherit"))
    }

    @Test fun nativeObjectsAreCreatedOnlyInsideTheDisposingEffect() {
        val composable = source().substringAfter("internal fun PrivateAppDialog(")
            .substringBefore("private class PrivateDialogHolder")
        assertTrue(composable.indexOf("DisposableEffect(holder)") < composable.indexOf("OwnedPrivateDialog(environment"))
        assertTrue(composable.contains("host.close()"))
        assertTrue(composable.indexOf("key(PrivateReferenceKey(requestIdentity)") <
            composable.indexOf("rememberUpdatedState(onDismissRequest)"))
        assertTrue(composable.contains("LocalPrivateAppDialogEnvironment.current ?: return"))
    }

    @Test fun parentOwnersAndEveryIntroducedParentAreReadBack() {
        val code = source()
        assertTrue(code.contains("parent.isAttachedToWindow && ownersMatch(parent)"))
        assertTrue(code.contains("parent.findViewTreeLifecycleOwner()"))
        assertTrue(code.contains("parent.findViewTreeViewModelStoreOwner()"))
        assertTrue(code.contains("parent.findViewTreeSavedStateRegistryOwner()"))
        assertTrue(code.contains("listOf(ownedRoot, provider, compose).all { ownersMatch(it) && excluded(it) }"))
        assertTrue(code.contains("compose.parent === provider && provider.parent === ownedRoot"))
        assertTrue(code.contains("FrameLayout(context), DialogWindowProvider"))
        assertFalse(code.contains("getDeclaredField"))
        assertFalse(code.contains("Class.forName"))
    }

    @Test fun showAndRevealNeedExactRequestAndIndependentRecordingCompatibility() {
        val code = source()
        assertTrue(code.contains("safe(localRequestIsCurrent) && safe { environment.isRequestCurrent(request) }"))
        val advance = code.substringAfter("private fun advance()").substringBefore("fun close(")
        assertTrue(advance.indexOf("safe(environment.captureDrainedForModal)") < advance.indexOf("ownedDialog.show()"))
        assertTrue(advance.contains("safe(environment.independentRecordingOwner)"))
        assertTrue(advance.contains("child.hasContinuousCaptureAuthorization"))
        assertFalse(advance.contains("captureDrained = true"))
        assertTrue(advance.contains("!requestCurrent() || !registered() || !ownedRoot.isCovered"))
        assertFalse(advance.contains("acknowledgePrivateComposition("))
        assertTrue(code.contains("SideEffect { acknowledgeAppliedComposition() }"))
        assertTrue(code.contains("child.acknowledgePrivateComposition(turn, presentationClaim())"))
        assertTrue(advance.contains("child.afterOwnedCoverFrameSubmitted(turn) { advance() }"))
        assertTrue(code.contains("dialog?.window?.decorView?.hasWindowFocus() == true"))
        assertFalse(code.contains("activity.hasWindowFocus()"))
    }

    @Test fun teardownKeepsOriginalViewsAndLateDetachCallbacksUntilVerifiedRelease() {
        val code = source()
        val ports = code.substringAfter("private val teardown = PrivateDialogTeardown").substringBefore("private val parentAttachment")
        assertTrue(ports.contains("root?.inputLifetime?.dispose()"))
        assertTrue(ports.contains("installProtectionBarrier()"))
        assertTrue(ports.contains("dialog?.dismissOwned()"))
        assertTrue(ports.contains("composeView?.disposeComposition()"))
        assertTrue(ports.contains("removeContentChildren = { contentParent?.removeAllViews() }"))
        assertTrue(ports.contains("removeRootChildren = { root?.removeAllViews() }"))
        assertTrue(code.contains("teardownIsComplete = ::teardownReadback"))
        val readback = code.substringAfter("private fun teardownReadback()").substringBefore("private fun releaseDetachedOwner")
        listOf("lifetime.isClosed", "detached(ownedDecor)", "detached(root)", "detached(contentParent)",
            "detached(composeView)", "!view.isAttachedToWindow", "view.windowToken == null", "root?.childCount",
            "contentParent?.parent == null", "composeView?.parent == null", "composeView?.hasComposition != true")
            .forEach { assertTrue(it, readback.contains(it)) }
        val release = code.substringAfter("private fun releaseDetachedOwner()").substringBefore("private fun afterNativeTeardownEvent")
        assertTrue(release.indexOf("unregisterPrivateDialog(ownedRegistration)") < release.indexOf("registration = null"))
        assertTrue(release.indexOf("if (!released) return false") < release.indexOf("removeOnAttachStateChangeListener"))
        assertTrue(code.contains("override fun onViewDetachedFromWindow(view: View) = afterNativeTeardownEvent()"))
        assertTrue(code.contains("nativeReadbackPosted = true"))
        assertTrue(code.contains("fun close(dismissWindow: Boolean = true) = teardown.close(dismissWindow)"))
    }

    @Test fun nativeDismissHasSeparateBeforeAndFinallyAfterPhases() {
        val code = source().substringAfter("override fun dismiss()").substringBefore("fun dismissOwned()")
        assertTrue(code.contains("beforeNativeDismiss()"))
        assertTrue(code.contains("try { super.dismiss() } finally { afterNativeDismiss() }"))
        assertFalse(code.contains("unregisterPrivateDialog"))
    }

    @Test fun privateNullRootDeniesAndTextSessionsCannotReacquireLaterGenerations() {
        val code = source()
        val boundary = code.substringAfter("internal fun PrivateInputBoundary(")
            .substringBefore("private class PrivateReferenceKey")
        assertTrue(boundary.contains("if (!privateMode)"))
        assertTrue(boundary.contains("lifetime?.acquire(owner)"))
        assertTrue(boundary.contains("actionToken?.isCurrent() == true"))
        assertTrue(boundary.contains("val sessionToken = actionToken?.fork()"))
        assertTrue(boundary.contains("if (next.view !== owner) return@PlatformTextInputInterceptor next.startInputMethod(request)"))
        val request = boundary.substringAfter("PrivatePlatformTextInputMethodRequest { info ->")
        assertTrue(request.contains("sanitizePrivateEditorInfo(info)"))
        assertFalse(request.contains(".acquire("))
        assertTrue(request.contains("createPrivateInputConnection(info, sessionToken, handler)"))
        assertTrue(request.contains("finally { sessionToken?.revoke() }"))
        assertTrue(code.contains("PrivateInputBoundary(true, ownedRoot.inputLifetime, generation)"))
    }

    @Test fun originalWidthBackOutsideAndImeContractsRemainExplicit() {
        val code = source()
        listOf("dismissOnClickOutside", "dismissOnBackPress", "usePlatformDefaultWidth", "decorFitsSystemWindows",
            "SOFT_INPUT_ADJUST_RESIZE", "Modifier.imePadding()", "Gravity.CENTER").forEach { assertTrue(code.contains(it)) }
        assertTrue(code.contains("override fun cancel() = outsideDismiss()"))
        assertTrue(code.contains("window.setTitle(\"EasySaveVideo\")"))
        assertFalse(code.contains("window.clearFlags"))
        assertFalse(code.contains("rememberSaveable"))
    }
}
