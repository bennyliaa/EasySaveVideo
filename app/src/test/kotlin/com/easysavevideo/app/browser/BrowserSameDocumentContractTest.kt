package com.easysavevideo.app.browser

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source wiring only. Does not execute Compose, the Host or native WebView callbacks. */
class BrowserSameDocumentContractTest {
    private fun source(path: String): String {
        val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
        return File(root, "src/main/kotlin/com/easysavevideo/app/$path").readText()
    }

    @Test fun hostBindsAcceptedRevisionAndExactOrdinaryEntryToPostReadSynchronization() {
        val host = source("browser/SystemBrowserHost.kt")
        val history = host.substringAfter("override fun doUpdateVisitedHistory").substringBefore("override fun onReceivedError")
        for (required in listOf("val owner = entry.owner", "val request = current(entry)",
            "val observedAddress = entry.inspectionDocument.address", "val observedRevision = entry.inspectionDocument.revision",
            "entry.session == null && owner === ordinaryController && request != null",
            "BrowserSameDocumentUpdate.accept(owner, request, entry.inspectionDocument,",
            "observedAddress, observedRevision", "canGoBack = { view.canGoBack() }", "canGoForward = { view.canGoForward() }",
            "viewUrl = { view.url }", "view === entry.view && entry.owner === owner && owner === ordinaryController",
            "current(entry) === request && !entry.suppressed",
            "entry.admission?.hasStarted() == true", "})) publish()")) assertTrue(required, history.contains(required))
        assertTrue(history.indexOf("val request = current(entry)") < history.indexOf("entry.inspectionDocument.history"))
        assertTrue(history.indexOf("entry.inspectionDocument.history") < history.indexOf("val observedRevision"))
        assertTrue(history.indexOf("val observedRevision") < history.indexOf("BrowserSameDocumentUpdate.accept"))
        val synchronization = history.substringAfter("if (entry.session == null && owner === ordinaryController")
        assertFalse(synchronization.contains("isSelected")); assertFalse(synchronization.contains("foreground"))
        assertFalse(synchronization.contains(".accepts(")); assertFalse(synchronization.contains("stopReason"))
        val live = host.substringAfter("private fun entryLive(entry: Entry)").substringBefore("private fun isSelected")
        for (required in listOf("!closed && !closing", "it.key == entry.context.tabId && it.value === entry",
            "released[entry.view] == null", "entry.owner === ordinaryController && entry.lease == null"))
            assertTrue(required, live.contains(required))
        val current = host.substringAfter("private fun current(entry: Entry)").substringBefore("private fun verifiedMainAddress")
        assertTrue(current.contains("entryLive(entry) && it.context == entry.context && entry.owner.isCurrent(it)"))
    }

    @Test fun seamRevalidatesAfterEveryNativeReadAndOnlyThenMutatesController() {
        val seam = source("browser/BrowserSameDocumentUpdate.kt")
        for (required in listOf("stillCurrent() && owner.isCurrent(request)", "it.context == request.context && !it.incognito",
            "it.loadState == BrowserLoadState.READY", "document.revision == observedRevision && document.address == observedAddress",
            "back = canGoBack()", "forward = canGoForward()", "actual = viewUrl()",
            "catch (_: Exception) { return false }", "if (!current() || actual != observedAddress) return false"))
            assertTrue(required, seam.contains(required))
        assertTrue(seam.indexOf("actual = viewUrl()") < seam.indexOf("if (!current() || actual"))
        assertTrue(seam.indexOf("if (!current() || actual") < seam.indexOf("owner.acceptSameDocument"))
        for (forbidden in listOf("beginNavigation", "acceptPageFinished", "updateLoading", "stopReason", "Cookie", "evaluateJavascript", "copyBackForwardList"))
            assertFalse(forbidden, seam.contains(forbidden))
    }

