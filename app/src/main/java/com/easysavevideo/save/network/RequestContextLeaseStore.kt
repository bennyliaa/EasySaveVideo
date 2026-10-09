package com.easysavevideo.save.network

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.save.RequestContextRef
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.SourceUrl
import java.net.URI
import java.util.UUID

/** Short-lived, memory-only headers for the single resource the user selected. Never a CookieManager. */
class RequestContextLeaseStore(private val clock: EpochClock = EpochClock(System::currentTimeMillis)) {
    private class Lease(val resource: String, val expires: Long, val headers: Map<String, String>) {
        override fun toString() = "RequestContextLease([redacted])"
    }
    private val leases = linkedMapOf<String, Lease>()

    /** Caller may capture cookies only after explicit selection and a same-origin page/resource check. */
    @Synchronized
    fun put(url: SourceUrl, cookie: String? = null, userAgent: String? = null,
            ttlMillis: Long = 60_000, authorization: String? = null): RequestContextRef {
        if (ttlMillis !in 1..120_000) throw SaveFailure(SaveError.INVALID_REQUEST)
        val now = clock.nowMillis()
        leases.entries.removeAll { it.value.expires <= now }
        if (leases.size >= 32 || now > Long.MAX_VALUE - ttlMillis) throw SaveFailure(SaveError.BUSY)
        val headers = linkedMapOf<String, String>()
        fun add(name: String, value: String?, max: Int) {
            if (value == null) return
            if (value.isEmpty() || value.length > max || value.any { it < ' ' || it == '\u007f' })
                throw SaveFailure(SaveError.INVALID_REQUEST)
            headers[name] = value
        }
        add("Cookie", cookie, 16_384)
        add("Authorization", authorization, 8192)
        add("User-Agent", userAgent, 2048)
        val ref = RequestContextRef(UUID.randomUUID().toString())
        leases[ref.leaseId] = Lease(HttpsRequestPolicy.canonical(URI(url.value)), now + ttlMillis, headers.toMap())
        return ref
    }

    /** A lease never follows a redirect, even to another path on the same origin. */
    @Synchronized
    internal fun headers(ref: RequestContextRef?, selected: URI, target: URI, firstHop: Boolean): Map<String, String> {
        if (ref == null || !firstHop) return emptyMap()
        val lease = leases[ref.leaseId] ?: throw SaveFailure(SaveError.AUTHENTICATION_REQUIRED)
        if (clock.nowMillis() >= lease.expires) {
            leases.remove(ref.leaseId)
            throw SaveFailure(SaveError.AUTHENTICATION_REQUIRED)
        }
        if (lease.resource != HttpsRequestPolicy.canonical(selected) ||
            lease.resource != HttpsRequestPolicy.canonical(target) ||
            HttpsRequestPolicy.origin(selected) != HttpsRequestPolicy.origin(target))
            throw SaveFailure(SaveError.POLICY_BLOCKED)
        return lease.headers.toMap()
    }

    @Synchronized fun revoke(ref: RequestContextRef) { leases.remove(ref.leaseId) }
    @Synchronized fun clear() { leases.clear() }
    override fun toString() = "RequestContextLeaseStore([redacted])"
}
