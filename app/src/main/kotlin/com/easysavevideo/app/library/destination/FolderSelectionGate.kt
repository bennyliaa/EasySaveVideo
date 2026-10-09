package com.easysavevideo.app.library.destination

/** Process-memory correlation only. Tickets are never serialized or reconstructed from a URI. */
internal class FolderSelectionGate {
    class Ticket internal constructor(val owner: Any) { internal var delivered = false }
    private var current: Ticket? = null
    @Synchronized fun begin(owner: Any): Ticket? = if (current != null) null else Ticket(owner).also { current = it }
    @Synchronized fun receive(ticket: Ticket): Boolean {
        if (current !== ticket || ticket.delivered) return false
        ticket.delivered = true
        return true
    }
    @Synchronized fun active(ticket: Ticket): Boolean = current === ticket
    /** The bounded durable preference commit and invalidation have one order. Provider IO is outside this lock. */
    @Synchronized fun commit(ticket: Ticket, action: () -> Unit): Boolean {
        if (current !== ticket || !ticket.delivered) return false
        action()
        current = null
        return true
    }
    @Synchronized fun cancel(ticket: Ticket): Boolean {
        if (current !== ticket) return false
        current = null
        return true
    }
    @Synchronized fun detach(owner: Any): Boolean {
        if (current?.owner !== owner) return false
        current = null
        return true
    }
}
