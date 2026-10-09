package com.easysavevideo.commons

import com.easysavevideo.discovery.DiscoveryCancellation

/** Compiled policy port only. No remote value can supply code, a host, or query authority. */
interface CommonsMaintenance {
    fun acquire(cancellation: DiscoveryCancellation): CommonsMaintenanceLease
}
interface CommonsMaintenanceLease : AutoCloseable {
    val limits: CommonsLimits
    val cacheNamespace: String
    fun check()
    fun succeeded(checkBeforePublish: () -> Unit)
    /** Called only after transport cleanup. Unrelated/configuration/cancellation errors are skipped. */
    fun failed(code: CommonsError, structureFailure: Boolean, checkBeforePublish: () -> Unit) {}
}

/** Neither a package nor local caller can expand the other's established budget. */
internal fun shrinkLimits(local: CommonsLimits, managed: CommonsLimits) = CommonsLimits(
    pageSize = minOf(local.pageSize, managed.pageSize),
    maxResponseBytes = minOf(local.maxResponseBytes, managed.maxResponseBytes),
    deadlineMillis = minOf(local.deadlineMillis, managed.deadlineMillis),
    cacheTtlMillis = minOf(local.cacheTtlMillis, managed.cacheTtlMillis),
    cacheEntries = minOf(local.cacheEntries, managed.cacheEntries),
)
