package android.net
import android.os.Handler
class Network(val id: Int) { override fun equals(other: Any?) = other is Network && other.id == id; override fun hashCode() = id }
class NetworkCapabilities(vararg capabilities: Int) {
    private val values = capabilities.toSet()
    fun hasCapability(value: Int) = value in values
    companion object { const val NET_CAPABILITY_INTERNET = 12; const val NET_CAPABILITY_VALIDATED = 16
        const val NET_CAPABILITY_NOT_SUSPENDED = 21; const val NET_CAPABILITY_NOT_METERED = 11 }
}
open class ConnectivityManager {
    open class NetworkCallback {
        open fun onAvailable(network: Network) {}
        open fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {}
        open fun onBlockedStatusChanged(network: Network, blocked: Boolean) {}
        open fun onLost(network: Network) {}
    }
    open fun registerDefaultNetworkCallback(callback: NetworkCallback,handler: Handler) {}
    open fun unregisterNetworkCallback(callback: NetworkCallback) {}
}
