package com.easysavevideo.app.ui.shell

import com.easysavevideo.app.discovery.DiscoveryControlRequest
import com.easysavevideo.app.discovery.DiscoveryControlTarget
import com.easysavevideo.app.discovery.DiscoveryLocalAction
import com.easysavevideo.app.discovery.DiscoveryPresentationPolicy
import com.easysavevideo.discovery.SessionStamp
import org.junit.Assert.*
import org.junit.Test

/** Production view adapter with observable local ports and explicitly delayed accepted work. */
class DiscoveryPrivacyActionsTest {
    private class Rig {
        val target = DiscoveryControlTarget("profile-one", SessionStamp("session-one", 7), 4, 3)
        var live = DiscoveryPrivacyAvailability(true, false, target, setOf("source-one", "source-two"), false)
        var foreground = true
        var visible = true
        var accept = true
        var availabilityReads = 0
        val submitted = mutableListOf<DiscoveryControlRequest>()
        val refreshed = mutableListOf<DiscoveryControlTarget>()
        val completed = mutableListOf<DiscoveryControlRequest>()
        val publications = mutableListOf<DiscoveryPrivacyState>()
        val pending = ArrayDeque<() -> Unit>()
        var onSubmit: (DiscoveryControlRequest) -> Unit = {}
        var onRefresh: (DiscoveryControlTarget) -> Unit = {}
        var onChanged: (DiscoveryPrivacyState) -> Unit = {}
        fun actions() = DiscoveryPrivacyActions(
            canAct = { foreground && visible },
            availability = { availabilityReads++; live },
            submit = { request ->
                submitted += request
                onSubmit(request)
                if (accept) {
                    live = live.copy(busy = true)
                    pending.addLast { completed += request; live = live.copy(busy = false) }
                }
                accept
            },
            refreshStatistics = { refreshed += it; onRefresh(it) },
            changed = { publications += it; onChanged(it) },
        )
        val actions = actions()
        fun begin(action: DiscoveryLocalAction = DiscoveryLocalAction.CLEAR_CURRENT_HISTORY): DiscoveryPrivacyPrompt {
            actions.begin(action, "source-two".takeIf { action == DiscoveryLocalAction.RESET_PROVIDER_PROGRESS })
            return requireNotNull(actions.state.prompt)
        }
        fun finish() { while (pending.isNotEmpty()) pending.removeFirst()() }
        fun assertNoPorts() { assertTrue(submitted.isEmpty()); assertTrue(refreshed.isEmpty()) }
    }

    @Test fun allThreeActionsRequireConfirmationAndKeepTheirExactScope() {
        for (action in DiscoveryLocalAction.entries) {
            val r = Rig()
            val prompt = r.begin(action)
            r.assertNoPorts()
            assertSame(r.target, prompt.request.target)
            assertEquals(action, prompt.request.action)
            assertEquals("source-two".takeIf { action == DiscoveryLocalAction.RESET_PROVIDER_PROGRESS }, prompt.request.providerId)
            r.actions.confirm(prompt)
            assertEquals(listOf(prompt.request), r.submitted)
            assertNull(r.actions.state.prompt)
            assertFalse(r.actions.state.stale)
            assertTrue(r.refreshed.isEmpty())
            val history = mutableListOf<String?>()
            val progress = mutableListOf<Pair<String, String>>()
            DiscoveryPresentationPolicy.executeControl(r.submitted.single(), r.target, r.live.providerIds,
                { history += it; true }, { profile, provider -> progress += profile to provider; true })
            when (action) {
                DiscoveryLocalAction.CLEAR_CURRENT_HISTORY -> {
                    assertEquals(listOf("profile-one"), history); assertTrue(progress.isEmpty())
                }
                DiscoveryLocalAction.CLEAR_ALL_HISTORY -> {
                    assertEquals(listOf<String?>(null), history); assertTrue(progress.isEmpty())
                }
                DiscoveryLocalAction.RESET_PROVIDER_PROGRESS -> {
                    assertTrue(history.isEmpty()); assertEquals(listOf("profile-one" to "source-two"), progress)
                }
            }
        }
    }

