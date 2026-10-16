package com.easysavevideo.app.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Exact production call-site contracts; these do not execute Compose, NavHost or native capture. */
class PrivateTaskActionsSourceContractTest {
    private fun source(path: String) = File("src/main/kotlin/com/easysavevideo/app/$path").readText()
    private fun ordered(text: String, vararg parts: String) {
        var offset = 0
        parts.forEach { part ->
            val found = text.indexOf(part, offset)
            assertTrue("Missing or out of order: $part", found >= 0)
            offset = found + part.length
        }
    }

    @Test fun chromeCapturesTheRootPublicationInsideItsBoundaryAndExactRouteOccurrence() {
        val main = source("MainActivity.kt")
        ordered(main, "PrivateInputBoundary(privateMode, privatePresentationOwner.inputLifetime, inputEpoch) {",
            "val rootInputAdmission = LocalPrivateInputAdmission.current",
            "val actionRouteIdentity = privateRouteIdentity",
            "remember(privateMode, presentation, rootInputAdmission, actionRouteIdentity, inputEpoch)",
            "CapturedPrivateActions(privateMode, rootInputAdmission)",
            "privateRouteIdentity === actionRouteIdentity",
            "privatePresentationOwner.inputEpoch == inputEpoch",
            "privatePresentationOwner.isCurrentSource(presentation)",
            "onDispose { chromeActions.close() }")
        assertTrue(main.contains("val privateMode = presentation?.sensitive"))
        val owner = source("browser/privacy/PrivateMainPresentationOwner.kt")
        val sourceCheck = owner.substringAfter("fun isCurrentSource(").substringBefore("fun vaultActionsCurrent(")
        for (part in listOf("current === value", "routeIdentity() === value.route", "value.vaultClaim.host.owns(value.vaultClaim)",
            "browser.isPrivateBrowsingPresentationCurrent(value.claim)", "!browser.isPrivateMode && vault()?.claimFor(value.route) == null"))
            assertTrue(part, sourceCheck.contains(part))
        assertTrue(main.contains("onNavigate = { destination -> chromeActions.run { navigate(destination) } })"))
        assertFalse(main.contains("onNavigate = ::navigate)"))
        assertTrue(main.contains("privateBackStackEntry = exactEntry\n                            privateRouteIdentity = Any()"))
    }

