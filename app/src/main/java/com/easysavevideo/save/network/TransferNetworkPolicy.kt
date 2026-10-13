package com.easysavevideo.save.network

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URLConnection

/**
 * Acquire one stable route per request/redirect, before any connection is opened. Implementations
 * must fail closed with WIFI_REQUIRED when the configured network condition cannot be established.
 * No implementation may bind the whole process or silently fall back to a different network.
 */
fun interface TransferNetworkPolicy {
    fun acquire(): TransferNetworkBinding
}

/**
 * A request-scoped route shared by URLConnection, DNS, raw sockets and the transfer watchdog.
 * check() is fast, thread-safe and throws WIFI_REQUIRED if this route is no longer approved. An
 * enabled policy must retain the selected network identity, not just ask whether any Wi-Fi exists.
 * Network-bound implementations must use that same network for all three I/O methods, preserve
 * Proxy.NO_PROXY, and check before and after their platform calls. They must never fall back.
 */
interface TransferNetworkBinding {
    fun check()
    fun openConnection(uri: URI): URLConnection
    fun resolve(host: String): Array<InetAddress>
    fun createSocket(): Socket
}

/** The historical system/default-route behavior, retained when no network restriction is supplied. */
object UnrestrictedTransferNetworkPolicy : TransferNetworkPolicy {
    override fun acquire(): TransferNetworkBinding = UnrestrictedTransferNetworkBinding
}

object UnrestrictedTransferNetworkBinding : TransferNetworkBinding {
    override fun check() = Unit
    override fun openConnection(uri: URI): URLConnection = uri.toURL().openConnection(Proxy.NO_PROXY)
    override fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
    override fun createSocket(): Socket = Socket()
}

/** A broken/unavailable policy reader cannot accidentally grant permission or kill the watchdog. */
internal fun TransferNetworkBinding.failureOrNull(): SaveError? = try {
    check()
    null
} catch (failure: SaveFailure) {
    failure.code
} catch (_: Exception) {
    SaveError.WIFI_REQUIRED
} catch (_: LinkageError) {
    SaveError.WIFI_REQUIRED
}

internal fun TransferNetworkBinding.requireApproved() {
    failureOrNull()?.let { throw SaveFailure(it) }
}

internal fun TransferNetworkPolicy.acquireApproved(): TransferNetworkBinding = try {
    acquire().also { it.requireApproved() }
} catch (failure: SaveFailure) {
    throw SaveFailure(failure.code)
} catch (_: Exception) {
    throw SaveFailure(SaveError.WIFI_REQUIRED)
} catch (_: LinkageError) {
    throw SaveFailure(SaveError.WIFI_REQUIRED)
}

/** Preserve native-release failures even when a platform wraps the original sanitized exception. */
internal fun Throwable.releaseFailureOrNull(): SaveError? {
    var current: Throwable? = this
    repeat(8) {
        val value = current ?: return null
        if (value is SaveFailure && value.code in setOf(SaveError.RECOVERY_REQUIRED, SaveError.RESOURCE_RELEASE_UNCONFIRMED))
            return value.code
        current = value.cause?.takeUnless { it === value }
    }
    return null
}
