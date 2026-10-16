package com.easysavevideo.app.browser.privacy

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Actual adapter placement contracts only; production scheduling behavior is tested separately. */
class PrivateChildCompositionSourceContractTest {
    private fun source(path: String) = File("src/main/kotlin/com/easysavevideo/app/$path").readText()
    private fun ordered(value: String, vararg parts: String) {
        var after = 0
        parts.forEach { part ->
            val at = value.indexOf(part, after)
            assertTrue(part, at >= 0); after = at + part.length
        }
    }
    @Test fun mainOuterCannotAcknowledgeAndActualNavHostEntryIsCarriedToChild() {
        val main = source("MainActivity.kt")
        val outer = main.substringAfter("appComposeView.setContent {").substringBefore("EsvAppShell(")
        assertFalse(outer.contains("compositionApplied("))
        val shell = source("ui/shell/EsvAppShell.kt")
        assertTrue(shell.contains("content: @Composable (EsvDestination, NavBackStackEntry) -> Unit"))
        assertTrue(shell.contains("composable(route.route) { exactEntry -> content(route, exactEntry) }"))
        val child = main.substringAfter(") { route, routeEntry ->")
        for (part in listOf("routeEntry === privateBackStackEntry", "controller.currentBackStackEntry === routeEntry",
            "privatePresentationOwner.compositionApplied(presentation, childApplyTicket)", "if (!browserChild) SideEffect { childApplied() }"))
            assertTrue(part, child.contains(part))
        assertEquals(2, Regex("onPresentationApplied = childApplied, onPresentationLayout = childLaidOut").findAll(child).count())
    }
    @Test fun browserAcknowledgementLivesInsideActualAndroidViewSubcomposition() {
        val ui = source("ui/BrowserScreen.kt")
        ordered(ui, "BoxWithConstraints(Modifier.fillMaxSize().imePadding().onGloballyPositioned { onPresentationLayout() })",
            "AndroidView(factory", "AndroidView(factory", "SideEffect { onPresentationApplied() }")
        assertEquals(1, Regex("SideEffect \\{ onPresentationApplied\\(\\) }").findAll(ui).count())
    }
    @Test fun realViewEventsInvalidateAndRetireOldFrameButNeverDirectlyAcknowledge() {
        val owner = source("browser/privacy/PrivateMainPresentationOwner.kt")
        val observer = owner.substringAfter("private val attachmentObserver").substringBefore("override fun ready()")
        assertTrue(observer.contains("onViewAttachedToWindow(view: View) = hierarchyChanged()"))
        assertTrue(observer.contains("onViewDetachedFromWindow(view: View)"))
        assertFalse(observer.contains("compositionApplied(")); assertFalse(observer.contains("acknowledgeChildComposition("))
        val event = owner.substringAfter("fun hierarchyChanged()").substringBefore("private fun forgetDetachedViews()")
        ordered(event, "val discardOldFrame = !root.isCovered || current?.acknowledgedTurn != null",
            "invalidateChildComposition()", "if (discardOldFrame) guard.installProtectionBarrier()")
        val invalidate = owner.substringAfter("private fun invalidateChildComposition()").substringBefore("fun hierarchyChanged()")
        ordered(invalidate, "value.applied = false; value.acknowledgedTurn = null", "value.childScheduler.hierarchyChanged()")
        assertTrue(owner.contains("view.addOnAttachStateChangeListener(attachmentObserver)"))
    }
    @Test fun independentDialogNotifiesOnlyAfterRealSuccessfulUnregister() {
        val guard = source("browser/privacy/PrivateWindowGuard.kt")
        val unregister = guard.substringAfter("fun unregisterPrivateDialog(").substringBefore("private val closeComplete")
        ordered(unregister, "safeCheck(registration.teardownIsComplete)", "registration.guard::detachedEmptyDialogRoot",
            "registration.guard.close()", "val removed = dialogs.remove(registration)",
            "if (removed) attempt { onOwnedHierarchyChanged?.invoke() }", "return removed")
        assertFalse(unregister.contains("acknowledge"))
        val main = source("MainActivity.kt")
        val startMarker = "privateWindowGuard.onOwnedHierarchyChanged = {"
        val endMarker = "browser.installPrivatePresentationBinding"
        val start = main.indexOf(startMarker)
        val end = main.indexOf(endMarker, (start + startMarker.length).coerceAtLeast(0))
        assertTrue("Missing callback start", start >= 0)
        assertTrue("Missing or out-of-order callback boundary", end > start)
        val callback = main.substring(start + startMarker.length, end)
        ordered(callback, "privatePresentationOwner.hierarchyChanged()", "vaultHost?.hierarchyChanged()")
        assertFalse(callback.contains("compositionApplied("))
    }
}