    @Test fun taskAdmissionIncludesTheExactNavEntryAndDoesNotUseLatestValueAuthority() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("val routeOwnsPresentation = { routeEntry === privateBackStackEntry &&"))
        assertTrue(main.contains("controller.currentBackStackEntry === routeEntry && route == activeDestination"))
        val task = main.substringAfter("else if (route == EsvDestination.TASKS) SaveTaskScreen(saves, browser,")
            .substringBefore("else if (route == EsvDestination.SOURCES)")
        assertTrue(task.contains("canAct = remember(chromeActions, routeEntry)"))
        assertTrue(task.contains("{ chromeActions.isCurrent() && routeOwnsPresentation() }"))
        val screen = source("save/SaveTaskScreen.kt")
        ordered(screen, "val inputAdmission = LocalPrivateInputAdmission.current",
            "val privateMode = LocalPrivatePresentation.current",
            "remember(runtime, browser, privateMode, inputAdmission, canAct)",
            "CapturedPrivateActions(privateMode, inputAdmission, canAct)",
            "onDispose { actions.close() }", "val taskAdmission = remember(actions) { actions::isCurrent }",
            "CompositionLocalProvider(LocalPrivateInputAdmission provides taskAdmission)")
        for (bad in listOf("rememberUpdatedState(inputAdmission)", "rememberUpdatedState(canAct)",
            "rememberUpdatedState(taskAdmission)", "rememberUpdatedState(rootInputAdmission)")) {
            assertFalse(bad, screen.contains(bad)); assertFalse(bad, main.contains(bad))
        }
        assertTrue(screen.contains("canAct: () -> Boolean,"))
    }

    @Test fun everyTaskHeaderBackRecoveryAndLocalSelectionEntryUsesCapturedScope() {
        val screen = source("save/SaveTaskScreen.kt").substringBefore("private fun SaveTaskDetail")
        for (required in listOf(
            "BackHandler(onBack = { actions.run(onBack) })",
            "flow_savetaskscreen_1), onClick = { actions.run(onBack) }",
            "flow_savetaskscreen_2), onClick = { actions.run(open) }",
            "flow_savetaskscreen_3), onClick = { actions.run(open) }",
            "onClick = { actions.run { showLimits = !showLimits } }",
            "onClick = { actions.run(runtime::dismissNotice) }",
            "onClick = { actions.run { recoveryAction.invoke(ticket) } }",
            "onClick = { actions.run { recoveryAction.cancel(ticket.taskId, runtime::cancel) } }",
            "onClick = { actions.run { recoveryAction.cancel(taskId, runtime::cancel) } }",
            "onClick = { actions.run { selectedTaskId = record.task.taskId } }",
            "onClick = { actions.run { selectedTaskId = null } }")) assertTrue(required, screen.contains(required))
        assertEquals(10, Regex("onClick = ").findAll(screen).count())
        assertEquals(10, Regex("onClick = \\{ actions.run").findAll(screen).count())
        assertTrue(screen.contains("onDispose { recoveryAction.close() }"))
        assertTrue(screen.contains("runtime.recoveryRetryAction { currentRecoveryAdmission(runtime) }"))
    }

    @Test fun bothTaskLayoutsShareTheGuardedDetailAndPreserveExistingAvailability() {
        val screen = source("save/SaveTaskScreen.kt")
        assertEquals(2, Regex("onPreview, onBrowser, canOpenBrowserForAuthentication\\)").findAll(screen).count())
        val detail = screen.substringAfter("private fun SaveTaskDetail").substringBefore("fun SaveSelectionDialog")
        assertTrue(detail.contains("val admission = LocalPrivateInputAdmission.current"))
        for (effect in listOf("preview(record)", "open()", "runtime.resume(record.task.taskId, browser)",
            "runtime.cancel(record.task.taskId)")) assertTrue(effect, detail.contains("onClick = { if (admission()) $effect }"))
        assertEquals(4, Regex("onClick = ").findAll(detail).count())
        for (guard in listOf("disposition == MediaLifecycle.READY && runtime.currentRecordingId == null && !runtime.draining",
            "enabled = !runtime.draining && record.task.taskId !in runtime.pendingCommands",
            "if (!record.task.state.isTerminal)", "enabled = record.task.taskId !in runtime.pendingCommands"))
            assertTrue(guard, detail.contains(guard))
    }

    @Test fun guidanceChecksCapturedTaskAdmissionBeforeAnyBusinessEffectOrLatestCallback() {
        val failure = source("save/SaveFailureGuidanceUi.kt").substringBefore("fun SaveFailureBrowserReminder")
        ordered(failure, "val admission = LocalPrivateInputAdmission.current", "onClick = {",
            "if (!admission()) return@EsvButton", "result = runtime.returnToBrowserAfterFailure(command)",
            "if (result == FailureNavigationResult.ACCEPTED) onBrowser()")
        for ((path, effect) in listOf(
            "save/SaveAuthenticationGuidance.kt" to "runtime.returnToBrowserForAuthentication(command)",
            "save/SaveAuthenticationOriginGuidance.kt" to "runtime.returnToOriginForAuthentication(command, browser, browserRevision)")) {
            val ui = source(path)
            ordered(ui, "val admission = LocalPrivateInputAdmission.current", "onClick = {",
                "if (!admission()) return@EsvButton", "val open = currentOpen", "!currentCanOpen()", effect,
                "if (result == AuthenticationNavigationResult.ACCEPTED) open?.invoke()")
            assertTrue(ui.contains("onDispose { command.retire() }"))
            assertFalse(ui.contains("rememberUpdatedState(admission)"))
        }
    }

    @Test fun sharedFeedbackAndDestinationHistoryAlsoCaptureTheTasksAdmission() {
        val alternative = source("save/SaveMethodAlternativeUi.kt")
        ordered(alternative, "val admission = LocalPrivateInputAdmission.current",
            "onClick = { if (admission()) runtime.dismissAlternativeFeedback() }")
        val destination = source("library/destination/OrdinaryDestinationUi.kt")
        val history = destination.substringAfter("fun OrdinaryDestinationHistory")
        ordered(history, "val admission = LocalPrivateInputAdmission.current", "OrdinaryDestinationNotice(runtime)",
            "onClick = { if (admission()) runtime.cancelCopy(status.creationId) }",
            "onClick = { if (admission()) runtime.reconcile() }")
        assertTrue(destination.contains("if (admission() && request != null && runtime.currentNoticeIdentity === request) onDismiss()"))
        assertTrue(history.contains("if (ordinaryCopyCanCancel(status))"))
    }

    @Test fun helperDoesNotExpandRuntimeAuthorityAndOrdinaryBusinessGatesAreStillLocal() {
        val helper = source("ui/CapturedPrivateActions.kt")
        assertTrue(helper.contains("!closed && ownerCurrent() && (!privateMode || inputAdmission())"))
        assertTrue(helper.contains("if (isCurrent()) action()"))
        assertTrue(helper.contains("fun close() { closed = true }"))
        for (forbidden in listOf("AndroidSaveRuntime", "SystemBrowserHost", "stopRecording", "discard", "publish(", "revoke(", "rememberUpdatedState"))
            assertFalse(forbidden, helper.contains(forbidden))
    }
}
