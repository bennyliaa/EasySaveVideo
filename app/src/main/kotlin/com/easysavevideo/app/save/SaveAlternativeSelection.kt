package com.easysavevideo.app.save

import com.easysavevideo.save.SaveFailedIdentity

/**
 * Main-thread lifetime only: one reviewed failed identity can accompany the next explicit
 * inspection. The caller reviews eligibility before [arm] and supplies actual page freshness.
 * This owner holds no proposal, preparation authority, task command or resource lease.
 */
internal class SaveAlternativeSelection {
    internal class Ticket internal constructor(
        val failed: SaveFailedIdentity,
        val generation: Long,
        internal val expiresAt: Long
    ) {
        override fun toString() = "SaveAlternativeSelection.Ticket([redacted])"
    }

    internal class Association internal constructor(
        val failed: SaveFailedIdentity,
        val generation: Long,
        val expiresAt: Long
    ) {
        override fun toString() = "SaveAlternativeSelection.Association([redacted])"
    }

    private class Pending(val failed: SaveFailedIdentity, val expiresAt: Long)
    private class Bound(val selection: Any, val association: Association)

    private var generation = 0L
    private var pending: Pending? = null
    private var inspecting: Ticket? = null
    private var bound: Bound? = null

    /** Called only after accepted navigation for an eligible failure; replaces the old intent. */
    fun arm(failed: SaveFailedIdentity, expiresAt: Long) {
        clear()
        pending = Pending(failed, expiresAt)
    }

    /**
     * Must run for every explicit inspection, including ordinary inspections with no intent.
     * Consumes pending before asynchronous work, so failure cannot pass it to another inspection.
     */
    fun beginInspection(now: Long): Ticket? {
        val intent = pending
        clear()
        generation += 1
        if (intent == null || now >= intent.expiresAt) return null
        return Ticket(intent.failed, generation, intent.expiresAt).also { inspecting = it }
    }

    /** Only the exact current ticket can bind once, to the exact fresh selection object. */
    fun bind(ticket: Ticket, selection: Any, now: Long, current: Boolean): Boolean {
        if (inspecting !== ticket) return false
        inspecting = null
        if (!current || now >= ticket.expiresAt) return false
        bound = Bound(selection, Association(ticket.failed, ticket.generation, ticket.expiresAt))
        return true
    }

    /**
     * A stale caller cannot retire a newer selection. A matching expired or invalid page is
     * retired permanently; a later valid-looking callback cannot resurrect it.
     */
    fun association(selection: Any, now: Long, current: Boolean): Association? {
        val existing = bound ?: return null
        if (existing.selection !== selection) return null
        if (!current || now >= existing.association.expiresAt) {
            bound = null
            return null
        }
        return existing.association
    }

    /** Dismiss, background, selecting Direct and invalid delivery retire UI intent only. */
    fun clear() {
        pending = null
        inspecting = null
        bound = null
    }
}

internal enum class SaveAlternativeInspectionDelivery { ORDINARY, ALTERNATIVE, REJECTED }

/** An expired or retired alternative inspection must never become an ordinary Stream attempt. */
internal fun resolveAlternativeInspection(
    owner: SaveAlternativeSelection,
    ticket: SaveAlternativeSelection.Ticket?,
    selection: Any,
    now: Long,
    current: Boolean
): SaveAlternativeInspectionDelivery = when {
    ticket == null -> if (current) SaveAlternativeInspectionDelivery.ORDINARY
        else SaveAlternativeInspectionDelivery.REJECTED
    owner.bind(ticket, selection, now, current) -> SaveAlternativeInspectionDelivery.ALTERNATIVE
    else -> SaveAlternativeInspectionDelivery.REJECTED
}
