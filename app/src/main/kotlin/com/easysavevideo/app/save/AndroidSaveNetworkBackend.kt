package com.easysavevideo.app.save

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.easysavevideo.save.network.TransferNetworkBinding
import com.easysavevideo.save.network.UnrestrictedTransferNetworkPolicy
import java.net.Proxy
import java.net.URI

/** Read-only OS access using the already admitted ACCESS_NETWORK_STATE permission.
 * Active requests recheck on their bounded transport watchdog; no background poller or global
 * process binding is installed. Wi-Fi-bound DNS, URLConnection and raw sockets share one Network.
 */
internal class AndroidSaveNetworkBackend(context: Context) : SaveNetworkBackend<Network> {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    // The public API returns false for unsupported transport indexes. Probe the bounded long-bit
    // domain rather than using the hidden getTransportTypes API or assuming only known names exist.
    @SuppressLint("WrongConstant")
    override fun current(): SaveDefaultNetwork<Network>? { return try {
        val manager = connectivity ?: return null
        val first = manager.activeNetwork
        val caps = first?.let(manager::getNetworkCapabilities)
        if (first == null || caps == null || manager.activeNetwork != first) null
        else SaveDefaultNetwork(first,
            isSaveWifiRoute(reportedSaveTransports { caps.hasTransport(it) }, NetworkCapabilities.TRANSPORT_WIFI,
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)))
    } catch (_: Exception) { null }
    }
    override fun bind(network: Network): TransferNetworkBinding = object : TransferNetworkBinding {
        override fun check() = Unit // Policy wrapper checks default identity and capabilities.
        override fun openConnection(uri: URI) = network.openConnection(uri.toURL(), Proxy.NO_PROXY)
        override fun resolve(host: String) = network.getAllByName(host)
        override fun createSocket() = network.socketFactory.createSocket()
    }
    override fun unrestricted() = UnrestrictedTransferNetworkPolicy.acquire()
}