    @Test fun cancellingEachActionMakesNoPortCallAndRetiresItsConfirmation() {
        for (action in DiscoveryLocalAction.entries) {
            val r = Rig(); val prompt = r.begin(action)
            r.actions.cancel(prompt); r.actions.cancel(prompt); r.actions.confirm(prompt)
            assertNull(r.actions.state.prompt); assertFalse(r.actions.state.stale)
            r.assertNoPorts()
        }
    }

    @Test fun malformedProviderArgumentsCannotSilentlyChangeActionScope() {
        val r = Rig()
        r.actions.begin(DiscoveryLocalAction.CLEAR_CURRENT_HISTORY, "source-one")
        r.actions.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY, "source-one")
        r.actions.begin(DiscoveryLocalAction.RESET_PROVIDER_PROGRESS)
        for (provider in listOf("", "source", "SOURCE-ONE", " source-one", "source-one ", "unknown")) {
            r.actions.begin(DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, provider)
        }
        assertNull(r.actions.state.prompt); r.assertNoPorts()
        r.actions.begin(DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, "source-one")
        assertEquals("source-one", r.actions.state.prompt!!.request.providerId)
    }

    @Test fun unavailableOrBusyControllerCannotOpenAPromptOrRefresh() {
        val changes: List<(DiscoveryPrivacyAvailability) -> DiscoveryPrivacyAvailability> = listOf(
            { it.copy(ready = false) }, { it.copy(storageUnavailable = true) },
            { it.copy(busy = true) }, { it.copy(target = null) },
        )
        for (change in changes) {
            val r = Rig(); r.live = change(r.live)
            for (action in DiscoveryLocalAction.entries) {
                r.actions.begin(action, "source-one".takeIf { action == DiscoveryLocalAction.RESET_PROVIDER_PROGRESS })
            }
            r.actions.refresh()
            assertNull(r.actions.state.prompt); r.assertNoPorts()
        }
    }

    @Test fun readinessStorageAndBusyAreRecheckedAtConfirmation() {
        val changes: List<(DiscoveryPrivacyAvailability) -> DiscoveryPrivacyAvailability> = listOf(
            { it.copy(ready = false) }, { it.copy(storageUnavailable = true) }, { it.copy(busy = true) },
        )
        for (change in changes) {
            val r = Rig(); val prompt = r.begin(); r.live = change(r.live)
            assertFalse(r.actions.canConfirm(prompt)); r.actions.confirm(prompt)
            assertNull(r.actions.state.prompt); assertTrue(r.actions.state.stale); r.assertNoPorts()
        }
    }

    @Test fun everyTargetComponentIsFencedForAllActionsAndNeverRetargeted() {
        val r0 = Rig(); val target = r0.target
        val mismatches = listOf(null, target.copy(profileId = "profile-two"),
            target.copy(stamp = SessionStamp("session-two", target.stamp.epoch)),
            target.copy(stamp = target.stamp.copy(epoch = target.stamp.epoch + 1)),
            target.copy(viewRevision = target.viewRevision + 1),
            target.copy(controllerGeneration = target.controllerGeneration + 1))
        for (action in DiscoveryLocalAction.entries) for (mismatch in mismatches) for (reconcile in listOf(false, true)) {
            val r = Rig(); val prompt = r.begin(action)
            r.live = r.live.copy(target = mismatch)
            if (reconcile) r.actions.reconcile() else r.actions.confirm(prompt)
            assertNull(r.actions.state.prompt); assertTrue(r.actions.state.stale)
            r.live = r.live.copy(target = r.target)
            r.actions.confirm(prompt)
            r.assertNoPorts()
        }
    }

    @Test fun removedProviderInvalidatesOnlyItsScopedProgressPrompt() {
        val r = Rig(); val prompt = r.begin(DiscoveryLocalAction.RESET_PROVIDER_PROGRESS)
        r.live = r.live.copy(providerIds = setOf("source-one"))
        r.actions.reconcile(); r.actions.confirm(prompt)
        assertNull(r.actions.state.prompt); assertTrue(r.actions.state.stale); r.assertNoPorts()
        for (action in listOf(DiscoveryLocalAction.CLEAR_CURRENT_HISTORY, DiscoveryLocalAction.CLEAR_ALL_HISTORY)) {
            val history = Rig(); val clear = history.begin(action)
            history.live = history.live.copy(providerIds = emptySet())
            assertTrue(history.actions.canConfirm(clear)); history.actions.confirm(clear)
            assertEquals(listOf(clear.request), history.submitted)
        }
    }

    @Test fun confirmationCapabilityIsReadOnlyAndDoesNotRetireThePrompt() {
        val r = Rig(); val prompt = r.begin(); val count = r.publications.size
        repeat(3) { assertTrue(r.actions.canConfirm(prompt)) }
        r.live = r.live.copy(target = r.target.copy(viewRevision = 9))
        assertFalse(r.actions.canConfirm(prompt))
        assertSame(prompt, r.actions.state.prompt); assertEquals(count, r.publications.size)
        r.assertNoPorts()
    }

    @Test fun backgroundOrRouteExitRetiresUnsubmittedAuthority() {
        for (background in listOf(false, true)) for (reconcile in listOf(false, true)) {
            val r = Rig(); val prompt = r.begin()
            if (background) r.foreground = false else r.visible = false
            if (reconcile) r.actions.reconcile() else r.actions.confirm(prompt)
            r.actions.refresh(); r.actions.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY)
            assertNull(r.actions.state.prompt); assertFalse(r.actions.state.stale)
            r.foreground = true; r.visible = true; r.actions.confirm(prompt)
            r.assertNoPorts()
        }
    }

    @Test fun closedCallbacksHaveZeroPortsOrPublicationsAfterReturnToSameRoute() {
        val r = Rig(); val prompt = r.begin(); r.actions.close(); val count = r.publications.size
        val reads = r.availabilityReads
        repeat(2) {
            r.actions.close(); r.actions.confirm(prompt); r.actions.cancel(prompt); r.actions.reconcile()
            r.actions.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY); r.actions.refresh(); r.actions.dismissStale()
            assertFalse(r.actions.canConfirm(prompt))
        }
        assertNull(r.actions.state.prompt); assertFalse(r.actions.state.stale)
        assertEquals(count, r.publications.size); assertEquals(reads, r.availabilityReads); r.assertNoPorts()
        val next = r.actions(); next.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY)
        next.confirm(next.state.prompt!!)
        assertEquals(1, r.submitted.size)
    }

    @Test fun sameValuePromptsHaveDifferentAuthorityAndLateCallbacksCannotClearTheNewOne() {
        val r = Rig(); val old = r.begin(); r.actions.cancel(old); val next = r.begin()
        assertEquals(old.request, next.request); assertNotSame(old, next)
        r.actions.confirm(old); r.actions.cancel(old)
        assertFalse(r.actions.canConfirm(old)); assertSame(next, r.actions.state.prompt)
        r.assertNoPorts(); r.actions.confirm(next); assertEquals(listOf(next.request), r.submitted)
    }

    @Test fun forgedValueEqualPromptCannotConfirmOrCancelAnExistingPrompt() {
        val r = Rig(); val prompt = r.begin(); val forged = DiscoveryPrivacyPrompt(prompt.request)
        assertFalse(r.actions.canConfirm(forged)); r.actions.confirm(forged); r.actions.cancel(forged)
        assertSame(prompt, r.actions.state.prompt); r.assertNoPorts()
    }

    @Test fun repeatedOpeningAndRepeatedConfirmationOnlySubmitOnce() {
        val r = Rig(); val prompt = r.begin()
        r.actions.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY)
        assertSame(prompt, r.actions.state.prompt)
        repeat(4) { r.actions.confirm(prompt) }
        assertEquals(listOf(prompt.request), r.submitted); assertNull(r.actions.state.prompt)
    }

    @Test fun publicationReentryCannotConfirmAgainOpenAnotherPromptOrRefresh() {
        val r = Rig(); val prompt = r.begin()
        r.onChanged = {
            r.actions.confirm(prompt)
            r.actions.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY)
            r.actions.refresh()
        }
        r.actions.confirm(prompt)
        assertEquals(listOf(prompt.request), r.submitted); assertTrue(r.refreshed.isEmpty())
        assertNull(r.actions.state.prompt)
    }

    @Test fun portReentryIsFencedBeforeControllerCanExposeBusyState() {
        val r = Rig(); val prompt = r.begin()
        r.onSubmit = {
            assertFalse(r.live.busy)
            r.actions.confirm(prompt); r.actions.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY); r.actions.refresh()
        }
        r.actions.confirm(prompt)
        assertEquals(listOf(prompt.request), r.submitted); assertTrue(r.refreshed.isEmpty())
        assertNull(r.actions.state.prompt)
    }

    @Test fun liveGuardAfterPromptConsumptionRejectsTargetOrProviderChanges() {
        for (providerRemoved in listOf(false, true)) {
            val r = Rig(); val prompt = r.begin(DiscoveryLocalAction.RESET_PROVIDER_PROGRESS)
            r.onChanged = { state -> if (state.prompt == null) {
                r.live = if (providerRemoved) r.live.copy(providerIds = emptySet())
                else r.live.copy(target = r.target.copy(controllerGeneration = 12))
            } }
            r.actions.confirm(prompt)
            assertTrue(r.actions.state.stale); assertNull(r.actions.state.prompt); r.assertNoPorts()
        }
    }

    @Test fun liveGuardAfterPromptConsumptionRejectsUnavailableOrBusyController() {
        val changes: List<(DiscoveryPrivacyAvailability) -> DiscoveryPrivacyAvailability> = listOf(
            { it.copy(ready = false) }, { it.copy(storageUnavailable = true) }, { it.copy(busy = true) },
        )
        for (change in changes) {
            val r = Rig(); val prompt = r.begin()
            r.onChanged = { r.live = change(r.live) }
            r.actions.confirm(prompt)
            assertTrue(r.actions.state.stale); r.assertNoPorts()
        }
    }

    @Test fun disposalOrExitDuringPromptConsumptionCannotInvokeAPortOrPublishStale() {
        for (close in listOf(false, true)) {
            val r = Rig(); val prompt = r.begin()
            r.onChanged = { if (close) r.actions.close() else r.foreground = false }
            r.actions.confirm(prompt)
            assertFalse(r.actions.state.stale); assertNull(r.actions.state.prompt); r.assertNoPorts()
        }
    }

    @Test fun rejectedSubmissionReportsOnlyLocalStaleAndRetryRequiresFreshConfirmation() {
        val r = Rig(); r.accept = false; val old = r.begin(); r.actions.confirm(old)
        assertEquals(listOf(old.request), r.submitted); assertTrue(r.actions.state.stale)
        assertTrue(r.pending.isEmpty()); assertTrue(r.completed.isEmpty())
        r.actions.confirm(old); assertEquals(1, r.submitted.size)
        r.actions.dismissStale(); assertFalse(r.actions.state.stale)
        val next = r.begin(); assertNotSame(old, next); assertFalse(r.actions.state.stale)
        r.accept = true; r.actions.confirm(next); assertEquals(2, r.submitted.size)
    }

    @Test fun reopeningAfterStaleClearsOnlyTheViewNotice() {
        val r = Rig(); val old = r.begin(); r.live = r.live.copy(target = r.target.copy(viewRevision = 5))
        r.actions.reconcile(); assertTrue(r.actions.state.stale)
        val next = r.begin(); assertFalse(r.actions.state.stale)
        assertEquals(r.live.target, next.request.target)
        r.actions.confirm(old); assertSame(next, r.actions.state.prompt); r.assertNoPorts()
    }

    @Test fun rejectionAfterReentrantDisposalDoesNotPublishToTheOldView() {
        val r = Rig(); r.accept = false; val prompt = r.begin()
        r.onSubmit = { r.actions.close() }
        r.actions.confirm(prompt)
        assertEquals(2, r.publications.size)
        assertFalse(r.actions.state.stale); assertNull(r.actions.state.prompt)
    }

    @Test fun refreshingOnlyUsesTheLocalReadPortAndDoesNotSubmitOrCreateAuthority() {
        val r = Rig(); r.actions.refresh()
        assertEquals(listOf(r.target), r.refreshed)
        assertTrue(r.submitted.isEmpty()); assertTrue(r.pending.isEmpty()); assertTrue(r.publications.isEmpty())
        assertNull(r.actions.state.prompt)
        val prompt = r.begin(); r.actions.refresh()
        assertSame(prompt, r.actions.state.prompt); assertEquals(listOf(r.target, r.target), r.refreshed)
        assertTrue(r.submitted.isEmpty())
    }

    @Test fun refreshUsesTheCurrentTargetWithoutRetargetingAnExistingConfirmation() {
        val r = Rig(); val prompt = r.begin()
        val nextTarget = r.target.copy(profileId = "profile-two", viewRevision = 12)
        r.live = r.live.copy(target = nextTarget); r.actions.refresh()
        assertEquals(listOf(nextTarget), r.refreshed); assertEquals(r.target, prompt.request.target)
        r.actions.confirm(prompt); assertTrue(r.submitted.isEmpty()); assertTrue(r.actions.state.stale)
    }

    @Test fun refreshPortReentryCannotRecursivelyReadOrOpenAnotherPrompt() {
        val r = Rig()
        r.onRefresh = { r.actions.refresh(); r.actions.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY) }
        r.actions.refresh()
        assertEquals(listOf(r.target), r.refreshed); assertTrue(r.submitted.isEmpty()); assertNull(r.actions.state.prompt)
    }

    @Test fun reentrantReconciliationDuringReadDoesNotRetireAStillValidPrompt() {
        val r = Rig(); val prompt = r.begin()
        r.onRefresh = { r.actions.reconcile() }
        r.actions.refresh()
        assertSame(prompt, r.actions.state.prompt); assertFalse(r.actions.state.stale)
        assertTrue(r.actions.canConfirm(prompt)); assertTrue(r.submitted.isEmpty())
    }

    @Test fun thrownPortsDoNotLeaveTheAdapterLockedOrInventAnOutcome() {
        val r = Rig(); val prompt = r.begin()
        r.onSubmit = { throw IllegalStateException("owned test failure") }
        try { r.actions.confirm(prompt); fail("The controller boundary owns submission failures") }
        catch (_: IllegalStateException) { }
        assertNull(r.actions.state.prompt); assertFalse(r.actions.state.stale); assertTrue(r.pending.isEmpty())
        r.onSubmit = {}; val next = r.begin(); assertNotSame(prompt, next)
        r.actions.cancel(next)
        r.onRefresh = { throw IllegalStateException("owned test failure") }
        try { r.actions.refresh(); fail("The controller boundary owns read failures") }
        catch (_: IllegalStateException) { }
        r.onRefresh = {}; r.actions.refresh(); assertEquals(2, r.refreshed.size)
    }

    @Test fun controllerBusySurvivesViewDisposalAndBlocksAReopenedAdapter() {
        val r = Rig(); val prompt = r.begin(); r.actions.confirm(prompt)
        assertTrue(r.live.busy); assertTrue(r.completed.isEmpty())
        r.actions.close(); val next = r.actions()
        next.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY); next.refresh()
        assertNull(next.state.prompt); assertEquals(1, r.pending.size)
        assertEquals(1, r.submitted.size); assertTrue(r.refreshed.isEmpty())
        r.finish()
        assertEquals(listOf(prompt.request), r.completed); assertFalse(r.live.busy)
        next.begin(DiscoveryLocalAction.CLEAR_ALL_HISTORY); assertNotNull(next.state.prompt)
    }

    @Test fun closingDuringAcceptedSubmissionDoesNotCancelOrOrphanTheWork() {
        val r = Rig(); val prompt = r.begin()
        r.onSubmit = { r.actions.close(); r.foreground = false }
        r.actions.confirm(prompt); assertEquals(1, r.pending.size)
        assertTrue(r.live.busy); assertNull(r.actions.state.prompt)
        r.finish(); assertEquals(listOf(prompt.request), r.completed); assertFalse(r.live.busy)
        assertFalse(r.actions.state.stale)
    }
}
