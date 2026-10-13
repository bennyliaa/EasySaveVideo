package com.easysavevideo.app.library.destination

import com.easysavevideo.library.destination.OrdinaryDestinationAdmission

/** Terminal selection results, independent of any subsequent media task or public copy. */
internal sealed interface OrdinaryDestinationSelectionResult {
    data class Accepted(val admission: OrdinaryDestinationAdmission) : OrdinaryDestinationSelectionResult
    data object Cancelled : OrdinaryDestinationSelectionResult
    data object Rejected : OrdinaryDestinationSelectionResult
    data object Failed : OrdinaryDestinationSelectionResult
}

internal enum class OrdinaryDestinationSourceCheck { CURRENT, CHANGED, FAILED }

/** A source fence can fail (for example, its Activity host is gone) without stranding admission. */
internal fun ordinaryDestinationSourceCheck(isCurrent: () -> Boolean): OrdinaryDestinationSourceCheck = try {
    if (isCurrent()) OrdinaryDestinationSourceCheck.CURRENT else OrdinaryDestinationSourceCheck.CHANGED
} catch (_: Exception) {
    OrdinaryDestinationSourceCheck.FAILED
}

/** Main-thread receipt. Consume both callbacks before invoking either, including reentrant calls. */
internal class OrdinaryDestinationReceipt(
    accepted: (OrdinaryDestinationAdmission) -> Unit,
    terminal: (OrdinaryDestinationSelectionResult) -> Unit,
) {
    private var acceptedCallback: ((OrdinaryDestinationAdmission) -> Unit)? = accepted
    private var terminalCallback: ((OrdinaryDestinationSelectionResult) -> Unit)? = terminal

    fun complete(result: OrdinaryDestinationSelectionResult): Boolean {
        val onTerminal = terminalCallback ?: return false
        val onAccepted = acceptedCallback
        acceptedCallback = null
        terminalCallback = null
        try {
            if (result is OrdinaryDestinationSelectionResult.Accepted) onAccepted?.invoke(result.admission)
        } finally {
            onTerminal(result)
        }
        return true
    }
}
