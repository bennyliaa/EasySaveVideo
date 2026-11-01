package com.easysavevideo.verification.local

import com.easysavevideo.discovery.VerificationOriginPolicy
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Per-verification DNS snapshot shared by robots and page. Every answer must be public; one is pinned. */
internal class LocalPublicDns(private val expectedHost: String, private val fixtureLoopbackOnly: Boolean,
    private val fence: () -> Unit,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }) : Dns {
    private var pinned: List<InetAddress>? = null
    private var attempted = false
    @Synchronized override fun lookup(hostname: String): List<InetAddress> {
        fence()
        if (hostname != expectedHost) throw UnknownHostException("ESV_ADDRESS_DENIED")
        pinned?.let { return it }
        if (attempted) throw UnknownHostException("ESV_ADDRESS_DENIED")
        attempted = true
        val addresses = resolve(hostname)
        fence()
        if (addresses.isEmpty() || addresses.size > 64 || addresses.any {
            if (fixtureLoopbackOnly) !it.isLoopbackAddress else !VerificationOriginPolicy.publicAddress(it.address)
        }) throw UnknownHostException("ESV_ADDRESS_DENIED")
        return listOf(addresses.first()).also { pinned = it }
    }
}
