package com.easysavevideo.app.browser

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual Host source contracts only. Android callback/publish/profile behavior remains NOT_RUN. */
class BrowserPrivateSameDocumentContractTest {
    private fun source(path: String): String {
        val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
        return File(root, "src/main/kotlin/com/easysavevideo/app/$path").readText()
    }
    private fun history() = source("browser/SystemBrowserHost.kt")
        .substringAfter("override fun doUpdateVisitedHistory").substringBefore("override fun onReceivedError")

    @Test fun hostCapturesExactPrivateOwnersBeforeAnyObservationReadAndRechecksAfterOtherNativeWork() {
        val history = history()
        val firstRead = history.indexOf("entry.inspectionDocument.history(url, view.url")
        for (capture in listOf("val owner = entry.owner", "val request = current(entry)",
            "val session = entry.session", "val lease = entry.lease", "val document = entry.inspectionDocument",
            "val memory = privateNavigation[entry.context.tabId]")) {
            assertTrue(capture, history.indexOf(capture) in 0 until firstRead)
        }
        val branch = history.substringAfter("if (session != null && lease != null && request != null && memory != null && owner === privateController)")
            .substringBefore("} else if")
        for (required in listOf("BrowserSameDocumentUpdate.acceptPrivate(owner, request, document,",
            "observedAddress, observedRevision, memory, viewUrl = { view.url }",
            "view === entry.view", "entries[entry.context.tabId] === entry", "entry.owner === owner",
            "owner === privateController", "entry.session === session && session === privateSession",
            "entry.lease === lease && lease === privateLease && lease.isLive() && privateRuntime.owns(session)",
            "privateViews[view] === session", "current(entry) === request && !entry.suppressed",
            "entry.admission?.hasStarted() == true", "entry.inspectionDocument === document",
            "document.revision == observedRevision && document.address == observedAddress",
            "privateNavigation[entry.context.tabId] === memory", "publish(persist = false)"))
            assertTrue(required, branch.contains(required))
        assertTrue(history.indexOf("syncVideoPresence()") < history.indexOf("BrowserSameDocumentUpdate.acceptPrivate"))
        assertTrue(history.indexOf("entry.bookmarkAddress =") < history.indexOf("syncVideoPresence()"))
        for (forbidden in listOf("privateMode", "foreground", "isSelected", "owner === controller",
            "canGoBack", "canGoForward", "getOrPut", "memory.committed")) assertFalse(forbidden, branch.contains(forbidden))
    }

    @Test fun privateSeamValidatesBoundedSnapshotAfterNativeReadThenCommitsWithoutCallbackGap() {
        val seam = source("browser/BrowserSameDocumentUpdate.kt").substringAfter("fun acceptPrivate(")
        for (required in listOf("stillCurrent() && owner.isCurrent(request)",
            "it.context == request.context && it.incognito && it.loadState == BrowserLoadState.READY",
            "document.revision == observedRevision && document.address == observedAddress",
            "memory.addresses.size !in 1..128", "memory.index !in memory.addresses.indices",
            "val position = memory.index", "val addresses = memory.addresses.toList()",
            "val back = memory.canBack()", "val forward = memory.canForward()",
            "try { viewUrl() } catch (_: Exception) { return false }",
            "if (!current() || actual != observedAddress || memory.index != position ||",
            "memory.addresses != addresses || memory.canBack() != back || memory.canForward() != forward"))
            assertTrue(required, seam.contains(required))
        assertTrue(seam.indexOf("val addresses =") < seam.indexOf("try { viewUrl() }"))
        assertTrue(seam.indexOf("try { viewUrl() }") < seam.indexOf("if (!current() || actual"))
        assertTrue(seam.indexOf("if (!current() || actual") < seam.indexOf("owner.acceptPrivateSameDocument"))
        val mutation = seam.substringAfter("if (!owner.acceptPrivateSameDocument(request, address, back, forward)) return false").trim()
        assertEquals("memory.committed(address.value, position)\n        return true\n    }\n}", mutation)
        for (forbidden in listOf("canGoBack", "canGoForward", "copyBackForwardList", "Cookie", "evaluateJavascript",
            "stopReason", "beginNavigation", "acceptPageFinished", "getOrPut", "publish")) assertFalse(forbidden, seam.contains(forbidden))
    }

    @Test fun acceptedPrivatePublicationIsLastAndCannotRollBackRetirementOrReplacement() {
        val history = history()
        val private = history.substringAfter("if (BrowserSameDocumentUpdate.acceptPrivate")
            .substringBefore("} else if")
        // The helper returns only after core+RAM commit. Publication may reenter/retire the session;
        // this real source branch contains no work after it, so no captured-owner rollback follows.
        assertTrue(private.trimEnd().endsWith("})) publish(persist = false)"))
        assertEquals(1, Regex("publish\\(persist = false\\)").findAll(history).count())
        assertFalse(history.contains("memory.committed")); assertFalse(history.contains("privateNavigation.getOrPut"))
        val publish = source("browser/SystemBrowserHost.kt").substringAfter("private fun publish(persist: Boolean = true)")
            .substringBefore("private fun requireMain")
        assertTrue(publish.contains("else controller.state"))
        assertTrue(publish.contains("resumeSelectedPrivateAudio(); syncVideoPresence()"))
        assertTrue(publish.contains("if (persist && !closed) onCheckpoint(ordinaryController.persistableSession())"))
    }

