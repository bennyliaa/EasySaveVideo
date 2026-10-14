package com.easysavevideo.app.discovery

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-wiring contracts only. These do not execute Android Handler, Compose, or SQLite. */
class DiscoveryLocalControlControllerSourceContractTest {
    private fun source(): String {
        val relative = "src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }

    @Test fun sourceContractUsesSameIoLaneExactPolicyAndReadOnlyRefresh() {
        val source = source()
        val control = source.substringAfter("fun confirmLocalControl(")
            .substringBefore("fun dismissLocalControlResult(")
        assertTrue(control.contains("localControlOwner.submit(request,"))
        assertTrue(control.contains("dispatch = { action -> io.execute(action); true }"))
        assertTrue(control.contains("osSignals.revalidate()"))
        assertTrue(control.contains("currentControlTarget(c)"))
        assertTrue(control.contains("DiscoveryPresentationPolicy.executeControl(captured, target,"))
        assertTrue(control.contains("c.status().providers.map { it.providerId }.toSet(), c::clearHistory"))
        assertTrue(control.contains("c.resetProgress(profileId, providerId)"))
        assertTrue(control.contains("refresh(readOnly = true)"))
        listOf("initialize(", "coordinator?.start(", "requestMore(", "pageDemandPending = true",
            "refresh()", "localControlState =").forEach { assertFalse(it, control.contains(it)) }
    }

    @Test fun sourceContractSeparatesMutationFromPostCommitPresentationMaintenance() {
        val control = source().substringAfter("fun confirmLocalControl(")
            .substringBefore("fun dismissLocalControlResult(")
        val work = control.substringAfter("work =").substringBefore("deliver =")
        val maintenance = control.substringAfter("afterWork =")
        assertTrue(work.contains("DiscoveryPresentationPolicy.executeControl("))
        assertFalse(work.contains("updateHistoryStatistics"))
        assertFalse(work.contains("refresh(readOnly = true)"))
        assertTrue(maintenance.contains("guarded {"))
        assertTrue(maintenance.contains("coordinator?.let(::updateHistoryStatistics)"))
        assertTrue(maintenance.contains("pageDemandPending = false"))
        assertTrue(maintenance.contains("nextPageDemand.cancel()"))
        assertFalse(maintenance.contains("executeControl("))
    }

    @Test fun sourceContractCompletionUsesIndependentMainHandlerEvenAfterClose() {
        val source = source()
        assertTrue(source.contains("private val localControlMain = Handler(Looper.getMainLooper())"))
        assertTrue(source.contains("private val localControlOwner = DiscoveryLocalControlOwner { localControlState = it }"))
        val delivery = source.substringAfter("fun confirmLocalControl(").substringAfter("deliver =")
            .substringBefore("afterWork =")
        assertTrue(delivery.contains("Looper.myLooper() == Looper.getMainLooper()"))
        assertTrue(delivery.contains("localControlMain.post(completion)"))
        assertFalse(delivery.contains("closed.get()"))
        val close = source.substringAfter("override fun close() {")
        assertTrue(close.contains("localControlOwner.close()"))
        assertTrue(close.contains("main.removeCallbacksAndMessages(null)"))
        assertFalse(close.contains("localControlMain.remove"))
    }

    @Test fun sourceContractLeavesGenericSubmitUnchanged() {
        assertTrue(source().contains("private fun submit(action: () -> Unit) { if (!closed.get()) runCatching { io.execute { if (!closed.get()) { osSignals.revalidate(); guarded(action) } } } }"))
    }
}
