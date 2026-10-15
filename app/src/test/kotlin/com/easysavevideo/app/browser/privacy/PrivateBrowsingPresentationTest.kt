package com.easysavevideo.app.browser.privacy

import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.core.model.BrowserLoadState
import com.easysavevideo.core.model.ErrorCode
import org.junit.Assert.*
import org.junit.Test

/** Production identity/audio predicates only. No Android attachment, provider or privacy evidence. */
class PrivateBrowsingPresentationTest {
    private class Fixture {
        val session = Any(); val lease = Any(); val controller = Any(); val tab = Any()
        val context = BrowserContextRef("tab", "session"); val generation = Any()
        val document = Any(); val webView = Any(); val fullscreen = Any()
        fun identity(session: Any = this.session, lease: Any = this.lease,
            controller: Any = this.controller, tab: Any = this.tab,
            context: BrowserContextRef = this.context, epoch: Long = 7L, interaction: Long = 9L,
            generation: Any = this.generation, document: Any? = this.document,
            revision: Long? = 3L, webView: Any? = this.webView, fullscreen: Any? = null,
            kind: PrivateBrowsingPresentationKind = PrivateBrowsingPresentationKind.BROWSER,
            rendered: PrivateBrowsingRenderedContent = if (webView == null)
                PrivateBrowsingRenderedContent.EMPTY else PrivateBrowsingRenderedContent.VIEW,
            errorState: BrowserLoadState? = null, errorCode: ErrorCode? = null) =
            PrivateBrowsingPresentationIdentity(session, lease, controller, tab, context, epoch,
                interaction, generation, document, revision, webView, fullscreen, kind,
                rendered, errorState, errorCode)
    }
    @Test fun everyOwnerAndDocumentDimensionIsExact() {
        val f = Fixture(); val expected = f.identity()
        assertTrue(expected.sameAs(f.identity()))
        listOf(f.identity(session = Any()), f.identity(lease = Any()), f.identity(controller = Any()),
            f.identity(tab = Any()), f.identity(context = BrowserContextRef("tab", "other-session")),
            f.identity(epoch = 8), f.identity(interaction = 10), f.identity(generation = Any()),
            f.identity(document = Any()), f.identity(revision = 4), f.identity(webView = Any()),
            f.identity(fullscreen = Any())).forEach { assertFalse(expected.sameAs(it)) }
    }
    @Test fun emptyShellBindsItsOwnExactTabAndCannotBecomeAViewOrFullscreen() {
        val f = Fixture()
        val empty = f.identity(document = null, revision = null, webView = null)
        assertTrue(empty.sameAs(f.identity(document = null, revision = null, webView = null)))
        assertFalse(empty.sameAs(f.identity(document = null, revision = null, webView = null, tab = Any())))
        assertFalse(empty.sameAs(f.identity()))
        assertFalse(empty.sameAs(f.identity(document = null, revision = null, webView = null, fullscreen = f.fullscreen)))
        assertTrue(PrivateBrowsingPresentationClaim(empty).isEmptyTab)
    }
    @Test fun fullscreenIsAnAdditionalExactIdentity() {
        val f = Fixture(); val fullscreen = f.identity(fullscreen = f.fullscreen)
        assertTrue(fullscreen.sameAs(f.identity(fullscreen = f.fullscreen)))
        assertFalse(fullscreen.sameAs(f.identity(fullscreen = Any())))
        assertFalse(fullscreen.sameAs(f.identity()))
    }
    @Test fun applicationClaimRetainsDocumentIdentityButCannotExposeItsUnderlyingView() {
        val f = Fixture(); val app = f.identity(kind = PrivateBrowsingPresentationKind.APPLICATION)
        assertFalse(app.sameAs(f.identity()))
        assertSame(f.webView, app.webView)
        val claim = PrivateBrowsingPresentationClaim(app)
        assertEquals(PrivateBrowsingPresentationKind.APPLICATION, claim.kind)
        assertNull(claim.webView); assertNull(claim.fullscreenView); assertNull(claim.contentView)
        assertFalse(claim.isEmptyTab)
    }
    @Test fun newClaimHasFreshPresentationEvenWhenLogicalIdentityMatches() {
        val f = Fixture(); val first = PrivateBrowsingPresentationClaim(f.identity())
        val second = PrivateBrowsingPresentationClaim(f.identity())
        assertNotSame(first.presentationIdentity, second.presentationIdentity)
        assertTrue(first.identity.sameAs(second.identity))
        assertEquals("PrivateBrowsingPresentationClaim([redacted])", first.toString())
        assertEquals("PrivateBrowsingPresentationIdentity([redacted])", first.identity.toString())
        // Progress/title/READY media-inspection fields are intentionally not presentation owners.
    }
    @Test fun attachmentRequiresCoveredUnadmittedExactBoundCurrentBrowserView() {
        val f = Fixture(); val claim = PrivateBrowsingPresentationClaim(f.identity())
        fun allowed(bound: PrivateBrowsingPresentationClaim? = claim, view: Any = f.webView,
            covered: Boolean = true, admitted: Boolean = false, current: Boolean = true) =
            privateViewAttachmentAllowed(claim, bound, view, covered, admitted, current)
        assertTrue(allowed())
        assertFalse(allowed(bound = null))
        assertFalse(allowed(bound = PrivateBrowsingPresentationClaim(f.identity())))
        assertFalse(allowed(view = Any()))
        assertFalse(allowed(covered = false))
        assertFalse(allowed(admitted = true))
        assertFalse(allowed(current = false))
        // A stale claim cannot use a replacement bound claim even if its logical identity matches.
        assertFalse(allowed(bound = PrivateBrowsingPresentationClaim(claim.identity,
            claim.presentationIdentity)))
    }
    @Test fun attachmentAllowsOnlyExactFullscreenAndNeverApplicationOrEmptyClaim() {
        val f = Fixture(); val fullscreen = PrivateBrowsingPresentationClaim(f.identity(fullscreen = f.fullscreen))
        assertTrue(privateViewAttachmentAllowed(fullscreen, fullscreen, f.fullscreen, true, false, true))
        assertTrue(privateViewAttachmentAllowed(fullscreen, fullscreen, f.webView, true, false, true))
        assertFalse(privateViewAttachmentAllowed(fullscreen, fullscreen, Any(), true, false, true))
        val application = PrivateBrowsingPresentationClaim(f.identity(kind = PrivateBrowsingPresentationKind.APPLICATION))
        assertFalse(privateViewAttachmentAllowed(application, application, f.webView, true, false, true))
        val empty = PrivateBrowsingPresentationClaim(f.identity(document = null, revision = null, webView = null))
        assertFalse(privateViewAttachmentAllowed(empty, empty, f.webView, true, false, true))
    }
    @Test fun failedAndCancelledRetainedPagesRenderTypedErrorShells() {
        for (state in listOf(BrowserLoadState.FAILED, BrowserLoadState.CANCELLED)) {
            for (hasAddress in listOf(false, true)) {
                assertEquals(PrivateBrowsingRenderedContent.ERROR_SHELL,
                    privateBrowsingRenderedContent(state, true, hasAddress, false))
                assertNull(privateBrowsingRenderedContent(state, true, hasAddress, true))
            }
        }
        for (state in listOf(BrowserLoadState.IDLE, BrowserLoadState.LOADING, BrowserLoadState.READY)) {
            assertEquals(PrivateBrowsingRenderedContent.VIEW,
                privateBrowsingRenderedContent(state, true, true, false))
        }
    }
    @Test fun entryLessAddressAndRendererLossNeverMasqueradeAsPrivateShells() {
        assertEquals(PrivateBrowsingRenderedContent.EMPTY,
            privateBrowsingRenderedContent(BrowserLoadState.IDLE, false, false, false))
        for (state in BrowserLoadState.entries) {
            assertNull(privateBrowsingRenderedContent(state, false, true, false))
            if (state != BrowserLoadState.IDLE)
                assertNull(privateBrowsingRenderedContent(state, false, false, false))
        }
        assertNull(privateBrowsingRenderedContent(BrowserLoadState.IDLE, false, false, true))
        assertNull(privateBrowsingRenderedContent(BrowserLoadState.RENDERER_UNAVAILABLE, true, true, false))
    }
    @Test fun errorShellIdentityBindsStateErrorCategoryAndSourceDocument() {
        val f = Fixture()
        fun failed(code: ErrorCode = ErrorCode.NETWORK_TIMEOUT) = f.identity(
            rendered = PrivateBrowsingRenderedContent.ERROR_SHELL,
            errorState = BrowserLoadState.FAILED, errorCode = code)
        assertTrue(failed().sameAs(failed()))
        assertFalse(failed().sameAs(failed(ErrorCode.TLS_REJECTED)))
        assertFalse(failed().sameAs(f.identity(rendered = PrivateBrowsingRenderedContent.ERROR_SHELL,
            errorState = BrowserLoadState.CANCELLED, errorCode = ErrorCode.NETWORK_TIMEOUT)))
        assertFalse(failed().sameAs(f.identity()))
        listOf(f.identity(tab = Any(), rendered = PrivateBrowsingRenderedContent.ERROR_SHELL,
            errorState = BrowserLoadState.FAILED, errorCode = ErrorCode.NETWORK_TIMEOUT),
            f.identity(document = Any(), rendered = PrivateBrowsingRenderedContent.ERROR_SHELL,
                errorState = BrowserLoadState.FAILED, errorCode = ErrorCode.NETWORK_TIMEOUT),
            f.identity(webView = Any(), rendered = PrivateBrowsingRenderedContent.ERROR_SHELL,
                errorState = BrowserLoadState.FAILED, errorCode = ErrorCode.NETWORK_TIMEOUT)
        ).forEach { assertFalse(failed().sameAs(it)) }
        val claim = PrivateBrowsingPresentationClaim(failed())
        assertSame(f.webView, claim.identity.webView); assertSame(f.document, claim.identity.document)
        assertNull(claim.webView); assertNull(claim.fullscreenView); assertNull(claim.contentView)
        assertFalse(claim.isEmptyTab)
        assertFalse(privateViewAttachmentAllowed(claim, claim, f.webView, true, false, true))
    }
    @Test fun noCaptureOwnerNeedsBothNormalVisibilityAndExistingIntegrationGate() {
        for (visible in listOf(false, true)) for (integration in listOf(false, true)) {
            assertEquals(visible && integration, privateBrowsingAudioAllowed(
                PrivateBrowsingAudioDisposition.NO_CAPTURE_OWNER, { visible }, { integration }))
        }
    }
    @Test fun continuousCaptureAllowsSelectedVisiblePlaybackForEitherAudioTrackChoice() {
        for (recordingAudio in PrivateCaptureAudio.entries) {
            for (visible in listOf(false, true)) for (integration in listOf(false, true)) {
                assertEquals(recordingAudio.name, visible && integration, privateBrowsingAudioAllowed(
                    PrivateBrowsingAudioDisposition.AUTHORIZED_CONTINUOUS_CAPTURE, { visible }, { integration }))
            }
        }
    }

    @Test fun stoppingBlockedOrUncertainOwnershipCannotFallThroughToOrdinaryAudio() {
        assertFalse(privateBrowsingAudioAllowed(PrivateBrowsingAudioDisposition.OWNED_OR_UNCERTAIN,
            { error("must not query visibility") }, { error("must not query ordinary audio") }))
    }
}
