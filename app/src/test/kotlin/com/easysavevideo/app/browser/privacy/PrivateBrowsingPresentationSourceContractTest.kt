package com.easysavevideo.app.browser.privacy

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source ordering/negative contracts only. These do not instantiate or validate Android adapters. */
class PrivateBrowsingPresentationSourceContractTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
    private val host get() = File(root, "src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt").readText()
    private val runtime get() = File(root, "src/main/kotlin/com/easysavevideo/app/browser/privacy/PrivateBrowsingRuntime.kt").readText()

    @Test fun missingPresentationBindingKeepsPrivateIntegrationClosed() {
        val ready = host.substringAfter("private fun integrationReady()").substringBefore("private fun revokePrivatePresentationInput")
        assertTrue(ready.contains("privateIntegration?.ready() == true && privatePresentationBinding?.ready() == true"))
        val install = host.substringAfter("internal fun installPrivatePresentationBinding(").substringBefore("private fun canEnterPrivateMode")
        assertTrue(install.contains("privateSession == null"))
        assertTrue(install.contains("check(!value.inputLifetime.isAdmitted)"))
        assertFalse(ready.contains("SDK_INT")); assertFalse(ready.contains("openException"))
    }
    @Test fun normalPresentationDoesNotUseReadyOnlyCaptureOrInspectPageData() {
        val body = host.substringAfter("internal fun capturePrivateBrowsingPresentation()").substringBefore("internal fun bindPrivatePresentationInput(")
        for (forbidden in listOf("saveInspectionPage(", "capturePrivateCaptureTarget(", "BrowserLoadState.READY", "view.url", "cookie(", "ensureEntry(", "loadUrl(", "entries["))
            assertFalse(forbidden, body.contains(forbidden))
        assertTrue(body.contains("entry?.captureDocumentIdentity")); assertTrue(body.contains("entry?.jsDocument"))
        assertTrue(body.contains("tab.epoch")); assertTrue(body.contains("interactionRevision"))
        assertTrue(body.contains("fullscreenOwner !== entry"))
        assertTrue(body.contains("privateBrowsingRenderedContent(tab.loadState, entry != null,"))
        assertTrue(body.contains("tab.address != null, fullscreen != null) ?: return null"))
        assertTrue(body.contains("view.parent == null && privateRuntime.isMuted(view)"))
    }
    @Test fun applicationShellIsBoundToTheNonBrowserRouteAndRequiresEveryPrivateViewDetached() {
        val identity = host.substringAfter("private fun privateBrowsingPresentationIdentity(").substringBefore("private fun privateBrowsingIdentityCurrent(")
        assertTrue(identity.contains("(kind == PrivateBrowsingPresentationKind.BROWSER) != browserSurfaceAllowed()"))
        assertTrue(identity.contains("kind == PrivateBrowsingPresentationKind.APPLICATION && fullscreen != null"))
        val application = host.substringAfter("internal fun capturePrivateApplicationPresentation()").substringBefore("private fun privateBrowsingPresentationIdentity(")
        assertTrue(application.contains("PrivateBrowsingPresentationKind.APPLICATION"))
        val current = host.substringAfter("internal fun isPrivateBrowsingPresentationCurrent(").substringBefore("internal fun bindPrivatePresentationInput(")
        assertTrue(current.contains("view === claim.webView || (view.parent == null && privateRuntime.isMuted(view))"))
    }
    @Test fun errorShellRetainsSourceIdentityButRevokesAndDetachesEveryPrivateView() {
        val identity = host.substringAfter("private fun privateBrowsingPresentationIdentity(").substringBefore("private fun privateBrowsingIdentityCurrent(")
        assertTrue(identity.contains("val errorShell = renderedContent == PrivateBrowsingRenderedContent.ERROR_SHELL"))
        assertTrue(identity.contains("entry?.captureDocumentIdentity, entry?.jsDocument, entry?.view, fullscreen, kind"))
        assertTrue(identity.contains("tab.loadState.takeIf { errorShell }, tab.error?.code.takeIf { errorShell }"))
        val current = host.substringAfter("internal fun isPrivateBrowsingPresentationCurrent(").substringBefore("internal fun bindPrivatePresentationInput(")
        assertTrue(current.contains("view === claim.webView || (view.parent == null && privateRuntime.isMuted(view))"))
        val bind = host.substringAfter("internal fun bindPrivatePresentationInput(").substringBefore("internal fun beforePrivateViewAttachment(")
        assertTrue(bind.contains("if (view !== claim.webView) privateRuntime.revokeViewInput(view)"))
        assertTrue(bind.contains("privateRuntime.mute(view)"))
        assertTrue(bind.contains("claim.webView?.let { privateRuntime.bindViewInput"))
        val presentation = File(root, "src/main/kotlin/com/easysavevideo/app/browser/privacy/PrivateBrowsingPresentation.kt").readText()
        val getters = presentation.substringAfter("val webView: WebView?").substringBefore("override fun toString()")
        assertEquals(2, Regex("renderedContent == PrivateBrowsingRenderedContent.VIEW").findAll(getters).count())
        val renderer = host.substringAfter("override fun onRenderProcessGone(").substringBefore("private fun chrome(")
        assertTrue(renderer.contains("if (privateSessionAffected) exitPrivateSession()"))
    }
    @Test fun privateViewIsRootBoundBeforePublicationAndNewConnectionsAreFenced() {
        val creation = runtime.substringAfter("fun createView(").substringBefore("fun viewReleased")
        assertTrue(creation.contains("inputLifetime: PrivateInputLifetime"))
        assertTrue(creation.indexOf("PrivateProfileWebView(context, inputLifetime)") < creation.indexOf("viewOwnership.publish"))
        val input = runtime.substringAfter("override fun onCreateInputConnection(").substringBefore("override fun onDetachedFromWindow")
        assertTrue(input.indexOf("binding?.acquire()") < input.indexOf("super.onCreateInputConnection"))
        assertTrue(input.contains("if (admission == null) { sanitizePrivateEditorInfo(outAttrs); return null }"))
        assertTrue(input.contains("createPrivateInputConnection(outAttrs, admission, inputOwnerHandler)"))
        assertEquals(1, Regex("inputBinding.get\\(\\)").findAll(input).count())
        val detach = runtime.substringAfter("override fun onDetachedFromWindow()").substringBefore("override fun destroy()")
        assertTrue(detach.indexOf("revokeInput()") < detach.indexOf("super.onDetachedFromWindow()"))
    }
    @Test fun protectionAndReleaseRevokeInputBeforeProviderOrCaptureCallbacks() {
        val protection = host.substringAfter("private fun protectPrivateTransition(").substringBefore("private fun cancelPrivateOpening")
        assertTrue(protection.indexOf("revokePrivatePresentationInput()") < protection.indexOf("integration.protectForPresentationTransition()"))
        val release = host.substringAfter("private fun releaseView(").substringBefore("private fun ownerOf(")
        assertTrue(release.indexOf("privateRuntime.revokeViewInput(view)") < release.indexOf("privateRuntime.mute(view)"))
        assertTrue(release.indexOf("privateRuntime.revokeViewInput(view)") < release.indexOf("removeView(view)"))
        val invalidate = host.substringAfter("private fun invalidateDocumentAuthority(").substringBefore("internal fun captureSaveRequestContext(")
        assertTrue(invalidate.indexOf("revokePrivatePresentationInput()") < invalidate.indexOf("entry.captureDocumentIdentity = Any()"))
    }
    @Test fun attachmentRebindUsesOnlyBoundClaimUnderCoverAndNeverMintsAuthority() {
        val attach = host.substringAfter("internal fun beforePrivateViewAttachment(").substringBefore("internal fun readPrivateOrdinaryDeparture()")
        for (required in listOf("requireMain()", "val claim = boundPrivatePresentation ?: return false",
            "view.parent == null", "privateViewAttachmentAllowed(", "binding.isCovered()",
            "binding.inputLifetime.isAdmitted", "privateBrowsingIdentityCurrent(claim)",
            "privateRuntime.bindViewInput(session, webView, claim.presentationIdentity)",
            "privateRuntime.revokeViewInput(webView)")) assertTrue(required, attach.contains(required))
        assertEquals(3, Regex("attachmentCurrent\\(\\)").findAll(attach.substringAfter("return try {")).count())
        assertTrue(attach.indexOf("if (!attachmentCurrent()) return false") < attach.indexOf("privateRuntime.mute(webView)"))
        assertTrue(attach.indexOf("privateRuntime.mute(webView)") < attach.indexOf("privateRuntime.bindViewInput("))
        assertTrue(attach.lastIndexOf("if (!attachmentCurrent())") > attach.indexOf("privateRuntime.bindViewInput("))
        for (forbidden in listOf("capturePrivateBrowsingPresentation(", "capturePrivateApplicationPresentation(",
            "PrivateBrowsingPresentationClaim(", "boundPrivatePresentation =", "issuedPrivatePresentation =",
            "privatePresentationGeneration =", "bindPrivatePresentationInput(", "viewRevision"))
            assertFalse(forbidden, attach.contains(forbidden))
        val profileView = runtime.substringAfter("private class PrivateProfileWebView(")
        assertTrue(profileView.contains("private val inputLifetime: PrivateInputLifetime"))
        assertTrue(profileView.contains("PrivateWebViewInputBinding(inputLifetime, this, presentation)"))
    }
    @Test fun ordinaryDepartureReadsOwnersAndQuarantineInsteadOfInferringFromMode() {
        val departure = host.substringAfter("internal fun readPrivateOrdinaryDeparture()").substringBefore("internal fun capturePrivateCaptureTarget()")
        for (required in listOf("privateViews.keys.toList()", "it.parent == null", "privateRuntime.isMuted(it)",
            "quarantinedViews.none", "!privateReleaseFailed", "boundPrivatePresentation == null",
            "inputLifetime?.isAdmitted != true", "oldClaim.identity.hostGeneration !== privatePresentationGeneration",
            "views.none(privateRuntime::hasViewInputBinding)", "fullscreenCallback == null", "jsDialog == null"))
            assertTrue(required, departure.contains(required))
        assertFalse(departure.contains("captureDrained = true"))
    }
    @Test fun audioUsesCurrentSelectedViewAndSeparatesRecordingOwnership() {
        val audio = host.substringAfter("private fun resumeSelectedPrivateAudio()").substringBefore("private fun resumeSelectedView(")
        assertTrue(audio.contains("binding.captureAudioDisposition(view)"))
        assertTrue(audio.contains("isPrivateBrowsingPresentationCurrent(claim) && binding.isVisible(claim)"))
        assertTrue(audio.contains("privateIntegration?.allowVisibleAudio(view) == true"))
        assertTrue(audio.contains("else privateRuntime.mute(view)"))
        assertFalse(audio.contains("PrivateCapturePhase.ACTIVE"))
    }
}
