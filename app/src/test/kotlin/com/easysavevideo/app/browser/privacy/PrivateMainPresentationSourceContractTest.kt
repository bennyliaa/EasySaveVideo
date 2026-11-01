package com.easysavevideo.app.browser.privacy

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts ONLY: no Activity, Compose, IME, WebView, frame, audio or native-drain execution. */
class PrivateMainPresentationSourceContractTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
    private fun source(name: String) = File(root, "src/main/kotlin/com/easysavevideo/app/$name").readText()
    private val activity get() = source("MainActivity.kt")
    private val owner get() = source("browser/privacy/PrivateMainPresentationOwner.kt")
    private val pending get() = source("recording/PendingPrivateRecordingPresentationPort.kt")
    private fun ordered(value: String, vararg parts: String) {
        var offset = 0
        for (part in parts) {
            val index = value.indexOf(part, offset)
            assertTrue("Missing or out of order: $part", index >= 0)
            offset = index + part.length
        }
    }
    @Test fun actualMainInstallsBothBindingsBeforeCompositionAndKeepsPlatformGatesClosed() {
        ordered(activity, "privateWindowGuard = PrivateWindowGuard", "privateRecording = PrivateRecordingBridge",
            "privatePresentationOwner = PrivateMainPresentationOwner", "browser.installPrivatePresentationBinding(privatePresentationOwner)",
            "browser.installPrivateIntegration(object : PrivateBrowserIntegration", "appComposeView.setContent {")
        val gates = activity.substringAfter("privateWindowGuard.setPendingGates(").substringBefore("privateRecordingSurface =")
        for (gate in listOf("RETAINED_INPUT_CONNECTION", "NATIVE_PRESENTATIONS", "ANDROID_DEVICE_VALIDATION"))
            assertTrue(gates.contains("PrivateWindowPendingGate.$gate"))
        assertFalse(gates.contains("PrivateWindowPendingGate.CAPTURE_POLICY_ADAPTER"))
        val ready = owner.substringAfter("override fun ready()").substringBefore("override fun isCovered()")
        assertTrue(ready.contains("guard.browsingIntegrationInstalled"))
        assertFalse(ready.contains("SDK_INT")); assertFalse(ready.contains("capabilityAvailable"))
    }
    @Test fun exactClaimIsCachedReservedAndInputBoundBeforeChildrenCompose() {
        val prepare = owner.substringAfter("fun prepareComposition()").substringBefore("private fun appliedCurrent")
        ordered(prepare, "old.route === route", "browser.isPrivateBrowsingPresentationCurrent(old.claim)",
            "browser.capturePrivateBrowsingPresentation()", "browser.capturePrivateApplicationPresentation()",
            "if (!value.prepared)", "guard.reservePrivatePresentation(claim.targetIdentity, claim.presentationIdentity)",
            "browser.bindPrivatePresentationInput(claim)", "value.prepared = true")
        assertFalse(prepare.contains("capturePrivateCaptureTarget"))
        ordered(activity.substringAfter("appComposeView.setContent {"), "privatePresentationOwner.prepareComposition()",
            "PrivateInputBoundary(", "EsvAppShell(")
        assertTrue(owner.contains("override fun isCovered() = !closed && root.isCovered"))
    }
    @Test fun actualSideEffectAcknowledgesThenWaitsForNewOwnedCoverFrame() {
        assertEquals(1, Regex("privatePresentationOwner\\.compositionApplied\\(").findAll(activity).count())
        val outer = activity.substringAfter("appComposeView.setContent {").substringBefore("EsvAppShell(")
        assertFalse(outer.contains("compositionApplied("))
        assertTrue(activity.contains("if (!browserChild) SideEffect { childApplied() }"))
        val child = activity.substringAfter("val childApplied = {").substringBefore("val childLaidOut")
        ordered(child, "if (routeOwnsPresentation())", "if (privateRecordingSurface.ownsCapturePresentation)",
            "privateRecordingSurface.compositionApplied(recordingUi)",
            "else privatePresentationOwner.compositionApplied(presentation, childApplyTicket)")
        assertTrue(source("ui/BrowserScreen.kt").contains("SideEffect { onPresentationApplied() }"))
        val apply = owner.substringAfter("private fun acknowledgeChildComposition(").substringBefore("private fun reveal(")
        ordered(apply, "guard.acknowledgePrivateComposition(turn, windowClaim)", "value.acknowledgedTurn = turn",
            "guard.afterOwnedCoverFrameSubmitted(turn)")
        val retry = owner.substringAfter("fun retryPresentation()").substringBefore("fun dialogEnvironment(")
        assertTrue(retry.contains("value.acknowledgedTurn !== turn"))
        assertFalse(retry.contains("acknowledgePrivateComposition(")); assertFalse(retry.contains("acknowledgeOrdinaryComposition("))
        assertFalse(activity.substringAfter("override fun onWindowFocusChanged").contains("compositionApplied("))
    }
    @Test fun parentAttachmentAndFullscreenUnderlyingViewAreBothChecked() {
        assertTrue(owner.contains("value.windowClaim != null && value.windowClaim?.expectedParent !== actualParent"))
        assertTrue(owner.contains("claim.contentView, actualParent"))
        assertTrue(owner.contains("ownsContent(claim.contentView)"))
        assertTrue(owner.contains("!it.isAttachedToWindow || ownsContent(it)"))
        assertTrue(owner.contains("if (!view.isAttachedToWindow) return false"))
        assertTrue(owner.contains("if (parent === root) return true"))
    }
    @Test fun explicitShellClaimsMatchTheRealBrowserUiBranchWithoutRelaxingViewAttachment() {
        val shape = owner.substringAfter("private fun compositionShapeCurrent(").substringBefore("private fun acknowledgeChildComposition(")
        assertTrue(shape.contains("PrivateBrowsingPresentationKind.APPLICATION"))
        assertTrue(shape.contains("!isBrowserRoute() && claim.contentView == null"))
        assertTrue(shape.contains("PrivateBrowsingRenderedContent.VIEW -> claim.contentView != null && ownsContent(claim.contentView)"))
        assertTrue(shape.contains("PrivateBrowsingRenderedContent.EMPTY -> claim.isEmptyTab"))
        assertTrue(shape.contains("PrivateBrowsingRenderedContent.ERROR_SHELL -> claim.contentView == null"))
        assertTrue(shape.contains("setOf(BrowserLoadState.FAILED, BrowserLoadState.CANCELLED)"))
        assertFalse(shape.contains("RENDERER_UNAVAILABLE"))
    }
    @Test fun failedEntryReservesNonAuthorizingTransitionAndObservesTerminalStatus() {
        val reserve = owner.substringAfter("fun reserveProtectedTransition()").substringBefore("fun prepareComposition()")
        ordered(reserve, "reservedPrivateTransition = true", "it.applied = false", "it.acknowledgedTurn = null",
            "val transition = Any()", "guard.reservePrivatePresentation(transition, transition)")
        assertFalse(reserve.contains("capturePrivateBrowsingPresentation"))
        val composition = activity.substringAfter("appComposeView.setContent {")
        ordered(composition, "val privateStatus = browser.privateStatus", "privatePresentationOwner.prepareComposition()",
            "privatePresentationOwner.captureChildApply(presentation)",
            "privatePresentationOwner.compositionApplied(presentation, childApplyTicket)")
        assertTrue(composition.contains("presentedFullscreen, privateStatus"))
        assertTrue(activity.contains("val reserved = privatePresentationOwner.reserveProtectedTransition()"))
    }
    @Test fun initialOrdinaryUsesHostReadbackAndLaterDepartureUsesAllIndependentOwners() {
        val initial = owner.substringAfter("if (!reservedPrivateTransition)").substringBefore("} else if (root.isCovered)")
        assertTrue(initial.contains("browser.readPrivateOrdinaryDeparture().ordinaryCurrent"))
        assertFalse(owner.contains("!browser.privateIntegrationReady")); assertFalse(activity.contains("!browser.privateIntegrationReady"))
        val departure = owner.substringAfter("private fun ordinaryProof(").substringBefore("fun retryPresentation()")
        for (field in listOf("appliedCurrent(value)", "!browser.isPrivateMode", "host.ordinaryCurrent",
            "host.privateViewsDetached", "!guard.hasRegisteredPrivateDialogs", "host.privateCallbacksRetired", "captureDrained()"))
            assertTrue(field, departure.contains(field))
        val apply = owner.substringAfter("private fun acknowledgeChildComposition(").substringBefore("private fun reveal(")
        ordered(apply, "guard.beginOrdinaryPresentation(value.identity, value.identity)",
            "guard.acknowledgeOrdinaryComposition(turn, proof)", "guard.afterOwnedCoverDrawn(turn)", "guard.releaseOrdinaryPresentation(turn, proof)")
    }
    @Test fun globalLocalsAndRevealEpochSurroundNonBrowserDialogsToo() {
        ordered(activity.substringAfter("appComposeView.setContent {"), "val inputEpoch = privatePresentationOwner.inputEpoch",
            "LocalPrivatePresentation provides privateMode", "LocalPrivateAppDialogEnvironment provides dialogs",
            "PrivateInputBoundary(privateMode, privatePresentationOwner.inputLifetime, inputEpoch)", "EsvAppShell(",
            "SaveSelectionDialog(saves, browser)", "OrdinaryDestinationDialog(it)")
        val reveal = owner.substringAfter("private fun reveal(").substringBefore("private fun ordinaryProof(")
        ordered(reveal, "PrivateRevealResult.REVEALED", "inputEpoch++", "browser.refreshPrivateCaptureAudio()")
        assertTrue(activity.contains("privatePresentationOwner.inputRevoked()"))
    }
    @Test fun modalEnvironmentIsStableAndDoesNotRequireTheMainWindowToKeepFocus() {
        val modal = owner.substringAfter("fun dialogEnvironment(").substringBefore("fun close()")
        assertTrue(modal.contains("value.dialogEnvironment?.let { return it }"))
        assertTrue(modal.contains("current === value && routeIdentity() === value.route"))
        assertTrue(modal.contains("Lifecycle.State.RESUMED"))
        assertTrue(modal.contains("browser.isPrivateBrowsingPresentationCurrent(value.claim)"))
        assertTrue(modal.contains("captureDrainedForModal = captureDrained"))
        assertFalse(modal.contains("hasWindowFocus")); assertFalse(modal.contains("hasFocusedWindow"))
    }
    @Test fun exactConsentGrantFlowsThroughRuntimeBeforePrivateBridgeAndDenialIsCorrelated() {
        val consent = activity.substringAfter("recordingConsent = RecordingConsentController(").substringBefore("persistence =")
        ordered(consent, "saves.receiveProjectionGrant(id, resultCode, data)", "privateRecording.grantOffered(id)")
        ordered(consent, "saves.abandonRecordingRequest(id)", "privateRecording.requestAbandoned(id)")
        assertTrue(consent.contains("saves.recordingAudioPermissionDenied(id)"))
        assertTrue(consent.contains("requiresDefaultDisplay = saves::requiresDefaultDisplayRecording"))
    }
    @Test fun privateRecordingControlsAreGlobalWhileOrdinaryRecordingRemainsTheExistingRoute() {
        assertTrue(activity.contains("if (browser.isPrivateMode) privateRecording.configure() else navigate(EsvDestination.RECORD)"))
        assertFalse(activity.contains("privateRecording = recordingUi"))
        assertTrue(activity.contains("if (recordingUi != null) RecordingSessionControls(recordingUi)"))
        assertTrue(activity.contains("consent = recordingConsent, canStart = !privateMode && saves.ready"))
        assertEquals(2, Regex("select\\(isCurrent = \\{ !browser\\.isPrivateMode && isForeground && showingRecording").findAll(activity).count())
        val basic = activity.substringAfter("onStart = { choice ->").substringBefore("onPause =")
        assertTrue(basic.contains("select(isCurrent = { controller.currentBackStackEntry === startEntry && routeOwnsPresentation()"))
        assertTrue(basic.contains("!browser.isPrivateMode && isForeground && showingRecording && !isDestroyed && !isFinishing &&"))
        assertTrue(activity.contains("canStart = !privateMode && saves.ready && saves.segmentRecording?.ready"))
    }
    @Test fun navigationBackPopAndOverrideChangesProtectBeforePublishingContent() {
        val navigate = activity.substringAfter("private fun navigate(").substringBefore("private fun backTo(")
        ordered(navigate, "!protectBeforePresentationMove()", "dispatchEsvNavigation(")
        val back = activity.substringAfter("private fun backTo(").substringBefore("private lateinit var browser")
        ordered(back, "!protectBeforePresentationMove()", "navController?.popBackStack()")
        val listener = activity.substringAfter("val listener = androidx.navigation.NavController.OnDestinationChangedListener").substringBefore("controller.addOnDestinationChangedListener")
        ordered(listener, "privateBackStackEntry !== exactEntry", "protectBeforePresentationMove()",
            "privateRouteIdentity = Any()", "activeDestination = nextDestination")
        assertEquals(2, Regex("privateRouteIdentity = Any\\(\\); (libraryPreviewState|previewReceiptState) = value").findAll(activity).count())
    }
    @Test fun filePromptActionsCaptureExactRequestAndProtectBeforePickerLaunch() {
        assertTrue(activity.contains("if (filePrompt === choice) { filePrompt = null; browser.finishFiles(choice, null) }"))
        assertTrue(activity.contains("isFilePromptCurrent = { choice -> filePrompt === choice"))
        assertTrue(activity.contains("if (filePrompt != null && !browser.hasPendingFileChoice) filePrompt = null"))
        ordered(activity.substringAfter("override fun onPause()"), "filePrompt = null", "browser.pause()")
        val picker = activity.substringAfter("private fun chooseFile(").substringBefore("private fun openSystemBrowser()")
        ordered(picker, "filePrompt !== choice", "!protectBeforePresentationMove()", "!browser.beginFilePicker(choice)",
            "filePrompt = null", "choosing = choice", "picker.launch(intent)")
    }
    @Test fun audioAndDepartureNeverInferCaptureDrainFromUiOrPhase() {
        val drain = activity.substringAfter("private fun captureSourcesDrained()").substringBefore("private fun protectPrivateTransition()")
        assertTrue(drain.contains("privateRecording.captureSourceDrained && saves.recordingSourceObligationsDrained()"))
        assertTrue(drain.contains("!privateRecording.hasSetupOwner && captureSourcesDrained()"))
        assertTrue(drain.contains("saves.continuousRecordingAuthorization != null"))
        assertTrue(drain.contains("PrivateBrowsingAudioDisposition.AUTHORIZED_CONTINUOUS_CAPTURE"))
        assertFalse(drain.contains("phase !=")); assertFalse(drain.contains("presentation == null"))
        assertFalse(drain.contains("mediaTaskActivity"))
    }
    @Test fun lifecycleAndDestructionKeepProtectionAliveThroughExactCaptureAndBrowserStop() {
        val pause = activity.substringAfter("override fun onPause()").substringBefore("override fun onDestroy()")
        ordered(pause, "privateRecording.protectForLifecycle()", "isForeground = false", "browser.pause()")
        val destroy = activity.substringAfter("override fun onDestroy()")
        ordered(destroy, "privateRecording.close()", "browser.close()", "privateRecordingSurface.close()",
            "privatePresentationOwner.close()", "privateWindowGuard.close()", "appComposeView.disposeComposition()", "super.onDestroy()")
    }
    @Test fun pendingCapturePortCannotFabricateEvidenceOrRemoveWindowProtection() {
        assertTrue(pending.contains("override fun capabilityAvailable(): Boolean = false"))
        assertTrue(pending.contains("override fun isCurrent(evidence: PrivateCaptureSurfaceEvidence): Boolean = false"))
        assertTrue(pending.contains("completed: (PrivateCaptureSurfaceEvidence?) -> Unit): Boolean = false"))
        val open = pending.substringAfter("override fun openException(").substringBefore("override fun installProtectionBarrier()")
        ordered(open, "installProtectionBarrier()", "return false")
        assertFalse(pending.contains("PrivateCaptureSurfaceEvidence("))
        assertFalse(pending.contains("clearFlags(")); assertFalse(pending.contains("LEGACY_VALIDATED_ISOLATION"))
        assertFalse(pending.contains("stillAllowed()"))
    }
    @Test fun onlyCoveredBrowserAndTaskRoutesRetainPrivateModeAndOtherRoutesPreserveSession() {
        val routes = activity.substringAfter("private fun canRetainPrivateRoute(").substringBefore("private fun protectForPrivateModal()")
        assertTrue(routes.contains("setOf(EsvDestination.BROWSER, EsvDestination.TASKS)"))
        for (pending in listOf("saves.scanning", "saves.selection != null", "saves.preparingStream", "saves.hlsPreparation != null",
            "saves.destinations?.asking", "saves.destinations?.admitting", "saves.destinations?.notice"))
            assertTrue(pending, routes.contains(pending))
        ordered(routes, "if (privateSelectionPending()) return false", "return browser.returnToOrdinaryMode()")
        for (forbidden in listOf("exitPrivateSession(", "dismissSelection(", "cancelSelection(", "cancelTask("))
            assertFalse(forbidden, routes.contains(forbidden))
        assertEquals(2, Regex("prepareRouteMode\\(activeDestination, previewOverride = true\\)").findAll(activity).count())
    }
    @Test fun unsafeOrdinaryRoutesAndRawPreviewDialogsWaitForExactDepartureShell() {
        val admission = owner.substringAfter("fun canComposeOrdinaryContent(").substringBefore("private fun ownsContent(")
        for (part in listOf("current === value", "!value.sensitive", "routeIdentity() === value.route", "!browser.isPrivateMode",
            "!reservedPrivateTransition", "!root.isCovered", "PrivateRootPresentationState.ORDINARY_VISIBLE"))
            assertTrue(part, admission.contains(part))
        assertTrue(activity.contains("privatePresentationOwner.canComposeOrdinaryContent(presentation)"))
        ordered(activity.substringAfter(") { route, routeEntry ->"),
            "if (!routeContentReady || (vaultPresentation && route != EsvDestination.VAULT)",
            "(presentation?.claim != null && !canRetainPrivateRoute(route))", "else if (previewHost) BrowserScreen")
        assertTrue(owner.contains("val sensitive get() = claim != null || vaultClaim != null"))
        assertTrue(activity.contains("setOf(EsvDestination.BROWSER, EsvDestination.TASKS)"))
        assertFalse(activity.contains("setOf(EsvDestination.BROWSER, EsvDestination.TASKS, EsvDestination.VAULT)"))
        assertTrue(activity.contains("if (!previewHost && (privateMode || routeContentReady))"))
        assertTrue(activity.contains("authorizedPreviewFrame != null && isForeground && routeContentReady && !privateMode"))
        assertTrue(activity.contains("enabled = routeContentReady && !privateMode && previewHost"))
    }
    @Test fun commandsAndLateBackPopBothSwitchModeBeforePublishingAnUnsafeRoute() {
        val navigate = activity.substringAfter("private fun navigate(").substringBefore("private fun backTo(")
        ordered(navigate, "!protectBeforePresentationMove()", "!prepareRouteMode(destination)", "dispatchEsvNavigation(")
        val listener = activity.substringAfter("val listener = androidx.navigation.NavController.OnDestinationChangedListener")
            .substringBefore("controller.addOnDestinationChangedListener")
        ordered(listener, "protectBeforePresentationMove()", "prepareRouteMode(EsvDestination.fromRoute(destination.route))",
            "privateRouteIdentity = Any()", "activeDestination = nextDestination")
        val effect = activity.substringAfter("LaunchedEffect(activeDestination, previewReceipt, libraryPreviewReference,")
            .substringBefore("CompositionLocalProvider(LocalPrivatePresentation")
        assertTrue(effect.contains("saves.scanning, saves.selection"))
        assertTrue(effect.contains("saves.destinations?.admitting, saves.destinations?.notice"))
        assertTrue(effect.contains("prepareRouteMode(activeDestination)"))
        assertFalse(effect.contains("compositionApplied("))
    }
    @Test fun configureModalAttemptsMuteEvenWhenProtectionFails() {
        val body = activity.substringAfter("val protected = if (configureOnly) {").substringBefore("private fun")
        ordered(body, "val barrier = try { privateRecordingSurface.installProtectionBarrier() }",
            "val muted = try { browser.mutePrivateCaptureAudio() }", "barrier && muted")
        assertFalse(body.contains("installProtectionBarrier() &&"))
        assertTrue(body.contains("catch (_: Exception) { false }"))
    }

}
