package com.easysavevideo.app.save

import com.easysavevideo.core.model.SaveSettings
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.network.TransferNetworkBinding
import com.easysavevideo.save.network.TransferNetworkPolicy
import java.net.URI

/** Public hasTransport probes a bounded bit domain; no hidden transport-list API or reflection. */
internal fun reportedSaveTransports(hasTransport: (Int) -> Boolean): Set<Int> =
    (0 until java.lang.Long.SIZE).filterTo(linkedSetOf(), hasTransport)

internal fun isSaveWifiRoute(transports: Set<Int>, wifiTransport: Int, internet: Boolean, suspended: Boolean): Boolean =
    transports == setOf(wifiTransport) && internet && !suspended

/** No network identity is persisted or exposed. A snapshot is about the current DEFAULT route. */
internal data class SaveDefaultNetwork<N : Any>(val network: N, val wifi: Boolean)
internal interface SaveNetworkBackend<N : Any> {
    fun current(): SaveDefaultNetwork<N>?
    fun bind(network: N): TransferNetworkBinding
    fun unrestricted(): TransferNetworkBinding
}

/** The setting is unknown until a successful preference load or an explicit user choice. */
internal class SaveWifiNetworkPolicy<N : Any>(private val backend: SaveNetworkBackend<N>) : TransferNetworkPolicy {
    @Volatile private var settings: SaveSettings? = null
    @Synchronized fun update(value: SaveSettings) { settings = value }
    /** An application-scoped explicit choice wins over a recreated Activity's stale disk load. */
    @Synchronized fun initialize(value: SaveSettings): SaveSettings {
        if (settings == null) settings = value
        return requireNotNull(settings)
    }
    val initialized: Boolean get() = settings != null
    fun checkAdmission() { acquire().check() }
    override fun acquire(): TransferNetworkBinding {
        val configured = settings ?: blocked()
        val network = if (configured.wifiOnly) currentWifi() else null
        val delegate = if (network == null) backend.unrestricted() else backend.bind(network)
        return object : TransferNetworkBinding {
            override fun check() = try {
                val latest = settings ?: blocked()
                // Enabling the restriction retires an old unpinned connection, even if the
                // current route happens to be Wi-Fi: the old socket may still be cellular.
                if (latest.wifiOnly && (network == null || currentWifi() != network)) blocked()
                delegate.check()
            } catch (failure: SaveFailure) { throw failure }
              catch (_: Exception) { blocked() }
              catch (_: LinkageError) { blocked() }
            override fun openConnection(uri: URI): java.net.URLConnection {
                check()
                val connection = delegate.openConnection(uri)
                try { check(); return connection } catch (failure: Exception) {
                    try { (connection as? java.net.HttpURLConnection)?.disconnect() }
                    catch (_: Exception) { throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
                    catch (_: LinkageError) { throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
                    throw failure
                }
            }
            override fun resolve(host: String) = checked { delegate.resolve(host) }
            override fun createSocket() = run {
                check()
                val socket = delegate.createSocket()
                try { check(); socket } catch (failure: Exception) {
                    try { socket.close() } catch (_: Exception) { throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
                    catch (_: LinkageError) { throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
                    throw failure
                }
            }
            private fun <T> checked(action: () -> T): T { check(); return action().also { check() } }
        }.also { it.check() }
    }
    private fun currentWifi(): N = try {
        backend.current()?.takeIf { it.wifi }?.network ?: blocked()
    } catch (_: Exception) { blocked() }
      catch (_: LinkageError) { blocked() }
    private fun blocked(): Nothing = throw SaveFailure(SaveError.WIFI_REQUIRED)
}
