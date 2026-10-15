package com.easysavevideo.app.browser.privacy

import android.view.View
import android.webkit.WebView
import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.core.model.BrowserLoadState
import com.easysavevideo.core.model.ErrorCode

internal enum class PrivateBrowsingPresentationKind { BROWSER, APPLICATION }
internal enum class PrivateBrowsingRenderedContent { VIEW, EMPTY, ERROR_SHELL }

/** Mirrors the actual BrowserScreen content branch; a missing/evicted page is never an empty tab. */
internal fun privateBrowsingRenderedContent(
    loadState: BrowserLoadState,
    hasEntry: Boolean,
    hasAddress: Boolean,
    hasFullscreen: Boolean,
): PrivateBrowsingRenderedContent? = when {
    !hasEntry -> if (!hasAddress && loadState == BrowserLoadState.IDLE && !hasFullscreen)
        PrivateBrowsingRenderedContent.EMPTY else null
    loadState == BrowserLoadState.RENDERER_UNAVAILABLE -> null // Renderer loss retires the private session.
    loadState == BrowserLoadState.FAILED || loadState == BrowserLoadState.CANCELLED ->
        if (!hasFullscreen) PrivateBrowsingRenderedContent.ERROR_SHELL else null
    else -> PrivateBrowsingRenderedContent.VIEW
}

/**
 * Normal browsing identity, independent of READY-only media inspection/capture authority. Opaque
 * owners never contain browsing text and must not be persisted. Context is matched in memory only.
 * Document revision means the Host's document generation, not loading progress or page title.
 */
internal class PrivateBrowsingPresentationIdentity(
    val session: Any,
    val lease: Any,
    val controller: Any,
    val tab: Any,
    val context: BrowserContextRef,
    val epoch: Long,
    val interactionRevision: Long,
    val hostGeneration: Any,
    val document: Any?,
    val documentRevision: Long?,
    val webView: Any?,
    val fullscreenView: Any?,
    val kind: PrivateBrowsingPresentationKind = PrivateBrowsingPresentationKind.BROWSER,
    val renderedContent: PrivateBrowsingRenderedContent = if (webView == null)
        PrivateBrowsingRenderedContent.EMPTY else PrivateBrowsingRenderedContent.VIEW,
    val errorShellState: BrowserLoadState? = null,
    val errorShellCode: ErrorCode? = null,
) {
    fun sameAs(other: PrivateBrowsingPresentationIdentity): Boolean =
        session === other.session && lease === other.lease && controller === other.controller &&
            tab === other.tab && context == other.context && epoch == other.epoch &&
            interactionRevision == other.interactionRevision && hostGeneration === other.hostGeneration &&
            document === other.document && documentRevision == other.documentRevision &&
            webView === other.webView && fullscreenView === other.fullscreenView && kind == other.kind &&
            renderedContent == other.renderedContent && errorShellState == other.errorShellState &&
            errorShellCode == other.errorShellCode

    override fun toString() = "PrivateBrowsingPresentationIdentity([redacted])"
}

/**
 * Host-issued normal-private claim. Empty/loading/error pages and legitimate fullscreen are valid;
 * none of these fields authorizes a capture exception, input, unmasking or native-surface safety.
 * The root owner separately proves actual attachment, current composition, frame and platform gates.
 */
internal class PrivateBrowsingPresentationClaim internal constructor(
    internal val identity: PrivateBrowsingPresentationIdentity,
    val presentationIdentity: Any = Any(),
) {
    val kind: PrivateBrowsingPresentationKind get() = identity.kind
    val renderedContent: PrivateBrowsingRenderedContent get() = identity.renderedContent
    val targetIdentity: Any get() = identity.tab
    val context: BrowserContextRef get() = identity.context
    val epoch: Long get() = identity.epoch
    val interactionRevision: Long get() = identity.interactionRevision
    val documentRevision: Long? get() = identity.documentRevision
    val webView: WebView? get() = if (kind == PrivateBrowsingPresentationKind.BROWSER &&
        renderedContent == PrivateBrowsingRenderedContent.VIEW) identity.webView as? WebView else null
    val fullscreenView: View? get() = if (kind == PrivateBrowsingPresentationKind.BROWSER &&
        renderedContent == PrivateBrowsingRenderedContent.VIEW) identity.fullscreenView as? View else null
    /** Null denotes the exact empty/error or non-browser app shell, never an arbitrary absent View. */
    val contentView: View? get() = fullscreenView ?: webView
    val isEmptyTab: Boolean get() = renderedContent == PrivateBrowsingRenderedContent.EMPTY
    override fun toString() = "PrivateBrowsingPresentationClaim([redacted])"
}

/** NONE requires positive complete release, not merely a phase other than ACTIVE. */
internal enum class PrivateBrowsingAudioDisposition {
    NO_CAPTURE_OWNER,
    AUTHORIZED_CONTINUOUS_CAPTURE,
    OWNED_OR_UNCERTAIN,
}

/**
 * Installed once by the main root owner, before private entry or View construction. All predicates
 * run only on the Host/main thread and must be short/read-only. The IME never calls this port.
 * Ready means installation and normal-browsing acceptance, not an uncovered root or capture grant.
 */
internal interface PrivateBrowserPresentationBinding {
    val inputLifetime: PrivateInputLifetime
    fun ready(): Boolean
    /** Exact installed root's synchronous cover; revoked input alone does not prove coverage. */
    fun isCovered(): Boolean = false
    fun isVisible(claim: PrivateBrowsingPresentationClaim): Boolean
    /** Include queued/native/STOPPING/BLOCKED/uncertain ordinary AND private capture obligations. */
    fun captureAudioDisposition(view: WebView): PrivateBrowsingAudioDisposition
}

/** A reattachment can reuse only the already-bound claim, never mint or restore claim authority. */
internal fun privateViewAttachmentAllowed(
    claim: PrivateBrowsingPresentationClaim,
    boundClaim: PrivateBrowsingPresentationClaim?,
    view: Any,
    covered: Boolean,
    inputAdmitted: Boolean,
    identityCurrent: Boolean,
): Boolean = covered && !inputAdmitted && boundClaim === claim && identityCurrent &&
    claim.kind == PrivateBrowsingPresentationKind.BROWSER &&
    claim.renderedContent == PrivateBrowsingRenderedContent.VIEW &&
    (view === claim.identity.webView || view === claim.identity.fullscreenView)

/** Recording track selection does not decide whether the current visible webpage may play audio. */
internal fun privateBrowsingAudioAllowed(
    disposition: PrivateBrowsingAudioDisposition,
    normalPresentationVisible: () -> Boolean,
    existingIntegrationAllows: () -> Boolean,
): Boolean = when (disposition) {
    PrivateBrowsingAudioDisposition.NO_CAPTURE_OWNER,
    PrivateBrowsingAudioDisposition.AUTHORIZED_CONTINUOUS_CAPTURE -> normalPresentationVisible() && existingIntegrationAllows()
    PrivateBrowsingAudioDisposition.OWNED_OR_UNCERTAIN -> false
}

/** Host-owned subset of ordinary departure proof. The main owner adds dialogs/capture/composition. */
internal data class PrivateHostOrdinaryDepartureReadback(
    val ordinaryCurrent: Boolean,
    val privateViewsDetached: Boolean,
    val privateCallbacksRetired: Boolean,
)