    @Test fun existingAddressFieldKeepsDraftGateAndRefreshUsesTheSingleModel() {
        val ui = source("ui/BrowserScreen.kt")
        assertTrue(ui.contains("var address by remember(selected?.context?.tabId) { mutableStateOf(selected?.address?.value.orEmpty()) }"))
        assertTrue(ui.contains("var editing by remember(selected?.context?.tabId) { mutableStateOf(false) }"))
        val effect = ui.substringAfter("LaunchedEffect(selected?.address?.value)").substringBefore("fun go()")
        assertTrue(effect.contains("if (!editing)"))
        assertTrue(effect.indexOf("if (!editing)") < effect.indexOf("address = selected?.address?.value.orEmpty()"))
        assertTrue(ui.contains("browser.reload()"))
        val host = source("browser/SystemBrowserHost.kt")
        val reload = host.substringAfter("fun reload()").substringBefore("fun back()")
        assertTrue(reload.contains("touched()")); assertTrue(reload.contains("owner.reload(")); assertTrue(reload.contains("loadOwned("))
        val owned = host.substringAfter("private fun loadOwned(").substringBefore("override fun stop(")
        assertTrue(owned.contains("request.address")); assertTrue(owned.contains("entry.view.loadUrl(address.value)"))
    }

    @Test fun checkpointAndExplicitResumeKeepTheirExistingOrdinaryOriginBoundary() {
        val host = source("browser/SystemBrowserHost.kt")
        val publish = host.substringAfter("private fun publish(persist: Boolean = true)").substringBefore("private fun requireMain")
        assertTrue(publish.contains("else controller.state"))
        assertTrue(publish.contains("onCheckpoint(ordinaryController.persistableSession())"))
        val runtime = source("save/AndroidSaveRuntime.kt")
        val lease = runtime.substringAfter("private fun leaseFor(").substringBefore("fun canSave(")
        assertTrue(lease.contains("if (browser.isPrivateMode) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)"))
        assertTrue(lease.contains("page?.loadState == BrowserLoadState.READY && view?.url == page.address?.value && sameOrigin(page.address?.value, url.value)"))
        assertTrue(lease.indexOf("if (browser.isPrivateMode)") < lease.indexOf("CookieManager.getInstance().getCookie"))
        val resume = runtime.substringAfter("private fun continueResumedAdmission(").substringBefore("fun canSave(")
        assertTrue(resume.contains("if (record.request.isPrivateNetwork && record.transfer == null)"))
        assertTrue(resume.contains("entry.refreshForExplicitResume()"))
        assertTrue(resume.contains("} else if (allowNetworkLease) source?.let { leaseFor(it.url, browser) } else null"))
    }

    @Test fun historyKeepsInspectionInvalidationAndDoesNotCreateVisitSaveOrRecordingCommands() {
        val host = source("browser/SystemBrowserHost.kt")
        val history = host.substringAfter("override fun doUpdateVisitedHistory").substringBefore("override fun onReceivedError")
        for (required in listOf("entry.captureDocumentIdentity = Any(); authenticationDocuments.invalidate(entry)",
            "entry.inspectionDocument.history(url, view.url, isReload", "current(entry) != null && !entry.suppressed",
            "entry.admission?.hasStarted() == true", "tab?.loadState == BrowserLoadState.READY"))
            assertTrue(required, history.contains(required))
        for (forbidden in listOf("historyRecorder?.", "inspectFromUserClick", "recordSeen", ".stop(", ".discard(", "beginNavigation(", "CookieManager"))
            assertFalse(forbidden, history.contains(forbidden))
        val finish = host.substringAfter("override fun onPageFinished").substringBefore("override fun doUpdateVisitedHistory")
        assertTrue(finish.contains("verifiedMainAddress(entry, url) ?: return"))
        assertTrue(finish.contains("entry.admission?.accepts(address.value) != true"))
        assertTrue(finish.contains("if (tab.loadState != BrowserLoadState.LOADING) return"))
    }
}
