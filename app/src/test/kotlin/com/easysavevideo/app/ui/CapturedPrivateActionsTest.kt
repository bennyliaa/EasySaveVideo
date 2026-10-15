package com.easysavevideo.app.ui

import com.easysavevideo.app.browser.privacy.PrivateInputLifetime
import org.junit.Assert.*
import org.junit.Test

/** Production action scope and root lifetime; synthetic owners, no Android/Compose acceptance. */
class CapturedPrivateActionsTest {
    private class Fixture {
        val presentation = Any()
        val lifetime = PrivateInputLifetime(Any()).also { it.publish(presentation) }
        var route = Any()
        fun scope(): CapturedPrivateActions {
            val token = lifetime.acquire(Any())!!
            val expectedRoute = route
            return CapturedPrivateActions(true, token::isCurrent) { route === expectedRoute }
        }
    }

    @Test fun currentPrivateTaskAndChromeActionsStillRun() {
        val f = Fixture(); val actions = f.scope(); val called = mutableListOf<String>()
        listOf("back", "library", "record", "limits", "dismiss", "retry", "cancel-recovery",
            "cancel-pending", "select", "clear", "preview", "resume", "cancel-task",
            "failure", "authentication", "origin", "alternative", "cancel-copy", "reconcile", "chrome")
            .forEach { action -> actions.run { called += action } }
        assertEquals(20, called.size)
    }

    @Test fun coverRejectsEveryQueuedActionWithoutInvokingItsEffect() {
        val f = Fixture(); val actions = f.scope(); var calls = 0
        val queued = List(20) { { actions.run { calls++ } } }
        f.lifetime.revoke()
        queued.forEach { it() }
        assertFalse(actions.isCurrent()); assertEquals(0, calls)
    }

    @Test fun sameRootAndPresentationRepublishCannotReviveOldClick() {
        val f = Fixture(); val old = f.scope(); var calls = 0
        f.lifetime.revoke(); f.lifetime.publish(f.presentation)
        val fresh = f.scope()
        old.run { calls += 100 }; fresh.run { calls++ }
        assertFalse(old.isCurrent()); assertTrue(fresh.isCurrent()); assertEquals(1, calls)
    }

    @Test fun routeReplacementRejectsOldActionBeforeCompositionDisposal() {
        val f = Fixture(); val old = f.scope(); var calls = 0
        f.route = Any()
        old.run { calls++ }
        assertFalse(old.isCurrent()); assertEquals(0, calls)
        assertTrue(f.scope().isCurrent())
    }

    @Test fun equalRouteTextOnReturnIsANewOccurrenceAndCannotReviveOldAction() {
        data class Route(val text: String)
        val lifetime = PrivateInputLifetime(Any()).also { it.publish(Any()) }
        val token = lifetime.acquire(Any())!!
        var route = Route("tasks"); val captured = route
        val old = CapturedPrivateActions(true, token::isCurrent) { route === captured }
        route = Route("browser"); assertFalse(old.isCurrent())
        route = Route("tasks")
        assertEquals(captured, route); assertNotSame(captured, route); assertFalse(old.isCurrent())
        var calls = 0; old.run { calls++ }; assertEquals(0, calls)
    }

    @Test fun disposingTasksIsMonotonicEvenIfTheSameRouteObjectReturns() {
        val f = Fixture(); val oldRoute = f.route; val old = f.scope()
        old.close(); old.close(); f.route = Any(); f.route = oldRoute
        assertFalse(old.isCurrent())
        assertTrue(f.scope().isCurrent())
    }

    @Test fun childRetirementCannotRevokeLiveChromeOrRootAdmission() {
        val f = Fixture(); val chrome = f.scope()
        val tasks = CapturedPrivateActions(true, chrome::isCurrent) { true }
        tasks.close()
        assertFalse(tasks.isCurrent()); assertTrue(chrome.isCurrent()); assertTrue(f.lifetime.isAdmitted)
    }

    @Test fun newestBusinessCallbackDoesNotRescueAQueuedOldPrivateClick() {
        val f = Fixture(); val old = f.scope(); var calls = 0
        var currentBusinessCallback: () -> Unit = { calls += 100 }
        val queued = { old.run { currentBusinessCallback() } }
        f.lifetime.revoke(); f.lifetime.publish(f.presentation)
        currentBusinessCallback = { calls++ }
        queued(); assertEquals(0, calls)
        f.scope().run { currentBusinessCallback() }; assertEquals(1, calls)
    }

    @Test fun missingOrDeniedInputAndRouteOwnerFailClosed() {
        var calls = 0
        CapturedPrivateActions(true, { false }, { true }).run { calls++ }
        CapturedPrivateActions(true, { true }, { false }).run { calls++ }
        assertEquals(0, calls)
    }

    @Test fun ordinaryLivePathPreservesItsBusinessGuardsWithoutReadingPrivateInput() {
        var attached = true
        val actions = CapturedPrivateActions(false, { error("must not read private input") }, { attached })
        var businessReady = true; var calls = 0
        val existingBusinessAction = { if (businessReady) calls++ }
        actions.run(existingBusinessAction)
        businessReady = false; actions.run(existingBusinessAction)
        businessReady = true; attached = false; actions.run(existingBusinessAction)
        assertEquals(1, calls)
    }

    @Test fun disposedOrdinaryActionsCannotInvokeEvenWithTheSameLiveOwner() {
        val actions = CapturedPrivateActions(false, { true }, { true }); var calls = 0
        actions.close(); actions.run { calls++ }
        assertFalse(actions.isCurrent()); assertEquals(0, calls)
    }

    @Test fun ordinaryToPrivateAndModeAbaRejectQueuedChromeAndTaskCallbacks() {
        var privateMode = false; var epoch = 0L; val route = Any()
        fun scope(): CapturedPrivateActions {
            val capturedMode = privateMode; val capturedEpoch = epoch; val capturedRoute = route
            return CapturedPrivateActions(capturedMode, { true }) {
                privateMode == capturedMode && epoch == capturedEpoch && route === capturedRoute
            }
        }
        val ordinary = scope(); var navigations = 0; var cancellations = 0
        val queuedNavigation = { ordinary.run { navigations++ } }
        val queuedCancel = { ordinary.run { cancellations++ } }
        privateMode = true; epoch++
        queuedNavigation(); queuedCancel()
        privateMode = false; epoch++
        queuedNavigation(); queuedCancel()
        assertFalse(ordinary.isCurrent()); assertEquals(0, navigations); assertEquals(0, cancellations)
        scope().run { navigations++ }; assertEquals(1, navigations)
    }

    @Test fun alreadyAdmittedWorkMayFinishButNoLaterClickIsAdmitted() {
        val f = Fixture(); val actions = f.scope(); var calls = 0
        actions.run { f.lifetime.revoke(); calls++ }
        actions.run { calls++ }
        assertEquals(1, calls); assertFalse(actions.isCurrent())
    }
}
