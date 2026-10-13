package com.easysavevideo.app.browser

import com.easysavevideo.app.save.PageSaveSelection
import com.easysavevideo.app.save.SaveInspectionPage
import com.easysavevideo.core.model.BrowserContextRef
import org.junit.Assert.*
import org.junit.Test

/** Owned synthetic callbacks only; not an Android/WebView lifecycle acceptance test. */
class BrowserInspectionDocumentTest {
    private val a = "https://example.invalid/watch?item=1#start"
    private val b = "https://example.invalid/route?item=2#next"
    private val view = Any()
    private val context = BrowserContextRef("tab", "session")
    private fun loaded() = BrowserInspectionDocument().apply { started(); finished(a, a) }
    private fun snapshot(document: BrowserInspectionDocument, actual: String?, owner: Any = view,
        ref: BrowserContextRef = context, epoch: Long = 2, interaction: Long = 1, ready: Boolean = true) =
        SaveInspectionPage(ref, epoch, ready, document.address, actual, owner, interaction, document.revision)

    @Test fun unacceptedDocumentCannotBorrowReadyAddress() {
        val document = BrowserInspectionDocument()
        document.history(a, a, false, true, true)
        assertNull(document.address); assertFalse(snapshot(document, a).isUsable())
    }
    @Test fun exactFinishedAddressIsUsable() { assertTrue(snapshot(loaded(), a).isUsable()) }
    @Test fun hashPushAndReplaceRoutesCanBeExplicitlyInspected() {
        for (url in listOf("https://example.invalid/watch?item=1#changed", b)) {
            val document = loaded(); val old = snapshot(document, a)
            document.history(url, url, false, true, true)
            val current = snapshot(document, url)
            assertTrue(current.isUsable()); assertEquals(url, current.pageUrl)
            assertFalse(old.matches(current, true)); assertTrue(current.matches(snapshot(document, url), true))
        }
    }
    @Test fun sameAddressHistoryAlsoRetiresCapturedWork() {
        val document = loaded(); val old = snapshot(document, a)
        document.history(a, a, false, true, true)
        assertTrue(snapshot(document, a).isUsable()); assertFalse(old.matches(snapshot(document, a), true))
    }
    @Test fun awayAndBackNeverRevivesOldCandidate() {
        val document = loaded(); var actual = a; val captured = snapshot(document, actual)
        val selection = PageSaveSelection(context, 2, a, emptyList(), Long.MAX_VALUE) {
            captured.matches(snapshot(document, actual), true)
        }
        assertTrue(selection.isCurrent())
        actual = b; document.history(actual, actual, false, true, true); assertFalse(selection.isCurrent())
        actual = a; document.history(actual, actual, false, true, true); assertFalse(selection.isCurrent())
        assertTrue(snapshot(document, actual).isUsable())
    }
    @Test fun currentViewMustMatchEvenBeforeHistoryCallbackArrives() {
        val document = loaded(); val captured = snapshot(document, a)
        assertFalse(snapshot(document, b).isUsable()); assertFalse(captured.matches(snapshot(document, b), true))
    }
    @Test fun mismatchedNativeCallbackRetiresWithoutAdmittingAddress() {
        val document = loaded(); val captured = snapshot(document, a)
        document.history(b, a, false, true, true)
        assertNull(document.address); assertFalse(captured.matches(snapshot(document, a), true))
    }
    @Test fun reloadLoadingAndRetiredRequestCannotAdmitHistory() {
        for ((reload, current, ready) in listOf(Triple(true, true, true), Triple(false, false, true), Triple(false, true, false))) {
            val document = loaded(); document.history(b, b, reload, current, ready); assertNull(document.address)
        }
    }
    @Test fun crossOriginSchemePortCredentialsAndInvalidAddressesAreRejected() {
        for (url in listOf("https://other.invalid/watch", "http://example.invalid/watch", "https://example.invalid:444/watch",
            "https://user:secret@example.invalid/watch", "javascript:alert(1)", "data:text/html,test", " https://example.invalid/watch", "https://example.invalid/watch ")) {
            val document = loaded(); document.history(url, url, false, true, true); assertNull(url, document.address)
        }
    }
    @Test fun invalidFinishedAddressCannotBecomeOriginAnchor() {
        for ((url, actual) in listOf(a to b, "javascript:alert(1)" to "javascript:alert(1)")) {
            val document = BrowserInspectionDocument(); document.finished(url, actual)
            document.history(a, a, false, true, true); assertNull(document.address)
        }
    }
    @Test fun startedDropsPriorOriginUntilNewAcceptedFinish() {
        val document = loaded(); val old = snapshot(document, a)
        document.started(); document.history(a, a, false, true, true)
        assertNull(document.address); assertFalse(old.matches(snapshot(document, a), true))
        document.finished(b, b); assertTrue(snapshot(document, b).isUsable())
    }
    @Test fun otherDocumentHistoryDoesNotRetireSelectedDocument() {
        val selected = loaded(); val background = loaded(); val captured = snapshot(selected, a)
        background.history(b, b, false, true, true)
        assertTrue(captured.matches(snapshot(selected, a), true))
    }
    @Test fun routeSnapshotStillRejectsOwnerContextEpochInteractionAndForegroundChanges() {
        val document = loaded(); document.history(b, b, false, true, true); val captured = snapshot(document, b)
        for (current in listOf(snapshot(document, b, owner = Any()), snapshot(document, b, ref = BrowserContextRef("other", "session")),
            snapshot(document, b, ref = BrowserContextRef("tab", "other")), snapshot(document, b, epoch = 3),
            snapshot(document, b, interaction = 2), snapshot(document, b, ready = false))) assertFalse(captured.matches(current, true))
        assertFalse(captured.matches(snapshot(document, b), false)); assertFalse(captured.matches(null, true))
    }
    @Test fun diagnosticsNeverExposeDocumentAddress() {
        val document = loaded(); assertFalse(document.toString().contains(a)); assertFalse(snapshot(document, a).toString().contains(a))
    }
}
