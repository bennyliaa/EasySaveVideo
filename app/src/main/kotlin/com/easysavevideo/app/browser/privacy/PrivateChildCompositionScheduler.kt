package com.easysavevideo.app.browser.privacy

/**
 * Owner-thread scheduling only. Hierarchy/layout/focus notifications can request another child
 * composition, never acknowledge one. Only its actual applied SideEffect supplies the exact ticket.
 * Platform checks remain in [acknowledge]; this class creates no attachment or frame evidence.
 */
internal class PrivateChildCompositionScheduler(
    private val isCurrent: () -> Boolean,
    private val acknowledge: () -> Boolean,
    private val requestComposition: () -> Unit,
) {
    internal class Ticket internal constructor()
    var ticket = Ticket(); private set
    var acknowledged = false; private set
    private var firstLayoutObserved = false
    private var closed = false
    private fun live(expected: Ticket) = !closed && ticket === expected && isCurrent()

    fun childApplied(expected: Ticket): Boolean {
        if (!live(expected)) return false
        if (acknowledged) return true
        val accepted = try { acknowledge() } catch (_: Exception) { false }
        if (!accepted || !live(expected)) return false
        acknowledged = true
        return true
    }
    /** Real native events only invalidate/request. A later genuine child SideEffect must respond. */
    fun hierarchyChanged() {
        if (closed || !isCurrent()) return
        ticket = Ticket(); acknowledged = false
        requestComposition()
    }
    /** First layout is a bounded retry for subcomposition; repeated layouts cannot spin composition. */
    fun childLaidOut(expected: Ticket) {
        if (!live(expected) || firstLayoutObserved) return
        firstLayoutObserved = true
        if (!acknowledged) hierarchyChanged()
    }
    fun close() { closed = true; acknowledged = false; ticket = Ticket() }
}
