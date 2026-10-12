package com.easysavevideo.app.save

import com.easysavevideo.core.model.BrowserContextRef
import org.junit.Assert.*
import org.junit.Test

class SaveInspectionPageTest {
    private val view = Any()
    private fun page(context: BrowserContextRef = BrowserContextRef("tab", "session"), epoch: Long = 2,
        ready: Boolean = true, address: String? = "https://example.invalid/watch", viewUrl: String? = address,
        viewIdentity: Any? = view, revision: Long = 7) = SaveInspectionPage(context, epoch, ready, address, viewUrl, viewIdentity, revision)
    @Test fun sameLivePageMatchesOnlyInForeground() {
        assertTrue(page().matches(page(), true)); assertFalse(page().matches(page(), false))
    }
    @Test fun tabSessionEpochAndSameDocumentRevisionChangesFenceOldInspection() {
        listOf(page(context = BrowserContextRef("other", "session")), page(context = BrowserContextRef("tab", "other")),
            page(epoch = 3), page(revision = 8)).forEach { assertFalse(page().matches(it, true)) }
    }
    @Test fun viewIdentityOrPageAddressChangeRequiresNewInspection() {
        assertFalse(page().matches(page(viewIdentity = Any()), true))
        assertFalse(page().matches(page(address = "https://example.invalid/other"), true))
        assertFalse(page().matches(page(viewUrl = "https://example.invalid/other"), true))
    }
    @Test fun absentLoadingOrMismatchedPageCannotBeInspected() {
        listOf(page(ready = false), page(address = null), page(viewUrl = null), page(viewIdentity = null)).forEach {
            assertFalse(it.isUsable()); assertFalse(page().matches(it, true)); assertFalse(it.matches(page(), true))
        }
        assertFalse(page().matches(null, true))
    }
    @Test fun candidateExpiryAndNavigationFenceReusedSelection() {
        var current = page()
        val initial = current
        val selection = PageSaveSelection(BrowserContextRef("tab", "session"), 2, "https://example.invalid/watch", emptyList(),
            System.currentTimeMillis() + 60_000) { initial.matches(current, true) }
        assertTrue(selection.isCurrent()); current = page(revision = 8); assertFalse(selection.isCurrent())
        val expired = PageSaveSelection(BrowserContextRef("tab", "session"), 2, "https://example.invalid/watch", emptyList(), 0) { true }
        assertFalse(expired.isCurrent())
    }
    @Test fun snapshotsDoNotExposePageDataInDiagnosticString() {
        assertFalse(page().toString().contains("example.invalid")); assertFalse(page().toString().contains("session"))
    }
}
