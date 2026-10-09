package com.easysavevideo.app.library.destination

import com.easysavevideo.library.destination.OrdinaryDestinationChoice
import com.easysavevideo.library.destination.OrdinaryDestinationPreference

/** One visible creation intent at a time. A callback can consume its selection only once. */
internal class OrdinaryDestinationSelection {
    class Ticket internal constructor(val preference: OrdinaryDestinationPreference, val folderGrantId: String?) {
        internal var admitting = false
    }
    private var current: Ticket? = null
    val busy: Boolean get() = current != null
    fun begin(preference: OrdinaryDestinationPreference, folderGrantId: String? = null): Ticket? {
        if (busy) return null
        return Ticket(preference, folderGrantId).also { current = it }
    }
    fun choose(ticket: Ticket): Boolean {
        if (current !== ticket || ticket.admitting) return false
        ticket.admitting = true
        return true
    }
    fun active(ticket: Ticket): Boolean = current === ticket
    fun finish(ticket: Ticket): Boolean {
        if (current !== ticket || !ticket.admitting) return false
        current = null
        return true
    }
    fun cancel(ticket: Ticket): Boolean {
        if (current !== ticket) return false
        current = null
        return true
    }
    fun cancel() { current = null }
}

internal fun automaticDestination(preference: OrdinaryDestinationPreference): OrdinaryDestinationChoice? = when (preference) {
    OrdinaryDestinationPreference.APP_LIBRARY -> OrdinaryDestinationChoice.APP_LIBRARY
    OrdinaryDestinationPreference.ASK -> null
    OrdinaryDestinationPreference.ALBUM_COPY -> OrdinaryDestinationChoice.ALBUM_COPY
    OrdinaryDestinationPreference.FOLDER_COPY -> OrdinaryDestinationChoice.FOLDER_COPY
}
