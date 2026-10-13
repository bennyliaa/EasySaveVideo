package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.DiscoveryState
import com.easysavevideo.discovery.DiscoveryStatus
import com.easysavevideo.discovery.ProviderDiscoveryState

/** One explicit next-page intent, never a queue or a request for another search/view. */
internal class DiscoveryPageDemand {
    private data class Request(val target: DiscoveryControlTarget, val from: Int, val to: Int, val lifecycle: Long)
    private var request: Request? = null

    fun begin(target: DiscoveryControlTarget, from: Int, lifecycle: Long = 0): Boolean {
        if (request != null || from < 0 || from == Int.MAX_VALUE) return false
        request = Request(target, from, from + 1, lifecycle)
        return true
    }

    fun targetPage(target: DiscoveryControlTarget?, currentPage: Int, lifecycle: Long = 0): Int? {
        val pending = request ?: return null
        if (pending.target != target || pending.from != currentPage || pending.lifecycle != lifecycle) {
            cancel()
            return null
        }
        return pending.to
    }

    fun fulfill(target: DiscoveryControlTarget?, currentPage: Int, status: DiscoveryStatus,
        allowed: Boolean, lifecycle: Long = 0, stillCurrent: () -> Boolean = { true }, materialize: (Int) -> Boolean): Int? {
        val next = targetPage(target, currentPage, lifecycle) ?: return null
        if (!allowed) return null
        if (!stillCurrent()) { cancel(); return null }
        if (status.availableCount > 0) {
            if (materialize(next)) { cancel(); return next }
        } else if (DiscoveryPagerPolicy.roundSettled(status)) cancel()
        return null
    }

    fun cancel() { request = null }
}

internal object DiscoveryPagerPolicy {
    fun sourcesExhausted(status: DiscoveryStatus?): Boolean = status != null &&
        status.availableCount == 0 && status.providers.isNotEmpty() &&
        status.providers.all { it.state == ProviderDiscoveryState.EXHAUSTED }

    fun canAdvance(pageIndex: Int, materializedPages: Int, status: DiscoveryStatus?): Boolean =
        pageIndex >= 0 && pageIndex < materializedPages &&
            (pageIndex < materializedPages - 1 || (status != null &&
                (status.availableCount > 0 || (!sourcesExhausted(status) && status.state in setOf(
                    DiscoveryState.FILLING, DiscoveryState.BUFFERED, DiscoveryState.BACKOFF, DiscoveryState.EXHAUSTED)))))

    /** EXHAUSTED can also mean the bounded request round ended, not all sources ended. */
    fun roundSettled(status: DiscoveryStatus): Boolean = status.state !in setOf(
        DiscoveryState.FILLING, DiscoveryState.BACKOFF, DiscoveryState.SUSPENDED)
}
