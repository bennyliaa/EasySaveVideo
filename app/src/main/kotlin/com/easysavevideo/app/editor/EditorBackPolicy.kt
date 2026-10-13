package com.easysavevideo.app.editor

import com.easysavevideo.app.editor.ui.EditorPhase
import com.easysavevideo.app.editor.ui.EditorStopFeedback

internal enum class EditorBackDecision { LEGACY, CONFIRM_STOP, CHECK_PUBLICATION }

/** A presentation phase is not the atomic publication admission decision. */
internal fun editorBackDecision(phase: EditorPhase): EditorBackDecision = when (phase) {
    EditorPhase.PROCESSING, EditorPhase.VERIFYING -> EditorBackDecision.CONFIRM_STOP
    EditorPhase.PUBLISHING -> EditorBackDecision.CHECK_PUBLICATION
    else -> EditorBackDecision.LEGACY
}

/** Opaque, in-memory capabilities. Never save these or put them in EditorViewState. */
internal class EditorBackPage internal constructor()
internal class EditorBackConfirmation internal constructor()

internal sealed interface EditorBackRequest {
    data object Ignored : EditorBackRequest
    data object Legacy : EditorBackRequest
    data object CheckPublication : EditorBackRequest
    class ConfirmStop internal constructor(val confirmation: EditorBackConfirmation) : EditorBackRequest
}

/**
 * Main-thread-only, bounded to one page and one dialog. Contains no Activity, navigation entry,
 * callback or saved-state authority. The host separately checks its exact live navigation entry.
 */
internal class EditorBackGate {
    private class Pending(val page: EditorBackPage, val request: EditorBackRequest.ConfirmStop,
        val cancellation: EditPublicationCancellation, val operation: String)
    private var currentPage: EditorBackPage? = null
    private var pending: Pending? = null

    fun openPage(): EditorBackPage {
        revoke()
        return EditorBackPage().also { currentPage = it }
    }
    fun isCurrent(page: EditorBackPage): Boolean = currentPage === page
    fun closePage(page: EditorBackPage) { if (isCurrent(page)) revoke() }
    fun revoke() { currentPage = null; pending = null }

    fun request(page: EditorBackPage, foreground: Boolean, phase: EditorPhase,
        cancellation: EditPublicationCancellation?, operation: String?): EditorBackRequest {
        if (!foreground || !isCurrent(page)) return EditorBackRequest.Ignored
        return when (editorBackDecision(phase)) {
            EditorBackDecision.LEGACY -> { pending = null; EditorBackRequest.Legacy }
            EditorBackDecision.CHECK_PUBLICATION -> { pending = null; EditorBackRequest.CheckPublication }
            EditorBackDecision.CONFIRM_STOP -> {
                if (cancellation == null || operation == null) return EditorBackRequest.Ignored
                val previous = pending
                if (previous != null && previous.page === page && previous.cancellation === cancellation &&
                    previous.operation == operation) previous.request
                else EditorBackRequest.ConfirmStop(EditorBackConfirmation()).also {
                    pending = Pending(page, it, cancellation, operation)
                }
            }
        }
    }

    /** Dismiss/Continue Processing revoke only this dialog; neither is a cancellation command. */
    fun dismiss(page: EditorBackPage, confirmation: EditorBackConfirmation) {
        val issued = pending ?: return
        if (issued.page === page && issued.request.confirmation === confirmation) pending = null
    }

    /** Consume before the caller performs any side effect, including submission retirement. */
    fun consume(page: EditorBackPage, confirmation: EditorBackConfirmation, foreground: Boolean,
        cancellation: EditPublicationCancellation?, operation: String?): EditPublicationCancellation? {
        val issued = pending ?: return null
        if (issued.page !== page || issued.request.confirmation !== confirmation) return null
        pending = null
        if (!foreground || !isCurrent(page) || cancellation !== issued.cancellation || operation != issued.operation) return null
        // No phase gate here: the same operation can have progressed to PUBLISHING. Its token
        // decides ACCEPTED / ALREADY_CANCELLED / TOO_LATE, never a cached UI phase.
        return issued.cancellation
    }

    /** A queued old Return must not cancel a new attempt or pop a newer entry. */
    fun consumeLegacy(page: EditorBackPage, foreground: Boolean, phase: EditorPhase): Boolean {
        if (!foreground || !isCurrent(page) || editorBackDecision(phase) != EditorBackDecision.LEGACY) return false
        revoke()
        return true
    }
}

/** Display only. Timers, native ownership, cancellation and publication remain unchanged. */
internal fun editorUnconfirmedStopFeedback(identity: Any,
    activeCancellation: EditPublicationCancellation?): EditorStopFeedback? {
    if (activeCancellation == null || activeCancellation !== identity) return null
    return if (activeCancellation.publicationAdmitted) EditorStopFeedback.CHECKING_RESULT
        else EditorStopFeedback.WAITING_FOR_RELEASE
}
