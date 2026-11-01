package com.easysavevideo.app.save

import com.easysavevideo.discovery.*

/** Explicit debug operator run. No persistence, URL inference or automatic browsing enrollment. */
internal class DebugSaveEvaluationOwner(private val enabled: Boolean, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var generation = 0L
    private var session: DiscoverySaveEvaluationSession? = null
    private val startedPages = mutableSetOf<String>()
    private var fixture = false
    private var navigation: Navigation? = null
    private var sealed: SaveEvaluationSessionReport? = null
    private class Navigation(val request: Any, val view: Any, val pageId: String, val expires: Long,
        val identity: Any = Any(), var starts: Int = 0, var document: Long? = null, var lease: EvaluationPageLease? = null)
    internal class EvaluationPageLease internal constructor(internal val owner: DebugSaveEvaluationOwner,
        internal val navigationIdentity: Any, internal val session: DiscoverySaveEvaluationSession, internal val pageId: String) {
        fun current() = owner.current(this)
        override fun toString() = "EvaluationPageLease([redacted])"
    }
    @Synchronized fun begin(syntheticFixture: Boolean, mode: SaveEvaluationMode = SaveEvaluationMode.JOINT): Boolean {
        if (!enabled || session != null) return false
        generation++; fixture = syntheticFixture; sealed = null; navigation = null; startedPages.clear()
        session = DiscoverySaveEvaluationSession(if (fixture) SaveEvaluationEvidence.SYNTHETIC_FIXTURE else SaveEvaluationEvidence.OBSERVED_SESSION, mode).also { it.begin() }
        return true
    }
    @Synchronized fun active() = enabled && session != null
    /** Exact host-created request and view; candidate comes directly from the explicit current card action. */
    @Synchronized fun opened(candidate: PendingVideoCandidate, syntheticFixture: Boolean, request: Any, view: Any): Boolean {
        invalidate()
        val run = session ?: return false
        if (!enabled || fixture != syntheticFixture) return false
        return try {
            run.register(candidate)
            navigation = Navigation(request, view, candidate.candidateId, now() + 120_000)
            true
        } catch (_: Exception) { false }
    }
    /** Conservative initial-load policy: exactly one start; redirects and subsequent starts revoke. */
    @Synchronized fun documentStarted(request: Any?, view: Any) {
        val n = navigation ?: return
        if (n.view === view && (n.request !== request || n.document != null || ++n.starts > 1)) invalidate()
    }
    @Synchronized fun documentFinished(request: Any, view: Any, document: Long) {
        val n = liveNavigation() ?: return
        if (n.request !== request || n.view !== view || n.document != null || n.starts != 1) return
        n.document = document
    }
    /** Called only by the explicit Save inspection click on a loaded evaluated document. */
    @Synchronized fun beginPage(request: Any, view: Any, document: Long): EvaluationPageLease? {
        val n = liveNavigation() ?: return null
        val run = session ?: return null
        if (n.request !== request || n.view !== view || n.document != document) return null
        return n.lease ?: try {
            if (n.pageId !in startedPages) { run.beginPage(n.pageId); startedPages.add(n.pageId) }
            EvaluationPageLease(this, n.identity, run, n.pageId).also { n.lease = it }
        } catch (_: Exception) { null }
    }
    @Synchronized fun documentInvalidated(view: Any) { if (navigation?.view === view && navigation?.document != null) invalidate() }
    @Synchronized fun viewRemoved(view: Any) { if (navigation?.view === view) invalidate() }
    @Synchronized fun prune() { liveNavigation() }
    @Synchronized fun invalidate() { navigation = null }
    @Synchronized private fun liveNavigation(): Navigation? {
        val n = navigation ?: return null
        if (now() >= n.expires) { invalidate(); return null }
        return n
    }
    @Synchronized private fun current(lease: EvaluationPageLease): Boolean = enabled && session === lease.session &&
        liveNavigation()?.identity === lease.navigationIdentity
    @Synchronized fun snapshot(): SaveEvaluationSessionReport? = session?.snapshot() ?: sealed
    fun finish(close: (DiscoverySaveEvaluationSession) -> SaveEvaluationSessionReport): SaveEvaluationSessionReport? {
        val run = synchronized(this) {
            invalidate()
            val current = session ?: return sealed
            session = null; startedPages.clear()
            current to generation
        }
        // Never call the task binder while holding this owner lock (acceptance reads the lease).
        val report = close(run.first)
        synchronized(this) { if (session == null && generation == run.second) sealed = report }
        return report
    }
    override fun toString() = "DebugSaveEvaluationOwner([redacted])"
}