    @Test fun exactPrivateViewWasBoundBeforePublicationAndRetiredEntriesCannotPassCurrent() {
        val host = source("browser/SystemBrowserHost.kt")
        val live = host.substringAfter("private fun entryLive(entry: Entry)").substringBefore("private fun isSelected")
        for (required in listOf("!closed && !closing && !privacyBlocked && !retiringPrivate && !transitioning",
            "it.key == entry.context.tabId && it.value === entry", "released[entry.view] == null",
            "entry.owner.state.tabs.any { it.context == entry.context }", "entry.owner === privateController",
            "entry.session === privateSession && entry.lease === privateLease", "entry.lease?.isLive() == true && privateRuntime.owns(entry.session)"))
            assertTrue(required, live.contains(required))
        val current = host.substringAfter("private fun current(entry: Entry)").substringBefore("private fun verifiedMainAddress")
        assertTrue(current.contains("entryLive(entry) && it.context == entry.context && entry.owner.isCurrent(it)"))
        assertTrue(host.contains("privateViews[it] = session"))
        val runtime = source("browser/privacy/PrivateBrowsingRuntime.kt")
        assertTrue(runtime.contains("backend.bindNewView(session, view)"))
        val backend = source("browser/privacy/AndroidPrivateProfileBackend.kt")
        assertTrue(backend.contains("WebViewCompat.setProfile(view, session.name.value)"))
    }

    @Test fun refreshRehydrateAndAddressFieldStillConsumeUnifiedModelWithoutPrivatePersistence() {
        val host = source("browser/SystemBrowserHost.kt")
        val reload = host.substringAfter("fun reload()").substringBefore("fun stopSelected()")
        for (required in listOf("touched()", "owner.reload(", "loadOwned(", "privateNavigation[tab.context.tabId]?.index"))
            assertTrue(required, reload.contains(required))
        val rehydrate = host.substringAfter("private fun resumeSelectedView(").substringBefore("var state by")
        for (required in listOf("val address = tab.address", "owner.beginNavigation(tab.context.tabId, address, operation())",
            "privateNavigation[tab.context.tabId]?.index")) assertTrue(required, rehydrate.contains(required))
        val bundle = host.substringAfter("private fun saveHistory(entry: Entry)").substringBefore("private fun recreateEntry")
        val privateExit = bundle.indexOf("if (entry.session != null) return")
        val bundleCreation = bundle.indexOf("val bundle = Bundle()")
        assertTrue(privateExit >= 0); assertTrue(bundleCreation >= 0)
        assertTrue(privateExit < bundleCreation)
        val ui = source("ui/BrowserScreen.kt").substringAfter("LaunchedEffect(selected?.address?.value)").substringBefore("fun go()")
        assertTrue(ui.contains("if (!editing)")); assertTrue(ui.contains("address = selected?.address?.value.orEmpty()"))
    }

    @Test fun sourceCaptureAuthenticationAndContinuousRecordingAuthorityStayRetired() {
        val host = source("browser/SystemBrowserHost.kt"); val history = history()
        for (required in listOf("entry.session != null && !prepareEntryNavigation(entry)",
            "entry.captureDocumentIdentity = Any(); authenticationDocuments.invalidate(entry)",
            "jsDialogs.cancel(view)", "entry.inspectionDocument.history(url, view.url, isReload",
            "tab?.loadState == BrowserLoadState.READY")) assertTrue(required, history.contains(required))
        for (forbidden in listOf("historyRecorder?.", "inspectFromUserClick", "recordSeen", ".stop(", ".discard(",
            "beginNavigation(", "CookieManager", "captureSaveRequestContext", "retainedPrivateRequestAuthority"))
            assertFalse(forbidden, history.contains(forbidden))
        val retained = host.substringAfter("private fun retainedPrivateRequestAuthority(").substringBefore("internal fun captureAuthenticationOrigin")
        for (required in listOf("entry.session === session && privateSession === session",
            "entry.lease === lease && privateLease === lease", "lease.isLive() && privateRuntime.owns(session)",
            "entry.owner.state.selectedTab?.epoch == epoch", "entry.inspectionDocument.revision == document",
            "entry.inspectionDocument.address == pageAddress && entry.view.url == pageAddress"))
            assertTrue(required, retained.contains(required))
        val finish = host.substringAfter("override fun onPageFinished").substringBefore("override fun doUpdateVisitedHistory")
        assertTrue(finish.contains("if (tab.loadState != BrowserLoadState.LOADING) return"))
        val ramCommit = finish.indexOf("memory?.committed")
        val documentFinish = finish.indexOf("entry.inspectionDocument.finished")
        assertTrue(ramCommit >= 0); assertTrue(documentFinish >= 0)
        assertTrue(ramCommit < documentFinish)
    }
}
