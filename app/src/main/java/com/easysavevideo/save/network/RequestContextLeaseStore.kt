package com.easysavevideo.save.network

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.save.RequestContextRef
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.SelectedMediaResource
import com.easysavevideo.save.SourceUrl
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Memory-only source permission supplied by the actual app session/owner. This must be a short,
 * nonblocking, thread-safe read, not a source identifier or a substitute for owner registration.
 */
fun interface RequestContextLiveAuthority {
    fun isLive(): Boolean
}

/** Short-lived, memory-only headers for the single resource the user selected. Never a CookieManager. */
class RequestContextLeaseStore(private val clock: EpochClock = EpochClock(System::currentTimeMillis)) {
    private class Lease(val resource: String, val expires: Long, var headers: Map<String, String>,
                        val authority: RequestContextAuthority?) {
        var issued = false
        override fun toString() = "RequestContextLease([redacted])"
    }
    private val leases = linkedMapOf<String, Lease>()

    /** Caller must register the real private work owner before issuing a private request context. */
    fun put(url: SourceUrl, cookie: String? = null, userAgent: String? = null,
            ttlMillis: Long = 60_000, authorization: String? = null,
            liveAuthority: RequestContextLiveAuthority? = null): RequestContextRef {
        if (ttlMillis !in 1..120_000) throw SaveFailure(SaveError.INVALID_REQUEST)
        val authority = liveAuthority?.let { RequestContextAuthority(it::isLive) }
        authority?.check() // Never call an external liveness reader under the registry lock.
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
        val resource = HttpsRequestPolicy.canonical(URI(url.value))
        return synchronized(this) {
            val now = clock.nowMillis()
            // Expiry ends admission/credential capture, never certifies transport drain. Retain an
            // issued private fence so explicit revoke/clear still reaches already captured owners.
            leases.entries.removeAll {
                if (it.value.expires > now) false else {
                    it.value.headers = emptyMap()
                    !it.value.issued || it.value.authority == null
                }
            }
            if (leases.size >= 32 || now > Long.MAX_VALUE - ttlMillis) throw SaveFailure(SaveError.BUSY)
            val ref = RequestContextRef(UUID.randomUUID().toString())
            leases[ref.leaseId] = Lease(resource, now + ttlMillis, headers.toMap(), authority)
            ref
        }
    }

    /**
     * Called once at Direct attempt/HLS session admission. Private seeds and bound tasks both
     * require actual app authority. The captured fence survives header TTL until revoke/release.
     */
    internal fun captureAuthority(resource: SelectedMediaResource): RequestContextAuthority? {
        val privateSource = resource.privateSource
        val ref = resource.requestContextRef
        if (ref == null) {
            if (privateSource != null) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            return null
        }
        val authority = synchronized(this) {
            val lease = leases[ref.leaseId]
            if (lease?.authority == null) {
                if (privateSource != null) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
                return null
            }
            if (clock.nowMillis() >= lease.expires) {
                lease.headers = emptyMap()
                throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            }
            if (lease.resource != HttpsRequestPolicy.canonical(URI(resource.url.value)))
                throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            lease.issued = true
            lease.authority
        }
        authority.check()
        return if (privateSource == null) authority else RequestContextAuthority {
            authority.check()
            privateSource.isLive()
        }.also { it.check() }
    }

    /** Credentials never follow a redirect, even to another path on the same origin. */
    internal fun headers(ref: RequestContextRef?, selected: URI, target: URI, firstHop: Boolean): Map<String, String> {
        if (ref == null || !firstHop) return emptyMap()
        val lease = synchronized(this) {
            val entry = leases[ref.leaseId] ?: throw SaveFailure(SaveError.AUTHENTICATION_REQUIRED)
            if (clock.nowMillis() >= entry.expires) {
                entry.headers = emptyMap()
                if (!entry.issued || entry.authority == null) leases.remove(ref.leaseId)
                throw SaveFailure(SaveError.AUTHENTICATION_REQUIRED)
            }
            if (entry.resource != HttpsRequestPolicy.canonical(selected) ||
                entry.resource != HttpsRequestPolicy.canonical(target) ||
                HttpsRequestPolicy.origin(selected) != HttpsRequestPolicy.origin(target))
                throw SaveFailure(SaveError.POLICY_BLOCKED)
            entry
        }
        lease.authority?.check()
        val result = synchronized(this) {
            if (leases[ref.leaseId] !== lease || clock.nowMillis() >= lease.expires)
                throw SaveFailure(SaveError.AUTHENTICATION_REQUIRED)
            lease.headers.toMap()
        }
        lease.authority?.check()
        return result
    }

    /** Revocation fences private operations; only their real owners may acknowledge release. */
    @Synchronized fun revoke(ref: RequestContextRef) {
        leases.remove(ref.leaseId)?.let { it.authority?.revoke(); it.headers = emptyMap() }
    }
    @Synchronized fun clear() {
        leases.values.forEach { it.authority?.revoke(); it.headers = emptyMap() }
        leases.clear()
    }
    override fun toString() = "RequestContextLeaseStore([redacted])"
}

/** A false or throwing liveness reader is latched forever. No source/header data enters failures. */
internal class RequestContextAuthority(private val live: () -> Boolean) {
    private val revoked = AtomicBoolean(false)
    fun revoke() { revoked.set(true) }
    fun failureOrNull(): SaveError? {
        if (revoked.get()) return SaveError.SOURCE_RESELECTION_REQUIRED
        val valid = try { live() } catch (_: Exception) { false } catch (_: LinkageError) { false }
        if (!valid) revoked.set(true)
        return if (revoked.get()) SaveError.SOURCE_RESELECTION_REQUIRED else null
    }
    fun check() { failureOrNull()?.let { throw SaveFailure(it) } }

    /** The same fence reaches factory/DNS/socket checks, before HTTP can expose any headers. */
    fun guard(network: TransferNetworkBinding): TransferNetworkBinding = object : TransferNetworkBinding by network {
        override fun check() { this@RequestContextAuthority.check(); network.check(); this@RequestContextAuthority.check() }
        override fun openConnection(uri: URI) = network.openConnection(uri.also { check() })
        override fun resolve(host: String) = network.resolve(host.also { check() })
        override fun createSocket(): java.net.Socket { check(); return network.createSocket() }
    }
    override fun toString() = "RequestContextAuthority([redacted])"
}
