package com.easysavevideo.app.browser

import com.easysavevideo.app.save.AndroidSaveRuntime
import com.easysavevideo.core.domain.BrowserSessionController
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Tests the existing explicit ordinary Resume eligibility relation; never reads real cookies. */
class BrowserSameDocumentResumeTest {
    @Test fun updatedModelRestoresSameOriginRelationWithoutAddingOriginAuthority() {
        val a = PageAddress("https://example.invalid/watch?item=1#start")
        val b = PageAddress("https://example.invalid/route?item=2#next")
        val owner = BrowserSessionController().also { it.openTab("tab", "session") }
        val request = owner.beginNavigation("tab", a, OperationContext("initial", 100, CancellationSource()))
        owner.acceptPageFinished(request, a, false, false, 1)
        val actual = b.value
        fun eligible(url: String): Boolean {
            val page = owner.state.selectedTab!!
            return page.loadState == BrowserLoadState.READY && actual == page.address?.value &&
                AndroidSaveRuntime.sameOrigin(page.address?.value, url)
        }
        assertFalse(eligible("https://example.invalid/media.mp4"))
        val document = BrowserInspectionDocument().apply { started(); finished(a.value, a.value) }
        document.history(b.value, actual, false, true, true)
        assertTrue(BrowserSameDocumentUpdate.accept(owner, request, document, document.address, document.revision,
            canGoBack = { true }, canGoForward = { false }, viewUrl = { actual }, stillCurrent = { true }))
        assertTrue(eligible("https://example.invalid/media.mp4"))
        assertTrue(eligible("https://EXAMPLE.invalid:443/media.mp4"))
        for (url in listOf("https://other.invalid/media.mp4", "https://cdn.example.invalid/media.mp4",
            "http://example.invalid/media.mp4", "https://example.invalid:444/media.mp4",
            "https://user:secret@example.invalid/media.mp4")) assertFalse(eligible(url))
    }
}
